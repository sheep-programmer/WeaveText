#!/usr/bin/env bash
# 构建可选的专业词库（每个领域一个独立文件，用户在应用里按需下载、随时卸载）。
# Build the optional domain dictionaries: one file per domain, downloaded and removed by the user in the app.
#
# 来源（固定版本，许可证见 docs/THIRD_PARTY.md）/ Sources (pinned; licenses in docs/THIRD_PARTY.md):
#   万象拼音 rime_wanxiang 的领域词表（CC BY 4.0，自带拼音）
#   THUOCL 清华开放中文词库（MIT，无拼音：按基础词库最长匹配注音）
#
# 计分：与基础词库同一尺度（--total），整体再靠后 800（--bias），领域词不会挤掉常用词；用户选过之后由学习提升。
# Scoring: same scale as the base (--total), then 800 behind (--bias), so domain words never push common words
# down; once the user picks one, learning promotes it.
#
# 输出 / Output: data/build/packs/<id>.wvz 与清单 packs.json（大小、条数、SHA-256、许可）。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REF="$ROOT/.ref"
OUT="${1:-$ROOT/data/build}/packs"
mkdir -p "$REF" "$OUT"
fetch_repo() { # <github repo> <dir> <commit>
  local dir="$REF/$2"
  if [ "$(git -C "$dir" rev-parse HEAD 2>/dev/null)" = "$3" ]; then return; fi
  [ -d "$dir/.git" ] || { rm -rf "$dir"; git init -q "$dir"; git -C "$dir" remote add origin "https://github.com/$1"; }
  git -C "$dir" fetch -q --depth 1 origin "$3"
  git -C "$dir" checkout -q --force FETCH_HEAD
}
sha256() { shasum -a 256 "$1" | cut -d' ' -f1; }
fetch_repo amzxyz/rime_wanxiang rime_wanxiang 516b1bb66bdce1fd5785f5481c415f13ff736548
fetch_repo thunlp/THUOCL THUOCL a30ce79d895d01ab5132a5c74c29703ff7efb4cc
CARGO="${CARGO:-$HOME/.cargo/bin/cargo}"
"$CARGO" build -q --release -p weave-dict --features pack --manifest-path "$ROOT/core/Cargo.toml" --bins
DICTGEN="$ROOT/core/target/release/dictgen"
PACK="$ROOT/core/target/release/wvpack"
WX="$REF/rime_wanxiang/dicts"
TH="$REF/THUOCL/data"
# 与 build.sh 的基础词库完全相同的源。 Exactly the base sources of build.sh.
BASE=("$WX/zi.dict.yaml" "$WX/jichu.dict.yaml" "$WX/diming.dict.yaml" "$WX/renming.dict.yaml" "$WX/mingren.dict.yaml"
  "$WX/shici.dict.yaml" "$WX/lianxiang.dict.yaml" "$WX/duoyin.dict.yaml")
TOTAL="$("$DICTGEN" total "${BASE[@]}")"
BIAS=800
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

thuocl() { # <name> → 注音后的 .dict.yaml 路径 / path of the annotated .dict.yaml
  "$DICTGEN" annotate "$TMP/thuocl_$1.dict.yaml" "$TH/THUOCL_$1.txt" "${BASE[@]}" >&2
  echo "$TMP/thuocl_$1.dict.yaml"
}

entries=()
pack() { # <id> <名称> <说明> <许可> <来源> <src>...
  local id="$1" name="$2" desc="$3" license="$4" source="$5"; shift 5
  "$DICTGEN" pinyin "$TMP/$id.wvl" "$@" --total "$TOTAL" --bias "$BIAS" 2>"$TMP/$id.log"
  local rows
  rows=$(grep -o '[0-9]* rows' "$TMP/$id.log" | awk '{s+=$1} END {print s+0}')
  local bad
  bad=$(grep -o '[0-9]* skipped' "$TMP/$id.log" | awk '{s+=$1} END {print s+0}')
  "$PACK" "$TMP/$id.wvl" "$OUT/$id.wvz" >/dev/null
  local bytes; bytes=$(wc -c < "$OUT/$id.wvz" | tr -d ' ')
  echo "$id: $rows words, $bad skipped, $bytes bytes" >&2
  entries+=("{\"id\":\"$id\",\"name\":\"$name\",\"description\":\"$desc\",\"license\":\"$license\",\"source\":\"$source\",\"words\":$rows,\"bytes\":$bytes,\"sha256\":\"$(sha256 "$OUT/$id.wvz")\"}")
}

WXS="万象拼音 rime_wanxiang"
THS="THUOCL 清华开放中文词库"
pack med "医学" "疾病、症状、解剖与诊疗术语" "CC BY 4.0 · MIT" "$WXS · $THS" "$WX/yixue.dict.yaml" "$(thuocl medical)"
pack drug "药品" "常用药品与药物名称" "CC BY 4.0" "$WXS" "$WX/yaopin.dict.yaml"
pack chem "化学化工" "化学物质、化工与材料术语" "CC BY 4.0" "$WXS" "$WX/huaxue.dict.yaml"
pack species "动植物" "动物、植物与物种名称" "CC BY 4.0 · MIT" "$WXS · $THS" "$WX/wuzhong.dict.yaml" "$(thuocl animal)"
pack it "IT 互联网" "编程、软件、硬件与互联网用语" "MIT" "$THS" "$(thuocl IT)"
pack finance "财经" "金融、证券、经济与企业用语" "MIT" "$THS" "$(thuocl caijing)"
pack law "法律" "法律法规与司法用语" "MIT" "$THS" "$(thuocl law)"
pack car "汽车" "车型、品牌与汽车术语" "MIT" "$THS" "$(thuocl car)"
pack places "地名" "国内外地名" "MIT" "$THS" "$(thuocl diming)"
pack culture "诗词成语" "古诗词与成语" "MIT" "$THS" "$(thuocl poem)" "$(thuocl chengyu)"
pack history "历史人物" "历史名人" "MIT" "$THS" "$(thuocl lishimingren)"
pack food "饮食" "菜名、食材与饮食用语" "MIT" "$THS" "$(thuocl food)"
pack celeb "影视艺人" "演员、歌手与艺人" "CC BY 4.0" "$WXS" "$WX/yiren.dict.yaml"
pack dialect "方言" "常见方言用字与用语" "CC BY 4.0" "$WXS" "$WX/fangyan.dict.yaml"

# 发布位置：仓库 Release「dict-packs-v1」的附件（内容变了就换新 tag，旧版本保持可下载）。
# Published as assets of the "dict-packs-v1" release; bump the tag whenever the contents change.
RELEASE="${PACKS_RELEASE:-dict-packs-v1}"
{
  echo "{\"version\":1,\"release\":\"$RELEASE\",\"base\":\"https://github.com/sheep-programmer/WeaveText/releases/download/$RELEASE/\",\"packs\":["
  (IFS=,; echo "${entries[*]}")
  echo ']}'
} > "$OUT/packs.json"
# 应用内的词库目录与这里的构建结果一致（SHA-256 相同）。 The in-app catalog matches these files exactly.
cp "$OUT/packs.json" "$ROOT/android/app/src/main/assets/dictpacks.json"
echo "wrote $OUT/packs.json and the app catalog (${#entries[@]} packs)" >&2
