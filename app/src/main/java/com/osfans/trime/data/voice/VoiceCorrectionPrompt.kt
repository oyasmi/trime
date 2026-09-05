/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.voice

import android.content.Context
import com.osfans.trime.R

/**
 * Resolves the AI correction system prompt: the user's override if they've set one, otherwise
 * the built-in prompt copied verbatim from voice-typer's `Resources/correction.md` — see
 * doc/voice-input-design.md D9.
 */
object VoiceCorrectionPrompt {
    fun resolve(
        context: Context,
        override: String,
    ): String {
        val trimmed = override.trim()
        if (trimmed.isNotEmpty()) return trimmed
        return context.resources.openRawResource(R.raw.voice_correction_prompt).bufferedReader().use { it.readText() }
    }
}
