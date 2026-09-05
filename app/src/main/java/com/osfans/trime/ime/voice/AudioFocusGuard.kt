/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import splitties.systemservices.audioManager

interface AudioFocusController {
    fun acquire()

    fun release()
}

/**
 * Ducks other apps' audio while a voice input session is recording, so the user can hear
 * themselves think over e.g. music. Deliberately minimal — just request/abandon
 * `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` — unlike a full audio-routing manager.
 */
class AudioFocusGuard : AudioFocusController {
    private var request: AudioFocusRequest? = null

    override fun acquire() {
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

    override fun release() {
        request?.let { audioManager.abandonAudioFocusRequest(it) }
        request = null
    }
}
