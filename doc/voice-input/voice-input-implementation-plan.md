# Trime 语音输入 · 实施计划

> **本文的读者是后续的实施 agent。** 先读 [`voice-input-design.md`](voice-input-design.md)（架构与取舍），
> 再读本文（做什么、怎么做、做到什么算完）。设计稿讲"为什么"，本文讲"照着做"。
>
> 本文里出现的 sherpa-onnx API 签名、trime 的手势回调语义、体积数字，
> 均已从 AAR 字节码 / trime 源码中**逐条核对过**，可直接照抄；标注「待实测」的除外。

---

## 0. 工作约定（每个任务都适用）

### 0.1 硬约束

1. **不得触碰的范围**：`app/src/main/jni/**`（librime/OpenCC 等 native）、`core/`（Rime 绑定）、
   `data/theme/**` 的既有逻辑、`BoardWindow` 体系。
2. **不得引入**：Jetpack Compose、OkHttp/Retrofit/Ktor、Room 新表、任何新的 DI 框架、
   `androidx.security-crypto`、新的 keycode。
3. **不得提交进 git**：sherpa-onnx 的 AAR/so、模型文件、任何 >1MB 的二进制。
4. **上游文件改动限额**：只允许改 §0.5 清单里的 9 个文件。需要改第 10 个文件时，
   **先停下来在本文 §6「计划外改动记录」里追加一行说明**，再动手。
5. 新增代码全部落在这四个位置：
   `ime/voice/**`、`data/voice/**`、`ui/main/settings/VoiceInput*`、`res/**` 的新增资源。

### 0.2 代码风格

- 每个新文件顶部加 SPDX 头，格式与同目录既有文件完全一致：
  ```kotlin
  /*
   * SPDX-FileCopyrightText: 2015 - 2026 Rime community
   * SPDX-License-Identifier: GPL-3.0-or-later
   */
  ```
  从 `trime-bibi-keyboard` 搬运的文件（`WaveformView` 等）保留其原始 copyright 行并追加本项目行。
- 格式化由 spotless + ktlint 1.7.1 强制：**提交前必须 `make spotlessApply` 且 `make spotlessCheck` 通过**。
- UI 文案与注释用中文；`strings.xml` 必须同时更新三份：
  `values/`、`values-zh-rCN/`、`values-zh-rTW/`（`values/` 里放中文即可，与项目现状一致）。
- commit message 用 conventional commits（仓库有 `cliff.toml` 生成 changelog）：
  `feat(voice): ...` / `fix(voice): ...` / `build(voice): ...`。每个任务至少一个独立 commit。

### 0.3 环境前提（本机当前是坏的，先修）

实施前先确认能构建。**本机现状（2026-09-05 实测）**：`JAVA_HOME` 指向不存在的
`/opt/homebrew/opt/openjdk`，且唯一安装的 JDK 是 x86_64 的 OpenJDK 24
（`/Users/oyasmi/Library/Java/JavaVirtualMachines/openjdk-24.0.1`，在 arm64 Mac 上无法直接执行，
`javap` 报 "Bad CPU type in executable"）。因此 `./gradlew` 与 `make spotlessCheck` **当前都跑不起来**。

第一步先装一个 arm64 的 JDK 17/21 并修正 `JAVA_HOME`，确认下面三条都通过，再开始 T0：
```bash
./gradlew -version
./gradlew :app:assembleDebug     # 需要 NDK 与 git submodule 已就绪
make spotlessCheck
```
另需确认 `git submodule update --init --recursive` 已执行（librime 等 native 依赖）。

### 0.4 每个任务收尾前必跑

```bash
make spotlessApply && make spotlessCheck
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```
涉及 T0/T4/T6 的任务额外跑一次 **release 包**（R8 会咬人，见 §5 坑 #3）：
```bash
./gradlew :app:assembleRelease
```

### 0.5 允许改动的上游文件清单（共 9 个）

| 文件 | 允许的改动 | 任务 |
| --- | --- | --- |
| `app/src/main/AndroidManifest.xml` | 加 2 条 `uses-permission` | T0 |
| `app/build.gradle.kts` | 依赖、`packaging.jniLibs.excludes` | T0 |
| `app/proguard-rules.pro` | keep 规则 | T0 |
| `build-logic/convention/src/main/kotlin/Versions.kt` | sherpa 版本号与校验和 | T0 |
| `build-logic/convention/build.gradle.kts` + 新插件文件 | 注册下载插件 | T0 |
| `data/prefs/AppPrefs.kt` | 加 1 行 `val voice = ...` | T1 |
| `ui/main/NavigationRoute.kt` | 加 1 个 route | T1 |
| `ui/main/MainFragment.kt` | 加 1 个入口 | T1 |
| `util/AppUtils.kt` | 加 1 个 `launchMainToVoiceInput` | T1 |
| `ime/core/InputView.kt` | 注册 delegate + 加 overlay | T4 |
| `ime/keyboard/KeyView.kt` | 长按/移动/抬手/取消 四处分支 | T4 |
| `ime/keyboard/CommonKeyboardActionListener.kt` | `KEYCODE_VOICE_ASSIST` 前置分支 | T4 |

> 实际是 12 个条目 9 个文件（build-logic 算一组）。超出即触发 §0.1 第 4 条。

---

## 1. 已锁定的决策

| 决策 | 取值 |
| --- | --- |
| product flavor 隔离 | **不做**。单包，`INTERNET` 权限直接加，靠"默认关闭 + 单一出网点"约束 |
| sherpa-onnx 版本 | **v1.13.7**（2026-09-01，当前最新 release） |
| API Key 存储 | **普通 SharedPreferences**，掩码显示，日志中打码，不引 `androidx.security-crypto` |
| 上滑取消手势 | **要做**，实现见 T4.4 |
| 模型 | 见下方 ⚠️ |

