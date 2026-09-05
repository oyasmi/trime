// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.voice.llm

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class LlmEndpointTest :
    StringSpec({
        "appends /chat/completions to a bare https base URL" {
            val result = LlmEndpoint.resolve("https://api.example.com/v1")
            (result.shouldBeInstanceOf<LlmEndpoint.Result.Valid>()).url shouldBe
                "https://api.example.com/v1/chat/completions"
        }

        "does not duplicate the suffix when already present" {
            val result = LlmEndpoint.resolve("https://api.example.com/v1/chat/completions")
            (result.shouldBeInstanceOf<LlmEndpoint.Result.Valid>()).url shouldBe
                "https://api.example.com/v1/chat/completions"
        }

        "trims a trailing slash before resolving" {
            val result = LlmEndpoint.resolve("https://api.example.com/v1/")
            (result.shouldBeInstanceOf<LlmEndpoint.Result.Valid>()).url shouldBe
                "https://api.example.com/v1/chat/completions"
        }

        "allows plain http for localhost" {
            val result = LlmEndpoint.resolve("http://localhost:11434/v1")
            result.shouldBeInstanceOf<LlmEndpoint.Result.Valid>()
        }

        "allows plain http for a loopback IPv4 literal" {
            val result = LlmEndpoint.resolve("http://127.0.0.1:8080/v1")
            result.shouldBeInstanceOf<LlmEndpoint.Result.Valid>()
        }

        "allows plain http for a private LAN address" {
            val result = LlmEndpoint.resolve("http://192.168.1.10:8000/v1")
            result.shouldBeInstanceOf<LlmEndpoint.Result.Valid>()
        }

        "rejects plain http for a public host" {
            val result = LlmEndpoint.resolve("http://api.example.com/v1")
            result.shouldBeInstanceOf<LlmEndpoint.Result.Invalid>()
        }

        "rejects an unsupported scheme" {
            val result = LlmEndpoint.resolve("ftp://api.example.com/v1")
            result.shouldBeInstanceOf<LlmEndpoint.Result.Invalid>()
        }

        "rejects an empty URL" {
            val result = LlmEndpoint.resolve("")
            result.shouldBeInstanceOf<LlmEndpoint.Result.Invalid>()
        }

        "rejects a URL without a host" {
            val result = LlmEndpoint.resolve("https:///v1")
            result.shouldBeInstanceOf<LlmEndpoint.Result.Invalid>()
        }
    })
