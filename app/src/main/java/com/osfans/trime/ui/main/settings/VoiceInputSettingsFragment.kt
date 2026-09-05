/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.os.Bundle
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.work.WorkInfo
import com.osfans.trime.R
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegateFragment
import com.osfans.trime.data.voice.VoiceCorrectionPrompt
import com.osfans.trime.data.voice.VoiceModelManager
import com.osfans.trime.util.addPreference
import kotlinx.coroutines.launch

class VoiceInputSettingsFragment : PreferenceDelegateFragment(AppPrefs.defaultInstance().voice) {
    private val prefs = AppPrefs.defaultInstance().voice
    private var modelPref: Preference? = null
    private var downloadWork: WorkInfo? = null

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        screen.findPreference<Preference>(prefs.llmApiKey.key)?.summaryProvider =
            Preference.SummaryProvider<Preference> { maskApiKey(prefs.llmApiKey.getValue()) }

        modelPref =
            Preference(screen.context).apply {
                title = getString(R.string.voice_model)
                setOnPreferenceClickListener {
                    VoiceModelDialog.show(requireContext(), prefs, downloadWork) { refreshModelSummary() }
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
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        // The download runs in WorkManager, so its progress and its failures only exist there —
        // observing it is what makes the model row show "Downloading… 12%" and, crucially, say
        // so when the download gives up instead of silently reading "not installed".
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                VoiceModelManager.workInfoFlow(requireContext()).collect { info ->
                    downloadWork = info
                    refreshModelSummary()
                }
            }
        }
    }

    private fun refreshModelSummary() {
        if (!isAdded) return
        val state = VoiceModelManager.statusOf(requireContext(), prefs.modelVariant.getValue(), downloadWork)
        modelPref?.summary = VoiceModelDialog.describeStatus(requireContext(), state)
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
