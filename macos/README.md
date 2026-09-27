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

## 安装 / Install

1. 下载 `WeaveText-<版本>.pkg`（或打开 `WeaveText-<版本>.dmg`，双击里面的 **双击安装织文输入法.pkg**），按「安装器」的提示点
   「继续」「安装」，输入一次管理员密码。织文装到 `/Library/Input Methods/`（这台 Mac 的所有用户都能用）；安装前会退出正在
   运行的织文、删掉以前装在 `~/Library/Input Methods/` 里的旧副本（用户词与设置都在 Application Support 里，保留），
   装好后去掉隔离属性，并以当前登录用户的身份运行 `WeaveText --register`：登记、启用并选中「织文拼音」。
   *Download `WeaveText-<version>.pkg` (or open `WeaveText-<version>.dmg` and double-click **双击安装织文输入法.pkg**
   inside), follow Installer (Continue, Install) and enter an administrator password once. WeaveText goes into
   `/Library/Input Methods/` (for every user of this Mac); before installing, the running WeaveText is quit and an older
   copy in `~/Library/Input Methods/` is removed (words and settings live in Application Support and stay); afterwards the
   quarantine attribute is removed and `WeaveText --register` runs as the logged-in user to register, enable and select
   织文拼音.*
2. 完成后在菜单栏右上角的输入法菜单里选择 **织文拼音**（系统语言是英文时显示为「织文拼音 WeaveText」）。
   菜单里没有时注销并重新登录一次；仍然没有就到 **系统设置 → 键盘 → 输入法 → 编辑…** 里点「+」，在「简体中文」下添加。
   *Then pick **织文拼音** from the input menu at the top right of the menu bar (shown as "织文拼音 WeaveText" when the
   system language is English). If it is missing, log out and back in once; if it still isn't there, add it under
   **System Settings → Keyboard → Input Sources → Edit…** (Simplified Chinese).*

安装包没有开发者签名（没有 Developer ID，也没有公证）。macOS 拦下它时，到 **系统设置 → 隐私与安全性**，在页面下方点
「仍要打开」；或者按住 Control 点安装包，选「打开」。磁盘映像里的 `使用说明.txt` 写着同样的步骤。安装经过记在
`~/Library/Logs/WeaveText-install.log`（登记）与 `/var/log/install.log`（安装器与脚本）。
*The package is unsigned (no Developer ID, not notarized). If macOS blocks it, go to **System Settings → Privacy &
Security** and click Open Anyway near the bottom, or Control-click the package and choose Open. `使用说明.txt` in the disk
image has the same steps. The install is logged to `~/Library/Logs/WeaveText-install.log` (registration) and
`/var/log/install.log` (Installer and the scripts).*

更新：双击新版本的安装包即可，用户词与设置保留。
*Update: double-click the newer package; your words and settings stay.*

不用安装包时（例如只下载了 `WeaveText-mac.zip`）：双击 `织文输入法.app` 会打开安装窗口。旁边有安装包时主按钮是「打开安装包」；
没有时退回到复制进 `~/Library/Input Methods/`（只对当前用户，不要管理员密码）；已经用安装包装在 `/Library` 时不再另装一份，
按钮改为「打开下载页」。出错时窗口里显示系统给出的原因。
*Without the package (e.g. only `WeaveText-mac.zip`): double-clicking `织文输入法.app` opens the installer window. With a
package next to it the main button is 打开安装包 (open the package); without one it falls back to copying into
`~/Library/Input Methods/` (current user, no admin password); if the package already installed it in `/Library`, it
won't add a second copy and offers 打开下载页 (the download page). Errors show the system's reason in the window.*

## 卸载 / Uninstall

