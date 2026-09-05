# Trime 内置语音输入（SenseVoice + AI 校对）· 设计方案

> 目标产物：在 **trime 本进程内**完成「按住说话 → 本地 SenseVoice 识别 → 可选 LLM 智能校对 → 上屏」，
> 配置界面直接扩展在 trime 现有设置里。不依赖任何外部 App、外部进程、外部服务端。
>
> 参考系：
> - `~/projects/voice-typer/macos/` —— **产品形态与取舍的蓝本**（精简够用、配置面收敛、失败兜底、校对提示词）
> - `~/projects/BiBi-Keyboard/` —— **Android 上 SenseVoice 落地的技术蓝本**（sherpa-onnx、模型管理、LLM 后处理）
> - `~/projects/trime-bibi-keyboard/` —— **触发与上屏 UI 的蓝本**（长按录音、波形浮层、工具栏按钮），其双进程方案被本设计废弃

---

## 1. 目标与非目标

### 1.1 目标

| # | 目标 | 验收标准 |
| --- | --- | --- |
| G1 | 单进程、零外部依赖 | 装好 trime → 设置里下载一次模型 → 授权麦克风 → 长按语音键即可听写。不装第二个 App，不受电池优化/后台清理影响 |
| G2 | 识别能力对齐 BiBi 的 SenseVoice 本地引擎 | 同一段音频，识别文本与 BiBi「SenseVoice small-int8 / 相同 language / 相同 ITN 设置」逐字一致（同一个 sherpa-onnx 版本、同一份模型文件，理论上必然一致，作为回归基线） |
| G3 | 不拖垮输入法本体 | 未开启语音功能时：零内存开销、零线程、零网络；开启但空闲时：引擎已卸载，常驻开销 ≈ 0 |
| G4 | 配置面收敛 | 用户可见配置项 ≤ 12 项，且"必须配置才能用"的只有 1 项（下载模型）；AI 校对默认关闭 |
| G5 | 对上游 trime 的侵入最小 | 改动上游既有文件 ≤ 8 处、合计 ≤ 80 行；其余代码全部落在新增的 `ime/voice/**`、`data/voice/**` 两个包里，便于长期跟随 upstream develop |
| G6 | 隐私可自证 | 全流程离线；只有当用户显式开启 AI 校对时才出网，且全项目只有一个出网点（`LlmCorrector`），可被 code review 一眼确认 |

### 1.2 非目标（本轮明确不做）

- **不做流式/实时预览**。松手后整段识别一次（理由见 §4.4）。
- **不做 VAD 自动判停**、不做「畅说模式」、不做连续听写。
- **不做多识别供应商**：只有本地 SenseVoice-Small，没有云端 ASR、没有主备切换、没有本地兜底链。
- **不做 AI 编辑面板 / 语音指令编辑 / 历史记录 / 音频重跑**（BiBi 的这些能力一律不移植）。
- **不做悬浮球、不做 AIDL/SpeechRecognizer 对外接口、不做 IME Bridge**。
- **不做热词、不做简繁转换**（trime 本身有 OpenCC，不在语音链路里重复）。
- **不做标点模型**（ct-punc）：SenseVoice 的 ITN 开关已能出标点。
- **不把模型打进 APK**。

### 1.3 命名

| 项 | 取值 |
| --- | --- |
| 代码包 | `com.osfans.trime.ime.voice`（运行时）/ `com.osfans.trime.data.voice`（模型与配置） |
| 设置页 | 「语音输入」，位于主设置列表 `剪贴板` 与 `高级` 之间 |
| Prefs 前缀 | `voice__*` |
| 模型目录 | `<app external files>/voice/sensevoice/small-int8/` |

---

## 2. 现状盘点

### 2.1 `voice-typer/macos/`：拿走产品决策，不拿代码

macOS 版是 Swift 单进程实现，代码不可直接复用。真正有价值的是它已经验证过的**取舍**：

| 它的决策 | 本方案是否继承 | 说明 |
| --- | --- | --- |
| 只留 SenseVoice-Small，砍掉 paraformer / ct-punc / 热词 | ✅ 继承 | 配置面收敛的最大来源 |
| 空闲 N 分钟卸载引擎，按键时与录音并行重新加载 | ✅ 继承 | 移动端更需要（§4.2） |
| 录音时长下限（0.3s）防误触 | ✅ 继承 | 长按语音键误触概率比桌面热键更高 |
| 单段录音有硬上限并主动收尾（macOS 120s） | ✅ 继承，改为可配（默认 60s，上限 300s） | 移动端内存更紧 |
| LLM 校对：失败/超时/截断 **一律返回 ASR 原文**，绝不丢文本 | ✅ 继承，逐条移植 | 见 §5.6 |
| 校对提示词 `Resources/correction.md` | ✅ **原样搬运，一字不改** | 已在真实使用中打磨过，重写只会退化 |
| API Key 不落明文配置文件（macOS 存 Keychain） | ⚠️ 有条件继承 | Android 侧见 §8.3 |
| 设置页「测试校对」按钮 | ✅ 继承 | 把配置错误暴露在配置时而不是听写时 |
| 流式预览（15s 滑窗 + 松手整段重跑） | ❌ **不继承** | 见 §4.4 |
| 首启自动下载模型 | ❌ 不继承，改为**用户在设置页显式触发** | 输入法静默下载 228MB 不可接受 |

### 2.2 `BiBi-Keyboard/`：只取 SenseVoice 与 LLM 后处理两条链路

BiBi 是一个 18 家 ASR 供应商的大工程，绝大部分与本方案无关。有效信息只有下面这些（均已在其源码中核对）：

| 事实 | 出处 | 对本设计的意义 |
| --- | --- | --- |
| 本地识别走 **sherpa-onnx 预编译 AAR**（`app/libs/sherpa-onnx-1.13.4.aar`，46.6MB，已提交进 git），通过**反射**调用 | `SenseVoiceFileAsrEngine.kt:552` `SenseVoiceOnnxManager` | 证明 Android 上 SenseVoice 走 sherpa-onnx 是通路；反射是因为他们要在 AAR 缺失时优雅降级，本方案直连不需要 |
| 关键 API：`OfflineRecognizer` / `OfflineRecognizerConfig` / `OfflineModelConfig` / `OfflineSenseVoiceModelConfig{model,language,useInverseTextNormalization}`，`numThreads` / `provider="cpu"` | 同上 `buildSenseVoiceConfig` / `buildModelConfig` | 直接照抄字段名即可 |
| 从绝对路径加载模型时 **`assetManager` 必须传 null** | 同上代码注释，引 sherpa-onnx issue #2562 | 一个会浪费半天的坑，白捡 |
| 引擎带**保活/卸载**：`svKeepAliveMinutes`（<0 常驻，0 立即卸载，>0 保活分钟数） | `SenseVoiceFileAsrEngine.recognize` | 与 voice-typer 的 idle unload 是同一件事，两边独立收敛到同一结论 |
| 本地 SenseVoice 单段录音上限 **5 分钟**，注释写明「为降低内存占用」 | `SenseVoiceFileAsrEngine.maxRecordDurationMillis` | 印证 §4.2 的内存判断 |
| 模型变体只有 `small-int8` / `small-full`，从他们自建的 GitHub Release 下载 zip | `AsrLocalModelSpecs.kt:77` | 本方案改为直连 sherpa-onnx 官方 `asr-models` release（§4.3） |
| 录音：`AudioRecord`，16k / mono / PCM16，优先 `VOICE_RECOGNITION`，失败回落 `MIC` | `AudioCaptureManager.kt:340,355` | 直接采用 |
| LLM 后处理：OpenAI 兼容 `/chat/completions`，system prompt 用用户选中的提示词，待处理文本包在 user message 里 | `LlmPostProcessor.kt:1596-1626` | 结构与 voice-typer 一致；本方案用 voice-typer 的提示词 |
| `packaging.jniLibs.excludes` 掉 `libonnxruntime4j_jni.so` / `libsherpa-onnx-c-api.so` / `libsherpa-onnx-cxx-api.so` | `app/build.gradle.kts` | 白捡的体积优化，已验证可行 |

