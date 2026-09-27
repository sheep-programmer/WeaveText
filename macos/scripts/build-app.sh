#!/bin/bash
# 构建织文输入法 macOS 版：内核通用静态库 → Swift 双架构 → 组装 .app → 自签名 → 打 zip。
# Build WeaveText for macOS: universal engine lib → Swift for both archs → assemble .app → ad-hoc sign → zip.
#
# 只需命令行工具（无需 Xcode）。 Needs only the command-line tools (no Xcode).
# 用法 / Usage: macos/scripts/build-app.sh [--skip-tests]
set -euo pipefail

MAC="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$MAC/.." && pwd)"
BUILD="$MAC/build"
APP="$BUILD/WeaveText.app"
VERSION="$(sed -n 's/^version = "\(.*\)"/\1/p' "$ROOT/core/Cargo.toml" | head -1)"
BUILD_NUMBER="$(git -C "$ROOT" rev-list --count HEAD 2>/dev/null || echo 1)"
SKIP_TESTS=0
[[ "${1:-}" == "--skip-tests" ]] && SKIP_TESTS=1

step() { printf '\n==> %s\n' "$*"; }

# swift-testing 的宏插件在命令行工具里需要显式指路。 The CLT need an explicit path to the swift-testing macro plugin.
TOOLCHAIN="$(dirname "$(dirname "$(xcrun --find swift)")")"
TESTING_PLUGINS="$TOOLCHAIN/lib/swift/host/plugins/testing"

step "内核 / engine (weave-c) for arm64 + x86_64"
# 独立的 target 目录，不碰 core/target；最低系统 13.0。宿主端的过程宏不 strip：strip 过的 dylib 在新系统上载入失败。
# A separate target dir, leaving core/target alone; macOS 13.0 minimum. Host proc-macros are not stripped:
# stripped dylibs fail to load on newer systems.
# x86_64 上 curve25519-dalek 4.1 的 SIMD 后端在新版 rustc 上编不过，改用纯标量后端。
# curve25519-dalek 4.1's SIMD backend fails to build on newer rustc for x86_64; use the serial backend.
CARGO_OUT="$BUILD/cargo"
export CARGO_TARGET_X86_64_APPLE_DARWIN_RUSTFLAGS='--cfg curve25519_dalek_backend="serial"'
( cd "$ROOT/core"
  for t in aarch64-apple-darwin x86_64-apple-darwin; do
    CARGO_TARGET_DIR="$CARGO_OUT" MACOSX_DEPLOYMENT_TARGET=13.0 CARGO_PROFILE_RELEASE_BUILD_OVERRIDE_STRIP=false \
      cargo build --release -p weave-c --target "$t"
  done )
mkdir -p "$BUILD/lib"
lipo -create \
  "$CARGO_OUT/aarch64-apple-darwin/release/libweave_c.a" \
  "$CARGO_OUT/x86_64-apple-darwin/release/libweave_c.a" \
  -output "$BUILD/lib/libweave_c.a"
lipo -info "$BUILD/lib/libweave_c.a"

cd "$MAC"
if [[ $SKIP_TESTS == 0 ]]; then
  step "测试 / tests"
  if [[ -d "$TESTING_PLUGINS" ]]; then
    swift test -Xswiftc -plugin-path -Xswiftc "$TESTING_PLUGINS"
  else
    swift test
  fi
fi

step "Swift (release, arm64 + x86_64)"
BINS=()
for arch in arm64 x86_64; do
  triple="$arch-apple-macosx13.0"
  # 每个架构单独的构建目录，否则两次产物落在同一处。 One scratch dir per arch, or both land in one place.
  scratch="$MAC/.build/$arch"
  swift build -c release --triple "$triple" --scratch-path "$scratch" --product WeaveText
  BINS+=("$(swift build -c release --triple "$triple" --scratch-path "$scratch" --show-bin-path)/WeaveText")