**设置 → 关于 → 卸载织文输入法…**：停用输入源，删掉两处「输入法」文件夹里的织文后退出。`~/Library/Input Methods` 里的那份移到
废纸篓；安装包装在 `/Library/Input Methods` 的那份会弹出系统的管理员密码框，直接删除并忘掉安装包的回执（点「取消」则什么都
不改，输入源恢复启用）。默认保留用户词、专业词库与设置；勾选「同时删除词库与设置」时，`~/Library/Application Support/WeaveText`
也移到废纸篓，偏好一并清除。之后如果 系统设置 → 键盘 → 输入法 里还留着条目，移除即可。
*Settings → 关于 (About) → 卸载织文输入法…: disables the input source, removes WeaveText from both Input Methods folders
and quits. The copy in `~/Library/Input Methods` goes to the Trash; the package's copy in `/Library/Input Methods` brings
up the system's administrator prompt, is deleted and the package receipt is forgotten (Cancel changes nothing and the
source is enabled again). User words, domain dictionaries and settings are kept unless 同时删除词库与设置 is ticked, in
which case `~/Library/Application Support/WeaveText` goes to the Trash too and the preferences are cleared. Remove any
leftover entry in System Settings → Keyboard → Input Sources afterwards.*

## 构建 / Build

需要 / Requires: macOS 13+、Swift 6 命令行工具（无需 Xcode / no Xcode needed）、Rust stable 及两个目标
（`rustup target add aarch64-apple-darwin x86_64-apple-darwin`）、已编好的词库 `data/build/*.wvz`（`./data/build.sh`）。

```bash
macos/scripts/build-app.sh            # 内核 + 测试 + 通用 .app + 自签名 + zip + pkg + dmg / engine, tests, universal .app, ad-hoc sign, zip, pkg, DMG
macos/scripts/build-app.sh --skip-tests
macos/scripts/make-pkg.sh [WeaveText.app]   # 只重新打安装包 / rebuild just the installer package
macos/scripts/make-dmg.sh [WeaveText.app] [WeaveText-<版本>.pkg]   # 只重新打磁盘映像 / rebuild just the disk image
WEAVE_CORE=/path/to/core macos/scripts/build-app.sh   # 用另一份内核源码（例如 git archive 导出的已提交版本） / build another copy of the engine sources
```

产物 / Output: `macos/build/WeaveText.app`（arm64 + x86_64）、`macos/build/WeaveText-mac.zip`、
`macos/build/WeaveText-<版本>.pkg`（发布时的主下载 / the main release download）与 `macos/build/WeaveText-<版本>.dmg`。

安装包只用系统自带的 `pkgbuild` 与 `productbuild` 生成（`macos/scripts/pkg/`：preinstall、postinstall 与它们共用的
`weavetext-lib.sh`，Distribution 模板，中英双语的欢迎页与完成页）：装到 `/Library/Input Methods`，组件不可搬动（「安装器」
不会把它装到别处找到的同标识副本上），`hostArchitectures="x86_64,arm64"`，最低 macOS 13.0，左下角是程序自己画的图标。
脚本最后检查：`pkgutil --check-signature` 报告没有签名、`pkgutil --payload-files` 里有 `Library/Input Methods/WeaveText.app`、
`installer -pkginfo`，再用 `pkgutil --expand-full` 展开，核对包内程序的签名、脚本可执行、Distribution 与说明页。
*The package is made with the system's own `pkgbuild` and `productbuild` (`macos/scripts/pkg/`: preinstall, postinstall and
their shared `weavetext-lib.sh`, the Distribution template, bilingual welcome and conclusion pages): it installs into
`/Library/Input Methods`, the component is not relocatable (Installer won't redirect it onto a copy with the same
identifier found elsewhere), `hostArchitectures="x86_64,arm64"`, macOS 13.0 minimum, with the app's own icon at the bottom
left. The script then checks that `pkgutil --check-signature` reports no signature, that `pkgutil --payload-files` lists
`Library/Input Methods/WeaveText.app` and `installer -pkginfo` works, and expands it with `pkgutil --expand-full` to verify
the app's signature, executable scripts, the Distribution and the pages.*

