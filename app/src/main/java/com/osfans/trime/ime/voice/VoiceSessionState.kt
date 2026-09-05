/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

/**
 * State machine for a single voice input session. Deliberately holds no Android resource IDs —
 * [ErrorReason] is mapped to user-facing text by the UI layer — so this whole module stays
 * testable without instrumentation. See doc/voice-input-design.md §5.2 / §9.
 */
sealed interface VoiceSessionState {
    data object Idle : VoiceSessionState

    data class Recording(
        val amplitude: Float,
        /** True once the user has slid their finger far enough to cancel on release. */
        val cancelling: Boolean,
    ) : VoiceSessionState

    data object Recognizing : VoiceSessionState

    data class Correcting(
        val asrText: String,
    ) : VoiceSessionState

    data class Error(
        val reason: ErrorReason,
    ) : VoiceSessionState
}

enum class ErrorReason {
    /** Recording finished, but no microphone could be opened at all. */
    AudioFailure,

    /** The recognizer failed to load or threw during decoding. */
    EngineFailure,

    /** Recognition succeeded but produced no usable text (silence, noise-only, etc.). */
    NothingRecognized,

    /** A session was already in progress when a new one was requested. */
    Busy,
}
