// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

object Versions {
    const val DEFAULT_CMAKE = "3.31.6"
    const val DEFAULT_NDK = "28.0.13004108"

    val supportedAbis = setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

    // sherpa-onnx prebuilt Android AAR used by the local voice input (SenseVoice) feature.
    // Release: https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.7
    const val SHERPA_ONNX = "1.13.7"
    const val SHERPA_ONNX_AAR_SHA256 = "c4ef49e309f24fcee5c106b8a279481aaecaabb078cd37b2cd6e9a62cc8a73c8"
}