**明确不取**：18 家供应商抽象、`ParallelAsrEngine`、`LazyLocalBackupAsrEngine`、伪流式分段、VAD、多 LLM Provider 配置、请求模式探测、reasoning 参数、API 日志、历史记录、Compose/Miuix 设置体系。

### 2.3 `trime-bibi-keyboard/`：双进程为什么必须废弃，以及 UI 层可以原样继承什么

这个 fork 在 trime 里加了 `link/AsrkbSpeechClient`（924 行），通过 AIDL 调起 BiBi 进程录音识别。**用户实测的失败模式**：BiBi 使用频率低 → 进程被系统/厂商 ROM 清理 → 长按语音键时冷启动失败或超时。这不是代码 bug，是 Android 后台进程治理的必然结果，**在双进程架构下无法根治**（保活、Shizuku、前台服务都只是把失败率往下压一点）。

但它的 **UI 触发层是好的，且已经在真机上被验证过**，本方案直接继承其形状（重写为无 IPC 版本）：

| 它的做法 | 本方案 |
| --- | --- |
| `KeyView` 长按分支：当长按动作是 `KEYCODE_VOICE_ASSIST` 且总开关打开时，`onLongPress` 起录音，`ACTION_UP`/`CANCEL` 停 | ✅ 同形状，把 `AsrkbVoiceHoldSessionController` 换成本地 `VoiceInputDelegate` |
| 录音浮层：在键盘视图上盖一层**不可点击**的 `FrameLayout` + 波形，触摸事件穿透回按键 | ✅ 同思路，但挂到 `InputView` 而不是 `KeyboardView`（§5.7），这样切到符号窗口也不受影响 |
| 浮层配色从 `ColorManager` 取，按对比度挑线条色 | ✅ 原样继承这段逻辑 |
| 工具栏麦克风按钮（点按开始/停止，图标随状态切换） | ✅ 继承，但不需要新增按钮类型（§6.2） |
| 录音时压低其他 App 音量（AudioFocus，`AsrkbRecordingAudioFocusController`，222 行） | ⚠️ 简化：只申请 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`，约 30 行 |
| 上屏后跟踪用户手动修改并回报给 BiBi（`AsrkbCorrectionTracker`） | ❌ 删除，这是给 BiBi 做数据回流的，本方案无此需求 |
| 剪贴板同步桥（529 行） | ❌ 与语音输入无关 |

### 2.4 trime 本体：必须尊重的架构约束

已核对当前 `develop`（3.3.13）：

| 约束 | 事实 | 影响 |
| --- | --- | --- |
| DI | `InputView` 里一个 kodein `DI{}`，`bindSingleton { XxxDelegate(di) }`，组件用 `by di.instance()` 取依赖 | 新增 `VoiceInputDelegate(di)` 一行注册即可 |
| 面板体系 | `BoardWindow` + `BoardWindowManager`（键盘/符号窗口切换） | 录音 UI **不做成 BoardWindow**（原因见 §5.7） |
| 设置体系 | 无 XML preference，全部由 `PreferenceDelegateOwner` 声明式生成（`switch/editText/int/list/enum`），`PreferenceDelegateFragment` 渲染，`NavigationRoute` 注册路由 | 新增设置页极其便宜；但**没有现成的自定义 Preference 范式**，模型管理项需要自己写（有 `SoundEffectPickerDialog`/`ThemePickerDialog` 可参照） |
| 权限 | manifest 现有权限只有 `RECEIVE_BOOT_COMPLETED` / `VIBRATE` / `POST_NOTIFICATIONS`，**没有 INTERNET，没有 RECORD_AUDIO** | 这是本方案最敏感的变更点，见 §8 |
| 网络库 | 项目里**一个 HTTP 客户端都没有**（无 OkHttp / Retrofit / Ktor） | 用 JDK 自带 `HttpURLConnection`，不引新依赖（§4.5） |
| 已有能力 | `kotlinx-serialization-json`、`kotlinx-coroutines`、`androidx-work`、`XXPermissions`、`timber` 均已在依赖里 | 全部复用 |
| 语音键 | `CommonKeyboardActionListener.kt:143` 已有 `KEYCODE_VOICE_ASSIST -> switchToVoiceInputMethod()`（切到系统语音输入法），并有 `general.preferredVoiceInput` 配置项 | **这就是天然的接入点**：开关打开时接管这个 keycode，否则维持旧行为 |
| 主题按键 | `KeyCode.nameToKeyCode` 会尝试 `KeyEvent.keyCodeFromString("KEYCODE_$name")`，所以主题 YAML 里 `send: VOICE_ASSIST` 就能绑定 | 用户无需新语法即可把语音键放到任意键位/长按位 |
| 工具栏 | 工具栏按钮的 `action` 字符串统一走 `KeyActionManager.getAction(action)` → 与按键同一条动作管线 | 工具栏麦克风按钮不需要新机制 |
| ABI | `splits.abi` 已开启，`isUniversalApk = false`，四个 ABI 各出一个 APK | 引入 sherpa-onnx 的体积代价按 ABI 摊薄（§4.1） |
| 打包 | `jniLibs.useLegacyPackaging = true`（安装时解压 .so） | APK 增量按压缩后算，设备占用按解压后算，两个数都要报给用户 |
| minSdk / 16KB | minSdk 21；sherpa-onnx AAR manifest 声明 `minSdkVersion 21`，其 `libonnxruntime.so` 的 PT_LOAD 对齐实测为 **0x4000（16KB）** | 兼容性与 Android 15+ 的 16KB 页面要求都满足 |

---

## 3. 总体架构

```
┌─────────────────────── trime 进程（唯一进程） ────────────────────────────┐
│                                                                          │
│  TrimeInputMethodService                                                 │
│    └── InputView（kodein DI 容器）                                        │
│          ├── KeyboardWindow / LiquidWindow / InputBarDelegate  ← 既有     │
│          └── VoiceInputDelegate  ← 新增，唯一挂进 DI 的语音组件            │
│                 ├── VoiceOverlayUi      录音浮层（波形 / 计时 / 状态文案） │
│                 └── VoiceSession        单次听写的状态机                   │
│                        Idle → Recording → Recognizing → Correcting → Done │
│                          │                                               │
│    触发源（三选一，行为一致）：                                            │
│      · 按键长按 / 点按（主题里 send: VOICE_ASSIST）                        │
│      · 工具栏麦克风按钮（同一条 KeyAction 管线）                           │
│      · 无 → 维持上游「切到系统语音输入法」旧行为                            │
│                                                                          │
│  ┌── voiceScope（Dispatchers.Default 上的串行 Mutex）──────────────────┐   │
│  │  AudioRecorder ─ 16k/mono/PCM16 ─► ShortArray 累积                 │   │
│  │  SenseVoiceEngine（sherpa-onnx OfflineRecognizer 薄封装）           │   │
│  │      ├ 加载互斥 / 空闲卸载定时器 / 语言与 ITN 变更时重建            │   │
│  │      └ decode(FloatArray) : String                                 │   │
│  │  TextPostProcessor（本地清洗：去 emoji 标签、收敛空白、CJK 空格）    │   │
│  └────────────────────────────────────────────────────────────────────┘   │
│                          │ ASR 原文                                       │
│  ┌── 可选，仅当用户开启 ───┴──────────────────────────────────────────┐    │
│  │  LlmCorrector（HttpURLConnection → OpenAI 兼容 /chat/completions） │    │
│  │    失败/超时/截断 → 原样返回 ASR 原文                              │    │
│  └───────────────────────────────────────────────────────────────────┘    │
│                          │ 最终文本                                       │
│                          ▼                                                │
│              service.commitText(text)                                     │
│                                                                          │
│  设置侧：VoiceInputSettingsFragment → AppPrefs.Voice                      │
│          VoiceModelManager（下载 / 导入 / 校验 / 删除）                    │
│                                                                          │
│  依赖：sherpa-onnx AAR（jni: libonnxruntime.so + libsherpa-onnx-jni.so）   │
│  模型：<external files>/voice/sensevoice/small-int8/{model.int8.onnx,      │
│                                                      tokens.txt}          │
└──────────────────────────────────────────────────────────────────────────┘
```

**核心原则：语音是一个"挂件"，不是一条新的输入主干。**
它不参与 rime 的候选/编码流程，不注册 `InputBroadcastReceiver`（除了需要感知窗口销毁），
唯一与 trime 主干耦合的动作是最后那一次 `commitText`。未开启时整棵子树不构造。

---

## 4. 关键技术选型

### 4.1 推理运行时 —— sherpa-onnx 预编译 AAR

**已实测的体积数据**（解包实际接入的 `sherpa-onnx-1.13.7.aar`，`fetchSherpaOnnxAar` 已在本机下载校验通过，未压缩字节；早前设计阶段基于 BiBi 的 1.13.4 版本的估算已被下方数字取代，版本间差异可忽略）：

| ABI | libonnxruntime.so | libsherpa-onnx-jni.so | 需保留合计 | 可 exclude 掉的 |
| --- | ---: | ---: | ---: | --- |
| arm64-v8a | 20.7 MB | 4.5 MB | **25.2 MB** | c-api 4.3MB + cxx-api 0.42MB |
| armeabi-v7a | 14.3 MB | 3.3 MB | **17.6 MB** | c-api 3.1MB + cxx-api 0.27MB |
| x86_64 | 23.8 MB | 5.0 MB | 28.8 MB | — |
| x86 | 24.7 MB | 5.1 MB | 29.8 MB | — |

其余：`classes.jar` 238KB，AAR manifest 声明 `minSdkVersion 21`。

由于 trime 已开启 `splits.abi` 且 `isUniversalApk = false`，**用户实际下载的单 ABI APK 增量待实测**（本机因预置的 NDK 28 + 项目内 librime-lua 的 vendored Lua 5.4 存在 `fseeko`/`ftello` 隐式声明冲突——这是与本特性无关的既有工具链问题，见实施计划 T5 备注——未能跑通 `assembleRelease` 拿到真实压缩后的 APK 体积；上表未压缩字节是上限的可靠代理，实际 zip 压缩后的 APK 增量预计明显更小）；因为 `useLegacyPackaging = true`，安装后设备上按上表"需保留合计"列增加相应磁盘占用。这个代价必须在 README 与 CHANGELOG 里明写（已在 README.md/README_sc.md 的"语音输入"一节写明数量级）。

**否决的备选：**

| 方案 | 否决理由 |
| --- | --- |
| `onnxruntime-android` + 自己用 Kotlin 重写 fbank/LFR/CMVN/CTC（即 voice-typer 在 macOS 上走的路） | 只省下 ~4.7MB 的 `libsherpa-onnx-jni.so`，却要移植 ~900 行强数值代码。macOS 侧之所以值得，是因为那边 sherpa-onnx 要自己搞 C++/xcframework 构建；Android 侧官方直接给预编译 AAR，成本对比整个反过来。而且我们**没有** voice-typer 那套逐点比对的金标准夹具可用 |
| 保留双进程（现状） | 用户已实测失败，见 §2.3 |
| 云端 ASR | 与"离线输入法"的定位冲突；且用户明确要本地 |
| 独立 `:voice` 子进程（同 APK） | 见 §4.2，作为**退路**保留而非首选 |

**获取方式（决策 D1）**：**不把 46MB 的 AAR 提交进 git**（BiBi 那样做会让 trime 仓库和每次 clone 都变重）。改为在 `build-logic` 里加一个 `FetchSherpaOnnxPlugin`：从
`https://github.com/k2-fsa/sherpa-onnx/releases/download/v<VER>/sherpa-onnx-v<VER>-android.tar.bz2`
下载 → 校验 sha256（pin 在 `Versions.kt`）→ 解出 AAR 到 `app/libs/`，产物入 `.gitignore`。
这与 trime 既有的 build-logic 插件风格（`OpenCCDataPlugin` / `DataChecksumsPlugin`）一致。
> 备选：jitpack（`com.github.k2-fsa:sherpa-onnx`）。仓库里有 `jitpack.yml`，但官方文档只宣传"从 release 下载 AAR"，**未验证 jitpack 坐标可用**，因此不作为首选。