### ⚠️ 模型版本：与"用最新的"这条指示冲突，需要你复核

你的指示是「用 2025 这个，有更新的就用更新的」。查证结果（sherpa-onnx 官方文档）：

| 模型 | 特点 | 标点 |
| --- | --- | --- |
| `...int8-2024-07-17` | 原版 SenseVoice-Small | **`use_itn=1` 时有标点** |
| `...int8-2025-09-09` | 在 2024 版基础上用 21.8k 小时**粤语**数据微调 | **不支持标点**（官方原文：This model does not support punctuations） |

2025-09-09 之后没有更新的 SenseVoice 模型。也就是说，「更新」的那个是**粤语专项微调版，代价是丢掉标点**——
对中文听写输入法来说，没有标点意味着上屏是一大坨连续文字，除非用户开了 AI 校对让 LLM 补标点，
而 AI 校对默认关闭且需要联网 + API Key。

**因此本计划的处理是**：
- 两个变体**都实现**（模型管理本来就要做变体，成本几乎为零），在设置里作为可选项，标签写清楚：
  - `2024-07-17 · 支持标点`
  - `2025-09-09 · 粤语增强 · 无标点`
- **默认值取 `2024-07-17`**。
- 若你确认要反过来，改 `VoicePrefs.modelVariant` 的 `defaultValue` 一处即可，其余代码零改动。

---

## 2. 任务分解

依赖关系：`T0 → T1 → T2 → T3 → T4 → T5(gate) → T6 → T7`。T3 完成后即可在设置页里跑通识别，
T4 才接键盘。**不要跳过 T5**：它决定要不要启用子进程退路。

---

### T0 · 构建与权限接入

**目标**：`./gradlew :app:assembleDebug` 与 `assembleRelease` 都能出包，包里有 sherpa 的 .so，
且 debug 包里能反射到 `com.k2fsa.sherpa.onnx.OfflineRecognizer`。此任务不写任何业务代码。

**新增**
- `build-logic/convention/src/main/kotlin/SherpaOnnxPlugin.kt`

**改上游**
- `Versions.kt`：加 `const val SHERPA_ONNX = "1.13.7"` 与其 tar.bz2 的 sha256。
- `build-logic/convention/build.gradle.kts`：注册插件 `com.osfans.trime.sherpa-onnx`。
- `app/build.gradle.kts`：应用插件；加依赖；加 packaging excludes。
- `AndroidManifest.xml`：`RECORD_AUDIO`、`INTERNET`。
- `proguard-rules.pro`：keep 规则。

**实现要点**

1. 插件行为：下载 → 校验 sha256 → 解出 AAR 到 `app/libs/sherpa-onnx-<ver>.aar`，
   已存在且校验通过则跳过。产物加进 `.gitignore`。任务要注册为
   `preBuild.dependsOn`，且带 `@CacheableTask` 语义（至少要有 up-to-date 检查，别每次构建都下 40MB）。
   ```
   https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.7/sherpa-onnx-v1.13.7-android.tar.bz2
   ```
   离线开发逃生口：若环境变量 `SHERPA_ONNX_AAR` 指向本地 AAR，则直接用它，跳过下载。
2. 依赖：
   ```kotlin
   implementation(fileTree("libs") { include("*.aar") })
   implementation("org.apache.commons:commons-compress:1.28.0")   // tar.bz2 解压，T2 用；纯 Java 无 .so
   ```
   `commons-compress` 加进 `gradle/libs.versions.toml`，不要写死在 build 文件里（项目惯例）。
3. packaging（抄 BiBi 已验证的三条，能省 4~5MB/ABI）：
   ```kotlin
   packaging.jniLibs.excludes += listOf(
       "**/libonnxruntime4j_jni.so",
       "**/libsherpa-onnx-c-api.so",
       "**/libsherpa-onnx-cxx-api.so",
   )
   ```
4. proguard：
   ```
   -keep class com.k2fsa.sherpa.onnx.** { *; }
   -keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }
   ```
   sherpa 的 Kotlin 数据类字段是被 JNI 按名字反查的，R8 改名/裁字段一律崩，且**只在 release 包崩**。
5. manifest 权限加在既有三条之后，并加一行注释说明 INTERNET 仅用于可选的 AI 校对与模型下载。

**验收**
- [ ] `assembleDebug` / `assembleRelease` 均成功。
- [ ] `unzip -l` 产物 APK，`lib/arm64-v8a/` 下有且仅有 `libonnxruntime.so`、`libsherpa-onnx-jni.so`（外加 trime 自己的）。
- [ ] 记录并写回 `voice-input-design.md` §4.1：**接入前后各 ABI 的 APK 体积差**（这是设计稿标注"待实测"的第一个数字）。
- [ ] 临时写一个 debug-only 的单元测试或 `MainActivity` 里的临时调用，确认 `Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer")` 在 **release 包**里也能拿到（验完删掉）。

---

### T1 · 配置与设置页骨架

**目标**：设置里出现「语音输入」页，所有配置项可读可写可持久化。此时全部配置都还没人消费。

**新增**
- `data/voice/VoicePrefs.kt`
- `ui/main/settings/VoiceInputSettingsFragment.kt`
- `res/values*/strings.xml` 新增文案（三份）
- `res/drawable/ic_baseline_mic_24.xml`（从 trime-bibi 搬）

**改上游**：`AppPrefs.kt`(+1 行)、`NavigationRoute.kt`(+1 route)、`MainFragment.kt`(+1 入口，放在「剪贴板」与「高级」之间)、`AppUtils.kt`(+1 函数)。

**实现要点**

