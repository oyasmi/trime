// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.voice

import com.osfans.trime.data.voice.RecognitionEngine
import com.osfans.trime.data.voice.VoiceModelVariant
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

private val SAMPLE_RATE = com.osfans.trime.data.voice.SenseVoiceEngine.SAMPLE_RATE

private fun samplesFor(durationMs: Long): FloatArray = FloatArray((durationMs * SAMPLE_RATE / 1000L).toInt())

private class FakeRecorder(
    private val result: FloatArray?,
) : VoiceRecorder {
    var stopRequested = false
        private set

    override fun requestStop() {
        stopRequested = true
    }

    override suspend fun record(
        maxDurationMs: Long,
        onFirstSample: () -> Unit,
        onAmplitude: (Float) -> Unit,
    ): FloatArray? {
        if (result != null && result.isNotEmpty()) onFirstSample()
        return result
    }
}

/** Reports amplitudes from a separate thread, the way [AudioRecorder] really does. */
private class OffThreadAmplitudeRecorder(
    private val result: FloatArray,
) : VoiceRecorder {
    override fun requestStop() = Unit

    override suspend fun record(
        maxDurationMs: Long,
        onFirstSample: () -> Unit,
        onAmplitude: (Float) -> Unit,
    ): FloatArray {
        val thread = Thread {
            onFirstSample()
            repeat(5) { onAmplitude(0.5f) }
        }
        thread.start()
        thread.join()
        return result
    }
}

private class FakeEngine(
    private val resultText: String = "识别结果",
    private val throwOnDecode: Boolean = false,
) : RecognitionEngine {
    var unloadCount = 0
        private set
    var decodeCount = 0
        private set

    override suspend fun decode(
        samples: FloatArray,
        variant: VoiceModelVariant,
        language: String,
        itn: Boolean,
        numThreads: Int,
    ): String {
        decodeCount++
        if (throwOnDecode) throw RuntimeException("boom")
        return resultText
    }

    override suspend fun unloadNow() {
        unloadCount++
    }
}

private class FakeAudioFocus : AudioFocusController {
    var acquired = false

    override fun acquire() {
        acquired = true
    }

    override fun release() {
        acquired = false
    }
}

private fun defaultConfig(
    maxDurationMs: Long = 60_000,
    llmEnabled: Boolean = false,
    unloadImmediatelyAfter: Boolean = false,
) = VoiceSession.Config(
    variant = VoiceModelVariant.V2024_07_17,
    language = "auto",
    itn = true,
    numThreads = 2,
    maxDurationMs = maxDurationMs,
    trimTrailingPunct = false,
    llmEnabled = llmEnabled,
    unloadImmediatelyAfter = unloadImmediatelyAfter,
)

/** Waits (busy-polls briefly) until [predicate] is true or ~2s elapse, for cross-thread state. */
private suspend fun awaitUntil(predicate: () -> Boolean) {
    withTimeoutOrNull(2_000) {
        while (!predicate()) kotlinx.coroutines.delay(5)
    }
}

