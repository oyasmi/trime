/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegateFragment
import com.osfans.trime.data.voice.VoiceCorrectionPrompt
import com.osfans.trime.util.addPreference
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class VoiceInputSettingsFragment : PreferenceDelegateFragment(AppPrefs.defaultInstance().voice) {
    private val prefs = AppPrefs.defaultInstance().voice
    private var modelPref: Preference? = null

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.findPreference<Preference>(prefs.llmApiKey.key)?.summaryProvider =
            Preference.SummaryProvider<Preference> { maskApiKey(prefs.llmApiKey.getValue()) }

        modelPref =
            Preference(screen.context).apply {
                title = getString(R.string.voice_model)
                setOnPreferenceClickListener {
                    VoiceModelDialog.show(requireContext(), prefs) { refreshModelSummary() }
                    true
                }
            }
        screen.addPreference(modelPref!!)

        screen.addPreference(R.string.voice_recording_test) {
            VoiceRecordingTestDialog.show(lifecycleScope, requireContext(), prefs)
        }
        screen.addPreference(R.string.voice_llm_test) {
            VoiceCorrectionTestDialog.show(lifecycleScope, requireContext(), prefs) {
                VoiceCorrectionPrompt.resolve(requireContext(), prefs.llmPromptOverride.getValue())
            }
        }
    }

    override fun onStart() {
        super.onStart()
        refreshModelSummary()
        // Cheap polling rather than wiring a full WorkManager LiveData observer here: this
        // screen is the only place the summary needs to stay live while a download runs.
        lifecycleScope.launch {
            while (true) {
                delay(1000)
                refreshModelSummary()
            }
        }
    }

    private fun refreshModelSummary() {
        if (!isAdded) return
        modelPref?.summary = VoiceModelDialog.describeStatus(requireContext(), prefs)
    }

    private fun maskApiKey(key: String): String {
        if (key.isBlank()) return getString(R.string.disable)
        return if (key.length <= 7) {
            "*".repeat(key.length)
        } else {
            key.take(3) + "*".repeat((key.length - 7).coerceAtLeast(0)) + key.takeLast(4)
        }
    }
}
