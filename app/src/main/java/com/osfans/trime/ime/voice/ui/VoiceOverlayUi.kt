/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import com.osfans.trime.R
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.voice.VoiceSessionState
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent
import android.view.ContextThemeWrapper as AndroidContextThemeWrapper

/**
 * The recording feedback: a **touch-transparent** status strip that sits at the top of
 * `keyboardView` and replaces the candidate bar in place while a voice session is running.
 *
 * It is deliberately *not* a `BoardWindow` — switching windows would send `ACTION_CANCEL` to the
 * key being held (see doc/voice-input-design.md §5.7 / D7) — and deliberately *not* a full-size
 * layer either: covering `InputView` (which spans the whole screen, so key popups can draw above
 * the keyboard) is what made the first version paint the entire display opaque. The strip stays
 * inside the keyboard, above every key row, where the user's finger can never reach it — the
 * cancel gesture only travels 48dp up from the space key. See
 * doc/voice-input-feedback-design.md.
 *
 * Owns no session logic; it only renders whatever [VoiceSessionState] it's given via [render].
 */
class VoiceOverlayUi(
    override val di: DI,
) : DIAware {
    private val context: AndroidContextThemeWrapper by instance()
    private val theme: Theme by instance()

    private val icon =
        context.imageView {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

    private val spinner =
        ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            isVisible = false
        }

    /** Fixed-width slot so swapping icon ⇄ spinner never shifts the text sideways. */
    private val iconSlot =
        context.frameLayout {
            add(icon, lParams(matchParent, matchParent))
            add(spinner, lParams(matchParent, matchParent))
        }

    private val waveform = WaveformView(context)

    private val statusText =
        context.textView {
            gravity = Gravity.CENTER
            textSize = 13f
            maxLines = 1
            // The only channel a TalkBack user has while physically holding the space key.
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }

    private val content =
        context.horizontalLayout {
            gravity = Gravity.CENTER
            add(
                iconSlot,
                lParams(dp(ICON_DP), dp(ICON_DP)) { gravity = Gravity.CENTER_VERTICAL },
            )
            add(
                waveform,
                lParams(dp(WAVEFORM_WIDTH_DP), dp(WAVEFORM_HEIGHT_DP)) {
                    gravity = Gravity.CENTER_VERTICAL
                    marginStart = dp(8)
                },
            )
            add(
                statusText,
                lParams(wrapContent, wrapContent) {
                    gravity = Gravity.CENTER_VERTICAL
                    marginStart = dp(10)
                },
            )
        }

    val root =
        context.frameLayout {
            // INVISIBLE rather than GONE: this is a child of `keyboardView`, and toggling GONE
            // would run a measure/layout pass on the keyboard while the user is holding a key
            // down. INVISIBLE keeps the layout completely still — only drawing changes.
            isInvisible = true
            alpha = 0f
            // Never make these true: "hold space to talk" needs the touch stream to keep
            // reaching the KeyView underneath (doc/voice-input-design.md D7).
            isClickable = false
            isFocusable = false
            // Only marks *this* view as unimportant; descendants (statusText's live region)
            // stay visible to accessibility services — that would be NO_HIDE_DESCENDANTS.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setPadding(dp(12), 0, dp(12), 0)
            add(content, lParams(wrapContent, wrapContent) { gravity = Gravity.CENTER })
        }

    private enum class Phase { LISTENING, CANCELLING, RECOGNIZING, CORRECTING }

    private var phase: Phase? = null
    private var shown = false

    @ColorInt private var accentColor = Color.WHITE

    @ColorInt private var textColor = Color.WHITE

    @ColorInt private var warningColor = WARNING_ON_DARK

    init {
        applyColors()
    }

    /**
     * Resolved once, at construction. A theme switch rebuilds the whole `InputView` — and with it
     * the DI container and this object — so there is nothing to invalidate. If `VoiceInputDelegate`
     * ever becomes a longer-lived singleton, this needs to be re-run on theme changes.
     */
    private fun applyColors() {
        val background = themeColor("keyboard_back_color") ?: Color.BLACK
        root.background = buildBackground(background)

        // The strip physically stands where the candidate bar does, so it takes its colors from
        // the candidate palette rather than the key palette.
        accentColor = pickForeground(background, ACCENT_KEYS, minContrast = 3.0)
        textColor = pickForeground(background, TEXT_KEYS, minContrast = 4.5)
        warningColor = pickWarningColor(background, textColor)

        waveform.setWaveformColor(accentColor)
        spinner.indeterminateTintList = ColorStateList.valueOf(accentColor)
    }

    private fun buildBackground(
        @ColorInt background: Int,
    ): Drawable {
        // Same decor parameters as InputBarDelegate's candidate bar, so the strip reads as a
        // replacement for that row rather than a floating panel.
        val decor =
            runCatching {
                ColorManager.getDecorDrawable(
                    "candidate_background",
                    "candidate_border_color",
                    context.dp(theme.generalStyle.candidateBorder),
                    context.dp(theme.generalStyle.candidateBorderRound),
                )
            }.getOrNull()
        // Opaque base underneath: `candidate_background` is transparent in some themes, and the
        // toolbar buttons behind the strip would otherwise show through.
        val base = ColorDrawable(background)
        return if (decor == null) base else LayerDrawable(arrayOf(base, decor))
    }

    @ColorInt
    private fun themeColor(key: String): Int? = runCatching { ColorManager.getColor(key) }.getOrNull()

    @ColorInt
    private fun pickForeground(
        @ColorInt background: Int,
        keys: List<String>,
        minContrast: Double,
    ): Int {
        val opaque = opaque(background)
        val candidates = keys.mapNotNull { themeColor(it) }
        if (candidates.isEmpty()) return onBackgroundFallback(opaque)
        return candidates.firstOrNull { ColorUtils.calculateContrast(it, opaque) >= minContrast }
            ?: candidates.maxByOrNull { ColorUtils.calculateContrast(it, opaque) }
            ?: onBackgroundFallback(opaque)
    }

    @ColorInt
    private fun pickWarningColor(
        @ColorInt background: Int,
        @ColorInt fallback: Int,
    ): Int {
        val opaque = opaque(background)
        val preferred =
            if (ColorUtils.calculateLuminance(opaque) > 0.5) WARNING_ON_LIGHT else WARNING_ON_DARK
        if (ColorUtils.calculateContrast(preferred, opaque) >= 3.0) return preferred
        // Unreadable red on this theme: walk it toward the (already legible) text color.
        var ratio = 0.2f
        while (ratio <= 1f) {
            val blended = ColorUtils.blendARGB(preferred, fallback, ratio)
            if (ColorUtils.calculateContrast(blended, opaque) >= 3.0) return blended
            ratio += 0.2f
        }
        return fallback
    }

    /**
     * `ColorUtils.calculateContrast` throws when the background is translucent, and trime themes
     * may legitimately specify `0xAARRGGBB` colors (see `util/ColorUtils.parseColor`) — so force
     * opacity before any contrast math.
     */
    @ColorInt
    private fun opaque(
        @ColorInt color: Int,
    ): Int = color or OPAQUE_MASK

    @ColorInt
    private fun onBackgroundFallback(
        @ColorInt background: Int,
    ): Int = if (ColorUtils.calculateLuminance(background) > 0.5) Color.BLACK else Color.WHITE

    fun render(state: VoiceSessionState) {
        // Errors are surfaced as a Toast by the caller and are immediately followed by another
        // state (Idle, or — for Busy — the restored ongoing state), so the strip ignores them
        // entirely and keeps whatever it was showing. See doc/voice-input-design.md §9.
        if (state is VoiceSessionState.Error) return
        when (state) {
            is VoiceSessionState.Idle -> hide()
            is VoiceSessionState.Recording -> {
                // `start()` clears the bar history, so it must run once on entry — not on every
                // amplitude frame (~20fps), which is what made the waveform look frozen before.
                if (show()) waveform.start()
                if (state.cancelling) {
                    applyPhase(Phase.CANCELLING)
                } else {
                    applyPhase(Phase.LISTENING)
                    waveform.updateAmplitude(state.amplitude)
                }
            }
            is VoiceSessionState.Recognizing -> {
                show()
                waveform.stop()
                applyPhase(Phase.RECOGNIZING)
            }
            is VoiceSessionState.Correcting -> {
                show()
                waveform.stop()
                applyPhase(Phase.CORRECTING)
            }
            is VoiceSessionState.Error -> Unit
        }
    }

    /** Idempotent: does nothing when [phase] is already current, so it's safe to call per frame. */
    private fun applyPhase(next: Phase) {
        if (phase == next) return
        phase = next

        val warn = next == Phase.CANCELLING
        val foreground = if (warn) warningColor else accentColor

        val iconRes =
            when (next) {
                Phase.LISTENING -> R.drawable.ic_baseline_mic_24
                Phase.CANCELLING -> R.drawable.ic_baseline_close_24
                Phase.RECOGNIZING, Phase.CORRECTING -> null
            }
        if (iconRes != null) icon.setImageResource(iconRes)
        icon.isVisible = iconRes != null
        icon.imageTintList = ColorStateList.valueOf(foreground)
        spinner.isVisible = iconRes == null

        waveform.isInvisible = next != Phase.LISTENING
        waveform.setWaveformColor(foreground)

        statusText.setText(
            when (next) {
                Phase.LISTENING -> R.string.voice_state_listening
                Phase.CANCELLING -> R.string.voice_state_release_to_cancel
                Phase.RECOGNIZING -> R.string.voice_state_recognizing
                Phase.CORRECTING -> R.string.voice_state_correcting
            },
        )
        statusText.setTextColor(if (warn) warningColor else textColor)
    }

    /** @return true if this call is what made the strip appear. */
    private fun show(): Boolean {
        if (shown) return false
        shown = true
        root.animate().cancel()
        root.isInvisible = false
        root.alpha = 0f
        root.translationY = -context.dp(ENTER_OFFSET_DP).toFloat()
        root
            .animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(SHOW_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
        return true
    }

    private fun hide() {
        if (!shown) return
        shown = false
        root.animate().cancel()
        root
            .animate()
            .alpha(0f)
            .setDuration(HIDE_DURATION_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                // Guarded: a session that restarts inside the fade-out must not be hidden again.
                if (!shown) {
                    root.isInvisible = true
                    waveform.stop()
                    phase = null
                }
            }.start()
    }

    companion object {
        private const val ICON_DP = 16
        private const val WAVEFORM_WIDTH_DP = 72
        private const val WAVEFORM_HEIGHT_DP = 16
        private const val ENTER_OFFSET_DP = 4
        private const val SHOW_DURATION_MS = 120L
        private const val HIDE_DURATION_MS = 90L

        private const val OPAQUE_MASK = 0xFF000000.toInt()
        private const val WARNING_ON_LIGHT = 0xFFD93025.toInt()
        private const val WARNING_ON_DARK = 0xFFFF6B6B.toInt()

        private val ACCENT_KEYS = listOf("hilited_candidate_text_color", "candidate_text_color")
        private val TEXT_KEYS = listOf("candidate_text_color", "hilited_candidate_text_color")
    }
}
