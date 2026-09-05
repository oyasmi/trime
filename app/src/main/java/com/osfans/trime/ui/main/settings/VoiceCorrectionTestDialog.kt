/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.content.Context
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import com.osfans.trime.R
import com.osfans.trime.data.voice.VoicePrefs
import com.osfans.trime.data.voice.llm.LlmConfig
import com.osfans.trime.data.voice.llm.LlmCorrector
import kotlinx.coroutines.launch
import splitties.dimensions.dp

/**
 * Runs one real round-trip against the configured AI correction endpoint on a fixed sample
 * sentence, so a misconfigured base URL / key / model is caught while editing settings rather
 * than while dictating. See doc/voice-input-design.md §5.6.
 */
object VoiceCorrectionTestDialog {
    fun show(
        scope: LifecycleCoroutineScope,
        context: Context,
        prefs: VoicePrefs,
        promptProvider: () -> String,
    ) {
        val sample = context.getString(R.string.voice_llm_test_sample)
        val resultView =
            TextView(context).apply {
                text = sample
                val pad = dp(16)
                setPadding(pad, pad, pad, pad)
            }

        val dialog =
            AlertDialog
                .Builder(context)
                .setTitle(R.string.voice_llm_test)
                .setView(resultView)
                .setPositiveButton(R.string.voice_llm_test, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        dialog.show()

        fun runTest() {
            resultView.text = context.getString(R.string.voice_llm_test_running)
            scope.launch {
                val config =
                    LlmConfig(
                        baseUrl = prefs.llmBaseUrl.getValue(),
                        apiKey = prefs.llmApiKey.getValue(),
                        model = prefs.llmModel.getValue(),
                        temperature = prefs.llmTemperature,
                        maxTokens = LlmCorrector.DEFAULT_MAX_TOKENS,
                        timeoutSeconds = prefs.llmTimeoutSeconds.getValue(),
                        systemPrompt = promptProvider(),
                    )
                val startedAt = System.currentTimeMillis()
                val result = LlmCorrector().test(config, sample)
                val elapsedMs = System.currentTimeMillis() - startedAt
                resultView.text =
                    result.fold(
                        onSuccess = { corrected ->
                            "$corrected\n\n" + context.getString(R.string.voice_llm_test_elapsed_ms, elapsedMs)
                        },
                        onFailure = { error ->
                            context.getString(R.string.voice_llm_test_error, error.message ?: error.toString())
                        },
                    )
            }
        }

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { runTest() }
        runTest()
    }
}
