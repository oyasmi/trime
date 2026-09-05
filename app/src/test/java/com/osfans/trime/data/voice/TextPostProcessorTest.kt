// SPDX-FileCopyrightText: 2015 - 2026 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.osfans.trime.data.voice

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class TextPostProcessorTest :
    StringSpec({
        "strips SenseVoice language/emotion/event tags" {
            TextPostProcessor.process("<|zh|><|NEUTRAL|><|Speech|><|withitn|>你好", false) shouldBe "你好"
        }

        "collapses whitespace touching CJK characters" {
            TextPostProcessor.process("你好 世界", false) shouldBe "你好世界"
        }

        "keeps spaces between non-CJK words" {
            TextPostProcessor.process("AI Coding", false) shouldBe "AI Coding"
        }

        "returns empty string for punctuation-only output" {
            TextPostProcessor.process("。", false) shouldBe ""
        }

        "returns empty string for whitespace-only output" {
            TextPostProcessor.process("   ", false) shouldBe ""
        }

        "returns empty string when only tags remain" {
            TextPostProcessor.process("<|zh|><|withitn|>", false) shouldBe ""
        }

        "keeps real text with trailing punctuation when trimming is off" {
            TextPostProcessor.process("你好。", false) shouldBe "你好。"
        }

        "trims trailing punctuation when requested" {
            TextPostProcessor.process("你好。", true) shouldBe "你好"
        }

        "trims trailing punctuation across ascii and CJK marks" {
            TextPostProcessor.process("hello, world!", true) shouldBe "hello, world"
        }

        "does not trim punctuation in the middle of the text" {
            TextPostProcessor.process("你好，世界", true) shouldBe "你好，世界"
        }

        "trims leading and trailing whitespace" {
            TextPostProcessor.process("  hello  ", false) shouldBe "hello"
        }
    })