`VoicePrefs` 继承 `PreferenceDelegateOwner(shared, R.string.voice_input)`，键名统一 `voice__` 前缀。
所有条目一律用既有的声明式 API（`switch/enum/int/editText/list`），**不要手写 Preference XML**。
`enableUiOn` 用来做联动（例：LLM 各项 `enableUiOn = { llmEnabled.getValue() }`）。

| 键 | 类型 | 默认 | 备注 |
| --- | --- | --- | --- |
| `voice__enabled` | switch | `false` | 总开关 |
| `voice__model_variant` | enum | `V2024_07_17` | 见 §1 ⚠️ |
| `voice__model_url` | editText | 官方 URL（随变体变化，留空=用内置） | 换源逃生口 |
| `voice__language` | enum | `AUTO` | auto/zh/en/yue/ja/ko |
| `voice__itn` | switch | `true` | = `useInverseTextNormalization` |
| `voice__num_threads` | int(1..4) | `2` | |
| `voice__keep_alive_minutes` | int(-1..30) | `5` | `-1` 常驻、`0` 用完即卸；用 `defaultLabel` 标注特殊值 |
| `voice__trigger_mode` | enum | `HOLD` | HOLD / TOGGLE |
| `voice__max_duration_seconds` | int(10..300) | `60` | |
| `voice__trim_trailing_punct` | switch | `false` | |
| `voice__llm_enabled` | switch | `false` | **默认关，关掉就没有任何网络行为** |
| `voice__llm_base_url` | editText | `""` | |
| `voice__llm_api_key` | editText | `""` | 显示掩码（见下） |
| `voice__llm_model` | editText | `gpt-4o-mini` | |
| `voice__llm_temperature` | int(0..20) | `0` | 展示为 /10；trime 没有 float seekbar |
| `voice__llm_timeout_seconds` | int(1..30) | `5` | |
| `voice__llm_prompt` | editText | `""` | 空 = 用 `res/raw` 内置提示词 |

`VoiceInputSettingsFragment` 覆写 `onPreferenceUiCreated(screen)`，在其中追加三个**非 delegate 的**点击项
（用 `util/PreferenceScreen.kt` 里现成的 `addPreference(title, summary, icon, onClick)`）：
- 「语音模型」→ T2 的模型对话框（summary 显示状态）
- 「录音测试」→ T3 的测试对话框
- 「测试校对」→ T6 的往返测试

API Key 掩码：`findPreference<Preference>("voice__llm_api_key")?.summaryProvider = ...` 输出
`sk-****abcd` 形式（保留前 3 后 4）。**不要**自己造 SecureField。

**验收**
- [ ] 设置页可进入，17 项全部显示，联动禁用生效（关掉 LLM 总开关，其下 6 项变灰）。
- [ ] 杀进程重进，值都还在。
- [ ] `voice__enabled` 关闭时，页面顶部有一条说明"关闭时语音键将切换到系统语音输入法"。

---

### T2 · 模型管理

**目标**：能把模型装好、看得到状态、删得掉；断网/中断/换源/本地导入都正确。

**新增**
- `data/voice/VoiceModelVariant.kt`（枚举：id、目录名、下载 URL、模型文件名、tokens 文件名、预期字节数、sha256、是否支持标点）
- `data/voice/VoiceModelManager.kt`（定位 / 校验 / 删除 / 状态 StateFlow）
- `data/voice/VoiceModelArchive.kt`（tar.bz2 与 zip 解压，含路径穿越防护）
- `data/voice/VoiceModelDownloadWorker.kt`（WorkManager，前台通知 + 进度 + 断点续传）
- `ui/main/settings/VoiceModelDialog.kt`
- 测试：`VoiceModelIntegrityTest`、`VoiceModelArchiveTest`

**实现要点**

1. 目录：`context.getExternalFilesDir(null)/voice/sensevoice/<variantDir>/`，
   期望内含 `model.int8.onnx` + `tokens.txt`。**绝不放进 rime 用户数据目录**（会被同步/备份，228MB 会毁掉用户备份）。
2. 状态机（`sealed interface VoiceModelState`）：`NotInstalled` / `Downloading(progress, bytes)` /
   `Extracting` / `Ready(sizeBytes)` / `Invalid(reason)`。设置页 summary 直接渲染它。
3. 校验分级（抄 BiBi `LocalModelIntegrity` 的思路）：
   - 文件缺失 / 大小为 0 → `Invalid`，**拒绝使用**；
   - sha256 不匹配 → 记 warning，**不拒绝**（要容忍用户自备的 fp32 或未来版本），
     只要 T3 里 sherpa 能成功构造 recognizer 就算可用。
4. 下载：`Range` 断点续传；写 `<name>.part`，校验通过后原子 `renameTo`；
   Worker 用 `setForeground` 挂通知（长任务，且 POST_NOTIFICATIONS 权限项目里已有）；
   支持取消；退出 App 后继续。**先下小文件后下大文件**没意义（这里是单个压缩包），
   但要在开始前发一个 HEAD/Range 探测请求，让网络问题在花掉 200MB 之前暴露。
5. 解压：`.tar.bz2` 用 commons-compress 的 `BZip2CompressorInputStream` + `TarArchiveInputStream`；
   `.zip` 用 JDK。**必须**校验每个 entry 的规范化路径仍在目标目录内（`canonicalPath.startsWith(destDir.canonicalPath)`），
   否则拒绝——这是 Zip Slip，必须有对应单测。
   官方包解出来是一层 `sherpa-onnx-sense-voice-.../` 目录，要做**扁平化**：把 `model.int8.onnx` 与
   `tokens.txt` 提到 `<variantDir>/` 根下，其余文件（`test_wavs/` 等）丢弃，能省几十 MB。
