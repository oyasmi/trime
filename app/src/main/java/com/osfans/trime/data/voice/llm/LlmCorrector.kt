/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.voice.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URI

data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val temperature: Float,
    val maxTokens: Int,
    val timeoutSeconds: Int,
    val systemPrompt: String,
)

/**
 * OpenAI-`/chat/completions`-compatible AI correction client.
 *
 * This is the **only** network call site in the app besides model downloading (see
 * doc/voice-input-design.md §8.2) — it only ever runs when the user has explicitly enabled AI
 * correction.
 *
 * [correct] never throws and never returns a blank string: any failure (network, timeout,
 * non-2xx, malformed JSON, empty/truncated response) is logged and falls back to [asrText]
 * unchanged. Losing the user's dictated text because an *optional enhancement* failed is not an
 * acceptable failure mode — see doc/voice-input-design.md's D6.
 *
 * [test] is the one exception: it's used by the settings screen's "test correction" button,
 * whose entire purpose is to surface *why* a request failed, so it reports the real error
 * instead of silently falling back.
 */
class LlmCorrector {
    companion object {
        /** Not user-configurable — see doc/voice-input-design.md §7; matches voice-typer's default. */
        const val DEFAULT_MAX_TOKENS = 800
    }

    class CorrectionException(
        message: String,
    ) : Exception(message)

    @Serializable
    private data class ChatMessage(
        val role: String,
        val content: String,
    )

    @Serializable
    private data class ChatRequest(
        val model: String,
        val temperature: Float,
        @SerialName("max_tokens") val maxTokens: Int,
        val messages: List<ChatMessage>,
    )

    @Serializable
    private data class ChatMessageContent(
        val content: String? = null,
    )

    @Serializable
    private data class ChatChoice(
        val message: ChatMessageContent? = null,
        @SerialName("finish_reason") val finishReason: String? = null,
    )

    @Serializable
    private data class ChatResponse(
        val choices: List<ChatChoice> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun correct(
        config: LlmConfig,
        asrText: String,
    ): String {
        if (config.baseUrl.isBlank() || config.model.isBlank()) return asrText
        return withContext(Dispatchers.IO) {
            try {
                requestCorrection(config, asrText)
            } catch (t: Exception) {
                Timber.w(t, "AI correction failed, falling back to ASR text")
                asrText
            }
        }
    }

    /** For the settings screen's "test correction" button — reports the real failure reason. */
    suspend fun test(
        config: LlmConfig,
        text: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            Result.success(requestCorrection(config, text))
        } catch (t: Exception) {
            Result.failure(t)
        }
    }

    private fun requestCorrection(
        config: LlmConfig,
        asrText: String,
    ): String {
        val endpoint =
            when (val resolved = LlmEndpoint.resolve(config.baseUrl)) {
                is LlmEndpoint.Result.Valid -> resolved.url
                is LlmEndpoint.Result.Invalid -> throw CorrectionException("invalid base URL: ${resolved.reason}")
            }

        val requestBody =
            ChatRequest(
                model = config.model,
                temperature = config.temperature,
                maxTokens = maxOf(config.maxTokens, asrText.length * 2 + 128),
                messages =
                listOf(
                    ChatMessage(role = "system", content = config.systemPrompt),
                    ChatMessage(role = "user", content = "<asr_text>$asrText</asr_text>"),
                ),
            )
        val requestJson = json.encodeToString(requestBody)

        val connection = URI(endpoint).toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = config.timeoutSeconds * 1000
        connection.readTimeout = config.timeoutSeconds * 1000
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        if (config.apiKey.isNotBlank()) {
            connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
        }

        connection.outputStream.use { it.write(requestJson.toByteArray(Charsets.UTF_8)) }

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }
            throw CorrectionException("HTTP $responseCode ${errorBody.orEmpty().take(200)}")
        }

        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val response =
            try {
                json.decodeFromString<ChatResponse>(body)
            } catch (t: Exception) {
                throw CorrectionException("malformed response: ${t.message}")
            }

        val choice = response.choices.firstOrNull() ?: throw CorrectionException("empty choices")
        if (choice.finishReason == "length") {
            throw CorrectionException("response was truncated (finish_reason=length)")
        }
        val content = stripAsrTextTags(choice.message?.content.orEmpty())
        if (content.isEmpty()) throw CorrectionException("empty response content")
        return content
    }

    private fun stripAsrTextTags(text: String): String = text
        .replace("<asr_text>", "")
        .replace("</asr_text>", "")
        .trim()
}
