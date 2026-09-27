#!/bin/bash
# 开发用：把织文装给当前用户，复制到 ~/Library/Input Methods 并向系统登记、启用（发布版用 .pkg 安装包）。
# For development: install WeaveText for the current user, copy into ~/Library/Input Methods, then register and enable
# it (releases use the .pkg installer).
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

status=0
"$DEST/Contents/MacOS/WeaveText" --register || status=$?
case $status in
  0)
    echo "已安装并启用 / installed and enabled: $DEST" ;;
  2)
    echo "已安装并登记，但输入法菜单里还没有「织文拼音」：请注销后重新登录一次。"
    echo "Installed and registered, but 织文拼音 is not listed yet: log out and back in once." ;;
  *)
    echo "已复制，但登记失败（见 ~/Library/Logs/WeaveText-install.log）；请注销后重新登录，再到系统设置里添加。" >&2
    echo "Copied, but registration failed (see ~/Library/Logs/WeaveText-install.log); log out and back in, then add it in System Settings." >&2
    exit 1 ;;
esac
if [[ -d "/Library/Input Methods/WeaveText.app" ]]; then
  echo "注意：/Library/Input Methods 里还有安装包装的一份，两份会抢同一个输入源；用 uninstall.sh 删掉其中一份。"
  echo "Note: the package's copy in /Library/Input Methods is still there and both fight over one input source; remove one with uninstall.sh."
fi
