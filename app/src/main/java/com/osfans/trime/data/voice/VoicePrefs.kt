/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.voice

import android.content.SharedPreferences
import com.osfans.trime.R
import com.osfans.trime.data.prefs.PreferenceDelegateOwner

/**
 * Preferences for the built-in local voice input feature (SenseVoice + optional AI correction).
 *
 * All keys share the `voice__` prefix. See `doc/voice-input-design.md` §7 for the rationale
 * behind each item; this class is intentionally the single place all of them are declared.
 */
class VoicePrefs(
    shared: SharedPreferences,
) : PreferenceDelegateOwner(shared, R.string.voice_input) {
    companion object {
        const val ENABLED = "voice__enabled"
        const val MODEL_VARIANT = "voice__model_variant"
        const val MODEL_URL_OVERRIDE = "voice__model_url_override"
        const val LANGUAGE = "voice__language"
        const val ITN = "voice__itn"
        const val NUM_THREADS = "voice__num_threads"
        const val KEEP_ALIVE_MINUTES = "voice__keep_alive_minutes"
        const val TRIGGER_MODE = "voice__trigger_mode"
        const val MAX_DURATION_SECONDS = "voice__max_duration_seconds"
        const val TRIM_TRAILING_PUNCT = "voice__trim_trailing_punct"
        const val LLM_ENABLED = "voice__llm_enabled"
        const val LLM_BASE_URL = "voice__llm_base_url"
        const val LLM_API_KEY = "voice__llm_api_key"
        const val LLM_MODEL = "voice__llm_model"
        const val LLM_TEMPERATURE_X10 = "voice__llm_temperature_x10"
        const val LLM_TIMEOUT_SECONDS = "voice__llm_timeout_seconds"
        const val LLM_PROMPT_OVERRIDE = "voice__llm_prompt_override"
    }

    /** Master switch. While off, no engine/thread/network is ever constructed (see G3). */
    val enabled = switch(R.string.voice_enabled, ENABLED, false, R.string.voice_enabled_summary)

    val modelVariant = enum(R.string.voice_model_variant, MODEL_VARIANT, VoiceModelVariant.V2024_07_17) {
        enabled.getValue()
    }

    /**
     * Overrides the built-in download URL for the currently selected [modelVariant]. Blank
     * (the default) means "use the built-in URL for whichever variant is selected" — this is
     * simpler than trying to keep a per-variant stored URL in sync with [modelVariant], and is
     * functionally equivalent for the user. See [effectiveDownloadUrls].
     */
    val modelUrlOverride = editText(
        R.string.voice_model_url_override,
        MODEL_URL_OVERRIDE,
        "",
        R.string.voice_model_url_override_summary,
    ) { enabled.getValue() }

    /**
     * The URLs to try, in order. A non-blank override wins outright — if the user named a source
     * they get that source and nothing else; otherwise the variant's built-in URL plus its
     * mirrors (see [VoiceModelVariant.downloadUrls]).
     */
    fun effectiveDownloadUrls(variant: VoiceModelVariant): List<String> = modelUrlOverride.getValue().trim().let { if (it.isEmpty()) variant.downloadUrls else listOf(it) }

    val language = enum(R.string.voice_language, LANGUAGE, VoiceLanguage.AUTO) { enabled.getValue() }

    val itn = switch(R.string.voice_itn, ITN, true, R.string.voice_itn_summary) { enabled.getValue() }

    val numThreads = int(
        R.string.voice_num_threads,
        NUM_THREADS,
        2,
        1,
        4,
        enableUiOn = { enabled.getValue() },
    )

    /**
     * `0` = unload right after each session; `-1` = never unload; else minutes of idle time.
     * (The title string itself explains these two special values — `int()` has no separate
     * summary slot.)
     */
    val keepAliveMinutes = int(
        R.string.voice_keep_alive_minutes,
        KEEP_ALIVE_MINUTES,
        5,
        -1,
        30,
        "min",
        enableUiOn = { enabled.getValue() },
    )

    val triggerMode = enum(R.string.voice_trigger_mode, TRIGGER_MODE, VoiceTriggerMode.HOLD) {
        enabled.getValue()
    }

    val maxDurationSeconds = int(
        R.string.voice_max_duration_seconds,
        MAX_DURATION_SECONDS,
        60,
        10,
        300,
        "s",
        enableUiOn = { enabled.getValue() },
    )

    val trimTrailingPunct = switch(
        R.string.voice_trim_trailing_punct,
        TRIM_TRAILING_PUNCT,
        false,
        enableUiOn = { enabled.getValue() },
    )

    /** AI correction: off by default. Off means zero network requests, ever (see G6). */
    val llmEnabled = switch(
        R.string.voice_llm_enabled,
        LLM_ENABLED,
        false,
        R.string.voice_llm_enabled_summary,
        enableUiOn = { enabled.getValue() },
    )

    private fun llmSubEnabled() = enabled.getValue() && llmEnabled.getValue()

    val llmBaseUrl = editText(R.string.voice_llm_base_url, LLM_BASE_URL, "") { llmSubEnabled() }

    /** Stored in plain SharedPreferences (not encrypted) — see doc/voice-input-design.md §8.3. */
    val llmApiKey = editText(R.string.voice_llm_api_key, LLM_API_KEY, "") { llmSubEnabled() }

    val llmModel = editText(R.string.voice_llm_model, LLM_MODEL, "gpt-4o-mini") { llmSubEnabled() }

    /** Stored ×10 (0..20) since the preference framework has no float seekbar; see [llmTemperature]. */
    val llmTemperatureX10 = int(
        R.string.voice_llm_temperature,
        LLM_TEMPERATURE_X10,
        0,
        0,
        20,
        enableUiOn = { llmSubEnabled() },
    )

    val llmTemperature: Float get() = llmTemperatureX10.getValue() / 10f

    val llmTimeoutSeconds = int(
        R.string.voice_llm_timeout_seconds,
        LLM_TIMEOUT_SECONDS,
        5,
        1,
        30,
        "s",
        enableUiOn = { llmSubEnabled() },
    )

    /** Blank means "use the built-in prompt" (`res/raw/voice_correction_prompt.md`). */
    val llmPromptOverride = editText(
        R.string.voice_llm_prompt_override,
        LLM_PROMPT_OVERRIDE,
        "",
        R.string.voice_llm_prompt_override_summary,
    ) { llmSubEnabled() }
}
