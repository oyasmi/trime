# 语音输入录音反馈重设计 · 从「整屏浮层」收敛到「键盘状态行」

> 前置文档：`doc/voice-input-design.md`（总体设计，本文沿用其术语与决策编号，新增决策从 D11 起）
>
> 背景：语音输入链路已在真机跑通（`feat/voice-input` 分支，10 个提交）。功能正确，
> 但**录音反馈的视觉强度严重超标**：按住空格键开始说话时整个屏幕变成一块不透明的白，
> 把用户正在使用的 App 和键盘一起盖掉。本文只解决这一个问题。

---

## 1. 问题

真机实测现象：按住空格键触发「按住说话」→ 全屏纯白 + 屏幕正中「正在聆听…」+ 一条横跨屏幕的大波形。
松手后恢复。功能没问题，**反馈过载**：

- 遮住了用户正在输入的目标 App（聊天记录、输入框、上下文全没了）；
- 遮住了键盘本身，用户失去「我按在哪个键上」的空间感；
- 浅色主题下是大面积高亮白，在暗环境里刺眼。

### 1.1 根因（两个独立缺陷叠加）

**缺陷一：浮层的布局范围是整屏，不是键盘。**

`InputView.kt:255` 把浮层以整屏尺寸加进了布局：

```kotlin
add(
    voice.root,
    lParams(matchParent, matchParent) {  // ← 这里
        centerInParent()
    },
)
```

`InputView` 继承自 `BaseInputView : ConstraintLayout`，它**本身就是整屏高**的——
这是为了让 `popup.root`（按键长按气泡）能画到键盘上方的区域，紧挨着它的
`voice.root` 于是继承了同样的整屏可绘制范围。`doc/voice-input-design.md` §5.7
原本写的是「覆盖 `keyboardView` 的区域」，实现时写成了 `matchParent` 覆盖整个 `InputView`，
这是设计与实现的偏差。

**缺陷二：浮层给这整块区域铺了不透明背景。**

`VoiceOverlayUi.applyColors()`：

```kotlin
val backgroundDrawable = runCatching { ColorManager.getDecorDrawable("keyboard_background") }.getOrNull()
if (backgroundDrawable != null) root.background = backgroundDrawable
else root.setBackgroundColor(backgroundColor)   // keyboard_back_color
```

浅色主题下 `keyboard_back_color` 就是接近白的颜色，铺满整屏即「整屏纯白」。

两个缺陷必须一起改：只缩小范围而保留不透明背景，会变成「键盘整块变白」，依然过强。

### 1.2 顺带发现的三个既有缺陷（重写时一并修掉）

读 `VoiceOverlayUi` 时确认的，都在本次要重写的两个方法里，不额外扩大改动面。

| # | 缺陷 | 位置 | 后果 |
| --- | --- | --- | --- |
| C1 | `render()` 在**每一个 amplitude 帧**都调 `waveform.start()`，而 `start()` 会 `history.fill(0f)` | `VoiceOverlayUi.kt:100` | **波形其实是坏的**：每秒 20 次清空历史，只有最新采样留在数组里，画出来是「最右一根柱子在跳」，不是滚动波形 |
| C2 | `statusText.text` 同样在每帧重新赋值，`TextView.setText` 即使内容相同也会 `requestLayout()` | `VoiceOverlayUi.kt:102-105` | 录音期间键盘顶部每秒被 requestLayout 20 次 |
| C3 | `ColorUtils.calculateContrast(fg, bg)` 在 `bg` 带 alpha 时抛 `IllegalArgumentException`，而 trime 主题**明确支持** `0xAARRGGBB`（`util/ColorUtils.parseColor`）；此处的 `runCatching` 只包住了 `getColor`，没包住对比度计算 | `VoiceOverlayUi.kt:88` | 主题把 `keyboard_back_color` 写成 8 位带 alpha 时，异常穿透 `init` → 穿透 `VoiceInputDelegate.overlay` 的 `by lazy` → **第一次按语音键就崩** |

C1 尤其重要：它意味着「波形太大太刺眼」这个观感里，有一部分其实是**波形根本没在正常工作**。
缩小尺寸的同时必须把它修对，否则缩小后更看不出它是坏的。

