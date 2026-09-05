/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.voice

/**
 * Local, pure-function cleanup of SenseVoice's raw output text. No network, no state — this is
 * the cheapest piece of the feature to unit test exhaustively.
 */
object TextPostProcessor {
    // SenseVoice language/emotion/event markers, e.g. "<|zh|><|NEUTRAL|><|Speech|><|withitn|>".
    private val TAG_REGEX = Regex("<\\|[^|>]*\\|>")

    // Trailing punctuation across ASCII and common CJK punctuation.
    private val TRAILING_PUNCT_REGEX = Regex("[\\s。，、！？!?,.;；:：]+$")

    // A CJK "word" character; used to decide whether a space between a CJK run and a non-CJK
    // run should be dropped ("你好 世界" -> "你好世界") while leaving "AI Coding" untouched.
    private val CJK_REGEX = Regex("[\\u4e00-\\u9fff\\u3400-\\u4dbf\\uff00-\\uffef]")

    /**
     * @param trimTrailingPunctuation corresponds to `voice__trim_trailing_punct`; off by default.
     * @return the cleaned text, or an empty string if nothing but punctuation/whitespace remains
     *   (SenseVoice frequently emits a lone "。" for silent/near-silent audio — that must not be
     *   committed to the input field).
     */
    fun process(
        raw: String,
        trimTrailingPunctuation: Boolean,
    ): String {
        var text = raw.replace(TAG_REGEX, "")
        text = collapseWhitespaceAroundCjk(text)
        text = text.trim()
        if (!containsAnyLetterOrDigitOrCjk(text)) return ""
        if (trimTrailingPunctuation) {
            text = text.replace(TRAILING_PUNCT_REGEX, "")
        }
        return text
    }

    private fun containsAnyLetterOrDigitOrCjk(text: String): Boolean = text.any { it.isLetterOrDigit() }

    /**
     * Collapses runs of whitespace, and additionally removes whitespace that sits directly
     * between a CJK character and any other character (CJK doesn't use spaces between words),
     * while leaving spaces inside a non-CJK run alone (so "AI Coding" stays "AI Coding").
     */
    private fun collapseWhitespaceAroundCjk(text: String): String {
        val collapsed = text.replace(Regex("\\s+"), " ")
        val sb = StringBuilder(collapsed.length)
        var i = 0
        while (i < collapsed.length) {
            val c = collapsed[i]
            if (c == ' ') {
                val prev = sb.lastOrNull()
                val next = collapsed.getOrNull(i + 1)
                val touchesCjk = (prev != null && CJK_REGEX.matches(prev.toString())) ||
                    (next != null && CJK_REGEX.matches(next.toString()))
                if (!touchesCjk) sb.append(c)
            } else {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }
}
