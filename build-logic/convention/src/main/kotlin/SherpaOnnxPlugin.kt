/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.com.google.common.hash.Hashing
import org.jetbrains.kotlin.com.google.common.io.ByteSource
import java.io.File
import java.net.URI

/**
 * Downloads the prebuilt sherpa-onnx Android AAR (used by the local voice input / SenseVoice
 * feature) into `app/libs/`, verifying its sha256 against [Versions.SHERPA_ONNX_AAR_SHA256].
 *
 * The AAR itself is never committed to the repository (it's ~49MB); this plugin fetches it once
 * and the result is left in `app/libs/`, which is git-ignored.
 *
 * For offline development, set the `SHERPA_ONNX_AAR` environment variable to a local file path;
 * it will be copied (and still sha256-checked) instead of being downloaded.
 */
class SherpaOnnxPlugin : Plugin<Project> {
    companion object {
        const val TASK = "fetchSherpaOnnxAar"

        private fun downloadUrl(version: String) = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$version/sherpa-onnx-$version.aar"
    }

    override fun apply(target: Project) {
        val task =
            target.tasks.register<FetchSherpaOnnxAarTask>(TASK) {
                version.set(Versions.SHERPA_ONNX)
                sha256.set(Versions.SHERPA_ONNX_AAR_SHA256)
                downloadUrl.set(downloadUrl(Versions.SHERPA_ONNX))
                localOverride.set(System.getenv("SHERPA_ONNX_AAR"))
                outputFile.set(target.file("libs/sherpa-onnx-${Versions.SHERPA_ONNX}.aar"))
            }
        target.tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(task) }
    }

    abstract class FetchSherpaOnnxAarTask : DefaultTask() {
        // Not modeled as Gradle @Input: this task decides for itself (by re-hashing the existing
        // output) whether real work is needed, rather than relying on Gradle's up-to-date check.
        @get:Internal
        abstract val version: org.gradle.api.provider.Property<String>

        @get:Internal
        abstract val sha256: org.gradle.api.provider.Property<String>

        @get:Internal
        abstract val downloadUrl: org.gradle.api.provider.Property<String>

        @get:Internal
        abstract val localOverride: org.gradle.api.provider.Property<String>

        @get:OutputFile
        abstract val outputFile: RegularFileProperty

        @TaskAction
        fun execute() {
            val dest = outputFile.get().asFile
            val expectedSha256 = sha256.get()

            if (dest.exists() && sha256(dest) == expectedSha256) {
                logger.lifecycle("sherpa-onnx AAR already present and verified: ${dest.name}")
                return
            }

            dest.parentFile.mkdirs()
            val tmp = File(dest.parentFile, "${dest.name}.part")

            val override = localOverride.orNull
            if (!override.isNullOrBlank()) {
                logger.lifecycle("Using local sherpa-onnx AAR override: $override")
                File(override).copyTo(tmp, overwrite = true)
            } else {
                val url = downloadUrl.get()
                logger.lifecycle("Downloading sherpa-onnx AAR from $url")
                URI(url).toURL().openStream().use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
            }

            val actualSha256 = sha256(tmp)
            if (actualSha256 != expectedSha256) {
                tmp.delete()
                throw org.gradle.api.GradleException(
                    "sherpa-onnx AAR sha256 mismatch: expected $expectedSha256, got $actualSha256. " +
                        "The pinned checksum in Versions.kt may be stale, or the download was corrupted.",
                )
            }
            tmp.renameTo(dest)
            logger.lifecycle("sherpa-onnx AAR ready: ${dest.name}")
        }

        private fun sha256(file: File): String = ByteSource.wrap(file.readBytes()).hash(Hashing.sha256()).toString()
    }
}
