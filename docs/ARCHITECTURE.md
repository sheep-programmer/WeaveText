# 织文输入法 · 架构与内核 / WeaveText · Architecture & Engine

## 1. 总览 / Overview

```
┌──────────────────────── Android app (Kotlin) ────────────────────────┐
│ WeaveImeService ── InputController ── KeyboardUi（自绘 View）          │
│        │                 │                                            │
│        │            NativeEngine (JNI)        VoiceHub ── NativePluginHost (JNI)
│   EngineHolder（进程共享）                     AudioRecord 16 kHz PCM   │
│ SettingsActivity（Compose M3）── UserDictionary / VoiceEngines         │
└──────────┬──────────────────────────────────────────────┬────────────┘
           │ libweave.so                                  │
┌──────────▼──────────── Rust core ───────────────────────▼────────────┐
│ weave-ffi     JNI 绑定：引擎句柄、快照编码、插件会话回调、互联            │
│ weave-c       C 接口（macOS 前端）：快照以 JSON 返回、互联                │
│ weave-engine  会话状态机 · 音节图 · 解码器 · 联想 · 用户学习 · 码表 ·     │
│               特殊候选（算式/大写金额/日期）· 云端热词 · 繁简/表情         │
│ weave-dict    音节表 · 前缀树词库 .wvl · 字符搭配模型 .wvg · 接续表 .wvf ·│
│               手写模板 · 构建工具（dictgen / followgen / wvpack …）       │
│ weave-link    织文互联：mDNS 发现 · SPAKE2 + Noise 配对与加密 · 文件传输  │
│ weave-plugin  Lua 5.4 插件宿主：host.* API、网络白名单、配额、.xipk      │
└──────────────────────────────────────────────────────────────────────┘
```

macOS 版（`macos/`，Swift + InputMethodKit）链接 `weave-c` 的静态库，与 Android 共用同一内核与数据。
*The macOS app (`macos/`, Swift + InputMethodKit) links the `weave-c` static library and shares the engine and data.*

中文：界面层只和 `InputController`、`VoiceHub` 打交道；内核是一个单线程状态机，每次按键后返回一份快照
（上屏文字、组合串、候选）。组合中的拼音**不写入编辑器**，只在确定时 `commitText`，避开各 App 对
composing 文本支持不一的兼容问题。

中文补充：**组合串有上限**。全拼/双拼/英文里，键盘上还没变成字词的最长原始输入是 96 个字母
（`MAX_COMPOSITION_RAW`）；超过就把首选能上屏的部分先上屏、剩下的按键留在组合里（与五笔满四码顶屏同一思路）。
没有这条上限时，乱打一整串会让组合串无上限地长，而每次按键都要把整串重解一遍（建音节图 + 格解码），
实测每键 2 ms 一路涨到 28 ms，就是「乱打一会儿越来越卡」。**代价**：一句连打超过 96 个字母才选词的句子
会在中途被切开（`keybench` 与 `typing_latency` 里有对照）；真实输入几乎不会这样，因为中间总会选词。
*Composition cap: at most 96 raw letters (`MAX_COMPOSITION_RAW`) stay uncommitted; past that, what can be
committed is committed. Without it a mashed run grows without bound while every key re-solves the whole string
(2 ms → 28 ms per key measured). Cost: a sentence typed past 96 letters with no pick in between gets cut;
see `keybench` and `typing_latency`.*

English: the UI talks only to `InputController` and `VoiceHub`. The engine is a single-threaded state
machine returning a snapshot (commit text, preedit, candidates) after every key. The preedit is never
written into the editor; only final text is committed, which avoids per-app composing-text quirks.

## 2. 词库格式 / Lexicon format（`.wvl` v4）

中文：一种格式同时服务拼音（符号 = 音节 ID）与五笔/英文（符号 = 字母 1..26）。前缀树按 BFS 顺序存放，
同一父节点的子节点连续且按符号升序，因此：

