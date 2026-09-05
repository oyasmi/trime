/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.voice

/** Observable install status of the currently-selected [VoiceModelVariant]. */
sealed interface VoiceModelState {
    data object NotInstalled : VoiceModelState

    data class Downloading(
        val percent: Int,
    ) : VoiceModelState

    data object Extracting : VoiceModelState

    data class Ready(
        val readableSize: String,
    ) : VoiceModelState

    data class Invalid(
        val reason: String,
    ) : VoiceModelState
}
