/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.osfans.trime.data.voice.SenseVoiceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.sqrt

interface VoiceRecorder {
    fun requestStop()

    /**
     * @param onFirstSample invoked once real audio starts flowing — the hardware has ~100-200ms
     *   of start-up latency, and a waveform that animates before that point makes it look like
     *   the app isn't actually listening yet.
     * @param onAmplitude invoked with a roughly 0..1 amplitude, throttled to ~20fps.
     * @return the recorded samples (range roughly -1..1), or `null` if the microphone couldn't
     *   be opened at all (permission denied, device busy, …).
     */
    suspend fun record(
        maxDurationMs: Long,
        onFirstSample: () -> Unit,
        onAmplitude: (Float) -> Unit,
    ): FloatArray?
}

/**
 * Records mono 16kHz PCM16 audio into memory (never to disk) until [requestStop] is called or
 * [maxDurationMs] elapses.
 *
 * A single [record] call is meant to back exactly one voice input session: launch it in a
 * coroutine, call [requestStop] from the gesture-release handler, and await its result.
 */
class AudioRecorder : VoiceRecorder {
    @Volatile
    private var stopRequested = false

    override fun requestStop() {
        stopRequested = true
    }

    @SuppressLint("MissingPermission") // caller is responsible for checking RECORD_AUDIO first
    override suspend fun record(
        maxDurationMs: Long,
        onFirstSample: () -> Unit,
        onAmplitude: (Float) -> Unit,
    ): FloatArray? =
        withContext(Dispatchers.IO) {
            stopRequested = false
            val sampleRate = SenseVoiceEngine.SAMPLE_RATE
            val minBufferBytes =
                AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            if (minBufferBytes <= 0) {
                Timber.e("AudioRecord.getMinBufferSize failed: $minBufferBytes")
                return@withContext null
            }
            val bufferBytes = minBufferBytes * 2

            val audioRecord =
                createAudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate, bufferBytes)
                    ?: createAudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, bufferBytes)
            if (audioRecord == null) {
                Timber.e("Failed to create AudioRecord with any audio source")
                return@withContext null
            }

            val maxSamples = (maxDurationMs * sampleRate / 1000L).toInt()
            val samples = FloatArray(maxSamples)
            var writeIndex = 0
            var firstSampleReported = false
            var lastAmplitudeReportAtMs = 0L
            val readBuffer = ShortArray(bufferBytes / 2)

            try {
                audioRecord.startRecording()
                while (!stopRequested && writeIndex < maxSamples) {
                    val n = audioRecord.read(readBuffer, 0, readBuffer.size)
                    if (n <= 0) break

                    if (!firstSampleReported) {
                        firstSampleReported = true
                        onFirstSample()
                    }

                    var sumSquares = 0.0
                    val countToCopy = n.coerceAtMost(maxSamples - writeIndex)
                    for (i in 0 until countToCopy) {
                        val f = readBuffer[i] / 32768f
                        samples[writeIndex++] = f
                        sumSquares += f.toDouble() * f.toDouble()
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastAmplitudeReportAtMs >= 50) {
                        lastAmplitudeReportAtMs = now
                        val rms = sqrt(sumSquares / n).toFloat()
                        onAmplitude(rms.coerceIn(0f, 1f))
                    }
                }
            } finally {
                runCatching { audioRecord.stop() }
                audioRecord.release()
            }

            samples.copyOf(writeIndex)
        }

    private fun createAudioRecord(
        audioSource: Int,
        sampleRate: Int,
        bufferBytes: Int,
    ): AudioRecord? = try {
        val record =
            AudioRecord(
                audioSource,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        if (record.state == AudioRecord.STATE_INITIALIZED) {
            record
        } else {
            record.release()
            null
        }
    } catch (t: Exception) {
        Timber.w(t, "Failed to create AudioRecord with source=$audioSource")
        null
    }
}
