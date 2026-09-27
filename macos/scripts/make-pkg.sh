#!/bin/bash
# 把 WeaveText.app 打成标准的 .pkg 安装包（只用系统自带的 pkgbuild、productbuild，无需 Xcode）。
# Pack WeaveText.app into a standard .pkg installer (only the pkgbuild and productbuild that ship with macOS; no Xcode).
#
# 装到 /Library/Input Methods（整台电脑，「安装器」请求一次管理员密码）；preinstall 退出前台用户正在运行的织文、删掉
# 他「输入法」文件夹里的旧副本，postinstall 去掉隔离属性，再以他的身份在图形会话里运行 `WeaveText --register`。
# 安装包没有开发者签名。
# Installs into /Library/Input Methods (system-wide; Installer asks for the admin password once); preinstall quits the
# console user's running WeaveText and removes the old copy in their Input Methods folder, postinstall strips the
# quarantine attribute and runs `WeaveText --register` as that user in their GUI session. The package is unsigned.
#
# 产物 / Output: macos/build/WeaveText-<version>.pkg
# 用法 / Usage: macos/scripts/make-pkg.sh [path/to/WeaveText.app]   （build-app.sh 会调用 / called by build-app.sh）
set -euo pipefail

MAC="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$MAC/build"
PKGSRC="$MAC/scripts/pkg"
APP="${1:-$BUILD/WeaveText.app}"
PKG_ID="com.weavetext.inputmethod.WeaveText.pkg"
COMPONENT="WeaveText-component.pkg"
INSTALL_REL="Library/Input Methods/WeaveText.app"

if [[ ! -d "$APP" ]]; then
  echo "找不到 / not found: $APP — run macos/scripts/build-app.sh first" >&2
  exit 1
fi
VERSION="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$APP/Contents/Info.plist")"
OUT="$BUILD/WeaveText-$VERSION.pkg"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/weavetext-pkg.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

step() { printf '\n==> %s\n' "$*"; }
fail() { echo "安装包检查失败 / package check failed: $*" >&2; exit 1; }

step "安装内容 / payload"
ROOT="$WORK/root"
mkdir -p "$ROOT/Library/Input Methods"
ditto "$APP" "$ROOT/$INSTALL_REL"
# 扩展属性不进安装包（签名在包内文件与可执行文件里，不在属性里）。
# No extended attributes in the payload (the signature lives in the bundle's files and the executable, not in them).
xattr -cr "$ROOT/$INSTALL_REL"
codesign --verify --strict "$ROOT/$INSTALL_REL"

# 不许「安装器」把包「搬」到别处找到的同标识副本上（比如下载文件夹或旧的用户副本），总是装到 /Library/Input Methods。
# Installer must not relocate the bundle onto a copy with the same identifier found elsewhere (Downloads, the old
# per-user copy); it always goes to /Library/Input Methods.
PLIST="$WORK/component.plist"
pkgbuild --analyze --root "$ROOT" "$PLIST" >/dev/null
plutil -replace 0.BundleIsRelocatable -bool NO "$PLIST"
plutil -replace 0.BundleIsVersionChecked -bool NO "$PLIST"
plutil -replace 0.BundleHasStrictIdentifier -bool YES "$PLIST"
plutil -replace 0.BundleOverwriteAction -string upgrade "$PLIST"

SCRIPTS="$WORK/scripts"
mkdir -p "$SCRIPTS"
cp "$PKGSRC/scripts/preinstall" "$PKGSRC/scripts/postinstall" "$PKGSRC/scripts/weavetext-lib.sh" "$SCRIPTS/"
chmod 755 "$SCRIPTS/preinstall" "$SCRIPTS/postinstall"
chmod 644 "$SCRIPTS/weavetext-lib.sh"
bash -n "$SCRIPTS/weavetext-lib.sh" "$SCRIPTS/preinstall" "$SCRIPTS/postinstall"

step "组件包 / component package (pkgbuild)"
mkdir -p "$WORK/pkgs"
pkgbuild --quiet --root "$ROOT" --component-plist "$PLIST" --identifier "$PKG_ID" --version "$VERSION" \
  --install-location / --scripts "$SCRIPTS" "$WORK/pkgs/$COMPONENT"

