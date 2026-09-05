/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.voice

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
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

    /**
     * Big enough that bzip2's bit reader, which pulls a byte at a time, isn't hitting the file
     * on every call. Measured on the real 2024-07-17 archive: unbuffered 68s, buffered 9s.
     */
    private const val BUFFER_SIZE = 1 shl 16

    class ExtractionException(
        message: String,
    ) : IOException(message)

    /**
     * [onProgress] is called as bytes land on disk, with the size the current entry declares
     * (`0` when the archive doesn't say). Unpacking this archive takes tens of seconds even at
     * full speed, so the caller needs something to show; it may also throw from the callback to
     * abort the extraction.
     */
    fun extract(
        source: File,
        destDir: File,
        onProgress: (copiedBytes: Long, entryBytes: Long) -> Unit = { _, _ -> },
    ) {
        val found = mutableSetOf<String>()
        destDir.mkdirs()
        openEntries(source) { name, entrySize, input ->
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
                BufferedOutputStream(tmp.outputStream(), BUFFER_SIZE).use { output ->
                    copyReporting(input, output, entrySize, onProgress)
                }
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    throw ExtractionException("Could not move $fileName into place")
                }
                found += fileName
            }
        }
        val missing = WANTED_NAMES - found
        if (missing.isNotEmpty()) {
            throw ExtractionException("Archive is missing required file(s): ${missing.joinToString()}")
        }
    }

    private fun copyReporting(
        input: InputStream,
        output: java.io.OutputStream,
        entrySize: Long,
        onProgress: (copiedBytes: Long, entryBytes: Long) -> Unit,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            copied += n
            onProgress(copied, entrySize)
        }
    }

    /**
     * Iterates entries of a bzip2'd tar or a zip file, calling [onEntry] for each regular file.
     *
     * The format comes from the file's own magic bytes, never its name: the downloader hands us
     * its `.part` scratch file and the SAF import path hands us whatever the user picked, so a
     * name-based check rejected perfectly good archives.
     */
    private fun openEntries(
        source: File,
        onEntry: (name: String, entrySize: Long, input: InputStream) -> Unit,
    ) {
        when (magicOf(source)) {
            Format.ZIP ->
                ZipInputStream(buffered(source)).use { zip ->
                    generateSequence { zip.nextEntry }.forEach { entry ->
                        if (!entry.isDirectory) onEntry(entry.name, entry.size.coerceAtLeast(0), zip)
                    }
                }

            Format.TAR_BZ2 ->
                // The BufferedInputStream is load-bearing, not a micro-optimisation: bzip2's bit
                // reader calls read() once per byte, so an unbuffered file here means ~160M
                // one-byte reads and an extraction that looks frozen for minutes on end.
                TarArchiveInputStream(BZip2CompressorInputStream(buffered(source))).use { tar ->
                    generateSequence { tar.nextEntry }.forEach { entry ->
                        if (!entry.isDirectory) onEntry(entry.name, entry.size.coerceAtLeast(0), tar)
                    }
                }

            null -> throw ExtractionException("Unsupported archive format: ${source.name}")
        }
    }

    private fun buffered(source: File) = BufferedInputStream(source.inputStream(), BUFFER_SIZE)

    private enum class Format { ZIP, TAR_BZ2 }

    /** `PK\u0003\u0004` for zip, `BZh` for bzip2; `null` for anything else. */
    private fun magicOf(source: File): Format? {
        val header = ByteArray(4)
        val read =
            source.inputStream().use { input ->
                var offset = 0
                while (offset < header.size) {
                    val n = input.read(header, offset, header.size - offset)
                    if (n < 0) break
                    offset += n
                }
                offset
            }
        if (read < 3) return null
        return when {
            header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() -> Format.ZIP
            header[0] == 'B'.code.toByte() && header[1] == 'Z'.code.toByte() && header[2] == 'h'.code.toByte() ->
                Format.TAR_BZ2
            else -> null
        }
    }
}