- 节点字段**按列存放**：`sym`、`best`（子树最小代价，供补全做最佳优先搜索）、子节点数、词条数各成一列，
  每个节点共 8 字节；二分查找只读 `sym` 列，前缀和只读两个计数列，同类数值挨在一起也更好压缩；
- 首个子节点、首个词条的下标由「每 16 个节点一个检查点 + 组内前缀和」推出，不必逐节点存储；
- 词条 3 字节：`cost`（`round(-ln p × 1000)`）与文本长度两列；文本按词条顺序紧挨存放，同一节点的候选
  落在同一个压缩块里，每 16 个词条存一个文本起点；
- 拼音词库的文本按**音节内序号**编码：第 i 个字记为它在第 i 个音节常用字表里的名次（几乎都 < 224，占 1 字节），
  字数与音节数不符的词条原样存 UTF-8。压缩后文本只有 UTF-8 的约 30%。

整个拼音词库原始 43 MB（旧格式 73 MB）。

English: one format for pinyin (symbol = syllable id) and Wubi/English (symbol = letter). The trie is stored
in BFS order with contiguous, sorted siblings. Node fields are **columnar** (`sym`, `best`, child count, entry
count; 8 bytes per node in total), so a binary search reads only the `sym` column and like values compress
together; child/entry offsets come from a checkpoint every 16 nodes plus an in-group prefix sum. Entries are
3 bytes (cost and text length columns); texts sit in entry order so a node's candidates share a compressed
block. Pinyin texts are coded as **per-syllable ranks** (the i-th character's rank in the i-th syllable's
character table, almost always one byte), about 30% of UTF-8 once compressed. The pinyin lexicon is 43 MB raw
(73 MB in the old format).

### 2.1 分块压缩与免解压 / Block compression without extraction（`.wvz`）

中文：所有数据文件（词库、搭配模型、简繁表、表情表）在打包时切成 16 KiB 的块，每块独立 brotli 压缩，
得到 `.wvz`（WVPK 格式，见 `weave-dict/src/blob.rs`）。APK 内这些文件**不压缩存放**，内核按
「APK 路径 + 偏移」直接读取，用到哪块才解压哪块，解压结果放进容量固定的 LRU 缓存（每个文件默认 12 MB，
低内存设备 6 MB；系统内存紧张时清空）。因此：

- 手机上不再解压出一份完整文件，**装机占用 ≈ APK 大小**，首次启动也不用等拷贝；
- 全部数据在 APK 内共 24 MB（旧方案 APK 内 45 MB、解压后再占 100 MB）；
- 桌面与测试仍可直接 mmap 原始文件；两种来源给出的结果逐字相同（`JniTest.packedSourcesInsideOneFile`）。

实测（1000 句评测集，桌面）：原始文件每句 9.2 ms CPU，分块压缩 + 12 MB 缓存 16.0 ms；准确率完全一致。
块大小 16 KiB 是在 16/32/64 KiB 中实测命中率与单块解压耗时最好的；共享字典实测无收益，未采用。

English: every data file is cut into 16 KiB blocks, each brotli-compressed on its own (`.wvz`, WVPK format,
`weave-dict/src/blob.rs`). The APK stores them uncompressed; the engine reads them by APK path + offset and
decodes blocks on first touch into a bounded LRU cache (12 MB per file by default, 6 MB on low-RAM devices,
dropped under memory pressure). Nothing is extracted on the device — the footprint is about the APK size —
and all data takes 24 MB inside the APK (45 MB compressed plus 100 MB extracted before). Desktop and tests can
still mmap the raw files, with identical results. Measured on 1000 sentences (desktop): 9.2 ms CPU per
sentence raw, 16.0 ms packed with a 12 MB cache, identical accuracy. 16 KiB blocks measured best of
16/32/64 KiB; a shared dictionary brought no gain and is not used.

