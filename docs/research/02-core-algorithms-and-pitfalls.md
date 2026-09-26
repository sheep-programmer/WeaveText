# 02 · 内核算法、数据结构与已知坑 / Core Algorithms, Data Structures and Pitfalls

> 所有源码引用均来自 librime（BSD-3-Clause）与 libime（LGPL-2.1-or-later）的公开源码，**只描述思路，不复制代码**。
> *All source references are to public librime / libime code; ideas only, no code is copied.*

---

## 1. 数据流总览 / Pipeline Overview

**中文**

```
按键 → Processor（ascii/标点/选择/编辑）
     → Segmentor（把输入串切成若干「段」，贴标签：abc / number / punct / …）
     → 音节图 SyllableGraph（拼音：输入串 → 所有合法的音节切分路径）
     → Translator（把每段翻译成候选词，查词典得到词图）
     → 组句/解码（Lattice + Viterbi/beam + n-gram 打分）
     → Filter（简繁、去重、Emoji、用户词注入）
     → 候选列表（+ 预编辑串 composing text 写回 InputConnection）
```

**English**

```
key → Processor (ascii / punctuation / selector / editor)
    → Segmentor (split input into tagged segments: abc / number / punct / …)
    → SyllableGraph (pinyin: all legal syllable split paths)
    → Translator (segment → candidate words; dictionary lookup builds the word graph)
    → Sentence composition / decoding (lattice + Viterbi/beam + n-gram scoring)
    → Filters (simplification, dedup, emoji, user-word injection)
    → Candidate list (+ composing text written back to InputConnection)
```

**织文的选择 / WeaveText decision**：把这套五级流水**合并成一条「图 → Lattice → 打分」管线**。Rime 的五级组件化（processor/segmentor/translator/filter）是为「YAML 配置可编程」服务的，代价是每次按键都要走 20+ 个虚函数调用与多次字符串拷贝。织文不需要运行期可编程，用**编译期固定的管线 + 特化分支**换性能。

*Rime's five-stage component pipeline exists to make YAML programmability possible; the price is 20+ virtual calls and several string copies per keystroke. WeaveText does not need runtime programmability — a compile-time fixed pipeline with specialized branches buys the performance back.*

---

## 2. 音节切分歧义 / Syllable Segmentation Ambiguity

### 2.1 问题本质 / The problem

**中文**
`xian` 可以是 `xi'an`（西安）也可以是 `xian`（先/现/县…）；`fangan` 可以是 `fang'an`（方案）或 `fan'gan`（反感）。这不是「切分」问题而是**所有切分都要保留**的问题：正确做法是构建**有向无环图（DAG）**，把每条合法切分都作为路径保留，最后**由语言模型决定哪条路径更合理**。提前切分 = 提前丢答案。

