# 01 · 开源输入法生态概览与对比 / Open-source IME Landscape & Comparison

> 调研日期 / Date: 2026-09-25 · 范围 / Scope: 内核（引擎）、Android 键盘前端、词库发行版
> 方法 / Method: 直接抓取各仓库源码、LICENSE、wiki 与 issue tracker（GitHub Search API + raw 文件），
> 所有结论均附可点击出处；凡未能核实者标注「未验证」。
> *All findings below were verified against repository sources, LICENSE files, wikis and issue trackers;
> unverified items are explicitly marked.*

---

## 0. 一句话结论 / TL;DR

**中文**
1. **内核只有两条成熟技术路线**：librime 的「固定音节表 + 棱镜(prism) + 词表(table) + 八股文语法」与 libime 的「音节图 + DATrie 词典 + KenLM n-gram + beam search」。两者都能做整句，但**都慢在启动/部署**，而不是慢在解码。
2. **Rime 生态最大的痛点不是算法，而是「部署/同步/配置」这套 2011 年的架构**：改一个词要重新部署、部署要重算全量词库 CRC32、用户数据与配置混在同一个目录、后台同步会拖垮打字。这些是**织文可以结构性绕开**的地方，也是「优于 Rime」最省力的切入点。
3. **Android 前端全都自己重写输入逻辑**（FlorisBoard 的 `EditorInstance` 状态机、HeliBoard 的 `inputlogic/InputLogic` + `RichInputConnection`、Trime 的 `Rime.java`），说明 **InputConnection 的 composing/光标状态机是最大的坑区**，必须自建一层「编辑会话状态机」，不能边写边试。
4. **词库许可证是硬约束**：rime-ice 是 **GPL-3.0 (only)** 且其词源含「华宇野风系统词库」「腾讯词向量」等**许可证不明/仅限研究**的上游；`rime-essay`/`octagram-data`/`luna-pinyin`/`wubi` 是 **LGPL-3.0**；只有 **THUOCL (MIT)、pinyin-data (MIT)、Unihan (Unicode License v3)、OpenCC (Apache-2.0)、万象 RIME-LMDG (CC BY 4.0)** 是可以放心再分发的。详见 `04-data-sources-licenses.md`。

**English**
1. Two mature engine designs exist: librime (fixed syllabary + prism + table + "octagram" grammar) and libime (syllable graph + DATrie dictionary + KenLM n-gram + beam search). Both do full-sentence decoding; **both are slow at startup/deployment, not at decoding.**
2. Rime's real pain is its 2011-era **deploy/sync/config** architecture: editing one word triggers a full redeploy, deployment recomputes CRC32 over the whole dictionary, user state and config share one directory, and background sync stalls typing. These are exactly the structural wins available to WeaveText.
3. Every Android frontend **rewrites its own input logic** (FlorisBoard `EditorInstance`, HeliBoard `inputlogic/InputLogic` + `RichInputConnection`, Trime `Rime.java`) — proof that the `InputConnection` composing/cursor state machine is the single biggest pitfall area.
4. **Dictionary licensing is a hard constraint**: rime-ice is GPL-3.0 (only) with upstreams of unclear/research-only provenance; rime-essay / octagram-data / luna-pinyin / wubi are LGPL-3.0; only THUOCL, pinyin-data, Unihan, OpenCC and the Wanxiang LMDG model are safely redistributable. See `04-data-sources-licenses.md`.

---

## 1. 内核（引擎）对比 / Engines

