/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.voice

import androidx.annotation.StringRes
import com.osfans.trime.R
import com.osfans.trime.data.prefs.PreferenceDelegateEnum

/** Recognition language, passed straight through to sherpa-onnx's SenseVoice `language` field. */
enum class VoiceLanguage(
    @StringRes override val stringRes: Int,
    val code: String,
) : PreferenceDelegateEnum {
    AUTO(R.string.voice_language_auto, "auto"),
    ZH(R.string.voice_language_zh, "zh"),
    EN(R.string.voice_language_en, "en"),
    YUE(R.string.voice_language_yue, "yue"),
    JA(R.string.voice_language_ja, "ja"),
    KO(R.string.voice_language_ko, "ko"),
}
