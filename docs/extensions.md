# 扩展与插件市场 / Extensions and the plugin market

织文把「基础输入」之外的东西都做成扩展：可以在插件市场里启用、安装、卸载，彼此独立，关掉的扩展不会在键盘、菜单或设置里留下入口。
手机与 Mac 共用同一份市场目录（`data/market/catalog.json`）和同一种主题格式。

Everything beyond basic typing is an extension: enabled, installed or removed from the plugin market, independent of
each other, and an extension that is off leaves no entry in the keyboard, menus or settings. Android and Mac share one
market catalog (`data/market/catalog.json`) and one theme format.

## 基础与扩展 / Base and extensions

基础功能始终存在，不进市场：拼音、双拼、英文（含 26 键、九键、14 键）、候选与联想、符号与 Emoji、光标编辑、剪贴板、设置本身，
以及基础主题（清爽、墨夜、薄荷、暮紫；手机另有动态取色）和基础布局（清爽、经典）。

Always present, never in the market: pinyin, shuangpin, English (26-key, 9-key, 14-key), candidates and predictions,
symbols and emoji, cursor editing, the clipboard, settings, the base themes (fresh, ink, mint, dusk; plus dynamic on
Android) and the base layouts (fresh, classic).

| 类别 kind | 名称 | 来源 source | 说明 |
| --- | --- | --- | --- |
| `feature` | 功能 | `builtin` | 随应用提供的功能模块（语音、翻译、表情收纳袋、常用语、互联、云端热词、计算器），在市场里启用 / 停用 |
| `scheme` | 输入方案 | `builtin` | 手写、五笔 86；停用后不出现在键盘切换与方案列表里 |
| `theme` | 主题 | `base` / `bundled` / 远程 | 基础主题始终可选；其余安装后才出现在主题列表 |
| `layout` | 布局 | `base` / `bundled` / 远程 | 仅手机；安装布局时一并安装它默认搭配的主题 |
| `speech` | 语音引擎 | GitHub 仓库 / 本地导入 | Lua 插件（见 [插件宿主](plugin-host.md)），需先启用「语音输入」 |
| `translation` | 翻译 | 平台插件 | 离线翻译插件（见 [离线翻译插件](offline-translation-plugins.md)），需先启用「翻译」 |
| `dict` | 词库 | `dictpacks.json` | 专业词库包，下载后参与候选 |

本轮的原生功能模块随应用分发，但每个模块只通过扩展注册表接入：工具栏按钮、工具箱格子、面板、
菜单项、设置页和后台服务都先问注册表，模块停用时一律不出现、不启动。

In this implementation, native feature modules ship inside the app, but each one is wired in only through
the extension registry: toolbar buttons, toolbox cells, panels, menu items, settings pages and background services all
ask the registry first, so a module that is off neither appears nor starts.

## 市场目录 / Catalog format

```json
{"version": 1,
 "kinds": [{"id": "feature", "name": "功能"}, …],
 "items": [
   {"id": "voice", "kind": "feature", "name": "语音输入", "summary": "…", "icon": "mic",
    "platforms": ["android", "mac"], "source": "builtin", "default": true},
   {"id": "sakura", "kind": "theme", "name": "樱粉", "summary": "…",
    "platforms": ["android", "mac"], "source": "bundled", "file": "themes/theme-sakura.json"},
   {"id": "x", "kind": "theme", "name": "…", "source": "remote", "url": "https://…", "sha256": "…", "bytes": 1234}
 ]}
```

