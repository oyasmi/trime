/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.voice

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Extracts the two files a SenseVoice model archive actually needs
 * ([VoiceModelVariant.MODEL_FILE_NAME] and [VoiceModelVariant.TOKENS_FILE_NAME]) out of the
 * official sherpa-onnx `.tar.bz2` release archive (or a `.zip` re-pack of the same layout),
 * flattening away the archive's own top-level directory and discarding everything else
 * (README, LICENSE, test_wavs/, …).
 *
 * Because only the entry's *file name* (not its path) is ever used to decide the destination —
 * every matched file always lands at `destDir/<fileName>` — a malicious `../` path inside an
 * entry can never write outside [destDir]: there is no path traversal to protect against in the
 * first place. [ExtractionException] paths still verify this via a canonical-path assertion, as
 * a regression guard rather than because it's load-bearing.
 */
object VoiceModelArchive {
    private val WANTED_NAMES = setOf(VoiceModelVariant.MODEL_FILE_NAME, VoiceModelVariant.TOKENS_FILE_NAME)

    class ExtractionException(
        message: String,
    ) : IOException(message)

    fun extract(
        source: File,
        destDir: File,
    ) {
        val found = mutableSetOf<String>()
        destDir.mkdirs()
        openEntries(source) { name, input ->
            val fileName = name.substringAfterLast('/')
            // fileName is always one of the two fixed WANTED_NAMES constants here (no "/" or
            // ".." components survive substringAfterLast), so File(destDir, fileName) can never
            // resolve outside destDir — but assert it anyway as a regression guard.
            if (fileName in WANTED_NAMES) {
                val target = File(destDir, fileName)
                check(target.canonicalFile.parentFile == destDir.canonicalFile) {
                    "Refusing to extract outside destination: $fileName"
                }
                val tmp = File(destDir, "$fileName.part")
                tmp.outputStream().use { output -> input.copyTo(output) }
                tmp.renameTo(target)
                found += fileName
            }
        }
        val missing = WANTED_NAMES - found
        if (missing.isNotEmpty()) {
            throw ExtractionException("Archive is missing required file(s): ${missing.joinToString()}")
        }
    }

    /** Iterates entries of a `.tar.bz2`/`.tbz2` or `.zip` file, calling [onEntry] for each regular file. */
    private fun openEntries(
        source: File,
        onEntry: (name: String, input: InputStream) -> Unit,
    ) {
        val lowerName = source.name.lowercase()
        when {
            lowerName.endsWith(".zip") ->
                ZipInputStream(source.inputStream()).use { zip ->
                    generateSequence { zip.nextEntry }.forEach { entry ->
                        if (!entry.isDirectory) onEntry(entry.name, zip)
                    }
                }

            lowerName.endsWith(".tar.bz2") || lowerName.endsWith(".tbz2") ->
                TarArchiveInputStream(BZip2CompressorInputStream(source.inputStream())).use { tar ->
                    generateSequence { tar.nextEntry }.forEach { entry ->
                        if (!entry.isDirectory) onEntry(entry.name, tar)
                    }
                }

            else -> throw ExtractionException("Unsupported archive format: ${source.name}")
        }
    }
}
