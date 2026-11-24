/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.Manifest
import android.content.pm.PackageManager
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.voice.SenseVoiceEngine
import com.osfans.trime.data.voice.VoiceCorrectionPrompt
import com.osfans.trime.data.voice.VoiceModelManager
import com.osfans.trime.data.voice.VoiceModelVariant
import com.osfans.trime.data.voice.VoicePrefs
import com.osfans.trime.data.voice.VoiceTriggerMode
import com.osfans.trime.data.voice.llm.LlmConfig
import com.osfans.trime.data.voice.llm.LlmCorrector
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.voice.ui.VoiceOverlayUi
import com.osfans.trime.util.AppUtils
import com.osfans.trime.util.toast
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import splitties.dimensions.dp

/**
 * Wires the voice input feature into the keyboard: gates on readiness (enabled? permission?
 * model installed? not a password field?), drives one [VoiceSession] at a time, and forwards its
 * state to [overlay].
 *
 * Deliberately lazy: while `voice__enabled` is off, nothing here — engine, recorder, audio
 * focus, correction client — is ever constructed (see doc/voice-input-design.md G3). Registered
 * as an [InputBroadcastReceiver] purely to learn the current field's `inputType` (password
 * fields disable voice input) and to know when to cancel an in-flight session.
 */
class VoiceInputDelegate(
    override val di: DI,
) : DIAware,
    InputBroadcastReceiver {
    private val context: ContextThemeWrapper by instance()
    private val service: TrimeInputMethodService by instance()

    private val prefs: VoicePrefs = AppPrefs.defaultInstance().voice

    private val engine by lazy { SenseVoiceEngine(context.applicationContext) }
    private val recorder by lazy { AudioRecorder() }
    private val audioFocus by lazy { AudioFocusGuard() }
    private val llmCorrector by lazy { LlmCorrector() }
    private val overlay by lazy { VoiceOverlayUi(di) }

    /** The overlay's root view — added to `InputView`'s layout, see InputView.kt. */
    val root get() = overlay.root

    /** Restyles the status strip after a color-scheme switch — see `InputView.refreshColors`. */
    fun refreshColors() = overlay.refreshColors()

    private var session: VoiceSession? = null
    private var idleUnloadLoopStarted = false
    private var isPasswordField = false
    private var cancellingBySlide = false

    override fun onStartInput(info: EditorInfo) {
        // A new input session starting (new field, new app, keyboard reshown, …) always means
        // any previous recording is stale — discard it. There's no separate onFinishInputView
        // broadcast to hook (adding one would mean touching TrimeInputMethodService, outside
        // this feature's allowed upstream-file budget; see doc/voice-input-implementation-plan.md
        // §0.5), and in practice a user can't dismiss the keyboard while still physically holding
        // the key down anyway.
        session?.cancel()
        isPasswordField = isPasswordInputType(info.inputType)
    }

    fun isHolding(): Boolean = session?.isBusy() == true

    /** `KeyView`'s long-press entry point (HOLD trigger mode only). */
    fun startHold() {
        if (prefs.triggerMode.getValue() != VoiceTriggerMode.HOLD) return
        tryStart()
    }

    /** Toolbar button / `KEYCODE_VOICE_ASSIST` entry point — always toggle semantics. */
    fun onVoiceActionTriggered(forceToggle: Boolean = false) {
        if (!forceToggle && prefs.triggerMode.getValue() == VoiceTriggerMode.HOLD) return
        val current = session
        if (current != null && current.isBusy()) {
            current.finishHold()
        } else {
            tryStart()
        }
    }

    /** `KeyView.onMove` while holding — `y` is pixels relative to the key, negative = upward. */
    fun onHoldMove(y: Float) {
        val current = session ?: return
        val enterPx = -context.dp(CANCEL_ENTER_DP)
        val exitPx = -context.dp(CANCEL_EXIT_DP)
        if (!cancellingBySlide && y < enterPx) {
            cancellingBySlide = true
            current.updateCancelling(true)
        } else if (cancellingBySlide && y > exitPx) {
            cancellingBySlide = false
            current.updateCancelling(false)
        }
    }

    /** `KeyView.onRelease` after a long press. */
    fun finishHold() {
        session?.finishHold()
    }

    /** `KeyView.onCancel`, losing focus, hiding the keyboard, etc. */
    fun cancelHoldIfRunning() {
        session?.cancel()
    }

    private fun tryStart() {
        if (!prefs.enabled.getValue()) return
        if (isPasswordField) {
            context.toast(R.string.voice_toast_password_field)
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            context.toast(R.string.voice_no_microphone_permission)
            AppUtils.launchMainToVoiceInput(context)
            return
        }
        val variant = prefs.modelVariant.getValue()
        if (!VoiceModelManager.isReady(context, variant)) {
            context.toast(R.string.voice_model_not_ready_error)
            return
        }

        val s = ensureSession()
        if (s.isBusy()) {
            context.toast(R.string.voice_toast_busy)
            return
        }
        cancellingBySlide = false
        startIdleUnloadLoopIfNeeded()
        s.start(buildConfig(variant))
    }

    private fun ensureSession(): VoiceSession = session ?: VoiceSession(
        scope = service.lifecycleScope,
        engine = engine,
        recorder = recorder,
        audioFocus = audioFocus,
        correct = ::correct,
        onStateChange = ::onStateChange,
        onResult = ::commit,
        onMaxDurationReached = { context.toast(R.string.voice_toast_max_duration_reached) },
    ).also { session = it }

    private fun onStateChange(state: VoiceSessionState) {
        overlay.render(state)
        if (state is VoiceSessionState.Error) {
            val messageRes =
                when (state.reason) {
                    ErrorReason.AudioFailure -> R.string.voice_toast_audio_failure
                    ErrorReason.EngineFailure -> R.string.voice_toast_engine_failure
                    ErrorReason.NothingRecognized -> R.string.voice_toast_nothing_recognized
                    ErrorReason.Busy -> R.string.voice_toast_busy
                }
            context.toast(messageRes)
        }
    }

    private fun startIdleUnloadLoopIfNeeded() {
        if (idleUnloadLoopStarted) return
        idleUnloadLoopStarted = true
        service.lifecycleScope.launch {
            engine.runIdleUnloadLoop { prefs.keepAliveMinutes.getValue() }
        }
    }

    private fun buildConfig(variant: VoiceModelVariant) = VoiceSession.Config(
        variant = variant,
        language = prefs.language.getValue().code,
        itn = prefs.itn.getValue(),
        numThreads = prefs.numThreads.getValue(),
        maxDurationMs = prefs.maxDurationSeconds.getValue() * 1000L,
        trimTrailingPunct = prefs.trimTrailingPunct.getValue(),
        llmEnabled = prefs.llmEnabled.getValue(),
        unloadImmediatelyAfter = prefs.keepAliveMinutes.getValue() == 0,
    )

    private suspend fun correct(text: String): String {
        val config =
            LlmConfig(
                baseUrl = prefs.llmBaseUrl.getValue(),
                apiKey = prefs.llmApiKey.getValue(),
                model = prefs.llmModel.getValue(),
                temperature = prefs.llmTemperature,
                maxTokens = LlmCorrector.DEFAULT_MAX_TOKENS,
                timeoutSeconds = prefs.llmTimeoutSeconds.getValue(),
                systemPrompt = VoiceCorrectionPrompt.resolve(context, prefs.llmPromptOverride.getValue()),
            )
        return llmCorrector.correct(config, text)
    }

    private fun commit(text: String) {
        service.commitText(text)
    }

    private fun isPasswordInputType(inputType: Int): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val cls = inputType and InputType.TYPE_MASK_CLASS
        return (
            cls == InputType.TYPE_CLASS_TEXT &&
                (
                    variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                        variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                        variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                    )
            ) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    companion object {
        private const val CANCEL_ENTER_DP = 48
        private const val CANCEL_EXIT_DP = 32
    }
}
