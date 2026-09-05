/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.voice

import androidx.annotation.StringRes
import com.osfans.trime.R
import com.osfans.trime.data.prefs.PreferenceDelegateEnum

/**
 * Local SenseVoice model variants offered by the built-in voice input feature.
 *
 * Both are published by the upstream sherpa-onnx project under its `asr-models` GitHub release:
 * https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models
 *
 * The two variants trade off differently: [V2025_09_09] is fine-tuned with extra Cantonese data
 * but, per upstream's own documentation, does not produce punctuation; [V2024_07_17] is the
 * original SenseVoice-Small and does produce punctuation when [supportsPunctuation]-gated ITN is
 * enabled. [V2024_07_17] is the default (see VoicePrefs) precisely because dictation without any
 * punctuation is a poor default experience.
 */
enum class VoiceModelVariant(
    @StringRes override val stringRes: Int,
    /** Sub-directory name under the app's external-files voice model root. */
    val dirName: String,
    val downloadUrl: String,
    val expectedBytes: Long,
    val sha256: String,
    val supportsPunctuation: Boolean,
) : PreferenceDelegateEnum {
    V2024_07_17(
        stringRes = R.string.voice_model_variant_2024_07_17,
        dirName = "2024-07-17",
        downloadUrl =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
        expectedBytes = 163_002_883L,
        sha256 = "7d1efa2138a65b0b488df37f8b89e3d91a60676e416f515b952358d83dfd347e",
        supportsPunctuation = true,
    ),
    V2025_09_09(
        stringRes = R.string.voice_model_variant_2025_09_09,
        dirName = "2025-09-09",
        downloadUrl =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2",
        expectedBytes = 165_783_878L,
        sha256 = "7305f7905bfcf77fa0b39388a313f3da35c68d971661a65475b56fb2162c8e63",
        supportsPunctuation = false,
    ),
    ;

    companion object {
        /** File names inside the archive; identical across both variants. */
        const val MODEL_FILE_NAME = "model.int8.onnx"
        const val TOKENS_FILE_NAME = "tokens.txt"
    }
}
