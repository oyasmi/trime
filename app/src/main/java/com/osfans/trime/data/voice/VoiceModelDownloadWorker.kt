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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
        const val KEY_URLS = "urls"
        const val KEY_PERCENT = "percent"
        const val KEY_PHASE = "phase"
        const val KEY_ERROR = "error"

        const val PHASE_DOWNLOAD = 0
        const val PHASE_EXTRACT = 1

        /** Give up (and report the failure) after this many WorkManager attempts. */
        const val MAX_ATTEMPTS = 3

        /** Not in [HttpURLConnection]'s constants. */
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416

        private const val CONNECT_TIMEOUT_MS = 20_000

        /**
         * Generous on purpose: this is ~160MB over what is often a mobile link, and the old 15s
         * ceiling turned any momentary stall into an outright failure.
         */
        private const val READ_TIMEOUT_MS = 60_000
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val variantName = inputData.getString(KEY_VARIANT) ?: return@withContext Result.failure()
        val urls = inputData.getStringArray(KEY_URLS)?.toList().orEmpty()
        if (urls.isEmpty()) return@withContext Result.failure()
        val variant =
            runCatching { VoiceModelVariant.valueOf(variantName) }.getOrNull()
                ?: return@withContext Result.failure()

        val cacheFile = File(applicationContext.cacheDir, "voice_model_${variant.name}.part")

        // The two phases are kept apart on purpose: only a *download* failure is worth retrying.
        // Folding them together made an unpack error look like a network blip, so WorkManager
        // re-ran a download that had already succeeded and then failed it on a bogus resume.
        try {
            downloadFromAnyOf(urls, cacheFile, variant.expectedBytes)
        } catch (t: Exception) {
            return@withContext when {
                isStopped -> {
                    cacheFile.delete()
                    Result.failure()
                }
                runAttemptCount + 1 < MAX_ATTEMPTS -> {
                    // Keep the partial file: the next attempt resumes from it via `Range`.
                    Timber.w(t, "Voice model download failed, will retry (attempt ${runAttemptCount + 1})")
                    Result.retry()
                }
                else -> {
                    Timber.e(t, "Voice model download failed")
                    cacheFile.delete()
                    Result.failure(workDataOf(KEY_ERROR to describe(t)))
                }
            }
        }

        try {
            report(PHASE_EXTRACT, 0)
            if (VoiceModelManager.sha256(cacheFile) != variant.sha256) {
                // Every URL for a built-in variant (primary + mirrors) is meant to serve this
                // exact object, so a hash mismatch means a corrupt download, a wrong/instrumented
                // mirror, or a bad cross-source resume splice. Extracting it anyway would feed an
                // unknown model + tokens pair to the native loader; refuse and force a clean
                // re-download instead. (The user-picked "import archive" path in
                // VoiceModelManager stays lenient by design — there the user vouches for the file.)
                Timber.e("Voice model archive sha256 mismatch for ${variant.name}, refusing to install")
                cacheFile.delete()
                return@withContext Result.failure(
                    workDataOf(KEY_ERROR to "downloaded model failed its integrity check"),
                )
            }
            extract(cacheFile, variant)
            Result.success()
        } catch (t: Exception) {
            if (isStopped) {
                Result.failure()
            } else {
                Timber.e(t, "Voice model extraction failed")
                Result.failure(workDataOf(KEY_ERROR to describe(t)))
            }
        } finally {
            // Either it extracted or the archive is unusable; nothing here is worth resuming.
            cacheFile.delete()
        }
    }

    private fun describe(t: Throwable): String = t.message ?: t.javaClass.simpleName

    private suspend fun report(
        phase: Int,
        percent: Int,
    ) = setProgress(workDataOf(KEY_PHASE to phase, KEY_PERCENT to percent))

    /**
     * Unpacking is a tens-of-seconds job on its own, so it publishes progress too — and checks
     * [isStopped] as it goes, otherwise "cancel" wouldn't take effect until it had finished.
     *
     * The archive holds one ~240MB model file and one tiny token list, so the current entry's
     * own percentage is, to the eye, the percentage of the whole job.
     */
    private suspend fun extract(
        archive: File,
        variant: VoiceModelVariant,
    ) {
        var lastReportedPercent = -1
        val channel = Channel<Int>(Channel.CONFLATED)
        coroutineScope {
            val reporter =
                launch {
                    for (percent in channel) report(PHASE_EXTRACT, percent)
                }
            try {
                VoiceModelArchive.extract(
                    archive,
                    VoiceModelManager.modelDir(applicationContext, variant),
                ) { copied, entryBytes ->
                    if (isStopped) throw InterruptedIOException("cancelled")
                    if (entryBytes > 0) {
                        val percent = ((copied * 100) / entryBytes).toInt().coerceIn(0, 100)
                        if (percent != lastReportedPercent) {
                            lastReportedPercent = percent
                            channel.trySend(percent)
                        }
                    }
                }
            } finally {
                channel.close()
                reporter.join()
            }
        }
    }

    /**
     * Tries each URL in turn, keeping the last failure to rethrow if none of them work.
     *
     * A partial [dest] carries across sources: every URL in the list serves the same asset
     * byte-for-byte (the mirrors are plain GitHub reverse proxies), so a `Range` request to the
     * next one resumes the same file. Should that assumption ever break, the sha256 check after
     * the download is what notices.
     */
    private suspend fun downloadFromAnyOf(
        urls: List<String>,
        dest: File,
        expectedBytes: Long,
    ) {
        // A complete archive from an earlier run that failed to unpack — don't re-fetch 160MB.
        if (expectedBytes > 0 && dest.length() == expectedBytes) return

        var lastError: Exception? = null
        for (url in urls) {
            try {
                download(url, dest, expectedBytes)
                return
            } catch (e: IOException) {
                if (isStopped) throw e
                Timber.w(e, "Voice model source failed: ${url.substringBefore("://")}…${url.takeLast(24)}")
                lastError = e
            }
        }
        throw lastError ?: IOException("no download source")
    }

    private suspend fun download(
        url: String,
        dest: File,
        expectedBytes: Long,
    ) {
        // Resume from where a previous attempt left off — but never from a file that's already
        // as long as the whole archive: that asks for a range past the end and earns a 416.
        if (expectedBytes > 0 && dest.length() >= expectedBytes) dest.delete()
        var downloadedBytes = if (dest.exists()) dest.length() else 0L
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        if (downloadedBytes > 0) connection.setRequestProperty("Range", "bytes=$downloadedBytes-")

        connection.connect()
        val code = connection.responseCode
        if (code == HTTP_RANGE_NOT_SATISFIABLE) {
            // Our partial file no longer lines up with what the server has. Start clean.
            dest.delete()
            throw IOException("stale partial download (HTTP $code), restarting")
        }
        if (code !in 200..299) {
            throw IOException("HTTP $code ${connection.responseMessage.orEmpty()}".trim())
        }
        val supportsResume = downloadedBytes > 0 && code == HttpURLConnection.HTTP_PARTIAL
        if (downloadedBytes > 0 && !supportsResume) {
            downloadedBytes = 0L
        }
        val contentLength = connection.contentLengthLong.takeIf { it >= 0 }
        val totalBytes = contentLength?.plus(downloadedBytes) ?: expectedBytes.takeIf { it > 0 }

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
                            report(PHASE_DOWNLOAD, percent)
                        }
                    }
                }
            }
        }
    }
}