class VoiceSessionTest :
    StringSpec({
        "a recording under 300ms is discarded silently" {
            runBlocking {
                val states = mutableListOf<VoiceSessionState>()
                val results = mutableListOf<String>()
                val engine = FakeEngine()
                val session =
                    VoiceSession(
                        scope = this,
                        engine = engine,
                        recorder = FakeRecorder(samplesFor(100)),
                        audioFocus = FakeAudioFocus(),
                        correct = { it },
                        onStateChange = { states += it },
                        onResult = { results += it },
                        onMaxDurationReached = {},
                    )

                session.start(defaultConfig())
                awaitUntil { states.lastOrNull() is VoiceSessionState.Idle }

                results shouldBe emptyList()
                engine.decodeCount shouldBe 0
            }
        }

        "a normal recording is recognized and committed" {
            runBlocking {
                val results = mutableListOf<String>()
                val engine = FakeEngine(resultText = "你好")
                val session =
                    VoiceSession(
                        scope = this,
                        engine = engine,
                        recorder = FakeRecorder(samplesFor(2000)),
                        audioFocus = FakeAudioFocus(),
                        correct = { it },
                        onStateChange = {},
                        onResult = { results += it },
                        onMaxDurationReached = {},
                    )

                session.start(defaultConfig())
                awaitUntil { results.isNotEmpty() }

                results shouldBe listOf("你好")
                engine.decodeCount shouldBe 1
            }
        }

        "reaching the maximum duration recognizes and reports it, without an explicit release" {
            runBlocking {
                val results = mutableListOf<String>()
                var maxDurationHits = 0
                val engine = FakeEngine(resultText = "结果")
                val maxDurationMs = 1000L
                val session =
                    VoiceSession(
                        scope = this,
                        engine = engine,
                        recorder = FakeRecorder(samplesFor(maxDurationMs)),
                        audioFocus = FakeAudioFocus(),
                        correct = { it },
                        onStateChange = {},
                        onResult = { results += it },
                        onMaxDurationReached = { maxDurationHits++ },
                    )

                session.start(defaultConfig(maxDurationMs = maxDurationMs))
                awaitUntil { results.isNotEmpty() }

                maxDurationHits shouldBe 1
                results shouldBe listOf("结果")
            }
        }

        "cancelling while sliding up discards the result without recognizing" {
            runBlocking {
                val states = mutableListOf<VoiceSessionState>()
                val results = mutableListOf<String>()
                val engine = FakeEngine()
                val recorder = FakeRecorder(samplesFor(2000))
                val session =
                    VoiceSession(
                        scope = this,
                        engine = engine,
                        recorder = recorder,
                        audioFocus = FakeAudioFocus(),
                        correct = { it },
                        onStateChange = { states += it },
                        onResult = { results += it },
                        onMaxDurationReached = {},
                    )

                session.start(defaultConfig())
                session.updateCancelling(true)
                session.finishHold()
                awaitUntil { states.lastOrNull() is VoiceSessionState.Idle }

                recorder.stopRequested shouldBe true
                results shouldBe emptyList()
                engine.decodeCount shouldBe 0
            }
        }

        "a session in progress rejects a second start without disturbing the first" {
            runBlocking {
                val mutex = Mutex()
                var releaseGate: (() -> Unit)? = null
                val blockingRecorder =
                    object : VoiceRecorder {
                        override fun requestStop() {}

                        override suspend fun record(
                            maxDurationMs: Long,
                            onFirstSample: () -> Unit,
                            onAmplitude: (Float) -> Unit,
                        ): FloatArray? {
                            mutex.withLock {}
                            return samplesFor(2000)
                        }
                    }
                mutex.lock()
                val states = mutableListOf<VoiceSessionState>()
                val session =
                    VoiceSession(
                        scope = this,
                        engine = FakeEngine(),
                        recorder = blockingRecorder,
                        audioFocus = FakeAudioFocus(),
                        correct = { it },
                        onStateChange = { states += it },
                        onResult = {},
                        onMaxDurationReached = {},
                    )

                session.start(defaultConfig())
                awaitUntil { states.isNotEmpty() }
                val busyBefore = states.size

                session.start(defaultConfig()) // should be rejected: a Busy flash, then restore

                states.last() shouldBe (states[busyBefore - 1])
                mutex.unlock()
                session.cancel()
            }
        }

        "engine failure returns to Idle without committing anything" {
            runBlocking {
                val states = mutableListOf<VoiceSessionState>()
                val results = mutableListOf<String>()
                val session =
                    VoiceSession(
                        scope = this,
                        engine = FakeEngine(throwOnDecode = true),
                        recorder = FakeRecorder(samplesFor(2000)),
                        audioFocus = FakeAudioFocus(),
                        correct = { it },
                        onStateChange = { states += it },
                        onResult = { results += it },
                        onMaxDurationReached = {},
                    )

                session.start(defaultConfig())
                awaitUntil { states.lastOrNull() is VoiceSessionState.Idle }

                results shouldBe emptyList()
                states.any { it is VoiceSessionState.Error } shouldBe true
            }
        }

        "an audio failure (null samples) surfaces an error and returns to Idle" {
            runBlocking {
                val states = mutableListOf<VoiceSessionState>()
                val session =
                    VoiceSession(
                        scope = this,
                        engine = FakeEngine(),
                        recorder = FakeRecorder(null),
                        audioFocus = FakeAudioFocus(),
                        correct = { it },
                        onStateChange = { states += it },
                        onResult = {},
                        onMaxDurationReached = {},
                    )

                session.start(defaultConfig())
                awaitUntil { states.lastOrNull() is VoiceSessionState.Idle }

                states.any { it is VoiceSessionState.Error && it.reason == ErrorReason.AudioFailure } shouldBe true
            }
        }

        "unloadImmediatelyAfter unloads the engine right after decoding" {
            runBlocking {
                val engine = FakeEngine(resultText = "结果")
                val results = mutableListOf<String>()
                val session =
                    VoiceSession(
                        scope = this,
                        engine = engine,
                        recorder = FakeRecorder(samplesFor(2000)),
                        audioFocus = FakeAudioFocus(),
                        correct = { it },
                        onStateChange = {},
                        onResult = { results += it },
                        onMaxDurationReached = {},
                    )

                session.start(defaultConfig(unloadImmediatelyAfter = true))
                awaitUntil { results.isNotEmpty() }

                engine.unloadCount shouldBe 1
            }
        }

        "llm correction runs through Correcting and commits the corrected text" {
            runBlocking {
                val states = mutableListOf<VoiceSessionState>()
                val results = mutableListOf<String>()
                val session =
                    VoiceSession(
                        scope = this,
                        engine = FakeEngine(resultText = "原文"),
                        recorder = FakeRecorder(samplesFor(2000)),
                        audioFocus = FakeAudioFocus(),
                        correct = { "校对后" },
                        onStateChange = { states += it },
                        onResult = { results += it },
                        onMaxDurationReached = {},
                    )

                session.start(defaultConfig(llmEnabled = true))
                awaitUntil { results.isNotEmpty() }

                states.any { it is VoiceSessionState.Correcting && it.asrText == "原文" } shouldBe true
                results shouldBe listOf("校对后")
            }
        }

        // Regression: the recorder reports amplitudes from its own thread; delivering them to
        // onStateChange there crashed the IME (the overlay's TextView is main-thread-confined).
        "amplitude updates are delivered on the session's own dispatcher" {
            val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            try {
                runBlocking {
                    val scope = CoroutineScope(dispatcher + Job())
                    // Coroutine debug mode appends " @coroutine#N" to the thread name; the
                    // thread identity is what matters here, not which coroutine is on it.
                    fun currentThread() = Thread.currentThread().name.substringBefore(" @coroutine#")
                    val sessionThreadName = withContext(dispatcher) { currentThread() }
                    val callbackThreads = java.util.Collections.synchronizedSet(mutableSetOf<String>())
                    val results = mutableListOf<String>()
                    val session =
                        VoiceSession(
                            scope = scope,
                            engine = FakeEngine(),
                            recorder = OffThreadAmplitudeRecorder(samplesFor(2000)),
                            audioFocus = FakeAudioFocus(),
                            correct = { it },
                            onStateChange = { callbackThreads += currentThread() },
                            onResult = { results += it },
                            onMaxDurationReached = {},
                        )

                    withContext(dispatcher) { session.start(defaultConfig()) }
                    awaitUntil { results.isNotEmpty() }
                    // let any queued amplitude updates drain
                    withContext(dispatcher) { }

                    callbackThreads shouldBe setOf(sessionThreadName)
                }
            } finally {
                dispatcher.close()
            }
        }
    })
