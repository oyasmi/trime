// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.voice

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory

class VoiceModelArchiveTest :
    StringSpec({
        fun tempDir(): File = createTempDirectory("voice-model-archive-test").toFile()

        fun writeZip(
            file: File,
            entries: Map<String, ByteArray>,
        ) {
            ZipOutputStream(file.outputStream()).use { zip ->
                entries.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content)
                    zip.closeEntry()
                }
            }
        }

        fun writeTarBz2(
            file: File,
            entries: Map<String, ByteArray>,
        ) {
            TarArchiveOutputStream(BZip2CompressorOutputStream(file.outputStream())).use { tar ->
                entries.forEach { (name, content) ->
                    val entry = TarArchiveEntry(name)
                    entry.size = content.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(content)
                    tar.closeArchiveEntry()
                }
            }
        }

        "extracts and flattens a zip archive with a nested top-level directory" {
            val archive = File(tempDir(), "model.zip")
            writeZip(
                archive,
                mapOf(
                    "sherpa-onnx-sense-voice-2024/README.md" to "readme".toByteArray(),
                    "sherpa-onnx-sense-voice-2024/model.int8.onnx" to "MODEL_BYTES".toByteArray(),
                    "sherpa-onnx-sense-voice-2024/tokens.txt" to "TOKENS_BYTES".toByteArray(),
                    "sherpa-onnx-sense-voice-2024/test_wavs/zh.wav" to "wav".toByteArray(),
                ),
            )
            val dest = tempDir()

            VoiceModelArchive.extract(archive, dest)

            File(dest, "model.int8.onnx").readText() shouldBe "MODEL_BYTES"
            File(dest, "tokens.txt").readText() shouldBe "TOKENS_BYTES"
            File(dest, "README.md").exists() shouldBe false
            File(dest, "test_wavs").exists() shouldBe false
        }

        "extracts and flattens a tar.bz2 archive with a nested top-level directory" {
            val archive = File(tempDir(), "model.tar.bz2")
            writeTarBz2(
                archive,
                mapOf(
                    "sherpa-onnx-sense-voice-2024/model.int8.onnx" to "MODEL_BYTES".toByteArray(),
                    "sherpa-onnx-sense-voice-2024/tokens.txt" to "TOKENS_BYTES".toByteArray(),
                    "sherpa-onnx-sense-voice-2024/LICENSE" to "license".toByteArray(),
                ),
            )
            val dest = tempDir()

            VoiceModelArchive.extract(archive, dest)

            File(dest, "model.int8.onnx").readText() shouldBe "MODEL_BYTES"
            File(dest, "tokens.txt").readText() shouldBe "TOKENS_BYTES"
            File(dest, "LICENSE").exists() shouldBe false
        }

        "extracts a path-traversal entry safely inside the destination directory" {
            val archive = File(tempDir(), "evil.zip")
            writeZip(
                archive,
                mapOf(
                    "../../../evil/model.int8.onnx" to "MODEL_BYTES".toByteArray(),
                    "tokens.txt" to "TOKENS_BYTES".toByteArray(),
                ),
            )
            val dest = tempDir()

            VoiceModelArchive.extract(archive, dest)

            // The file must land inside dest, not escape via the entry's own "../" path.
            File(dest, "model.int8.onnx").readText() shouldBe "MODEL_BYTES"
            dest.parentFile?.listFiles { f -> f.name == "evil" }?.isEmpty() shouldBe true
        }

        "detects the format from magic bytes, not the file name" {
            // The downloader hands the extractor its own scratch file, whose name ends in
            // ".part" — trusting the extension made every completed download fail to unpack.
            val archive = File(tempDir(), "voice_model_V2024_07_17.part")
            writeTarBz2(
                archive,
                mapOf(
                    "sherpa-onnx-sense-voice-2024/model.int8.onnx" to "MODEL_BYTES".toByteArray(),
                    "sherpa-onnx-sense-voice-2024/tokens.txt" to "TOKENS_BYTES".toByteArray(),
                ),
            )
            val dest = tempDir()

            VoiceModelArchive.extract(archive, dest)

            File(dest, "model.int8.onnx").readText() shouldBe "MODEL_BYTES"
            File(dest, "tokens.txt").readText() shouldBe "TOKENS_BYTES"
        }

        "rejects a file that is neither a zip nor a bzip2 archive" {
            val archive = File(tempDir(), "model.tar.bz2")
            archive.writeText("this is not an archive at all")
            val dest = tempDir()

            var threw = false
            try {
                VoiceModelArchive.extract(archive, dest)
            } catch (e: VoiceModelArchive.ExtractionException) {
                threw = true
            }
            threw shouldBe true
        }

        "a wrong archive missing a required file leaves an existing install untouched" {
            val dest = tempDir()
            File(dest, "model.int8.onnx").writeText("OLD_MODEL")
            File(dest, "tokens.txt").writeText("OLD_TOKENS")

            val archive = File(tempDir(), "wrong.zip")
            writeZip(archive, mapOf("model.int8.onnx" to "NEW_MODEL".toByteArray()))

            var threw = false
            try {
                VoiceModelArchive.extract(archive, dest)
            } catch (e: VoiceModelArchive.ExtractionException) {
                threw = true
            }

            threw shouldBe true
            File(dest, "model.int8.onnx").readText() shouldBe "OLD_MODEL"
            File(dest, "tokens.txt").readText() shouldBe "OLD_TOKENS"
            dest.listFiles { f -> f.name.endsWith(".part") }?.isEmpty() shouldBe true
        }

        "throws when a required file is missing from the archive" {
            val archive = File(tempDir(), "incomplete.zip")
            writeZip(archive, mapOf("model.int8.onnx" to "MODEL_BYTES".toByteArray()))
            val dest = tempDir()

            var threw = false
            try {
                VoiceModelArchive.extract(archive, dest)
            } catch (e: VoiceModelArchive.ExtractionException) {
                threw = true
            }
            threw shouldBe true
        }
    })
