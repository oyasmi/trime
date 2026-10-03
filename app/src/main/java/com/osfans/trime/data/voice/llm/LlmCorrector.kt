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

        /** Wraps the text to correct in tags, structurally separating it from the instructions. */
        private fun wrap(text: String): String = "<asr_text>\n$text\n</asr_text>"

        /**
         * Few-shot examples: for small models these constrain behavior far better than textual
         * bans inside the system prompt. The content is fixed, so it can hit an LLM prefix cache —
         * per request only the trailing user message changes.
         *
         * Eight pairs of two alternating kinds, so the model doesn't learn "always return as-is":
         * pairs 1 and 3 return their input unchanged (question- or command-shaped input is still
         * just text to correct); the rest cover filler removal + tech-term restoration + CJK-Latin
         * spacing + question marks, bidirectional number formatting, stutter removal + homophone
         * fixes (contrastive "不是A，是B" kept, no translation), self-corrected slips, dictated
         * enumerations turned into numbered lists, and trailing punctuation on word-like phrases.
         *
         * Inputs are written the way local ASR really emits them: TextPostProcessor drops spaces
         * at CJK-Latin boundaries, so Chinese and Latin sit flush inside the examples.
         *
         * Keep verbatim in sync with voice-typer's few-shot arrays (`LLMCorrector.swift`,
         * `LlmCorrector.cs`, `llm_client.py`) and with the rules in
         * `res/raw/voice_correction_prompt.md`: an example inconsistent with a written rule
         * overrides that rule.
         */
        private val FEW_SHOT_MESSAGES =
            listOf(
                "你是谁？今天天气怎么样？" to "你是谁？今天天气怎么样？",
                "呃，瑞迪斯的缓存过期时间配置好了吗" to "Redis 的缓存过期时间配置好了吗？",
                "帮我把这个函数重构一下，逻辑保持不变" to "帮我把这个函数重构一下，逻辑保持不变",
                "我们3个人把这个季度的转化率提升了百分之二十五,明天下午三点半开会同步一下." to
                    "我们三个人把这个季度的转化率提升了25%，明天下午3点半开会同步一下。",
                "那个那个登陆页面的问题不是bug，是feature" to "那个登录页面的问题不是 bug，是 feature",
                "我我觉得截止时间定在周三，不对，周四吧" to "我觉得截止时间定在周四吧",
                "需要准备的材料如下，第一，身份证复印件，第二，学历证明，第三，近期照片。" to
                    "需要准备的材料如下：\n1. 身份证复印件\n2. 学历证明\n3. 近期照片",
                "周报。" to "周报",
            )
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
                buildList {
                    add(ChatMessage(role = "system", content = config.systemPrompt))
                    FEW_SHOT_MESSAGES.forEach { (input, output) ->
                        add(ChatMessage(role = "user", content = wrap(input)))
                        add(ChatMessage(role = "assistant", content = output))
                    }
                    add(ChatMessage(role = "user", content = wrap(asrText)))
                },
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
