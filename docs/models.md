# 端侧模型 / On-device Models

## 1. 概览 / Overview

中文：织文的离线语音识别运行在 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)（Apache-2.0，含 onnxruntime）之上，采用**两遍识别**：
流式模型边说边出字，检测到句尾后由非流式模型对这一句重新识别作为终稿，再可选地补全标点。
两个小模型随 APK 内置，开箱即可离线语音输入；更准或多语种的模型可在「设置 → 语音引擎 → 离线模型」中按需下载。

English: offline speech runs on sherpa-onnx with **two-pass recognition** — a streaming model shows text while you speak,
an offline model re-decodes each utterance for the final text, then punctuation is optionally restored. Two small models
ship in the APK; more accurate or multilingual ones are optional downloads.

## 2. 模型清单 / Catalog

目录文件：`android/app/src/main/assets/models/catalog.json`（构建与运行时共用）。 *Shared by the build and the app.*

| id | 用途 Role | 内置 Built-in | 大小 Size | 字错率 CER 干净/嘈杂 clean/noisy | 实时率 RTF | 许可 License |
|---|---|---|---|---|---|---|
| `asr-stream-small` | 实时 streaming | ✓ | 26 MB | 3.11% / 5.90% | 0.09 | Apache-2.0* |
| `asr-final-small` | 终稿 final | ✓ | 63 MB | 0.98% / 1.80% | 0.06 | Apache-2.0* |
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

`./gradlew :app:assembleDebug` 会自动（经同样的镜像列表）下载 sherpa-onnx AAR 与两个内置模型到 `.ref/cache/`（不入库），
校验 SHA-256 后只解出所需文件放入 assets（不压缩）。
*The build fetches the sherpa-onnx AAR and built-in models into `.ref/cache/` (git-ignored) through the same mirrors,
verifies SHA-256 and extracts only the needed files into uncompressed assets.*

### 安装包体积 / APK size

| 构建 Build | 命令 Command | 体积 Size |
|---|---|---|
| 离线语音版（内置运行时与两遍识别模型） / offline voice | `./gradlew :app:assembleRelease` | ≈ 146 MB |
| 轻量版（不含端侧语音识别） / lite | `./gradlew :app:assembleRelease -Pweave.lite=true` | ≈ 30 MB |

轻量版不带 sherpa-onnx 运行时（约 27 MB）与模型，「本地离线识别」与「离线模型」入口不会出现；语音输入使用系统识别或插件。
两个版本的词库都直接从 APK 读取、不再解压（见 `docs/ARCHITECTURE.md` §2.1），装机占用约等于 APK 大小。
*Lite drops the sherpa-onnx runtime (~27 MB) and models, hiding the on-device engine and the models page; voice uses the
system recognizer or plugins. Both builds read dictionaries straight from the APK, so the footprint is about the APK size.*

正式版只含 arm64-v8a；调试版额外含 x86_64 以便模拟器。`-Pweave.abis=arm64-v8a,x86_64` 可覆盖。
*Release builds are arm64-v8a only; debug adds x86_64 for emulators; override with `-Pweave.abis`.*

## 5. 测试 / Tests

`./gradlew :native-test:test` 在桌面 JVM 上验证：目录解析、下载器（镜像回退、坏镜像、断点续传、取消）、两条路线择优与逐文件校验、
Rust 解压真实模型包、两遍识别（真实 sherpa-onnx + 内置模型，两句话各出一个正确终稿）。`WEAVE_NET_TEST=1` 时额外做真实网络下载。
*Desktop tests cover catalog parsing, downloader fallback/resume/cancel, route selection with per-file checks, real archive
extraction, and two-pass recognition with the real runtime; `WEAVE_NET_TEST=1` adds a real-network download.*