`packaging.jniLibs.excludes` 直接抄 BiBi 已验证的三条：`libonnxruntime4j_jni.so`、`libsherpa-onnx-c-api.so`、`libsherpa-onnx-cxx-api.so`。

### 4.2 进程模型 —— 同进程 + 保活/卸载（决策 D2）

**结论：v1 跑在 trime 主进程里。**

理由：
1. 用户的痛点是"**另一个 App** 的进程被杀"。同进程彻底消灭这个失败模式。
2. BiBi 自己就是在输入法进程里跑 SenseVoice + Compose UI 的，属于已被验证的量级。
3. 双进程/子进程会把 IPC、生命周期、音频路由、错误传播全部复杂化，与 G4「精简够用」冲突。

**代价与对策**（诚实记录，这是本方案最大的技术风险）：

- SenseVoice int8 模型文件 228MB，ONNX Runtime 会把权重读进内存，**预计常驻 300~500MB**（此数字来自 macOS 侧同模型的实测量级：默认 ~800MB、关闭 prepacking 后 ~510MB；**Android 上尚未实测，P4 必须补**）。叠加 trime 自身的 librime + 词典，输入法进程会变得很重，被 LMK 回收的概率上升。
- 对策一：**空闲卸载**（`voice__keep_alive_minutes`，默认 5 分钟；0 = 用完即卸；-1 = 常驻）。这同时是 voice-typer 和 BiBi 独立收敛到的同一结论。
- 对策二：**按下语音键立刻并行预加载**，与录音同时进行；用户说话的时间就是加载时间。
- 对策三：单段录音上限（默认 60s，可调到 300s），超时主动收尾走与松手完全一致的路径（继承 voice-typer 的 R3-03 决策：不要静默丢弃后续音频）。
- 对策四：`OfflineRecognizerConfig.modelConfig.numThreads` 默认 2（BiBi 的默认值），避免抢占 rime 的 CPU。
- **退路（不在 v1 实施，但接口要为它留位）**：若 P4 真机实测发现输入法进程被频繁回收，把 `SenseVoiceEngine` 挪到同一 APK 的 `:voice` 子进程，用 `bindService(BIND_AUTO_CREATE)` 由 IME 持有绑定。这仍然规避了"另一个 App"的问题（同一 APK、同一 UID、随主 App 安装更新、绑定期间进程受前台 IME 的重要性庇护），且 PCM 传输量很小（60s 音频 ≈ 1.9MB，走文件或 ashmem）。
  为此，`SenseVoiceEngine` 的对外接口只暴露 `suspend fun decode(samples: FloatArray): String` 这一个纯数据方法，**不泄漏任何 sherpa 类型**，将来换成 IPC 实现时调用方零改动。

