package review

import com.osfans.trime.data.voice.*
import com.osfans.trime.data.voice.llm.*
import com.osfans.trime.ime.voice.*
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private fun config() = VoiceSession.Config(VoiceModelVariant.V2024_07_17, "auto", true, 2, 60000, false, false, false)
private class Focus : AudioFocusController {
    var released = false
    override fun acquire() {}
    override fun release() { released = true }
}
private class Engine(val action: () -> Unit = {}) : RecognitionEngine {
    override suspend fun decode(samples: FloatArray, variant: VoiceModelVariant, language: String, itn: Boolean, numThreads: Int): String {
        action()
        return "你好"
    }
    override suspend fun unloadNow() {}
}
private class Recorder(val fail: Boolean = false) : VoiceRecorder {
    override fun requestStop() {}
    override suspend fun record(maxDurationMs: Long, onFirstSample: () -> Unit, onAmplitude: (Float) -> Unit): FloatArray {
        if (fail) throw IllegalStateException("record start failed")
        return FloatArray(16000)
    }
}

// These probes assert the observed defects, not desired regression behavior.
class VoiceReviewProbeTest : StringSpec({
    "synchronous decode blocks session dispatcher and delays queued UI work" {
        val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "review-ui") }.asCoroutineDispatcher()
        try {
            runBlocking {
                val scope = CoroutineScope(SupervisorJob() + dispatcher)
                val result = CompletableDeferred<Unit>()
                val heartbeat = CompletableDeferred<Long>()
                var decodeThread = ""
                val engine = Engine {
                    decodeThread = Thread.currentThread().name.substringBefore(" @")
                    scope.launch { heartbeat.complete(System.nanoTime()) }
                    Thread.sleep(250)
                }
                val session = VoiceSession(scope, engine, Recorder(), Focus(), { it }, {}, { result.complete(Unit) }, {})
                val started = System.nanoTime()
                withContext(dispatcher) { session.start(config()) }
                withTimeout(3000) { result.await() }
                val elapsed = (heartbeat.await() - started) / 1000000
                decodeThread shouldBe "review-ui"
                (elapsed >= 240) shouldBe true
                println("REVIEW dispatcher=$decodeThread heartbeatDelayedMs=$elapsed")
                scope.cancel()
            }
        } finally { dispatcher.close() }
    }

    "withTimeout cannot impose a wall clock cap on synchronous work" {
        runBlocking {
            val started = System.nanoTime()
            withTimeoutOrNull(50) { Thread.sleep(250); "done" }
            val elapsed = (System.nanoTime() - started) / 1000000
            (elapsed >= 240) shouldBe true
            println("REVIEW timeoutBudgetMs=50 actualMs=$elapsed")
        }
    }

    "recorder exception escapes and leaves session busy" {
        runBlocking {
            val failure = CompletableDeferred<Throwable>()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, t -> failure.complete(t) })
            val focus = Focus()
            val session = VoiceSession(scope, Engine(), Recorder(true), focus, { it }, {}, {}, {})
            session.start(config())
            withTimeout(2000) { failure.await() }
            session.isBusy() shouldBe true
            focus.released shouldBe true
            println("REVIEW recorderExceptionEscaped=true sessionStillBusy=${session.isBusy()}")
            scope.cancel()
        }
    }

    "failed model replacement leaves a mixed installation" {
        val root = Files.createTempDirectory("voice-archive-probe").toFile()
        try {
            val dest = File(root, "installed").apply { mkdirs() }
            File(dest, "model.int8.onnx").writeText("old model")
            File(dest, "tokens.txt").writeText("old tokens")
            val archive = File(root, "bad.zip")
            ZipOutputStream(archive.outputStream()).use {
                it.putNextEntry(ZipEntry("model.int8.onnx"))
                it.write("new incompatible model".toByteArray())
                it.closeEntry()
            }
            runCatching { VoiceModelArchive.extract(archive, dest) }.isFailure shouldBe true
            File(dest, "model.int8.onnx").readText() shouldBe "new incompatible model"
            File(dest, "tokens.txt").readText() shouldBe "old tokens"
            println("REVIEW archiveFailed=true installedModel=new installedTokens=old bothNonempty=true")
        } finally { root.deleteRecursively() }
    }

    "read timeout allows a slowly delivered correction to exceed configured timeout" {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            exchange.sendResponseHeaders(200, 0)
            runCatching {
                exchange.responseBody.use { out ->
                    repeat(5) { out.write(' '.code); out.flush(); Thread.sleep(400) }
                    out.write("""{"choices":[{"message":{"content":"校对后"},"finish_reason":"stop"}]}""".toByteArray())
                }
            }
        }
        server.start()
        try {
            runBlocking {
                val c = LlmConfig("http://127.0.0.1:${server.address.port}/v1", "", "test", 0f, 800, 1, "test")
                val started = System.nanoTime()
                LlmCorrector().correct(c, "原文") shouldBe "校对后"
                val elapsed = (System.nanoTime() - started) / 1000000
                (elapsed >= 1900) shouldBe true
                println("REVIEW llmTimeoutMs=1000 actualMs=$elapsed")
                val job = launch { LlmCorrector().correct(c, "原文") }
                delay(200)
                val cancelAt = System.nanoTime()
                job.cancelAndJoin()
                val cancelMs = (System.nanoTime() - cancelAt) / 1000000
                (cancelMs >= 1000) shouldBe true
                println("REVIEW llmCancelAndJoinMs=$cancelMs")
            }
        } finally { server.stop(0); executor.shutdownNow() }
    }
})
