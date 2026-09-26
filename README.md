# 织文输入法 WeaveText

> 自研内核的中文输入法，Android 优先。零配置、秒开、离线、隐私优先。
> A Chinese IME with a self-written engine, Android first: zero config, instant start, offline, private.

## 特性 / Features

- **输入方案**：全拼（整句、简拼、模糊音、常见错拼纠正、中英混输）、双拼（小鹤 / 自然码 / 微软 / 搜狗）、九键、五笔 86（四码唯一自动上屏、顶屏、`z` 键拼音反查）、英文联想。
  *Schemes: full pinyin (sentences, abbreviations, fuzzy sounds, typo correction, mixed English), double pinyin
  (Xiaohe / Ziranma / Microsoft / Sogou), T9, Wubi 86, English with suggestions.*
- **整句更准**：束搜索 + 字符搭配语言模型 + 用户学习；1000 句原创评测集首选整句 75.6%。
  *Beam search, a character collocation model and user learning: 75.6% top-1 on a 1000-sentence benchmark.*
- **零部署、体积小**：预编译词库分块压缩后直接从 APK 读取、按需解压，手机上不再多占一份；全部词库与模型约 24 MB。
  用户词写只追加日志。
  *Zero deployment and small: precompiled, block-compressed dictionaries are read straight from the APK and decoded
  on demand — about 24 MB for all data, nothing extracted. User words go to an append-only log.*
- **键盘**：主流输入法式布局、亮/暗主题、候选展开、符号、光标编辑、剪贴板（默认不记录）、常用语、单手、键盘高度、繁体输出、表情联想。
  *mainstream-IME-style layout, light/dark, candidate grid, symbols, cursor panel, clipboard (off by default),
  phrases, one-handed mode, height, traditional output, emoji suggestions.*
- **语音**：内置**本地离线两遍识别**（实时 + 终稿小模型，语音不离开手机）；可下载 SenseVoice、Paraformer、高精度实时与智能标点模型，
  经 hf-mirror 与多个 GitHub 加速镜像测速下载、断点续传、逐文件校验（见 [docs/models.md](docs/models.md)）；另有系统语音识别，
  以及在沙箱中运行的 Lua 语音插件（`.xipk`）。
  *Voice: built-in on-device two-pass recognition; optional SenseVoice / Paraformer / high-accuracy streaming / punctuation
  models downloaded through hf-mirror and several GitHub mirrors with resume and per-file checks; plus the platform
  recognizer and sandboxed Lua voice plugins.*

## 下载 / Download

在 [Releases](../../releases) 下载 APK（Android 8.0+，arm64），两个版本签名相同、可互相覆盖安装：

- **轻量版**（推荐）：全部输入功能，词库直接从 APK 读取、不再解压，装机占用约等于 APK 大小；语音输入使用系统识别或插件。
- **离线语音版**：在轻量版基础上内置端侧语音识别（语音不离开手机），可再下载更准的模型。

安装后在系统设置中启用「织文输入法」并切换为当前输入法。

*Get the APK from [Releases](../../releases) (Android 8.0+, arm64); both builds share one signature. **Lite**
(recommended) has every input feature and reads its dictionaries straight from the APK, so the footprint is about
the APK size; voice uses the system recognizer or plugins. **Offline voice** adds on-device speech recognition.
Enable WeaveText in system settings after installing.*

## 目录 / Layout

| 路径 Path | 内容 Contents |
|---|---|
| `core/` | Rust 内核：`weave-dict` 词库格式、`weave-engine` 解码、`weave-plugin` 插件宿主、`weave-ffi` JNI |
| `android/` | Android 应用：输入法服务、自绘键盘、Compose 设置、语音后端；`native-test` 桌面 JNI 测试 |
| `data/` | 词库构建脚本 `build.sh`、整句评测集 `eval/` |
| `docs/` | 架构、设计规范、调研、插件开发、第三方许可（中英双语） |

## 构建 / Build

需要 / Requires: Rust (stable) + `cargo-ndk`、Android SDK 36、NDK 27.2、JDK 17。

```bash
./data/build.sh                         # 下载开放数据并编译词库 / fetch open data, compile dictionaries
cd core && cargo test                   # 内核测试 / engine tests
cd ../android && ./gradlew :app:assembleDebug :app:testDebugUnitTest :native-test:test
```

可选 `-Pweave.bundledPlugins=<目录>` 把该目录下的 `.xipk` 打进 APK（默认不含任何插件）。
*Optional `-Pweave.bundledPlugins=<dir>` bundles the `.xipk` files in that directory; none by default.*

## 文档 / Docs

- [架构与内核 / Architecture](docs/ARCHITECTURE.md)
- [插件宿主与插件开发 / Plugin host](docs/plugin-host.md)
- [UI 设计规范 / Design system](docs/design/)
- [调研报告 / Research](docs/research/)
- [第三方组件与数据 / Third party](docs/THIRD_PARTY.md)

## 数据与许可 / Data & licenses

词库与语言模型数据来自万象拼音（CC BY 4.0）、OpenCC（Apache-2.0）、rime-wubi（LGPL-3.0，独立数据文件），详见
`docs/THIRD_PARTY.md`。项目代码以 [Apache-2.0](LICENSE) 许可发布。
*Dictionary and model data: Wanxiang (CC BY 4.0), OpenCC (Apache-2.0), rime-wubi (LGPL-3.0, separate data
file); see `docs/THIRD_PARTY.md`. The code is licensed under [Apache-2.0](LICENSE).*
