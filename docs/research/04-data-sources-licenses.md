# 04 · 词库/语料数据来源与许可证 / Data Sources & Licenses

> **规则**：每条结论都来自真实抓取到的 LICENSE / COPYING / README / 官方条款原文，附证据 URL；
> 凡未能抓到原文的一律标注「**未验证**」并进入禁用/待核清单。
> *Every license claim below was verified against fetched LICENSE/COPYING/README/terms text with a source URL.
> Anything not verified is explicitly marked and placed on the pending/blocked list.*

---

## 0. 三条硬规则下的判断流程 / Decision Procedure

**中文**
```
拿到一份数据 →
  ① 有没有明确的许可证文件？         没有 → 不用
  ② 许可证允许再分发吗？             GPL/AGPL → 只有当织文也开源同许可证时才可用
  ③ 允许商用吗？                     CC BY-NC / 「仅限研究」 → 不用
  ④ 上游数据来源清楚吗？             词库常见问题：许可证是 MIT，但内容是抄的 → 不用
  ⑤ 署名要求能满足吗？               能 → 记进 docs/THIRD_PARTY.md，随包附许可全文
```

**English**
```
For any dataset:
  ① Explicit license file?            No → don't use
  ② Redistribution allowed?           GPL/AGPL → only if WeaveText ships under the same license
  ③ Commercial use allowed?           CC BY-NC / "research only" → don't use
  ④ Upstream provenance clear?        Common trap: MIT wrapper around copied content → don't use
  ⑤ Attribution satisfiable?          Then record it in docs/THIRD_PARTY.md and ship the license text
```

**一个必须先决定的问题 / A decision you must make first**

织文目前的仓库许可证策略直接影响可用数据范围：
- **若织文开源为 GPL-3.0**：可直接使用 rime-ice（GPL-3.0）、rime-essay / luna / wubi / octagram-data（LGPL-3.0）——**但 rime-ice 的词源里仍有「腾讯词向量」「华宇野风」等许可不明项，仍需剔除**。
- **若织文使用 MIT/Apache-2.0 等宽松许可证**：**上述 Rime 生态数据全部不可直接使用**，只能用 THUOCL(MIT)、pinyin-data(MIT)、Unihan(Unicode License v3)、OpenCC(Apache-2.0)、RIME-LMDG(CC BY 4.0)、libime data(LGPL-2.1，需动态链接或数据独立分发+声明)。
- **本文档按「宽松许可证」假设给出推荐组合**（最保守，也最自由）。

*If WeaveText is GPL-3.0, most Rime data becomes reachable (minus the unclear upstreams). If it is MIT/Apache-2.0, the whole Rime data ecosystem is off-limits as is. The recommendations below assume a permissive license — the most conservative and most flexible position.*

---

## 1. 总表 / Master Table

