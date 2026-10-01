#!/usr/bin/env bash
# 真机/模拟器冒烟测试（需调试版 APK）。 Device smoke test (debug APK).
#   tools/device-smoke.sh [serial] [--install]
# 通过调试版的 DebugBridge 广播驱动输入法，读取输入框文字与 logcat 中的单键耗时。
set -uo pipefail
SERIAL="${1:-emulator-5554}"
ADB="adb -s $SERIAL"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IME="com.weavetext.ime/.ime.WeaveImeService"
if [[ " $* " == *" --install "* ]]; then
  (cd "$ROOT/android" && ./gradlew -q :app:assembleDebug) || exit 1
  $ADB install -r "$ROOT/android/app/build/outputs/apk/debug/app-debug.apk" | tail -1
fi
$ADB shell ime enable "$IME" >/dev/null
$ADB shell ime set "$IME" >/dev/null
$ADB logcat -c
pass=0; fail=0
field_text() {
  $ADB shell uiautomator dump /sdcard/smoke.xml >/dev/null 2>&1
  $ADB shell cat /sdcard/smoke.xml | python3 -c "
import sys,re
s=sys.stdin.read()
m=re.search(r'<node[^>]*text=\"([^\"]*)\"[^>]*content-desc=\"smoke_input\"',s) or re.search(r'<node[^>]*content-desc=\"smoke_input\"[^>]*text=\"([^\"]*)\"',s)
print(m.group(1) if m else '<no field>')"
}
check() { # name keys expected [inputType]
  local name="$1" keys="$2" want="$3" type="${4:-131073}"
  $ADB shell am start -W -f 0x10008000 -n com.weavetext.ime/.debug.SmokeActivity --ei type "$type" >/dev/null
  sleep 1.5
  $ADB shell am broadcast -a com.weavetext.ime.debug.KEYS --es keys "'{schema:pinyin}'" >/dev/null
  $ADB shell am broadcast -a com.weavetext.ime.debug.KEYS --es keys "'$keys'" >/dev/null
  sleep 0.8
  local got; got="$(field_text)"
  # 密码框里系统把字显示成圆点（最后一个字可能短暂明文），只比长度。 Password fields show dots: compare length only.
  local ok=0
  if [[ "$type" == 129 ]]; then [[ ${#got} == ${#want} ]] && ok=1; else [[ "$got" == "$want" ]] && ok=1; fi
  if [[ $ok == 1 ]]; then pass=$((pass+1)); echo "  ✓ $name → $got"; else fail=$((fail+1)); echo "  ✗ $name → [$got] (want [$want])"; fi
}
echo "== 输入 / typing"
check "全拼 nihao"        "nihao{space}"                        "你好"
check "语气词嗯嗯"        "enen{sel:0}"                         "嗯嗯"
check "短句修吧"          "xiuba{sel:0}"                        "修吧"
check "整句"              "woshizhongguoren{space}"             "我是中国人"
check "标点"              "nihao,"                              "你好，"
check "退格"              "nihaoa{bs}{space}"                   "你好"
check "九键"              "{schema:t9}94664486736{space}"       "中国人"
check "五笔四码上屏"      "{schema:wubi86}ggll"                 "一"
check "小鹤双拼"          "{schema:shuangpin:xiaohe}vsgo{space}" "中国"
check "繁体输出"          "{opt:output.traditional=true}toufa{space}{opt:output.traditional=false}" "頭髮"
check "中英切换"          "{toggle}hello{space}{toggle}"        "hello "
check "密码框直接上屏"    "abc"                                 "abc" 129
echo "== 语音与删除 / voice and deletion"
check "语音终稿结束拼音"  "ni{voice-final:明天见}{bs}"          "你明天"
check "语音终稿连续退格"  "ni{voice-final:明天见}{bs}{bs}{bs}{bs}" ""
check "语音清掉旧联想"    "jintian{sel:0}{voice-final:明天见}{sel:0}" "今天明天见"
check "中间结果替换"      "{voice-partial:明天}{voice-partial:明天见}{voice-final:明天见。}{bs}" "明天见"
check "语音多句连续退格"  "{voice-partial:明天}{voice-final:明天见}{voice-partial:再}{voice-final:再见}{voice-final:}{bs}{bs}{bs}{bs}{bs}" ""
check "语音取消后退格"    "ni{sel:0}{voice-partial:临时文字}{voice-cancel}{bs}" ""
check "语音修正后退格"    "{voice-final:明天见}{voice-replace:明天见|明天见。}{bs}{bs}{bs}{bs}" ""
echo "== 耗时 / latency (WeaveSmoke)"
$ADB logcat -d -s WeaveSmoke | grep -o 'keys=[0-9]* avg_ms=[0-9.]* max_ms=[0-9.]*' | tail -12
echo "== 崩溃 / crashes"
crash="$($ADB logcat -d -b crash | grep -c 'com.weavetext' || true)"
echo "  crash lines: $crash"
echo "pass=$pass fail=$fail"
[[ $fail -eq 0 && $crash -eq 0 ]]
