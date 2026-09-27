#!/bin/bash
# 把安装包打进可双击打开的磁盘映像（只用 hdiutil 等命令行工具，无需 Xcode）。
# Pack the installer package into a disk image (hdiutil and other CLI tools only, no Xcode).
#
# 内容 / Contents: 双击安装织文输入法.pkg（主角，make-pkg.sh 生成）+ 使用说明.txt；背景图由程序自己画，Finder 能用时
# 摆好图标位置。程序本身不放进映像：系统从磁盘映像里「安装」App 的流程装不了输入法。
# The main item is 双击安装织文输入法.pkg (made by make-pkg.sh) plus 使用说明.txt; the app draws the background and Finder
# places the icons when available. The bare app is not in the image: the system's install-an-app-from-a-disk-image flow
# cannot install an input method.
# 产物 / Output: macos/build/WeaveText-<version>.dmg（UDZO 压缩、只读 / compressed, read-only）
# 用法 / Usage: macos/scripts/make-dmg.sh [path/to/WeaveText.app] [path/to/WeaveText-<version>.pkg]
#   （build-app.sh 最后会调用 / called by build-app.sh）
set -euo pipefail

MAC="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$MAC/build"
APP="${1:-$BUILD/WeaveText.app}"
VOLNAME="织文输入法"
PKGNAME="双击安装织文输入法.pkg"
README="使用说明.txt"

if [[ ! -d "$APP" ]]; then
  echo "找不到 / not found: $APP — run macos/scripts/build-app.sh first" >&2
  exit 1
fi
VERSION="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$APP/Contents/Info.plist")"
PKG="${2:-$BUILD/WeaveText-$VERSION.pkg}"
if [[ ! -f "$PKG" ]]; then
  echo "找不到安装包 / package not found: $PKG — run macos/scripts/make-pkg.sh first" >&2
  exit 1
fi
OUT="$BUILD/WeaveText-$VERSION.dmg"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/weavetext-dmg.XXXXXX")"
MOUNTED=""
cleanup() {
  [[ -n "$MOUNTED" ]] && hdiutil detach "$MOUNTED" -force -quiet 2>/dev/null || true
  rm -rf "$WORK"
}
trap cleanup EXIT

step() { printf '\n==> %s\n' "$*"; }

step "磁盘映像内容 / disk image contents"
ROOT="$WORK/root"
mkdir -p "$ROOT/.background"
cp "$PKG" "$ROOT/$PKGNAME"
cat > "$ROOT/$README" <<'EOF'
织文输入法 · 使用说明

安装
  1. 双击「双击安装织文输入法.pkg」，按「安装器」的提示点「继续」「安装」，输入一次管理员密码。
     织文会装到 /Library/Input Methods（这台 Mac 的所有用户都能用），并自动登记、启用、选中「织文拼音」。
  2. 装好后，在菜单栏右上角的输入法菜单里选择「织文拼音」即可使用
     （系统语言是英文时显示为「织文拼音 WeaveText」）。
     菜单里没有时，请注销并重新登录一次；仍然没有就打开 系统设置 › 键盘 › 输入法 › 编辑…，
     点「+」，在「简体中文」下添加「织文拼音」。

macOS 拦下安装包时
  安装包没有开发者签名，也没有经过 Apple 公证。如果 macOS 提示无法打开或无法验证开发者：
  - 到 系统设置 › 隐私与安全性，在页面下方找到这个安装包，点「仍要打开」；
  - 或者按住 Control 键点安装包（或右键），选「打开」，再点「打开」。
  只需要这一次。

更新与卸载
  更新：打开新版本的磁盘映像，双击里面的安装包。用户词、专业词库与设置都会保留；
        以前装在自己「输入法」文件夹（~/Library/Input Methods）里的旧版本会被替换掉。
  卸载：织文设置 › 关于 › 卸载织文输入法（装在 /Library 的那份需要输入管理员密码）。
  安装记录：~/Library/Logs/WeaveText-install.log

------------------------------------------------------------

WeaveText input method · Read me

Install
  1. Double-click 双击安装织文输入法.pkg, follow Installer (Continue, Install) and enter an administrator password once.
     WeaveText goes into /Library/Input Methods (for every user of this Mac) and 织文拼音 is registered, enabled and
     selected for you.
  2. Then pick 织文拼音 (shown as "织文拼音 WeaveText" in English) from the input menu at the top right of the menu bar.
     If it is not there, log out and back in once; if it is still missing, open System Settings › Keyboard ›
     Input Sources › Edit…, press "+" and add 织文拼音 under Simplified Chinese.

If macOS blocks the package
  The package has no developer signature and is not notarized by Apple. If macOS says it cannot open it or cannot
  verify the developer:
  - go to System Settings › Privacy & Security and click Open Anyway near the bottom;
  - or Control-click (right-click) the package, choose Open, then Open again.
  This is needed only once.

Update and uninstall
  Update: open the new disk image and double-click the package inside. Your words, domain dictionaries and settings
          stay; an older copy in your own Input Methods folder (~/Library/Input Methods) is replaced.
  Uninstall: WeaveText Settings › 关于 (About) › 卸载织文输入法 (the copy in /Library asks for an administrator password).
  Install log: ~/Library/Logs/WeaveText-install.log
