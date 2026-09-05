/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import com.osfans.trime.data.voice.RecognitionEngine
import com.osfans.trime.data.voice.SenseVoiceEngine
import com.osfans.trime.data.voice.TextPostProcessor
import com.osfans.trime.data.voice.VoiceModelVariant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Orchestrates one hold-to-talk (or tap-to-toggle) voice input attempt: record → recognize →
 * optionally correct → hand the final text to the caller. Readiness gating that depends on
 * Android state the session shouldn't need to know about — model installed?, microphone
 * permission?, is this a password field? — is the caller's job (see `VoiceInputDelegate`); by
 * the time [start] is called, this session assumes recording is allowed to proceed.
 *
 * Every external entry point is a no-op unless the session is in the state it expects, so
 * double-taps / stray callbacks after cancellation can never corrupt the state machine (see
 * doc/voice-input-design.md §5.2's invariants, exercised by `VoiceSessionTest`).
 */
class VoiceSession(
    private val scope: CoroutineScope,
    private val engine: RecognitionEngine,
    private val recorder: VoiceRecorder,
    private val audioFocus: AudioFocusController,
    /** Must never throw and must never return a blank string — see `LlmCorrector` for why. */
    private val correct: suspend (String) -> String,
    private val onStateChange: (VoiceSessionState) -> Unit,
    /** Final text to commit. Never called with a blank string. */
    private val onResult: (String) -> Unit,
    private val onMaxDurationReached: () -> Unit,
) {
    data class Config(
        val variant: VoiceModelVariant,
        val language: String,
        val itn: Boolean,
        val numThreads: Int,
        val maxDurationMs: Long,
        val trimTrailingPunct: Boolean,
        val llmEnabled: Boolean,
        val unloadImmediatelyAfter: Boolean,
    )

    /** Why a recording was discarded without ever reaching recognition. Internal to this class. */
    private sealed interface DiscardReason {
        data object Cancelled : DiscardReason

        data object TooShort : DiscardReason

        data object AudioFailure : DiscardReason

        /** Hit the configured cap without the user having released/cancelled — still recognized. */
        data object ReachedMax : DiscardReason
    }

    companion object {
        const val MIN_DURATION_MS = 300L
        const val RECOGNIZE_TIMEOUT_MS = 30_000L
    }

    /**
     * Pure decision function, split out for direct unit testing: given what
     * [AudioRecorder.record] returned, decide whether to proceed to recognition (`null`) or
     * discard and why.
     */
    private fun classifyRecordingOutcome(
        samples: FloatArray?,
        cancelRequested: Boolean,
        maxDurationMs: Long,
    ): DiscardReason? {
        if (cancelRequested) return DiscardReason.Cancelled
        if (samples == null) return DiscardReason.AudioFailure
        val durationMs = samples.size * 1000L / SenseVoiceEngine.SAMPLE_RATE
        if (durationMs < MIN_DURATION_MS) return DiscardReason.TooShort
        val expectedMaxSamples = (maxDurationMs * SenseVoiceEngine.SAMPLE_RATE / 1000L).toInt()
        if (samples.size >= expectedMaxSamples) return DiscardReason.ReachedMax
        return null
    }

    private var state: VoiceSessionState = VoiceSessionState.Idle
    private var sessionJob: Job? = null
    private var cancelRequested = false

    fun isBusy(): Boolean = state !is VoiceSessionState.Idle

    fun start(config: Config) {
        if (state !is VoiceSessionState.Idle) {
            onStateChange(VoiceSessionState.Error(ErrorReason.Busy))
            onStateChange(state) // restore the actual ongoing state; Busy is a transient toast
            return
        }
        cancelRequested = false
        setState(VoiceSessionState.Recording(amplitude = 0f, cancelling = false))

        sessionJob =
            scope.launch {
                audioFocus.acquire()
                val samples =
                    try {
                        recorder.record(
                            maxDurationMs = config.maxDurationMs,
                            onFirstSample = {},
                            onAmplitude = { amplitude ->
                                // Called from the recorder's own thread (see `VoiceRecorder`),
                                // so hop back onto [scope]'s dispatcher — the IME's main thread —
                                // before touching state, since `onStateChange` renders the
                                // overlay. Re-read the state inside the launch: by the time it
                                // runs the session may already have left Recording.
                                scope.launch {
                                    val current = state
                                    if (current is VoiceSessionState.Recording) {
                                        setState(current.copy(amplitude = amplitude))
                                    }
                                }
                            },
                        )
                    } finally {
                        audioFocus.release()
                    }

                when (val reason = classifyRecordingOutcome(samples, cancelRequested, config.maxDurationMs)) {
                    DiscardReason.Cancelled, DiscardReason.TooShort -> setState(VoiceSessionState.Idle)
                    DiscardReason.AudioFailure -> {
                        setState(VoiceSessionState.Error(ErrorReason.AudioFailure))
                        setState(VoiceSessionState.Idle)
                    }
                    DiscardReason.ReachedMax -> {
                        onMaxDurationReached()
                        recognizeAndMaybeCorrect(samples!!, config)
                    }
                    null -> recognizeAndMaybeCorrect(samples!!, config)
                }
            }
    }

    /** Slide-to-cancel: updates the visible "about to cancel" flag; doesn't stop recording. */
    fun updateCancelling(cancelling: Boolean) {
        val current = state
        if (current is VoiceSessionState.Recording && current.cancelling != cancelling) {
            setState(current.copy(cancelling = cancelling))
        }
    }

    /** Release: stop recording. If currently in the "about to cancel" state, discard on stop. */
    fun finishHold() {
        val current = state
        if (current !is VoiceSessionState.Recording) return
        cancelRequested = current.cancelling
        recorder.requestStop()
    }

    /** Explicit cancel (e.g. losing focus, hiding the keyboard) — always discards. */
    fun cancel() {
        if (state is VoiceSessionState.Idle) return
        cancelRequested = true
        recorder.requestStop()
        sessionJob?.cancel()
        sessionJob = null
        setState(VoiceSessionState.Idle)
    }

    private suspend fun recognizeAndMaybeCorrect(
        samples: FloatArray,
        config: Config,
    ) {
        setState(VoiceSessionState.Recognizing)
        val asrText =
            withTimeoutOrNull(RECOGNIZE_TIMEOUT_MS) {
                try {
                    engine.decode(samples, config.variant, config.language, config.itn, config.numThreads)
                } catch (t: Exception) {
                    Timber.w(t, "Voice recognition failed")
                    null
                }
            }
        if (config.unloadImmediatelyAfter) engine.unloadNow()

        if (asrText == null) {
            setState(VoiceSessionState.Error(ErrorReason.EngineFailure))
            setState(VoiceSessionState.Idle)
            return
        }

        val cleaned = TextPostProcessor.process(asrText, config.trimTrailingPunct)
        if (cleaned.isEmpty()) {
            setState(VoiceSessionState.Error(ErrorReason.NothingRecognized))
            setState(VoiceSessionState.Idle)
            return
        }

        val finalText =
            if (config.llmEnabled) {
                setState(VoiceSessionState.Correcting(cleaned))
                correct(cleaned)
            } else {
                cleaned
            }

        setState(VoiceSessionState.Idle)
        onResult(finalText)
    }

    private fun setState(newState: VoiceSessionState) {
        state = newState
        onStateChange(newState)
    }
}