- `id` 在同一 `kind` 内唯一；`kind` + `id` 是扩展的完整标识，偏好里写作 `kind:id`。
- `source`：`builtin` 随应用的功能（启用即可用）；`base` 基础内置（不可卸载）；`bundled` 安装包内附带、安装时复制到用户目录；
  `remote` 从公开 [weavetext-market](https://github.com/sheep-programmer/weavetext-market) 读取，下载后核对大小与 SHA-256。
- `local`：用户导入的 JSON 主题或布局；不得替换基础主题或布局，大小限制为 256 KB。
- `default` 只对 `builtin` 有意义：首次运行时是否启用。已有用户升级后保持原来能看到的功能全部启用。
- `platforms` 不含当前平台的条目不显示。

- `id` is unique within its `kind`; `kind:id` is the full key used in preferences.
- `source`: `builtin` app-shipped feature (enable to use); `base` built in, cannot be removed; `bundled` shipped in the
  package and copied to the user directory on install; `remote` assets come from the public weavetext-market repository with byte-count and SHA-256 verification.
- `local` data comes from a JSON import (up to 256 KiB) and cannot replace base presets.
- `default` applies to `builtin` only: whether it starts enabled. Upgrading users keep every feature they could see.
- Entries whose `platforms` exclude the current platform are hidden.

## 主题格式 / Theme format

主题文件与手机原有格式相同：`{"version":1,"kind":"theme","id","name","description","light":{…},"dark":{…}}`，
调色板键见 `android/app/src/main/assets/styles/theme-fresh.json`。Mac 取 `accent`、`background`、`candidate` 等键生成
候选窗与设置窗口的配色，其余键忽略；亮色、暗色各取一套。Mac 的渐变背景使用第一个色标。

Theme files keep Android's format. The Mac reads `accent`, `background`, `candidate` and the like for the candidate
window and settings, ignoring the rest, one set each for light and dark.

## 状态保存 / Stored state

- 手机：`SharedPreferences` 键 `ext_enabled`（已启用的 `kind:id` 集合）；已安装的主题、布局放在 `filesDir/extensions/<kind>/<id>.json`。
- Mac：`UserDefaults` 键 `extensionsEnabled`；已安装文件放在用户目录 `extensions/<kind>/<id>.json`。
- 卸载正在使用的主题或布局时回到默认的清爽。

- Android: `ext_enabled` holds the enabled `kind:id` set; installed themes and layouts live in
  `filesDir/extensions/<kind>/<id>.json`.
- Mac: `extensionsEnabled` in UserDefaults; files in the user directory under `extensions/<kind>/<id>.json`.
- Removing the theme or layout in use falls back to fresh.

## 本轮界面与验证 / UI and verification

手机设置首页以「你的键盘」「插件市场」「已添加的工具」分组；Mac 增加概览页，侧栏按键盘、扩展与工具、应用分组。
市场支持分类、搜索、已添加筛选、功能启停、数据安装/卸载和本地 JSON 导入；语音引擎、翻译插件与词库从市场进入各自的安装管理器。
Mac 系统输入法菜单只保留设置、中英切换、输入方案与工具子菜单；连有设备时显示互联子菜单，停用功能的入口不显示。

旧用户首次升级时会自动安装当前或已保存风格引用的附加主题、布局。经典布局现在搭配基础清爽主题；原来使用经典 + 自动主题的用户
会安装暖橙并保留该配色。卸载被已安装布局或保存风格引用的扩展时，先提示调整依赖。

计算器启停同时控制宿主的等号结果与内核的 v 模式（`features.calculator`）。关闭互联停止服务，关闭云端热词卸载当前词表并取消更新，
关闭语音取消录音和结果回调。基础输入始终保留，停用当前附加输入方案时回退到拼音。

Android 回归截图可输出到临时目录，避免重写已有基准：
`./gradlew :app:testDebugUnitTest -Pweave.skipRust=true -Pweave.snapshotDir=/tmp/weave-snapshots -x buildDicts -x syncDicts`
（跳过原生构建仅用于 JVM 单测；发布前必须重新构建 Rust。）
Mac 新界面可由 `WeaveText --snapshot-extensions <directory>` 生成亮暗截图；命令使用独立偏好与临时目录。

## 公开仓库与在线目录

官方公开仓库：[sheep-programmer/weavetext-market](https://github.com/sheep-programmer/weavetext-market)。
在市场点「刷新」获取 `catalog.json`；失败时保留上次有效目录，首次离线时使用随应用提供的目录。
在线目录仅可更新／添加主题与布局数据，不得替换基础输入、基础主题、基础布局或原生功能模块。
安装过程中显示连接、字节进度和取消入口；大小、校验或数据格式不匹配时不替换已安装文件。
手机安装布局时先安装它声明的主题；Mac 只加载支持自身平台的主题。已安装数据与新目录校验值不一致时显示「更新」。
目录读取和安装只在明确操作后进行，不进入每次按键处理，也不上传输入内容或 GitHub 私人凭据。

语音插件仓库可填写 `https://github.com/sheep-programmer/weavetext-market/tree/main/plugins`。
其中 HTTP PCM 适配器代码独立编写，需要用户自己的兼容 HTTPS 服务，不包含模型、账户或密钥。
