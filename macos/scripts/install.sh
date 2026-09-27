#!/bin/bash
# 安装织文输入法到当前用户：复制到 ~/Library/Input Methods 并向系统登记、启用。
# Install WeaveText for the current user: copy into ~/Library/Input Methods, then register and enable it.
#
# 用法 / Usage: macos/scripts/install.sh [path/to/WeaveText.app]   （默认 macos/build/WeaveText.app）
set -euo pipefail

MAC="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${1:-$MAC/build/WeaveText.app}"
DEST_DIR="$HOME/Library/Input Methods"
DEST="$DEST_DIR/WeaveText.app"

if [[ ! -d "$SRC" ]]; then
  echo "找不到 / not found: $SRC — run macos/scripts/build-app.sh first" >&2
  exit 1
fi

# 旧版本还在运行时先退出，系统会按需重新拉起。 Quit a running copy; the system relaunches it on demand.
pkill -x WeaveText 2>/dev/null || true

mkdir -p "$DEST_DIR"
rm -rf "$DEST"
ditto "$SRC" "$DEST"
# 本地构建未公证，去掉隔离属性。 Local builds are not notarized; drop the quarantine flag.
xattr -dr com.apple.quarantine "$DEST" 2>/dev/null || true

if "$DEST/Contents/MacOS/WeaveText" --register; then
  echo "已安装并启用 / installed and enabled: $DEST"
  echo "若输入法菜单里还没有「织文拼音」，到 系统设置 → 键盘 → 输入法 → 编辑… 里添加（简体中文）。"
  echo "If 织文拼音 is not in the input menu yet, add it in System Settings → Keyboard → Input Sources → Edit… (Simplified Chinese)."
else
  echo "已复制，但登记失败；请注销后重新登录，再到系统设置里添加。" >&2
  echo "Copied, but registration failed; log out and back in, then add it in System Settings." >&2
  exit 1
fi
