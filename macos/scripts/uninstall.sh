#!/bin/bash
# 卸载织文输入法：~/Library/Input Methods 与 /Library/Input Methods（安装包装的，需要 sudo）两处的副本都删掉，
# 并忘掉安装包的回执。保留 ~/Library/Application Support/WeaveText 里的用户词，加 --purge 一并删除。
# Uninstall WeaveText: remove the copies in ~/Library/Input Methods and /Library/Input Methods (from the package; needs
# sudo) and forget the package receipt. User words in ~/Library/Application Support/WeaveText stay unless --purge.
set -euo pipefail

USER_COPY="$HOME/Library/Input Methods/WeaveText.app"
SYSTEM_COPY="/Library/Input Methods/WeaveText.app"
for app in "$USER_COPY" "$SYSTEM_COPY"; do
  if [[ -x "$app/Contents/MacOS/WeaveText" ]]; then
    "$app/Contents/MacOS/WeaveText" --disable || true
    break
  fi
done
pkill -x WeaveText 2>/dev/null || true
rm -rf "$USER_COPY"
if [[ -e "$SYSTEM_COPY" ]]; then
  echo "删除 / removing $SYSTEM_COPY (sudo)"
  sudo rm -rf "$SYSTEM_COPY"
fi
if pkgutil --pkg-info com.weavetext.inputmethod.WeaveText.pkg >/dev/null 2>&1; then
  sudo pkgutil --forget com.weavetext.inputmethod.WeaveText.pkg >/dev/null
fi
if [[ "${1:-}" == "--purge" ]]; then
  rm -rf "$HOME/Library/Application Support/WeaveText"
  defaults delete com.weavetext.inputmethod.WeaveText 2>/dev/null || true
fi
echo "已卸载 / uninstalled"