6. 本地导入：SAF `ACTION_OPEN_DOCUMENT`，接受 `.tar.bz2` / `.zip`，复用同一条解压校验路径。
7. 删除：二次确认对话框，删完把状态刷回 `NotInstalled`，并调用 T3 的 `SenseVoiceEngine.unloadNow()`。

**验收**
- [ ] 全新安装 → 下载 → 就绪，重启 App 状态仍为就绪。
- [ ] 下载中杀进程 → 重进 → 能续传（不是从 0 开始）。
- [ ] 断网 → 明确的错误文案，不是 crash 也不是永远转圈。
- [ ] 改一个错误 URL → 报错清晰。
- [ ] 本地导入官方 tar.bz2 成功。
- [ ] 构造一个含 `../` entry 的恶意包，单测断言被拒绝。
- [ ] 删除后目录确实消失，占用回收。

---

### T3 · 识别链路（不接键盘）

**目标**：在设置页的「录音测试」对话框里，按住能录、松开能出识别文本。这一步把识别的所有风险打完，
与键盘交互完全解耦。

**新增**
- `data/voice/SenseVoiceEngine.kt`
- `data/voice/TextPostProcessor.kt`
- `ime/voice/AudioRecorder.kt`
- `ime/voice/AudioFocusGuard.kt`
- `ime/voice/VoiceSession.kt` + `VoiceSessionState.kt`
- `ui/main/settings/VoiceRecordingTestDialog.kt`
- 测试：`TextPostProcessorTest`、`VoiceSessionTest`

**实现要点**

**(a) `SenseVoiceEngine`** —— 下面的 API 签名已从 sherpa-onnx AAR 的字节码逐字段核对，可直接照抄：

```kotlin
val config = OfflineRecognizerConfig(
    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
    modelConfig = OfflineModelConfig(
        tokens = "$dir/tokens.txt",
        numThreads = prefs.numThreads,      // 默认 2
        debug = false,
        provider = "cpu",
        senseVoice = OfflineSenseVoiceModelConfig(
            model = "$dir/model.int8.onnx",
            language = prefs.language,      // "" | "auto" | "zh" | "en" | "yue" | "ja" | "ko"
            useInverseTextNormalization = prefs.itn,
        ),
    ),
)
// assetManager 必须传 null —— 从绝对路径加载时传非 null 会失败
// 见 sherpa-onnx issue #2562，BiBi 源码里有同样的注释
val recognizer = OfflineRecognizer(null, config)

// 推理
val stream = recognizer.createStream()
stream.acceptWaveform(samples /* FloatArray, -1..1 */, 16000)
recognizer.decode(stream)
val text = recognizer.getResult(stream).text
stream.release()
```
- 释放用 `recognizer.release()`。
- 全部操作在一个 `Mutex` 内串行（加载与推理互斥）。
- `state: StateFlow<State>`：`Unloaded / Loading / Ready / Failed(cause)`。
- `preload()` 在按下语音键时调用，与录音并行；**绝不在 IME 启动路径上加载**。
- 空闲卸载：每次 `decode` 后重置定时协程；`keepAliveMinutes` 为 `0` 时立即卸载、`-1` 时不卸载。
- 语言 / ITN / 线程数变化 → 标记 dirty，下次 `preload` 时重建。
- **对外只暴露 `suspend fun decode(samples: FloatArray): String`，不泄漏任何 `com.k2fsa.*` 类型**——
  这是给设计稿 §4.2 的子进程退路留的接缝，别破坏它。

**(b) `AudioRecorder`**
- 16000Hz / `CHANNEL_IN_MONO` / `ENCODING_PCM_16BIT`。
- `MediaRecorder.AudioSource.VOICE_RECOGNITION` 优先，构造失败回落 `MIC`（抄 BiBi）。
- 读循环在 `Dispatchers.IO`，边读边 `short / 32768f` 累积进 `FloatArray`（用可增长的 buffer，别每次 copy 整段）。
- 每块算 RMS → `onAmplitude`，节流到 ~20fps。
- **第一块真实音频到达前不发 `Recording` 的波形信号**（硬件启动有 100~200ms 延迟，
  提前画波形会显得"没在听"）——这条 trime-bibi 踩过，commit `12b5c853`。
- 不落磁盘。60s @16k float ≈ 3.8MB，可接受。

**(c) `TextPostProcessor`**（纯函数，单测重点）
1. 剥 `<|...|>` 标签与 emoji 标签残留；
2. 空白收敛 + trim；
3. 去掉与 CJK 相邻的空格（`你好 世界`→`你好世界`；`AI Coding` 必须保留，要有反例单测）；
4. **若结果不含任何"实字"（只剩标点/空白）返回空串**——静音时 SenseVoice 常吐孤立句号；
5. `trimTrailingPunct` 开启时去句末标点。

**(d) `VoiceSession`** —— 状态机，规则逐条实现（设计稿 §5.2）：
- 录音 <300ms → 静默丢弃；
- 达到 `maxDurationSeconds` → 走**与正常松手完全相同**的收尾路径 + 一次 toast，不是静默截断；
- `Recognizing`/`Correcting` 期间重复触发 → 拒绝；
- 用自增 `sessionId` 校验迟到结果，取消/失焦后到达的结果一律丢弃；
- 任何异常路径都必须回到 `Idle` 且清掉 UI；
- `Recognizing` 30s 看门狗。

