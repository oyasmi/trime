/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice.ui

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.transition.Slide
import androidx.transition.TransitionManager
import com.osfans.trime.R
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.ime.voice.VoiceSessionState
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent
import android.view.ContextThemeWrapper as AndroidContextThemeWrapper

/**
 * The recording overlay: a full-size, **touch-transparent** layer added to `InputView` (not a
 * `BoardWindow` — switching windows would send `ACTION_CANCEL` to the key being held; see
 * doc/voice-input-design.md §5.7 / D7).
 *
 * Owns no session logic; it only renders whatever [VoiceSessionState] it's given via [render].
 */
class VoiceOverlayUi(
    override val di: DI,
) : DIAware {
    private val context: AndroidContextThemeWrapper by instance()

    private val waveform = WaveformView(context)

    private val statusText =
        context.textView {
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.WHITE)
        }

    val root =
        context.frameLayout {
            isVisible = false
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

            add(waveform, lParams(matchParent, matchParent))
            add(
                statusText,
                lParams(WRAP_CONTENT, WRAP_CONTENT) {
                    gravity = Gravity.CENTER
                },
            )
        }

    init {
        applyColors()
    }

    private fun applyColors() {
        val backgroundColor = runCatching { ColorManager.getColor("keyboard_back_color") }.getOrElse { Color.BLACK }
        val backgroundDrawable =
            runCatching { ColorManager.getDecorDrawable("keyboard_background") }.getOrNull()
        if (backgroundDrawable != null) {
            root.background = backgroundDrawable
        } else {
            root.setBackgroundColor(backgroundColor)
        }

        val candidateColors =
            listOf("key_text_color", "hilited_key_text_color", "candidate_text_color", "hilited_candidate_text_color")
                .mapNotNull { key -> runCatching { ColorManager.getColor(key) }.getOrNull() }
        val lineColor =
            candidateColors.firstOrNull { ColorUtils.calculateContrast(it, backgroundColor) >= 2.5 }
                ?: candidateColors.firstOrNull()
                ?: Color.WHITE
        waveform.setWaveformColor(lineColor)
        statusText.setTextColor(lineColor)
    }

    fun render(state: VoiceSessionState) {
        when (state) {
            is VoiceSessionState.Idle -> hide()
            is VoiceSessionState.Recording -> {
                show()
                waveform.start()
                waveform.updateAmplitude(state.amplitude)
                statusText.text =
                    context.getString(
                        if (state.cancelling) R.string.voice_state_release_to_cancel else R.string.voice_state_listening,
                    )
            }
            is VoiceSessionState.Recognizing -> {
                show()
                waveform.stop()
                statusText.text = context.getString(R.string.voice_state_recognizing)
            }
            is VoiceSessionState.Correcting -> {
                show()
                waveform.stop()
                statusText.text = context.getString(R.string.voice_state_correcting)
            }
            // Errors are surfaced as a Toast by the caller and are immediately followed by an
            // Idle transition; the overlay doesn't need to render them (see §9's failure matrix).
            is VoiceSessionState.Error -> Unit
        }
    }

    private fun show() {
        if (root.isVisible) return
        val transition = Slide(Gravity.BOTTOM).apply {
            addTarget(root)
            duration = 100
        }
        (root.parent as? android.view.ViewGroup)?.let {
            runCatching { TransitionManager.beginDelayedTransition(it, transition) }
        }
        root.isVisible = true
    }

    private fun hide() {
        if (!root.isVisible) return
        waveform.stop()
        val transition = Slide(Gravity.BOTTOM).apply {
            addTarget(root)
            duration = 100
        }
        (root.parent as? android.view.ViewGroup)?.let {
            runCatching { TransitionManager.beginDelayedTransition(it, transition) }
        }
        root.isVisible = false
    }
}
