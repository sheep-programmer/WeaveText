# 织文输入法 macOS 版 / WeaveText for macOS

与 Android 版共用同一个 Rust 内核（经 C 接口 `core/weave-c`），前端用 InputMethodKit + SwiftUI 编写。
*Shares the Rust engine with the Android app (through the C ABI in `core/weave-c`); the front end is InputMethodKit + SwiftUI.*

## 功能 / Features

- 输入方案：全拼、双拼（小鹤 / 自然码 / 微软）、五笔 86、English；模糊音、繁体输出、表情候选。
  *Schemes: full pinyin, double pinyin (Xiaohe / Ziranma / Microsoft), Wubi 86, English; fuzzy sounds, traditional output, emoji candidates.*
- 按键：字母进内核；空格上屏高亮候选；`1`–`9` 选词；`-` `=` 与 `,` `.` 翻页（仅在输入中，可在设置里选）；
  `←` `→` `↑` `↓` 移动高亮；回车上屏原字母；Esc 清空；单击 Shift 切换中英；大写锁定时直接输入大写；
  中文模式下输出全角标点（引号自动成对交替，数字后的 `.` `,` `:` 保持半角）；带 ⌘ ⌃ ⌥ 的组合键原样交给应用。
  *Keys: letters go to the engine; Space commits the highlight; `1`–`9` pick; `-` `=` and `,` `.` page while composing
  (configurable); arrows move the highlight; Return commits the raw letters; Esc clears; tapping Shift toggles
  Chinese/English; Caps Lock types capitals; full-width punctuation in Chinese mode (paired quotes alternate,
  `.` `,` `:` after a digit stay half-width); ⌘ ⌃ ⌥ shortcuts go straight to the app.*
- 候选窗：跟随光标、到屏幕底边自动翻到上方、多屏正确；横排 / 竖排、字号、深浅色可调；可用鼠标点选。
  *Candidate window: follows the caret, flips above at the screen bottom, correct on multiple screens; horizontal or
  vertical, font size and light/dark are adjustable; click to pick.*
- 菜单栏图标：左键打开快捷菜单（中英、输入方案、繁体、设置、关于、退出），右键直接打开设置；可在设置里隐藏。
  *Menu bar item: left click for a quick menu, right click opens Settings; can be hidden.*
- 设置：常规、输入方案、外观、词库（用户词搜索 / 删除 / 清空）、互联（即将推出）、关于（版本与开源许可）。
  *Settings: General, Schemes, Appearance, Dictionary (search / delete / clear user words), Link (coming soon), About.*

## 构建 / Build

需要 / Requires: macOS 13+、Swift 6 命令行工具（无需 Xcode / no Xcode needed）、Rust stable 及两个目标
（`rustup target add aarch64-apple-darwin x86_64-apple-darwin`）、已编好的词库 `data/build/*.wvz`（`./data/build.sh`）。

```bash
macos/scripts/build-app.sh            # 内核 + 测试 + 通用 .app + 自签名 + zip / engine, tests, universal .app, ad-hoc sign, zip
macos/scripts/build-app.sh --skip-tests
```

产物 / Output: `macos/build/WeaveText.app`（arm64 + x86_64）与 `macos/build/WeaveText-mac.zip`。

单独跑测试 / Tests only（先跑一次构建脚本生成 `build/lib/libweave_c.a` / run the build script once first）：

```bash
cd macos
swift test -Xswiftc -plugin-path -Xswiftc "$(dirname "$(dirname "$(xcrun --find swift)")")/lib/swift/host/plugins/testing"
```

只有命令行工具时 swift-testing 的宏插件要这样显式指定。 *With only the command-line tools the swift-testing macro
plugin has to be passed explicitly.*

调试用的命令行模式 / Diagnostic modes of the app binary:

| 命令 Command | 作用 Effect |
|---|---|
| `WeaveText --selftest` | 用包内词库打 `nihao`，检查首选为「你好」 / types `nihao` with the bundled data |
| `WeaveText --snapshot <目录>` | 把候选窗和各设置页画成 PNG / renders the candidate bar and settings pages to PNG |
| `WeaveText --register` / `--disable` | 向系统登记并启用 / 停用输入源（安装、卸载脚本使用） / register + enable or disable the input source |

## 安装 / Install

```bash
macos/scripts/install.sh              # 复制到 ~/Library/Input Methods 并登记、启用 / copy, register, enable
```

然后在 **系统设置 → 键盘 → 输入法 → 编辑…** 里点「+」，在「简体中文」下添加 **织文拼音**（脚本通常已自动启用），
用菜单栏的输入法菜单或 ⌃ 空格切换过去。首次安装若列表里没有，注销并重新登录一次。
*Then open **System Settings → Keyboard → Input Sources → Edit…**, press "+", and add **织文拼音 / WeaveText
Pinyin** under Simplified Chinese (the script usually enables it already); switch to it from the input menu or
with ⌃Space. If it does not show up after the first install, log out and back in once.*

从 zip 安装 / From the zip：解压后把 `WeaveText.app` 放进 `~/Library/Input Methods/`，执行
`xattr -dr com.apple.quarantine ~/Library/Input\ Methods/WeaveText.app`（本地构建未公证 / local builds are not
notarized），再注销重新登录，并按上面的步骤添加。

## 卸载 / Uninstall

```bash
macos/scripts/uninstall.sh            # 停用并删除，保留用户词 / disable and remove, keep user words
macos/scripts/uninstall.sh --purge    # 连同 ~/Library/Application Support/WeaveText 与偏好一起删除 / also user data and preferences
```

之后在 系统设置 → 键盘 → 输入法 里把残留的条目移除。 *Then remove any leftover entry in System Settings → Keyboard → Input Sources.*

## 数据位置 / Where things live

| 内容 Content | 位置 Location |
|---|---|
| 只读词库 Read-only dictionaries | `WeaveText.app/Contents/Resources/data/*.wvz` |
| 用户词 User words | `~/Library/Application Support/WeaveText/` |
| 偏好 Preferences | `defaults read com.weavetext.inputmethod.WeaveText` |

## 已知限制 / Known limits

- 只做了自签名（ad-hoc），未用开发者证书签名与公证；从网络下载的包需要去掉隔离属性。
  *Ad-hoc signed only, not Developer ID signed or notarized; downloaded copies need the quarantine flag removed.*
- 少数应用（部分终端、Electron / 跨平台框架）报不出光标位置，候选窗会沿用上一次的位置或出现在鼠标附近。
  *A few apps (some terminals, Electron / cross-platform toolkits) do not report the caret; the panel then reuses its
  last position or appears near the mouse.*
- 系统「用大写锁定键切换 ABC」打开时，大写锁定会被系统拿去切换输入法。
  *When the system option "Use Caps Lock to switch to and from ABC" is on, the system takes Caps Lock for switching.*
- 九键、手写与语音只在 Android 版提供；手机互联与扩展词库包在 Mac 上尚未接入（设置里为占位页）。
  *T9, handwriting and voice are Android only; phone pairing and extension packs are not wired up on the Mac yet
  (placeholder pages in Settings).*
- 输入法所在进程里的文本框（例如设置窗口的搜索框）不经过织文本身。
  *Text fields inside the IME's own process (such as the settings search box) do not go through WeaveText itself.*
