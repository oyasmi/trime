<!--
SPDX-FileCopyrightText: 2015 - 2026 Rime community

SPDX-License-Identifier: GPL-3.0-or-later
-->

# 同文输入法 · 本地语音输入版

这是 [**osfans/trime**](https://github.com/osfans/trime)（同文输入法，Android 上的 RIME 输入法）的一个 fork，在其基础上加入了**完全离线的语音输入**。

[![License: GPL v3](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Upstream](https://img.shields.io/badge/上游-osfans%2Ftrime-blue)](https://github.com/osfans/trime)
[![Powered by sherpa-onnx](https://img.shields.io/badge/语音识别-sherpa--onnx%20%2B%20SenseVoice-orange)](https://github.com/k2-fsa/sherpa-onnx)

[English](README.md) | 简体中文 | [繁體中文](README_tc.md)

## 与原项目的关系

同文输入法的一切——[RIME] 引擎、方案、主题、用户词典、整套输入体验——都来自上游项目，本 fork **没有改动**。做这个 fork 只为一件事：让你能对着同一个键盘说话打字，而**声音不出手机**。

| | |
| --- | --- |
| 原项目 | <https://github.com/osfans/trime> |
| 原项目 README（原样保留） | [README_upstream_sc.md](README_upstream_sc.md) · [English](README_upstream.md) · [繁體中文](README_upstream_tc.md) |
| 原项目文档与 Wiki | <https://github.com/osfans/trime/wiki> |
| 本 fork 跟随 | 上游 `develop` 分支 |

如果你只想要原版同文，请直接从 [F-Droid](https://f-droid.org/packages/com.osfans.trime) 或 [Google Play](https://play.google.com/store/apps/details?id=com.osfans.trime) 安装——只有当你需要语音输入时，这个 fork 才有意义。**关于 RIME 方案、主题、通用输入行为的问题请去上游反馈**；本仓库的 issue 请只用于语音输入相关问题。

这个 fork 有意做得很浅：新增代码几乎全部落在 `ime/voice/**` 与 `data/voice/**` 两个新包里，被改动的上游文件只有寥寥数个（键盘事件监听、输入视图、设置页导航、manifest）。正因如此，跟随上游 `develop` 变基的成本很低。

## 本项目新增了什么

### 本地语音输入（核心功能）

按住某个键、说话、松手，识别出的文字直接上屏到你正在输入的地方。识别全程在**本机**完成，底层是 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 运行 [SenseVoice-Small](https://github.com/FunAudioLLM/SenseVoice) 模型。不联网、不上传录音、不装第二个 App、没有后台服务、没有独立进程。

- **离线是结构性的。** 录音不出本机，也不落磁盘——它只在一次说话期间存在于内存里，随后被丢弃。在「智能校对」关闭（默认）的情况下，模型下载完成之后，本功能**不会发起任何网络请求**。
- **默认关闭。** 你不去设置里打开它，就不会下载任何东西，不起线程、不占内存。
- **空闲自动卸载。** 识别引擎按需加载，空闲一段时间后自动卸载（默认 5 分钟；`0` = 用完即卸，`-1` = 常驻），所以「开启但没在用」的语音功能几乎不产生常驻开销。
- **多语言。** 中文、英语、粤语、日语、韩语，或自动识别。可选的反向文本正则化（ITN）会输出标点与规范化数字。
- **反馈收在键盘内。** 录音状态、实时波形、以及「正在聆听 → 识别中 → 校对中」的过程，都画成键盘内的一条状态行，而不是盖住整屏的浮层——你始终看得见正在输入的那个 App。
- **防呆与兜底。** 过短的按压被判为误触而丢弃；单段录音有可配置的时长上限（默认 60 秒，最长 300 秒）并主动收尾；密码框拒绝语音输入；音频焦点会正确归还，媒体播放能恢复。

### 模型管理

- 提供两个 SenseVoice-Small int8 版本，均取自 sherpa-onnx 官方的 [`asr-models`](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) 发布：**2024-07-17**（会输出标点，默认）与 **2025-09-09**（加训粤语数据，但不输出标点）。
- 下载在后台进行，可跨应用重启续传，并实时显示进度；压缩包的 SHA-256 已内置校验。
- GitHub 的 release 资源在国内常常连不上，因此下载会自动回退到公共 GitHub 镜像；你也可以填自定义下载地址。
- 也支持**从本地文件导入模型**（`.tar.bz2` 或 `.zip`），以及删除已安装模型来释放磁盘空间。

### 可选的「智能校对」——默认关闭

识别出的文本可以再经过一个**你自己配置的 OpenAI 兼容接口**（服务地址、API 密钥、模型、温度、超时，以及可自定义的提示词）做一次修正，用来处理同音字、口头禅和标点。

- **默认关闭，而且这是本项目在听写链路上唯一的出网点。** 一旦开启，识别出的文本——不是录音——会被发送到*你所配置的*服务端。
- 任何失败、超时或截断都会**回退到原始识别文本**，校对永远不会把你说的话弄丢。
- 设置页里有「测试校对」按钮，把配置错误暴露在配置的时候，而不是听写到一半的时候。
- 一个需要直说的取舍：API 密钥保存在普通的应用偏好设置里，没有放进硬件密钥库。

### 顺带修的构建问题

- 为 rime-lua 固定 `_FILE_OFFSET_BITS=32`，使 32 位 Android ABI 恢复可编译。
- 发布版 APK 归档文件名带上版本号，方便管理本地构建产物。

## 语音输入上手

1. **开启**：设置 →「**语音输入**」→ 打开开关。
2. 在同一页面**下载识别模型**（约 160 MB），或导入你已有的模型。
3. 按提示**授予麦克风权限**。
4. **开始用**：默认主题下**长按空格键**即可，工具栏的麦克风按钮同样可用。
   按住说话、松手识别、**上滑取消**。更习惯点按？设置 → 语音输入 →「触发方式」→「点按切换」。

任意键位都能触发——在主题里把它的 `send` 绑定为 `VOICE_ASSIST`：

```yaml
space:
  click: space
  long_click: { send: VOICE_ASSIST }
```

详细绑定说明见 [`doc/Keyboard.md`](doc/Keyboard.md)。

> 语音输入**关闭**时，语音键的行为与上游完全一致：切换到系统语音输入法。

### 代价，如实说明

内置 sherpa-onnx 的推理运行时，会让安装后的应用体积增加大约 **arm64-v8a 25 MB**、**armeabi-v7a 18 MB**（未压缩的原生库；下载增量小于此数）。识别模型**不随安装包分发**，就是那个另外下载的约 160 MB 文件，存放在应用的外部文件目录里。

### 设计文档

这个功能是先设计后实现的，文档都在仓库里：

- [`doc/voice-input/voice-input-design.md`](doc/voice-input/voice-input-design.md) —— 目标、非目标、技术选型、隐私模型
- [`doc/voice-input/voice-input-implementation-plan.md`](doc/voice-input/voice-input-implementation-plan.md) —— 实施任务拆解
- [`doc/voice-input/voice-input-feedback-design.md`](doc/voice-input/voice-input-feedback-design.md) —— 键盘内状态行的重设计
- [`doc/voice-input/voice-input-review-2026-09-05.md`](doc/voice-input/voice-input-review-2026-09-05.md) —— 设计与实现复核

## 构建

与上游相同（环境要求与故障排除见 [README_upstream_sc.md](README_upstream_sc.md)），只是仓库地址换成本项目：

```sh
git clone git@github.com:oyasmi/trime.git
cd trime
git submodule update --init --recursive --filter=blob:none

make debug      # Linux/macOS；Windows 上执行 .\gradlew assembleDebug
```

sherpa-onnx 的 AAR **没有**提交进 git。一个 Gradle 插件（`build-logic/.../SherpaOnnxPlugin.kt`）会在首次构建时下载指定版本、校验 SHA-256 并解压到 `app/libs/`——和上游处理 OpenCC 数据的做法一致。

## 鸣谢

- **[osfans/trime](https://github.com/osfans/trime)** 及其全体贡献者——本 fork 是他们的成果加上一个功能而已。完整鸣谢见 [README_upstream_sc.md](README_upstream_sc.md#鸣谢)。
- **[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)**（[k2-fsa](https://github.com/k2-fsa)）——让离线听写成为可能的端侧语音识别运行时，本项目使用的预编译 Android AAR 与模型发布也都来自它。**没有它就没有这个功能。**
- **[SenseVoice](https://github.com/FunAudioLLM/SenseVoice)**（[FunAudioLLM](https://github.com/FunAudioLLM)）——识别模型。
- **[RIME]** 与 **[OpenCC]** ——同文输入法自身的基石。

## 第三方库

[README_upstream_sc.md](README_upstream_sc.md#第三方库) 中列出的全部内容，另加：

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)（Apache License 2.0）——端侧语音识别运行时，含其内置的 [ONNX Runtime](https://github.com/microsoft/onnxruntime)（MIT License）
- [SenseVoice-Small](https://github.com/FunAudioLLM/SenseVoice) —— 模型权重，适用 [FunASR Model Open Source License Agreement](https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE)
- [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/)（Apache License 2.0）——模型压缩包解压

## 许可

GPL-3.0-or-later，与上游一致。参见 [LICENSE](LICENSE) 与 [PRIVACY.md](PRIVACY.md)。

[RIME]: https://rime.im
[OpenCC]: https://github.com/BYVoid/OpenCC
