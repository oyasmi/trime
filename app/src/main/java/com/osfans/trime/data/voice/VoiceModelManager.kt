/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.voice

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Locates, validates and deletes the on-disk SenseVoice model. Downloading is done by
 * [VoiceModelDownloadWorker] (a WorkManager job the UI observes directly, rather than this class
 * duplicating WorkManager's own progress/state plumbing).
 *
 * The model lives under the app's external-files directory, never inside Rime's user data
 * directory: that directory is synced/backed up, and this is ~160-170MB — see
 * doc/voice-input-design.md §4.3.
 */
object VoiceModelManager {
    const val UNIQUE_WORK_NAME = "voice_model_download"

    fun modelDir(
        context: Context,
        variant: VoiceModelVariant,
    ): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "voice/sensevoice/${variant.dirName}")
    }

    /**
     * Synchronous, disk-only check — cheap enough to call every time the settings screen is
     * shown. Does not report `Downloading`/`Extracting`; the UI gets those from observing the
     * WorkManager job directly (see [UNIQUE_WORK_NAME]).
     */
    fun status(
        context: Context,
        variant: VoiceModelVariant,
    ): VoiceModelState {
        val dir = modelDir(context, variant)
        val model = File(dir, VoiceModelVariant.MODEL_FILE_NAME)
        val tokens = File(dir, VoiceModelVariant.TOKENS_FILE_NAME)
        if (!model.exists() || !tokens.exists()) return VoiceModelState.NotInstalled
        if (model.length() <= 0L) return VoiceModelState.Invalid("model file is empty")
        if (tokens.length() <= 0L) return VoiceModelState.Invalid("tokens file is empty")
        return VoiceModelState.Ready(readableSize(model.length() + tokens.length()))
    }

    fun isReady(
        context: Context,
        variant: VoiceModelVariant,
    ): Boolean = status(context, variant) is VoiceModelState.Ready

    fun delete(
        context: Context,
        variant: VoiceModelVariant,
    ): Boolean = modelDir(context, variant).deleteRecursively()

    /**
     * Extracts an already-downloaded/user-picked archive into place. Runs synchronously — call
     * this from a background dispatcher. A sha256 mismatch against [VoiceModelVariant.sha256] is
     * logged and returned as [ImportResult.shaMismatch] but does **not** block extraction: it
     * tolerates a locally re-packaged or future-updated archive, exactly like BiBi-Keyboard's
     * model integrity check does (see doc/voice-input-design.md §4.3).
     */
    fun importArchive(
        context: Context,
        variant: VoiceModelVariant,
        archive: File,
    ): ImportResult {
        val shaMismatch = sha256(archive) != variant.sha256
        val dir = modelDir(context, variant)
        VoiceModelArchive.extract(archive, dir)
        return ImportResult(shaMismatch)
    }

    data class ImportResult(
        val shaMismatch: Boolean,
    )

    fun enqueueDownload(
        context: Context,
        variant: VoiceModelVariant,
        url: String,
    ) {
        val request =
            OneTimeWorkRequestBuilder<VoiceModelDownloadWorker>()
                .setInputData(
                    Data
                        .Builder()
                        .putString(VoiceModelDownloadWorker.KEY_VARIANT, variant.name)
                        .putString(VoiceModelDownloadWorker.KEY_URL, url)
                        .build(),
                ).build()
        WorkManager
            .getInstance(context)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun cancelDownload(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readableSize(bytes: Long): String {
        val mb = bytes / 1024.0 / 1024.0
        return String.format(Locale.ROOT, "%.0f MB", mb.roundToInt().toDouble())
    }
}
