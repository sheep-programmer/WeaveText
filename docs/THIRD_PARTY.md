# 第三方组件与数据 / Third-Party Components & Data

织文的代码全部自研；以下为作为**依赖**使用的库与**随包分发**的数据。新增依赖必须在此登记。
All WeaveText code is original. Below are libraries used as dependencies and data shipped with the app.
Every new dependency must be registered here.

## 1. 词库数据 / Dictionary data

| 数据 Data | 来源 Source | 许可证 License | 用途 Use | 说明 Notes |
|---|---|---|---|---|
| 万象拼音词库（`zi`、`jichu`、`diming`、`renming`、`mingren`、`shici`、`lianxiang`、`duoyin`、`en`） | [amzxyz/rime_wanxiang](https://github.com/amzxyz/rime_wanxiang) | CC BY 4.0 | 拼音字词与词频、英文词频 | 构建时去声调并编译为 `pinyin.wvl` / `english.wvl`；应用「关于」页署名 |
| 简繁转换表（`STPhrases`、`STCharacters`） | [OpenCC](https://github.com/BYVoid/OpenCC)，经 rime_wanxiang 整理 | Apache-2.0 / CC BY 4.0 | 繁体输出 | 原样随包分发（文本） |
| 表情联想表（`emoji.txt`） | [amzxyz/rime_wanxiang](https://github.com/amzxyz/rime_wanxiang) | CC BY 4.0 | 词后表情候选 | 原样随包分发（文本） |
| 字符搭配模型（`grammar.wvg`） | [amzxyz/RIME-LMDG](https://github.com/amzxyz/RIME-LMDG) `wanxiang-lts-zh-hans.gram` | CC BY 4.0 | 整句组词 | 构建时读取原始模型，剪枝为 2~3 字搭配并转为织文格式 |
| 五笔 86 码表 | [rime/rime-wubi](https://github.com/rime/rime-wubi) | LGPL-3.0 | 五笔 86 编码 | 作为**独立、可替换**的数据文件 `wubi86.wvl` 分发，附许可证全文与源地址；后续计划替换为自建码表 |

*Wanxiang data (CC BY 4.0) is tone-stripped and compiled into `pinyin.wvl` / `english.wvl`, attributed on the
About page. The Wubi 86 table (LGPL-3.0) ships as a separate, replaceable data file `wubi86.wvl` with the license
text and source link; a self-built table is planned.*

署名 / Attribution: 「本应用词库数据部分来自万象拼音（amzxyz/rime_wanxiang），依 CC BY 4.0 授权使用，已做格式转换。」
*"Dictionary data partly from Wanxiang Pinyin (amzxyz/rime_wanxiang), used under CC BY 4.0, format-converted."*

## 1.1 语音模型与运行时 / Speech models & runtime

| 组件 Component | 来源 Source | 许可证 License | 说明 Notes |
|---|---|---|---|
| sherpa-onnx（Android AAR 与桌面 JNI，含 onnxruntime） | [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | Apache-2.0（onnxruntime：MIT） | 构建时下载，不入库 |
| Zipformer 中文模型（内置实时/终稿、可下载高精度实时） | k2-fsa，随 sherpa-onnx 发布 | Apache-2.0（模型卡未单独声明） | 详见 `docs/models.md` |
| SenseVoice / Paraformer / CT-Transformer 标点 | [FunASR](https://github.com/modelscope/FunASR) 模型经 sherpa-onnx 转换 | FunASR Model License（需署名） | 仅按需下载，不随 APK 分发 |

## 2. Rust crates

| Crate | 许可证 License | 用途 Use |
|---|---|---|
| memmap2 | MIT OR Apache-2.0 | 词库 mmap / dictionary mmap |
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