### 4.3 模型分发 —— 不入包，设置页显式获取（决策 D3）

| 变体 | 文件 | 大小 | 说明 |
| --- | --- | ---: | --- |
| `small-int8`（**唯一支持**） | `model.int8.onnx` | 228 MB | sherpa-onnx 官方文档给出的尺寸 |
| | `tokens.txt` | 308 KB | |

来源（官方 `asr-models` release，`.tar.bz2`）：

```
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/
    sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2   # 与 BiBi 同版本，作为默认
    sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2   # 更新版本，P5 评估后再切
```

`VoiceModelManager` 要点（大部分是 voice-typer `ModelDownloader` 的直译，另加 Android 特有项）：

- 落点 `context.getExternalFilesDir(null)/voice/sensevoice/small-int8/`。**不放进 rime 用户数据目录**——那里会被同步/备份，228MB 会毁掉用户的备份体验。
- 三种获取方式，UI 上并列：
  1. **在线下载**：`DownloadWorker`（WorkManager，已在依赖里）。必须支持 `Range` 断点续传、前台通知显示进度、可取消、退出 App 后继续。
  2. **改下载地址**：一个 `editText` 项，默认填官方 URL。GitHub 在部分网络环境下不可达，这是唯一低成本的逃生口。
  3. **本地导入**：SAF 选一个 `.tar.bz2` / `.zip`，本地解压。用户可以自己用任何方式把包搞到手机上。
- 校验：解压后校验 `model.int8.onnx` 与 `tokens.txt` **存在 + 文件大小在预期区间 + sha256 命中**（sha256 pin 在代码里；因为要兼容用户自备的 fp32 变体，sha256 不匹配时降级为**警告**而非拒绝，只要 sherpa 能成功构造 recognizer 就放行 —— 这条规则抄 BiBi 的 `LocalModelIntegrity` 思路）。
- 写 `.part` 临时文件 + 校验通过后原子 rename，绝不留下"看起来完整"的半个模型目录。
- 解压 `.tar.bz2` 需要 bzip2：引入 `org.apache.commons:commons-compress`（BiBi 也用它，jar 约 1MB，纯 Java 无 .so）。`.zip` 走 JDK 自带。
- 设置页显示：未安装 / 下载中 x% / 已就绪（含目录大小）/ 校验失败原因；提供「删除模型」（带二次确认）。

**许可证动作**：SenseVoice 权重适用 [FunASR Model Open Source License](https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE)，其 §2.2 有署名要求。由于模型由用户自行下载、APK 不分发权重，合规压力低；仍需在「关于」页与 README 标注 `Powered by SenseVoice-Small (FunAudioLLM) via sherpa-onnx`。sherpa-onnx 本身 Apache-2.0，需进 `aboutlibraries` 的许可证清单（trime 已有该机制）。

### 4.4 不做流式预览（决策 D4）

voice-typer 在 macOS 上做了 15 秒滑窗预览（稳态 CPU ≈ 27%）。移动端**不移植**：

- 手机 CPU 上 SenseVoice int8 的 RTF 远高于 M4，滑窗预览意味着录音全程持续满载 1~2 个核，直接换来发热、掉电和主键盘的输入卡顿——而输入法的第一优先级是**不卡**。
- 桌面 HUD 有充裕的显示空间来展示自我修正的预览文本；手机上录音浮层就那么大，预览文本反复跳变是负价值。
- 预览带来的心理延迟收益，在"按住说一句话（2~6 秒）"的手机场景里本来就小。

替代方案：录音时显示**波形 + 计时 + 状态文案**（继承 trime-bibi 的浮层），松手后一次整段识别。
若 P5 实测「松手 → 上屏」延迟不可接受，**再**考虑 BiBi 的 `NonStreamingProgressiveChunking`（录音期间对已完成的定长分段提前解码，松手时只解码尾巴）——它比滑窗预览省得多，作为 P2 优化候选。

### 4.5 LLM 校对的网络层 —— 不引 HTTP 库（决策 D5）

trime 目前**没有任何 HTTP 客户端依赖**。为了一个可选功能引入 OkHttp（含其传递依赖 okio）不划算，也让"这个输入法会不会偷偷联网"的审查变难。

采用 `java.net.HttpURLConnection` + `kotlinx-serialization-json`（都已在手）：一次非流式 POST，约 120 行。
**不做流式（SSE）**：校对结果要整体替换 ASR 原文，逐 token 显示对上屏毫无价值（BiBi 的打字机效果是给它自己的预览面板用的）。

---

## 5. 模块设计

### 5.1 目录结构（新增文件全景）

```
app/src/main/java/com/osfans/trime/
├── ime/voice/
│   ├── VoiceInputDelegate.kt      # DI 组件，唯一对外入口；持有 overlay 与 session
│   ├── VoiceSession.kt            # 单次听写状态机
│   ├── VoiceSessionState.kt       # sealed class: Idle/Recording/Recognizing/Correcting/Error
│   ├── AudioRecorder.kt           # AudioRecord 封装，产出 FloatArray + 振幅回调
│   ├── AudioFocusGuard.kt         # 录音期间 duck 其他 App（~30 行）
│   ├── ui/VoiceOverlayUi.kt       # 浮层（splitties views DSL，跟随主题配色）
│   └── ui/WaveformView.kt         # 波形（从 trime-bibi 继承，MIT/Apache 出处需标注）
├── data/voice/
│   ├── VoicePrefs.kt              # PreferenceDelegateOwner 子类（见 §7）
│   ├── SenseVoiceEngine.kt        # sherpa-onnx 薄封装 + 加载互斥 + 空闲卸载
│   ├── VoiceModelManager.kt       # 定位/校验/删除
│   ├── VoiceModelDownloadWorker.kt# WorkManager 下载 + 解压
│   ├── TextPostProcessor.kt       # 本地文本清洗
│   └── llm/
│       ├── LlmCorrector.kt        # OpenAI 兼容校对客户端（唯一出网点）
│       └── LlmEndpoint.kt         # base_url 结构化解析与拼接
├── ui/main/settings/
│   ├── VoiceInputSettingsFragment.kt
│   └── VoiceModelDialog.kt        # 模型下载/导入/删除
└── res/raw/voice_correction_prompt.md   # 从 voice-typer 原样搬运
```