修法见实施计划 §1 与 §3.3。

---

## 2. 目标与非目标

### 2.1 目标

| # | 目标 | 验收标准 |
| --- | --- | --- |
| F1 | 反馈完全不遮挡目标 App | 录音全程，键盘顶边以上的像素**一个都不改变**（截图逐像素比对） |
| F2 | 反馈收进键盘区域内的单一位置 | 反馈占用高度 ≤ 一行候选栏（默认主题约 40dp），横向不超出键盘宽度 |
| F3 | 反馈永远不被手指遮住 | 位置在键盘顶部；「上滑取消」手势最远只到距空格键 48dp（`CANCEL_ENTER_DP`），够不到顶部状态行 |
| F4 | 四个状态可辨识 | 聆听中 / 松手取消 / 识别中 / AI 校对中，在余光扫一眼的条件下可区分 |
| F5 | 不破坏长按手势 | 浮层保持触摸穿透，不切 `BoardWindow`（沿用 D7）；真机连续 20 次按住说话零中断 |
| F6 | 跟随主题 | 状态行配色从 `ColorManager` 取，与候选栏视觉连续，深/浅色主题都可读 |

### 2.2 非目标

- 不改语音识别链路、状态机（`VoiceSession` / `VoiceSessionState`）、上屏逻辑——**本次改动只在 UI 层**。
- 不做录音计时显示（原设计 §5.7 提过，现有实现没做，本次也不加：一行里塞不下，且价值低）。
- 不做「点按切换」模式的停止按钮区（`VoiceTriggerMode.TOGGLE` 下浮层需可点击的停止区）——
  现有实现也没做，属于独立议题，见 §8 遗留项。
- 不改 `WaveformView` 的驱动方式（仍由 `AudioRecorder` 节流到 ~20fps 推 `updateAmplitude`）。

---

## 3. 方案：键盘顶部状态行（替换候选栏那一行）

录音期间，用一条**与候选栏等高、等宽、同背景**的状态行原地覆盖候选栏/工具栏；
键盘按键、周边留白、目标 App 全部不动。

```
┌───────────────────────────────┐
│                               │
│   App 内容 —— 一个像素都不改   │
│                               │
├───────────────────────────────┤
│ 🎤 ▁▂▃▅▇▅▃▂▁   正在聆听…      │ ← 状态行，高度 = candidate bar
├───────────────────────────────┤
│  q w e r t y u i o p          │
│   a s d f g h j k l           │  键盘完全可见、完全可用
│  ⇧ z x c v b n m  ⌫           │
│  ?123  ,  [  ␣  ]  . ↵        │
└──────────────☝ 手指按住────────┘
```

### 3.1 为什么是这里（备选方案与否决理由）

| 方案 | 否决理由 |
| --- | --- |
| 候选栏内浮一个 wrap-content 小胶囊，背景保持透明 | 录音时候选栏里显示的是**工具栏按钮**（`AlwaysUi`，不是空白），胶囊会与按钮图标叠在一起，视觉更乱；且透明背景在花色 `keyboard_background` 上可读性不可控 |
| 键盘中部浮动小卡片 | 正好落在「上滑取消」的手指移动路径上（阈值 48dp，手指会滑到倒数第 2~3 排），录音时被自己的手挡住；且盖住字母键 |
| 吸附在空格键正上方的小气泡（类按键预览） | 同上，且更严重——那就是手指上滑必经之地；还需要拿到空格键的屏幕坐标，要么改 `KeyView` 要么做坐标反查，成本高收益负 |
| 键盘区域整体加半透明遮罩 + 中央卡片 | 仍然是「一大块变暗」，只是从整屏缩到键盘；用户明确要求「克制、小」 |

顶部状态行胜出的核心理由是 **F3**：它是键盘区域内唯一一处「手指物理上够不到」的地方。

### 3.2 状态 → 视觉映射