| 项目 Project | 语言 Lang | 许可证 License | 词典/模型格式 Format | 解码 Decoding | 语言模型 LM | 用户学习 Learning | 维护 Maintained |
|---|---|---|---|---|---|---|---|
| [librime](https://github.com/rime/librime) | C++17 | BSD-3-Clause | `*.prism.bin`(darts 双数组) + `*.table.bin`(字符串池+分级索引) + `*.reverse.bin` + `*.userdb`(LevelDB) | 音节图 → 逐段翻译 → 组句（八股文插件） | 八股文 n-gram（LGPL-3.0，外挂） | LevelDB userdb + `formula_d` 指数衰减 | 活跃 |
| [libime](https://github.com/fcitx/libime)（fcitx5） | C++17 | LGPL-2.1-or-later | `sc.dict`(cedar DATrie，zstd 流) + `zh_CN.lm`(KenLM trie) + `zh_CN.lm.predict` | 音节图 + Lattice + beam search（默认 beam=20） | KenLM n-gram + 历史 bigram | 用户词典 + `UserLanguageModel` + `HistoryBigram` | 活跃 |
| [libpinyin](https://github.com/libpinyin/libpinyin) | C++ | **GPL-3.0** | 自定义二进制 + BerkeleyDB/KyotoDB/TKRZW 多后端 | n-gram 整句 + `matrix`/`lookup` 分层 | 自研 flexible n-gram | 自学习用户词典 | 活跃 |
| [sunpinyin](https://github.com/sunpinyin/sunpinyin) | C++ | LGPL-2.1 + CDDL 双许可 | 自有二进制（`*.dict`/`*.lm`） | 整句解码 | 自研 bi-gram | 有 | 低频（最近提交 2025-03） |
| AOSP PinyinIME | C++/Java | Apache-2.0（数据来源不明） | `dict_pinyin.dat`（二进制打包） | `MatrixSearch` 矩阵搜索 | 内置 bigram | 无 | **已从 AOSP 移除** |
| [rime-ice](https://github.com/iDvel/rime-ice)（配置发行版） | YAML/Lua | **GPL-3.0 (only)** | 复用 librime | 复用 librime | 可选八股文/万象 | 复用 librime | 活跃 |
| [万象 rime_wanxiang](https://github.com/amzxyz/rime-wanxiang) + [RIME-LMDG](https://github.com/amzxyz/RIME-LMDG) | YAML | CC BY 4.0 | 复用 librime | 复用 librime | 自训 32GB 语料 n-gram | 复用 librime | 活跃 |

**逐条点评 / Notes**

- **librime 的格式是「编译期定型」**：部署时把 `*.dict.yaml` 编译成 `prism.bin`（音节表 + 拼写运算结果，内部是 [darts](https://github.com/rime/librime/blob/master/src/rime/dict/prism.h) 双数组）与 `table.bin`（词条 + 权重，字符串池 + 分级索引）。运行时用 `mmap`（[`mapped_file.h`](https://github.com/rime/librime/blob/master/src/rime/dict/mapped_file.h)）零拷贝加载，**这点值得抄**；但它的**增量更新能力为零**：改一个词 = 重编译整本词典。
  *librime freezes everything at compile time (darts double-array prism + string-pool table), mmaps them for zero-copy reads — worth copying — but it has zero incremental update: one word change recompiles the whole dictionary.*
- **libime 的架构更现代**：`PinyinDictionary` 是多个 cedar DATrie 的叠加（系统词典/用户词典/附加词典），语言模型是 KenLM trie，词典流用 zstd 压缩，解码器是标准 beam search（`beamSizeDefault = 20`，见 [`decoder.h`](https://github.com/fcitx/libime/blob/master/src/libime/core/decoder.h)）。**它的 `HistoryBigram` + `UserLanguageModel` 分离设计，是织文用户学习的正确参考模型。**
  *libime is more modern: stacked cedar DATries, KenLM trie LM, zstd-compressed dictionary streams, standard beam search. Its split of HistoryBigram vs UserLanguageModel is the right reference for user learning.*
- **libpinyin 是 GPL-3.0**，且存储层同时维护 BerkeleyDB / KyotoDB / TKRZW 三套后端（[`src/storage/`](https://github.com/libpinyin/libpinyin/tree/main/src/storage)），历史包袱重；**只能借鉴算法思路，代码与数据都不可用**。
  *libpinyin is GPL-3.0 with three parallel storage backends — ideas only, no code or data.*
- **AOSP PinyinIME 已从 AOSP 主干移除**，社区镜像仍在（如 [`lizhangqu/PinyinIME`](https://github.com/lizhangqu/PinyinIME)，含 `share/matrixsearch.cpp` 与 `res/raw/dict_pinyin.dat`）。它的 `MatrixSearch` 是「音节矩阵 + bigram 动态规划」的经典实现，**解码结构值得读**，但 `dict_pinyin.dat` 的数据来源在 AOSP 中从未说明，**不可使用**。
  *AOSP PinyinIME was removed from AOSP; community mirrors remain. Its MatrixSearch is a classic syllable-matrix + bigram DP design worth reading, but dict_pinyin.dat's provenance was never documented — do not use it.*

---

## 2. Android 键盘前端对比 / Android Frontends

| 项目 Project | 许可证 License | 键盘渲染 Rendering | 中文能力 Chinese | 关键教训 Key lesson |
|---|---|---|---|---|
| [Trime 同文](https://github.com/osfans/trime) | **GPL-3.0** | 自绘 View + 主题 YAML | 完整（librime 全功能） | 主题/资源加载拖慢弹键盘；切键盘会重新部署 |
| [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) | **LGPL-2.1** | 自绘 View（Kotlin） | 完整（fcitx5 + libime） | 大词库（>30MB）首次按键卡顿数秒 |
| [FlorisBoard](https://github.com/florisboard/florisboard) | Apache-2.0 | Compose + 自绘混合 | 无（仅英文/多语言拼写） | 输入逻辑重写过三次（#1822/#444/#1169），证明状态机难写 |
| [HeliBoard](https://github.com/HeliBorg/HeliBoard)（原 Helium314/HeliBoard） | **GPL-3.0** | 自绘 View | 无 | AOSP LatinIME 的现代重构；`inputlogic/InputLogic` + `RichInputConnection` 分层清晰 |
| [AnySoftKeyboard](https://github.com/AnySoftKeyboard/AnySoftKeyboard) | Apache-2.0 | 自绘 View | 弱（社区语言包） | 与 Compose `TextField`、第三方 App 的兼容坑最多 |
| 主流商业输入法 | 闭源 | — | 完整 + 云 | **仅作 UI/交互参考，代码与词库一律不可用** |

**要点 / Takeaways**

1. **只有 Trime 与 fcitx5-android 真正做中文**，其余三个是「英文键盘 + 拼写检查」。中文输入法的 Android 端在开源世界基本是空白区 —— 这正是织文的机会，也是**没有现成答案可抄**的部分。
   *Only Trime and fcitx5-android do real Chinese; the rest are English keyboards. The Android Chinese IME space is effectively empty in open source — the opportunity, and the reason there is nothing to copy.*
2. **自绘 Canvas View 是性能正确选择**：本项目已定此方向；FlorisBoard 在混合 Compose 键盘上长期受延迟困扰，而 HeliBoard/Trime 的自绘 View 方案没有此类抱怨。
   *Canvas-drawn keys are the right performance choice; FlorisBoard's Compose-heavy keyboard has a long latency track record.*
3. **参考对象要分开**：UI/交互参考主流商业输入法（`docs/design/` 已有截图）；输入逻辑分层看 HeliBoard（GPL-3.0，只能看思路）；中文引擎看 libime 与 librime。
   *Split the references: mainstream IMEs for UI, HeliBoard for input-logic layering (GPL-3.0 — ideas only), libime/librime for the Chinese engine.*

---

## 3. 词库发行版对比 / Dictionary Distributions

| 发行版 Distribution | 词量级 Size | 许可证 License | 词源风险 Provenance risk | 可否用于织文 Usable |
|---|---|---|---|---|
| rime-ice | ~100 万条（含 tencent 表 98 万行） | **GPL-3.0 (only)** | 华宇野风系统词库（论坛，无许可证）、腾讯词向量（仅研究）、现代汉语常用词表（gist）、北语 25 亿字字频 | **不可**（传染 + 上游不明） |
| 万象 rime_wanxiang / RIME-LMDG | 32GB 语料训练 | CC BY 4.0 | 语料构成未逐项披露 | 谨慎可用（需署名，需评估语料合规） |
| rime-essay 八股文 | ~23 万词 | **LGPL-3.0** | CC-CEDICT + 新酷音整理 | 谨慎（LGPL 传染性弱于 GPL，但仍是 copyleft） |
| rime-octagram-data 语法模型 | n-gram 模型 | **LGPL-3.0** | 「开放的互联网语料」未具名 | 谨慎 |
| rime-luna-pinyin | 单字表 | **LGPL-3.0** | CC-CEDICT / AOSP PinyinIME / 新酷音 | 谨慎 |
| rime-wubi | 五笔86 码表 | **LGPL-3.0** | 极点五笔 6 + 某搜索引擎词频 | 谨慎 |
| libime data（`dict_sc.txt`/`lm_sc.arpa`） | — | **LGPL-2.1-or-later**（REUSE.toml 声明） | fcitx 项目数据（Yuking/CSSlayer 版权） | 谨慎（独立资源包 + 声明） |
| THUOCL | 分领域词表 | MIT | 清华 THUNLP | ✅ 可 |
| pinyin-data | 汉字注音 | MIT | Unihan + 汉典（`zdic.txt` 需单独处理） | ✅ 可 |
| Unihan | 汉字属性 | Unicode License v3 | Unicode 官方 | ✅ 可 |
| OpenCC | 简繁转换表 | Apache-2.0 | 开源社区 | ✅ 可 |

> 许可证细节、证据链接与「推荐组合」见 `04-data-sources-licenses.md`。
> *License details, evidence links and the recommended stack: see `04-data-sources-licenses.md`.*
> 摘要 / Summary: only THUOCL (MIT), pinyin-data (MIT), Unihan (Unicode License v3), OpenCC (Apache-2.0),
> the Wanxiang model (CC BY 4.0) and libime data (LGPL-2.1) are usable; everything under rime-ice is blocked
> by GPL-3.0 plus unclear upstreams.

---

## 4. 织文的定位 / Positioning

**中文** — 三条不可动摇的差异化：

1. **「零部署」**：词库是**只读的、预编译的、随包分发的**（首次启动只做 mmap，不做编译）。用户改词走**增量写日志 + 后台合并**，永不阻塞键盘。Rime 的「重新部署」概念在织文里**不存在**。
2. **「首帧即键盘」**：键盘 View 必须在 `onCreateInputView` 后的第一帧就完整可见（不含候选），引擎在后台线程预热；候选数据到达后再刷新候选栏。目标：冷启动到可打字 ≤ 200ms，热切换 ≤ 50ms。
3. **「一个引擎，五种输入」**：全拼/双拼/九键/五笔/英文共用同一套「音节图/码表图 + Lattice + 打分器」抽象，而不是像 Rime 那样靠 schema 配置拼装。用户切换输入方式时，**用户词与词频完全共享**。

**English** — three non-negotiable differentiators:

1. **Zero deployment.** Dictionaries ship pre-compiled and read-only; first launch only mmaps them. Edits go to an append-only log merged in the background. The concept of "redeploy" does not exist in WeaveText.
2. **Keyboard first frame.** The keyboard view must be fully visible on the first frame after `onCreateInputView`; the engine warms up off the UI thread and candidates refresh later. Targets: cold start to typable ≤ 200 ms, hot switch ≤ 50 ms.
3. **One engine, five input modes.** Pinyin / double-pinyin / 9-key / Wubi / English share a single "graph + lattice + scorer" abstraction instead of Rime's schema assembly. User words and frequencies are shared across all modes.

---

## 5. 出处 / Sources

**内核**
- librime: <https://github.com/rime/librime> · LICENSE BSD-3-Clause · <https://raw.githubusercontent.com/rime/librime/master/LICENSE>
- librime 词典格式: <https://github.com/rime/librime/blob/master/src/rime/dict/prism.h> · <https://github.com/rime/librime/blob/master/src/rime/dict/table.h> · <https://github.com/rime/librime/blob/master/src/rime/dict/mapped_file.h>
- librime 词典扩展包（prism/table 说明）: <https://github.com/rime/home/wiki/DictionaryPack>
- Rime 输入方案设计书（组件、编译、部署、八股文）: <https://github.com/rime/home/wiki/RimeWithSchemata>
- libime: <https://github.com/fcitx/libime> · `decoder.h` beamSizeDefault=20 · `core/datrie.h`（cedar） · `core/languagemodel.h`（KenLM） · `data/CMakeLists.txt`（`lm_sc.arpa` / `dict_sc.txt` 下载源）
- libime 词典文本格式: <https://github.com/fcitx/libime/blob/master/src/libime/pinyin/pinyindictionary-text-format_zh.md>
- libime 双拼方案格式: <https://github.com/fcitx/libime/blob/master/src/libime/pinyin/shuangpin-profile-format_zh.md>
- libpinyin（GPL-3.0）: <https://github.com/libpinyin/libpinyin> · 存储层 <https://github.com/libpinyin/libpinyin/tree/main/src/storage>
- sunpinyin（LGPL-2.1 + CDDL）: <https://github.com/sunpinyin/sunpinyin> · COPYING: 「Refer to LGPL.LICENSE and OPENSOLARIS.LICENSE.」
- AOSP PinyinIME 镜像: <https://github.com/lizhangqu/PinyinIME>（`app/src/main/cpp/share/matrixsearch.cpp`、`app/src/main/res/raw/dict_pinyin.dat`）

**Android 前端**
- Trime（GPL-3.0）: <https://github.com/osfans/trime>
- fcitx5-android（LGPL-2.1）: <https://github.com/fcitx5-android/fcitx5-android>
- FlorisBoard（Apache-2.0）: <https://github.com/florisboard/florisboard>
- HeliBoard（GPL-3.0，仓库已迁移至 HeliBorg/HeliBoard）: <https://github.com/HeliBorg/HeliBoard>
- AnySoftKeyboard（Apache-2.0）: <https://github.com/AnySoftKeyboard/AnySoftKeyboard>

**词库发行版**
- rime-ice（GPL-3.0 only）: <https://github.com/iDvel/rime-ice> · README §许可证
- rime-essay（LGPL-3.0）: <https://github.com/rime/rime-essay>
- rime-octagram-data（LGPL-3.0）: <https://github.com/lotem/rime-octagram-data>
- 万象（CC BY 4.0）: <https://github.com/amzxyz/rime-wanxiang> · <https://github.com/amzxyz/RIME-LMDG>
