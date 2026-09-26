# 整句评测集 / Sentence evaluation set

## 目的 / Purpose

中文：本目录提供**整句转换准确率**基准。评测时给定无声调拼音，检查输入法首选整句是否等于原句。
英文：This directory holds the benchmark for pinyin-to-sentence accuracy. Given toneless pinyin,
the engine's top sentence candidate is compared against the reference sentence.

## 来源 / Provenance

中文：全部 1000 条句子均为为本项目**原创编写**，不取自任何书籍、新闻、网站或语料库，也不使用任何
商业输入法的数据。拼音由编写者按句中实际读音逐字标注。本目录内容以 CC0 发布，可自由使用。
英文：All 1000 sentences were written from scratch for this project. Nothing is copied from books,
news, websites or corpora, and no data from commercial IMEs is used. Pinyin was annotated
character by character according to the reading used in the sentence. The contents are released
under CC0.

## 文件 / Files

- `sentences.tsv` — 评测集本体，每行 `句子<TAB>拼音`。 One sentence per line, `sentence<TAB>pinyin`.
- `check.py` — 自检脚本。 Self-check script.

## 格式 / Format

中文：

- 句子只含汉字，**不带标点**，数字用汉字书写（如「三点半」）。
- 拼音为**无声调**，音节之间用**一个空格**分隔，标点不写入拼音。
- `ü` 写作 `v`：`nv lv lve nve`。
- 汉字个数与音节个数一一对应，儿化的「儿」写作独立音节 `er`。
- 「一」「不」按本调标注，即 `yi`、`bu`。
- 多音字按句中实际读音标注，例如「银行 yin hang」「行走 xing zou」「重要 zhong yao」「重新 chong xin」。

English:

- Sentences contain Chinese characters only, no punctuation; numbers are spelled out in Chinese.
- Pinyin is toneless, syllables separated by a single space, no punctuation.
- `ü` is written `v`: `nv lv lve nve`.
- Character count equals syllable count; the retroflex suffix is a separate `er` syllable.
- `一` and `不` keep their citation tone: `yi`, `bu`.
- Polyphonic characters use the reading of the sentence, e.g. `yin hang`, `xing zou`, `zhong yao`,
  `chong xin`.

## 类别与数量 / Categories and counts

| 类别 Category | 条数 Count |
| --- | ---: |
| 日常聊天 daily chat | 300 |
| 工作办公 work and office | 150 |
| 生活服务（购物/出行/餐饮/医疗）everyday services | 150 |
| 新闻时事 news style | 100 |
| 科技互联网 technology and internet | 100 |
| 教育学习 education and study | 60 |
| 成语俗语与书面语 idioms and written style | 60 |
| 人名地名机构名 names of people, places and organizations | 40 |
| 数字与时间 numbers and time | 40 |
| 合计 total | 1000 |

句长分布 / Length distribution：2–4 字 150 条，5–8 字 350 条，9–14 字 350 条，15–24 字 150 条
（15% / 35% / 35% / 15%）。

## 自检 / Self-check

中文：脚本从 `core/weave-dict/src/syllable.rs` 读取 `SYLLABLES` 表，逐行校验格式、音节合法性、
汉字数与音节数是否相等、句子是否重复，并核对类别配额与句长分布。

```
python3 data/eval/check.py
```

全部通过时退出码为 0，输出各类别条数、句长分布与音节表大小。

英文：The script reads the `SYLLABLES` table from `core/weave-dict/src/syllable.rs` and validates
each line: format, legal syllables, character/syllable count equality, duplicate sentences, plus
category quotas and length distribution. Exit code 0 means everything passed.

## 许可证 / License

中文：本目录下的数据与脚本以 **CC0 1.0**（公共领域贡献）发布，随仓库分发，可任意使用、修改与再分发。
英文：The data and scripts in this directory are released under **CC0 1.0** (public domain
dedication) and ship with the repository; use, modify and redistribute freely.