**(e) `AudioFocusGuard`**：录音期间申请 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`，结束释放。
约 30 行，**不要**照搬 trime-bibi 那个 222 行的版本。

**(f) 录音测试对话框**：一个按住说话的按钮 + 结果文本框 + 耗时显示（加载 ms / 推理 ms）。
这是 T5 的测量工具，也是长期的排障入口，值得做扎实。

**验收**
- [ ] 设置页里按住说一句中文，松开后 3 秒内出正确文本。
- [ ] ITN 开/关的差异可见（标点有无）。
- [ ] 语言切到 `en` 说英文正常。
- [ ] 对着静音录 2 秒 → 结果为空，不显示孤立句号。
- [ ] 录 0.1 秒 → 无任何反应，无报错。
- [ ] 录到时长上限 → 自动收尾并出文本。
- [ ] 模型未安装时点录音测试 → 明确提示 + 跳转下载。
- [ ] 单测：`TextPostProcessorTest` 覆盖 5 条规则的正反例；`VoiceSessionTest` 用 fake engine 覆盖上述 6 条状态机规则。
- [ ] 记录首次加载耗时与各时长音频的推理耗时，写进本文 §7。

---

### T4 · 键盘接入

**目标**：真机上长按语音键能听写上屏；上滑取消可用；所有失败路径不会把 UI 卡在录音态。

**新增**
- `ime/voice/VoiceInputDelegate.kt`
- `ime/voice/ui/VoiceOverlayUi.kt`
- `ime/voice/ui/WaveformView.kt`（从 `trime-bibi-keyboard/app/src/main/java/com/osfans/trime/ime/voice/WaveformView.kt` 搬运，保留出处注释）

**改上游**：`InputView.kt`、`KeyView.kt`、`CommonKeyboardActionListener.kt`。

**实现要点**

**T4.1 DI 与浮层挂载（`InputView.kt`）**
```kotlin
bindSingleton { VoiceInputDelegate(di) }          // 与 PopupDelegate 同级
...
private val voice: VoiceInputDelegate by instance()
...
add(voice.root, lParams(matchParent, matchParent) { centerInParent() })   // 与 popup.root 同级，加在其之前
```
- `voice.root` 初始 `isVisible = false`、`isClickable = false`、`isFocusable = false`，
  **触摸必须能穿透回下面的按键**，否则"按住说话"当场断。
- 挂在 `InputView` 而不是 `KeyboardView`：这样符号窗口下也能用，且不必给 `KeyboardWindow`
  加 4 个转发方法（trime-bibi 加了，我们不要）。
- **`VoiceInputDelegate` 必须惰性**：`voice__enabled` 为 false 时不构造引擎、不注册任何监听、不起协程。

**T4.2 动作入口（`CommonKeyboardActionListener.kt`）**
```kotlin
KeyEvent.KEYCODE_VOICE_ASSIST -> {
    if (prefs.voice.enabled.getValue()) voiceInputDelegate.onVoiceActionTriggered()
    else switchToVoiceInputMethod()      // 上游既有行为，保持不变
}
```
`onVoiceActionTriggered()` 在 `TOGGLE` 模式下切换开始/停止；在 `HOLD` 模式下**什么都不做**
（HOLD 由 T4.3 的长按路径驱动，避免点一下就开始录音）。
工具栏麦克风按钮走的是同一条 `KeyActionManager` 通路，因此**始终按 TOGGLE 语义**处理，
即：`onVoiceActionTriggered(forceToggle = true)`。

**T4.3 按住说话（`KeyView.kt`）** —— 四处分支，形状照 trime-bibi：
```kotlin
private fun isVoiceHoldAction(action: KeyAction?): Boolean =
    voiceEnabled && triggerMode == HOLD && action?.code == KeyEvent.KEYCODE_VOICE_ASSIST

onLongClick = {
    val longPressAction = key.getAction(KeyBehavior.LONG_CLICK)
    if (isVoiceHoldAction(longPressAction)) {
        voice.startHold()                       // 不再走 popup / processKeyAction
    } else if (key.popup.isNotEmpty()) { ...既有... }
}

onMove = { x, y, isLongPress ->
    if (isLongPress && voice.isHolding()) voice.onHoldMove(y)   // y 是相对本 KeyView 的坐标
    else if (isLongPress && hasPopup) { ...既有... }
}

onRelease = { behavior, isFromLongPress ->
    if (isFromLongPress && voice.isHolding()) {
        voice.finishHold()                      // 内部按"是否处于将取消态"决定停止还是取消
        setPressedState(false); dismissPopupPreview()
        if (keyboard.firstPressedKeyIndex == id) keyboard.firstPressedKeyIndex = -1
    } else if (isFromLongPress) { ...既有... }
}

