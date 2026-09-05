/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import splitties.systemservices.audioManager
import timber.log.Timber

interface AudioFocusController {
    fun acquire()

    fun release()
}

/**
 * Ducks other apps' audio while a voice input session is recording, so the user can hear
 * themselves think over e.g. music. Deliberately minimal — just request/abandon
 * `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` — unlike a full audio-routing manager.
 *
 * `AudioFocusRequest` only exists on API 26+, but the feature ships down to `minSdk` 21, so the
 * pre-26 path uses the (now deprecated) stream-type `requestAudioFocus` overload. Audio focus is
 * a courtesy to other apps, not a precondition for recording, so a failure to obtain it is
 * logged and swallowed rather than allowed to crash the recording state machine.
 */
class AudioFocusGuard : AudioFocusController {
    // Kept only so the pre-26 abandon call passes the same listener instance it requested with.
    private val legacyListener = AudioManager.OnAudioFocusChangeListener { /* no-op */ }

    private var request: AudioFocusRequest? = null

    override fun acquire() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                acquireApi26()
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    legacyListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                )
            }
        }.onFailure { Timber.w(it, "Could not acquire audio focus") }
    }

    override fun release() {
        runCatching {
            val req = request
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && req != null) {
                audioManager.abandonAudioFocusRequest(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(legacyListener)
            }
        }.onFailure { Timber.w(it, "Could not abandon audio focus") }
        request = null
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun acquireApi26() {
        val attributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        val req =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .build()
        audioManager.requestAudioFocus(req)
        request = req
    }
}
