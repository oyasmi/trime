<!--
SPDX-FileCopyrightText: 2015 - 2024 Rime community

SPDX-License-Identifier: GPL-3.0-or-later
-->

# Liquid Keyboard 參數

(3.2.17), 只有使用 `single_width` 。其餘的還未有作用。

- `single_width` : 在 `SINGLE` 類別中，每項的闊度 (和高度相等)。

新增一些参数 `fixed_key_bar:` 用于自定义液态键盘中的固定按键条：

例：
```yaml
liquid_keyboard:
  fixed_key_bar:  # 固定按键条
    position: bottom  # 摆放位置（在滚动区域的……） top|bottom|left|right （上/下/左/右）
    keys:  # 按键（显示名称为对应的label，不能放太多）
      - liquid_keyboard_exit  # 返回键（在preset_keys内定义，同下）
      - space1  # 空格键
      - BackSpace  # 退格键
      - Return2  # 回车键
      - liquid_keyboard_clipboard  # “剪贴板”跳转按键
      - liquid_keyboard_switch  # “更多”跳转按键
```

# 藍芽鍵盤問題
https://github.com/osfans/trime/issues/1058#issuecomment-1672635413

# 橫屏鍵盤

(3.2.17) 新增兩個參數到 `preset_keyboards` :

- `landscape_keyboard`: 橫屏時轉用的 keyboard id。當判定需顯示橫屏鍵盤時，會顯示此值代表的鍵盤。該 id 需包含在 `style/keyboards`中。
- `landscape_split_percent`:  0 - 200。此值只在橫屏時生效，優先等級高於「鍵盤設定」>「自動分割鍵盤比例」。此值代表鍵盤自動分割時中間的空白闊度比例。如：
	- `100` 代表 100%，即空白的闊度與鍵盤的總闊度比例為 1:1。
	- `150` 的話，代表空白的闊度與鍵盤的總闊度比例為 1.5:1。
	- `0` 代表不做自動分割。

例：
```
patch:
  "style/keyboard": [my_keyboard, my_landscape_keyboard, mini, .....]
  "preset_keyboards/my_keyboard":
    name: My Keyboard
    landscape_keyboard: my_landscape_keyboard
    ...
    keys:
      - ......
  "preset_keyboards/my_landscape_keyboard":
    name: My Landscape Keyboard
    landscape_split_percent: 0
    ...
    keys:
      - ......
```
以上例子代表：
直屏時使用 `my_keyboard` 
橫屏時使用 `my_landscape_keyboard`，並且不做自動分割。若 `landscape_split_percent` > 0，則會分割顯示。

# 语音输入键位绑定

需先在「设置 → 语音输入」中开启功能并下载识别模型。把任意键位的 `send`（点按或长按）绑定为 `VOICE_ASSIST` 即可：

```yaml
space:
  click: space
  long_click: { send: VOICE_ASSIST }  # 长按空格触发"按住说话"
```

工具栏按钮同样支持，把按钮的 `action` 指向 `VOICE_ASSIST` 即可：

```yaml
toolbar:
  buttons:
    - { action: VOICE_ASSIST, click: VOICE_ASSIST }
```

交互方式（按住说话 / 点按切换）在「设置 → 语音输入 → 触发方式」中配置，与键位绑定无关——同一个键位在两种模式下行为不同：按住说话模式下需要真正按住并长按触发；点按切换模式下点一下开始、再点一下结束。
