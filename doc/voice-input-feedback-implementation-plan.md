# 语音输入反馈重设计 · 实施计划

> **本文的读者是后续的实施 agent。** 先读 [`voice-input-feedback-design.md`](voice-input-feedback-design.md)
> （做什么、为什么这么做），再读本文（照着做、做到什么算完）。
> 底层背景在 [`voice-input-design.md`](voice-input-design.md) 与
> [`voice-input-implementation-plan.md`](voice-input-implementation-plan.md)，本文不重复。
>
> 本文里出现的行号、API 签名、颜色解析行为、构建命令、测试基线，
> 均已在 `feat/voice-input` @ `35462606` 上**逐条实测核对**过，可直接照抄。
>
> 改动规模预估：**4 个文件（3 改 1 增），净增约 +170 / −80 行，无新依赖、无新权限、无新字符串资源。**

---

## 0. 工作约定

### 0.1 环境（已实测，直接用）

本机 `JAVA_HOME` 指向不存在的 `/opt/homebrew/opt/openjdk`，且 `~/Library/Java/.../openjdk-24.0.1`
是 **x86_64** 的，在 arm64 Mac 上直接执行会 `Bad CPU type in executable`。
可用的是 Homebrew 的 **arm64 JDK 21**：

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

**每个 gradle 命令前都要带上它**（或在 shell 里 export 一次）。已验证：
`java -version` → `openjdk 21.0.11 (arm64)`；`:app:testDebugUnitTest` 全绿。

其它环境事实：

| 项 | 状态 |
| --- | --- |
| `adb` | 在 `~/Library/Android/sdk/platform-tools/adb`，**不在 PATH 上** |
| 连接的设备 | 当前 `adb devices` 为空；无 AVD。V5 需要用户插真机 |
| 原生构建 | **已修好**（`c539d3e2` 给 rime-lua 钉了 `_FILE_OFFSET_BITS=32`），release 包能出：`app/build/outputs/apk/release/com.osfans.trime-c539d3e2-arm64-v8a-release.apk` 18.1MB |
| 单测基线 | **14 个测试类 / 99 个用例 / 0 失败**（`app/build/test-results/testDebugUnitTest/*.xml`）|

### 0.2 硬约束

1. **本次改动只允许碰 UI 层。** 不得修改：
   `VoiceSession.kt`、`VoiceSessionState.kt`、`VoiceInputDelegate.kt`、`AudioRecorder.kt`、
   `AudioFocusGuard.kt`、`data/voice/**`、`KeyView.kt`、`CommonKeyboardActionListener.kt`、
   `KeyboardActionListener.kt`。
   它们已在真机验证通过，本次没有任何理由动它们。
2. **不得引入**任何新依赖（尤其不要为了动画引入 Lottie / MotionLayout / Compose）。
3. **不得新增 `strings.xml` 条目**：四条 `voice_state_*` 文案原样复用，三份 values 目录都不用改。
4. **三条不变量，破坏其一即本次改动失败**：
   - **I1 触摸穿透**：浮层 root 永远 `isClickable = false` + `isFocusable = false`，
     且不设 `OnTouchListener`。否则「按住空格说话」当场断（旧坑 #2 / #4）。
   - **I2 不切 `BoardWindow`**：反馈只能是 `keyboardView` 里的一个普通 child（D7）。
   - **I3 不触碰键盘顶边以上的像素**：这是本次改动的全部意义（F1）。
5. **允许改动的文件，共 4 个**：

   | 文件 | 性质 | 任务 |
   | --- | --- | --- |
   | `app/src/main/res/drawable/ic_baseline_close_24.xml` | **新增** | V1 |
   | `app/src/main/java/com/osfans/trime/ime/voice/ui/WaveformView.kt` | 改（调参） | V2 |
   | `app/src/main/java/com/osfans/trime/ime/voice/ui/VoiceOverlayUi.kt` | 改（重写） | V3 |
   | `app/src/main/java/com/osfans/trime/ime/core/InputView.kt` | 改（约 −7 / +14 行） | V4 |

   需要第 5 个文件时**先停下来**，在 §6「计划外改动记录」追加一行说明再动手。
   `InputView.kt` 已在上一轮 §0.5 的上游文件清单里，不算新增侵入点。

### 0.3 代码风格

- 新增的 XML 资源顶部必须有 SPDX 头（项目用 REUSE 强制），格式照抄 `ic_baseline_mic_24.xml`。
- Kotlin 由 spotless + ktlint 1.7.1 强制。import 按字母序，`as` 别名 import 排最后
  （现有 `VoiceOverlayUi.kt` 就是这个形状，照抄）。
- 注释用英文（`ime/voice/**` 现有文件都是英文注释），文档用中文。
- commit：`fix(voice): ...`，conventional commits。本次全部改动可以合成 **1 个 commit**。