上游既有文件的改动清单见 §6.3。

### 5.2 `VoiceSession` —— 状态机

```kotlin
sealed interface VoiceSessionState {
    data object Idle : VoiceSessionState
    data class Recording(val elapsedMs: Long, val amplitude: Float) : VoiceSessionState
    data object Recognizing : VoiceSessionState
    data class Correcting(val asrText: String) : VoiceSessionState
    data class Error(@StringRes val reason: Int, val detail: String?) : VoiceSessionState
}
```

```
Idle ──start()──► Recording ──stop()───► Recognizing ──[LLM 关]──────────► commit → Idle
                     │                        │
                     │                        └──[LLM 开]──► Correcting ──► commit → Idle
                     │                                            │
                     ├──cancel() / 上滑 / 窗口销毁 ──► Idle（丢弃音频，不上屏）
                     ├──时长 < 0.3s ──► Idle（误触，静默丢弃）
                     └──时长 ≥ 上限 ──► 走与 stop() 完全相同的收尾路径 + 一次 toast
```

规则（逐条对应 voice-typer 已踩过的坑）：

- 录音不足 **300ms** 判为误触，直接丢弃，不识别、不提示。
- 达到时长上限时**主动收尾并上屏**，而不是继续录后面被静默丢掉的音频。
- `Recognizing` / `Correcting` 期间再次按下语音键：**拒绝**（提示"正在识别"），不叠加会话。
- 会话被取消/输入框失焦/`onFinishInputView` 后到达的识别结果，**丢弃不上屏**（用 sessionId 校验）。
- 任何一步失败，状态机必须回到 `Idle` 且浮层必须消失——UI 卡在录音态是最糟的失败形态。
- `Recognizing` 有看门狗（默认 30s）：本地推理理论上不会卡死，但要防 native 层异常导致协程永不返回。

### 5.3 `AudioRecorder`

```kotlin
class AudioRecorder(private val onAmplitude: (Float) -> Unit) {
    suspend fun record(maxDurationMs: Long, cancelSignal: ...): FloatArray
}
```

- 16000 Hz / `CHANNEL_IN_MONO` / `ENCODING_PCM_16BIT`（SenseVoice 的输入契约）。
- `AudioSource.VOICE_RECOGNITION` 优先，构造失败回落 `MIC`（抄 BiBi）。
- 读线程在 `Dispatchers.IO`，每读到一块就算 RMS 推给浮层做波形（节流到 ~20fps）。
- 直接累积成 `FloatArray`（`sample / 32768f`），60s 上限 ≈ 3.8MB float，可接受；不落磁盘（不留音频文件是隐私优势，也少一堆清理逻辑）。
- 录音起始有约 100~200ms 的硬件启动延迟（CoreAudio 侧实测 150~171ms，Android 侧同类量级）。**不做 pre-roll 常开麦克风**——输入法常开麦克风在隐私上不可接受。改为：浮层在真正拿到第一个音频块后才开始波形动画（这条 trime-bibi 已经做了，commit `12b5c853`「延迟显示波形直至真实录音开始」）。

### 5.4 `SenseVoiceEngine`

```kotlin
object SenseVoiceEngine {
    sealed interface State { Unloaded; Loading; Ready; Failed(cause) }
    val state: StateFlow<State>

    suspend fun preload()                                  // 按下语音键时调用，与录音并行
    suspend fun decode(samples: FloatArray): String        // 唯一推理入口，Mutex 串行
    fun unloadNow()
    fun onConfigChanged()                                  // 语言/ITN/线程数变化 → 标记需重建
}
```

- 内部用 `Mutex` 保证加载与推理串行（与 BiBi 的 `withLock` 一致）。
- `OfflineRecognizerConfig`：
  ```kotlin
  modelConfig = OfflineModelConfig(
      tokens = "$dir/tokens.txt",
      numThreads = prefs.numThreads,        // 默认 2
      provider = "cpu",
      debug = false,
      senseVoice = OfflineSenseVoiceModelConfig(
          model = "$dir/model.int8.onnx",
          language = prefs.language,        // auto/zh/en/yue/ja/ko
          useInverseTextNormalization = prefs.itn,
      ),
  )
  ```
  从绝对路径加载时 **`assetManager` 必须为 null**（sherpa-onnx issue #2562，BiBi 代码里有这条注释）。
- 空闲卸载：每次 `decode` 完成后重置定时器；到点后在 Mutex 内 `release()` 并置 `Unloaded`。定时器用 `voiceScope` 的协程而非 `Handler`，随 Delegate 一起取消。
- **不在 IME 启动时预加载**，只在按下语音键时预加载。输入法冷启动路径上多 1~3 秒的模型加载是不可接受的。
- 加载失败（文件缺失/损坏/OOM）→ `Failed`，浮层显示具体原因并给出「去设置」入口。

### 5.5 `TextPostProcessor`

sherpa-onnx 已经做了 CTC 解码与基础文本化，但仍需一层本地清洗（各条规则均来自 voice-typer `TextPostprocessor` 与 BiBi `TextSanitizer` 的交集）：

1. 剥掉残留的 `<|...|>` 标签（语言/情感/事件标记）与 emoji 标签。
2. 空白收敛：多空格 → 单空格，首尾 trim。
3. 去掉与 CJK 字符相邻的空格（`你好 世界` → `你好世界`；`AI Coding` 保留）。
4. **若结果不含任何"实字"（只剩标点/空白），返回空串**——静音时 SenseVoice 常吐一个孤立句号，必须丢弃。空串不上屏、不提示（或提示"没有听清"）。
5. 可选：去掉句末标点（`voice__trim_trailing_punct`，默认关）。BiBi 默认开这个是因为它做分段拼接，我们不分段，所以默认关。

这一层是**纯函数**，是整个特性里单元测试性价比最高的地方。

### 5.6 `LlmCorrector`

```kotlin
class LlmCorrector(private val config: LlmConfig) {
    suspend fun correct(asrText: String): Result   // Corrected(text) | FellBack(reason, original)
}
```

`llm_client.py` / macOS `LLMCorrector` 的直译，**逻辑一条不改**：

- system prompt = `res/raw/voice_correction_prompt.md`（voice-typer 的 `correction.md` 原样搬运），用户可在设置里覆盖（`editText`，留空 = 用内置）。
- user message 把待校对文本包在 `<asr_text>…</asr_text>` 里。
- `max_tokens = max(配置值, 原文长度 * 2 + 128)`，防长听写被截断。
- `finish_reason == "length"` → **放弃修正，返回原文**。
- 防御性剥离模型回显的 `<asr_text>` 标签。
- 任何失败（网络/超时/鉴权/JSON 解析/空响应）→ 记日志 + 浮层一闪而过的警告 + **使用 ASR 原文**。**绝不因为校对失败而丢掉用户说的话**，这是整个特性最重要的一条不变量。
- 超时默认 5s；超时后立刻用原文上屏（用户体感是"AI 没帮上忙"，而不是"输入法卡住了"）。
- `LlmEndpoint`：结构化解析 base_url，白名单 scheme（https，明文 http 仅允许回环/私网地址），`/chat/completions` 后缀去重（用户填 `https://api.x.com/v1` 或 `https://api.x.com/v1/chat/completions` 都要能工作）。这段 voice-typer 有对应的单元测试，规则直接照搬。
- 设置页「测试校对」：用一段固定的含错样例做一次真实往返，把 base_url / key / model 的配置错误暴露在**配置时**。

