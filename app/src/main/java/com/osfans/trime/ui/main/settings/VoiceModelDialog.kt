/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ui.main.settings

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.osfans.trime.R
import com.osfans.trime.data.voice.VoiceModelManager
import com.osfans.trime.data.voice.VoicePrefs

/**
 * Minimal model management dialog: start a download for the currently-selected model variant,
 * or delete the one that's installed. (Local-file import isn't implemented yet — that needs a
 * SAF `ActivityResultLauncher` registered on the hosting Fragment, not a standalone dialog; see
 * doc/voice-input-implementation-plan.md T2.)
 */
object VoiceModelDialog {
    fun show(
        context: Context,
        prefs: VoicePrefs,
        onChanged: () -> Unit,
    ) {
        val variant = prefs.modelVariant.getValue()
        val ready = VoiceModelManager.isReady(context, variant)

        val builder =
            AlertDialog
                .Builder(context)
                .setTitle(R.string.voice_model)
                .setMessage(describeStatus(context, prefs))

        if (ready) {
            builder.setNegativeButton(R.string.voice_model_delete) { _, _ ->
                VoiceModelManager.delete(context, variant)
                onChanged()
            }
        } else {
            builder.setPositiveButton(R.string.voice_model_download) { _, _ ->
                VoiceModelManager.enqueueDownload(context, variant, prefs.effectiveDownloadUrl(variant))
                onChanged()
            }
        }
        builder.setNeutralButton(android.R.string.cancel, null)
        builder.show()
    }

    fun describeStatus(
        context: Context,
        prefs: VoicePrefs,
    ): String {
        val variant = prefs.modelVariant.getValue()
        return when (val state = VoiceModelManager.status(context, variant)) {
            is com.osfans.trime.data.voice.VoiceModelState.NotInstalled ->
                context.getString(R.string.voice_model_status_not_installed)
            is com.osfans.trime.data.voice.VoiceModelState.Downloading ->
                context.getString(R.string.voice_model_status_downloading, state.percent)
            is com.osfans.trime.data.voice.VoiceModelState.Extracting ->
                context.getString(R.string.voice_model_status_extracting)
            is com.osfans.trime.data.voice.VoiceModelState.Ready ->
                context.getString(R.string.voice_model_status_ready, state.readableSize)
            is com.osfans.trime.data.voice.VoiceModelState.Invalid ->
                context.getString(R.string.voice_model_status_invalid, state.reason)
        }
    }
}
