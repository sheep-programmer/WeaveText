#!/usr/bin/env python3
"""Cangjie 5 code table → "字<TAB>码<TAB>权重" lines for dictgen letters.

Source: Jackchows/Cangjie5 (MIT), Cangjie5.txt, rows of "字<TAB>码[<TAB>标注]" with a single character.
Scope: only characters listed in Wanxiang's zi dictionary (CC BY 4.0), the same character set as the pinyin lexicon.
Weight: the largest reading weight of the character there.
Usage: make_cangjie.py <Cangjie5.txt> <zi.dict.yaml> <out.tsv>
"""
import re
import sys


def char_weights(path):
    """字 → 最大权重，取自 zi.dict.yaml 的「字<TAB>读音<TAB>权重」行。"""
    weights = {}
    body = False
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("..."):
                body = True
                continue
            if not body:
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 3:
                continue
            try:
                w = int(parts[2])
            except ValueError:
                continue
            if w > weights.get(parts[0], -1):
                weights[parts[0]] = w
    return weights


CODE = re.compile(r"^[a-z]{1,5}$")


def main():
    table, zi, out = sys.argv[1:4]
    weights = char_weights(zi)
    seen = set()
    rows = []
    with open(table, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 2 or len(parts[0]) != 1 or not CODE.match(parts[1]):
                continue
            char, code = parts[0], parts[1]
            if char not in weights or (char, code) in seen:
                continue
            seen.add((char, code))
            rows.append((char, code, weights[char]))
    # 同码的字按权重从高到低，dictgen 保留出现顺序。 Same code: higher weight first.
    rows.sort(key=lambda r: (r[1], -r[2]))
    with open(out, "w", encoding="utf-8") as f:
        for char, code, w in rows:
            f.write(f"{char}\t{code}\t{w}\n")
    print(f"{len(rows)} rows", file=sys.stderr)


if __name__ == "__main__":
    main()