### 5.7 录音 UI —— 为什么不是 `BoardWindow`

第一直觉是做成 `BoardWindow`（像符号窗口那样切换过去）。**不行**，因为：
按住键盘上的语音键时，手指的 `MotionEvent` 序列必须持续送达那个 `KeyView`；一旦 `BoardWindowManager.attachWindow` 把 `KeyboardWindow` 的视图从布局里摘掉，按键会收到 `ACTION_CANCEL`，"按住说话"当场断掉。trime-bibi 用"盖一层不可点击的浮层"正是绕开这个问题。

本方案：`VoiceOverlayUi` 的 root 由 `VoiceInputDelegate` 持有，**加到 `InputView` 上**（与 `popup.root` 同级，覆盖 `keyboardView` 的区域），而不是像 trime-bibi 那样加到 `KeyboardView` 里。这样：

- 不管当前是键盘窗口还是符号窗口，浮层都能正常显示；
- 不需要在 `KeyboardWindow` 上加 4 个 `showXxx/hideXxx` 转发方法（trime-bibi 加了）；
- `isClickable = false` + `isFocusable = false`，触摸穿透回下面的按键，长按手势不受影响；
- 点按模式下浮层顶部放一个"停止"按钮区域（这块是可点击的），其余仍然穿透。

浮层内容：波形（`WaveformView`，配色从 `ColorManager` 按对比度挑，逻辑继承 trime-bibi）、计时、状态文案（"正在聆听…" / "识别中…" / "AI 校对中…" / 错误原因）。
显示/隐藏用 `TransitionManager` + `Slide(Gravity.BOTTOM)`，100ms，与 trime 既有窗口切换动画一致。
振动/音效复用 `InputFeedbackManager`。

---

## 6. 触发方式与上屏

### 6.1 三种触发，一条路径

| 入口 | 配置方式 | 行为 |
| --- | --- | --- |
| 键盘按键 | 主题 YAML 里任意键位 `send: VOICE_ASSIST`（`click` 或 `long_click`） | 按 §6.2 的模式 |
| 工具栏按钮 | 主题 `tool_bar` 里按钮 `action` 指向同一动作（工具栏动作与按键共用 `KeyActionManager`） | 同上 |
| 未配置任何语音键 | —— | 设置页里给一句提示 + 一键"把长按空格设为语音输入"的引导（P3，可选） |

### 6.2 两种交互模式（`voice__trigger_mode`）

- **按住说话（默认）**：`KeyView` 长按触发 → 录音；`ACTION_UP` / `ACTION_CANCEL` → 停止并识别。手指上滑超过阈值 → 取消（这是国内用户对语音输入的肌肉记忆，值得做）。
- **点按切换**：点一次开始，再点一次（或点浮层的停止区）结束。适合长段口述与工具栏按钮。

工具栏按钮**始终**是点按切换模式（工具栏按钮的长按已被 `longPressAction` 占用）。

### 6.3 上屏与对上游文件的改动清单

上屏：`service.commitText(text)`。注意事项：

- 若此刻 rime 有未完成的编码（`composingText` 非空），先 `postRimeJob { commitComposition() }` 再 commit 语音文本，避免把语音文本插进编码中间。
- 密码类输入框（`TYPE_TEXT_VARIATION_PASSWORD` 等）：**禁用语音输入**并 toast 说明（把识别文本送进密码框既没意义又有风险）。
- 上屏后不自动加空格、不自动回车（BiBi 有这些选项，v1 不做）。

**对上游既有文件的全部改动（8 处，合计约 70 行）**：

| 文件 | 改动 |
| --- | --- |
| `AndroidManifest.xml` | `+2` 行：`RECORD_AUDIO`、`INTERNET` |
| `app/build.gradle.kts` | AAR 依赖（`fileTree(libs)`）、`packaging.jniLibs.excludes` 三条、`commons-compress` |
| `build-logic/.../Versions.kt` + 新插件 | sherpa-onnx 版本号与 sha256 |
| `data/prefs/AppPrefs.kt` | `+1` 行：`val voice = VoicePrefs(shared).register()` |
| `ime/keyboard/CommonKeyboardActionListener.kt` | `KEYCODE_VOICE_ASSIST` 分支加一个前置判断：开关打开 → `voiceInputDelegate.toggle()`，否则走既有 `switchToVoiceInputMethod()`（约 4 行） |
| `ime/keyboard/KeyView.kt` | 长按/抬手两处分支，形状与 trime-bibi 一致（约 15 行） |
| `ime/core/InputView.kt` | `bindSingleton { VoiceInputDelegate(di) }` + 把 overlay root 加进布局（约 8 行） |
| `ui/main/NavigationRoute.kt` + `MainFragment.kt` + `strings.xml` | 新设置页的路由与入口 |

`InputBarDelegate` **不改**：工具栏麦克风按钮走的是既有的 `action → KeyActionManager` 通路，图标状态同步由 `VoiceInputDelegate` 观察 `state` 后直接操作按钮视图（若做不到零改动，退而在 `InputBarDelegate` 加一个 5 行的状态订阅）。

---

## 7. 配置项设计

新增 `AppPrefs.Voice`（`PreferenceDelegateOwner` 子类），设置页「语音输入」：

| 分组 | 配置项 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| **总开关** | 启用语音输入 | switch | `false` | 关闭时整棵子树不构造；语音键退回上游"切到系统语音输入法"行为 |
| **模型** | 语音模型 | 自定义项 → 对话框 | 未安装 | 下载 / 导入 / 删除 / 显示状态与占用（§4.3） |
| | 下载地址 | editText | 官方 URL | 逃生口 |
| **识别** | 识别语言 | enum | `auto` | auto / 中文 / 英文 / 粤语 / 日语 / 韩语 |
| | 自动标点与数字规范化 | switch | `true` | = sherpa 的 `useInverseTextNormalization` |
| | 推理线程数 | int(1..4) | `2` | |
| | 空闲多久后卸载模型 | int(分钟) | `5` | `0` = 用完即卸；`-1` = 常驻 |
| **交互** | 触发方式 | enum | 按住说话 | 按住说话 / 点按切换 |
| | 单段最长录音 | int(秒, 10..300) | `60` | |
| | 去掉句末标点 | switch | `false` | |
| **AI 校对** | 启用智能校对 | switch | `false` | **关闭时不发起任何网络请求** |
| | Base URL | editText | 空 | 以下各项 `enableUiOn = { 校对开关打开 }` |
| | API Key | editText（掩码显示） | 空 | 存储见 §8.3 |
| | 模型 | editText | `gpt-4o-mini` | |
| | 温度 | int(0..20，显示为 /10) | `0` | trime 的 preference 体系没有 float seekbar，用整数除 10 |
| | 超时 | int(秒, 1..30) | `5` | |
| | 自定义提示词 | editText（多行） | 空 = 用内置 | |
| | 测试校对 | 点击项 | —— | 真实往返一次并显示结果 |

