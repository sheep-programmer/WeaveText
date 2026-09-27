# 端侧模型 / On-device Models

## 1. 概览 / Overview

中文：织文的离线语音识别运行在 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)（Apache-2.0，含 onnxruntime）之上，采用**两遍识别**：
流式模型边说边出字，检测到句尾后由非流式模型对这一句重新识别作为终稿，再可选地补全标点。
只装了终稿模型（没有实时模型）时按音量断句，说话中每 1.5 秒重新识别一次作为实时文字。新装好的识别模型自动设为使用中；「语音包」里已装的模型可点「使用」随时切换。
*With only a final model, endpoints come from the audio level and the sentence is re-decoded every 1.5 s as live text. A newly installed model is put to use automatically; installed models can be switched with 「使用」.*
离线语音版随 APK 内置运行时与「实时识别 · 小」，开箱即可离线语音输入，终稿、标点等在「语音包」列表里逐项安装；轻量版可在应用内安装识别运行库与模型（见 §4.1）。
更准或多语种的模型可在「设置 → 语音引擎 → 离线模型」中按需下载。

English: offline speech runs on sherpa-onnx with **two-pass recognition** — a streaming model shows text while you speak,
an offline model re-decodes each utterance for the final text, then punctuation is optionally restored. Two small models
ship in the offline-voice APK; the lite build downloads a voice pack instead (§4.1); more accurate or multilingual
ones are optional downloads.

## 2. 模型清单 / Catalog

目录文件：`android/app/src/main/assets/models/catalog.json`（构建与运行时共用）。 *Shared by the build and the app.*

| id | 用途 Role | 内置 Built-in | 大小 Size | 字错率 CER 干净/嘈杂 clean/noisy | 实时率 RTF | 许可 License |
|---|---|---|---|---|---|---|
| `asr-runtime` | 识别运行库 runtime（仅轻量版 / lite only） | – | 27 MB（下载 9 MB） | – | – | Apache-2.0 / MIT |
| `asr-stream-small` | 实时 streaming | ✓ | 26 MB | 3.11% / 5.90% | 0.09 | Apache-2.0* |
| `asr-final-small` | 终稿 final | – | 63 MB | 0.98% / 1.80% | 0.06 | Apache-2.0* |
| `asr-stream-large` | 实时（高精度） | – | 162 MB | 0.33% / 3.77% | 0.28 | Apache-2.0* |
| `asr-sensevoice` | 终稿（普/粤/英/日/韩，带标点） | – | 237 MB | 0.82% / 3.44% | 0.09 | FunASR Model License |
| `asr-paraformer` | 终稿（中文） | – | 238 MB | 0.66% / 3.61% | 0.07 | FunASR Model License |
| `punc-ct` | 智能标点 | – | 76 MB | – | – | FunASR Model License |

\* 这些 Zipformer 模型由 k2-fsa 随 sherpa-onnx（Apache-2.0）发布，模型卡未单独声明许可证；商用发布前需再确认。
*These Zipformer models are published by k2-fsa with sherpa-onnx (Apache-2.0) without a separate model license; confirm
before any commercial release.*

评测方法：从 `data/eval/sentences.tsv` 取 60 句，用 macOS 语音合成（Tingting，两种语速）生成 16 kHz 音频，
「嘈杂」为叠加约 15 dB 信噪比的高斯噪声；RTF 在 M 系列 Mac 上以 2 线程测得。合成语音比真人说话容易，数字只用于模型间相对比较。
*Method: 60 eval sentences synthesized with macOS TTS; "noisy" adds ~15 dB SNR Gaussian noise; RTF on an M-series Mac with
2 threads. Synthetic speech is easier than real speech — use the numbers only to compare models.*

## 3. 下载与加速 / Downloads & mirrors

中文：
- **两条路线**：HuggingFace 逐文件（`hfMirrors`：hf-mirror.com、huggingface.co，无需解压）与 GitHub Releases 压缩包
  （`mirrors`：GitHub 直连与 ghfast.top、gh-proxy.com、gh-proxy.org、ghproxy.net、gh.llkk.cc 加速镜像）。
- 下载前并发测速（取前 64 KB），快的路线与镜像先试；中途断开会在同一镜像续传，失败再换下一个镜像/路线。
- **每个文件都按目录里的 SHA-256 校验**（HuggingFace 与 GitHub 的文件已核对为同一份）；压缩包另外校验整体 SHA-256。
- 失败时保留已下载部分，重试可续传；取消时清除。安装是先解到暂存目录、校验通过后原子改名。
- 用户可指定下载源或填自定义镜像模板（形如 `https://example.com/{url}`），并可设置「仅 Wi-Fi 下载」。
- 镜像可用性会变化：`catalog.json` 中的镜像列表为 2026-09 实测可用的一组，失效的镜像只会被测速排到最后，不影响其它镜像。

English: two routes (HuggingFace per-file via hf-mirror, GitHub archives via five accelerating mirrors plus direct), probed
concurrently; resume on the same mirror, fall back to the next mirror/route; every file verified by SHA-256; partial
downloads kept on failure; atomic installs; user-selectable or custom mirrors; Wi-Fi-only option.

## 4. 构建 / Build

