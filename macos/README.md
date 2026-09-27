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
- v 模式（全拼）：`v` 后接数字或算式，如 `v1234` → 壹仟贰佰叁拾肆元整、一千二百三十四、1,234，`v(128+32)*4` → 640，
  候选后面以小字注明类型。此时数字与 `+ - * / ( ) . % ^` 都进组合串、不再选词；用空格上屏高亮项（默认第一个），
  方向键移动高亮，鼠标点选，回车上屏原样的 `v…`，`=` `,` 仍可翻页。
  *v mode (full pinyin): `v` followed by digits or an expression, e.g. `v1234` → 壹仟贰佰叁拾肆元整 / 一千二百三十四 /
  1,234, `v(128+32)*4` → 640, with a small note after each candidate. Digits and `+ - * / ( ) . % ^` then go into the
  composition instead of picking; Space commits the highlight (the first by default), arrows move it, click to pick,
  Return commits the raw `v…`, and `=` `,` still page.*
- 等号出结果：中英文模式下，光标前是算式（如 `单价 128*4`）时敲 `=`，候选窗给出结果，按 `1`、空格或点击接在等号后面，
  按其他键收起。读不到光标前文字的应用里不出现。 `rq` `sj` `xq` 给出日期、时间、星期（跟随系统时区）。
  *Result after `=`: in either mode, typing `=` right after an expression (such as `单价 128*4`) shows the result;
  `1`, Space or a click appends it, any other key dismisses it. Apps that don't expose the text before the caret don't get
  it. `rq` `sj` `xq` give the date, time and weekday (following the system time zone).*
- 联想词：上屏后候选窗给出下一个词（顶行小字「联想」，不高亮）。按 `1`–`9` 或点击选词并接着联想；空格（照常输入空格）、
  回车、Esc、方向键、标点或点击文档都会收起；直接打字母照常开始新的输入。可在「输入方案」里关闭。
  上屏后马上退格，除了删字，也会撤销这次上屏刚学到的词；中间打过标点、空格、数字或挪过光标就不撤销。
  *Predictions: after a commit the panel offers the next word (a small 联想 hint, no highlight). `1`–`9` or a click picks
  and predicts again; Space (which still types a space), Return, Esc, arrows, punctuation or a click in the document
  dismiss them; typing letters starts new input as usual. Can be turned off under Schemes. A backspace right after a
  commit also undoes what that commit just learned; not after punctuation, a space, a digit or a caret move.*
- 候选窗：跟随光标、到屏幕底边自动翻到上方、多屏正确；横排 / 竖排、字号、深浅色可调；可用鼠标点选。
  *Candidate window: follows the caret, flips above at the screen bottom, correct on multiple screens; horizontal or
  vertical, font size and light/dark are adjustable; click to pick.*
- 菜单栏图标：左键打开快捷菜单（中英、输入方案、繁体、设置、关于、退出），右键直接打开设置；可在设置里隐藏。
  *Menu bar item: left click for a quick menu, right click opens Settings; can be hidden.*