onCancel = { voice.cancelHoldIfRunning(); ...既有... }
```
> 已核对 `GestureFrame`：长按成立后（`isLongPressed = true`），`ACTION_MOVE` 只回调 `onMove`，
> 不会触发 swipe 判定、不会因为手指移出按键范围而取消。所以上滑手势不会和既有逻辑打架。

**T4.4 上滑取消**
- `onMove` 给的 `y` 是 **KeyView 局部坐标**，手指移到按键上方时为负值。
- 阈值：`y < -dp(48)` 进入「将取消」态；回落到 `y > -dp(32)` 退出（**必须有滞回**，否则边界抖动）。
- 进入「将取消」态时：浮层文案变为「松手取消」、波形变红/变灰、触发一次
  `InputFeedbackManager.keyPressVibrate(view)` 作为触觉确认。
- `finishHold()` 时若处于将取消态 → 走 `cancel()` 路径：丢弃音频、不识别、不上屏。
- TOGGLE 模式没有上滑手势，浮层上给一个可点击的「取消」区域代替。

**T4.5 浮层 `VoiceOverlayUi`**
- 用 splitties views DSL 构建（项目惯例），**不要**用 XML layout。
- 内容：波形 + 计时（`00:03`）+ 状态文案（正在聆听… / 识别中… / AI 校对中… / 松手取消 / 错误原因）。
- 配色从 `ColorManager.getColor(...)` 取，按对比度挑线条色 —— 这段逻辑从 trime-bibi 的
  `KeyboardView.showVoiceOverlay()` 原样继承（候选色序：`key_text_color`、`hilited_key_text_color`、
  `candidate_text_color`、`hilited_candidate_text_color`，取第一个与背景对比度 ≥2.5 的）。
- 背景优先 `ColorManager.getDecorDrawable("keyboard_background")`，回落 `keyboard_back_color`。
- 显示/隐藏用 `TransitionManager` + `Slide(Gravity.BOTTOM)`，`duration = 100`（与 trime 窗口切换一致）。

**T4.6 上屏与守卫（`VoiceInputDelegate`）**
- 上屏：`service.commitText(text)`。
- 若 rime 有未完成编码，先 `service.postRimeJob { commitComposition() }` 再 commit。
- **密码类输入框禁用语音**：`onStartInputView` 时检查 `EditorInfo.inputType` 的
  `TYPE_TEXT_VARIATION_PASSWORD` / `VISIBLE_PASSWORD` / `WEB_PASSWORD` / `TYPE_NUMBER_VARIATION_PASSWORD`，
  命中则触发时 toast 拒绝。
- **无麦克风权限**：浮层提示 + 点击跳 `AppUtils.launchMainToVoiceInput(context)`。
  **不要**从 IME 拉起透明 Activity 直接申请权限（trime-bibi 的 `MicPermissionActivity` 做法，
  会遮挡当前 App，部分 ROM 不稳）。权限申请统一在设置页用 `XXPermissions` 完成（抄 `MainActivity` 里
  `POST_NOTIFICATIONS` 的写法）。
- `onFinishInputView` / 输入框失焦 / 键盘隐藏 → `cancelHoldIfRunning()`，浮层必须消失。

**验收**（全部真机）
- [ ] 主题里给某个键配 `long_click: { send: VOICE_ASSIST }`，长按能录、松开上屏。
- [ ] 上滑到按键上方 → 文案变「松手取消」+ 震动 → 松手不上屏；滑回去再松手正常上屏。
- [ ] TOGGLE 模式：点一次开始、再点一次结束；工具栏按钮同样有效且图标随状态切换。
- [ ] 录音中切到其他 App / 收起键盘 / 点到别的输入框 → 浮层消失，不上屏，无残留状态。
- [ ] 密码框里触发 → toast 拒绝。
- [ ] 未授麦克风 → 提示并能跳到设置页授权，授权后回来即可用。
- [ ] 拒绝授权后再次触发 → 仍是提示，不 crash。
- [ ] 输入法关闭语音总开关 → 语音键退回"切换到系统语音输入法"的上游行为。
- [ ] 连续快速触发 20 次 → 无 ANR、无浮层残留、无重复上屏。

---

### T5 · 真机实测与决策关口（不要跳过）

**目标**：拿到设计稿里所有标注「待实测」的数字，并据此决定是否需要子进程退路。

**要测的**

| 指标 | 方法 | 判据 |
| --- | --- | --- |
| 各 ABI APK 体积增量 | T0 已测，此处复核 release 包 | 记录即可 |
| 模型加载耗时 | 录音测试对话框显示 | > 3s 需要优化（考虑 `preload` 时机前移到键盘首次显示） |
| 推理耗时 vs 音频时长 | 1s / 5s / 15s / 30s / 60s 各测 3 次 | 5s 音频 > 2s 推理则考虑分段渐进解码 |
| 输入法进程 RSS（加载模型前/后/卸载后） | `adb shell dumpsys meminfo com.osfans.trime` | **加载后 > 700MB 是红线** |
| 存活率 | 加载模型后正常用手机 30 分钟（切 App、看视频），记录输入法进程是否被回收 | 30 分钟内被杀 ≥2 次 → 触发退路 |
| release 包功能 | 用 release 包重跑 T3/T4 全部验收 | R8 问题只在这里暴露 |

**决策关口**：若 RSS 或存活率越线，**停下来**，按设计稿 §4.2 把 `SenseVoiceEngine` 挪进
同 APK 的 `:voice` 子进程（bound service，`BIND_AUTO_CREATE`，PCM 走文件或 ashmem）。
因为 `SenseVoiceEngine` 对外只有 `decode(FloatArray): String`，这个改造应当只影响
`data/voice/` 内部与一个新的 service 类，**不应该**波及 `ime/voice/` 与任何上游文件。

**产出**：把实测数字回填到 `voice-input-design.md` §4.1/§4.2/§11 与本文 §7。

---

### T6 · AI 智能校对

**目标**：开启后能纠正识别错误；关闭时零网络；任何失败都不丢文本。

**新增**
- `data/voice/llm/LlmCorrector.kt`（**全项目唯一出网点**）
- `data/voice/llm/LlmEndpoint.kt`
- `res/raw/voice_correction_prompt.md`
- `ui/main/settings/VoiceCorrectionTestDialog.kt`
- 测试：`LlmCorrectorTest`、`LlmEndpointTest`

**实现要点**

1. **提示词从 `~/projects/voice-typer/macos/Resources/correction.md` 原样复制，一个字都不要改。**
   这是已在真实使用中打磨过的资产。用户在设置里填了自定义提示词则用自定义的。
2. 请求：`POST <base>/chat/completions`，`HttpURLConnection`，非流式。
   ```jsonc
   {
     "model": "<voice__llm_model>",
     "temperature": <voice__llm_temperature / 10.0>,
     "max_tokens": max(配置值, 原文长度 * 2 + 128),
     "messages": [
       { "role": "system", "content": "<提示词>" },
       { "role": "user", "content": "<asr_text>识别原文</asr_text>" }
     ]
   }
   ```
   Header：`Authorization: Bearer <key>`、`Content-Type: application/json`。
   连接与读超时都用 `voice__llm_timeout_seconds`。
3. `LlmEndpoint`：结构化解析 base_url。规则（抄 voice-typer，它有对应单测）：
   - scheme 白名单：`https`；明文 `http` **仅允许**回环与私网地址；
   - 用户填 `https://api.x.com/v1` 或 `https://api.x.com/v1/chat/completions` 都要能工作（后缀去重）；
   - 非法输入返回明确错误，不要拼出一个畸形 URL 再去请求。