done
mkdir -p "$BUILD/bin"
lipo -create "${BINS[@]}" -output "$BUILD/bin/WeaveText"

step "组装 / assemble $APP"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources/data"
cp "$BUILD/bin/WeaveText" "$APP/Contents/MacOS/WeaveText"
sed -e "s/@VERSION@/$VERSION/" -e "s/@BUILD@/$BUILD_NUMBER/" "$MAC/Resources/Info.plist" > "$APP/Contents/Info.plist"
printf 'APPL????' > "$APP/Contents/PkgInfo"
cp -R "$MAC/Resources/zh-Hans.lproj" "$MAC/Resources/en.lproj" "$APP/Contents/Resources/"

DATA="$ROOT/data/build"
# 内核认识的资源名取自 session.rs 的 RESOURCES，有 .wvz 的都带上；这几个必须有。
# The resource keys the engine knows come from RESOURCES in session.rs; every one with a .wvz ships. These are required.
KEYS=($(sed -n '/^pub const RESOURCES/,/^];/p' "$ROOT/core/weave-engine/src/session.rs" | sed -n 's/^ *("\([a-z0-9_]*\)",.*/\1/p'))
REQUIRED=(pinyin wubi86 english grammar emoji st_characters st_phrases follow)
if [[ ${#KEYS[@]} -eq 0 ]]; then
  echo "读不到内核的资源列表 / cannot read RESOURCES from session.rs" >&2
  exit 1
fi
for f in "${REQUIRED[@]}"; do
  if [[ ! -f "$DATA/$f.wvz" ]]; then
    echo "缺少词库 / missing $DATA/$f.wvz — run data/build.sh first" >&2
    exit 1
  fi
done
for f in "${KEYS[@]}"; do
  if [[ -f "$DATA/$f.wvz" ]]; then
    cp "$DATA/$f.wvz" "$APP/Contents/Resources/data/"
  else
    echo "注意 / note: no $f.wvz, skipped"
  fi
done
echo "data: ${KEYS[*]}"
# 专业词库目录（与 Android 同一份）。 The domain-dictionary catalog, the same file as Android's.
cp "$ROOT/android/app/src/main/assets/dictpacks.json" "$APP/Contents/Resources/dictpacks.json"
# 下载镜像：取 Android 模型目录里的 "mirrors"（专业词库与云端热词先直连，失败再依次换镜像）。
# Download mirrors: the "mirrors" of Android's model catalog (packs and hot words try the direct URL first).
plutil -extract mirrors json -o "$APP/Contents/Resources/mirrors.json" "$ROOT/android/app/src/main/assets/models/catalog.json"

# 图标由程序自己画出来。 The app draws its own icons.
ICONS="$BUILD/icons"
rm -rf "$ICONS"
"$APP/Contents/MacOS/WeaveText" --render-icons "$ICONS"
iconutil -c icns "$ICONS/AppIcon.iconset" -o "$APP/Contents/Resources/AppIcon.icns"
cp "$ICONS/InputMode.tiff" "$APP/Contents/Resources/InputMode.tiff"
plutil -lint "$APP/Contents/Info.plist" >/dev/null

step "自检 / self-test (bundled data)"
"$APP/Contents/MacOS/WeaveText" --selftest
if arch -x86_64 /usr/bin/true 2>/dev/null; then
  arch -x86_64 "$APP/Contents/MacOS/WeaveText" --selftest
fi

step "签名 / ad-hoc sign"
codesign --force --deep --sign - "$APP"
codesign --verify --strict "$APP"

step "打包 / zip"
rm -f "$BUILD/WeaveText-mac.zip"
ditto -c -k --keepParent "$APP" "$BUILD/WeaveText-mac.zip"

step "完成 / done"
lipo -info "$APP/Contents/MacOS/WeaveText"
codesign -dv "$APP" 2>&1 | grep -E "Identifier|Format|Signature" || true
du -sh "$APP" "$BUILD/WeaveText-mac.zip"
