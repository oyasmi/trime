/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.voice.llm

import java.net.URI

/**
 * Structured parsing/resolution of the user-configured base URL into a concrete
 * `/chat/completions` endpoint.
 *
 * Rules (mirrors voice-typer's `LLMEndpoint`, which has its own test suite there):
 * - scheme must be `https`, unless the host is loopback or a private-network address, in which
 *   case plain `http` is also allowed (self-hosted/local servers);
 * - the user may supply either a bare base (`https://api.example.com/v1`) or the full completions
 *   URL already (`https://api.example.com/v1/chat/completions`) — both resolve to the same thing.
 */
object LlmEndpoint {
    private const val COMPLETIONS_SUFFIX = "/chat/completions"

    sealed interface Result {
        data class Valid(
            val url: String,
        ) : Result

        data class Invalid(
            val reason: String,
        ) : Result
    }

    fun resolve(baseUrl: String): Result {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return Result.Invalid("empty base URL")

        val uri =
            try {
                URI(trimmed)
            } catch (e: Exception) {
                return Result.Invalid("malformed URL: ${e.message}")
            }

        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        if (scheme == null || host.isNullOrEmpty()) {
            return Result.Invalid("URL must include a scheme and host")
        }
        when (scheme) {
            "https" -> Unit
            "http" -> {
                if (!isLoopbackOrPrivate(host)) {
                    return Result.Invalid("plain http is only allowed for loopback/private hosts")
                }
            }
            else -> return Result.Invalid("unsupported scheme: $scheme")
        }

        val resolved = if (trimmed.endsWith(COMPLETIONS_SUFFIX)) trimmed else trimmed + COMPLETIONS_SUFFIX
        return Result.Valid(resolved)
    }

    private fun isLoopbackOrPrivate(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true)) return true
        val parts = host.split(".")
        if (parts.size != 4 || parts.any { it.toIntOrNull() !in 0..255 }) {
            // Not a dotted-quad IPv4 literal (e.g. a LAN hostname without mDNS resolution info
            // available here) — err on the side of rejecting plain http for anything we can't
            // positively identify as loopback/private.
            return false
        }
        val octets = parts.map { it.toInt() }
        val (a, b) = octets[0] to octets[1]
        return a == 127 ||
            a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168)
    }
}
