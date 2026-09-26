#!/usr/bin/env bash
# 构建词库二进制 / Build compiled dictionaries.
# 源数据（只读，不入库）放在 .ref/，缺失时自动浅克隆。许可证见 docs/THIRD_PARTY.md：
#   拼音、英文：万象拼音 rime_wanxiang（CC BY 4.0）  五笔86：rime-wubi（LGPL-3.0，独立数据文件）
# Sources live in .ref/ (git-ignored), shallow-cloned on demand. Licenses: docs/THIRD_PARTY.md.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REF="$ROOT/.ref"
OUT="${1:-$ROOT/data/build}"
mkdir -p "$REF" "$OUT"
clone() { [ -d "$REF/$2" ] || git clone -q --depth 1 "https://github.com/$1" "$REF/$2"; }
clone amzxyz/rime_wanxiang rime_wanxiang
clone rime/rime-wubi rime-wubi
CARGO="${CARGO:-$HOME/.cargo/bin/cargo}"
"$CARGO" build -q --release -p weave-dict --manifest-path "$ROOT/core/Cargo.toml" --bins
DICTGEN="$ROOT/core/target/release/dictgen"
WX="$REF/rime_wanxiang/dicts"
stamp="$OUT/.stamp"
newest=$(find "$WX" "$REF/rime-wubi/wubi86.dict.yaml" "$DICTGEN" "$0" -newer "$stamp" 2>/dev/null | head -1 || true)
if [ -f "$stamp" ] && [ -z "$newest" ] && [ -f "$OUT/pinyin.wvl" ] && [ -f "$OUT/grammar.wvg" ]; then
  echo "dictionaries up to date"; exit 0
fi
# 基础字词 + 地名/人名/名人/诗词/联想长词/多音词：整句评测 71.7% → 75.6%。
# Base + places/names/people/poetry/long phrases/polyphones: sentence accuracy 71.7% → 75.6%.
"$DICTGEN" pinyin "$OUT/pinyin.wvl" "$WX/zi.dict.yaml" "$WX/jichu.dict.yaml" "$WX/diming.dict.yaml" \
  "$WX/renming.dict.yaml" "$WX/mingren.dict.yaml" "$WX/shici.dict.yaml" "$WX/lianxiang.dict.yaml" "$WX/duoyin.dict.yaml"
"$DICTGEN" letters "$OUT/wubi86.wvl" "$REF/rime-wubi/wubi86.dict.yaml"
"$DICTGEN" letters "$OUT/english.wvl" "$WX/en.dict.yaml"
# 字符搭配模型：万象 RIME-LMDG（CC BY 4.0）剪枝为 2~3 字搭配。 Collocation model from RIME-LMDG.
GRAM_SRC="$REF/wanxiang-lts-zh-hans.gram"
[ -f "$GRAM_SRC" ] || curl -fsSL -o "$GRAM_SRC" "https://github.com/amzxyz/RIME-LMDG/releases/download/LTS/wanxiang-lts-zh-hans.gram"
"$ROOT/core/target/release/gramdump" build "$GRAM_SRC" "$OUT/grammar.wvg" 99
cp "$REF/rime_wanxiang/opencc/wanxiang/STPhrases.txt" "$REF/rime_wanxiang/opencc/wanxiang/STCharacters.txt" "$REF/rime_wanxiang/opencc/wanxiang/emoji.txt" "$OUT/"
touch "$stamp"
