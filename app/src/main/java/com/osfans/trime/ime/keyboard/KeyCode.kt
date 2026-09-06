/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.view.KeyEvent
import com.osfans.trime.core.RimeKeyMapping
import com.osfans.trime.util.virtualKeyCharacterMap
import timber.log.Timber

object KeyCode {
    /**
     * 主题里 `send:` 的历史拼写 —— 兼容层，别删。
     *
     * 3.3.x 之前 `KeyCode` 是一个 `ordinal == Android keycode` 的枚举，条目名用 X11 keysym 拼写，
     * 所以主题写的是 `send: Mode_switch`、`send: function`、`send: Find`。改用生成的 [RimeKeyMapping]
     * 之后只认 Android 的 `KEYCODE_*` 拼写，内置主题在同一个提交里跟着改了，第三方和用户主题没有。
     *
     * 后果是这类按键**静默失效**：`toggle`/`states` 仍能解析，键面照常显示「中」，但 `code` 退化成
     * `KEYCODE_UNKNOWN`，于是 `onAction` 落到 `handleDefaultKeyAction`，最终把 `VoidSymbol` 发给 rime。
     * 这里按老枚举的 ordinal 一一还原；这些名字都不与现有任何解析路径冲突（都是当前解析不出来的）。
     */
    internal val LEGACY_NAME_TO_KEY_CODE =
        mapOf(
            "_0" to KeyEvent.KEYCODE_0,
            "_1" to KeyEvent.KEYCODE_1,
            "_2" to KeyEvent.KEYCODE_2,
            "_3" to KeyEvent.KEYCODE_3,
            "_4" to KeyEvent.KEYCODE_4,
            "_5" to KeyEvent.KEYCODE_5,
            "_6" to KeyEvent.KEYCODE_6,
            "_7" to KeyEvent.KEYCODE_7,
            "_8" to KeyEvent.KEYCODE_8,
            "_9" to KeyEvent.KEYCODE_9,
            "KP_Begin" to KeyEvent.KEYCODE_DPAD_CENTER,
            "Clear" to KeyEvent.KEYCODE_CLEAR,
            "Menu" to KeyEvent.KEYCODE_MENU,
            "Find" to KeyEvent.KEYCODE_SEARCH,
            "Mode_switch" to KeyEvent.KEYCODE_SWITCH_CHARSET,
            "Scroll_Lock" to KeyEvent.KEYCODE_SCROLL_LOCK,
            "function" to KeyEvent.KEYCODE_FUNCTION,
            "Sys_Req" to KeyEvent.KEYCODE_SYSRQ,
            "Pause" to KeyEvent.KEYCODE_BREAK,
            "Next" to KeyEvent.KEYCODE_FORWARD,
            "Num_Lock" to KeyEvent.KEYCODE_NUM_LOCK,
            "KP_Separator" to KeyEvent.KEYCODE_NUMPAD_COMMA,
            "KP_Equal" to KeyEvent.KEYCODE_NUMPAD_EQUALS,
            "parenleft" to KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN,
            "parenright" to KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN,
            "_3D_MODE" to KeyEvent.KEYCODE_3D_MODE,
            "Muhenkan" to KeyEvent.KEYCODE_MUHENKAN,
            "Henkan" to KeyEvent.KEYCODE_HENKAN,
            "yen" to KeyEvent.KEYCODE_YEN,
            "_11" to KeyEvent.KEYCODE_11,
            "_12" to KeyEvent.KEYCODE_12,
            "Help" to KeyEvent.KEYCODE_HELP,
            "Pointer_UpLeft" to KeyEvent.KEYCODE_DPAD_UP_LEFT,
            "Pointer_DownLeft" to KeyEvent.KEYCODE_DPAD_DOWN_LEFT,
            "Pointer_UpRight" to KeyEvent.KEYCODE_DPAD_UP_RIGHT,
            "Pointer_DownRight" to KeyEvent.KEYCODE_DPAD_DOWN_RIGHT,
        )