librime 的 [`algo/syllabifier.h`](https://github.com/rime/librime/blob/master/src/rime/algo/syllabifier.h) 正是这么做的：

```cpp
struct EdgeProperties : SpellingProperties {
  EdgeProperties(SpellingProperties sup) : SpellingProperties(sup) {};
  EdgeProperties() = default;
  // 切分歧義編碼段的起始位置
  set<size_t> ambiguous_source_positions;
};
struct SyllableGraph {
  size_t input_length = 0;
  size_t interpreted_length = 0;
  VertexMap vertices;
  EdgeMap edges;
  SpellingIndices indices;
};
```

`ambiguous_source_positions` 显式记录「这个音节是被多个起点共享的」——即歧义点。libime 的 [`SegmentGraph`](https://github.com/fcitx/libime/blob/master/src/libime/core/segmentgraph.h) 是同一思路，并在 [`lattice.h`](https://github.com/fcitx/libime/blob/master/src/libime/core/lattice.h) 里给出了经典例子：

```
For example, pinyin, "xian" has three SegmentGraphNodes.
[0] ---- xian ----- [4]
  \- xi -[2] - an -/
```

**English**
`xian` may be `xi'an` or `xian`; `fangan` may be `fang'an` or `fan'gan`. This is not a "pick one" problem: build a **DAG keeping every legal split**, and let the language model choose. librime records ambiguity explicitly (`ambiguous_source_positions`); libime's `SegmentGraph`/`Lattice` does the same. **Early segmentation = early information loss.**

### 2.2 具体做法 / Concrete design

1. **音节表驱动的前缀扫描**：对输入串每个起点 `i`，用 `max_key_length` 限制向后扫描长度（librime prism 里就有 `uint32_t max_key_length`），产出所有 `[i, j)` 合法音节边。
2. **隔音符号 `'` 作为显式边界**：librime `Syllabifier` 构造参数里有 `delimiters_`；libime 的词典文本格式要求音节之间用 `'` 分隔（`ni'hui`、`xiao'qi'e`）。**用户输入 `'` 时必须强制断开该处**，同时该字符本身**不上屏**。
3. **简拼（首字母）作为「部分音节」**：librime 用 `enable_completion_` 开关，把单字母视为「某个音节的合法前缀」，于是 `xian` 可由 `x` + … 组合。**坑**：开启后 `xian` 会额外产生 `x`(声母) + `ian` 等路径，候选数暴涨、排序变差；libime 用独立 flag 区分（`Enable matching partial shuangpin`）。**建议**：简拼只在「完整音节数 ≥ 2 且首字母不是独立音节」时才作为**低权重补充分支**加入，而不是禁用或全开。
4. **模糊音不是「改写输入」而是「图的额外边」**：librime 的做法（`prism::SpellingDescriptor` 带 `Credibility` 与 `is_correction` 位）值得照抄——模糊音边带 **credibility（置信度）**，参与打分但权重低于精确边。**不要**在输入层把 `zh` 替换成 `z`，否则用户输入 `zh` 时你会丢失精确匹配。
   *Fuzzy pinyin should be extra edges with a credibility weight (librime's `SpellingDescriptor{ Credibility, type, bit30 is_correction }`), never a pre-rewrite of the input string.*
5. **拼写运算（Spelling Algebra）的取舍**：librime 有完整的 `algo/algebra.cc` + `calculus.cc`，能用 `xform/derive/fuzz/abbrev` 描述任意拼写变体（方言、注音、双拼统一处理）。**优点**：表达力强，一套机制支撑双拼/模糊音/简拼/方言。**缺点**：在部署期展开成巨大的 spelling map（`prism::SpellingMap`），**部署时间与内存随规则数非线性增长**，是 Rime「部署慢」的元凶之一。**织文建议**：用 Rust 泛型/宏在**编译期**实现等价的映射（`trait SpellingVariant`），运行期只查表，不做规则解释。
6. **双拼映射**：数学上是「2 键 → 1 音节」的定长编码。libime 的方案文件格式（[`shuangpin-profile-format_zh.md`](https://github.com/fcitx/libime/blob/master/src/libime/pinyin/shuangpin-profile-format_zh.md)）分五节，可直接作为织文的方案文件格式参考：
   ```text
   [方案] / [零声母标识] (=O*) / [声母] (ch=I …) / [韵母] (ai=L …) / [音节]
   ```
   **坑**：双拼的零声母（如 `a`、`an`、`ang`、`e`、`o`）与「不完整输入」会产生大量无法解析的键序列；必须**允许半成品状态**（`Enable matching partial shuangpin`），否则用户打第一个键时界面会闪空。另外，小鹤/自然码/微软等方案对 `;`/`v`/`ue` 的处理各不相同，**方案文件必须能表达「键 → 多个韵母」的一对多**。
7. **九键（T9）歧义是「一对多」的极端情形**：数字键 `2` 对应 `abc`。不要把它当成「先枚举字母串再跑全拼」——那会产生指数级路径。**正确做法**：把九键看作**音节集合上的模糊匹配**，在音节图的边上直接挂「哪些音节可由该数字序列产生」。Rime 生态的九键方案全靠 YAML 硬编码映射，可维护性极差，Trime 至今仍有 [#1894 九宫格布局不能正常显示](https://github.com/osfans/trime/issues/1894)、[#1005 无法使用 luna 九宫格](https://github.com/osfans/trime/issues/1005)、[#1464 希望原生支持中文九键](https://github.com/osfans/trime/issues/1464) 这类问题。**织文应把九键做成一等公民**（数字键 → 音节候选集合的预计算索引，构建期生成）。
   *9-key is not "enumerate letters then run full pinyin" (exponential). Treat it as fuzzy matching over the syllable set; precompute, at build time, the mapping from digit sequences to candidate syllables.*

---

## 3. 词图与解码 / Word Graph and Decoding

### 3.1 两条路线 / Two designs

| 维度 | librime | libime |
|---|---|---|
| 结构 | 音节图 → 每段 `script_translator` 查棱镜+词表 → 候选词条 | `SegmentGraph` → `Lattice`（叠加图）→ `Decoder` |
| 解码 | 组句交给插件（`librime-octagram` 八股文语法） | 内置 beam search，`beamSizeDefault = 20` |
| N-best | sentence composer 产出 top-1 句子 + 逐段候选 | `decode(lattice, graph, nbest, …, beamSize)` 显式 n-best |
| 打分 | 词频（`table.bin` weight）+ 可选 n-gram | LM log-prob + 词条 cost + 用户模型 |

**中文要点**
- **librime 把「组句」外置成插件**，本身只做逐段翻译。好处是核心小；坏处是**没有语法模型时整句质量断崖式下跌**（用户装完 rime-ice 发现「不打语法模型就很笨」，见 [rime-ice README「我是否需要语法模型」](https://github.com/iDvel/rime-ice#常见问题)）。
- **libime 内置 beam search**，是更稳的工程选择：beam=20 在手机上单次解码通常在**毫秒级**，且 n-best 可直接喂给候选栏（第二、第三候选可以是一个完整短句，这正是「整句输入」体验的关键）。
- **织文建议**：内置 beam search，`beam` 宽度**自适应**（输入短时 8、长句时 32），并保留 top-N 句子候选 + 末段词组候选的混合列表。

**English**
librime externalizes sentence composition into a plugin and has no grammar by default — sentence quality collapses without it (a recurring rime-ice FAQ). libime builds beam search in (`beamSizeDefault = 20`), giving explicit n-best. **WeaveText: built-in adaptive beam search (8 for short input, 32 for long sentences), mixing top-N sentence candidates with last-segment word candidates.**

### 3.2 n-gram 与平滑 / n-gram and smoothing

- **libime 用 KenLM**：`data/CMakeLists.txt` 显示 `lm_sc.arpa` 被 `slm_build_binary -s -a 22 -q 4 trie` 编译成 `zh_CN.lm`（trie 结构的二进制，`-a`/`-q` 为构建期的规模/量化参数），另生成 `zh_CN.lm.predict` 用于下一词预测。
- **librime 的八股文**：`lotem/rime-octagram-data`（**LGPL-3.0**），同样提供 trie 二进制模型。
- **万象 RIME-LMDG**：32GB 语料自训（**CC BY 4.0**），是当前质量最好且许可最宽松的公开中文输入法 n-gram 模型。
- **常见坑**：
  1. **ARPA 文本格式体积巨大**，必须先量化编译为 trie/FST；`KenLM` 的 `-a`/`-q` 参数直接决定模型体积与精度。
  2. **平滑与回退必须与词典解耦**：词典里的 weight 与 LM 的 log-prob 量纲不同，直接相加会出现「生僻词高频」或「常用词永不出现」。libime 用 `LanguageModel::unknown()` 惩罚未知词（`historybigram.h` 里明确有 `Set unknown probability penalty`）。
  3. **OOV（未登录词）处理是整句质量的关键**：单字必须永远作为兜底路径存在，且单字路径的 cost 要足够高但不至于被完全压制。
  4. **模型再分发风险**：n-gram 模型是从语料训练出来的，若语料含未授权文本，「模型」本身的法律地位在多数法域尚不明确。**织文建议只用许可证明确的语料自训，或直接采用 CC BY 4.0 的 RIME-LMDG**。

### 3.3 用户词频学习与衰减 / User learning and decay

**这是 Rime 写得最精巧、也是最值得借鉴的一段。** librime [`algo/dynamics.h`](https://github.com/rime/librime/blob/master/src/rime/algo/dynamics.h) 全文只有两个公式：

```cpp
inline double formula_d(double d, double t, double da, double ta) {
  return d + da * exp((ta - t) / 200);
}
inline double formula_p(double s, double u, double t, double d) {
  const double kM = 1 / (1 - exp(-0.005));
  double m = s - (s - u) * pow((1 - exp(-t / 10000)), 10);
  return (d < 20) ? m + (0.5 - m) * (d / kM)
                  : m + (1 - m) * (pow(4, (d / kM)) - 1) / 3;
}
```

**中文解读**
- `formula_d`：用户词权重的**指数增长/衰减**。时间常数 200，即**半衰期 = 200·ln2 ≈ 138.6 个 tick**（[librime#1205](https://github.com/rime/librime/pull/1205) 的作者原话：`formula_d dee 的半衰期是 200*log(2) ≈ 138`）。
- `formula_p`：把「系统词频 `s`」与「用户词频 `u`」融合成最终显示权重，`d` 为用户输入次数。**关键设计**：`d < 20` 时用户词缓慢上升，`d ≥ 20` 后按 `4^(d/kM)` 加速——即**「用得多就快速提前，用得少就慢慢爬」**，避免误触导致某个词立刻霸榜。
- **陈旧词清理**：librime#1205 提出查询时**跳过权重极小的条目**（阈值 `1e-200` 相当于约 10 万次 tick 未使用，其个人词库中 3.08% 的条目会被清掉）。**这是「用户词库无限膨胀」的正解**——不是定期删，而是**按需惰性丢弃**。

**English**
`formula_d` is exponential growth/decay with time constant 200 → **half-life ≈ 138.6 ticks**. `formula_p` blends system frequency `s` with user frequency `u`, accelerating only after ~20 uses (`4^(d/kM)`), so a mis-tap cannot instantly promote a word. Stale entries are best handled **lazily**: skip entries below a threshold (1e-200 ≈ 100k unused ticks) at query time (librime#1205).

**已知坑 / Known pitfalls**
1. **userdb 用 LevelDB**（[`dict/level_db.cc`](https://github.com/rime/librime/blob/master/src/rime/dict/level_db.cc)），**查询是随机 IO**，每次按键都要查用户词 → 长输入时卡顿（[librime#510 输入较多字母时候导致卡顿](https://github.com/rime/librime/issues/510)）。librime 直到 [#1196 `perf(user_dict): in-memory sorted cache for user dictionary queries`](https://github.com/rime/librime/pull/1196) 才尝试加内存有序缓存。**织文必须一开始就把用户词全量放内存**（哪怕只有几万条，占用 < 2MB），落盘只做批量追加。
2. **配置与状态混在同一目录**（[librime#1169](https://github.com/rime/librime/issues/1169)）导致多前端冲突、同步困难。**织文：配置目录与状态目录从一开始就分离**（Android 侧对应 `filesDir` 与 `noBackupFilesDir`/独立子目录）。
3. **误学习**：英文、网址、一次性字符串容易被学进用户词。**必须**在写入前过滤（长度、字符集、是否来自 `TYPE_TEXT_VARIATION_PASSWORD`/`IME_FLAG_NO_PERSONALIZED_LEARNING` 的编辑框）。

---

## 4. 自动造词 / Auto-phrase Creation

**中文**
- **Rime 的做法**：整句上屏时把「按音节切开的连续词块」作为一个新词写入 userdb（受 `user_dict` 与最小词长/最大词长设置约束）。
- **坑 1 — 脏词**：用户输入一段乱码或敏感内容也会被学。**做法**：只在「由词典内词组成的组合」时才造词；对纯自由组合设上限。
- **坑 2 — 中英混输**：`zuogeDNAjiance`（rime-ice 明确支持的能力）会把 `DNA` 当作词块。**做法**：大写字母序列单独作为 `latin` 段处理，不参与造词。
- **坑 3 — 造词污染排序**：新造词初始权重必须**低于**系统词，靠 `formula_d` 自然爬升。
- **libime 的替代思路**：不改词典，而是用 `userlanguagemodel` 记录「词序列」的历史概率（[`core/userlanguagemodel.h`](https://github.com/fcitx/libime/blob/master/src/libime/core/userlanguagemodel.h)），**不增加词典条目**就能改善整句。**推荐织文采用这种「用户 n-gram + 用户词表」双轨：高频组合走 n-gram，真正的新词才进词表。**

**English**
Rime writes whole-syllable blocks into userdb on commit — risky (garbage learned, code-switching pollution, ranking pollution). libime instead improves whole sentences by recording word-sequence history in a `UserLanguageModel` **without adding dictionary entries**. **Recommendation: dual track — user n-gram for frequent combinations, user word list only for genuinely new words.**

---

## 5. 误触纠错 / Typo Correction

**中文**
- **相邻键交换**：librime `Syllabifier` 里有 `Transpose(SyllableGraph* graph)`，专门处理 `eh` ↔ `he` 这类邻键颠倒。
- **邻键替换**：libime 有独立的 [`PinyinCorrectionProfile`](https://github.com/fcitx/libime/blob/master/src/libime/pinyin/pinyincorrectionprofile.h)（since 1.1.7），基于 QWERTY 邻接表做「一键输错」的候选扩展：`'w' may be corrected to 'q','e'`，并把换位分成内置 profile 与用户自定义映射。
- **坑**：
  1. 纠错是**候选爆炸**的主要来源。`xian` 加上邻键替换后路径数可翻 3-5 倍。**必须**给纠错边低 credibility，并把它**排在精确匹配候选之后**（librime 的 `is_correction` 位就是干这个的）。
  2. **纠错不要与模糊音叠加**：`zh/z` 模糊 + `n/m` 邻键会互相干扰，产生大量垃圾候选。建议**同一位置最多替换一次**。
  3. 纠错需要**在音节图阶段做**，不能在候选阶段做（否则无法参与整句解码）。
- **Rust 实现提示**：`fst` crate 自带 `Levenshtein` 自动机（编辑距离匹配），可用于「整串容错字典查询」；但音节级纠错还是手写邻接表更快、更可控。

**English**
Two mechanisms: adjacent-key transpose (librime `Transpose`) and adjacent-key substitution with a QWERTY neighbourhood profile (libime `PinyinCorrectionProfile`). Pitfalls: correction multiplies the search space and must carry low credibility, be ranked after exact matches, never stack with fuzzy pinyin at the same position, and must live in the syllable-graph stage so it can take part in sentence decoding.

---

## 6. 五笔 / Wubi 86

**中文**
Rime 用 `table_translator`（码表式），与拼音的 `script_translator`（罗马字式）是**两种完全不同的构造**——[RimeWithSchemata](https://github.com/rime/home/wiki/RimeWithSchemata) 明确说明：用码表方式写拼音会失去简拼/模糊音/智能语句；用罗马字方式写五笔会失去定长编码顶字上屏。

必须实现的四件事：
1. **四码唯一自动上屏**：输入 4 码后若唯一匹配则该字直接上屏，不再等空格。**坑**：必须排除「还有更长的词以这 4 码为前缀」的情形——即查询时要问「是否存在更长的候选」，否则会吞掉 5 码词。
2. **顶屏（顶字）**：已输入 4 码且有候选时，继续输入下一个字的第 1 码，应把当前候选上屏。**坑**：`z` 键与标点键要排除在顶屏触发之外。
3. **`z` 键拼音反查**：Rime 用 `reverse_lookup_translator` + `reverse.bin` 反向词典（`ReverseLookupDictionary`，见 [`reverse_lookup_dictionary.h`](https://github.com/rime/librime/blob/master/src/rime/dict/reverse_lookup_dictionary.h)）。**代价**：反查词典是**又一份全量索引**，显著增大体积。**织文建议**：反查只对**单字**建索引（不建词组），可把体积压到 1/5 以内。
4. **简码与重量**：rime-wubi 的 `wubi86.dict.yaml` 头部说明其词频来自某搜索引擎词频与极点五笔 6（[源码](https://github.com/rime/rime-wubi/blob/master/wubi86.dict.yaml)），**许可证 LGPL-3.0**；织文若要用五笔码表，需寻找许可证更宽松的开源码表或自行整理（五笔86 码表本身是「规则」而非「创作」，但**具体的码表文件**有版权）。

**English**
Wubi uses Rime's `table_translator` (code-table mode), fundamentally different from pinyin's `script_translator`. Four must-haves: (1) auto-commit on a unique 4-code match — while still checking for longer code-prefixed words; (2) top-of-screen push when typing the next character's first code, excluding `z` and punctuation; (3) `z`-key pinyin reverse lookup, which costs a second full index — index **single characters only** to cut size ~5×; (4) short codes and weights, whose data files are LGPL-3.0 in rime-wubi.

---

## 7. 词典二进制格式与加载 / Dictionary Binary Formats

### 7.1 三种格式对照 / Three formats

| 方案 | 读性能 | 体积 | 增量更新 | 构建耗时 | 复杂度 |
|---|---|---|---|---|---|
| librime: prism(darts) + table(字符串池) + reverse + LevelDB userdb | 高（mmap） | 中 | ❌ 无 | **高**（全量重编译） | 高 |
| libime: cedar DATrie（多词典叠加）+ KenLM trie + zstd 流 | 高 | 低（zstd） | 词典可叠加，但仍需重编 | 中 | 中 |
| 纯 FST（`fst` crate / OpenFST） | 高 | **最低**（共享前缀后缀） | ❌ 只读 | 中（需有序输入） | 低 |

### 7.2 启动慢的真正原因（重要）/ The real cause of slow startup

**中文**
[librime#627](https://github.com/rime/librime/pull/627) 给出了决定性证据：**即使词库已经编译完成，每次启动仍会调用 `compute_dict_file_checksum`，对词库源文件做全量 CRC32 读取**，这是 profile 里最耗时的函数；作者在树莓派等低性能设备上尤其明显，因此改为「用文件大小 + 修改时间判断是否需重建」。

结合 Trime 的实测（[trime#713](https://github.com/osfans/trime/issues/713)，含逐段耗时日志）：

| 阶段 | 耗时 |
|---|---|
| `Rime::init()` 初始化 | **2.6 s** |
| `set_notification_handler` | 0.6 s |
| `Config` 主题参数载入 | 0.9 s |
| `check(full_check)` | 不稳定，**最高 5 s** |
| 合计（换键盘/重装/崩溃后首次弹出键盘） | **约 5–9 s** |

而 [trime#1169](https://github.com/osfans/trime/issues/1169) 记录：某版本开始**每次从其他键盘切回 Trime 都会强制重新部署**，每次约 3 秒。

**English**
The decisive evidence: librime#627 shows `compute_dict_file_checksum` (full CRC32 read of every dictionary source at startup) dominated the profile even when dictionaries were already built. Trime #713 measured init 2.6 s + 0.6 s + 0.9 s + up to 5 s `full_check`; trime#1169 documents forced redeploy (~3 s) on every keyboard switch.

**织文的硬性要求 / Hard requirements for WeaveText**
1. **运行期零校验**：不读源文件、不算 CRC、不做时间戳比较。词典文件带**版本号 + 构建指纹**，指纹不符才拒绝加载（而不是每次启动都算）。
2. **一切 mmap**：词典文件用 `memmap2` 映射，启动只做页表映射，不做反序列化。禁止启动期 `Vec::from(reader.read_to_end())` 式全量读入。
3. **用户数据与系统数据物理分离**：系统词典只读、可随包分发；用户数据是可写的小文件（目标 < 5 MB），启动时**异步**加载。
4. **可测量的预算**（写进 CI）：冷启动到键盘可见 ≤ 200 ms；词库加载 ≤ 30 ms；单次按键到候选刷新 ≤ 16 ms（1 帧）。

### 7.3 内存对比与预算 / Memory

**中文**
[trime#92](https://github.com/osfans/trime/issues/92) 的抱怨很典型：「3 个自定义方案是 KB 级，产生的 bin 都是 MB 级」，用户质问「一般输入法都是占 20MB 左右 RAM」。而 [fcitx5-android#532](https://github.com/fcitx5-android/fcitx5-android/issues/532) 更严重：「挂大于 30MB 的词库……打第一个键要反应很久」。

**关键洞察**：librime 的 `table.bin` **全部 mmap 进虚拟地址空间**，因此「词库 100MB」不等于「RSS 100MB」——只有被访问的页才会驻留。但如果**构建期把数据放在堆上（如 `HashMap<String, Vec<Entry>>`），就是真占 100MB 物理内存**。Android 上 IME 进程常年驻留，OOM 风险高。

**织文预算 / Budget**（建议写进设计文档）

| 项 | 目标 | 说明 |
|---|---|---|
| 系统词典文件 | ≤ 30 MB（含 n-gram） | FST/双数组 + zstd，只读 mmap |
| 进程常驻 RSS | ≤ 40 MB | 含键盘 View、字体、Lua 宿主 |
| 单次解码临时分配 | ≤ 256 KB | Lattice 节点池复用，禁止每键分配 |
| 用户数据 | ≤ 5 MB | 内存全量 + 追加日志 |

**English**
librime mmaps its whole `table.bin`, so a 100 MB dictionary does not mean 100 MB RSS — only touched pages resident. But building heap structures (`HashMap<String, Vec<Entry>>`) does cost real physical memory, which is fatal for a long-lived Android IME process. Budget: dictionary file ≤ 30 MB (read-only mmap), process RSS ≤ 40 MB, per-decode allocations ≤ 256 KB via a reused node pool, user data ≤ 5 MB.

### 7.4 推荐 Rust crate / Recommended Rust crates

| 用途 | 推荐 | 许可证 | 备注 |
|---|---|---|---|
| 只读有序映射/FST | [`fst`](https://crates.io/crates/fst) | Unlicense/MIT | 体积最小；内置 `Levenshtein` 自动机可做容错查询 |
| 可更新双数组 trie | [`yada`](https://crates.io/crates/yada) | MIT OR Apache-2.0 | 适合「构建期可增删、运行期只读」的用户词典 |
| 双数组 trie（cedar 移植） | [`cedarwood`](https://crates.io/crates/cedarwood) | BSD-2-Clause | 与 libime 的 DATrie 同源思路 |
| 多模式匹配 | [`daachorse`](https://crates.io/crates/daachorse) | MIT OR Apache-2.0 | Aho-Corasick，适合敏感词/简码前缀 |
| mmap | [`memmap2`](https://crates.io/crates/memmap2) | MIT OR Apache-2.0 | 跨平台，Android 上稳定 |
| 零拷贝反序列化 | [`rkyv`](https://crates.io/crates/rkyv) | MIT | 结构体直接读映射内存，替代手写 `OffsetPtr` |
| 压缩 | [`zstd`](https://crates.io/crates/zstd) | BSD-3-Clause | libime 同款；解压流式加载 |
| Lua 宿主 | [`mlua`](https://crates.io/crates/mlua) | MIT | 已定，vendored Lua 5.4 |
| JNI | [`jni`](https://crates.io/crates/jni) | MIT OR Apache-2.0 | 官方推荐 |
| 汉字→拼音（构建期） | [`pinyin`](https://crates.io/crates/pinyin) | MIT | 只用于**构建期**注音，不用于解码 |

> ⚠️ **不要在织文里用 `marisa-rs`**：crates.io 元数据显示其许可证为 **LGPL-2.1-or-later**，链接进 Rust 二进制会带来 copyleft 义务。用 `yada`/`cedarwood`/`fst` 替代。
> *Do not use `marisa-rs` (LGPL-2.1-or-later) — use `yada` / `cedarwood` / `fst` instead.*

---

## 8. 坑位速查表 / Pitfall Cheat-Sheet

| # | 坑 | 根因 | 织文对策 |
|---|---|---|---|
| 1 | 启动/切换键盘慢 3–9 s | 启动期全量 CRC32 + 全量配置解析 | 运行期零校验、mmap、配置惰性解析 |
| 2 | 改一个词要重新部署 | 词典是编译产物，无增量 | 只读系统词典 + 追加日志 |
| 3 | 长输入卡顿 | userdb(LevelDB) 随机 IO、多次字符串拷贝 | 用户词全内存、Lattice 池化 |
| 4 | 大词库首键卡死（>30MB） | 启动/首查期同步构建结构 | 构建期完成、运行期 mmap |
| 5 | 整句不准（不装语法模型） | 组句外置成插件 | 内置 beam search + 内置 n-gram |
| 6 | 候选爆炸 | 简拼/模糊/纠错叠加 | 各来源独立 credibility，分层排序 |
| 7 | 用户词库无限膨胀 | 无清理机制 | 惰性丢弃（阈值）+ 定期压缩 |
| 8 | 多前端同步冲突 | 配置与状态同目录 | 目录分离 + 版本化状态文件 |
| 9 | 九键体验差 | YAML 硬编码映射 | 数字序列→音节集合的构建期索引 |
| 10 | 五笔顶屏吞字 | 未检查「更长的码」 | 查询时同时看前缀延伸 |
| 11 | 拼写运算部署慢 | 规则运行期展开成巨大 spelling map | 编译期展开（Rust 宏/泛型） |
| 12 | Lua 插件内存泄漏 | 插件生命周期管理 | 宿主侧 session 回收 + 内存上限 |

---

## 9. 出处 / Sources

- librime `prism.h`（darts、SpellingDescriptor、Credibility、is_correction）: <https://github.com/rime/librime/blob/master/src/rime/dict/prism.h>
- librime `table.h` / `mapped_file.h` / `string_table.h`: <https://github.com/rime/librime/tree/master/src/rime/dict>
- librime `algo/syllabifier.h`（SyllableGraph、ambiguous_source_positions、Transpose）: <https://github.com/rime/librime/blob/master/src/rime/algo/syllabifier.h>
- librime `algo/dynamics.h`（formula_d / formula_p）: <https://github.com/rime/librime/blob/master/src/rime/algo/dynamics.h>
- librime `dict/level_db.cc`（userdb 用 LevelDB）: <https://github.com/rime/librime/blob/master/src/rime/dict/level_db.cc>
- librime `dict/reverse_lookup_dictionary.h`（反查词典）: <https://github.com/rime/librime/blob/master/src/rime/dict/reverse_lookup_dictionary.h>
- librime#627 启动慢 = `compute_dict_file_checksum`: <https://github.com/rime/librime/pull/627>
- librime#1196 user dict 内存有序缓存: <https://github.com/rime/librime/pull/1196>
- librime#1205 用户词惰性丢弃（半衰期 200·log2≈138）: <https://github.com/rime/librime/pull/1205>
- librime#1169 配置文件与状态文件分离: <https://github.com/rime/librime/issues/1169>
- librime#510 输入较多字母时卡顿: <https://github.com/rime/librime/issues/510>
- librime#1197 perf(prism) 减少临时字符串拷贝: <https://github.com/rime/librime/pull/1197>
- librime#405 librime-lua 内存泄漏: <https://github.com/rime/librime/issues/405>
- libime `decoder.h`（beamSizeDefault=20）、`lattice.h`（xian 例子）、`datrie.h`（cedar）: <https://github.com/fcitx/libime/tree/master/src/libime/core>
- libime `pinyincorrectionprofile.h`（邻键纠错）: <https://github.com/fcitx/libime/blob/master/src/libime/pinyin/pinyincorrectionprofile.h>
- libime `historybigram.h`（unknown penalty）: <https://github.com/fcitx/libime/blob/master/src/libime/core/historybigram.h>
- libime `data/CMakeLists.txt`（KenLM 量化参数 `-a 22 -q 4`）: <https://github.com/fcitx/libime/blob/master/data/CMakeLists.txt>
- libime 双拼方案格式: <https://github.com/fcitx/libime/blob/master/src/libime/pinyin/shuangpin-profile-format_zh.md>
- Trime#713 弹键盘耗时逐段分析: <https://github.com/osfans/trime/issues/713>
- Trime#1169 切键盘强制重新部署: <https://github.com/osfans/trime/issues/1169>
- Trime#92 RAM 与 bin 体积: <https://github.com/osfans/trime/issues/92>
- fcitx5-android#532 大词库首键卡顿: <https://github.com/fcitx5-android/fcitx5-android/issues/532>
- fcitx5-android#439 首次切中文卡顿: <https://github.com/fcitx5-android/fcitx5-android/issues/439>
- rime-wubi 码表与词频来源: <https://github.com/rime/rime-wubi/blob/master/wubi86.dict.yaml>
- marisa-rs 许可证（LGPL-2.1-or-later）: <https://crates.io/api/v1/crates/marisa-rs>
