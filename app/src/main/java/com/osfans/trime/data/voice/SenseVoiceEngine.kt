/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Thin wrapper around the sherpa-onnx `OfflineRecognizer` for the SenseVoice model.
 *
 * Deliberately exposes only [decode] (`FloatArray` in, `String` out) — no
 * `com.k2fsa.sherpa.onnx.*` type ever leaves this class. That keeps the door open for moving the
 * engine into a separate process later (see doc/voice-input-design.md §4.2) without touching any
 * caller.
 *
 * All state is guarded by [mutex]: loading and inference never run concurrently, matching
 * sherpa-onnx's single-threaded-per-recognizer expectation.
 *
 * Model construction, the synchronous JNI `decode`, and `release` are all CPU/IO-bound blocking
 * calls that must never touch the IME's main thread (the caller's `lifecycleScope` runs on
 * `Dispatchers.Main.immediate`), so every public entry point here hops onto [Dispatchers.Default]
 * before taking [mutex]. A `withTimeoutOrNull` around [decode] only stops the caller waiting — the
 * native call keeps running to completion on the background thread and the mutex stays held until
 * it returns, which is what prevents a timed-out decode from racing a fresh one or an early
 * `release()`.
 */
interface RecognitionEngine {
    /** Runs one offline recognition pass over [samples] (mono, 16kHz, range roughly -1..1). */
    suspend fun decode(
        samples: FloatArray,
        variant: VoiceModelVariant,
        language: String,
        itn: Boolean,
        numThreads: Int,
    ): String

    suspend fun unloadNow()
}

class SenseVoiceEngine(
    private val context: Context,
) : RecognitionEngine {
    sealed interface State {
        data object Unloaded : State

        data object Loading : State

        data object Ready : State

        data class Failed(
            val reason: String,
        ) : State
    }

    class DecodeException(
        message: String,
    ) : Exception(message)

    private val mutex = Mutex()
    private val _state = MutableStateFlow<State>(State.Unloaded)
    val state: StateFlow<State> = _state.asStateFlow()

    private var recognizer: OfflineRecognizer? = null

    @Volatile
    private var lastUseAtMs: Long = System.currentTimeMillis()

    // The config a currently-loaded recognizer was built with; used to detect that a preference
    // change (language/ITN/thread count/model variant) requires a rebuild.
    private data class LoadedConfig(
        val variant: VoiceModelVariant,
        val language: String,
        val itn: Boolean,
        val numThreads: Int,
    )

    private var loadedConfig: LoadedConfig? = null

    /** Preloads the engine so the first [decode] call doesn't pay the model-load cost. */
    suspend fun preload(
        variant: VoiceModelVariant,
        language: String,
        itn: Boolean,
        numThreads: Int,
    ) = withContext(Dispatchers.Default) {
        mutex.withLock { ensureLoadedLocked(variant, language, itn, numThreads) }
        Unit
    }

    override suspend fun decode(
        samples: FloatArray,
        variant: VoiceModelVariant,
        language: String,
        itn: Boolean,
        numThreads: Int,
    ): String = withContext(Dispatchers.Default) {
        mutex.withLock {
            val engine = ensureLoadedLocked(variant, language, itn, numThreads)
            val stream = engine.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                engine.decode(stream)
                engine.getResult(stream).text
            } finally {
                stream.release()
            }
        }
    }

    override suspend fun unloadNow() {
        withContext(Dispatchers.Default) {
            mutex.withLock {
                recognizer?.release()
                recognizer = null
                loadedConfig = null
                _state.value = State.Unloaded
            }
        }
    }

    /**
     * Runs forever (until the caller's coroutine is cancelled), unloading the engine after
     * [minutesProvider] minutes without a [decode]/[preload] call. `<= 0` disables idle
     * unloading here — `0` ("unload immediately after each session") is instead handled by the
     * caller (`VoiceSession`) calling [unloadNow] right after it gets a result, since that's a
     * one-shot action, not a background poll.
     */
    suspend fun runIdleUnloadLoop(minutesProvider: () -> Int) {
        val pollIntervalMs = 30_000L
        while (true) {
            val minutes = minutesProvider()
            if (minutes <= 0) {
                delay(pollIntervalMs)
                continue
            }
            val remaining = minutes * 60_000L - (System.currentTimeMillis() - lastUseAtMs)
            if (remaining <= 0) {
                unloadNow()
                delay(pollIntervalMs)
            } else {
                delay(remaining.coerceAtMost(pollIntervalMs))
            }
        }
    }

    private fun ensureLoadedLocked(
        variant: VoiceModelVariant,
        language: String,
        itn: Boolean,
        numThreads: Int,
    ): OfflineRecognizer {
        lastUseAtMs = System.currentTimeMillis()
        val wanted = LoadedConfig(variant, language, itn, numThreads)
        recognizer?.let { if (loadedConfig == wanted) return it }

        recognizer?.release()
        recognizer = null
        _state.value = State.Loading

        val dir = VoiceModelManager.modelDir(context, variant)
        val modelFile = File(dir, VoiceModelVariant.MODEL_FILE_NAME)
        val tokensFile = File(dir, VoiceModelVariant.TOKENS_FILE_NAME)
        if (!modelFile.exists() || !tokensFile.exists()) {
            val reason = "model not installed"
            _state.value = State.Failed(reason)
            throw DecodeException(reason)
        }

        val config =
            OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig =
                OfflineModelConfig(
                    tokens = tokensFile.absolutePath,
                    numThreads = numThreads,
                    debug = false,
                    provider = "cpu",
                    senseVoice =
                    OfflineSenseVoiceModelConfig(
                        model = modelFile.absolutePath,
                        language = language,
                        useInverseTextNormalization = itn,
                    ),
                ),
            )

        return try {
            // assetManager must be null when loading from an absolute filesystem path — passing
            // a non-null AssetManager here makes sherpa-onnx try (and fail) to resolve the paths
            // as assets. See https://github.com/k2-fsa/sherpa-onnx/issues/2562.
            val built = OfflineRecognizer(null, config)
            recognizer = built
            loadedConfig = wanted
            _state.value = State.Ready
            built
        } catch (t: Throwable) {
            Timber.e(t, "Failed to load SenseVoice engine")
            _state.value = State.Failed(t.message ?: t.javaClass.simpleName)
            throw DecodeException(t.message ?: "failed to load engine")
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}