- 专业词库：「词库 › 专业词库」里按需下载医学、法律、IT、地名等领域词表（从织文的 GitHub 发布页下载，直连不通时依次换用与
  Android 相同的下载镜像，都校验大小与 SHA-256），装上立即生效，删除同样即时；这些词不会排到常用词前面，选过一次后自动靠前。
  文件在但没能载入（例如损坏）时显示「重试」。
  *Domain dictionaries: download medicine, law, IT, places and more under Dictionary › 专业词库 (from WeaveText's GitHub
  release, falling back to the Android app's download mirrors one by one, always checked for size and SHA-256); they take
  effect at once, removal too; they never outrank common words until picked once. A file that is there but failed to load
  (say, corrupt) offers 重试.*
- 云端热词（默认关闭）：打开后每天最多检查一次公开的织文热词库（带 ETag，没变化不下载），内核验签通过才换上，
  验签失败继续用旧版本；直连不通时同样换用镜像，镜像的内容也要验签。没能载入时显示「重试」。只下载，不上传任何输入。
  关闭即卸下并删除已下载的文件。
  *Cloud hot words (off by default): when on, the public hot-words list is checked at most daily (with an ETag, nothing
  is downloaded when unchanged) and swapped in only after the engine verifies its signature; a bad signature keeps the
  old version. Mirrors are used here too when the direct URL fails, and their content must pass the same check; 重试
  shows when nothing is loaded. Download only. Turning it off detaches and deletes the files.*
- 设置：常规、输入方案（含联想词开关）、外观、词库（用户词、专业词库、云端热词）、互联、关于（版本、隐私说明与开源许可）。
  *Settings: General, Schemes (with the prediction switch), Appearance, Dictionary (user words, domain dictionaries,
  cloud hot words), Link, About (version, privacy notes, licences).*

## 织文互联 / WeaveLink

与同一 Wi-Fi 下装了织文的 Android 手机配对，互传文字、剪贴板、图片与文件；端到端加密，不经过任何服务器，默认关闭。
*Pair with an Android phone running WeaveText on the same Wi-Fi to exchange text, clipboard, images and files;
end-to-end encrypted, no server involved, off by default.*

- 开启：设置 → 互联 → 打开「织文互联」。首次开启时系统会询问是否允许访问本地网络，请允许。
  *Enable: Settings → Link → turn on 织文互联. macOS asks for local network access the first time; allow it.*
- 配对：点「配对手机…」，窗口里显示二维码、6 位配对码和本机地址，两分钟内有效；在手机的 织文 › 设置 › 互联 里扫码，
  或在「附近的设备」里选这台 Mac 输入配对码，找不到时用「用地址配对」。配对成功后窗口自动关闭，以后同一网络下自动重连。
  *Pair: click 配对手机…; the sheet shows a QR code, the 6-digit code and this Mac's addresses for two minutes. On the
  phone, scan it under 织文 › 设置 › 互联, pick this Mac under 附近的设备 and type the code, or use 用地址配对. The sheet
  closes itself once paired; the devices reconnect on the same network from then on.*
- 剪贴板同步（默认开）：Mac 上复制的文字（2 万字以内）和图片（PNG/TIFF，20 MB 以内）自动出现在手机上，反之亦然；
  密码管理器标为保密或临时的内容、访达里复制的文件不同步，刚从手机收到的内容不会再发回去。
  *Clipboard sync (on by default): text (up to 20,000 characters) and images (PNG/TIFF, up to 20 MB) copied on the Mac
  appear on the phone and vice versa; content marked concealed/transient by password managers and files copied in
  Finder are skipped, and what just arrived from the phone is never sent back.*
- 发送：菜单栏图标 → 发送到手机 → 发送剪贴板 / 发送文件…（可多选），或直接把文件拖到菜单栏图标上；菜单里显示进度，
  如「正在发送 3/5 · 42%」。手机连着时图标右下角有个小圆点。
  *Send: menu bar icon → 发送到手机 → 发送剪贴板 / 发送文件… (multiple allowed), or drop files on the menu bar icon;
  the menu shows progress such as 正在发送 3/5 · 42%. A small dot on the icon means a phone is connected.*
- 接收：手机主动发来的文字放进剪贴板并发通知；文件存进 `~/Downloads/WeaveText`，点通知在访达中显示。
  *Receive: text sent from the phone goes to the clipboard with a notification; files land in `~/Downloads/WeaveText`,
  and clicking the notification reveals them in Finder.*

## 构建 / Build

需要 / Requires: macOS 13+、Swift 6 命令行工具（无需 Xcode / no Xcode needed）、Rust stable 及两个目标
（`rustup target add aarch64-apple-darwin x86_64-apple-darwin`）、已编好的词库 `data/build/*.wvz`（`./data/build.sh`）。

```bash
macos/scripts/build-app.sh            # 内核 + 测试 + 通用 .app + 自签名 + zip / engine, tests, universal .app, ad-hoc sign, zip
macos/scripts/build-app.sh --skip-tests
```

产物 / Output: `macos/build/WeaveText.app`（arm64 + x86_64）与 `macos/build/WeaveText-mac.zip`。

脚本按内核的资源表（`core/weave-engine/src/session.rs` 的 `RESOURCES`）把 `data/build/` 里对应的 `.wvz` 全部放进包里，
缺少基础词库或联想表 `follow.wvz` 时报错；专业词库目录 `dictpacks.json` 与 Android 共用一份，
下载镜像取自 Android 模型目录 `models/catalog.json` 的 `mirrors`，放成 `mirrors.json`。
*The script ships every `.wvz` in `data/build/` that the engine's resource table (`RESOURCES` in
`core/weave-engine/src/session.rs`) knows, and fails when a base dictionary or the prediction table `follow.wvz` is
missing; the domain-dictionary catalog `dictpacks.json` is shared with Android, and the download mirrors are the
`mirrors` of Android's model catalog `models/catalog.json`, shipped as `mirrors.json`.*

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
| `WeaveText --selftest` | 用包内词库打 `nihao`（首选「你好」）、算 `v(128+32)*4`、上屏「今天」后应有联想 / types `nihao`, evaluates `v(128+32)*4` and checks predictions after 今天 with the bundled data |
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
| 专业词库 Domain dictionaries | `~/Library/Application Support/WeaveText/packs/<id>.wvz`（启动时自动载入 / loaded at startup） |
| 云端热词 Cloud hot words | `~/Library/Application Support/WeaveText/cloud/hotwords.tsv`（及 `.sig`） |
| 互联身份与已配对设备 WeaveLink identity and paired devices | `~/Library/Application Support/WeaveText/link/` |
| 收到的文件 Received files | `~/Downloads/WeaveText/` |
| 偏好 Preferences | `defaults read com.weavetext.inputmethod.WeaveText` |