EOF

# 背景图：程序自己画 1x 与 2x，合成一张 HiDPI 的 TIFF。 Background: the app draws 1x and 2x, merged into a HiDPI TIFF.
"$APP/Contents/MacOS/WeaveText" --render-dmg-background "$WORK/bg"
tiffutil -cathidpicheck "$WORK/bg/background.png" "$WORK/bg/background@2x.png" \
  -out "$ROOT/.background/background.tiff" 2>/dev/null
SIZE_MB=$(( $(du -sm "$ROOT" | cut -f1) + 20 ))

step "可写映像 / writable image"
RW="$WORK/rw.dmg"
hdiutil create -quiet -srcfolder "$ROOT" -volname "$VOLNAME" -fs HFS+ -format UDRW -size "${SIZE_MB}m" "$RW"

step "图标位置 / icon layout (Finder)"
# 挂在 /Volumes 下 Finder 才看得见；已有同名卷时跳过排版。坐标与 DiskImageBackground.swift 一致。
# Finder only sees volumes under /Volumes; skip the layout when one with the same name is already mounted.
# Coordinates match DiskImageBackground.swift.
if [[ -e "/Volumes/$VOLNAME" ]]; then
  echo "注意 / note: /Volumes/$VOLNAME already exists, icon layout skipped"
  LAYOUT=0
else
  LAYOUT=1
fi
MOUNTED="$(hdiutil attach "$RW" -readwrite -noverify -noautoopen | sed -n 's|^/dev/[^ ]*[[:space:]]*Apple_HFS[[:space:]]*||p' | head -1)"
if [[ -z "$MOUNTED" ]]; then
  echo "挂载失败 / attach failed" >&2
  exit 1
fi
if [[ $LAYOUT == 1 && "$MOUNTED" == "/Volumes/$VOLNAME" ]] && command -v osascript >/dev/null; then
  cat > "$WORK/layout.applescript" <<EOF
tell application "Finder"
  tell disk "$VOLNAME"
    open
    set current view of container window to icon view
    set toolbar visible of container window to false
    set statusbar visible of container window to false
    set bounds of container window to {200, 120, 800, 548}
    set opts to the icon view options of container window
    set arrangement of opts to not arranged
    set icon size of opts to 112
    set text size of opts to 13
    set background picture of opts to file ".background:background.tiff"
    set position of item "$PKGNAME" of container window to {190, 190}
    set position of item "$README" of container window to {430, 190}
    update without registering applications
    delay 1
    close
  end tell
end tell
EOF
  # Finder 可能要先征得自动化授权；等不到就放弃排版，映像照常生成。
  # Finder may ask for automation consent first; if it doesn't answer in time the layout is skipped.
  osascript "$WORK/layout.applescript" & pid=$!
  ( sleep 60; kill "$pid" 2>/dev/null ) & watchdog=$!
  if wait "$pid"; then
    echo "图标位置已设置 / icon layout set"
  else
    echo "注意 / note: Finder layout unavailable, skipped (the image still works)"
  fi
  kill "$watchdog" 2>/dev/null || true
  wait "$watchdog" 2>/dev/null || true
else
  echo "注意 / note: Finder layout skipped"
fi
# 让 Finder 写完 .DS_Store 再卸载。 Let Finder finish writing .DS_Store before detaching.
sync
sleep 1
rm -rf "$MOUNTED/.fseventsd" "$MOUNTED/.Trashes" 2>/dev/null || true
hdiutil detach "$MOUNTED" -quiet || hdiutil detach "$MOUNTED" -force -quiet
MOUNTED=""

step "压缩 / compress (UDZO)"
rm -f "$OUT"
hdiutil convert -quiet "$RW" -format UDZO -imagekey zlib-level=9 -o "$OUT"

step "校验 / verify"
hdiutil verify -quiet "$OUT"
CHECK="$WORK/check"
mkdir -p "$CHECK"
hdiutil attach "$OUT" -readonly -nobrowse -noautoopen -mountpoint "$CHECK" -quiet
MOUNTED="$CHECK"
[[ -f "$CHECK/$README" ]] || { echo "缺少 / missing $README" >&2; exit 1; }
[[ -f "$CHECK/.background/background.tiff" ]] || { echo "缺少 / missing background" >&2; exit 1; }
[[ -f "$CHECK/$PKGNAME" ]] || { echo "缺少 / missing $PKGNAME" >&2; exit 1; }
cmp -s "$PKG" "$CHECK/$PKGNAME" || { echo "安装包不一致 / package differs" >&2; exit 1; }
if [[ -e "$CHECK/织文输入法.app" ]]; then echo "映像里不应有程序本身 / the bare app must not be in the image" >&2; exit 1; fi
echo "安装包 / package: $(pkgutil --payload-files "$CHECK/$PKGNAME" | wc -l | tr -d ' ') entries"
# 只读：写入应当失败。 Read-only: a write must fail.
if touch "$CHECK/.write-test" 2>/dev/null; then
  echo "映像不是只读的 / image is writable" >&2
  exit 1
fi
hdiutil detach "$CHECK" -quiet
MOUNTED=""

step "完成 / done"
du -h "$OUT"