用户可见项 **17 → 实际"必须配置"只有 1 项（下载模型）**；AI 校对那一组默认折叠/禁用，不开就完全不存在。

**与两个参考项目的配置对照**：

| voice-typer / BiBi 的配置项 | 本方案 |
| --- | --- |
| voice-typer `asr.language` / `threads` / `idle_unload_minutes` / `model_dir` | 全部保留（`model_dir` 降级为内部路径，不暴露） |
| voice-typer `hotkey.*` / `ui.opacity` | 删除（Android 上由主题按键与主题配色决定） |
| voice-typer `llm.*` 六项 | 全部保留 + 新增"自定义提示词" |
| BiBi 的 18 家供应商 / 主备引擎 / 本地兜底 / 并行识别 | 全部删除 |
| BiBi 的 VAD 判停 / 畅说模式 / 自动回车 / 打字机效果 / 历史记录 / 音频缓存 | 全部删除 |
| BiBi 的多 LLM Provider / 请求模式 / reasoning 参数 | 删除，只留单套 OpenAI 兼容配置 |
| BiBi 的 `svKeepAliveMinutes` / `svNumThreads` / `svLanguage` / `svUseItn` | 全部保留（语义一致） |

---

## 8. 权限、隐私与合规

### 8.1 `RECORD_AUDIO`

- 运行时权限。`InputMethodService` **不能**直接申请运行时权限，必须由 Activity 发起。
- 方案：在**设置页**申请（复用已有的 `XXPermissions`，与 `POST_NOTIFICATIONS` 的申请方式一致）。
- 若用户在键盘上按了语音键但未授权：浮层显示"需要麦克风权限"，点击跳到 trime 设置页的语音输入页（`AppUtils` 已有启动 Activity 的封装）。
  **不做** trime-bibi 那种从 IME 拉起透明 Activity 直接申请的路子——那会遮挡当前 App，体验割裂，且在部分 ROM 上不稳。
- 录音仅在输入法可见且用户主动触发时进行；不常开麦克风；不落地音频文件。Android 12+ 的麦克风指示器会在录音时亮起，这是好事，符合"可自证"。

### 8.2 `INTERNET`（本方案最敏感的变更）

trime 至今**没有网络权限**——对一个输入法而言，这是重要的信任资产。加上 `INTERNET` 会在应用商店与用户的权限列表里显式可见。

对策（缺一不可）：

1. **默认关闭**：AI 校对开关默认 `false`，不开则代码路径上没有任何 socket。
2. **单一出网点**：全项目只有 `LlmCorrector` 一个类发起网络请求，加一条 CI 检查（grep `HttpURLConnection` / `openConnection` 的出现位置白名单），让"是否偷偷联网"可被机械验证。
3. **模型下载**也走网络，但只在用户点击下载时发生，且 URL 可见可改。
4. 在设置页 AI 校对分组顶部、README、CHANGELOG 里都明写"开启后 ASR 文本会发送到你配置的服务端"。
5. **可选（推荐给上游 PR 时考虑）**：把语音功能做成独立的 product flavor（`trimeVoice`），让不需要的用户拿到一个既无 `INTERNET` 也无 sherpa .so 的干净包。成本是 CI 出包数量翻倍（4 ABI × 2 flavor），**v1 不做，记入 §12 待决**。

### 8.3 API Key 的存储

macOS 版把 key 存进 Keychain。Android 上对应物是 `EncryptedSharedPreferences`（androidx.security-crypto），但那个库长期处于 alpha/维护尴尬期，且会引入新依赖。

**决策**：v1 存在普通 SharedPreferences 里（与 trime 其他配置同级），但：
- 设置页里掩码显示；
- 明确告知这是"本机明文存储"，建议使用有额度限制的专用 key；
- 不写进任何日志（`LlmCorrector` 的日志里 key 一律替换为 `***`）。
> 理由：SharedPreferences 位于应用私有目录，未 root 的设备上其他 App 读不到；引入一个 alpha 依赖换取的边际安全收益，不匹配"精简够用"。此决策记入 §12，若将来 androidx.security 稳定可再改。

---

## 9. 失败与降级矩阵

| 失败点 | 用户看到 | 系统行为 |
| --- | --- | --- |
| 未下载模型 | 浮层："未安装语音模型" + 「去下载」 | 不录音，直接结束会话 |
| 模型损坏 / sherpa 构造失败 | 浮层：具体原因 + 「去设置」 | 引擎置 `Failed`，不重试 |
| 无麦克风权限 | 浮层："需要麦克风权限" + 「去授权」 | 同上 |
| `AudioRecord` 构造失败 / 被别的 App 占用 | Toast + 浮层消失 | 回 `Idle` |
| 录音 < 300ms | 无提示 | 静默丢弃 |
| 录音达上限 | Toast "已达最长录音时长" | **正常收尾并上屏** |
| 识别结果为空/只有标点 | 轻提示"没有听清" | 不上屏 |
| 推理抛异常 | Toast + 日志 | 回 `Idle`，引擎标记为需重建 |
| 推理超时（30s 看门狗） | Toast | 回 `Idle` |
| LLM 未配置完整（缺 base_url/key/model） | 设置页提前拦截；运行时静默跳过校对 | **用 ASR 原文上屏** |
| LLM 网络失败 / 超时 / 鉴权失败 / 解析失败 / `finish_reason=length` | 浮层一闪的警告 | **用 ASR 原文上屏** |
| 会话进行中输入框失焦 / 键盘隐藏 / 切走 App | 浮层消失 | 丢弃结果，不上屏 |
| 目标是密码输入框 | Toast "密码框不支持语音输入" | 不启动会话 |
| 进程被系统回收 | —— | 下次使用重新加载模型（这是 §4.2 风险的兜底） |

---

## 10. 测试策略

trime 已配好 JUnit5 + kotest（`testOptions.unitTests.all { useJUnitPlatform() }`）。

| 测试 | 内容 | 依赖 |
| --- | --- | --- |
| `TextPostProcessorTest` | 每条清洗规则的正反例；孤立句号必须返回空串；CJK 空格规则不能误伤 `AI Coding` | 无 |
| `LlmEndpointTest` | base_url 解析：scheme 白名单、明文 http 限回环/私网、`/chat/completions` 去重、非法输入 | 无 |
| `LlmCorrectorTest` | **失败兜底是重点**：网络异常/超时/401/畸形 JSON/空 choices/`finish_reason=length` → 一律返回原文；标签回显被剥离；`max_tokens` 计算 | JDK 自带 `com.sun.net.httpserver` 起本地桩（不引 MockWebServer） |
| `VoiceSessionTest` | 状态机：短录音丢弃、上限收尾走正常路径、识别中重复触发被拒、取消后迟到结果被丢弃、失败必回 Idle | 假引擎（`SenseVoiceEngine` 的接口只有一个纯函数，极易 fake） |
| `VoiceModelIntegrityTest` | 文件缺失/大小异常/sha256 不匹配的分类判定 | 临时目录 |
| `VoiceModelArchiveTest` | tar.bz2 / zip 解压出的目录结构与路径穿越防护（`../` 必须拒绝） | 小体积夹具 |
| 端到端识别（手工/仪器测试） | 固定 wav → 与 BiBi 同配置的输出逐字比对（G2 基线） | 需真机 + 模型，缺失时跳过 |

