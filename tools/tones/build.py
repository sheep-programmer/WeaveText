#!/usr/bin/env python3
"""生成 core/weave-engine/data/tones.tsv：单字的带声调读音（来自 pypinyin 打包的 pinyin-data，MIT）加上 overrides.tsv 里的多音词覆盖表。
只用单字数据，不用 pypinyin 的词组表（其中一部分优化自 CC-CEDICT，CC BY-SA，与本项目的许可不兼容地混合不妥）。
Builds core/weave-engine/data/tones.tsv: per-char toned readings (pinyin-data shipped with pypinyin, MIT) plus the
polyphonic-word overrides in overrides.tsv. Only the per-char data is used, not pypinyin's phrase table (partly refined
from CC-CEDICT, CC BY-SA).

用法 / Usage:  pip install pypinyin==0.55.0 && python3 tools/tones/build.py
"""
import pathlib, sys
from pypinyin.pinyin_dict import pinyin_dict

here = pathlib.Path(__file__).parent
out = here.parent.parent / "core/weave-engine/data/tones.tsv"
lines = [
    "# 单字读音：字<TAB>读音（常用读音在前，逗号分隔）；以 @ 开头的是多音词覆盖表。由 tools/tones/build.py 生成，勿手改。",
    "# Per-char readings: char<TAB>readings (most common first); lines starting with @ are word overrides. Generated; do not edit.",
]
count = 0
for cp in sorted(pinyin_dict):
    if not 0x4E00 <= cp <= 0x9FFF:
        continue
    readings = [r for r in pinyin_dict[cp].split(",") if r]
    if readings:
        lines.append(f"{chr(cp)}\t{','.join(readings)}")
        count += 1
words = 0
for raw in (here / "overrides.tsv").read_text(encoding="utf8").splitlines():
    if not raw.strip() or raw.startswith("#"):
        continue
    word, reading = raw.split("\t")
    assert len(word) == len(reading.split()), f"syllable count mismatch: {raw}"
    lines.append(f"@{word}\t{reading}")
    words += 1
out.write_text("\n".join(lines) + "\n", encoding="utf8")
print(f"{count} chars, {words} words, {out.stat().st_size} bytes -> {out}", file=sys.stderr)