## 已知限制 / Known limits

- 只做了自签名（ad-hoc），未用开发者证书签名与公证；从网络下载的包需要去掉隔离属性。
  *Ad-hoc signed only, not Developer ID signed or notarized; downloaded copies need the quarantine flag removed.*
- 少数应用（部分终端、Electron / 跨平台框架）报不出光标位置，候选窗会沿用上一次的位置或出现在鼠标附近。
  *A few apps (some terminals, Electron / cross-platform toolkits) do not report the caret; the panel then reuses its
  last position or appears near the mouse.*
- 系统「用大写锁定键切换 ABC」打开时，大写锁定会被系统拿去切换输入法。
  *When the system option "Use Caps Lock to switch to and from ABC" is on, the system takes Caps Lock for switching.*
- 九键、手写与语音只在 Android 版提供。镜像按固定顺序逐个尝试，不像 Android 那样先测速、断点续传，也不能自选镜像；
  专业词库的发布页还没发布时会显示「下载失败，请检查网络后重试」。
  *T9, handwriting and voice are Android only. Mirrors are tried one by one in a fixed order, without Android's speed
  probe, resume or mirror choice; until the packs release is published, downloads show 下载失败，请检查网络后重试.*
- 互联的 Mac 端只显示本机的配对码让手机来连，不能在 Mac 上输入手机的配对码（手机端不显示配对码）。
  *On the Mac, WeaveLink only shows its own code for the phone to dial in; it can't type a phone's code (phones don't
  show one).*
- 通知需要在第一次收到文件或文字时允许；拒绝后可在 系统设置 → 通知 里打开。
  *Notifications must be allowed the first time something arrives; if declined, turn them on in System Settings →
  Notifications.*
- 输入法所在进程里的文本框（例如设置窗口的搜索框）不经过织文本身。
  *Text fields inside the IME's own process (such as the settings search box) do not go through WeaveText itself.*