4. **失败兜底（本任务最重要的部分）**——下列情况**一律返回 ASR 原文**并只记日志/闪一下警告：
   网络异常、超时、非 2xx、JSON 解析失败、`choices` 为空、`finish_reason == "length"`、
   返回内容为空白、配置不完整（缺 base_url/key/model）。
   **绝不能因为校对失败而丢掉用户说的话。**
5. 防御性剥离模型回显的 `<asr_text>` / `</asr_text>` 标签。
6. 日志里 API Key 一律替换为 `***`。
7. UI：`Recognizing` 之后进入 `Correcting`，浮层显示「AI 校对中…」并把 ASR 原文先显示出来
   （让用户知道已经听到了什么），校对完成后一次性上屏最终文本。**只 commit 一次**，
   不做"先上屏再替换"（会与用户的手动编辑打架，trime-bibi 为此写了一整套 generation 跟踪，我们不要）。
8. 「测试校对」对话框：用一段固定的含错样例做真实往返，显示请求耗时与结果，
   把配置错误暴露在配置时而不是听写时。

**验收**
- [ ] 正常配置下，"因该"→"应该"这类错误被纠正。
- [ ] 关闭 AI 校对 → 抓包/日志确认零网络请求。
- [ ] 拔网络 → 5 秒内用 ASR 原文上屏，不卡死。
- [ ] 填错 base_url / key / model → 「测试校对」给出可读的错误。
- [ ] 单测：`LlmCorrectorTest` 用 JDK 自带 `com.sun.net.httpserver` 起本地桩，覆盖
      超时 / 401 / 畸形 JSON / 空 choices / `finish_reason=length` / 标签回显，**每条都断言返回原文**。
- [ ] 单测：`LlmEndpointTest` 覆盖 scheme 白名单、明文 http 限制、后缀去重、非法输入。
- [ ] 全项目 grep：`openConnection|HttpURLConnection|Socket` 只在 `LlmCorrector` 与 T2 的下载 Worker 中出现。

---

### T7 · 收尾

- `README_sc.md` / `README.md`：新增「语音输入」一节，写明：本地识别、需下载 228MB 模型、
  APK 体积增量、**开启 AI 校对会把识别文本发送到用户自己配置的服务端**。
- `CHANGELOG.md`：按 cliff 规范补条目。
- 「关于」页 / `aboutlibraries`：加 `sherpa-onnx (Apache-2.0)`、
  `Powered by SenseVoice-Small (FunAudioLLM)` 并链接 FunASR MODEL_LICENSE。
- `doc/Keyboard.md`：补一段"如何给键位绑定语音输入"，给出 YAML 示例：
  ```yaml
  space:
    click: space
    long_click: { send: VOICE_ASSIST }
  ```
- 把 T0/T3/T5 实测到的所有数字回填 `voice-input-design.md`，删掉其中的「待实测」标注。

---

## 3. 关键代码骨架速查

### 3.1 sherpa-onnx（已从 1.13.4 AAR 字节码核对，1.13.7 若有出入以实际为准）

| 类 | 字段/方法 |
| --- | --- |
| `OfflineRecognizer` | `(AssetManager?, OfflineRecognizerConfig)`、`createStream()`、`decode(stream)`、`getResult(stream)`、`release()` |
| `OfflineRecognizerConfig` | `featConfig`、`modelConfig`、`decodingMethod`、`hotwordsFile`… |
| `OfflineModelConfig` | `tokens`、`numThreads`、`debug`、`provider`、`senseVoice`、… |
| `OfflineSenseVoiceModelConfig` | `model`、`language`、`useInverseTextNormalization` |
| `FeatureConfig` | `sampleRate`、`featureDim`、`dither` |
| `OfflineStream` | `acceptWaveform(FloatArray, Int)`、`release()` |
| `OfflineRecognizerResult` | `text`、`tokens`、`timestamps`、`lang`、`emotion`、`event` |

### 3.2 状态机

```
Idle ─start()→ Recording ─stop()→ Recognizing ─[LLM 关]→ commit → Idle
                  │                    └─[LLM 开]→ Correcting → commit → Idle
                  ├─ 上滑/cancel/失焦 → Idle（丢弃）
                  ├─ <300ms → Idle（静默）
                  └─ ≥上限 → 同 stop()
```

---

## 4. 总验收清单

- [ ] 关闭语音总开关时：无网络、无线程、无内存开销，语音键行为与上游一致。
- [ ] 开启但未装模型时：所有入口都给出"去下载"的明确引导，无 crash。
- [ ] 装好模型后：长按说话 → 上屏，端到端可用。
- [ ] 设计稿 §9 的失败矩阵 15 条逐条走过，每条的表现与表格一致。
- [ ] release 包（R8 开启）功能与 debug 一致。
- [ ] `./gradlew :app:testDebugUnitTest` 全绿；新增测试覆盖 §2 中列出的全部单测项。
- [ ] `make spotlessCheck` 通过。
- [ ] 上游文件改动不超过 §0.5 的 9 个文件；`git diff --stat` 可核对。
- [ ] 设计稿中所有「待实测」数字已回填。

