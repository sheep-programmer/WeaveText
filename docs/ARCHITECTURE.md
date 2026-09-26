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
│ weave-ffi     JNI 绑定：引擎句柄、快照编码、插件会话回调                 │
│ weave-engine  会话状态机 · 音节图 · 解码器 · 用户学习 · 码表 · 繁简/表情 │
│ weave-dict    音节表 · 前缀树词库 .wvl · 字符搭配模型 .wvg · 构建工具    │
│ weave-plugin  Lua 5.4 插件宿主：host.* API、网络白名单、配额、.xipk      │
└──────────────────────────────────────────────────────────────────────┘
```

中文：界面层只和 `InputController`、`VoiceHub` 打交道；内核是一个单线程状态机，每次按键后返回一份快照
（上屏文字、组合串、候选）。组合中的拼音**不写入编辑器**，只在确定时 `commitText`，避开各 App 对
composing 文本支持不一的兼容问题。

English: the UI talks only to `InputController` and `VoiceHub`. The engine is a single-threaded state
machine returning a snapshot (commit text, preedit, candidates) after every key. The preedit is never
written into the editor; only final text is committed, which avoids per-app composing-text quirks.

## 2. 词库格式 / Lexicon format（`.wvl` v3）

中文：一种格式同时服务拼音（符号 = 音节 ID）与五笔/英文（符号 = 字母 1..26）。前缀树按 BFS 顺序存放，
同一父节点的子节点连续且按符号升序，因此：

- 节点只存 8 字节：`sym | best | child_count | entry_count`（`best` = 子树最小代价，供补全做最佳优先搜索）；
- 首个子节点、首个词条的下标由「每 16 个节点一个检查点 + 组内前缀和」推出，不必逐节点存储；
- 词条 6 字节：`text_id | cost`，`cost = round(-ln p × 1000)`；文字去重后集中存放。

文件直接 `mmap`，启动时不解析、不校验全文件：加载时间 < 1 ms，内存按需分页。

English: one format for pinyin (symbol = syllable id) and Wubi/English (symbol = letter). The trie is stored
in BFS order with contiguous, sorted siblings, so nodes need only 8 bytes; child/entry offsets are derived
from a checkpoint every 16 nodes plus an in-group prefix sum. Entries are 6 bytes. Files are mmapped with no
load-time parsing (< 1 ms).

## 3. 解码 / Decoding

1. **音节图 / Syllable graph** — 全拼、双拼、九键各自把按键切成所有可能的音节边，带惩罚：
   模糊音、常见错拼纠正（zhogn→zhong）、简拼、末尾不完整音节；「悬空」简拼（字母本可属于完整音节，
   如 `wang` 里的 `g`）直接删去。*Full pinyin, shuangpin and T9 each build the same kind of graph.*
2. **词图 / Lattice** — 从每个起点在音节图与系统、用户两棵前缀树上同步深搜；大音节集合（简拼）按子节点
   扫描位图，避免上百次二分查找。*DFS over the graph and both tries; large sets scan children via a bitset.*
3. **整句 / Sentence** — 束搜索（束宽 6）。每个词边界加上字符搭配模型的分数（见 §4），用户二元组奖励常用搭配；
   同时输出次优整句作为第 2 候选。*Beam search with collocation scores and user-bigram bonuses; the runner-up
   sentence is offered second.*
4. **候选 / Candidates** — 整句、次优整句、以 0 开头的词（覆盖越长越靠前），中英混输时插入英文词，
   其后插入表情联想；繁体输出在最后一步转换。

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

## 6. 插件宿主 / Plugin host

详见 [`plugin-host.md`](plugin-host.md)。安全边界：manifest 声明的网络白名单（重定向逐跳复查）、
每条消息 3 亿条 Lua 指令预算、每实例 64 MiB 内存、`.xipk` 单文件 64 MiB / 合计 256 MiB / 4096 条目、
无 `io`/`debug`/`package` 库、正式版日志不含插件原文。
*See `plugin-host.md`. Limits: manifest host allow-list re-checked on every redirect, 300 M instructions per
message, 64 MiB per instance, package caps, no io/debug/package, redacted release logs.*

## 7. 评测 / Benchmarks

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
