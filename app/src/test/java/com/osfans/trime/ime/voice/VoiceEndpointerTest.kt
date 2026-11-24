// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.ime.voice

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class VoiceEndpointerTest :
    StringSpec({
        "times out in silence without treating a short transient as speech" {
            val endpoint = VoiceEndpointer(startedAtMs = 0)
            endpoint.observe(0.04f, 500) shouldBe false
            endpoint.observe(0.002f, 560) shouldBe false
            endpoint.observe(0.002f, 6_999) shouldBe false
            endpoint.observe(0.002f, 7_000) shouldBe true
            endpoint.hasSpeech shouldBe false
        }

        "keeps listening across a short pause and ends after the last voiced frame" {
            val endpoint = VoiceEndpointer(startedAtMs = 0)
            endpoint.observe(0.03f, 100) shouldBe false
            endpoint.observe(0.04f, 160) shouldBe false
            endpoint.observe(0.03f, 220) shouldBe false
            endpoint.hasSpeech shouldBe true
            endpoint.observe(0.003f, 820) shouldBe false
            endpoint.observe(0.03f, 900) shouldBe false
            endpoint.observe(0.003f, 1_699) shouldBe false
            endpoint.observe(0.003f, 1_700) shouldBe true
        }
    })
