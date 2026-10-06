# 织文输入法 WeaveText

> 自研内核的中文输入法，Android 优先。零配置、秒开、离线、隐私优先。
> A Chinese IME with a self-written engine, Android first: zero config, instant start, offline, private.

## 特性 / Features

- **输入方案**：全拼（整句、简拼、模糊音、常见错拼纠正、中英混输）、双拼（小鹤 / 自然码 / 微软 / 搜狗）、九键、14 键（每键两个字母）、手写（单字，笔顺不限、可连笔）、五笔 86（四码唯一自动上屏、顶屏、`z` 键拼音反查）、英文联想。
  *Schemes: full pinyin (sentences, abbreviations, fuzzy sounds, typo correction, mixed English), double pinyin
  (Xiaohe / Ziranma / Microsoft / Sogou), T9, 14-key (two letters per key), handwriting (single
  characters, any stroke order, cursive strokes), Wubi 86, English with suggestions.*
- **整句更准**：束搜索 + 字符搭配语言模型 + 用户学习；1000 句原创评测集首选整句 75.6%。
  *Beam search, a character collocation model and user learning: 75.6% top-1 on a 1000-sentence benchmark.*
- **联想与学习**：上屏后推荐下一个词（你的搭配优先，其次词库长词的接续）；用户词随时间淡出，
  选错马上退格即撤销学习，连着打的两段第二次出现时记成新词。
  *Next-word prediction after a commit (your own pairs first, then continuations of lexicon phrases); learned words
  fade over time, an immediate backspace undoes a wrong pick, and two pieces typed together become a word the second time.*
- **专业词库与云端热词**：医学、法律、IT、地名等 14 个专业词库按需下载、随时删除；可选的云端热词（默认关闭）
  每天从公开热词库下载一次、签名校验，只下载不上传。
  *14 optional domain dictionaries (medicine, law, IT, places…) downloaded and removed individually; optional cloud
  hot words (off by default) fetched daily from a public, signed word list — download only.*
- **织文互联**：与同一 Wi-Fi 下的电脑（macOS 版）扫码配对，互传文字、图片、文件并同步剪贴板，端到端加密、不经服务器。
  *WeaveLink: pair with a computer on the same Wi-Fi by QR code; exchange text, images and files and sync the clipboard,
  end-to-end encrypted, no server.*
- **实用候选**：`v` 加数字出大写金额与中文数字、`v` 加算式出结果，`rq` / `sj` / `xq` 出日期时间星期，
  敲等号给出算式结果，成对符号，复制的短信里自动取出验证码。
  *Practical candidates: uppercase amounts and Chinese numerals after `v`, arithmetic, date/time shortcuts, a result
  after `=`, paired punctuation, one-time codes from copied messages.*
- **零部署、体积小**：预编译词库分块压缩后直接从 APK 读取、按需解压，手机上不再多占一份；全部词库与模型约 24 MB。
  用户词写只追加日志。
  *Zero deployment and small: precompiled, block-compressed dictionaries are read straight from the APK and decoded
  on demand — about 24 MB for all data, nothing extracted. User words go to an append-only log.*
- **表情收纳袋**：工具栏收纳图片，支持相册／文件／系统分享／兼容来源的拖入；原 GIF、WebP 保留，分组、标签、收藏、最近使用及 Android／Mac 备份互导。可选悬浮窗，Mac 从系统输入法菜单打开；插入与发送仍依赖目标 App 的支持。
- **键盘**：主流输入法式布局、亮/暗主题、候选展开、符号、光标编辑、剪贴板（默认不记录）、常用语、单手、键盘高度、繁体输出、表情联想；
  可拖动、可缩放的悬浮键盘，宽屏（横屏、平板、折叠屏）自动分体，可选数字行布局；多指快速输入不丢键，字符按下即出。
  *mainstream-IME-style layout, light/dark, candidate grid, symbols, cursor panel, clipboard (off by default),
  phrases, one-handed mode, height, traditional output, emoji suggestions; a draggable, resizable floating keyboard,
  automatic split on wide screens, an optional number row; fast multi-finger typing without dropped keys, chars on press.*
- **离线语音**：使用前下载语音包，识别在手机上完成。中文默认实时字幕与终稿复核配合，旧中文实时用户在 Wi-Fi 下补装终稿；支持可选标点、SenseVoice、Paraformer、Dolphin 和 TeleSpeech；Silero 辅助整句分句。点按说话可停顿思考，第二次点击才结束；波纹随真实麦克风音量变化。下载支持镜像、断点续传、大小与 SHA-256 校验（见 [docs/models.md](docs/models.md)）。
- **翻译**：默认使用 Google 官方网页，无需下载或自建服务。离线翻译是独立插件：Android 安装单独的 Google 离线插件 APK，语言包在插件内下载、查看和删除，主输入法不内置 Google 翻译 SDK，也不自动下载模型；Mac 安装独立 Apple 适配插件后使用系统语言包。结果须确认后替换、插入或复制，目标失效时拒绝写回。见 [离线翻译插件](docs/offline-translation-plugins.md)。
- **插件仓库**：手机支持自定义 GitHub 仓库，指定分支与插件目录；私有仓库可用只读访问令牌登录。导入前确认插件信息与网络访问，选用联网语音插件时音频发送给该插件的服务（见 [插件仓库](docs/plugin-repositories.md)）。
  *Offline voice: download models before use. Live transcription with optional final-pass models and punctuation;
  Silero speech detection, explicit recording stop, and a microphone-driven waveform. Downloads support mirrors,
  resuming and SHA-256 verification.*