## 3. 解码 / Decoding

1. **音节图 / Syllable graph** — 全拼、双拼、九键各自把按键切成所有可能的音节边，带惩罚：
   模糊音、常见错拼纠正（zhogn→zhong）、简拼、末尾不完整音节；「悬空」简拼（字母本可属于完整音节，
   如 `wang` 里的 `g`）直接删去。*Full pinyin, shuangpin and T9 each build the same kind of graph.*
2. **词图 / Lattice** — 从每个起点在音节图与系统词库、专业词库（至多 15 个，含云端热词）、用户词库几棵前缀树上同步深搜，
   同一个词取最低 cost；大音节集合（简拼）按子节点
   扫描位图，避免上百次二分查找。*DFS over the graph and both tries; large sets scan children via a bitset.*
3. **整句 / Sentence** — 束搜索（束宽 6）。每个词边界加上字符搭配模型的分数（见 §4），用户二元组奖励常用搭配；
   同时输出次优整句作为第 2 候选。*Beam search with collocation scores and user-bigram bonuses; the runner-up
   sentence is offered second.*
4. **候选 / Candidates** — 整句、次优整句、以 0 开头的词（覆盖越长越靠前），中英混输时插入英文词，
   其后插入表情联想；繁体输出在最后一步转换。
5. **触点纠错 / Tap-neighbour correction** — 触屏按在两键交界附近时，键盘把另一侧的字母与贴近度一并交给内核；
   换成邻键才能组成音节时补一条纠正边（惩罚 600–1800，原拼写已是音节时再加 1200）。模拟评测中，1.2% 的按键落到邻键时，
   整句首选从 57.0% 回到 72.6%，准确点击时不变。*Border taps carry the neighbour letter; the graph gets a correction
   edge when the neighbour spells a syllable. Simulated 1.2% slips: 57.0% → 72.6% top-1, unchanged for clean taps.*
   **自动纠错 / Auto-correction** — 全拼再解一次带纠错边的图（相邻字母颠倒、漏一个字母、多一个字母；只从用完整音节走到的
   位置猜），纠错读法比正常读法好出一截才采用：正常读法读不出来、要靠句中简拼或原样按键时门槛 500，句中出现零声母音节时
   5000，否则不纠错。预编辑显示纠正后的拼写，`Snapshot.marks` 标出改动（swap / insert / replace / delete），界面标红。
   模拟评测（每句一个错）：颠倒 4.7% → 56.8%，多打 0.4% → 63.0%，漏打 5.2% → 25.9%，干净输入不变（75.6%），
   计算量 +9%。*Full pinyin decodes a second graph with typo edges (swapped, missing, extra letter; only guessed from
   positions reached through whole syllables) and keeps it when clearly better — margin 500 when the plain reading fails
   or needs mid-input abbreviations / raw keys, 5000 when a zero-initial syllable appears mid-input, no correction
   otherwise. The preedit shows the fixed spelling with `Snapshot.marks`, drawn in red. One typo per sentence: swap
   4.7% → 56.8%, extra 0.4% → 63.0%, missing 5.2% → 25.9%; clean input unchanged; +9% instructions.*
6. **特殊候选 / Special candidates** — `v` + 数字（大写金额、中文数字、千分位）、`v` + 算式（结果），
   `rq` / `sj` / `xq`（日期、时间、星期，插在首选之后）。*`v` numerals and arithmetic, date/time shortcuts.*

## 4. 字符搭配模型 / Collocation model（`.wvg`）

中文：由万象 RIME-LMDG（CC BY 4.0，字符级 n-gram，6550 万键）在构建时剪枝为 2~3 字搭配（约 430 万键，20.6 MB），
以「按首字分组的有序表」存放。评分口径与 octagram 一致：每个词边界未命中 −12，命中强搭配为 `ln w − 12`，
弱搭配为 `ln w − 24`，再乘权重 λ = 0.25。只在两个「干净」跨度（完整音节、无简拼）之间使用，
否则模型会奖励把完整音节拆成简拼。

