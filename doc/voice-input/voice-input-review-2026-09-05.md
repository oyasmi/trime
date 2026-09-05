# 语音输入设计与实现审查（2026-09-05）

审查基线：`b143b236`。检查最近 12 小时的 12 个提交、`doc/voice-input/` 设计与实施方案，以及录音、识别、校对、模型管理、输入法生命周期的完整调用链。扩展检查了文件提供器、数据同步、剪贴板和构建接入。

总体判断：本地识别、松手后一次解码、AI 默认关闭并失败回退、按需加载模型，这几个方向值得保留。主要问题集中在执行线程、资源所有权和故障边界；短句在真机上跑通，尚不足以验证这些边界。应先完成下列 P1 修复，再用数据决定是否分段识别、调整保活时间或启用同 APK 子进程。

这里 P1 表示建议在扩大使用范围前修复，P2 表示随后完善。性能试验单独列出，不把尚未量化的风险算成已发生的故障。此次只交付审查和方案，没有修改生产代码。

## 1. P1：模型加载、推理和卸载运行在输入法主线程

证据：[VoiceInputDelegate.kt](/Users/oyasmi/projects/trime/app/src/main/java/com/osfans/trime/ime/voice/VoiceInputDelegate.kt:153) 把 `service.lifecycleScope` 交给状态机；[VoiceSession.kt](/Users/oyasmi/projects/trime/app/src/main/java/com/osfans/trime/ime/voice/VoiceSession.kt:174) 直接调用 `engine.decode()`；[SenseVoiceEngine.kt](/Users/oyasmi/projects/trime/app/src/main/java/com/osfans/trime/data/voice/SenseVoiceEngine.kt:96) 只有 `Mutex.withLock`，没有切换 dispatcher。模型构造、`acceptWaveform`、同步 JNI `decode` 和 `release` 都在调用线程执行。设置里的录音测试也走同一路径。

