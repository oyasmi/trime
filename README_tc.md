<!--
SPDX-FileCopyrightText: 2015 - 2026 Rime community

SPDX-License-Identifier: GPL-3.0-or-later
-->

# 同文輸入法 · 本地語音輸入版

這是 [**osfans/trime**](https://github.com/osfans/trime)（同文輸入法，Android 上的 RIME 輸入法）的一個 fork，在其基礎上加入了**完全離線的語音輸入**。

[![License: GPL v3](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Upstream](https://img.shields.io/badge/上游-osfans%2Ftrime-blue)](https://github.com/osfans/trime)
[![Powered by sherpa-onnx](https://img.shields.io/badge/語音辨識-sherpa--onnx%20%2B%20SenseVoice-orange)](https://github.com/k2-fsa/sherpa-onnx)

[English](README.md) | [简体中文](README_sc.md) | 繁體中文

## 與原專案的關係

同文輸入法的一切——[RIME] 引擎、方案、主題、使用者詞典、整套輸入體驗——都來自上游專案，本 fork **沒有改動**。做這個 fork 只為一件事：讓你能對著同一個鍵盤說話打字，而**聲音不出手機**。

| | |
| --- | --- |
| 原專案 | <https://github.com/osfans/trime> |
| 原專案 README（原樣保留） | [README_upstream_tc.md](README_upstream_tc.md) · [English](README_upstream.md) · [简体中文](README_upstream_sc.md) |
| 原專案文件與 Wiki | <https://github.com/osfans/trime/wiki> |
| 本 fork 跟隨 | 上游 `develop` 分支 |

如果你只想要原版同文，請直接從 [F-Droid](https://f-droid.org/packages/com.osfans.trime) 或 [Google Play](https://play.google.com/store/apps/details?id=com.osfans.trime) 安裝——只有當你需要語音輸入時，這個 fork 才有意義。**關於 RIME 方案、主題、通用輸入行為的問題請去上游回報**；本倉庫的 issue 請只用於語音輸入相關問題。

這個 fork 有意做得很淺：新增程式碼幾乎全部落在 `ime/voice/**` 與 `data/voice/**` 兩個新套件裡，被改動的上游檔案只有寥寥數個（鍵盤事件監聽、輸入檢視、設定頁導覽、manifest）。正因如此，跟隨上游 `develop` 變基的成本很低。

## 本專案新增了什麼

### 本地語音輸入（核心功能）

按住某個鍵、說話、鬆手，辨識出的文字直接上螢幕到你正在輸入的地方。辨識全程在**本機**完成，底層是 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 執行 [SenseVoice-Small](https://github.com/FunAudioLLM/SenseVoice) 模型。不連網、不上傳錄音、不裝第二個 App、沒有背景服務、沒有獨立行程。

- **離線是結構性的。** 錄音不出本機，也不落磁碟——它只在一次說話期間存在於記憶體裡，隨後被丟棄。在「智慧校對」關閉（預設）的情況下，模型下載完成之後，本功能**不會發起任何網路請求**。
- **預設關閉。** 你不去設定裡打開它，就不會下載任何東西，不起執行緒、不佔記憶體。
- **閒置自動卸載。** 辨識引擎按需載入，閒置一段時間後自動卸載（預設 5 分鐘；`0` = 用完即卸，`-1` = 常駐），所以「開啟但沒在用」的語音功能幾乎不產生常駐開銷。
- **多語言。** 中文、英語、粵語、日語、韓語，或自動辨識。可選的反向文字正則化（ITN）會輸出標點與規範化數字。
- **回饋收在鍵盤內。** 錄音狀態、即時波形，以及「正在聆聽 → 辨識中 → 校對中」的過程，都畫成鍵盤內的一條狀態列，而不是蓋住整個螢幕的浮層——你始終看得見正在輸入的那個 App。
- **防呆與兜底。** 過短的按壓被判為誤觸而丟棄；單段錄音有可設定的時長上限（預設 60 秒，最長 300 秒）並主動收尾；密碼欄拒絕語音輸入；音訊焦點會正確歸還，媒體播放能恢復。

### 模型管理

- 提供兩個 SenseVoice-Small int8 版本，均取自 sherpa-onnx 官方的 [`asr-models`](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) 發佈：**2024-07-17**（會輸出標點，預設）與 **2025-09-09**（加訓粵語資料，但不輸出標點）。
- 下載在背景進行，可跨應用重啟續傳，並即時顯示進度；壓縮檔的 SHA-256 已內建校驗。
- GitHub 的 release 資源在部分網路環境常常連不上，因此下載會自動回退到公共 GitHub 鏡像；你也可以填自訂下載位址。
- 也支援**從本機檔案匯入模型**（`.tar.bz2` 或 `.zip`），以及刪除已安裝模型來釋放磁碟空間。

### 可選的「智慧校對」——預設關閉

辨識出的文字可以再經過一個**你自己設定的 OpenAI 相容介面**（服務位址、API 金鑰、模型、溫度、逾時，以及可自訂的提示詞）做一次修正，用來處理同音字、口頭禪和標點。

- **預設關閉，而且這是本專案在聽寫鏈路上唯一的出網點。** 一旦開啟，辨識出的文字——不是錄音——會被傳送到*你所設定的*伺服端。
- 任何失敗、逾時或截斷都會**回退到原始辨識文字**，校對永遠不會把你說的話弄丟。
- 設定頁裡有「測試校對」按鈕，把設定錯誤暴露在設定的時候，而不是聽寫到一半的時候。
- 一個需要直說的取捨：API 金鑰儲存在普通的應用偏好設定裡，沒有放進硬體金鑰庫。

### 順帶修的建置問題

- 為 rime-lua 固定 `_FILE_OFFSET_BITS=32`，使 32 位元 Android ABI 恢復可編譯。
- 發佈版 APK 封存檔名帶上版本號，方便管理本機建置產物。

## 語音輸入上手

1. **開啟**：設定 →「**語音輸入**」→ 打開開關。
2. 在同一頁面**下載辨識模型**（約 160 MB），或匯入你已有的模型。
3. 依提示**授予麥克風權限**。
4. **開始用**：預設主題下**長按空白鍵**即可，工具列的麥克風按鈕同樣可用。
   按住說話、鬆手辨識、**上滑取消**。更習慣點按？設定 → 語音輸入 →「觸發方式」→「點按切換」。

任意鍵位都能觸發——在主題裡把它的 `send` 綁定為 `VOICE_ASSIST`：

```yaml
space:
  click: space
  long_click: { send: VOICE_ASSIST }
```

詳細綁定說明見 [`doc/Keyboard.md`](doc/Keyboard.md)。

> 語音輸入**關閉**時，語音鍵的行為與上游完全一致：切換到系統語音輸入法。

### 代價，如實說明

內建 sherpa-onnx 的推理執行時，會讓安裝後的應用體積增加大約 **arm64-v8a 25 MB**、**armeabi-v7a 18 MB**（未壓縮的原生函式庫；下載增量小於此數）。辨識模型**不隨安裝包分發**，就是那個另外下載的約 160 MB 檔案，存放在應用的外部檔案目錄裡。

### 設計文件

這個功能是先設計後實作的，文件都在倉庫裡：

- [`doc/voice-input/voice-input-design.md`](doc/voice-input/voice-input-design.md) —— 目標、非目標、技術選型、隱私模型
- [`doc/voice-input/voice-input-implementation-plan.md`](doc/voice-input/voice-input-implementation-plan.md) —— 實作任務拆解
- [`doc/voice-input/voice-input-feedback-design.md`](doc/voice-input/voice-input-feedback-design.md) —— 鍵盤內狀態列的重新設計
- [`doc/voice-input/voice-input-review-2026-09-05.md`](doc/voice-input/voice-input-review-2026-09-05.md) —— 設計與實作複核

## 建置

與上游相同（環境要求與疑難排解見 [README_upstream_tc.md](README_upstream_tc.md)），只是倉庫位址換成本專案：

```sh
git clone git@github.com:oyasmi/trime.git
cd trime
git submodule update --init --recursive --filter=blob:none

make debug      # Linux/macOS；Windows 上執行 .\gradlew assembleDebug
```

sherpa-onnx 的 AAR **沒有**提交進 git。一個 Gradle 外掛（`build-logic/.../SherpaOnnxPlugin.kt`）會在首次建置時下載指定版本、校驗 SHA-256 並解壓到 `app/libs/`——和上游處理 OpenCC 資料的做法一致。

## 鳴謝

- **[osfans/trime](https://github.com/osfans/trime)** 及其全體貢獻者——本 fork 是他們的成果加上一個功能而已。完整鳴謝見 [README_upstream_tc.md](README_upstream_tc.md#鳴謝)。
- **[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)**（[k2-fsa](https://github.com/k2-fsa)）——讓離線聽寫成為可能的端側語音辨識執行時，本專案使用的預編譯 Android AAR 與模型發佈也都來自它。**沒有它就沒有這個功能。**
- **[SenseVoice](https://github.com/FunAudioLLM/SenseVoice)**（[FunAudioLLM](https://github.com/FunAudioLLM)）——辨識模型。
- **[RIME]** 與 **[OpenCC]** ——同文輸入法自身的基石。

## 第三方函式庫

[README_upstream_tc.md](README_upstream_tc.md#第三方庫) 中列出的全部內容，另加：

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)（Apache License 2.0）——端側語音辨識執行時，含其內建的 [ONNX Runtime](https://github.com/microsoft/onnxruntime)（MIT License）
- [SenseVoice-Small](https://github.com/FunAudioLLM/SenseVoice) —— 模型權重，適用 [FunASR Model Open Source License Agreement](https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE)
- [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/)（Apache License 2.0）——模型壓縮檔解壓

## 授權

GPL-3.0-or-later，與上游一致。參見 [LICENSE](LICENSE) 與 [PRIVACY.md](PRIVACY.md)。

[RIME]: https://rime.im
[OpenCC]: https://github.com/BYVoid/OpenCC