---

## 5. 坑速查表（都是已核实的，不是猜测）

| # | 坑 | 后果 | 解 |
| --- | --- | --- | --- |
| 1 | 从绝对路径加载模型时 `assetManager` 传了非 null | recognizer 构造失败 | 传 `null`（sherpa-onnx issue #2562） |
| 2 | 浮层设成可点击/可获焦 | "按住说话"手势当场断 | `isClickable = false` + `isFocusable = false` |
| 3 | 没加 sherpa 的 proguard keep | **只有 release 包崩**，debug 正常 | T0 的 keep 规则；每个大任务都要打一次 release 包 |
| 4 | 把录音 UI 做成 `BoardWindow` | `attachWindow` 摘掉键盘视图 → 按住中的 KeyView 收到 `ACTION_CANCEL` | 用 InputView 上的穿透浮层 |
| 5 | 模型放进 rime 用户数据目录 | 228MB 进同步/备份 | 放 `getExternalFilesDir` |
| 6 | 解压不校验 entry 路径 | Zip Slip | 规范化路径前缀检查 + 单测 |
| 7 | 静音时 SenseVoice 吐一个孤立句号 | 空录音也上屏一个"。" | `TextPostProcessor` 的"无实字返回空串"规则 |
| 8 | 上滑阈值没有滞回 | 边界抖动，文案闪烁 | 进入 -48dp / 退出 -32dp |
| 9 | 硬件启动前就画波形 | 用户以为没在听 | 收到第一块真实音频后再启动动画 |
| 10 | 在 IME 启动路径上预加载模型 | 键盘冷启动多 1~3 秒 | 只在按下语音键时 `preload()` |
| 11 | 先上屏 ASR 原文再替换成校对结果 | 与用户手动编辑打架 | 只 commit 一次 |
| 12 | 每次构建都下载 40MB AAR | 构建体验灾难 | 插件做 up-to-date 检查 + `SHERPA_ONNX_AAR` 环境变量逃生口 |

---

## 6. 计划外改动记录

> 实施过程中若不得不改 §0.5 清单之外的文件，在此追加一行：`文件 · 原因 · 行数 · 任务号`。

- `gradle/libs.versions.toml` · 新增 `commons-compress` 的版本目录条目（T0 依赖声明的规范位置，`app/build.gradle.kts` 不允许写死版本号，项目既有惯例） · +1 行 · T0
- `.gitignore` · 忽略 `app/libs/`（sherpa-onnx AAR 由插件下载，不能提交进 git，但需要显式忽略否则 `git status` 会一直提示） · +2 行 · T0
- `ime/keyboard/KeyboardActionListener.kt` · 给接口加 `val voiceInput: VoiceInputDelegate`（`KeyView` 只持有 `KeyboardActionListener` 接口引用，没有 DI 容器访问权；`CommonKeyboardActionListener.listener` 是一个匿名 `object`，不是外层类本身，无法从 `KeyView` 侧转型拿到 `voice` 属性。给接口加一个属性是比"给 `KeyboardView`/`KeyboardWindow` 的构造函数多穿一个参数"侵入小得多的方案——原计划低估了这条 DI 访问路径的复杂度） · +6 行 · T4
- `ime/voice/ui/WaveformView.kt` 未按计划从 `trime-bibi-keyboard` 搬运：其 `WaveformView` 依赖 `jaygoo.widget.wlv.WaveLineView`/`RenderView`（两个文件共 478 行），经检查**完全没有 SPDX/许可证头**，只有一行"移植自说点啥同名实现"的注释，来源不清晰，且项目本身用 REUSE 工具强制 SPDX 合规。改为写了一个约 90 行的一等公民 `View`（无 SurfaceView、无第三方依赖），公开 API（`start/stop/updateAmplitude/setWaveformColor`）不变。 · 不算"改动"，是新文件内容的自主决定，记在此处仅为透明说明 · T4

---

## 7. 实测数据回填区

> T0 / T3 / T5 完成后填这里，并同步回设计稿。

| 指标 | 数值 | 测量条件 | 日期 |
| --- | --- | --- | --- |
| sherpa-onnx AAR 各 ABI 未压缩 .so 合计 | arm64-v8a 25.2MB / armeabi-v7a 17.6MB / x86_64 28.8MB / x86 29.8MB | 解包实际接入的 `sherpa-onnx-1.13.7.aar`（`fetchSherpaOnnxAar` 已验证下载+sha256） | 2026-09-05 |
| APK 体积增量（压缩后，release） | **待测——本机环境阻塞**：`app/src/main/jni/librime/plugins/librime-lua/thirdparty/lua5.4/liolib.c` 在 NDK 28.0.13004108 + Clang 下 `fseeko`/`ftello` 隐式声明报错，与本特性无关的既有原生工具链问题（未改动任何 `jni/**` 文件即可复现）。已确认与本特性引入的 cmake 版本无关（换回 SDK 匹配的 cmake 3.31.6 后依旧报错）。需要换一个更旧的 NDK，或给 `librime-lua` 的 CMake 补 `_FILE_OFFSET_BITS=64`/`_POSIX_C_SOURCE` 定义（超出本特性改动范围，未处理） | 2026-09-05 |
| `:app:compileDebugKotlin` / `:app:testDebugUnitTest` | 全部通过（96/96，含新增 45 个语音相关用例） | 不依赖原生库，可在本机稳定验证 | 2026-09-05 |
| 模型加载耗时 / 推理耗时 / 进程 RSS / 30 分钟存活率 | **待测——需要真机或模拟器**：本机 `adb devices` 为空、无 AVD，且上条的原生构建问题导致连 debug APK 都无法产出 | | |
