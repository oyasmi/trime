// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.voice.llm

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress

/**
 * [LlmCorrector.correct] must never throw and must never lose the user's ASR text: every one of
 * these failure modes falls back to the original text unchanged. See
 * doc/voice-input-design.md's D6.
 */
class LlmCorrectorTest :
    StringSpec({
        val asrText = "因该是这样地"

        fun config(
            baseUrl: String,
            timeoutSeconds: Int = 2,
        ) = LlmConfig(
            baseUrl = baseUrl,
            apiKey = "sk-test",
            model = "test-model",
            temperature = 0f,
            maxTokens = 800,
            timeoutSeconds = timeoutSeconds,
            systemPrompt = "correct the text",
        )

        fun withServer(
            handler: HttpHandler,
            block: (baseUrl: String) -> Unit,
        ) {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/v1/chat/completions", handler)
            server.start()
            try {
                block("http://127.0.0.1:${server.address.port}/v1")
            } finally {
                server.stop(0)
            }
        }

        fun jsonResponse(
            exchange: HttpExchange,
            status: Int,
            body: String,
        ) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        "returns corrected text on a normal successful response" {
            withServer(
                { exchange ->
                    jsonResponse(
                        exchange,
                        200,
                        """{"choices":[{"message":{"content":"应该是这样的"},"finish_reason":"stop"}]}""",
                    )
                },
            ) { baseUrl ->
                runBlocking {
                    LlmCorrector().correct(config(baseUrl), asrText) shouldBe "应该是这样的"
                }
            }
        }

        "strips echoed <asr_text> tags from the response" {
            withServer(
                { exchange ->
                    jsonResponse(
                        exchange,
                        200,
                        """{"choices":[{"message":{"content":"<asr_text>应该是这样的</asr_text>"}}]}""",
                    )
                },
            ) { baseUrl ->
                runBlocking {
                    LlmCorrector().correct(config(baseUrl), asrText) shouldBe "应该是这样的"
                }
            }
        }

        "falls back to the original text on HTTP 401" {
            withServer(
                { exchange -> jsonResponse(exchange, 401, """{"error":"unauthorized"}""") },
            ) { baseUrl ->
                runBlocking {
                    LlmCorrector().correct(config(baseUrl), asrText) shouldBe asrText
                }
            }
        }

        "falls back to the original text on malformed JSON" {
            withServer(
                { exchange -> jsonResponse(exchange, 200, "not json at all") },
            ) { baseUrl ->
                runBlocking {
                    LlmCorrector().correct(config(baseUrl), asrText) shouldBe asrText
                }
            }
        }

        "falls back to the original text on empty choices" {
            withServer(
                { exchange -> jsonResponse(exchange, 200, """{"choices":[]}""") },
            ) { baseUrl ->
                runBlocking {
                    LlmCorrector().correct(config(baseUrl), asrText) shouldBe asrText
                }
            }
        }

        "falls back to the original text when finish_reason is length" {
            withServer(
                { exchange ->
                    jsonResponse(
                        exchange,
                        200,
                        """{"choices":[{"message":{"content":"truncated cont"},"finish_reason":"length"}]}""",
                    )
                },
            ) { baseUrl ->
                runBlocking {
                    LlmCorrector().correct(config(baseUrl), asrText) shouldBe asrText
                }
            }
        }

        "falls back to the original text when the response content is blank" {
            withServer(
                { exchange -> jsonResponse(exchange, 200, """{"choices":[{"message":{"content":"  "}}]}""") },
            ) { baseUrl ->
                runBlocking {
                    LlmCorrector().correct(config(baseUrl), asrText) shouldBe asrText
                }
            }
        }

        "falls back to the original text on a request timeout" {
            withServer(
                { exchange ->
                    Thread.sleep(3000)
                    jsonResponse(exchange, 200, """{"choices":[{"message":{"content":"too late"}}]}""")
                },
            ) { baseUrl ->
                runBlocking {
                    LlmCorrector().correct(config(baseUrl, timeoutSeconds = 1), asrText) shouldBe asrText
                }
            }
        }

        "does not touch the network at all when base URL is blank" {
            runBlocking {
                LlmCorrector().correct(config(""), asrText) shouldBe asrText
            }
        }

        "test() reports success with the corrected text" {
            withServer(
                { exchange -> jsonResponse(exchange, 200, """{"choices":[{"message":{"content":"应该是这样的"}}]}""") },
            ) { baseUrl ->
                runBlocking {
                    val result = LlmCorrector().test(config(baseUrl), asrText)
                    result.getOrNull() shouldBe "应该是这样的"
                }
            }
        }

        "test() reports the real failure reason instead of falling back" {
            withServer(
                { exchange -> jsonResponse(exchange, 401, """{"error":"unauthorized"}""") },
            ) { baseUrl ->
                runBlocking {
                    val result = LlmCorrector().test(config(baseUrl), asrText)
                    result.isFailure shouldBe true
                }
            }
        }
    })