| `VoiceSessionState` | 图标 | 波形 | 文案 | 配色 |
| --- | --- | --- | --- | --- |
| `Recording(cancelling = false)` | `ic_baseline_mic_24` 16dp | 显示，实时跟音量 | `voice_state_listening`「正在聆听…」 | 常规前景色 |
| `Recording(cancelling = true)` | `ic_baseline_close_24` 16dp（**新增**） | 隐藏 | `voice_state_release_to_cancel`「松手取消」 | **警示色（整行图标+文字同时变色）** |
| `Recognizing` | 16dp 不确定态 `ProgressBar` | 隐藏 | `voice_state_recognizing`「识别中…」 | 常规前景色 |
| `Correcting` | 同上 | 隐藏 | `voice_state_correcting`「AI 校对中…」 | 常规前景色 |
| `Idle` | —— | —— | —— | 状态行隐藏 |
| `Error` | —— | —— | —— | 状态行隐藏（错误由 `VoiceInputDelegate` toast，维持现状） |

状态切换时**布局不重排**：图标槽固定 16dp、波形槽固定宽度（见 §4.2），
波形靠 `isInvisible` 而非 `isGone` 让位，避免文字左右横跳。

### 3.3 「上滑取消」的提示

沿用同一行原地变化，不新增任何布局：

```
聆听中:  🎤 ▁▂▃▅▇▅▃▂▁   正在聆听…      （常规前景色）
                ↓ 手指上滑越过 48dp 阈值
上滑后:  ✕  松手取消                    （整行转警示色）
```

图标换形（麦克风 → ✕）+ 文案换 + 整行变色，三个信号同时给，余光可辨。
不加振动——`VoiceInputDelegate.onHoldMove` 已有 48dp 进入 / 32dp 退出的迟滞
（`CANCEL_ENTER_DP` / `CANCEL_EXIT_DP`），但抖动仍可能触发多次，振动会变成骚扰。

---

## 4. 视觉规格

### 4.1 状态行容器

| 属性 | 取值 |
| --- | --- |
| 宽度 | `matchParent`（与 `inputBar.view` 同宽，即键盘全宽） |
| 高度 | 候选栏可见时：`dp(inputBar.themedHeight)`，与候选栏**逐像素等高**（`themedHeight = candidateViewHeight + commentHeight`，主题决定）<br>候选栏被隐藏时（`keyboard__hide_input_bar`）：`min(dp(themedHeight), dp(48))`，见 §6.1 |
| 位置 | `keyboardView` 内，与 `inputBar.view` 相同约束（`topOfParent()` + `centerHorizontally()`），**最后加入**以保证绘制在最上层 |
| 背景 | `LayerDrawable`：底层 `keyboard_back_color` 纯色（保证不透）+ 上层复用候选栏的 `ColorManager.getDecorDrawable("candidate_background", "candidate_border_color", dp(candidateBorder), dp(candidateBorderRound))` |
| 触摸 | `isClickable = false`、`isFocusable = false`（**不可改**，见 D7 / F5） |
| 内容布局 | 水平 `LinearLayout`，`gravity = CENTER`，`wrapContent` 居中于容器 |

底层铺一层 `keyboard_back_color` 是必要的：部分主题的 `candidate_background` 是透明或半透明的，
不垫底会让下面的工具栏按钮透出来。

### 4.2 内容元素

| 元素 | 尺寸 | 说明 |
| --- | --- | --- |
| 图标槽 | 16dp × 16dp | `FrameLayout`，内含 `ImageView`（mic / ✕）与 `ProgressBar`（16dp，不确定态）二选一显示，**槽宽固定**避免重排 |
| 间距 | 8dp | 图标 → 波形 |
| 波形槽 | 72dp × 16dp（固定） | `WaveformView`，非 `Recording` 状态用 `isInvisible = true` 占位隐藏 |
| 间距 | 10dp | 波形 → 文字 |
| 状态文字 | `textSize = 13f`，单行，`maxLines = 1` | 文案见 §3.2 表 |
| 容器左右内边距 | 12dp | —— |

整行内容总宽约 `12 + 16 + 8 + 72 + 10 + 文字宽 + 12` ≈ 180~200dp，窄屏（360dp）横向富余充足。

### 4.3 `WaveformView` 的调整

