<!--
SPDX-FileCopyrightText: 2015 - 2026 Rime community

SPDX-License-Identifier: GPL-3.0-or-later
-->

# Trime with Local Voice Input

A fork of [**osfans/trime**](https://github.com/osfans/trime) — the RIME input method for Android — that adds **fully offline voice dictation** to the keyboard.

[![License: GPL v3](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
[![Upstream](https://img.shields.io/badge/upstream-osfans%2Ftrime-blue)](https://github.com/osfans/trime)
[![Powered by sherpa-onnx](https://img.shields.io/badge/ASR-sherpa--onnx%20%2B%20SenseVoice-orange)](https://github.com/k2-fsa/sherpa-onnx)

English | [简体中文](README_sc.md) | [繁體中文](README_tc.md)

## Relationship to the upstream project

Everything Trime does — the [RIME] engine, schemas, themes, user dictionaries, the whole input experience — comes from the upstream project and is **unchanged** here. This fork exists for one reason: to let you dictate into that same keyboard **without sending your voice anywhere**.

| | |
| --- | --- |
| Upstream project | <https://github.com/osfans/trime> |
| Upstream README (kept verbatim) | [README_upstream.md](README_upstream.md) · [简体中文](README_upstream_sc.md) · [繁體中文](README_upstream_tc.md) |
| Upstream documentation & wiki | <https://github.com/osfans/trime/wiki> |
| This fork tracks | upstream `develop` |

If you want plain Trime, install it from [F-Droid](https://f-droid.org/packages/com.osfans.trime) or [Google Play](https://play.google.com/store/apps/details?id=com.osfans.trime) — this fork is only worth it if you want the voice feature. **Issues about RIME schemas, themes or general input behaviour belong upstream**; please only open issues here for the voice input.

The fork is deliberately shallow: nearly all new code lives in two new packages, `ime/voice/**` and `data/voice/**`, and only a handful of upstream files are touched (the keyboard action listener, the input view, the settings navigation, the manifest). That is what keeps rebasing onto upstream `develop` cheap.

## What this fork adds

### Local voice input (the main feature)

Hold a key, talk, release — the recognized text is committed straight into whatever you were typing in. Recognition runs **on the device**, through [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) with a [SenseVoice-Small](https://github.com/FunAudioLLM/SenseVoice) model. No network, no audio upload, no second app, no background service, no separate process.

- **Offline by construction.** Audio never leaves the device and is never written to disk — it lives in memory for the duration of one utterance and is then dropped. With AI correction off (the default), the feature makes **zero network requests** after the model is downloaded.
- **Disabled by default.** Nothing is downloaded, no thread runs, no memory is used until you turn it on in Settings.
- **Idle-unloading engine.** The recognizer is loaded on demand and unloaded after an idle timeout (5 minutes by default; `0` = unload after every utterance, `-1` = keep resident), so an enabled-but-unused voice feature costs essentially nothing.
- **Languages.** Chinese, English, Cantonese, Japanese, Korean, or automatic detection. Optional inverse text normalization (ITN) produces punctuation and normalized numbers.
- **In-keyboard feedback.** Recording state, a live waveform and the "listening → recognizing → correcting" progression are drawn as a compact status strip inside the keyboard, not as a full-screen overlay — you keep seeing the app you are dictating into.
- **Safety rails.** A minimum utterance length rejects accidental taps; a configurable maximum (60 s by default, up to 300 s) wraps up long recordings; password fields refuse voice input; audio focus is released properly so media playback recovers.

### Model management

- Two SenseVoice-Small int8 variants, both published by sherpa-onnx's own [`asr-models`](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) release: **2024-07-17** (produces punctuation — the default) and **2025-09-09** (extra Cantonese training data, no punctuation).
- Downloads run in the background, survive app restarts, resume, and report progress; the archive's SHA-256 is pinned in-app.
- GitHub's release-asset host is often unreachable from mainland China, so downloads automatically fall back to public GitHub mirrors — and you can point at your own URL instead.
- You can also **import a model from a local file** (`.tar.bz2` or `.zip`), or delete an installed one to reclaim the disk space.

### Optional AI correction — off by default

Recognized text can optionally be passed through an **OpenAI-compatible chat-completions endpoint** you configure yourself (base URL, API key, model, temperature, timeout, and your own prompt if you want one) to fix homophones, filler words and punctuation.

- **Off by default, and it is the only place in this project that touches the network at dictation time.** Turning it on means the recognized text — not the audio — is sent to the server *you* configured.
- Any failure, timeout or truncation **falls back to the raw recognized text**; correction can never lose what you said.
- A "test correction" button in Settings verifies your endpoint at configuration time rather than mid-sentence.
- Caveat, stated plainly: the API key is stored in ordinary app preferences, not in a hardware keystore.

### Build fixes carried along

- `_FILE_OFFSET_BITS=32` pinned for rime-lua so 32-bit Android ABIs build again.
- Release APK archives are named with the version, which makes local release builds easier to keep track of.

## Getting started with voice input

1. **Enable it**: Settings → **Voice Input** → turn on.
2. **Download a model** on the same screen (~160 MB). Or import one you already have.
3. **Grant the microphone permission** when prompted.
4. **Use it**: in the default theme, **long-press the space bar**. The toolbar microphone button works too.
   Hold to talk, release to recognize, **slide up to cancel**. Prefer tapping? Settings → Voice Input → *Trigger mode* → *Tap to toggle*.

Any key can trigger it — bind its `send` to `VOICE_ASSIST` in your theme:

```yaml
space:
  click: space
  long_click: { send: VOICE_ASSIST }
```

See [`doc/Keyboard.md`](doc/Keyboard.md) for the binding details.

> When voice input is **disabled**, the voice key behaves exactly as upstream does: it hands off to your system voice IME.

### The cost, honestly

Bundling sherpa-onnx's inference runtime enlarges the installed app by roughly **25 MB on arm64-v8a** and **18 MB on armeabi-v7a** (uncompressed native libraries; the download increment is smaller). The recognition model is **not** bundled — it is that separate ~160 MB download, stored in the app's external files directory.

### Design documents

The feature was designed before it was written, and the documents are in the repo:

- [`doc/voice-input/voice-input-design.md`](doc/voice-input/voice-input-design.md) — goals, non-goals, technology choices, threat model
- [`doc/voice-input/voice-input-implementation-plan.md`](doc/voice-input/voice-input-implementation-plan.md) — task breakdown
- [`doc/voice-input/voice-input-feedback-design.md`](doc/voice-input/voice-input-feedback-design.md) — the in-keyboard status strip
- [`doc/voice-input/voice-input-review-2026-09-05.md`](doc/voice-input/voice-input-review-2026-09-05.md) — design & implementation review

## Building

Same as upstream (see [README_upstream.md](README_upstream.md) for requirements and troubleshooting), with this repo's URL:

```sh
git clone git@github.com:oyasmi/trime.git
cd trime
git submodule update --init --recursive --filter=blob:none

make debug      # Linux/macOS;  .\gradlew assembleDebug on Windows
```

The sherpa-onnx AAR is **not** committed to git. A Gradle plugin (`build-logic/.../SherpaOnnxPlugin.kt`) downloads the pinned release, verifies its SHA-256, and extracts it into `app/libs/` on first build — the same pattern upstream uses for its OpenCC data.

## Acknowledgments

- **[osfans/trime](https://github.com/osfans/trime)** and every one of its contributors — this fork is their work plus one feature. See [README_upstream.md](README_upstream.md#acknowledgments) for the full credits.
- **[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)** by [k2-fsa](https://github.com/k2-fsa) — the on-device speech recognition runtime that makes offline dictation possible, and the source of the prebuilt Android AAR and the model releases used here. The voice feature would not exist without it.
- **[SenseVoice](https://github.com/FunAudioLLM/SenseVoice)** by [FunAudioLLM](https://github.com/FunAudioLLM) — the recognition model.
- **[RIME]** and **[OpenCC]** — the foundations Trime itself is built on.

## Third-party libraries

Everything listed in [README_upstream.md](README_upstream.md#third-party-libraries), plus:

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache License 2.0) — on-device speech recognition runtime, including its bundled [ONNX Runtime](https://github.com/microsoft/onnxruntime) (MIT License)
- [SenseVoice-Small](https://github.com/FunAudioLLM/SenseVoice) — model weights, under the [FunASR Model Open Source License Agreement](https://github.com/modelscope/FunASR/blob/main/MODEL_LICENSE)
- [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) (Apache License 2.0) — model archive extraction

## License

GPL-3.0-or-later, unchanged from upstream. See [LICENSE](LICENSE) and [PRIVACY.md](PRIVACY.md).

[RIME]: https://rime.im
[OpenCC]: https://github.com/BYVoid/OpenCC