## 下载 / Download

在 [Releases](../../releases) 下载统一 Android APK（Android 8.0+，arm64）。安装包内置离线语音运行库，识别模型在第一次使用语音时通过镜像自动下载；默认准备中英混合模型，默认识别在手机上完成。

- **Android 版**：只有一个安装包，输入、手写、语音和互联功能统一发布；语音模型按需下载，不需要先手动选择 Lite 或语音版。
- **macOS 版**（`.dmg`，macOS 13+，Apple 芯片与 Intel 通用）：打开磁盘映像，双击里面的安装包，按提示安装即可；
  安装包没有签名，macOS 拦下时到 系统设置 › 隐私与安全性 点「仍要打开」。
安装后在系统设置中启用「织文输入法」并切换为当前输入法。

*Get the APK from [Releases](../../releases) (Android 8.0+, arm64); both builds share one signature. **Lite**
(recommended) has every input feature and reads its dictionaries straight from the APK, so the footprint is about
the APK size; voice uses the system recognizer or plugins, or a one-tap ~30 MB offline voice pack (speech runtime +
streaming model) downloaded in the app — same on-device quality, no reinstall. **Offline voice** adds on-device speech recognition.
**macOS** (`.dmg`, macOS 13+, universal): open the disk image and double-click the installer inside;
the package is unsigned, so if macOS blocks it click Open Anyway in System Settings › Privacy & Security. Enable WeaveText in system settings after installing.*

## macOS 版 / macOS

同一内核的 macOS 输入法（InputMethodKit + SwiftUI，macOS 13+，Apple 芯片与 Intel 通用），可通过织文互联与手机互传
文字、剪贴板和文件。打开 `WeaveText-<版本>-mac.dmg`，双击里面的安装包，按提示装好后在菜单栏的输入法菜单里选「织文拼音」，不用终端；
构建与使用见 [macos/README.md](macos/README.md)。
*A macOS input method on the same engine (InputMethodKit + SwiftUI, macOS 13+, universal for Apple silicon and
Intel) that exchanges text, clipboard and files with the phone over WeaveLink. Open `WeaveText-<version>-mac.dmg`,
double-click the installer inside and pick 织文拼音 from the input menu in the menu bar — no terminal needed; see
[macos/README.md](macos/README.md) to build and use it.*

## 目录 / Layout

| 路径 Path | 内容 Contents |
|---|---|
| `core/` | Rust 内核：`weave-dict` 词库格式、`weave-engine` 解码与联想、`weave-link` 织文互联、`weave-plugin` 插件宿主、`weave-ffi` JNI、`weave-c` C 接口 |
| `macos/` | macOS 输入法：InputMethodKit 前端、候选窗、SwiftUI 设置、自带安装窗口、构建与磁盘映像脚本 |
| `android/` | Android 应用：输入法服务、自绘键盘、Compose 设置、语音后端；`native-test` 桌面 JNI 测试 |
| `data/` | 词库构建脚本 `build.sh`、专业词库 `packs.sh`、整句评测集 `eval/` |
| `docs/` | 架构、设计规范、调研、插件开发、第三方许可（中英双语） |

## 构建 / Build

需要 / Requires: Rust (stable) + `cargo-ndk`、Android SDK 36、NDK 27.2、JDK 17。

```bash
./data/build.sh                         # 下载开放数据并编译词库 / fetch open data, compile dictionaries
cd core && cargo test                   # 内核测试 / engine tests
cd ../android && ./gradlew :app:assembleDebug :app:testDebugUnitTest :native-test:test
```

可选 `-Pweave.bundledPlugins=<目录>` 把该目录下按 ZIP 内容识别的插件包打进 APK（不限制后缀）（默认不含任何插件）。
*Optional `-Pweave.bundledPlugins=<dir>` bundles plugin ZIP archives in that directory regardless of suffix; none by default.*

## 文档 / Docs

- [架构与内核 / Architecture](docs/ARCHITECTURE.md)
- [插件宿主与插件开发 / Plugin host](docs/plugin-host.md)
- [GitHub 插件仓库与私有仓库登录](docs/plugin-repositories.md)
- [UI 设计规范 / Design system](docs/design/)
- [调研报告 / Research](docs/research/)
- [第三方组件与数据 / Third party](docs/THIRD_PARTY.md)

## 数据与许可 / Data & licenses

词库与语言模型数据来自万象拼音（CC BY 4.0）、OpenCC（Apache-2.0）、rime-wubi（LGPL-3.0，独立数据文件），详见
`docs/THIRD_PARTY.md`。项目代码以 [Apache-2.0](LICENSE) 许可发布。
*Dictionary and model data: Wanxiang (CC BY 4.0), OpenCC (Apache-2.0), rime-wubi (LGPL-3.0, separate data
file); see `docs/THIRD_PARTY.md`. The code is licensed under [Apache-2.0](LICENSE).*
