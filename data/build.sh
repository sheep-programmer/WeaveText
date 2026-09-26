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
# 源数据固定到具体版本，保证任何机器（含 CI）构建出的词库逐字相同；升级时改这里的提交与校验值。
# Sources are pinned so every machine (CI included) builds identical data; bump the commits/hashes to upgrade.
fetch_repo() { # <github repo> <dir> <commit>
  local dir="$REF/$2"
  if [ "$(git -C "$dir" rev-parse HEAD 2>/dev/null)" = "$3" ]; then return; fi
  [ -d "$dir/.git" ] || { rm -rf "$dir"; git init -q "$dir"; git -C "$dir" remote add origin "https://github.com/$1"; }
  git -C "$dir" fetch -q --depth 1 origin "$3"
  git -C "$dir" checkout -q --force FETCH_HEAD
}
fetch_repo amzxyz/rime_wanxiang rime_wanxiang 516b1bb66bdce1fd5785f5481c415f13ff736548
fetch_repo rime/rime-wubi rime-wubi 152a0d3f3efe40cae216d1e3b338242446848d07
# 手写模板：Make Me a Hanzi 的 graphics.txt（Arphic Public License，派生数据仍适用该许可，见 docs/licenses/）。
# Handwriting templates from Make Me a Hanzi's graphics.txt (Arphic Public License; see docs/licenses/).
fetch_repo skishore/makemeahanzi makemeahanzi bddc96d41bef78427ed0e034e9f7e31d71fd1b92
CARGO="${CARGO:-$HOME/.cargo/bin/cargo}"
"$CARGO" build -q --release -p weave-dict --features pack --manifest-path "$ROOT/core/Cargo.toml" --bins
DICTGEN="$ROOT/core/target/release/dictgen"
WX="$REF/rime_wanxiang/dicts"
stamp="$OUT/.stamp"
newest=$(find "$WX" "$REF/rime-wubi/wubi86.dict.yaml" "$REF/makemeahanzi/graphics.txt" "$DICTGEN" "$0" -newer "$stamp" 2>/dev/null | head -1 || true)
if [ -f "$stamp" ] && [ -z "$newest" ] && [ -f "$OUT/pinyin.wvz" ] && [ -f "$OUT/grammar.wvz" ] && [ -f "$OUT/hand.wvz" ]; then
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
GRAM_SHA=71bc2ef5bb0d6af519ede62ca7e85c6cefa9b6916a1a09390b6924e39fdd5059
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }
if [ ! -f "$GRAM_SRC" ] || [ "$(sha256 "$GRAM_SRC")" != "$GRAM_SHA" ]; then
  curl -fsSL --retry 3 -o "$GRAM_SRC.part" "https://github.com/amzxyz/RIME-LMDG/releases/download/LTS/wanxiang-lts-zh-hans.gram"
  [ "$(sha256 "$GRAM_SRC.part")" = "$GRAM_SHA" ] || { echo "grammar source changed upstream; update GRAM_SHA after review" >&2; exit 1; }
  mv "$GRAM_SRC.part" "$GRAM_SRC"
fi
"$ROOT/core/target/release/gramdump" build "$GRAM_SRC" "$OUT/grammar.wvg" 99
cp "$REF/rime_wanxiang/opencc/wanxiang/STPhrases.txt" "$REF/rime_wanxiang/opencc/wanxiang/STCharacters.txt" "$REF/rime_wanxiang/opencc/wanxiang/emoji.txt" "$OUT/"
# 分块压缩版（WVPK）：APK 内不压缩存放、由内核按需解压，手机上不再解压出第二份。
# Block-compressed copies (WVPK), stored uncompressed in the APK and decoded on demand by the engine.
"$ROOT/core/target/release/handgen" "$REF/makemeahanzi/graphics.txt" "$OUT/hand.wvh" "$WX/zi.dict.yaml"
PACK="$ROOT/core/target/release/wvpack"
for pair in pinyin:pinyin.wvl wubi86:wubi86.wvl english:english.wvl grammar:grammar.wvg \
    st_phrases:STPhrases.txt st_characters:STCharacters.txt emoji:emoji.txt hand:hand.wvh; do
  "$PACK" "$OUT/${pair#*:}" "$OUT/${pair%%:*}.wvz"
done
touch "$stamp"