`LifecycleCoroutineScope` 使用 `Dispatchers.Main.immediate`，`suspend` 和互斥锁不会自动转移线程。[Android 定义](https://developer.android.com/reference/androidx/lifecycle/LifecycleCoroutineScope)。冷加载、较长语音和低端设备会让键盘绘制、触摸、生命周期回调一起等待，严重时可能触发 ANR。设置中的 `numThreads=2` 控制推理内部线程数，不能解决调用线程被阻塞的问题。

当前 30 秒 `withTimeoutOrNull` 也不是计算硬上限。协程取消需要被执行代码配合；同步 native 调用不会因此自动停止。[Kotlin 超时说明](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/with-timeout.html)。临时探针中，250ms 的同步假引擎把同线程任务延后了 256ms；50ms 超时包裹 250ms 同步工作，实际等待 251ms。这是线程和取消语义验证，不是真实模型的耗时测量。

**方案：**把整个引擎操作封装成可安全从主线程调用的接口：加载、解码、卸载统一送到后台工作线程，继续用互斥锁保护 native 对象；状态变化与提交文本回主线程。不要只把录音移到 IO，也不要把整个状态机直接挪到 Default 而让 UI 回调越线程。

超时需要区分“用户停止等待”和“native 工作已停止”。引擎拥有独立、受管理的任务；UI 超时后作废结果，native 尚未返回时保持该引擎占用，禁止并发解码和提前 `release()`。如果确实需要硬中止计算，再验证可用的 native 取消接口或使用同 APK 的绑定子进程。仅添加 `withContext(Default)` 仍不提供硬中止保证。

**验收：**冷加载以及 5/30/60 秒音频识别期间，主线程能持续处理按键、收键盘和动画；慢引擎超时后 UI 能退出等待；旧任务返回不上屏、不与新任务并发访问 recognizer。

## 2. P1：会话、键盘视图和引擎的生命周期没有闭合

证据：[VoiceInputDelegate.kt](/Users/oyasmi/projects/trime/app/src/main/java/com/osfans/trime/ime/voice/VoiceInputDelegate.kt:69) 只在新输入广播时取消；注释明确因为“上游文件预算”没有接 `onFinishInputView`。[服务的结束回调](/Users/oyasmi/projects/trime/app/src/main/java/com/osfans/trime/ime/core/TrimeInputMethodService.kt:570) 和 [InputView 的 detach](/Users/oyasmi/projects/trime/app/src/main/java/com/osfans/trime/ime/core/InputView.kt:391) 都没有处理 voice。会话和永久空闲循环却绑定在服务生命周期上。

这有两个独立后果。第一，点按模式启动后收起键盘、录音松手后等待 AI 时隐藏键盘，不一定发生任何按键 `ACTION_CANCEL`，服务也不会随键盘隐藏销毁，录音或校对可能继续。第二，主题、配色或设置导致视图重建后，旧空闲循环仍持有旧 delegate，通过 DI 引用旧视图；新视图又能创建另一套引擎和循环。默认保活期间可能同时保留多套模型；即使模型稍后卸载，旧循环及其视图引用仍在；`keepAlive=-1` 时风险更大。

最终 [commit()](/Users/oyasmi/projects/trime/app/src/main/java/com/osfans/trime/ime/voice/VoiceInputDelegate.kt:210) 直接使用服务当时的 `currentInputConnection`，没有设计中承诺的 sessionId/editor generation 校验。普通切换到另一个可见输入框时现有广播能取消一部分旧任务，但不能覆盖旧 delegate 已脱离广播、硬件键盘路径或隐藏后回调等情况。

**方案：**允许为正确性增加服务接入点，删除“≤8 文件/≤80 行”作为硬约束的做法。服务统一产生输入会话代号；在 `onStartInput`、`onFinishInputView`、`onFinishInput`、视图替换/销毁时使旧会话失效。提交时在主线程核验代号、活动 editor 和当前连接，隐藏后即使连接对象仍存在也不上屏。

把 UI 会话 scope 与 InputView 的有效期绑定，并提供显式 `dispose()`。引擎可以由服务级管理器统一持有和租用，避免每个视图各自保活；服务销毁、关闭总开关、模型更换/删除时有明确的停止与后台释放流程。释放必须等待 native 使用结束。振幅事件也携带会话代号，防止旧录音的排队回调更新新 UI。

**验收：**点按录音后收键盘；AI 等待时按返回；切到密码框；语音结束前重建主题；连续重建 20 次后检查旧 InputView 和引擎数量。所有取消场景不再上屏，旧视图可回收，总开关关闭后任务与保活停止。

## 3. P1：录音异常会逃出状态机，停止标志也存在竞态

证据：[VoiceSession.kt](/Users/oyasmi/projects/trime/app/src/main/java/com/osfans/trime/ime/voice/VoiceSession.kt:103) 在顶层 `launch` 中只为录音写了释放音频焦点的 `finally`，没有异常到 Error/Idle 的完整收尾；`audioFocus.acquire()` 甚至在这个 try 之外。[AudioRecorder.kt](/Users/oyasmi/projects/trime/app/src/main/java/com/osfans/trime/ime/voice/AudioRecorder.kt:90) 的 `startRecording()`、读取等异常会向上抛。临时探针确认：录音抛 `IllegalStateException` 后，异常到达 scope 的异常处理器，`isBusy()` 仍为 true。在 Android 未安装相应处理器的顶层 launch 中，这还可能使输入法进程崩溃。

另外，[record() 进入 IO 后才把 stopRequested 清零](/Users/oyasmi/projects/trime/app/src/main/java/com/osfans/trime/ime/voice/AudioRecorder.kt:60)。如果长按触发后 IO 调度迟迟未运行，用户已经松手并 `requestStop()`，随后录音任务会把停止请求覆盖，可能录到时长上限。`cancel()` 立即把会话标为空闲但不等旧 recorder 退出；快速重开又复用同一个停止标志，可能影响尚在阻塞 read 中的旧录音。录音循环也没有检查 coroutine cancellation。

**方案：**一次会话独立拥有 recorder/停止令牌，在入队之前建立状态，不能由异步 worker 重置已经发出的停止。新会话启动前确认旧音频资源已退出，或由录音管理器串行交接。录音循环检查取消并设计可解除阻塞读取的停止协议，不仅设置 Boolean。

状态机增加覆盖整个 acquire → record → decode → correct 的异常与 finally 收尾；明确重新抛出 `CancellationException`，普通录音错误转为提示并回 Idle。清理要按会话代号执行，防止旧 finally 清掉新会话。`AudioRecord` 创建后的缓冲分配也应纳入资源保护区；读取错误不能与正常松手无差别处理。不要用吞掉所有 Throwable 的方式假装恢复 OOM 或 native 崩溃。

**验收：**注入焦点失败、startRecording 异常、读错误、IO 延迟后快速松手、取消后立刻重开。检查麦克风及时关闭、焦点恢复、不会出现两个录音、状态不会永久 busy。

## 4. P1：Android 5–7 的语音路径调用了 API 26 才有的类

项目声明 `minSdk=21`，但 [AudioFocusGuard.kt](/Users/oyasmi/projects/trime/app/src/main/java/com/osfans/trime/ime/voice/AudioFocusGuard.kt:27) 无版本判断地构造 `AudioFocusRequest.Builder`，该类从 API 26 才存在。[Android API 文档](https://developer.android.com/reference/android/media/AudioFocusRequest)。这会让 API 21–25 设备在进入相关语音路径时发生类/方法不可用错误，功能开关默认关闭无法保证开启后可用。

**方案：**API 26+ 使用新接口；21–25 使用旧 `requestAudioFocus(listener, streamType, durationHint)` 和对应 abandon，或采用适合当前依赖的兼容封装。音频焦点申请失败应可控降级，不能因此使录音状态机崩溃。加上 NewApi 检查和旧系统运行覆盖；单次新手机测试无法验证 minSdk 承诺。

## 5. P1：自动模型下载的完整性校验只报警，随后仍安装

证据：[VoiceModelDownloadWorker.kt](/Users/oyasmi/projects/trime/app/src/main/java/com/osfans/trime/data/voice/VoiceModelDownloadWorker.kt:103) 对 SHA-256 不匹配的归档仍然解压。下载源包括自动回退的第三方 GitHub 代理；续传跨源共用 `.part`，没有用 ETag/If-Range 和 Content-Range 核实是否属于相同对象。`expectedBytes` 主要用于进度/恢复判断，不能证明内容相同。[isReady 的实际判断](/Users/oyasmi/projects/trime/app/src/main/java/com/osfans/trime/data/voice/VoiceModelManager.kt:50) 也只是两个文件存在且非空。

因此代码虽然保存了固定摘要，却没有形成信任边界：下载源返回内容不同、旧续传片段拼接错误、错误包包含同名文件，均可能进入 native 模型加载。这里不需要假设存在某个 ONNX 漏洞；单是装错模型、词表和损坏文件，就足以让输入法反复无法识别。

**方案：**内置官方模型及其镜像必须严格匹配固定 hash，失败时隔离/删除该源的部分文件，再从干净文件尝试下一源。跨源续传要核对实体标识与 Content-Range；无法确认就不拼接。用户自定义归档/重新打包的兼容需求应单独处理，可采用解压后两文件的内容摘要或显式自定义模型模式，不能放宽默认自动下载路径。对网络字节数、解压后尺寸和剩余磁盘空间设合理界限。

**验收：**同大小但不同内容、错误 206 range、替换镜像对象、错误 hash、断网续传。任何不匹配都不发布 Ready，不传给 JNI；有效旧模型仍可用。

## 6. P2：模型安装不是两文件事务，失败后可留下混合版本

证据：[VoiceModelArchive.extract()](/Users/oyasmi/projects/trime/app/src/main/java/com/osfans/trime/data/voice/VoiceModelArchive.kt:49) 每解出一个文件就立即 rename 到正式目录，最后才检查是否缺少另一文件。临时探针用“旧模型+旧词表”目录导入只含新模型的归档：函数确实失败，但目录已经变成“新模型+旧词表”，且两个文件均非空，会通过现有 Ready 判断。新安装在后续归档条目损坏时，也可能出现失败与已发布文件不一致的情况。

**方案：**使用每次安装独立的 staging 目录；两文件完整、摘要/格式检查完成后，发布新的不可变版本目录或更新活动版本指针。失败只清 staging，保留旧安装。下载、导入、删除、引擎加载共用安装协调机制；引擎配置缓存应包含安装版本标识，不能只比较模型枚举。特别要避免取消并立即 REPLACE 时旧 worker 清理掉新 worker 共用的 `.part`。

**验收：**在解压第一个文件后取消、缺词表、末尾损坏、磁盘满、取消后立即重试、旧引擎保活时重装。读者永远只看到完整旧版本或完整新版本。

## 7. P2：AI 校对没有总时限，也没有及时停止底层请求

证据：[LlmCorrector.kt](/Users/oyasmi/projects/trime/app/src/main/java/com/osfans/trime/data/voice/llm/LlmCorrector.kt:139) 分别设置连接和读取超时，随后 `readText()` 读完整响应；未建立端到端 deadline 或取消时断开连接的机制。读取超时限制的是一次等待，不限制整个请求持续多久。临时本地 HTTP 服务每 400ms 发一点数据：配置 1 秒超时，校对仍在 2022ms 后成功；请求开始后取消，`cancelAndJoin()` 又等待 1821ms 才结束。

这意味着“5 秒失败回原文”并不成立：慢速响应可以持续占住 Correcting，用户也没有明确的“跳过校对，上屏原文”入口。键盘取消后，底层连接还可能占 IO 线程和无线网络资源。不能据此声称所有取消都会错上屏：`withContext(IO)` 的取消通常会阻止回到调用方，但它没有停止正在进行的网络工作。

**方案：**为连接、发送、接收和解析设置统一总 deadline；使用明确支持 call cancellation 的客户端，或把 HttpURLConnection 的流关闭/连接断开与取消绑定并实测。所有路径 finally 清理；正常与错误响应都限制体积。增加“使用原文”动作，只有当前有效 editor 才可提交；隐藏键盘的取消应丢弃，不要自动提交到其他输入框。保留当前无重试的交互策略，避免重复耗时与计费。

顺带检查：非 2xx 的错误响应前 200 字符会拼进异常并由 Timber 记录，第三方服务可能回显正文或凭据。这条日志应改成状态码与脱敏错误类别，不记录任意响应正文。端点接受私网 HTTP，但合并后的 Android manifest 没有相应明文网络配置；JVM localhost 测试不等于 Android 上自建 HTTP 服务可用，应另做设备验收。[Android 明文网络策略](https://developer.android.com/privacy-and-security/security-config)。

## 8. P1，已有问题：文件提供器的目录授权边界可以被路径绕过

这不是今天语音提交引入的问题；文件最近修改记录为 `cbc4142f`（2026-01-10）。证据：[RimeDataProvider.kt](/Users/oyasmi/projects/trime/app/src/main/java/com/osfans/trime/provider/RimeDataProvider.kt:72) 直接 `File(docIdPrefix, docId)`，而 [isChildDocument()](/Users/oyasmi/projects/trime/app/src/main/java/com/osfans/trime/provider/RimeDataProvider.kt:172) 只检查 `documentId.startsWith(parentDocumentId)`。create/rename 的 displayName 也未统一限制为单个文件名。

例如已授权 `files/rime` 时，`files/rime/../voice/sensevoice/model.int8.onnx` 满足字符串前缀判断，但实际位于相邻的 voice 目录；授权 `files/rime` 也会把 `files/rime-other` 误判为子项。临时路径演算验证了前者。Android 的 DocumentsProvider 在树 URI 访问时调用提供器的 `isChildDocument()` 做后代校验，因此不能依赖 manifest 的 MANAGE_DOCUMENTS 权限弥补这个实现。[AOSP 授权检查](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/provider/DocumentsProvider.java)。

**影响有明确前提：**调用方需要已经得到相关 SAF 树授权或具备管理文档权限；不是任意未授权 App 都能读取所有文件。能否到达其他存储位置还受输入法 UID、Android 存储和 SELinux 权限限制。本次没有进行跨应用真机攻击验证。即使仅限于授权子树之外的应用外部文件，越界读写或删除模型、词典也值得优先修复。

**方案：**统一解析并规范化 documentId，先保证落在允许的根内；isChildDocument 使用规范化路径按目录边界验证父子关系，不能用裸字符串前缀。create/rename 拒绝绝对路径、`.`、`..` 和路径分隔符；全部查询、打开、复制、移动、删除入口共用校验。需要对符号链接也按最终路径限制。验收覆盖真实获授权子树的跨应用读取/写入拒绝、相似前缀和路径遍历，同时保证合法后代仍可访问。

## 性能与耗电：值得实施或测量的方向

| 方向 | 当前事实与建议 | 如何决定是否采用 |
| --- | --- | --- |
| 录音期间预加载 | 设计承诺并行预加载，但 `preload()` 没有任何调用点。当前冷启动用户松手后才支付加载成本。先修线程和生命周期，再在真实音频开始、确认非误触后按需预加载。 | 分别记录冷加载、推理和松手到上屏耗时；比较短句 P50/P95，以及取消录音造成的无效加载次数。不要移到 IME 冷启动或每次显示键盘。 |
| 事件驱动卸载 | 现在首次使用后永久每 30 秒轮询，即使引擎已经卸载或总开关关闭。最后使用时间在加载/解码开始时更新，长录音期间也没有活跃租约保护。 | 改为最后一次使用结束后安排一次卸载任务；新会话取消旧任务，录音/推理期间不卸载；关功能、释放视图和内存压力时有显式策略。先处理重复引擎，比调计时器频率收益大。 |
| 保活时间 | 五分钟是默认取舍，不是已经证实最省电的值。模型驻留主要是内存成本，释放后频繁重载也有 CPU/I/O 成本。 | 比较 0、1、5 分钟，在连续聊天和偶发听写两种节奏下的 PSS、重载次数、总能耗。低内存设备可采用更短保活。普通 delay 轮询不等于唤醒深度休眠，不应夸称为每 30 秒硬件唤醒。 |
| ONNX 线程自旋 | 当前 `provider="cpu"` 采用默认会话配置。ORT 的自旋可以换取延迟，但增加 CPU/能耗，属于可测的取舍。 | 对比 1/2/4 线程以及关闭 intra/inter spinning 的耗时和能耗；不要仅凭核数把线程调高。自旋是有限等待行为，不意味着保活五分钟持续满核。 |
| native 内存分配 | 长音频的中间张量与 CPU arena 可能比音频数组大得多，模型卸载后的进程 RSS 也不能只靠 JVM GC 推断。 | 在模型加载、最长音频推理峰值、释放后测 PSS/native heap；按需试验 CPU arena、memory pattern、prepacking 的配置，不能未经测试全部关闭。 |
| 长音频边界 | 用户可设 300 秒，仍整体解码一次。录音数组预分配 19.2 MB（300×16000×4 字节），满长返回再复制一份，尚不含 JNI、特征和模型张量。默认 60 秒预分配 3.84 MB。 | 在通过峰值内存/延迟测试前，将更长录音作为未验证能力限制；需要长听写时，采用静音边界分段，每段只识别一次，有界累积结果。分段会影响断句与文本，必须另设准确率回归，不能继续承诺与整段解码逐字相同。 |
| 音频缓冲与静音 | 上限为 300 秒时，说两秒也先分配 19.2 MB。 | 后续可用分块 PCM16 累积，只在解码需要时转换有效区间；无声录音可结合稳健 VAD/质量门控跳过推理。仅靠固定 RMS 阈值可能丢失轻声，不应直接上线。 |

ONNX 参数不是只能通过重写推理链路调整：核对了实际依赖的 sherpa-onnx v1.13.7 上游实现，CPU provider 支持配置文件，并将 `SessionConfig.*` 转交 ORT；本地 AAR 的 arm64 库也包含相关配置标记。可以用应用私有的配置文件做 A/B 实验，例如 `provider="cpu:/.../voice-ort.conf"`，文件内设置 `SessionConfig.session.intra_op.allow_spinning=0`。实际生效与收益仍需验证，特别是选项支持程度取决于 AAR 内的 ORT 版本。[sherpa 会话配置](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.7/sherpa-onnx/csrc/session.cc)，[ORT 线程取舍](https://onnxruntime.ai/docs/performance/tune-performance/threading.html)。

波形已经限制为约 20fps、14 根柱并由振幅驱动重绘，优先级明显低于重复模型、后台录音和无界请求。不建议先把精力用在颜色、柱宽或零碎对象分配上。

## 设计上应修订的约束

“同进程”解决外部 App 冷启动依赖，但不能保证输入法不被回收，也不能隔离 native 故障。先完成后台串行执行、有界工作量和资源回收；如果这些修复后仍达不到内存/故障隔离要求，再使用同 APK、按需绑定的 `:voice` 服务。不要直接恢复原先另一个 App 的常驻方案。

把“全流程离线”“零线程/零内存”等绝对表述改成可测试条件：AI 关闭时音频和识别文本不出网；用户触发模型下载可联网；功能关闭无 native 引擎、无录音/推理/保活任务。目前状态行会随 InputView 创建，即使功能关闭也不是字面上的零分配，这个小 UI 成本本身不值得大改。

此外，“API key 仅本机明文存储”需要与备份策略一致。当前 manifest 开启 `allowBackup`，未配置备份排除，API key 位于普通 SharedPreferences。建议将密钥单独存放并排除备份，或用 Android Keystore 加密；不必为了做到这点依赖特定 EncryptedSharedPreferences 库。是否实际进入云端或设备迁移备份取决于设备与备份机制，不能从当前代码宣称绝不会离开本机。[Android 自动备份说明](https://developer.android.com/identity/data/autobackup)。

## 验证记录与执行顺序

用本机 ARM JDK 21 运行 `:app:testDebugUnitTest`，原有 **99 项全部通过**，其中语音相关 48 项。另行执行 **5 项临时故障探针**，与原有测试合计 104 项通过。探针断言的是上述缺陷确实存在，不代表已经修复。探针源码与输出保存在 [review-evidence-2026-09-05](/Users/oyasmi/projects/trime/doc/voice-input/review-evidence-2026-09-05/VoiceReviewProbeTest.kt)，未纳入日常测试源码目录。

复现时将该探针临时复制到 `app/src/test/java/com/osfans/trime/ime/voice/VoiceReviewProbeTest.kt`，用 `rtk proxy env JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew :app:testDebugUnitTest --console=plain` 执行，完成后移除临时复制件。保留的 [XML 输出](/Users/oyasmi/projects/trime/doc/voice-input/review-evidence-2026-09-05/probe-results.xml) 包含五项探针与具体耗时。这里的本地 HTTP 服务只处理合成文本和空 API key。

当前 ADB 没有连接设备。本次没有测量真实模型的 Android 峰值内存、能耗、识别速度，没有重跑 release/旧 Android 系统，也没有把路径演算当作完整跨 App 渗透测试。JNI 行为核对了 v1.13.7 源码，但主线程探针使用的是同步假引擎。

建议分三批落地：

1. **保证输入法本体可靠：**后台引擎执行、会话/editor 代号、生命周期退出、录音错误和停止竞态、旧 Android 音频焦点兼容。文件提供器越界作为独立修复一并优先处理。
2. **保证外部工作可控：**模型严格完整性校验和事务发布、可取消且有总时限的校对、备份与日志隐私边界。
3. **用数据调优：**预加载、保活、ORT 线程与内存配置、长音频分段。每组使用相同音频与设备温度条件，分开统计加载/推理/校对耗时；用 Perfetto 观察主线程与 CPU，用 dumpsys meminfo 采样 PSS/native heap，设备支持时再结合电源轨或电量工具比较整组能耗。不能拿一次短任务的电池百分比变化推断省电效果。

真机矩阵至少包含一个 API 21–25 环境、一台较低内存手机和当前常用手机；覆盖短句连续输入、60 秒长句、取消重开、键盘隐藏、主题重建和慢速网络。先确保没有串会话上屏、后台录音、重复引擎和主线程阻塞，再讨论速度提升百分比。
