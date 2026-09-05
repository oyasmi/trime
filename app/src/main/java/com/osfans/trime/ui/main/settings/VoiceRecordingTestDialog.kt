/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.content.Context
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import com.osfans.trime.R
import com.osfans.trime.data.voice.SenseVoiceEngine
import com.osfans.trime.data.voice.TextPostProcessor
import com.osfans.trime.data.voice.VoiceModelManager
import com.osfans.trime.data.voice.VoicePrefs
import com.osfans.trime.ime.voice.AudioRecorder
import kotlinx.coroutines.launch
import splitties.dimensions.dp

/**
 * A "hold to talk" tester that exercises the exact same recognition path the keyboard uses
 * (`AudioRecorder` → `SenseVoiceEngine` → `TextPostProcessor`), without touching the keyboard UI
 * at all. Useful both as a first-run sanity check and as a standing troubleshooting tool — see
 * doc/voice-input-implementation-plan.md T3.
 */
object VoiceRecordingTestDialog {
    fun show(
        scope: LifecycleCoroutineScope,
        context: Context,
        prefs: VoicePrefs,
    ) {
        val statusView = TextView(context).apply { text = context.getString(R.string.voice_recording_test_hint) }
        val holdButton = Button(context).apply { text = context.getString(R.string.voice_recording_test_hold) }
        val layout =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pad = dp(16)
                setPadding(pad, pad, pad, pad)
                addView(statusView)
                addView(holdButton)
            }

        val engine = SenseVoiceEngine(context.applicationContext)
        val recorder = AudioRecorder()

        holdButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    statusView.text = context.getString(R.string.voice_state_listening)
                    scope.launch {
                        val variant = prefs.modelVariant.getValue()
                        if (!VoiceModelManager.isReady(context, variant)) {
                            statusView.text = context.getString(R.string.voice_model_not_ready_error)
                            return@launch
                        }
                        val recordStartedAt = System.currentTimeMillis()
                        val samples =
                            recorder.record(
                                maxDurationMs = prefs.maxDurationSeconds.getValue() * 1000L,
                                onFirstSample = {},
                                onAmplitude = {},
                            )
                        if (samples == null) {
                            statusView.text = context.getString(R.string.voice_engine_error, "no audio")
                            return@launch
                        }
                        statusView.text = context.getString(R.string.voice_state_recognizing)
                        val loadStartedAt = System.currentTimeMillis()
                        val text =
                            try {
                                engine.decode(
                                    samples,
                                    variant,
                                    prefs.language.getValue().code,
                                    prefs.itn.getValue(),
                                    prefs.numThreads.getValue(),
                                )
                            } catch (t: Exception) {
                                statusView.text = context.getString(R.string.voice_engine_error, t.message ?: "?")
                                return@launch
                            }
                        val finishedAt = System.currentTimeMillis()
                        val cleaned = TextPostProcessor.process(text, prefs.trimTrailingPunct.getValue())
                        val loadAndInferMs = finishedAt - loadStartedAt
                        val recordMs = loadStartedAt - recordStartedAt
                        statusView.text =
                            buildString {
                                append(if (cleaned.isEmpty()) "(空)" else cleaned)
                                append('\n')
                                append(context.getString(R.string.voice_recording_test_infer_ms, loadAndInferMs))
                                append(" (+")
                                append(recordMs)
                                append("ms recording)")
                            }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    recorder.requestStop()
                    true
                }
                else -> false
            }
        }

        AlertDialog
            .Builder(context)
            .setTitle(R.string.voice_recording_test)
            .setView(layout)
            .setNegativeButton(android.R.string.cancel) { _, _ -> recorder.requestStop() }
            .setOnDismissListener {
                recorder.requestStop()
                scope.launch { engine.unloadNow() }
            }.show()
    }
}
