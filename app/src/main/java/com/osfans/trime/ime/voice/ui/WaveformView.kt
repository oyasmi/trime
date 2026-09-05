/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import androidx.annotation.ColorInt
import splitties.dimensions.dp
import kotlin.math.max

/**
 * A small scrolling-bars waveform, driven by [updateAmplitude] (expects roughly 0..1, throttled
 * upstream by `AudioRecorder` to ~20fps — this view just draws whatever it's given).
 *
 * Deliberately a plain first-party `View` rather than a `SurfaceView`-backed renderer: at ~20fps
 * on a small overlay, a single `invalidate()` per amplitude update is plenty, and it avoids
 * pulling in a whole separate render-thread widget for what is a decorative element.
 */
class WaveformView(
    context: Context,
) : View(context) {
    // 14 bars fit the 72dp slot in the status strip (14 * 4.5 - 2 = 61dp) and cut the
    // per-frame draw cost by more than half. See doc/voice-input-feedback-design.md §4.3.
    private val barCount = 14
    private val history = FloatArray(barCount)
    private var writeIndex = 0
    private var running = false

    private val barPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
    private val barWidthPx = dp(2.5f)
    private val barGapPx = dp(2).toFloat()

    fun setWaveformColor(
        @ColorInt color: Int,
    ) {
        barPaint.color = color
        invalidate()
    }

    fun start() {
        running = true
        history.fill(0f)
        writeIndex = 0
        invalidate()
    }

    fun stop() {
        running = false
        history.fill(0f)
        invalidate()
    }

    fun updateAmplitude(amplitude: Float) {
        if (!running) return
        history[writeIndex] = amplitude.coerceIn(0f, 1f)
        writeIndex = (writeIndex + 1) % barCount
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!running) return
        val centerY = height / 2f
        // Both are half-heights: a bar spans centerY ± value. The old dp(4) headroom was
        // sized for a full-screen overlay; in a 16dp slot it flattens the waveform to 8dp.
        val minBarHeight = dp(1).toFloat()
        val maxBarHeight = height / 2f - dp(1)
        val step = barWidthPx + barGapPx
        val totalWidth = barCount * step - barGapPx
        var x = (width - totalWidth) / 2f

        for (i in 0 until barCount) {
            // Oldest sample is at writeIndex (about to be overwritten next); draw left-to-right
            // from oldest to newest so bars appear to scroll in from the right.
            val amplitude = history[(writeIndex + i) % barCount]
            val barHeight = max(minBarHeight, amplitude * maxBarHeight)
            val radius = barWidthPx / 2f
            canvas.drawRoundRect(
                x,
                centerY - barHeight,
                x + barWidthPx,
                centerY + barHeight,
                radius,
                radius,
                barPaint,
            )
            x += step
        }
    }
}
