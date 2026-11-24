/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice

import android.Manifest
import android.content.ContextParams
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.os.SystemClock
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.voice.SenseVoiceEngine
import com.osfans.trime.data.voice.TextPostProcessor
import com.osfans.trime.data.voice.VoiceLanguage
import com.osfans.trime.data.voice.VoiceModelManager
import com.osfans.trime.data.voice.VoiceModelVariant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale
import kotlin.math.log10

/** Recognizes one utterance at a time in Trime's process using the keyboard's installed model. */
@RequiresApi(Build.VERSION_CODES.S)
class SenseVoiceRecognitionService : RecognitionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs by lazy { AppPrefs.defaultInstance().voice }
    private var active: Session? = null

    private class Session(
        val listener: Callback,
        val recorder: AudioRecorder,
        var job: Job? = null,
    )

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        if (active != null) {
            reportError(listener, SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            recognizerIntent.hasExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE)
        ) {
            reportError(listener, SpeechRecognizer.ERROR_CLIENT)
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            reportError(listener, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }
        val variant = prefs.modelVariant.getValue()
        if (!prefs.enabled.getValue() || !VoiceModelManager.isReady(this, variant)) {
            reportError(listener, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
            return
        }
        val requested = recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
        val language = if (requested.isNullOrBlank()) {
            prefs.language.getValue().code
        } else {
            Locale.forLanguageTag(requested).language.takeIf { code -> VoiceLanguage.entries.any { it.code == code } }
        }
        if (language == null) {
            reportError(listener, SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
            return
        }

        val attributedContext = try {
            createContext(ContextParams.Builder().setNextAttributionSource(listener.callingAttributionSource).build())
        } catch (_: SecurityException) {
            reportError(listener, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }
        val session = Session(listener, AudioRecorder(attributedContext))
        active = session
        session.job = scope.launch {
            try {
                recognize(session, variant, language)
            } catch (error: CancellationException) {
                throw error
            } catch (_: RemoteException) {
                // The client disconnected while recording or decoding.
            } catch (_: SecurityException) {
                reportError(listener, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            } catch (error: Exception) {
                Timber.w(error, "Local SenseVoice recognition failed")
                reportError(listener, SpeechRecognizer.ERROR_SERVER)
            } finally {
                if (active === session) active = null
            }
        }
    }

    private suspend fun recognize(session: Session, variant: VoiceModelVariant, language: String) {
        lateinit var endpoint: VoiceEndpointer
        val listener = session.listener
        val samples = session.recorder.record(
            maxDurationMs = minOf(30, prefs.maxDurationSeconds.getValue()) * 1_000L,
            onFirstSample = {
                endpoint = VoiceEndpointer(SystemClock.elapsedRealtime())
                listener.readyForSpeech(Bundle())
            },
            onAmplitude = { rms ->
                val hadSpeech = endpoint.hasSpeech
                val end = endpoint.observe(rms, SystemClock.elapsedRealtime())
                if (!hadSpeech && endpoint.hasSpeech) listener.beginningOfSpeech()
                listener.rmsChanged(20f * log10(rms.coerceAtLeast(0.0001f)) + 40f)
                if (end) session.recorder.requestStop()
            },
        )
        if (samples == null) {
            reportError(listener, SpeechRecognizer.ERROR_AUDIO)
            return
        }
        if (samples.isEmpty() || !endpoint.hasSpeech) {
            reportError(listener, SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            return
        }
        listener.endOfSpeech()
        val raw = SenseVoiceEngine.shared(this).decode(
            samples,
            variant,
            language,
            prefs.itn.getValue(),
            prefs.numThreads.getValue(),
            unloadAfter = prefs.keepAliveMinutes.getValue() == 0,
        )
        val text = TextPostProcessor.process(raw, prefs.trimTrailingPunct.getValue())
        if (text.isBlank()) {
            reportError(listener, SpeechRecognizer.ERROR_NO_MATCH)
        } else {
            listener.results(
                Bundle().apply {
                    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                },
            )
        }
    }

    override fun onStopListening(listener: Callback) {
        active?.takeIf { it.listener === listener }?.recorder?.requestStop()
    }

    override fun onCancel(listener: Callback) {
        active?.takeIf { it.listener === listener }?.let {
            it.recorder.requestStop()
            it.job?.cancel()
        }
    }

    override fun onDestroy() {
        active?.recorder?.requestStop()
        scope.cancel()
        super.onDestroy()
    }

    private fun reportError(listener: Callback, code: Int) {
        try {
            listener.error(code)
        } catch (_: RemoteException) {
            // The client no longer needs the result.
        }
    }
}

/** Detects the start of a turn and ends it after a pause, using the recorder's RMS samples. */
internal class VoiceEndpointer(private val startedAtMs: Long) {
    private var voiceStartedAt: Long? = null
    private var lastVoiceAt = startedAtMs
    private var noiseFloor = 0.002f

    var hasSpeech = false
        private set

    fun observe(rms: Float, now: Long): Boolean {
        val threshold = maxOf(0.009f, noiseFloor * 3f)
        if (rms >= threshold) {
            val start = voiceStartedAt ?: now.also { voiceStartedAt = it }
            if (now - start >= 100L) hasSpeech = true
            lastVoiceAt = now
        } else {
            voiceStartedAt = null
            if (!hasSpeech) noiseFloor = noiseFloor * 0.9f + rms * 0.1f
        }
        return if (hasSpeech) now - lastVoiceAt >= 800L else now - startedAtMs >= 7_000L
    }
}
