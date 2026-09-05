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
    private val barCount = 32
    private val history = FloatArray(barCount)
    private var writeIndex = 0
    private var running = false

    private val barPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
    private val barWidthPx = dp(3).toFloat()
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
        val minBarHeight = dp(2).toFloat()
        val maxBarHeight = height / 2f - dp(4)
        val step = barWidthPx + barGapPx
        val totalWidth = barCount * step - barGapPx
        var x = (width - totalWidth) / 2f

        for (i in 0 until barCount) {
            // Oldest sample is at writeIndex (about to be overwritten next); draw left-to-right
            // from oldest to newest so bars appear to scroll in from the right.
            val amplitude = history[(writeIndex + i) % barCount]
            val barHeight = max(minBarHeight, amplitude * maxBarHeight)
            canvas.drawRect(x, centerY - barHeight, x + barWidthPx, centerY + barHeight, barPaint)
            x += step
        }
    }
}
