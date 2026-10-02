# 第三方组件与数据 / Third-Party Components & Data

织文的代码全部自研；以下为作为**依赖**使用的库与**随包分发**的数据。新增依赖必须在此登记。
All WeaveText code is original. Below are libraries used as dependencies and data shipped with the app.
Every new dependency must be registered here.

## 1. 词库数据 / Dictionary data

| 数据 Data | 来源 Source | 许可证 License | 用途 Use | 说明 Notes |
|---|---|---|---|---|
| 万象拼音词库（`zi`、`jichu`、`diming`、`renming`、`mingren`、`shici`、`lianxiang`、`duoyin`、`en`） | [amzxyz/rime_wanxiang](https://github.com/amzxyz/rime_wanxiang) | CC BY 4.0 | 拼音字词与词频、英文词频 | 构建时去声调并编译为 `pinyin.wvl` / `english.wvl`；应用「关于」页署名 |
| 简繁转换表（`STPhrases`、`STCharacters`） | [OpenCC](https://github.com/BYVoid/OpenCC)，经 rime_wanxiang 整理 | Apache-2.0 / CC BY 4.0 | 繁体输出 | 内容不变，以分块压缩文件 `st_*.wvz` 随包分发 |
| 表情联想表（`emoji.txt`） | [amzxyz/rime_wanxiang](https://github.com/amzxyz/rime_wanxiang) | CC BY 4.0 | 词后表情候选 | 内容不变，以分块压缩文件 `emoji.wvz` 随包分发 |
| 字符搭配模型（`grammar.wvg`） | [amzxyz/RIME-LMDG](https://github.com/amzxyz/RIME-LMDG) `wanxiang-lts-zh-hans.gram` | CC BY 4.0 | 整句组词 | 构建时读取原始模型，剪枝为 2~3 字搭配并转为织文格式 |
| 五笔 86 码表 | [rime/rime-wubi](https://github.com/rime/rime-wubi) | LGPL-3.0 | 五笔 86 编码 | 作为**独立、可替换**的数据文件 `wubi86.wvz` 分发，附许可证全文与源地址；后续计划替换为自建码表 |
| 专业词库（可选下载：`med`、`drug`、`chem`、`species`、`celeb`、`dialect` 等） | [amzxyz/rime_wanxiang](https://github.com/amzxyz/rime_wanxiang) 的领域词表（`yixue`、`yaopin`、`huaxue`、`wuzhong`、`yiren`、`fangyan`，提交 `516b1bb6`） | CC BY 4.0 | 领域词候选 | 由 `data/packs.sh` 可复现构建，与基础词库同尺度计分并整体靠后；不随 APK 分发，用户在「词库 › 专业词库」按需下载 |
| 专业词库（可选下载：`it`、`finance`、`law`、`car`、`places`、`culture`、`history`、`food`，及 `med`、`species` 的一部分） | [thunlp/THUOCL](https://github.com/thunlp/THUOCL)（提交 `a30ce79d`） | MIT | 领域词候选 | 上游无拼音：构建时按基础词库最长匹配注音，频次按对数排名换算；读不出的词（含外文）跳过 |
| 手写识别模板（`hand.wvz`） | [skishore/makemeahanzi](https://github.com/skishore/makemeahanzi) 的 `graphics.txt`（提交 `bddc96d4`），源自文鼎 Arphic PL KaitiM GB / UKai 字体 | Arphic Public License | 手写输入（9574 字，含 GB2312 全部 6763 字） | 由 `core/weave-dict/src/bin/handgen.rs` 从上游数据生成（可复现），作为**独立数据文件**分发，派生数据仍适用 Arphic Public License，许可证全文见 `docs/licenses/ARPHIC-PUBLIC-LICENSE*.txt`；字频先验取自万象 `zi.dict.yaml`（CC BY 4.0） |
| 手写识别网络（`hand_net.wvz`） | 由 `tools/handnet/` 从上面同一份 Make Me a Hanzi 笔画中线，以及霞鹜文楷、Noto Sans/Serif SC、马善政、智莽行、刘建毛草、龙藏、站酷小薇、站酷快乐等字体（均为 SIL OFL 1.1）的字形骨架合成样本训练 | Arphic Public License（字体部分：OFL 1.1 只约束字体本身，不约束由其渲染的图像） | 手写输入（与模板匹配融合，连笔、潦草书写） | 训练脚本在仓库内，可复现；权重作为**独立数据文件**分发，未使用任何只限研究用途的手写数据库 |

*Wanxiang data (CC BY 4.0) is tone-stripped and compiled into `pinyin.wvl` / `english.wvl`, attributed on the
About page. The Wubi 86 table (LGPL-3.0) ships as a separate, replaceable data file `wubi86.wvz` with the license
text and source link; a self-built table is planned.*

署名 / Attribution: 「本应用词库数据部分来自万象拼音（amzxyz/rime_wanxiang），依 CC BY 4.0 授权使用，已做格式转换。」
*"Dictionary data partly from Wanxiang Pinyin (amzxyz/rime_wanxiang), used under CC BY 4.0, format-converted."*

## 1.1 语音模型与运行时 / Speech models & runtime

| 组件 Component | 来源 Source | 许可证 License | 说明 Notes |
|---|---|---|---|
| sherpa-onnx（Android AAR 与桌面 JNI，含 onnxruntime） | [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | Apache-2.0（onnxruntime：MIT） | 构建时下载，不入库 |
| 识别运行库（语音包）：`libsherpa-onnx-c-api.so` 与 `libonnxruntime.so` v1.13.8，arm64-v8a | sherpa-onnx 官方 Android 发布包，原样取出 | Apache-2.0（onnxruntime：MIT） | 不随轻量版 APK 分发；用户在应用内下载后按 SHA-256 校验、设为只读再载入 / not in the lite APK; downloaded in-app, SHA-256 verified, made read-only, then loaded |
| Zipformer 中文模型（实时/终稿按需下载） | k2-fsa，随 sherpa-onnx 发布 | Apache-2.0（模型卡未单独声明） | 详见 `docs/models.md` |
| Zipformer 中英双语 transducer 标准/增强 | [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) 官方双语 mobile 导出 | Apache-2.0 | 编码器、解码器、连接器与词表按需下载，详见模型目录 |
| 标准中英模型热词分词表（`vocab-mixed-standard.txt`） | [原始双语模型](https://huggingface.co/csukuangfj/k2fsa-zipformer-bilingual-zh-en-t)，[sherpa 官方转换包](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html#sherpa-onnx-streaming-zipformer-small-bilingual-zh-en-2023-02-16-bilingual-chinese-english) | Apache-2.0（原始模型卡声明） | 从匹配的 `bpe.model` 原样导出 500 个 SentencePiece 片段，用于个人热词；原始模型 SHA-256 `bcae393dbc5611be5ffa4c7ae0841558978a5a4f484008cb9dff3a2cc97ebe01`；不随包分发声学权重 |
| WeNet 中英粤语 CTC | [ASLP-lab/WSYue-ASR](https://huggingface.co/ASLP-lab/WSYue-ASR)，Sherpa ONNX 导出 | Apache-2.0 | 模型卡声明许可；按需下载，不随 APK 分发 |
| Whisper base/small int8 | [OpenAI Whisper](https://github.com/openai/whisper)、Sherpa ONNX 转换 | MIT（Copyright 2022 OpenAI） | 按需下载，可选自动、中文、英文；英文测试与混说边界见语音调研记录 |
| SenseVoice / Paraformer / CT-Transformer 标点 | [FunASR](https://github.com/modelscope/FunASR) 模型经 sherpa-onnx 转换 | FunASR Model License（需署名） | 仅按需下载，不随 APK 分发 |

## 1.2 可选云端补充词表

签名发布仓库：[weavetext-hotwords](https://github.com/sheep-programmer/weavetext-hotwords)。原有示例与自主整理的 AI／输入法术语为 CC0；万象补充数据保留 CC BY 4.0；THUOCL 数据保留 MIT，基于万象的补充注音保留 CC BY 4.0。发布原文含署名、许可链接、修改说明和 THUOCL MIT 声明，客户端只下载并在本机解码，不上传个人输入。

来源、固定提交、原始及转换摘要、复现脚本和完整许可见 [SOURCES.md](https://github.com/sheep-programmer/weavetext-hotwords/blob/main/SOURCES.md)。不能将合并词表整体标为 CC0。本轮参考 Fcitx5 的来源提示交互，独立实现界面，没有引入其 LGPL 源码。

## 2. Rust crates

| Crate | 许可证 License | 用途 Use |
|---|---|---|
| memmap2 | MIT OR Apache-2.0 | 词库 mmap / dictionary mmap |
| brotli-decompressor（含 alloc-no-stdlib、alloc-stdlib） | BSD-3-Clause OR MIT（alloc-*：BSD-3-Clause） | 分块压缩词库解压 / decoding block-compressed data |
| brotli（仅构建工具 `wvpack`，不进 APK / build tool only） | BSD-3-Clause AND MIT | 生成 `.wvz` / packing `.wvz` |
| jni | MIT OR Apache-2.0 | Android JNI 绑定 / JNI bindings |
| tar | MIT OR Apache-2.0 | 模型包解压 / model archive extraction |
| bzip2（libbz2-rs-sys，纯 Rust） | MIT OR Apache-2.0（libbz2-rs-sys：bzip2 license） | 模型包解压 / model archive extraction |
| mlua（含 vendored Lua 5.4 源码 / with vendored Lua 5.4 via lua-src） | MIT（Lua: MIT） | 插件宿主 Lua 运行时 / plugin host Lua runtime |
| serde_json | MIT OR Apache-2.0 | 插件 JSON、配置 / plugin JSON & config |
| yaml-rust2 | MIT OR Apache-2.0 | 解析 manifest.yaml / manifest parsing |
| zip（deflate，经 flate2 / via flate2） | MIT（flate2: MIT OR Apache-2.0） | 解包 `.xipk` / unpacking `.xipk` |
| rustls（ring 后端 / ring backend） | Apache-2.0 OR ISC OR MIT | 插件网络 TLS / TLS for plugin networking |
| ring | Apache-2.0 AND ISC | rustls 密码学后端 / rustls crypto backend |
| webpki-roots | CDLA-Permissive-2.0 | 内置 TLS 根证书 / bundled TLS root certificates |
| tungstenite | MIT OR Apache-2.0 | 插件 WebSocket / plugin WebSocket |
| ureq | MIT OR Apache-2.0 | 插件 HTTP 与 SSE / plugin HTTP & SSE |
| aes, cbc, ctr, cipher, hmac, sha1, sha2, md-5, rsa（RustCrypto） | MIT OR Apache-2.0 | `host.crypto` |
| ecb | MIT | `host.crypto` AES-ECB |
| base64 | MIT OR Apache-2.0 | `host.crypto` 编码 / encodings |
| getrandom, rand_core | MIT OR Apache-2.0 | 随机数 / randomness |
| libloading | ISC | 运行时载入下载的识别运行库 / loading the downloaded speech runtime at run time |
| snow（含 chacha20poly1305、blake2、x25519-dalek、curve25519-dalek 等 RustCrypto / dalek 组件） | Apache-2.0 OR MIT（dalek：BSD-3-Clause） | 织文互联的 Noise 加密通道 / WeaveLink Noise encrypted channel |
| spake2 | MIT OR Apache-2.0 | 织文互联配对码的口令认证密钥交换 / WeaveLink pairing-code PAKE |
| mdns-sd（含 flume、socket2、if-addrs） | Apache-2.0 OR MIT | 织文互联局域网发现 / WeaveLink LAN discovery |
| serde | MIT OR Apache-2.0 | 织文互联的设备列表 / WeaveLink device list |

## 3. Android 库 / Android libraries

| 库 Library | 许可证 License | 用途 Use |
|---|---|---|
| AndroidX (core-ktx, activity-compose, lifecycle) | Apache-2.0 | 基础组件 / basics |
| Jetpack Compose, Material 3 | Apache-2.0 | 设置 App 界面 / settings UI |

### 3.1 仅测试使用 / Test-only (not shipped in the APK)

| 库 Library | 许可证 License | 用途 Use |
|---|---|---|
| JUnit 4 | EPL-1.0 | 单元测试框架 / unit test framework |
| Robolectric | MIT | JVM 上运行 Android 代码与原生图形渲染 / Android on the JVM with native graphics |
| Roborazzi（roborazzi、roborazzi-compose） | Apache-2.0 | JVM 截图测试 / JVM screenshot tests |
| AndroidX Test (core-ktx)、Compose UI Test (ui-test-junit4, ui-test-manifest) | Apache-2.0 | 测试工具 / test utilities |
