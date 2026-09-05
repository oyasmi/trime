/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.voice

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Downloads a SenseVoice model archive (with HTTP `Range` resume) and extracts it in place.
 *
 * This is the app's *other* network call site besides `LlmCorrector` (see
 * doc/voice-input-design.md §8.2) — it only ever runs when the user explicitly starts a
 * download from the voice input settings screen.
 *
 * Deliberately **not** a foreground-service worker: that would require adding
 * `FOREGROUND_SERVICE`(+`_DATA_SYNC`) manifest permissions on top of the two this feature already
 * needs (RECORD_AUDIO, INTERNET), just to show a progress notification for what's normally a
 * one-off, settings-screen-visible download. Progress is still published via [setProgress] for
 * the settings screen (which is presumably what the user is looking at) to observe directly.
 */
class VoiceModelDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    companion object {
        const val KEY_VARIANT = "variant"
        const val KEY_URL = "url"
        const val KEY_PERCENT = "percent"
        const val KEY_ERROR = "error"

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 15_000
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val variantName = inputData.getString(KEY_VARIANT) ?: return@withContext Result.failure()
        val url = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val variant =
            runCatching { VoiceModelVariant.valueOf(variantName) }.getOrNull()
                ?: return@withContext Result.failure()

        val cacheFile = File(applicationContext.cacheDir, "voice_model_${variant.name}.part")
        try {
            download(url, cacheFile)
            if (VoiceModelManager.sha256(cacheFile) != variant.sha256) {
                Timber.w("Voice model archive sha256 mismatch for ${variant.name}, extracting anyway")
            }
            VoiceModelArchive.extract(cacheFile, VoiceModelManager.modelDir(applicationContext, variant))
            Result.success()
        } catch (t: Exception) {
            if (isStopped) {
                Result.failure()
            } else {
                Timber.e(t, "Voice model download/extract failed")
                Result.failure(workDataOf(KEY_ERROR to (t.message ?: t.javaClass.simpleName)))
            }
        } finally {
            cacheFile.delete()
        }
    }

    private suspend fun download(
        url: String,
        dest: File,
    ) {
        // Resume from where a previous attempt left off, if any.
        var downloadedBytes = if (dest.exists()) dest.length() else 0L
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        if (downloadedBytes > 0) connection.setRequestProperty("Range", "bytes=$downloadedBytes-")

        connection.connect()
        val supportsResume = downloadedBytes > 0 && connection.responseCode == HttpURLConnection.HTTP_PARTIAL
        if (downloadedBytes > 0 && !supportsResume) {
            downloadedBytes = 0L
        }
        val contentLength = connection.contentLengthLong.takeIf { it >= 0 }
        val totalBytes = contentLength?.plus(downloadedBytes)

        connection.inputStream.use { input ->
            FileOutputStream(dest, supportsResume).use { output ->
                val buffer = ByteArray(1 shl 16)
                var lastReportedPercent = -1
                while (true) {
                    if (isStopped) throw InterruptedIOException("cancelled")
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    downloadedBytes += n
                    if (totalBytes != null && totalBytes > 0) {
                        val percent = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                        if (percent != lastReportedPercent) {
                            lastReportedPercent = percent
                            setProgress(workDataOf(KEY_PERCENT to percent))
                        }
                    }
                }
            }
        }
    }
}