### 0.4 每个任务收尾前必跑

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
make spotlessApply && make spotlessCheck
./gradlew :app:testDebugUnitTest      # 必须仍是 99/99
./gradlew :app:assembleDebug
```

V4 之后额外跑一次 release（本次没动 proguard，但 `InputView` 是热路径，便宜保险）：

```bash
./gradlew :app:assembleRelease
```

REUSE 检查（V1 之后跑一次即可）：

```bash
make reuse
```

---

## 1. 现有实现的三个缺陷（本次必须一并修掉）

这三个是读代码时**实测确认**的，不是猜测。它们都在要重写的 `VoiceOverlayUi.render()` /
`applyColors()` 里，重写时顺手修掉，不要漏。

### C1 · 波形其实是坏的（`VoiceOverlayUi.kt:100`）

```kotlin
is VoiceSessionState.Recording -> {
    show()
    waveform.start()                              // ← 每帧都调
    waveform.updateAmplitude(state.amplitude)
```

`VoiceSession` 在录音时是这样推状态的（`VoiceSession.kt:120`）：

```kotlin
setState(current.copy(amplitude = amplitude))     // ~20fps，每次都走 onStateChange → render()
```

而 `WaveformView.start()` 会 `history.fill(0f); writeIndex = 0`。
于是**每一帧历史都被清空**，只有最新一个采样被写进 `history[0]`，
绘制时它落在最右一根柱子上，其余 13/31 根恒为 `minBarHeight`。
用户看到的不是滚动波形，是「最右边一根柱子在跳」。

**修法**：`start()` 只在 `Idle → Recording` 的**入场**那一次调用。

### C2 · 每秒 20 次无谓的 TextView 重排（`VoiceOverlayUi.kt:102-105`）

`statusText.text = ...` 在每个 amplitude 帧都重新赋值。`TextView.setText` 即使内容相同也会触发
`requestLayout()`。录音期间键盘顶部每秒被 requestLayout 20 次，纯浪费。

**修法**：文案只在**状态相位切换**时设置（见 §3.3 的 `applyPhase` + `lastPhase` 守卫）。

### C3 · 主题相关的潜在崩溃（`VoiceOverlayUi.kt:88`）

```kotlin
candidateColors.firstOrNull { ColorUtils.calculateContrast(it, backgroundColor) >= 2.5 }
```

`androidx.core.graphics.ColorUtils.calculateContrast(fg, bg)` 在 **`bg` 不是完全不透明时直接抛
`IllegalArgumentException("background can not be translucent")`**。
而 trime 的主题颜色**明确支持 alpha**——`util/ColorUtils.parseColor` 里有
`else -> "#$sub" // 0x(AA)RRGGBB -> #(AA)RRGGBB` 这条分支。
只要用户的主题把 `keyboard_back_color` 写成 8 位带 alpha 的值，
`applyColors()` 抛异常 → 传播出 `VoiceOverlayUi.init` → 传播出 `VoiceInputDelegate.overlay` 的 `by lazy`
→ **第一次按语音键就崩**。

这条 `runCatching` 没兜住（`runCatching` 只包了 `getColor`，没包 `calculateContrast`）。

**修法**：所有进入对比度计算的背景色先强制不透明：`背景色 or 0xFF000000.toInt()`。

---

## 2. 任务分解

依赖：`V0 → V1 → V2 → V3 → V4 → V5(gate) → V6`。
V1/V2/V3/V4 强烈建议**一次做完再编译**（V3 引用 V1 的资源、V2 的参数；V4 引用 V3 的 root）。

---

### V0 · 基线确认与改前取证

**做什么**

1. 确认在 `feat/voice-input` 分支、工作区干净：`git status`。
2. 建立可编译基线：

   ```bash
   export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
   ./gradlew :app:testDebugUnitTest    # 期望 99/99
   ```
3. **若此刻有真机连着**，先抓一组「改前」证据（V5 要拿它做对比）：

   ```bash
   ADB=~/Library/Android/sdk/platform-tools/adb
   $ADB devices                                     # 确认有设备
   $ADB exec-out screencap -p > /tmp/voice-before-idle.png
   # 手动：按住空格开始说话，保持按住，另开一个终端跑下一行
   $ADB exec-out screencap -p > /tmp/voice-before-recording.png
   ```
   没有真机就跳过，V5 会补。

**完成判据**：单测 99/99；（若有真机）两张改前截图已保存。

---

### V1 · 新增关闭图标资源

**做什么**：新建 `app/src/main/res/drawable/ic_baseline_close_24.xml`，
内容见 §3.1（Material Design 标准 `close` 路径，24dp viewport，
`fillColor` 用 `@android:color/white`，实际颜色在代码里用 `imageTintList` 覆盖）。

**为什么要新增**：`res/drawable/` 里有 `ic_baseline_mic_24`、`ic_baseline_delete_24` 等 30 个
`ic_baseline_*_24`，但**没有 close/clear/cancel**（已 grep 确认）。

**完成判据**：`make reuse` 通过（SPDX 头没漏）；`./gradlew :app:assembleDebug` 能引用到
`R.drawable.ic_baseline_close_24`。

---

### V2 · `WaveformView` 小型化

**做什么**：按 §3.2 改 6 个参数 + 把 `drawRect` 换成 `drawRoundRect`。
**不要动**公开 API（`start` / `stop` / `updateAmplitude` / `setWaveformColor`）——
`VoiceOverlayUi` 依赖它们，且这个类的注释里写明了它是刻意的一等公民 View。

关键算术（自己验一遍，别信描述）：

| 量 | 旧 | 新 |
| --- | --- | --- |
| 自然宽度 `barCount * (w + gap) - gap` | `32 * 5 - 2 = 158dp` | `14 * 4.5 - 2 = 61dp` |
| 柱子最大**半高** `height/2 - 余量` | `h/2 - 4dp` | `h/2 - 1dp` |
| 在 16dp 高的槽里的最大**全高** | `16/2-4 = 4` → **8dp** | `16/2-1 = 7` → **14dp** |

旧的余量 `dp(4)` 是给整屏浮层（几百 dp 高）设计的，塞进 16dp 槽里会把波形压成一条几乎看不见的线。

**完成判据**：编译通过；`WaveformView` 的公开签名一字未改。

---

### V3 · `VoiceOverlayUi` 重写为键盘状态行

**这是本次的核心任务。** 整个文件按 §3.3 重写。要点：

1. **布局**：root `FrameLayout`（左右 padding 12dp）→ 居中一条 `horizontalLayout`
   → `[16dp 图标槽] 8dp [72×16dp 波形槽] 10dp [13sp 状态文字]`。
   图标槽是一个 `FrameLayout`，里面叠 `ImageView`（mic / close）与
   `ProgressBar(context, null, android.R.attr.progressBarStyleSmall)`，同一时刻只显示一个——
   **槽宽固定 16dp**，这样三个相位切换时文字不会左右横跳（T3 要验这个）。
2. **可见性用 `INVISIBLE` 而不是 `GONE`**（`androidx.core.view.isInvisible`）。
   理由：root 是 `keyboardView` 的 child，`GONE ↔ VISIBLE` 会触发 `keyboardView` 的
   measure/layout；虽然按约束关系其它 view 不会移动，但在**用户正按住按键的过程中**触发父容器
   重新布局是不必要的风险。`INVISIBLE` 让布局完全静止，只有绘制在变。
3. **动画用 `ViewPropertyAnimator`**，删掉 `TransitionManager` + `Slide` 与它们的 import。
   进场 `alpha 0→1` + `translationY -4dp→0` / 120ms / `DecelerateInterpolator`；
   出场 `alpha 1→0` / 90ms / `AccelerateInterpolator`，`withEndAction` 里收尾。
   出场的 end action 必须用 `shown` 标志兜一层，防止「刚 hide 又 show」时被过期回调打回 INVISIBLE。
4. **背景**：`LayerDrawable(底 = ColorDrawable(keyboard_back_color), 上 = 候选栏的 decor drawable)`。
   decor 参数要与 `InputBarDelegate.kt:250` **完全一致**：
   `getDecorDrawable("candidate_background", "candidate_border_color", dp(candidateBorder), dp(candidateBorderRound))`。
   底下垫一层纯色是必须的：部分主题的 `candidate_background` 是透明/半透明，
   不垫底会让下面的工具栏按钮透出来。
   （已确认 `ColorManager.parseDrawable` 每次都 `new` 一个 Drawable，
   不与候选栏共享实例，可以安全持有。）
5. **配色**：前景色从 `candidate_text_color` 系取（不再用 `key_text_color` 系）——
   状态行现在物理上就站在候选栏的位置，应当与候选栏同源。
   对比度计算前把背景强制不透明（**C3**）。三个颜色（图标色 / 文字色 / 警示色）在
   `applyColors()` 里一次算好存字段，`render()` 里只做赋值，不做颜色运算。
6. **`render()` 重构**：`Error` 直接 early return；`Recording` 只在入场调 `waveform.start()`（**C1**）；
   相位切换走 `applyPhase(Phase)` 且自带 `lastPhase` 幂等守卫（**C2**）；
   `cancelling` 时不再往波形推 amplitude（波形此刻不可见，省掉无谓的 `invalidate`）。
7. **无障碍**：root 保持 `IMPORTANT_FOR_ACCESSIBILITY_NO`（它不可点，不该被 TalkBack 聚焦），
   但给 `statusText` 设 `accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE`。
   注意 `NO` 只让 **root 自己**不重要，**不影响子 view**（那是 `NO_HIDE_DESCENDANTS`），
   所以 live region 仍然生效。这是盲用户在「按住说话」时唯一能感知状态的通道。
8. 在 `applyColors()` 上加一行注释说明「只在构造时调用一次；主题切换会重建整个 `InputView`
   连带 DI 容器，所以不需要重算」——避免以后有人把 `VoiceInputDelegate` 提成跨 `InputView`
   的单例时踩坑。

**完成判据**：编译通过；`VoiceOverlayUi` 对外只暴露 `root` 与 `render(state)`（签名不变，
`VoiceInputDelegate` 零改动）；文件里不再出现 `matchParent` 铺满背景的写法。

---

### V4 · `InputView` 布局迁移

**做什么**（精确 diff 见 §3.4）：

1. **删除** `InputView.kt:258-264` 那段把 `voice.root` 加到根布局的代码：
   ```kotlin
   add(
       voice.root,
       lParams(matchParent, matchParent) {
           centerInParent()
       },
   )
   ```
2. **在 `keyboardView = constraintLayout { ... }` 块的最后**（`bottomPaddingSpace` 那个 `add` 之后，
   即当前的第 `238` 行之后）加上：
   ```kotlin
   add(
       voice.root,
       lParams(matchParent, voiceStripHeight) {
           topOfParent()
           centerHorizontally()
       },
   )
   ```
3. 新增私有属性 `voiceStripHeight`（见 §3.4）。

**为什么放进 `keyboardView` 而不是 `InputView` 根布局**：
`keyboardView` 是 `matchParent` 宽、贴底、`wrapContent` 高的容器，
放进去就自动跟随键盘宽度与 `updateKeyboardSize()` 的留白逻辑，不用自己去对齐
`leftPaddingSpace` / `rightPaddingSpace`。

**绘制层级要核对清楚**（改完自己在脑子里过一遍）：

```
InputView (整屏高 ConstraintLayout)
├─ preedit.ui.root
├─ keyboardView                      ← matchParent 宽，贴底
│  ├─ keyboardBackground
│  ├─ inputBar.view                  ← 候选栏/工具栏（可能 GONE）
│  ├─ left/rightPaddingSpace
│  ├─ windowManager.view             ← 键盘窗口 / 符号窗口
│  ├─ bottomPaddingSpace
│  └─ voice.root      ★ 新位置       ← 最后一个 child ⇒ 盖在候选栏与键盘窗口之上
└─ popup.root                        ← 仍在 keyboardView 之后 ⇒ 按键气泡仍盖在状态行之上
```

**触摸为什么仍然穿透**：`ViewGroup.dispatchTouchEvent` 按 z 序倒序问子 view，
`clickable=false` 且无 touch listener 的 view 的 `onTouchEvent` 返回 false，
事件继续下发给下一个 child。这与浮层原来挂在 `InputView` 根布局时**是同一套机制**，
只是换了个父容器，不构成新风险。另外 `ACTION_DOWN` 早于状态行出现，
手势的 target 已经锁定在 `KeyView` 上，后续 MOVE/UP 根本不会重新分发。

**注意**：`updateKeyboardSize()` 里给 `inputBar.view` 和 `preedit.ui.root` 设了
`setPadding(sidePadding, 0, sidePadding, 0)`。**状态行不需要跟着设**——它的内容是居中的，
左右留白对居中内容没有影响，加这一行只会多一处对上游文件的改动。

**完成判据**：`./gradlew :app:assembleDebug` 与 `:app:assembleRelease` 都过；
`git diff --stat` 显示只动了 §0.2 表里那 4 个文件。

---

### V5 · 真机验收（gate，不要跳过）

需要用户插上真机。`ADB=~/Library/Android/sdk/platform-tools/adb`。

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
ADB=~/Library/Android/sdk/platform-tools/adb
./gradlew :app:installDebug          # 或 make debug
$ADB shell ime list -s               # 确认 trime 已启用
```

逐条跑 §4 的 T1~T10。**T10（逐像素比对）是 F1 的硬证据，必须做**：

```bash
$ADB exec-out screencap -p > /tmp/voice-after-idle.png
# 按住空格保持录音，另一个终端：
$ADB exec-out screencap -p > /tmp/voice-after-recording.png
# 比对键盘顶边以上的区域（键盘顶边的 y 坐标从截图里量）
python3 - <<'PY'
from PIL import Image
a = Image.open('/tmp/voice-after-idle.png').convert('RGB')
b = Image.open('/tmp/voice-after-recording.png').convert('RGB')
assert a.size == b.size, (a.size, b.size)
KEYBOARD_TOP_Y = 1200          # ← 按实际截图量出来的键盘顶边 y，改这里
box = (0, 0, a.size[0], KEYBOARD_TOP_Y)
diff = sum(1 for p, q in zip(a.crop(box).getdata(), b.crop(box).getdata()) if p != q)
print('differing pixels above keyboard:', diff)     # 期望 0
PY
```
（`PIL` 没装就 `pip3 install pillow`，或退而求其次用 `cmp` 比对裁剪后的 PNG。）

**gate 判据**：T1~T10 全过。任何一条不过就回到对应任务修，不要带着问题往下走。
特别地：**T5（连续 20 次按住说话零中断）不过 = I1/I2 被破坏 = 必须回滚重做**。

---

### V6 · 收尾

1. `make spotlessApply && make spotlessCheck`
2. `./gradlew :app:testDebugUnitTest`（99/99）
3. `make reuse`
4. 回填本文 §7 的实测数据表。
5. 若实施过程中对设计有任何偏离，**同步改
   [`voice-input-feedback-design.md`](voice-input-feedback-design.md)**，
   并在 §6 记一行——设计稿和代码不一致，就是这次要修的那个 bug 的成因
   （原设计 §5.7 写的是「覆盖 `keyboardView` 的区域」，实现写成了整屏 `matchParent`）。
6. commit：
   ```
   fix(voice): shrink the recording feedback into a keyboard status strip

   The overlay was added to InputView at matchParent×matchParent. InputView
   spans the whole screen (so key popups can draw above the keyboard), and the
   overlay painted an opaque keyboard background across all of it — holding
   space to talk turned the entire screen white, hiding both the target app and
   the keyboard.

   It now renders as a strip that replaces the candidate bar in place: mic icon
   + a 14-bar mini waveform + status text, inside the keyboard, never under the
   user's finger. Also fixes three defects in the old overlay: start() was
   called on every amplitude frame (wiping the waveform history ~20 times a
   second, so only the rightmost bar ever moved), the status text was re-set at
   the same rate (a requestLayout per frame), and calculateContrast() was fed a
   possibly-translucent theme color, which throws.
   ```

---

## 3. 参考实现（核对过，可直接照抄）

### 3.1 `app/src/main/res/drawable/ic_baseline_close_24.xml`（新增）

```xml
<!--
  ~ SPDX-FileCopyrightText: 2015 - 2026 Rime community
  ~ SPDX-License-Identifier: GPL-3.0-or-later
  -->

<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">

    <path
        android:fillColor="@android:color/white"
        android:pathData="M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z" />

</vector>
```

### 3.2 `WaveformView.kt` 的改动（6 处）

```kotlin
// 第 28 行
-    private val barCount = 32
+    // 14 bars fit the 72dp slot in the status strip (14 * 4.5 - 2 = 61dp) and cut the
+    // per-frame draw cost by more than half. See doc/voice-input-feedback-design.md §4.3.
+    private val barCount = 14

// 第 38-39 行
-    private val barWidthPx = dp(3).toFloat()
+    private val barWidthPx = dp(2.5f)
     private val barGapPx = dp(2).toFloat()

// 第 72-73 行（onDraw 内）
-        val minBarHeight = dp(2).toFloat()
-        val maxBarHeight = height / 2f - dp(4)
+        // Both are half-heights: a bar spans centerY ± value. The old dp(4) headroom was
+        // sized for a full-screen overlay; in a 16dp slot it flattens the waveform to 8dp.
+        val minBarHeight = dp(1).toFloat()
+        val maxBarHeight = height / 2f - dp(1)

// 第 83 行
-            canvas.drawRect(x, centerY - barHeight, x + barWidthPx, centerY + barHeight, barPaint)
+            val radius = barWidthPx / 2f
+            canvas.drawRoundRect(
+                x, centerY - barHeight, x + barWidthPx, centerY + barHeight,
+                radius, radius, barPaint,
+            )
```

> `drawRoundRect(float,float,float,float,float,float,Paint)` 是 API 21+，项目 `minSdk = 21`，安全。

### 3.3 `VoiceOverlayUi.kt`（全文重写）

```kotlin
/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.voice.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import com.osfans.trime.R
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.voice.VoiceSessionState
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent
import android.view.ContextThemeWrapper as AndroidContextThemeWrapper

/**
 * The recording feedback: a **touch-transparent** status strip that sits at the top of
 * `keyboardView` and replaces the candidate bar in place while a voice session is running.
 *
 * It is deliberately *not* a `BoardWindow` — switching windows would send `ACTION_CANCEL` to the
 * key being held (see doc/voice-input-design.md §5.7 / D7) — and deliberately *not* a full-size
 * layer either: covering `InputView` (which spans the whole screen, so key popups can draw above
 * the keyboard) is what made the first version paint the entire display opaque. The strip stays
 * inside the keyboard, above every key row, where the user's finger can never reach it — the
 * cancel gesture only travels 48dp up from the space key. See
 * doc/voice-input-feedback-design.md.
 *
 * Owns no session logic; it only renders whatever [VoiceSessionState] it's given via [render].
 */
class VoiceOverlayUi(
    override val di: DI,
) : DIAware {
    private val context: AndroidContextThemeWrapper by instance()
    private val theme: Theme by instance()

    private val icon =
        context.imageView {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

    private val spinner =
        ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            isVisible = false
        }

    /** Fixed-width slot so swapping icon ⇄ spinner never shifts the text sideways. */
    private val iconSlot =
        context.frameLayout {
            add(icon, lParams(matchParent, matchParent))
            add(spinner, lParams(matchParent, matchParent))
        }

    private val waveform = WaveformView(context)

    private val statusText =
        context.textView {
            gravity = Gravity.CENTER
            textSize = 13f
            maxLines = 1
            // The only channel a TalkBack user has while physically holding the space key.
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }

    private val content =
        context.horizontalLayout {
            gravity = Gravity.CENTER
            add(
                iconSlot,
                lParams(dp(ICON_DP), dp(ICON_DP)) { gravity = Gravity.CENTER_VERTICAL },
            )
            add(
                waveform,
                lParams(dp(WAVEFORM_WIDTH_DP), dp(WAVEFORM_HEIGHT_DP)) {
                    gravity = Gravity.CENTER_VERTICAL
                    marginStart = dp(8)
                },
            )
            add(
                statusText,
                lParams(wrapContent, wrapContent) {
                    gravity = Gravity.CENTER_VERTICAL
                    marginStart = dp(10)
                },
            )
        }

    val root =
        context.frameLayout {
            // INVISIBLE rather than GONE: this is a child of `keyboardView`, and toggling GONE
            // would run a measure/layout pass on the keyboard while the user is holding a key
            // down. INVISIBLE keeps the layout completely still — only drawing changes.
            isInvisible = true
            alpha = 0f
            // Never make these true: "hold space to talk" needs the touch stream to keep
            // reaching the KeyView underneath (doc/voice-input-design.md D7).
            isClickable = false
            isFocusable = false
            // Only marks *this* view as unimportant; descendants (statusText's live region)
            // stay visible to accessibility services — that would be NO_HIDE_DESCENDANTS.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setPadding(dp(12), 0, dp(12), 0)
            add(content, lParams(wrapContent, wrapContent) { gravity = Gravity.CENTER })
        }

    private enum class Phase { LISTENING, CANCELLING, RECOGNIZING, CORRECTING }

    private var phase: Phase? = null
    private var shown = false

    @ColorInt private var accentColor = Color.WHITE

    @ColorInt private var textColor = Color.WHITE

    @ColorInt private var warningColor = WARNING_ON_DARK

    init {
        applyColors()
    }

    /**
     * Resolved once, at construction. A theme switch rebuilds the whole `InputView` — and with it
     * the DI container and this object — so there is nothing to invalidate. If `VoiceInputDelegate`
     * ever becomes a longer-lived singleton, this needs to be re-run on theme changes.
     */
    private fun applyColors() {
        val background = themeColor("keyboard_back_color") ?: Color.BLACK
        root.background = buildBackground(background)

        // The strip physically stands where the candidate bar does, so it takes its colors from
        // the candidate palette rather than the key palette.
        accentColor = pickForeground(background, ACCENT_KEYS, minContrast = 3.0)
        textColor = pickForeground(background, TEXT_KEYS, minContrast = 4.5)
        warningColor = pickWarningColor(background, textColor)

        waveform.setWaveformColor(accentColor)
        spinner.indeterminateTintList = ColorStateList.valueOf(accentColor)
    }

    private fun buildBackground(
        @ColorInt background: Int,
    ): Drawable {
        // Same decor parameters as InputBarDelegate's candidate bar, so the strip reads as a
        // replacement for that row rather than a floating panel.
        val decor =
            runCatching {
                ColorManager.getDecorDrawable(
                    "candidate_background",
                    "candidate_border_color",
                    context.dp(theme.generalStyle.candidateBorder),
                    context.dp(theme.generalStyle.candidateBorderRound),
                )
            }.getOrNull()
        // Opaque base underneath: `candidate_background` is transparent in some themes, and the
        // toolbar buttons behind the strip would otherwise show through.
        val base = ColorDrawable(background)
        return if (decor == null) base else LayerDrawable(arrayOf(base, decor))
    }

    @ColorInt
    private fun themeColor(key: String): Int? = runCatching { ColorManager.getColor(key) }.getOrNull()

    @ColorInt
    private fun pickForeground(
        @ColorInt background: Int,
        keys: List<String>,
        minContrast: Double,
    ): Int {
        val opaque = opaque(background)
        val candidates = keys.mapNotNull { themeColor(it) }
        if (candidates.isEmpty()) return onBackgroundFallback(opaque)
        return candidates.firstOrNull { ColorUtils.calculateContrast(it, opaque) >= minContrast }
            ?: candidates.maxByOrNull { ColorUtils.calculateContrast(it, opaque) }
            ?: onBackgroundFallback(opaque)
    }

    @ColorInt
    private fun pickWarningColor(
        @ColorInt background: Int,
        @ColorInt fallback: Int,
    ): Int {
        val opaque = opaque(background)
        val preferred =
            if (ColorUtils.calculateLuminance(opaque) > 0.5) WARNING_ON_LIGHT else WARNING_ON_DARK
        if (ColorUtils.calculateContrast(preferred, opaque) >= 3.0) return preferred
        // Unreadable red on this theme: walk it toward the (already legible) text color.
        var ratio = 0.2f
        while (ratio <= 1f) {
            val blended = ColorUtils.blendARGB(preferred, fallback, ratio)
            if (ColorUtils.calculateContrast(blended, opaque) >= 3.0) return blended
            ratio += 0.2f
        }
        return fallback
    }

    /**
     * `ColorUtils.calculateContrast` throws when the background is translucent, and trime themes
     * may legitimately specify `0xAARRGGBB` colors (see `util/ColorUtils.parseColor`) — so force
     * opacity before any contrast math.
     */
    @ColorInt
    private fun opaque(
        @ColorInt color: Int,
    ): Int = color or OPAQUE_MASK

    @ColorInt
    private fun onBackgroundFallback(
        @ColorInt background: Int,
    ): Int = if (ColorUtils.calculateLuminance(background) > 0.5) Color.BLACK else Color.WHITE

    fun render(state: VoiceSessionState) {
        // Errors are surfaced as a Toast by the caller and are immediately followed by another
        // state (Idle, or — for Busy — the restored ongoing state), so the strip ignores them
        // entirely and keeps whatever it was showing. See doc/voice-input-design.md §9.
        if (state is VoiceSessionState.Error) return
        when (state) {
            is VoiceSessionState.Idle -> hide()
            is VoiceSessionState.Recording -> {
                // `start()` clears the bar history, so it must run once on entry — not on every
                // amplitude frame (~20fps), which is what made the waveform look frozen before.
                if (show()) waveform.start()
                if (state.cancelling) {
                    applyPhase(Phase.CANCELLING)
                } else {
                    applyPhase(Phase.LISTENING)
                    waveform.updateAmplitude(state.amplitude)
                }
            }
            is VoiceSessionState.Recognizing -> {
                show()
                waveform.stop()
                applyPhase(Phase.RECOGNIZING)
            }
            is VoiceSessionState.Correcting -> {
                show()
                waveform.stop()
                applyPhase(Phase.CORRECTING)
            }
            is VoiceSessionState.Error -> Unit
        }
    }

    /** Idempotent: does nothing when [phase] is already current, so it's safe to call per frame. */
    private fun applyPhase(next: Phase) {
        if (phase == next) return
        phase = next

        val warn = next == Phase.CANCELLING
        val foreground = if (warn) warningColor else accentColor

        val iconRes =
            when (next) {
                Phase.LISTENING -> R.drawable.ic_baseline_mic_24
                Phase.CANCELLING -> R.drawable.ic_baseline_close_24
                Phase.RECOGNIZING, Phase.CORRECTING -> null
            }
        if (iconRes != null) icon.setImageResource(iconRes)
        icon.isVisible = iconRes != null
        icon.imageTintList = ColorStateList.valueOf(foreground)
        spinner.isVisible = iconRes == null

        waveform.isInvisible = next != Phase.LISTENING
        waveform.setWaveformColor(foreground)

        statusText.setText(
            when (next) {
                Phase.LISTENING -> R.string.voice_state_listening
                Phase.CANCELLING -> R.string.voice_state_release_to_cancel
                Phase.RECOGNIZING -> R.string.voice_state_recognizing
                Phase.CORRECTING -> R.string.voice_state_correcting
            },
        )
        statusText.setTextColor(if (warn) warningColor else textColor)
    }

    /** @return true if this call is what made the strip appear. */
    private fun show(): Boolean {
        if (shown) return false
        shown = true
        root.animate().cancel()
        root.isInvisible = false
        root.alpha = 0f
        root.translationY = -context.dp(ENTER_OFFSET_DP).toFloat()
        root
            .animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(SHOW_DURATION_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
        return true
    }

    private fun hide() {
        if (!shown) return
        shown = false
        root.animate().cancel()
        root
            .animate()
            .alpha(0f)
            .setDuration(HIDE_DURATION_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                // Guarded: a session that restarts inside the fade-out must not be hidden again.
                if (!shown) {
                    root.isInvisible = true
                    waveform.stop()
                    phase = null
                }
            }.start()
    }

    companion object {
        private const val ICON_DP = 16
        private const val WAVEFORM_WIDTH_DP = 72
        private const val WAVEFORM_HEIGHT_DP = 16
        private const val ENTER_OFFSET_DP = 4
        private const val SHOW_DURATION_MS = 120L
        private const val HIDE_DURATION_MS = 90L

        private const val OPAQUE_MASK = 0xFF000000.toInt()
        private const val WARNING_ON_LIGHT = 0xFFD93025.toInt()
        private const val WARNING_ON_DARK = 0xFFFF6B6B.toInt()

        private val ACCENT_KEYS = listOf("hilited_candidate_text_color", "candidate_text_color")
        private val TEXT_KEYS = listOf("candidate_text_color", "hilited_candidate_text_color")
    }
}
```

> 实施时自己复核两点：
> **(a)** 属性初始化顺序 = 声明顺序，`content` 必须在 `iconSlot`/`waveform`/`statusText` 之后、
> `root` 之前，`init { applyColors() }` 必须在 `root` 之后（`applyColors` 会写 `root.background`）。
> **(b)** ktlint 会要求 `@ColorInt private var` 这类带注解的属性之间空一行，
> `make spotlessApply` 会自动处理，不要手工纠结。

### 3.4 `InputView.kt` 的改动

```kotlin
// —— 删除（当前第 258-264 行，在 add(keyboardView, ...) 之后、add(popup.root, ...) 之前）——
-        add(
-            voice.root,
-            lParams(matchParent, matchParent) {
-                centerInParent()
-            },
-        )

// —— 新增（keyboardView = constraintLayout { ... } 块内，bottomPaddingSpace 的 add 之后）——
+                // Last child of `keyboardView`, so the voice status strip draws above both the
+                // input bar and the board window. `popup.root` is added to InputView *after*
+                // keyboardView and therefore still draws above the strip.
+                // Non-clickable (see VoiceOverlayUi), so touches fall through to the keys —
+                // "hold space to talk" depends on it.
+                add(
+                    voice.root,
+                    lParams(matchParent, voiceStripHeight) {
+                        topOfParent()
+                        centerHorizontally()
+                    },
+                )

// —— 新增私有属性（放在 updateKeyboardSize() 附近即可）——
+    /**
+     * Height of the voice status strip: exactly the input bar's height, so during recording it
+     * replaces that row pixel for pixel. When the bar is hidden (`keyboard__hide_input_bar`) the
+     * strip lands on the key rows instead, so it's capped there — see
+     * doc/voice-input-feedback-design.md §6.1.
+     */
+    private val voiceStripHeight: Int
+        get() = dp(inputBar.themedHeight).let {
+            if (inputBar.view.visibility == View.VISIBLE) it else minOf(it, dp(48))
+        }
```

`topOfParent`、`centerHorizontally`、`matchParent`、`dp` 在 `InputView.kt` 里**都已经 import 了**，
不需要新增 import。`centerInParent` 仍被 `popup.root` 使用，**不要删它的 import**。

---

## 4. 总验收清单

### 4.1 静态

| # | 检查 | 命令 / 判据 |
| --- | --- | --- |
| S1 | 改动文件恰好 4 个 | `git diff --stat develop...HEAD -- <本次改动>` 只含 §0.2 表里的 4 个文件 |
| S2 | 无新依赖 | `git diff` 不含 `build.gradle.kts` / `libs.versions.toml` |
| S3 | 无新字符串 | `git diff` 不含 `strings.xml` |
| S4 | 语音链路零改动 | `git diff` 不含 `VoiceSession*.kt` / `VoiceInputDelegate.kt` / `AudioRecorder.kt` / `KeyView.kt` |
| S5 | 格式 | `make spotlessCheck` 通过 |
| S6 | 许可证 | `make reuse` 通过 |
| S7 | 单测 | `:app:testDebugUnitTest` **99/99**，无新增无减少 |
| S8 | debug + release 都能打包 | `:app:assembleDebug`、`:app:assembleRelease` |
| S9 | I1 不变量 | 全仓 grep：`VoiceOverlayUi` 里没有 `isClickable = true` / `setOnClickListener` / `setOnTouchListener` |

### 4.2 真机（V5）

| # | 场景 | 期望 |
| --- | --- | --- |
| T1 | 浅色主题，聊天 App 里按住空格说话 | App 界面与键盘按键全程可见；只有候选栏那一行变成状态行 |
| T2 | 深色主题，同上 | 同上，文字/波形对比度足够 |
| T3 | 录音中上滑越阈值再滑回 | 「🎤 正在聆听…」⇄「✕ 松手取消」来回切换，**文字不左右跳动**、行高不变、整行变色 |
| T4 | 松手后到上屏 | 「识别中…」（小转圈）→（开了 AI 校对时）「AI 校对中…」→ 淡出 → 文字上屏 |
| T5 | 连续按住说话 20 次 | 零中断（**I1/I2 回归，最重要的一条**） |
| T6 | 说话音量由小到大 | 波形柱子高度明显跟随，且是**整条在滚动**而不是只有最右一根在跳（**C1 回归**） |
| T7 | 关掉「显示候选栏」后录音 | 状态行在键盘顶部，≤48dp，第二排及以下按键完全可见 |
| T8 | 符号窗口打开时点工具栏语音按钮 | 状态行正常显示在符号窗口顶部 |
| T9 | 录音中切走 App / 收起键盘 | 状态行随会话取消而消失，不残留 |
| T10 | 逐像素比对（录音前 vs 录音中） | 键盘顶边以上差异 **0 像素**（**F1 硬证据**）|
| T11 | 换一个 `keyboard_back_color` 带 alpha 的主题后触发语音 | 不崩（**C3 回归**）。找不到这样的主题就手工改一份主题 YAML 造一个 |
| T12 | TalkBack 打开后按住说话 | 状态变化被播报 |

---

## 5. 坑速查表

| # | 坑 | 后果 | 解 |
| --- | --- | --- | --- |
| 1 | 浮层设成可点击/可获焦 | 「按住说话」当场断 | `isClickable = false` + `isFocusable = false`，且不设 touch listener（I1） |
| 2 | 把反馈做成 `BoardWindow` | `attachWindow` 摘掉键盘视图 → 按住中的 `KeyView` 收到 `ACTION_CANCEL` | 只能是 `keyboardView` 的普通 child（I2 / D7） |
| 3 | `waveform.start()` 放在每帧 | 波形历史被清空，只剩最右一根柱子在跳 | 只在入场调一次（C1） |
| 4 | `statusText.text` 每帧赋值 | 每秒 20 次 `requestLayout` | 相位切换时才设，`applyPhase` 自带幂等守卫（C2） |
| 5 | 把带 alpha 的主题色喂给 `ColorUtils.calculateContrast` | `IllegalArgumentException` → 第一次按语音键就崩 | 先 `or 0xFF000000`（C3） |
| 6 | 用 `GONE` 切换可见性 | 在用户按住按键的过程中触发 `keyboardView` 的 measure/layout | 用 `INVISIBLE`（`androidx.core.view.isInvisible`） |
| 7 | 沿用 `TransitionManager.beginDelayedTransition(keyboardView, Slide(...))` | 长按过程中对父容器做 transition，有引发按键状态抖动的风险，且对固定尺寸的兄弟 view 是浪费 | 用 `ViewPropertyAnimator` |
| 8 | 状态行只铺 `candidate_background` 不垫底色 | 透明主题下工具栏按钮从状态行里透出来 | `LayerDrawable(ColorDrawable(keyboard_back_color), decor)` |
| 9 | 图标槽宽度随内容变化 | mic ⇄ ✕ ⇄ 转圈 切换时文字左右横跳 | 图标槽固定 16dp，波形槽固定 72dp，波形用 `INVISIBLE` 让位而不是 `GONE` |
| 10 | 新增 drawable 忘了 SPDX 头 | `make reuse` 失败 | 照抄 `ic_baseline_mic_24.xml` 的注释块 |
| 11 | 用 `JAVA_HOME=/opt/homebrew/opt/openjdk`（默认值） | 目录不存在；换成 `~/Library/Java/.../openjdk-24.0.1` 又是 x86_64，`Bad CPU type` | 用 `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` |
| 12 | 想给状态行加单测 | `android.graphics.Color` 的静态方法在 JVM 单测里会 `Method not mocked`（项目没开 `isReturnDefaultValues`，也没有 Robolectric） | 本次**不加单测**，靠 99 个既有用例做回归 + 真机验收 |

---

## 6. 计划外改动记录

> 实施过程中若不得不改 §0.2 清单之外的文件，在此追加：`文件 · 原因 · 行数 · 任务号`。

（暂无）

---

## 7. 实测数据回填区

> V5 完成后填这里，并把结论同步回 `voice-input-feedback-design.md`。

| 指标 | 数值 | 测量条件 | 日期 |
| --- | --- | --- | --- |
| 单测（改前基线） | 14 类 / 99 用例 / 0 失败 | JDK 21 arm64，`:app:testDebugUnitTest` | 2026-09-05 |
| release APK（改前基线） | arm64-v8a 18.1MB | `c539d3e2` 产物 | 2026-09-05 |
| 单测（改后） | | | |
| release APK（改后，arm64-v8a） | | 预期与基线持平（只增一个 vector drawable） | |
| T10 键盘顶边以上差异像素数 | | 期望 0 | |
| T5 连续 20 次按住说话中断次数 | | 期望 0 | |
| 录音时状态行实测高度 | | 默认主题 `candidate_view_height + comment_height` | |