现状是为整屏浮层设计的：`barCount = 32`、`barWidthPx = dp(3)`、`barGapPx = dp(2)`，
自然宽度 `32 × 5 - 2 = 158dp`，塞进 72dp 槽里会被裁掉一半。改为：

| 参数 | 现值 | 新值 |
| --- | --- | --- |
| `barCount` | 32 | **14** |
| `barWidthPx` | `dp(3)` | `dp(2.5f)` |
| `barGapPx` | `dp(2)` | `dp(2)` |
| 自然宽度 | 158dp | `14 × 4.5 - 2 = 61dp`（落在 72dp 槽内，居中） |
| `maxBarHeight` | `height / 2 - dp(4)` | `height / 2 - dp(1)`（16dp 高的槽里，`dp(4)` 余量会把柱子压到几乎不可见） |
| `minBarHeight` | `dp(2)` | `dp(1)` |

`barCount` 从 32 降到 14 同时降低了每次 `invalidate()` 的绘制成本，20fps 下开销可忽略。
圆角：柱子改用 `canvas.drawRoundRect(..., barWidthPx / 2, barWidthPx / 2, paint)`，
在 16dp 的小尺寸下直角柱子会显得毛糙。

### 4.4 配色

```
backgroundColor = ColorManager.getColor("keyboard_back_color")   // 兜底 Color.BLACK

常规前景（图标 + 波形 + Progress tint）:
  首选 hilited_candidate_text_color，其次 candidate_text_color，
  取第一个与 backgroundColor 对比度 ≥ 3.0 的；都不满足则取对比度最高的一个；
  全失败兜底 Color.WHITE

常规文字色:
  首选 candidate_text_color，规则同上（对比度阈值 4.5，文字要求更高）

警示色（cancelling）:
  luminance(backgroundColor) > 0.5  →  0xFFD93025   (浅底用深红)
  否则                              →  0xFFFF6B6B   (深底用亮红)
  若所选红色与背景对比度 < 3.0，则用 ColorUtils.blendARGB 向前景色方向拉到达标
```

沿用现有 `applyColors()` 的「按对比度挑色」思路，只是把候选来源从 `key_text_color` 系
换成 `candidate_text_color` 系——因为状态行现在物理上就站在候选栏的位置，应当与候选栏同源。

### 4.5 出现/消失动画

现状是 `TransitionManager` + `Slide(Gravity.BOTTOM)`，100ms。整屏浮层从底部滑入很明显；
40dp 的状态行滑入会像「抖了一下」。改为：

- **出现**：`alpha 0 → 1` + `translationY -4dp → 0`，120ms，`DecelerateInterpolator`
- **消失**：`alpha 1 → 0`，90ms，`AccelerateInterpolator`

直接用 `ViewPropertyAnimator`（`root.animate()`），不再走 `TransitionManager`——
后者会对整个 `keyboardView` 做 layout 快照，对一个固定尺寸的兄弟视图是浪费，
且在按键长按过程中触发父容器 transition 有引发按键状态抖动的风险。
消失时在 `withEndAction` 里置 `isInvisible = true` 并 `waveform.stop()`（见 D16：用 `INVISIBLE` 不用 `GONE`）。

---

## 5. 代码改动清单

全部落在 UI 层，**不新增对上游既有文件的改动处数**（`InputView.kt` 已在 D10 的 8 处清单内）。

| 文件 | 性质 | 改动 |
| --- | --- | --- |
| `ime/voice/ui/VoiceOverlayUi.kt` | 改写 | root 从「整屏 FrameLayout + 铺满背景 + 居中大波形」改为「候选栏尺寸的状态行 + 水平内容行」；`applyColors()` 换配色来源并加警示色；`render()` 按 §3.2 表驱动图标/波形/文案/配色；动画换成 §4.5 |
| `ime/voice/ui/WaveformView.kt` | 调参 | §4.3 的六个参数 + `drawRoundRect` |
| `ime/core/InputView.kt` | 挪位置 | `voice.root` 从 `InputView` 根布局（`matchParent, matchParent`）移入 `keyboardView` 的 `constraintLayout {}`，作为**最后一个** child，`lParams(matchParent, voiceStripHeight) { topOfParent(); centerHorizontally() }` |
| `res/drawable/ic_baseline_close_24.xml` | 新增 | 标准 Material `close` 路径，24dp viewport，`android:tint` 不设（代码里 `setColorFilter`），命名沿用既有 `ic_baseline_*_24` 惯例 |
| `res/values*/strings.xml` | 不动 | 四条 `voice_state_*` 文案原样复用 |
| `ime/voice/VoiceInputDelegate.kt` | 不动 | 状态转发逻辑不变 |
| `ime/voice/VoiceSession*.kt` | 不动 | 状态机不变 |

