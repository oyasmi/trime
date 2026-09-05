/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.voice

import androidx.annotation.StringRes
import com.osfans.trime.R
import com.osfans.trime.data.prefs.PreferenceDelegateEnum

/** How the user starts/stops a voice input session from the keyboard. */
enum class VoiceTriggerMode(
    @StringRes override val stringRes: Int,
) : PreferenceDelegateEnum {
    /** Hold the voice key down to record; release to recognize. Slide up to cancel. */
    HOLD(R.string.voice_trigger_mode_hold),

    /** Tap once to start, tap again (or tap the overlay's stop area) to finish. */
    TOGGLE(R.string.voice_trigger_mode_toggle),
}
