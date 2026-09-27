#!/bin/bash
# 卸载织文输入法（保留 ~/Library/Application Support/WeaveText 里的用户词，加 --purge 一并删除）。
# Uninstall WeaveText (user words in ~/Library/Application Support/WeaveText are kept unless --purge).
set -euo pipefail

DEST="$HOME/Library/Input Methods/WeaveText.app"
if [[ -x "$DEST/Contents/MacOS/WeaveText" ]]; then
  "$DEST/Contents/MacOS/WeaveText" --disable || true
fi
pkill -x WeaveText 2>/dev/null || true
rm -rf "$DEST"
if [[ "${1:-}" == "--purge" ]]; then
  rm -rf "$HOME/Library/Application Support/WeaveText"
  defaults delete com.weavetext.inputmethod.WeaveText 2>/dev/null || true
fi
echo "已卸载 / uninstalled"