**必须真机走查（无法单测）**：长按手势与浮层的交互、切 App/失焦/旋转屏幕时的清理、麦克风权限的各种拒绝路径、内存与被杀概率、识别延迟。

---

## 11. 实施阶段

| 阶段 | 内容 | 产出/验收 |
| --- | --- | --- |
| **P0 骨架** | build 接入 sherpa-onnx（下载插件 + excludes）、manifest 权限、`VoicePrefs` + 设置页 + 路由 | 装上 APK，设置页可见，体积增量有实测数字 |
| **P1 模型链路** | `VoiceModelManager` + 下载 Worker + 导入 + 校验 + 删除 UI | 能把模型装好，断网/中断/重试/换 URL 都正确 |
| **P2 识别链路** | `SenseVoiceEngine` + `AudioRecorder` + `TextPostProcessor` + `VoiceSession`，**先用设置页里的"录音测试"按钮驱动**，不碰键盘 | 在设置页里能录一段并看到识别文本；单测齐 |
| **P3 键盘接入** | `VoiceInputDelegate` + 浮层 + `KeyView` 长按 + `CommonKeyboardActionListener` 分支 + 工具栏按钮 + 权限引导 | 真机上按住语音键能上屏；§9 的失败矩阵逐条走一遍 |
| **P4 内存与性能实测** | 测常驻内存、加载耗时、各时长音频的识别耗时、连续使用 30 分钟后的进程存活率 | **决定是否需要 §4.2 的子进程退路**；数字写回本文 |
| **P5 AI 校对** | `LlmCorrector` + 提示词资源 + 设置项 + 测试校对按钮 | 校对可用；断网/超时/错配置一律不丢文本 |
| **P6 收尾** | README / CHANGELOG / 关于页署名 / aboutlibraries 许可证 / 主题文档补 `VOICE_ASSIST` 键位示例 | 可发布 |

P2 之所以先用"设置页里的录音测试"驱动，是因为它把**识别链路**和**键盘交互**这两块风险解耦：识别不对就在设置页里调，不用每次都去按键盘。这也顺手留下了一个长期有用的排障工具。

---

## 12. 风险与待决

| # | 风险 | 影响 | 对策 |
| --- | --- | --- | --- |
| R1 | **输入法进程内存变重被 LMK 回收** | 键盘"闪退"重建，rime 重新初始化 | 空闲卸载 + 录音上限 + numThreads=2；P4 实测后决定是否启用 `:voice` 子进程退路（接口已为此预留） |
| R2 | 首次使用要下 228MB | 劝退 | 设置页明确标注体积；支持断点续传、后台下载、本地导入、换源 |
| R3 | GitHub 在部分网络环境不可达 | 装不上模型 | 下载地址可改 + 本地导入；README 给出手动放置路径 |
| R4 | 加 `INTERNET` 权限损害 trime 的信任形象 | 用户/上游反弹 | §8.2 五条；flavor 方案待决 |
| R5 | sherpa-onnx AAR 与 trime 的 CMake 产物共存出问题（STL、页面大小） | 编译或运行崩 | 已核对：AAR 内为预编译 .so，与 trime 的 `ANDROID_STL=c++_static` 不冲突（不共享 C++ 符号边界）；16KB 对齐已验证；P0 首个真机跑通即可证伪 |
| R6 | R8/ProGuard 裁掉 sherpa 的 Kotlin 类（JNI 反向引用字段） | release 包崩、debug 包正常 | `proguard-rules.pro` 加 `-keep class com.k2fsa.sherpa.onnx.** { *; }`；**P0 必须打一个 release 包验证**，不能只测 debug |
| R7 | 移动端识别延迟不可接受 | 体验差 | P4 量化；退路是 BiBi 的分段渐进解码（§4.4） |
| R8 | 与上游 develop 的长期合流成本 | 维护负担 | §6.3 的 8 处接缝清单是硬约束，任何新增改动都要先问"能不能挪进 voice 包" |

**待决（需要你拍板）**：

- **D-a** 是否要做 product flavor 把语音功能（连带 `INTERNET` 与 26MB .so）隔离成独立包？（我的建议：v1 不做，先单包跑通；若你打算给上游提 PR，再补 flavor）
- **D-b** 模型版本用 `2024-07-17`（与 BiBi 同版，可直接做 G2 逐字比对）还是 `2025-09-09`（更新）？（我的建议：v1 用 2024-07-17 拿到比对基线，P5 再评估升级）
- **D-c** API Key 是否值得引入 `androidx.security-crypto`？（我的建议：不引，理由见 §8.3）
- **D-d** 「按住说话」时手指上滑取消，阈值与提示文案要不要做？（我的建议：做，这是国内用户的肌肉记忆）

---

## 13. 决策记录

| ID | 决策 | 一句话理由 |
| --- | --- | --- |
| D1 | 用 sherpa-onnx 预编译 AAR，构建时下载而非提交进 git | Android 有官方预编译产物，自研 ONNX 管线省不下 5MB 却要背 900 行数值代码 |
| D2 | 跑在 trime 主进程，配空闲卸载；子进程作为已预留接口的退路 | 用户的痛点就是"另一个 App 的进程被杀"，同进程根治它 |
| D3 | 模型不入包，设置页显式下载/导入，可换源 | 228MB 不能进 APK，也不能静默下载 |
| D4 | v1 不做流式预览 | 手机上滑窗预览的发热与卡顿代价 > 收益 |
| D5 | 用 `HttpURLConnection`，不引 OkHttp；不做 SSE | 一个可选功能不值得给输入法加网络库 |
| D6 | 校对失败一律用 ASR 原文上屏 | 绝不因为增强功能失败而丢掉用户说的话 |
| D7 | 录音 UI 用 InputView 上的穿透浮层，不用 BoardWindow | BoardWindow 切换会给按住中的按键发 `ACTION_CANCEL`，长按当场断掉 |
| D8 | 复用既有 `KEYCODE_VOICE_ASSIST` 而不是发明新 keycode | 主题 YAML 无需新语法，且天然兼容"没开语音功能就切系统语音输入法"的旧行为 |
| D9 | 校对提示词从 voice-typer 原样搬运 | 已打磨过的资产，重写只会退化 |
| D10 | 语音代码全部收进 `ime/voice` + `data/voice`，上游文件改动限制在 8 处 | 这是一个要长期跟随 upstream 的 fork |

---

## 附：本文中"已实测"与"待实测"的区分

**已在本机核对/实测**：sherpa-onnx AAR 的各 ABI .so 体积与 16KB 对齐、AAR 的 minSdk、BiBi 的 sherpa 调用方式与参数、BiBi 的录音参数、trime 的 DI/设置/权限/ABI 分包/keycode 通路、trime-bibi 的浮层与长按实现、SenseVoice 模型文件名与体积（来自 sherpa-onnx 官方文档）。

**尚未实测，P0/P4 必须补**：APK 压缩后增量、Android 上的常驻内存、模型加载耗时、各时长的识别耗时、release 包在 R8 下是否正常、真机上输入法进程的存活率。这些数字回填到 §4.1 / §4.2 / §11 之后，本文才算完整。