| 数据源 Source | 仓库/官网 | 许可证 License | 再分发 | 商用 | 传染 Copyleft | 建议 Verdict |
|---|---|---|---|---|---|---|
| rime-ice 词库与配置 | [iDvel/rime-ice](https://github.com/iDvel/rime-ice) | **GPL-3.0 (only)** | 是 | 是 | **强（整个作品）** | ❌ 宽松许可下不可用 |
| └ 华宇野风系统词库（rime-ice base 组成） | 论坛帖 | **无许可证** | — | — | — | ❌ |
| └ 腾讯词向量（rime-ice base/tencent 组成） | [ai.tencent.com](https://ai.tencent.com/ailab/nlp/zh/download.html) | **未验证**（页面为 JS） | 存疑 | 存疑 | — | ❌ 按不可用处理 |
| └ 现代汉语常用词表（gist） | gist | **未验证** | 存疑 | 存疑 | — | ❌ |
| └ 北语 25 亿字字频表（8105 字表用） | 北语页面 | **未验证** | 存疑 | 存疑 | — | ⚠️ 需核实 |
| rime-essay 八股文词表 | [rime/rime-essay](https://github.com/rime/rime-essay) | **LGPL-3.0** | 是 | 是 | 弱（LGPL） | ⚠️ 谨慎（需声明+可替换） |
| rime-octagram-data 语法模型 | [lotem/rime-octagram-data](https://github.com/lotem/rime-octagram-data) | **LGPL-3.0** | 是 | 是 | 弱（LGPL） | ⚠️ 谨慎 |
| rime-luna-pinyin 单字表 | [rime/rime-luna-pinyin](https://github.com/rime/rime-luna-pinyin) | **LGPL-3.0** | 是 | 是 | 弱 | ⚠️ 谨慎 |
| rime-wubi 五笔86 码表 | [rime/rime-wubi](https://github.com/rime/rime-wubi) | **LGPL-3.0** | 是 | 是 | 弱 | ⚠️ 谨慎 |
| 万象 rime_wanxiang | [amzxyz/rime-wanxiang](https://github.com/amzxyz/rime-wanxiang) | **CC BY 4.0** | 是 | 是 | 无（需署名） | ✅ 可用（需评估语料） |
| 万象 RIME-LMDG 语法模型 | [amzxyz/RIME-LMDG](https://github.com/amzxyz/RIME-LMDG) | **CC BY 4.0** | 是 | 是 | 无（需署名） | ✅ 推荐（当前最佳开放中文 IME n-gram） |
| libime 拼音词典 + 语言模型 | [fcitx/libime](https://github.com/fcitx/libime) `data/` | **LGPL-2.1-or-later**（REUSE.toml 明确声明） | 是 | 是 | 弱（LGPL） | ⚠️ 谨慎（数据独立分发 + 声明） |
| THUOCL 清华开放词库 | [THUNLP/THUOCL](https://github.com/THUNLP/THUOCL) | **MIT** | 是 | 是 | 无 | ✅ **推荐** |
| pinyin-data（汉字注音） | [mozillazg/pinyin-data](https://github.com/mozillazg/pinyin-data) | **MIT** | 是 | 是 | 无 | ✅ **推荐**（`zdic.txt` 源自汉典，见 §5） |
| Unihan Database | [unicode.org](https://www.unicode.org/Public/) | **Unicode License v3** | 是 | 是 | 无 | ✅ **推荐** |
| OpenCC 简繁转换 | [BYVoid/OpenCC](https://github.com/BYVoid/OpenCC) | **Apache-2.0** | 是 | 是 | 无 | ✅ **推荐** |
| CC-CEDICT | [cc-cedict.org](https://cc-cedict.org/wiki/) | **CC BY-SA 3.0** | 是 | 是 | **强（SA 传染）** | ⚠️ 作为独立数据可，改动后须同许可 |
| 维基百科/维基词典 dump | [Wikimedia 服务条款](https://foundation.wikimedia.org/wiki/Policy:Terms_of_Use) | **CC BY-SA 4.0 + GFDL**（双许可，取其一） | 是 | **是**（条款明确允许） | **强（SA）** | ⚠️ 训练语料可用，产物需评估 |
| jieba 分词/词频 | [fxsjy/jieba](https://github.com/fxsjy/jieba) | **MIT** | 是 | 是 | 无 | ✅ 代码可用；**词典数据来源需再核** |
| SCOWL / en-wl 英文词表 | [en-wl/wordlist](https://github.com/en-wl/wordlist) | **BSD 兼容**（README 原文） | 是 | 是 | 无 | ✅ **推荐（英文词表）** |
| dwyl/english-words | [dwyl/english-words](https://github.com/dwyl/english-words) | **Unlicense（公有领域）** | 是 | 是 | 无 | ✅ 可用（词表质量一般） |
| first20hours 英文万词表 | [first20hours](https://github.com/first20hours) | 源数据来自 **LDC 语料** | **存疑** | **存疑** | — | ❌ 不用 |
| LCCC 中文语料（清华） | thu-coai | **未验证**（LICENSE 抓取 404） | 存疑 | 存疑 | — | ❌ 待核 |
| SUBTLEX-CH 词频 | UGent 页面 | **未验证** | 存疑 | 存疑 | — | ⚠️ 待核 |
| 思源黑体 / Noto Sans CJK | Noto 项目 / Adobe | **OFL-1.1**（另有 Apache-2.0 版本） | 是 | 是 | 无（但**改名字体须改名**） | ✅ **推荐** |

---

## 2. 关键证据 / Key Evidence

### 2.1 rime-ice = GPL-3.0 (only)
README §许可证原文：`GPL-3.0 (only) License.`（<https://github.com/iDvel/rime-ice#许可证>）；
仓库 `LICENSE` 为 GPLv3 全文。「(only)」意味着**不能升级到 GPL-4，也不能降级**。

**为什么它不能用于宽松许可的织文**
- GPL-3.0 是**强 copyleft**：把 GPL 数据编入并随 App 分发，通常会被认定为「基于该作品的整体」，从而要求**整个 App 以 GPL-3.0 发布**。
- 更严重的是**词源本身不干净**。`cn_dicts/base.dict.yaml` 头部原文列出：
  ```
  # - [华宇野风系统词库](http://bbs.pinyin.thunisoft.com/forum.php?mod=viewthread&tid=30049)
  # - [清华大学开源词库](https://github.com/thunlp/THUOCL)
  # - [现代汉语常用词表](https://gist.github.com/indiejoseph/eae09c673460aa0b56db)
  # - [腾讯词向量](https://ai.tencent.com/ailab/nlp/zh/download.html)，补充大量上述词典没有收录的常用两字词
  # - 参考《现代汉语词典》《通用规范汉字字典》《现代汉语规范词典》《同义词词林》
  ```
  「华宇野风系统词库」来自论坛帖（无许可证）；「腾讯词向量」是腾讯 AI Lab 的研究数据（需授权）；「参考《现代汉语词典》」指向**受版权保护的商业词典**。**即使织文愿意 GPL-3.0，这些上游也不能用。**
- `cn_dicts/tencent.dict.yaml` 有 **981,283 行**，是整个词库的最大来源；`base.dict.yaml` 557,954 行。也就是说 rime-ice 的「好用」，很大程度建立在许可不明的数据上。

**English**
rime-ice is GPL-3.0 (only), a strong copyleft. Worse, its own headers list upstreams with no license (华宇野风 forum table), research-only terms (Tencent word vectors) and copyrighted dictionaries (现代汉语词典). The largest single source, `tencent.dict.yaml`, has 981,283 lines. Even a GPL-3.0 WeaveText could not use these safely.

### 2.2 libime 数据 = LGPL-2.1-or-later（REUSE 明确）
`REUSE.toml`（<https://github.com/fcitx/libime/blob/master/REUSE.toml>）原文：
```toml
[[annotations]]
path = "data/lm_sc.**.tar.**"
SPDX-FileCopyrightText = "2022~2026 CSSlayer <wengxt@gmail.com>"
SPDX-License-Identifier = "LGPL-2.1-or-later"

[[annotations]]
path = "data/dict-**.tar.**"
SPDX-FileCopyrightText = ["2002-2005 Yuking <yuking_net@sohu.com>", "2014~2026 CSSlayer <wengxt@gmail.com>"]
SPDX-License-Identifier = "LGPL-2.1-or-later"
```
数据托管在 <https://download.fcitx-im.org/data/>（最新 `dict-20260907.tar.zst`、`lm_sc.arpa-20260629.tar.zst`）。
**LGPL 的实务要求**：若以**独立数据文件**形式随包分发、并保留版权声明与许可全文，通常不触发「衍生作品」问题；但**若把数据编译进二进制并静态链接代码**，则需要评估。**织文建议：把 libime 数据当作「可替换的独立资源包」处理，并在 THIRD_PARTY.md 声明。**

### 2.3 万象 = CC BY 4.0（仅需署名，无传染）
`amzxyz/rime_wanxiang` 与 `amzxyz/RIME-LMDG` 的 `LICENSE` 均为 **Creative Commons Attribution 4.0 International** 全文；README 徽章亦标 `License: CC BY 4.0`。
**注意**：CC BY 4.0 **不传染**，商用与再分发都可以，只要署名。但 RIME-LMDG 声称模型由 32GB 多领域语料训练而成，**语料构成未逐项披露**（含「公众号、百科词条、新闻报道、歌词、诗词」等，其中报刊/歌词很可能有版权）。**「模型文件是 CC BY」不等于「训练语料合法」**——这是当前法律灰区，建议：① 优先使用它，但 ② 在 THIRD_PARTY.md 中如实记录来源与风险，③ 保留未来替换为自训模型的能力。

### 2.4 CC-CEDICT = CC BY-SA 3.0（**SA 传染**）
<https://cc-cedict.org/wiki/> 原文：*"CC-CEDICT is licensed under a Creative Commons Attribution-Share Alike 3.0 License"*。
- 单独作为词典数据分发没问题（署名 + 同许可）。
- **但 SA 的传染性比 GPL 更模糊**：把 CC-CEDICT 的词条**合并进**织文自己的词库后，该合并词库在多数解释下需以 CC BY-SA 3.0 发布。若织文要闭源或宽松许可，**不要把它并入主词库**，只能作为**独立可选词包**。
- 更麻烦的是**它同时是 rime-essay、rime-luna-pinyin 的上游**，所以「LGPL 的 Rime 词表」内部其实还套着一层 CC BY-SA。**这就是为什么 §1 中 Rime 系数据全部标「谨慎」。**

### 2.5 Unihan = Unicode License v3（宽松）
<https://www.unicode.org/license.txt> 原文：*"Permission is hereby granted, free of charge, to any person obtaining a copy of data files … to deal in the Data Files or Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, and/or sell copies … provided that either (a) this copyright and permission notice appear with all copies … or (b) this copyright and permission notice appear in associated Documentation."*
→ **可商用、可再分发，只需保留声明**。这是织文**汉字基础数据**最安全的选择（`Unihan_Readings.txt` 的 `kMandarin` 提供拼音，`kTotalStrokes` 提供笔画，`kRSUnicode` 提供部首）。

### 2.6 pinyin-data = MIT，但注意 `zdic.txt`
`mozillazg/pinyin-data` 的 `LICENSE` 为 MIT 全文（Copyright (c) 2016 mozillazg）。
**但**该仓库包含多个来源的注音文件，其中 `zdic.txt` 的数据取自**汉典**（`zdic.net`）——汉典网站内容**有版权声明**。rime-ice 的 `41448.dict.yaml` 头部也明确写「音来自汉典 … https://github.com/mozillazg/pinyin-data/ > zdic.txt」。
**做法**：优先使用 `Unihan_Readings.txt` 与 `kMandarin` 的注音，**`zdic.txt` 只作为「查漏」的内部参考，不随包分发**。

### 2.7 字体 = OFL-1.1
思源黑体 / Noto Sans CJK 采用 **SIL Open Font License 1.1**：可自由使用、修改、随 App 分发（含商用），**但有两条限制**：① 修改后不得继续使用保留字体名（Reserved Font Name）；② 字体本身不能单独售卖。
**做法**：直接使用未修改的 Noto Sans CJK SC 子集（只保留 GB2312 + 常用符号）可把体积从 ~20MB 压到 ~3-5MB。

### 2.8 维基百科语料 = CC BY-SA 4.0 + GFDL，**允许商用**
Wikimedia 服务条款原文：*"you agree to license it under: Creative Commons Attribution-ShareAlike 4.0 International License ('CC BY-SA 4.0'), and GNU Free Documentation License ('GFDL')… Please note that these licenses do allow commercial uses of your contributions, as long as such uses are compliant with the terms of the respective licenses."*
→ **训练 n-gram 模型用它是可以的**（需署名 + 衍生文本同许可）。但 SA 是否传染到「模型权重」尚无定论，**建议把语料来源与处理脚本一并记录**，并优先考虑只用它训练、把「模型」作为独立资源分发。

---

## 3. copyleft 传染风险速查 / Copyleft Risk Cheat-Sheet

| 织文许可证 | 可直接用的数据 | 不可用 | 替代方案 |
|---|---|---|---|
| **MIT / Apache-2.0（推荐）** | THUOCL、pinyin-data、Unihan、OpenCC、RIME-LMDG(CC BY)、SCOWL、Noto(OFL) | rime-ice、rime-essay、luna、wubi、octagram-data、libime data、CC-CEDICT(并入时) | 自建词库（THUOCL + 语料自训 n-gram）；LGPL 数据只能作为**独立可替换资源包** |
| **LGPL-2.1/3.0** | 上述全部 + libime data + rime-essay/luna/wubi/octagram-data（作为独立数据包） | rime-ice 中许可不明部分 | 剔除不明词源 |
| **GPL-3.0** | 上述全部 + rime-ice（仍需剔除腾讯词向量/华宇野风） | 无额外限制 | — |

**实务建议 / Practical advice**
1. **仓库许可证建议 MIT 或 Apache-2.0**（与 `jni`、`mlua`、`memmap2`、`fst` 等依赖一致，便于社区采用）。
2. **把「数据」与「代码」彻底分开**：`data/` 下的东西是**可替换资源包**，各自的许可证独立声明在 `docs/THIRD_PARTY.md`；代码仓库本身不受数据许可证影响（这是 LGPL/CC BY-SA 数据的常见合规做法）。
3. **保留「替换能力」**：词库格式设计上支持**多词库叠加**（像 libime 的 `TrieDictionary` 那样），这样某个词库因许可证问题被替换时不影响引擎。

---

## 4. 推荐组合 / Recommended Stack

**中文**

| 层 | 推荐 | 许可证 | 理由 |
|---|---|---|---|
| 汉字基础属性 | **Unihan**（`kMandarin`/`kTotalStrokes`/`kRSUnicode`） | Unicode License v3 | 最权威、最宽松 |
| 汉字注音补充 | **mozillazg/pinyin-data**（除 `zdic.txt`） | MIT | 覆盖多音字与异读 |
| 单字表 | 《通用规范汉字表》8105 字（自行按 Unihan 注音） | 字表本身为国家标准，字音取 Unihan | 避免 rime-ice 的北语字频问题 |
| 多字词 | **THUOCL**（各领域词表） + 自建（从 OpenCC 词表、Unihan 词组规则生成） | MIT | 唯一可商用的高质量开放中文词表 |
| 词频 | **自训**：维基百科 dump + 开放新闻语料（逐项记录许可证） | CC BY-SA 4.0（需署名） | 无法用商业 IME 词频 |
| n-gram 语言模型 | **RIME-LMDG**（万象，CC BY 4.0）；备选自训 KenLM/ARPA | CC BY 4.0 | 当前质量最好、许可最清晰的开放中文 IME 模型 |
| 简繁转换 | **OpenCC** | Apache-2.0 | 事实标准 |
| 五笔86 码表 | **自建**（86 版规则确定，需自行整理并校验）；可选 rime-wubi 作为**独立 LGPL 词包** | 自建（干净） / LGPL-3.0 | 码表文件有版权，规则本身没有 |
| 英文词表 | **SCOWL（en-wl/wordlist）** 或 **dwyl/english-words** | BSD 兼容 / Unlicense | 均无 copyleft |
| 英文词频 | **自训**（维基百科英文 dump / 开放字幕语料） | 视来源 | 不用 first20hours 英文万词表（LDC 来源） |
| 字体 | **Noto Sans CJK SC**（子集化） | OFL-1.1 | 可商用、可嵌入 |
| 敏感词表 | **自建** | — | 来源必须自己可控 |

**English** — recommended stack: Unihan for character data, pinyin-data (minus `zdic.txt`) for readings, the national standard 8105 character list, THUOCL plus self-built multi-character words, self-trained frequencies from Wikipedia dumps and open news corpora (recording each source's license), RIME-LMDG (CC BY 4.0) as the n-gram model, OpenCC for simplification, a self-built Wubi 86 table, SCOWL or english-words for English, Noto Sans CJK SC (OFL) subsetted for fonts, and a self-built sensitive-word list.

---

## 5. 禁用清单 / Blocked List

**绝对不用 / Never use**
1. **任何从商业输入法（各商业输入法）逆向、提取、导出的词库或码表**——侵犯著作权与商业秘密。
2. **商业在线百科 / 汉典 / 各种在线词典的抓取内容**（除非明确 CC 授权）。
3. **无许可证的论坛词库**（如「华宇野风系统词库」）。
4. **腾讯词向量**（Tencent AI Lab Embedding）——除非获得腾讯书面授权；当前按不可用处理。
5. **first20hours 英文万词表**——来源于 LDC 分发的 Web 1T 万亿词语料，LDC 许可不允许再分发。
6. **《现代汉语词典》《同义词词林》等商业辞书**——rime-ice 只在注释中「参考」，织文不得引用其内容。
7. **任何标注「仅供学习/研究使用」的语料**（多数中文大模型语料属于此类）。
8. **CC BY-NC / CC BY-ND 数据**（ND 禁止改编，而词库必然需要清洗与改写）。

**待核实后才可考虑 / Verify before use**
- LCCC、SUBTLEX-CH、北语字频表、腾讯之外的各家词向量、任何 HuggingFace 上的中文语料（逐个看 LICENSE 与「训练语料是否允许再分发模型」）。

---

## 6. 署名与登记做法 / Attribution & Registration

**中文**
1. **`docs/THIRD_PARTY.md` 每条至少含**：名称、版本/日期、来源 URL、许可证 SPDX ID、是否修改、版权声明原文、在织文中的使用位置（哪个文件/哪个资源包）。
   模板：
   ```markdown
   ## Unihan Database (Unicode 16.0)
   - 来源 / Source: https://www.unicode.org/Public/16.0.0/ucd/Unihan.zip
   - 许可证 / License: Unicode License V3 (https://www.unicode.org/license.txt)
   - 版权 / Copyright: © 1991-2026 Unicode, Inc.
   - 修改 / Modified: 是（提取 kMandarin 字段，转换为 UTF-8 TSV）
   - 用途 / Used in: data/unihan/char_pinyin.tsv → weave-dict 构建期
   ```
2. **App 内「开源许可」页**：Android 上用 `androidx.preference` 或 Compose 页面列出全部第三方许可证全文（离线打包，不联网）。
3. **不修改、不删除上游许可证文件**：每个数据包目录下保留原始 `LICENSE`。
4. **OFL 字体**：保留 `OFL.txt`，若子集化（subsetting）视为修改，需**在文档中说明已修改**，且**不得使用 Reserved Font Name**（子集化时改名，如 `Noto Sans CJK SC Subset` 是不允许的，应换一个不含保留名的名字或保留原名但按 OFL 要求说明）。
   > 实务做法：**OFL 允许保留原名**（只要不修改字形），子集化只删字符不改字形，通常可保留原名，但仍建议在 NOTICE 中说明。

**English** — record name, version/date, source URL, SPDX id, modification status, copyright notice and where it is used; ship an offline "open-source licenses" screen; keep upstream LICENSE files untouched; for OFL fonts keep `OFL.txt` and document subsetting.

---

## 7. 出处汇总 / Sources

- rime-ice（GPL-3.0 only，README §许可证）: <https://github.com/iDvel/rime-ice> · 本地只读副本 `.ref/rime-ice/README.md` 第 461-463 行；词源见 `cn_dicts/base.dict.yaml` 头部
- rime-essay / rime-luna-pinyin / rime-wubi / rime-octagram-data（LGPL-3.0）: <https://github.com/rime/rime-essay> · <https://github.com/rime/rime-luna-pinyin> · <https://github.com/rime/rime-wubi> · <https://github.com/lotem/rime-octagram-data>（各自 `LICENSE`）
- libime 数据许可（REUSE.toml，LGPL-2.1-or-later）: <https://github.com/fcitx/libime/blob/master/REUSE.toml> · 数据托管 <https://download.fcitx-im.org/data/>
- 万象 / RIME-LMDG（CC BY 4.0）: <https://github.com/amzxyz/rime-wanxiang/blob/master/LICENSE> · <https://github.com/amzxyz/RIME-LMDG/blob/master/LICENSE>
- THUOCL（MIT）: <https://github.com/THUNLP/THUOCL/blob/master/LICENSE>
- pinyin-data（MIT）: <https://github.com/mozillazg/pinyin-data/blob/master/LICENSE>
- Unihan（Unicode License V3）: <https://www.unicode.org/license.txt>
- OpenCC（Apache-2.0）: <https://github.com/BYVoid/OpenCC/blob/master/LICENSE>
- CC-CEDICT（CC BY-SA 3.0）: <https://cc-cedict.org/wiki/>
- Wikimedia 服务条款（CC BY-SA 4.0 + GFDL，允许商用）: <https://foundation.wikimedia.org/wiki/Policy:Terms_of_Use>
- jieba（MIT）: <https://github.com/fxsjy/jieba/blob/master/LICENSE>
- SCOWL / en-wl wordlist（BSD 兼容）: <https://github.com/en-wl/wordlist>（README 原文 "derived from many sources under a BSD compatible license"）
- dwyl/english-words（Unlicense）: <https://github.com/dwyl/english-words/blob/master/LICENSE.md>
- first20hours 英文万词表（LDC 来源）: <https://github.com/first20hours>
- librime（BSD-3-Clause）: <https://github.com/rime/librime/blob/master/LICENSE>
- libpinyin（GPL-3.0）: <https://github.com/libpinyin/libpinyin/blob/main/COPYING>
- Trime（GPL-3.0）: <https://github.com/osfans/trime/blob/develop/LICENSE>
- HeliBoard（GPL-3.0，仓库已迁移至 HeliBorg/HeliBoard）: <https://github.com/HeliBorg/HeliBoard/blob/main/LICENSE>
- fcitx5-android（LGPL-2.1）: <https://github.com/fcitx5-android/fcitx5-android/blob/master/LICENSE>
- FlorisBoard / AnySoftKeyboard（Apache-2.0）: <https://github.com/florisboard/florisboard/blob/main/LICENSE> · <https://github.com/AnySoftKeyboard/AnySoftKeyboard/blob/master/LICENSE>
- sunpinyin（LGPL + CDDL 双许可）: <https://github.com/sunpinyin/sunpinyin/blob/master/COPYING>（原文 "Refer to LGPL.LICENSE and OPENSOLARIS.LICENSE."）
- Rust crate 许可证: crates.io API（`marisa-rs` = LGPL-2.1-or-later；`yada`/`daachorse`/`memmap2`/`jni` = MIT OR Apache-2.0；`fst` = Unlicense/MIT；`cedarwood` = BSD-2-Clause；`mlua` = MIT；`zstd` = BSD-3-Clause）