English: RIME-LMDG (CC BY 4.0, 65.5 M character n-grams) is pruned at build time to 2–3 character
collocations (≈4.3 M keys, 20.6 MB). Scoring follows octagram (miss −12, strong `ln w − 12`, weak `ln w − 24`)
scaled by λ = 0.25, and is applied only between clean spans.

## 5. 用户学习 / Learning

中文：只追加日志（`W/D/B` 三种行），进程随时被杀不丢数据；启动时回放，过长时压缩。
选过的系统词在该读音下提升排名（次数与近期度），多段选择拼出的整体自动成为新词，相邻词对记为用户二元组。
密码框和 `IME_FLAG_NO_PERSONALIZED_LEARNING` 输入框关闭学习。

English: append-only log replayed at start and compacted when large. Chosen words are promoted by count and
recency, multi-step selections become new words, and adjacent pairs become user bigrams. Learning is off in
password and no-personalized-learning fields.

### 5.1 衰减、撤销与连续造词 / Decay, undo and chaining

中文：次数按逻辑时钟减半（半衰期 20000 步，约一周的高强度输入），久不用的词慢慢回到原位；二元组同样衰减。
上屏后 1.5 秒内、中间没有其他输入时的退格视为选错，撤销这次学到的词与搭配。两次上屏间隔不超过 2.5 秒且中间没有
标点等直接输入时，两段合起来记一次，第二次出现才成为用户词。压缩日志时清掉衰减殆尽的记录，并限制在 2 万词、3 万对。
English: counts halve every 20 000 ticks; a backspace within 1.5 s of a commit undoes what it learned; two commits
within 2.5 s form a phrase that is learned the second time; compaction prunes decayed records and caps the sizes.

### 5.2 联想 / Next-word prediction

中文：上屏后（没有组合中的输入）在候选栏给出下一个词，分档排序：①用户二元组 ②接续表——词库里常用的 3–6 字长词按
前 1–4 字记下剩余部分（`follow.wvz`，0.47 MB，`followgen` 生成），用上文末尾 2–4 字查 ③字符搭配模型给出的下一个字，
接成词库确有的词（按字的读音在词库里查）④只凭最后一个字的接续。选中联想词会上屏并学习这对搭配，接着再联想；
打字、退格、空格、回车、标点都会收起。评测（`cargo run --release --example predict -- --eval`，400 句，词边界处）：
前 8 个里命中 9.6%，每处平均省 0.116 个字，约 1.4 ms/次（桌面）。
English: after a commit the candidate bar shows next words in tiers — user bigrams, the follow table (the rest of
common lexicon phrases keyed by their first 1–4 chars), collocation-model continuations checked against the lexicon,
then single-char-context continuations. Picking one commits it, learns the pair and predicts again.

## 6. 专业词库与云端热词 / Domain packs and cloud hot words

中文：专业词库由 `data/packs.sh` 可复现构建（万象领域词表 CC BY 4.0 与 THUOCL MIT，后者按基础词库最长匹配注音），
与基础词库同一尺度计分（`dictgen --total`）并整体靠后 800（`--bias`），装上后不会挤掉常用词、选过即由学习提升；
14 个包共约 1.4 MB，全部装上时整句评测 75.4%（−0.2）。云端热词默认关闭：从公开热词仓库每天最多下载一次
`hotwords.tsv` 与 Ed25519 签名，内核用内置公钥验签、去掉过期词后作为扩展词库 `cloud` 挂上（靠后 300），只下载不上传。
English: domain packs are built reproducibly by `data/packs.sh`, scored on the base scale and 800 behind it; all 14
together cost 0.2 points on the benchmark. Cloud hot words (off by default) are downloaded at most daily with an
Ed25519 signature checked against the built-in key, and attached as the extra lexicon `cloud`.

