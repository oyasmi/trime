/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.work.WorkInfo
import com.osfans.trime.R
import com.osfans.trime.data.voice.VoiceModelManager
import com.osfans.trime.data.voice.VoiceModelState
import com.osfans.trime.data.voice.VoicePrefs

/**
 * Minimal model management dialog: start a download for the currently-selected model variant,
 * cancel one that's running, or delete the one that's installed. (Local-file import isn't
 * implemented yet — that needs a SAF `ActivityResultLauncher` registered on the hosting Fragment,
 * not a standalone dialog; see doc/voice-input-implementation-plan.md T2.)
 */
object VoiceModelDialog {
    fun show(
        context: Context,
        prefs: VoicePrefs,
        work: WorkInfo?,
        onChanged: () -> Unit,
    ) {
        val variant = prefs.modelVariant.getValue()
        val state = VoiceModelManager.statusOf(context, variant, work)

        val builder =
            AlertDialog
                .Builder(context)
                .setTitle(R.string.voice_model)
                .setMessage(describeStatus(context, state))

        when {
            state is VoiceModelState.Ready ->
                builder.setNegativeButton(R.string.voice_model_delete) { _, _ ->
                    VoiceModelManager.delete(context, variant)
                    onChanged()
                }
            VoiceModelManager.isDownloading(work) ->
                builder.setNegativeButton(R.string.voice_model_download_cancel) { _, _ ->
                    VoiceModelManager.cancelDownload(context)
                    onChanged()
                }
            else -> {
                val label =
                    if (state is VoiceModelState.Failed) {
                        R.string.voice_model_download_retry
                    } else {
                        R.string.voice_model_download
                    }
                builder.setPositiveButton(label) { _, _ ->
                    VoiceModelManager.enqueueDownload(context, variant, prefs.effectiveDownloadUrls(variant))
                    Toast
                        .makeText(context, R.string.voice_model_download_started, Toast.LENGTH_SHORT)
                        .show()
                    onChanged()
                }
            }
        }
        builder.setNeutralButton(android.R.string.cancel, null)
        builder.show()
    }

    fun describeStatus(
        context: Context,
        state: VoiceModelState,
    ): String = when (state) {
        is VoiceModelState.NotInstalled ->
            context.getString(R.string.voice_model_status_not_installed)
        is VoiceModelState.Downloading ->
            context.getString(R.string.voice_model_status_downloading, state.percent)
        is VoiceModelState.Extracting ->
            context.getString(R.string.voice_model_status_extracting)
        is VoiceModelState.Ready ->
            context.getString(R.string.voice_model_status_ready, state.readableSize)
        is VoiceModelState.Invalid ->
            context.getString(R.string.voice_model_status_invalid, state.reason)
        is VoiceModelState.Failed ->
            if (state.reason.isBlank()) {
                context.getString(R.string.voice_model_status_failed_unknown)
            } else {
                context.getString(R.string.voice_model_status_failed, state.reason)
            }
    }
}