`./gradlew :app:assembleDebug` 会自动（经同样的镜像列表）下载 sherpa-onnx AAR 与内置模型到 `.ref/cache/`（不入库），
校验 SHA-256 后只解出所需文件放入 assets（不压缩）。
*The build fetches the sherpa-onnx AAR and built-in models into `.ref/cache/` (git-ignored) through the same mirrors,
verifies SHA-256 and extracts only the needed files into uncompressed assets.*

### 安装包体积 / APK size

| 构建 Build | 命令 Command | 体积 Size |
|---|---|---|
| 离线语音版（内置运行时与实时识别小模型，原生库压缩存放） / offline voice | `./gradlew :app:assembleRelease` | ≈ 64 MB |
| 轻量版（不含端侧语音识别） / lite | `./gradlew :app:assembleRelease -Pweave.lite=true` | ≈ 30 MB |

轻量版不带 sherpa-onnx 运行时（约 27 MB）与模型；在「语音包」里装好运行库与识别模型（实时模型，或只装终稿模型时整句识别；§4.1）之前「本地离线识别」不会出现，语音输入可用系统识别、手机上其他的语音输入法（一键切换）或插件。
两个版本的词库都直接从 APK 读取、不再解压（见 `docs/ARCHITECTURE.md` §2.1），装机占用约等于 APK 大小。
*Lite drops the sherpa-onnx runtime (~27 MB) and models; until the runtime and a streaming model are installed from the
voice-pack list (§4.1) the on-device engine is hidden and voice uses the system recognizer, another voice IME on the phone
(one-tap switch) or plugins. Both builds read dictionaries straight from the APK, so the footprint is about the APK size.*

正式版只含 arm64-v8a；调试版额外含 x86_64 以便模拟器。`-Pweave.abis=arm64-v8a,x86_64` 可覆盖。
*Release builds are arm64-v8a only; debug adds x86_64 for emulators; override with `-Pweave.abis`.*

### 4.1 轻量版语音包 / Lite voice pack

中文：
- 「设置 → 语音引擎 → 语音包」是逐项列表，每一项单独安装、卸载（语音面板里的「安装离线语音」也会打开这里）。轻量版顶部另有推荐组合一键安装两样：
  **识别运行库** `asr-runtime`（sherpa-onnx 1.13.8 的 C 接口库与 onnxruntime，arm64-v8a，官方文件原样，解开后约 27 MB）
  和 **实时识别 · 小** `asr-stream-small`，合计下载约 30 MB，合并显示进度、可取消，计流量网络下先询问。
- 运行库按目录里的 `archives` 顺序取：先试织文发布页 `asr-runtime-v1.13.8` 上只含 arm64 的小包（约 9 MB），404 或失败再退到 sherpa-onnx 官方的
  多架构 Android 包（约 46 MB），只解出 `arm64-v8a/` 下的两个文件；两条都走同一组 GitHub 镜像，每个文件按 SHA-256 校验。
- 安装后运行库文件设为只读（Android 14 起动态载入的代码必须不可写），再由 Rust 内核按绝对路径载入并核对版本号
  （`core/weave-ffi/src/asr.rs`）。识别参数（端点规则、线程数、两遍识别）与离线语音版一致，所以识别效果相同。
- 装好后自动选中「本地离线识别」；终稿识别与智能标点在同一列表里按需安装。单独安装模型时若还没有运行库会一起装上；卸载运行库不会连带删除模型。不必重装 APK。
- 运行库只在不随包的构建里、且设备主 ABI 相符时才列出；否则此页退回「安装完整离线语音版」（覆盖安装 APK）。

English: the lite build downloads a ~30 MB voice pack — the unmodified sherpa-onnx 1.13.8 C API + onnxruntime
libraries (arm64-v8a) and the small streaming model — with combined progress, cancel and a metered-network prompt. The
runtime tries a small arm64-only pack first and falls back to the official multi-ABI archive, keeping only
`arm64-v8a/`; files are SHA-256 verified, made read-only (Android 14 dynamic-code rule) and loaded by the Rust core with a
version check. Settings match the offline-voice build, so recognition quality is the same. The local engine is selected
automatically; final-pass and punctuation models remain optional. Without a matching ABI the page falls back to the
full offline-voice APK upgrade.

## 5. 测试 / Tests

`./gradlew :native-test:test` 在桌面 JVM 上验证：目录解析、下载器（镜像回退、坏镜像、断点续传、取消）、两条路线择优与逐文件校验、
Rust 解压真实模型包、两遍识别（真实 sherpa-onnx + 测试用模型 `fetchTestModels`，两句话各出一个正确终稿）、压缩包来源回退（首个 404 → 下一个，只取一个 ABI）；
在 macOS arm64 上还会下载桌面版运行库，经 `NativeAsr`（与轻量版同一条载入路径）端到端识别官方测试音频。`WEAVE_NET_TEST=1` 时额外做真实网络下载。
*Desktop tests cover catalog parsing, downloader fallback/resume/cancel, route selection with per-file checks, real archive
extraction, two-pass recognition with the real runtime, archive-source fallback, and (on macOS arm64) an end-to-end
`NativeAsr` decode through the same run-time loading path as the lite build; `WEAVE_NET_TEST=1` adds a real-network download.*