### 5.1 `InputView.kt` 的具体形态

```kotlin
// keyboardView = constraintLayout { ... } 内，bottomPaddingSpace 之后追加：
add(
    voice.root,
    lParams(matchParent, voiceStripHeight) {
        topOfParent()
        centerHorizontally()
    },
)
```

并删除根布局里原来那段 `add(voice.root, lParams(matchParent, matchParent) { centerInParent() })`。

`voiceStripHeight` 就近计算：

```kotlin
private val voiceStripHeight: Int
    get() = dp(inputBar.themedHeight).let {
        // 候选栏被主题/设置隐藏时，状态行会压在第一排按键上；限高避免吃掉两排键
        if (inputBar.view.visibility == View.VISIBLE) it else minOf(it, dp(48))
    }
```

放在 `keyboardView` 内而不是 `InputView` 根布局，好处是横向自动跟随键盘宽度与
`updateKeyboardSize()` 的留白逻辑，不需要自己对齐 `leftPaddingSpace` / `rightPaddingSpace`。

**绘制顺序**：`voice.root` 是 `keyboardView` 的最后一个 child → 盖在 `inputBar.view` 与
`windowManager.view` 之上；而 `popup.root`（按键气泡）仍在 `InputView` 根布局里、加在
`keyboardView` 之后 → 仍然盖在状态行之上。层级关系保持正确。

---

## 6. 边界情况

### 6.1 候选栏被隐藏（`keyboard__hide_input_bar = true`）

`InputBarDelegate.view` 直接 `View.GONE`，键盘顶部没有那一行。此时状态行会覆盖第一排按键的上半部分。

**接受这个行为**，理由：录音期间用户手指按在空格键上，物理上不可能同时按字母键，
临时遮住第一排半排键零代价；而它仍然满足 F1（不碰 App）和 F3（够不到手指）。
限高 48dp 是为了在 `themedHeight` 很大的主题下不至于吃掉两整排键。

### 6.2 主题的候选栏特别高

`themedHeight = candidateViewHeight + commentHeight`，理论上可以很大。候选栏可见时**不限高**——
状态行必须与候选栏逐像素等高才能做到「原地替换」而不露出边缘缝隙。
高度大时内容行仍然垂直居中，视觉上是一条更宽的带子，可接受。

### 6.3 横屏

`isLandscapeMode` 下键盘更矮更宽，`themedHeight` 由主题的横屏配置决定，逻辑不变。
内容行约 200dp，横屏宽度富余更多。

### 6.4 符号窗口（`LiquidWindow`）打开时触发语音

状态行在 `keyboardView` 内、与 `windowManager.view` 平级且在其之上，
不管当前挂的是 `KeyboardWindow` 还是符号窗口都能正常显示——这条性质与 D7 一致，不退化。

### 6.5 主题切换

`applyColors()` 现在只在 `VoiceOverlayUi.init` 调用一次，主题切换后配色是陈旧的。
但主题切换会重建整个 `InputView`（连带 DI 容器与 `VoiceInputDelegate`），
所以实际不会出问题。**本次不改**，但在 `applyColors()` 上加一行注释说明这个依赖，
避免以后有人把 `VoiceInputDelegate` 提升成跨 `InputView` 的单例时踩坑。

### 6.6 无障碍

现状 `importantForAccessibility = NO`。改为：
- root 仍 `IMPORTANT_FOR_ACCESSIBILITY_NO`（不可点，不该被 TalkBack 聚焦）；
- 状态文字 `TextView` 设 `accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE`，
  让 TalkBack 播报状态变化（「正在聆听」→「松手取消」→「识别中」）。

