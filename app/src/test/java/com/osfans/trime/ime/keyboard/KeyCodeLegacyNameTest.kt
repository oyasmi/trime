/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.view.KeyEvent
import com.osfans.trime.core.RimeKeyMapping
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * 老主题用 X11 keysym 拼写写 `send:`（`send: Mode_switch` 等）。内置主题在
 * `d9d248ca` 里换成了 Android 的 `KEYCODE_*` 拼写，第三方/用户主题没有；如果解析不了这些名字，
 * 按键会静默失效——键面照常渲染（`toggle`/`states` 仍然解析），按下去却只把 `VoidSymbol` 发给 rime。
 *
 * 这些断言全部走 `KeyCode.LEGACY_NAME_TO_KEY_CODE` 那条分支，在 `KeyEvent.keyCodeFromString()`
 * 之前返回，因此不需要 Android 运行时（`KeyEvent.KEYCODE_*` 是编译期常量）。
 */
class KeyCodeLegacyNameTest :
    BehaviorSpec({
        Given("a theme written before the KEYCODE_* rename") {
            When("its `send:` value is an X11 keysym name") {
                Then("the 中/西文 switch key resolves to SWITCH_CHARSET") {
                    // 正是这个键失效导致「中英文切换键没反应，一直是中」。
                    KeyCode.nameToKeyCode("Mode_switch") shouldBe KeyEvent.KEYCODE_SWITCH_CHARSET
                }

                Then("`parse` yields the same code, so KeyAction gets a real keycode") {
                    KeyCode.parse("Mode_switch") shouldBe (KeyEvent.KEYCODE_SWITCH_CHARSET to 0)
                }

                Then("modifiers still combine with a legacy name") {
                    KeyCode.parse("Control+Mode_switch") shouldBe
                        (KeyEvent.KEYCODE_SWITCH_CHARSET to KeyEvent.META_CTRL_ON)
                }

                Then("the other legacy names resolve to the codes the old enum gave them") {
                    val expected =
                        mapOf(
                            "function" to KeyEvent.KEYCODE_FUNCTION,
                            "Find" to KeyEvent.KEYCODE_SEARCH,
                            "Menu" to KeyEvent.KEYCODE_MENU,
                            "Clear" to KeyEvent.KEYCODE_CLEAR,
                            "Henkan" to KeyEvent.KEYCODE_HENKAN,
                            "Muhenkan" to KeyEvent.KEYCODE_MUHENKAN,
                            "Num_Lock" to KeyEvent.KEYCODE_NUM_LOCK,
                            "Scroll_Lock" to KeyEvent.KEYCODE_SCROLL_LOCK,
                            "Sys_Req" to KeyEvent.KEYCODE_SYSRQ,
                            "Pause" to KeyEvent.KEYCODE_BREAK,
                            "Next" to KeyEvent.KEYCODE_FORWARD,
                            "Help" to KeyEvent.KEYCODE_HELP,
                            "KP_Begin" to KeyEvent.KEYCODE_DPAD_CENTER,
                            "KP_Separator" to KeyEvent.KEYCODE_NUMPAD_COMMA,
                            "KP_Equal" to KeyEvent.KEYCODE_NUMPAD_EQUALS,
                            "parenleft" to KeyEvent.KEYCODE_NUMPAD_LEFT_PAREN,
                            "parenright" to KeyEvent.KEYCODE_NUMPAD_RIGHT_PAREN,
                            "yen" to KeyEvent.KEYCODE_YEN,
                            "_0" to KeyEvent.KEYCODE_0,
                            "_9" to KeyEvent.KEYCODE_9,
                            "_11" to KeyEvent.KEYCODE_11,
                            "_12" to KeyEvent.KEYCODE_12,
                            "_3D_MODE" to KeyEvent.KEYCODE_3D_MODE,
                            "Pointer_UpLeft" to KeyEvent.KEYCODE_DPAD_UP_LEFT,
                            "Pointer_DownLeft" to KeyEvent.KEYCODE_DPAD_DOWN_LEFT,
                            "Pointer_UpRight" to KeyEvent.KEYCODE_DPAD_UP_RIGHT,
                            "Pointer_DownRight" to KeyEvent.KEYCODE_DPAD_DOWN_RIGHT,
                        )
                    expected.forEach { (name, code) ->
                        withClue(name) { KeyCode.nameToKeyCode(name) shouldBe code }
                    }
                }
            }

            When("checking the compatibility table against the generated mapping") {
                Then("no legacy name shadows a name the generated tables already resolve") {
                    // 兼容层排在 KEYCODE_* 查表之前，所以它必须只覆盖当前解析不出来的名字。
                    // `KeyEvent.keyCodeFromString()` 在 JVM 单测里没有实现，无法在这里断言；
                    // 这张表是拿 android.jar 的 KEYCODE_* 常量集做过差集的，见提交说明。
                    KeyCode.LEGACY_NAME_TO_KEY_CODE.keys.forEach { name ->
                        withClue(name) {
                            RimeKeyMapping.upperNameToCode(name) shouldBe null
                            RimeKeyMapping.symbolNameToCode(name) shouldBe null
                            RimeKeyMapping.charToCode(name) shouldBe null
                            RimeKeyMapping.nameToKeyCode(name) shouldBe KeyEvent.KEYCODE_UNKNOWN
                        }
                    }
                }

                Then("every legacy name maps to a real Android keycode") {
                    KeyCode.LEGACY_NAME_TO_KEY_CODE.forEach { (name, code) ->
                        withClue(name) { (code > KeyEvent.KEYCODE_UNKNOWN) shouldBe true }
                    }
                }
            }
        }
    })
