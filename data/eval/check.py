#!/usr/bin/env python3
"""校验 data/eval/sentences.tsv 的格式、音节合法性与统计分布。

用法：
    python3 data/eval/check.py

逐条检查内容：
    1. 每行形如 `句子<TAB>拼音`，句子只含汉字，拼音只含小写字母与单个空格；
    2. 每个拼音音节都在 core/weave-dict/src/syllable.rs 的 SYLLABLES 表中（lue/nue 不是合法拼写）；
    3. 汉字个数等于音节个数；
    4. 句子不重复；
    5. 类别条数与配额一致；
    6. 全局句长分布为 2-4 字 150 条、5-8 字 350 条、9-14 字 350 条、15-24 字 150 条。

全部通过时退出码为 0，存在错误时为 1。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

EVAL_DIR = Path(__file__).resolve().parent
REPO_ROOT = EVAL_DIR.parent.parent
SENTENCES_TSV = EVAL_DIR / "sentences.tsv"
SYLLABLE_RS = REPO_ROOT / "core" / "weave-dict" / "src" / "syllable.rs"

# 类别按 sentences.tsv 中出现的先后顺序排列，配额之和即总条数。
CATEGORIES = (
    ("日常聊天", "daily chat", 300),
    ("工作办公", "work and office", 150),
    ("生活服务", "everyday services", 150),
    ("新闻时事", "news style", 100),
    ("科技互联网", "technology and internet", 100),
    ("教育学习", "education and study", 60),
    ("成语俗语与书面语", "idioms and written style", 60),
    ("人名地名机构名", "names of people places and organizations", 40),
    ("数字与时间", "numbers and time", 40),
)

# (句长区间标签, 最长汉字数, 条数)，按句长升序。
LENGTH_BUCKETS = (("2-4", 4, 150), ("5-8", 8, 350), ("9-14", 14, 350), ("15-24", 24, 150))

HANZI_RE = re.compile(r"[\u4e00-\u9fff]+")
PINYIN_RE = re.compile(r"[a-z]+(?: [a-z]+)*")
TOTAL = sum(count for _, _, count in CATEGORIES)


def load_syllables() -> set[str]:
    text = SYLLABLE_RS.read_text(encoding="utf-8")
    block = re.search(r"pub const SYLLABLES: &\[&str\] = &\[(.*?)\];", text, re.S)
    if block is None:
        raise SystemExit(f"无法解析 {SYLLABLE_RS} 中的 SYLLABLES 表")
    return set(re.findall(r'"([a-z]+)"', block.group(1)))


def read_rows(errors: list[str]) -> list[tuple[str, str]]:
    if not SENTENCES_TSV.exists():
        raise SystemExit(f"缺少文件 {SENTENCES_TSV}")
    rows: list[tuple[str, str]] = []
    for lineno, line in enumerate(SENTENCES_TSV.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            errors.append(f"第 {lineno} 行：空行")
            continue
        parts = line.split("\t")
        if len(parts) != 2:
            errors.append(f"第 {lineno} 行：应以一个制表符分隔句子与拼音，实际有 {len(parts) - 1} 个制表符")
            continue
        rows.append((parts[0], parts[1]))
    return rows


def bucket_index(length: int) -> int | None:
    for i, (_, limit, _) in enumerate(LENGTH_BUCKETS):
        if length <= limit:
            return i
    return None


def main() -> int:
    errors: list[str] = []
    warnings: list[str] = []
    syllables = load_syllables()
    rows = read_rows(errors)

    if len(rows) != TOTAL:
        errors.append(f"总条数应为 {TOTAL}，实际 {len(rows)}")

    lengths: list[int] = []
    seen_sentence: dict[str, int] = {}
    seen_pinyin: dict[str, int] = {}

    for lineno, (sentence, pinyin) in enumerate(rows, start=1):
        if HANZI_RE.fullmatch(sentence) is None:
            errors.append(f"第 {lineno} 行：句子只允许汉字，实际为「{sentence}」")
        if not pinyin or PINYIN_RE.fullmatch(pinyin) is None:
            errors.append(f"第 {lineno} 行：拼音只允许小写字母与单个空格，实际为「{pinyin}」")
            continue
        parts = pinyin.split(" ")
        illegal = sorted({p for p in parts if p not in syllables})
        if illegal:
            errors.append(f"第 {lineno} 行：音节不在 SYLLABLES 表中：{' '.join(illegal)}")
        if len(parts) != len(sentence):
            errors.append(f"第 {lineno} 行：汉字 {len(sentence)} 个、音节 {len(parts)} 个「{sentence} / {pinyin}」")
        lengths.append(len(sentence))
        if sentence in seen_sentence:
            errors.append(f"第 {lineno} 行：句子与第 {seen_sentence[sentence]} 行重复「{sentence}」")
        else:
            seen_sentence[sentence] = lineno
        if pinyin in seen_pinyin:
            warnings.append(f"第 {lineno} 行：拼音与第 {seen_pinyin[pinyin]} 行重复「{pinyin}」")
        else:
            seen_pinyin[pinyin] = lineno

    # 类别统计：按行序切分。
    print("类别统计：")
    offset = 0
    for name_cn, name_en, expected in CATEGORIES:
        chunk = lengths[offset : offset + expected]
        offset += expected
        counts = [0] * len(LENGTH_BUCKETS)
        for length in chunk:
            i = bucket_index(length)
            if i is None:
                errors.append(f"类别「{name_cn}」出现 {length} 字的句子，超出 24 字上限")
            else:
                counts[i] += 1
        if len(chunk) != expected:
            errors.append(f"类别「{name_cn}」应有 {expected} 条，实际 {len(chunk)} 条")
        span = " ".join(f"{label}字 {count}" for (label, _, _), count in zip(LENGTH_BUCKETS, counts))
        print(f"  {name_cn} / {name_en}：{len(chunk)} 条（{span}）")

    if offset != len(rows):
        errors.append(f"类别配额合计 {offset} 条，文件实际 {len(rows)} 条")

    print("句长分布：")
    global_counts = [0] * len(LENGTH_BUCKETS)
    for length in lengths:
        i = bucket_index(length)
        if i is not None:
            global_counts[i] += 1
    for i, (label, _, expected) in enumerate(LENGTH_BUCKETS):
        actual = global_counts[i]
        mark = "" if actual == expected else "  <- 与配额不符"
        print(f"  {label} 字：{actual} 条（应 {expected} 条）{mark}")
        if actual != expected:
            errors.append(f"{label} 字应有 {expected} 条，实际 {actual} 条")

    print(f"音节表：{len(syllables)} 个音节，来自 {SYLLABLE_RS.relative_to(REPO_ROOT)}")
    if warnings:
        print(f"提示：{len(warnings)} 条拼音重复")
        for w in warnings[:10]:
            print(f"  {w}")

    print(f"检查完成：{len(rows)} 条，{len(errors)} 个错误")
    for e in errors[:40]:
        print(f"  错误 {e}")
    if len(errors) > 40:
        print(f"  …… 另有 {len(errors) - 40} 个错误")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