    fun isStandardKey(code: Int): Boolean = code in 1 until RimeKeyMapping.SYMBOL_CODE_OFFSET

    fun nameToKeyCode(name: String): Int {
        Timber.d("nameToKeyCode: $name")
        if (name.isEmpty()) return KeyEvent.KEYCODE_UNKNOWN

        RimeKeyMapping.upperNameToCode(name)?.let { return it }
        RimeKeyMapping.symbolNameToCode(name)?.let { return it }
        RimeKeyMapping.charToCode(name)?.let { return it }
        LEGACY_NAME_TO_KEY_CODE[name]?.let { return it }

        val androidCode = KeyEvent.keyCodeFromString("KEYCODE_$name")
        if (androidCode > 0) return androidCode

        val rimeCode = RimeKeyMapping.nameToKeyCode(name)
        if (rimeCode != KeyEvent.KEYCODE_UNKNOWN) return rimeCode

        return KeyEvent.KEYCODE_UNKNOWN
    }

    fun codeToKeyName(code: Int): String? {
        RimeKeyMapping.upperCodeToName(code)?.let { return it }
        RimeKeyMapping.symbolCodeToName(code)?.let { return it }

        val rimeName = RimeKeyMapping.keyCodeToName(code)
        if (rimeName != null) return rimeName

        val androidName = KeyEvent.keyCodeToString(code)
        if (androidName.isNotEmpty() && androidName != "KEYCODE_UNKNOWN") {
            return androidName.removePrefix("KEYCODE_")
        }

        return null
    }

    fun getDisplayLabel(
        code: Int,
        mask: Int,
    ): String = if (isStandardKey(code)) {
        if (virtualKeyCharacterMap.isPrintingKey(code)) {
            val charCode = virtualKeyCharacterMap.get(code, mask)
            Timber.d("getDisplayLabel: keyCode=$code, mask=$mask, charCode=$charCode")
            if (charCode > 0) {
                charCode.toChar().toString()
            } else {
                virtualKeyCharacterMap.getDisplayLabel(code).lowercase()
            }
        } else {
            val name = codeToKeyName(code) ?: ""
            name.removePrefix("KP_")
        }
    } else {
        RimeKeyMapping.symbolCodeToLabel(code) ?: codeToKeyName(code) ?: ""
    }

    fun parse(repr: String): Pair<Int, Int> {
        if (repr.isEmpty()) return 0 to 0
        var modifiers = 0
        var start = 0
        while (true) {
            val found = repr.indexOf('+', start)
            if (found == -1) break

            val token = repr.substring(start, found)
            val modifier =
                when (token) {
                    "Shift" -> KeyEvent.META_SHIFT_ON
                    "Control" -> KeyEvent.META_CTRL_ON
                    "Alt" -> KeyEvent.META_ALT_ON
                    "Lock" -> KeyEvent.META_CAPS_LOCK_ON
                    else -> null
                }
            if (modifier != null) {
                modifiers = modifiers or modifier
            } else {
                Timber.e("Unrecognized modifier '$token'")
                return 0 to 0
            }
            start = found + 1
        }
        val token = repr.substring(start)
        // A single uppercase letter (e.g. 'A') is the letter key with Shift
        // held, just like on a physical keyboard. Do not use the synthetic
        // uppercase codes from RimeKeyMapping.upperNameToCode: they carry no
        // shift modifier, so in ascii mode rime would reject the bare
        // uppercase keysym and the fallback would commit the lowercase
        // letter instead.
        if (modifiers == 0 && token.length == 1 && token[0] in 'A'..'Z') {
            return KeyEvent.KEYCODE_A + (token[0] - 'A') to KeyEvent.META_SHIFT_ON
        }
        val keycode = nameToKeyCode(token)
        if (keycode == 0) {
            Timber.e("Unrecognized key '$token'")
            return 0 to 0
        }
        return keycode to modifiers
    }
}