step "说明页与背景 / pages and background"
RES="$WORK/resources"
mkdir -p "$RES"
style="$(<"$PKGSRC/resources/style.inc")"
for page in welcome conclusion; do
  html="$(<"$PKGSRC/resources/$page.html.in")"
  html="${html//@STYLE@/$style}"
  printf '%s\n' "${html//@VERSION@/$VERSION}" > "$RES/$page.html"
done
# 左下角的织文标志：程序自己画的图标，1x 与 2x 合成一张 HiDPI 的 TIFF。
# The logo at the bottom left: the app's own icon, 1x and 2x merged into one HiDPI TIFF.
"$APP/Contents/MacOS/WeaveText" --render-icons "$WORK/icons"
tiffutil -cathidpicheck "$WORK/icons/AppIcon.iconset/icon_128x128.png" "$WORK/icons/AppIcon.iconset/icon_128x128@2x.png" \
  -out "$RES/background.tiff" 2>/dev/null
sed -e "s/@PKG_ID@/$PKG_ID/g" -e "s/@VERSION@/$VERSION/g" -e "s/@COMPONENT@/$COMPONENT/g" \
  "$PKGSRC/distribution.xml.in" > "$WORK/distribution.xml"
xmllint --noout "$WORK/distribution.xml"

step "安装包 / product archive (productbuild)"
rm -f "$OUT"
productbuild --quiet --distribution "$WORK/distribution.xml" --package-path "$WORK/pkgs" --resources "$RES" "$OUT"

step "校验 / verify"
# 没有开发者签名：pkgutil 应当报告没有签名。 No Developer ID: pkgutil must report no signature.
sig="$(pkgutil --check-signature "$OUT" 2>&1 || true)"
[[ "$sig" == *"no signature"* ]] || { echo "$sig"; fail "expected an unsigned package"; }
echo "签名 / signature: none (unsigned, as expected)"
payload="$(pkgutil --payload-files "$OUT")"
grep -qx "./$INSTALL_REL/Contents/MacOS/WeaveText" <<<"$payload" || fail "payload does not contain $INSTALL_REL"
echo "内容 / payload: $(wc -l <<<"$payload" | tr -d ' ') entries under ./$INSTALL_REL"
info="$(installer -pkginfo -pkg "$OUT")"
[[ -n "$info" ]] || fail "installer -pkginfo shows nothing"
echo "$info"

X="$WORK/expanded"
pkgutil --expand-full "$OUT" "$X"
DIST="$X/Distribution"
grep -q 'hostArchitectures="x86_64,arm64"' "$DIST" || fail "hostArchitectures"
grep -q '<os-version min="13.0"/>' "$DIST" || fail "minimum macOS"
grep -q '<title>织文输入法</title>' "$DIST" || fail "title"
for f in welcome.html conclusion.html background.tiff; do
  [[ -f "$X/Resources/$f" ]] || fail "missing Resources/$f"
done
grep -q "仍要打开" "$X/Resources/conclusion.html" || fail "conclusion must explain 仍要打开"
C="$X/$COMPONENT"
INFO="$C/PackageInfo"
grep -q "identifier=\"$PKG_ID\"" "$INFO" || fail "identifier"
grep -q 'install-location="/"' "$INFO" || fail "install location"
# 不可搬动、以 root 安装。 Not relocatable, installed as root.
grep -q 'relocatable="false"' "$INFO" || fail "the bundle is relocatable"
if grep -q "<relocate>" "$INFO"; then fail "the bundle has relocation entries"; fi
grep -q 'auth="root"' "$INFO" || fail "auth must be root"
for s in preinstall postinstall; do
  [[ -x "$C/Scripts/$s" ]] || fail "$s is not executable"
done
[[ -f "$C/Scripts/weavetext-lib.sh" ]] || fail "missing weavetext-lib.sh"
INSTALLED="$C/Payload/$INSTALL_REL"
[[ -d "$INSTALLED" ]] || fail "payload app missing"
codesign --verify --strict "$INSTALLED" || fail "payload app signature"
codesign -dv "$INSTALLED" 2>&1 | grep -E "Identifier|Format"
lipo -info "$INSTALLED/Contents/MacOS/WeaveText"

step "完成 / done"
du -h "$OUT"