## 7. 织文互联 / WeaveLink

中文：同一局域网内的设备用 mDNS（`_weavelink._tcp`，默认端口 47811）互相发现。配对时电脑显示 6 位配对码与二维码，
SPAKE2 把配对码变成强密钥作为 Noise XXpsk3 的预共享密钥（只能在线猜，截获的握手无法离线穷举），双方记下对方静态公钥；
之后用 Noise XX 连接并核对公钥。消息：文字（剪贴板或直接发送）、文件（60 KB 分块、SHA-256 校验、文件名清洗）。
宿主通过 JSON 命令与事件驱动（见 `core/weave-link/src/lib.rs`）。

局域网及可达的远程地址沿用 TCP；跨网直传单独开启 UDP 端点，同一个 socket 做 STUN、双向打洞及 QUIC 传输。
双方手动交换 5 分钟有效的连接码（候选地址、临时 TLS 证书、设备 ID、配对码），仍经 SPAKE2 + Noise 核对身份。
QUIC 处理丢包、重传、排序与流控。没有数据中继或自动信令；打洞失败明确报错，绝不转为文件中转。
实现与验证边界见 [互联与 NAT 穿透](research/09-link-nat-traversal.md)。
English: mDNS discovery; pairing turns the 6-digit code into a PSK with SPAKE2 for Noise XXpsk3 and pins static keys;
connections use Noise XX with the pinned keys. Text and chunked, checksummed files; hosts drive it with JSON.

## 8. 插件宿主 / Plugin host

详见 [`plugin-host.md`](plugin-host.md)。安全边界：manifest 声明的网络白名单（重定向逐跳复查）、
每条消息 3 亿条 Lua 指令预算、每实例 64 MiB 内存、`.xipk` 单文件 64 MiB / 合计 256 MiB / 4096 条目、
无 `io`/`debug`/`package` 库、正式版日志不含插件原文。
*See `plugin-host.md`. Limits: manifest host allow-list re-checked on every redirect, 300 M instructions per
message, 64 MiB per instance, package caps, no io/debug/package, redacted release logs.*

## 9. 评测 / Benchmarks

整句评测集：`data/eval/sentences.tsv`（1000 条原创句子，CC0）。运行：
*Sentence set: 1000 original sentences (CC0). Run:*

```bash
cd core && cargo run --release --example eval -- --data ../data/build \
  --eval ../data/eval/sentences.tsv --gram ../data/build/grammar.wvg [--schema pinyin|xiaohe|t9]
```

| 配置 Config | 首选整句 Top-1 | 前三 Top-3 | 字准确率 Char acc |
|---|---|---|---|
| 全拼，仅词频 / pinyin, unigram only | 67.2% | 72.9% | 93.68% |
| 全拼 + 搭配模型 / + collocation model | 71.7% | 75.5% | 94.68% |
| 全拼 + 搭配模型 + 扩展词库（当前默认）/ + extended vocabulary (default) | **75.6%** | **79.6%** | **95.84%** |
| 小鹤双拼（默认配置）/ Xiaohe (default) | 75.8% | 79.7% | 95.88% |
| 九键（默认配置）/ T9 (default) | 48.8% | 55.5% | 85.56% |

中文：整句逐键输入的总耗时（M 系列 Mac，含搭配模型）：全拼约 13 ms/句（< 1 ms/键），双拼约 3 ms/句，
九键约 53 ms/句。关键优化：删除「悬空」简拼边（CPU 降约 5 倍、准确率不变）、简拼位图扫描、字 id 直查表。
真机数据待测。
English: total time to type a sentence key by key on an M-series Mac (with the model): pinyin ~13 ms
(< 1 ms per key), shuangpin ~3 ms, T9 ~53 ms. Key optimisations: dropping dangling abbreviation edges (~5× less
CPU, same accuracy), bitset child scans, O(1) char ids. Device numbers are pending.