对按住说话的盲用户，这是唯一能感知状态的通道，成本一行。

---

## 7. 验收

### 7.1 真机测试清单

| # | 场景 | 期望 |
| --- | --- | --- |
| T1 | 浅色主题，聊天 App 里按住空格说话 | App 界面与键盘按键全程可见；仅候选栏那一行变为状态行 |
| T2 | 深色主题，同上 | 同上，且文字/波形对比度足够 |
| T3 | 录音中上滑越过阈值再滑回 | 状态行在「🎤 正在聆听…」与「✕ 松手取消」之间来回切换，**文字不左右跳动**，行高不变 |
| T4 | 松手后到上屏 | 依次出现「识别中…」（带小转圈）→（若开 AI 校对）「AI 校对中…」→ 状态行淡出，文字上屏 |
| T5 | 连续按住说话 20 次 | 无一次因浮层导致长按中断（F5 回归） |
| T6 | 说话时音量由小到大 | 波形柱子高度跟随可见变化（确认 `maxBarHeight` 新值在 16dp 槽内仍有足够动态范围） |
| T7 | 关闭「显示候选栏」后录音 | 状态行出现在键盘顶部，最多占 48dp，第二排及以下按键完全可见 |
| T8 | 符号窗口打开时用工具栏语音按钮 | 状态行正常显示在符号窗口顶部 |
| T9 | 录音中切走 App / 收起键盘 | 状态行随会话取消而消失，不残留 |
| T10 | 截图逐像素比对（录音前 vs 录音中）| 键盘顶边以上区域差异为 0（F1） |

### 7.2 不需要新增单元测试

改动全在 `View` 绘制与布局层，`VoiceSessionTest` 覆盖的状态机与
`VoiceInputDelegate` 的转发路径均未改动，现有 5 个测试类应保持全绿（作为回归门槛）。

---

## 8. 遗留项（本次不做，记录在案）

| # | 项 | 说明 |
| --- | --- | --- |
| L1 | `VoiceTriggerMode.TOGGLE` 的停止按钮 | 原设计 §5.7 提到「浮层顶部放一个可点击的停止区」，现有实现没做，点按模式下只能再点一次语音键停止。状态行方案里可以让状态行本身可点击（点击 = 停止），但那会破坏「全程触摸穿透」这条不变量，需要按模式区分 `isClickable`，单独议题 |
| L2 | 录音计时 | 一行里塞不下，且 `maxDurationSeconds` 到点已有 toast 兜底 |
| L3 | 主题热切换时刷新配色 | 见 §6.5，当前架构下不会触发 |

---

## 9. 新增决策

| # | 决策 | 理由 |
| --- | --- | --- |
| D11 | 录音反馈收进「键盘顶部候选栏那一行」，而非整屏/键盘中部/空格键上方 | 这是键盘区域内唯一手指够不到、又不遮 App 不遮键的位置（F1 + F3） |
| D12 | 状态行原地替换候选栏，背景垫一层 `keyboard_back_color` 后叠候选栏 decor | 录音时候选栏里是工具栏按钮不是空白，半透明会糊；垫底保证任何主题下都不透光 |
| D13 | 保留实时波形但缩到 14 根 / 72×16dp | 波形是「麦克风确实在收音」的唯一实时证据，删掉会让失败排查变盲；缩小后既克制又保留信息 |
| D14 | 取消提示 = 同一行换图标 + 换文案 + 整行转警示色，不加振动 | 零额外布局、三重信号可余光辨识；阈值抖动会让振动变骚扰 |
| D15 | 动画用 `ViewPropertyAnimator` 淡入淡出，弃用 `TransitionManager` + `Slide` | 对固定尺寸的兄弟视图做父容器 transition 是浪费，且在长按过程中有触发按键状态抖动的风险 |
| D16 | 可见性切换用 `INVISIBLE` 而非 `GONE` | 状态行是 `keyboardView` 的 child，`GONE ⇄ VISIBLE` 会在**用户正按住按键时**触发键盘容器的 measure/layout；`INVISIBLE` 让布局完全静止，只有绘制在变 |