磁盘映像只用 `hdiutil` 生成：里面是 `双击安装织文输入法.pkg` 与 `使用说明.txt`（程序本身不放进去：系统从磁盘映像「安装」App
的流程装不了输入法），卷名「织文输入法」，背景图由程序（`--render-dmg-background`）自己画；Finder 可用时用 AppleScript
摆好图标位置（第一次会请求自动化授权，拿不到就跳过，映像照常可用），最后压成只读的 UDZO，并用 `hdiutil verify` 与只读挂载后
核对安装包。
*The disk image is made with `hdiutil` only: `双击安装织文输入法.pkg` plus `使用说明.txt` (not the bare app: the system's
install-an-app-from-a-disk-image flow cannot install an input method), volume 织文输入法, a background the app draws itself
(`--render-dmg-background`); when Finder is available an AppleScript places the icons (it asks for automation consent the
first time and is skipped without it, the image still works), then it is compressed to read-only UDZO and checked with
`hdiutil verify` and by comparing the package on a read-only mount.*

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
| `WeaveText --register` | 登记、启用并选中「织文拼音」，可反复运行；成功时不输出，记到 `~/Library/Logs/WeaveText-install.log`；退出码 0 成功、2 已登记但还没列出（注销一次）、1 失败（安装包的 postinstall 使用） / register, enable and select 织文拼音, safe to repeat; silent on success, logged to `~/Library/Logs/WeaveText-install.log`; exit 0 ok, 2 registered but not listed yet (log out once), 1 failed (used by the package's postinstall) |
| `WeaveText --disable` | 停用输入源（卸载脚本使用） / disable the input source (used by the uninstall script) |
| `WeaveText --ime` | 不在「输入法」文件夹里也按输入法运行（调试用） / run as the input method even outside Input Methods (debugging) |
| `WeaveText --render-dmg-background <目录>` | 画磁盘映像的背景图 / draws the disk image background |

直接双击运行时，程序看自己在哪里：在 `~/Library/Input Methods` 或 `/Library/Input Methods` 里就是输入法，
在别处（磁盘映像、下载文件夹、应用程序文件夹）就打开安装窗口。
*Launched normally, the app checks where it is: inside `~/Library/Input Methods` or `/Library/Input Methods` it is the
input method; anywhere else (the disk image, Downloads, Applications) it opens the installer window.*

### 开发者用的脚本 / Developer scripts

```bash
macos/scripts/install.sh              # 开发用：复制到 ~/Library/Input Methods 并登记、启用 / development: copy, register, enable
macos/scripts/uninstall.sh            # 停用并删除两处副本（/Library 的那份用 sudo），保留用户词 / disable and remove both copies (sudo for /Library), keep user words
macos/scripts/uninstall.sh --purge    # 连同 ~/Library/Application Support/WeaveText 与偏好一起删除 / also user data and preferences
```

## 数据位置 / Where things live

| 内容 Content | 位置 Location |
|---|---|
| 程序 The app | `/Library/Input Methods/WeaveText.app`（安装包 / the package）或 / or `~/Library/Input Methods/WeaveText.app`（安装窗口 / the installer window） |
| 安装记录 Install log | `~/Library/Logs/WeaveText-install.log`，`/var/log/install.log` |
| 只读词库 Read-only dictionaries | `WeaveText.app/Contents/Resources/data/*.wvz` |
| 用户词 User words | `~/Library/Application Support/WeaveText/` |
| 专业词库 Domain dictionaries | `~/Library/Application Support/WeaveText/packs/<id>.wvz`（启动时自动载入 / loaded at startup） |
| 云端热词 Cloud hot words | `~/Library/Application Support/WeaveText/cloud/hotwords.tsv`（及 `.sig`） |
| 互联身份与已配对设备 WeaveLink identity and paired devices | `~/Library/Application Support/WeaveText/link/` |
| 收到的文件 Received files | `~/Downloads/WeaveText/` |
| 偏好 Preferences | `defaults read com.weavetext.inputmethod.WeaveText` |

## 已知限制 / Known limits

- 程序只做了自签名（ad-hoc），安装包没有签名，都没有公证：第一次打开安装包要按上面的方法放行一次；安装后会自动去掉
  隔离属性。
  *The app is ad-hoc signed only and the package is unsigned, neither is notarized: the package has to be allowed once as
  described above; the quarantine flag is removed after installing.*
- 安装包只为当时登录在屏幕前的用户登记并选中「织文拼音」；同一台 Mac 的其他用户登录后要在键盘设置里自己添加一次。
  *The package registers and selects 织文拼音 only for the user logged in at the screen; other users of the same Mac add it
  once in Keyboard settings.*
- 深色模式下磁盘映像窗口里的文件名是浅色字，压在浅色背景上不太清楚（直接下载 .pkg 就不经过这个窗口）。
  *In dark mode Finder draws the file names in the disk image window in a light colour over the light background
  (downloading the .pkg directly skips that window).*
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
