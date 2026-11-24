/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import com.osfans.trime.data.voice.SenseVoiceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.math.sqrt

interface VoiceRecorder {
    fun requestStop()

    /**
     * [onFirstSample] and [onAmplitude] are invoked on the recording thread, *not* the caller's
     * thread — marshal back yourself before touching anything thread-confined (UI included).
     *
     * @param onFirstSample invoked once real audio starts flowing — the hardware has ~100-200ms
     *   of start-up latency, and a waveform that animates before that point makes it look like
     *   the app isn't actually listening yet.
     * @param onAmplitude invoked with a roughly 0..1 amplitude, throttled to ~20fps.
     * @return the recorded samples (range roughly -1..1), or `null` if audio capture fails.
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
class AudioRecorder(
    private val attributionContext: Context? = null,
) : VoiceRecorder {
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
    ): FloatArray? {
        // Clear the stop flag *before* the coroutine possibly suspends on the IO dispatcher. If
        // this were done inside `withContext(Dispatchers.IO)` and dispatch was slow, a release
        // that already called `requestStop()` would be silently overwritten and the recording
        // would run to its full time limit.
        stopRequested = false
        return withContext(Dispatchers.IO) {
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
            val readBuffer = ShortArray(sampleRate / 50)
            val amplitudeWindow = readBuffer.size * 3
            var amplitudeSamples = 0
            var sumSquares = 0.0

            try {
                audioRecord.startRecording()
                if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) return@withContext null
                while (!stopRequested && writeIndex < maxSamples) {
                    // Cooperate with coroutine cancellation (`sessionJob.cancel()` on an explicit
                    // cancel): without this the loop only ever stops on `stopRequested` or the
                    // time cap, so a cancelled session's microphone stayed open until then.
                    currentCoroutineContext().ensureActive()
                    val count = minOf(readBuffer.size, maxSamples - writeIndex, amplitudeWindow - amplitudeSamples)
                    val n = audioRecord.read(readBuffer, 0, count)
                    if (n <= 0) return@withContext null
                    if (writeIndex == 0) onFirstSample()

                    for (i in 0 until n) {
                        val f = readBuffer[i] / 32768f
                        samples[writeIndex++] = f
                        sumSquares += f.toDouble() * f.toDouble()
                    }
                    amplitudeSamples += n
                    if (amplitudeSamples == amplitudeWindow) {
                        onAmplitude(sqrt(sumSquares / amplitudeSamples).toFloat())
                        amplitudeSamples = 0
                        sumSquares = 0.0
                    }
                }
            } finally {
                runCatching { audioRecord.stop() }
                audioRecord.release()
            }

            samples.copyOf(writeIndex)
        }
    }

    private fun createAudioRecord(
        audioSource: Int,
        sampleRate: Int,
        bufferBytes: Int,
    ): AudioRecord? = try {
        val record = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && attributionContext != null) {
            AudioRecord.Builder()
                .setContext(attributionContext)
                .setAudioSource(audioSource)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .build()
        } else {
            AudioRecord(
                audioSource,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        }
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
