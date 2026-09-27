# 03 · 设置 App / Settings App

> Jetpack Compose + Material 3；颜色见 `01 §2.3`。原则：**首页 ≤ 6 个入口，二级页不超过一屏半。**
> Compose + M3; colours in `01 §2.3`. Rule: ≤ 6 entries on home; sub-pages fit ~1.5 screens.

---

## 1. 信息架构 / Information architecture

```
启动 Launch
 ├─ 未完成启用 → 启用引导（3 步）/ Onboarding
 └─ 已完成     → 首页 Home
                  ├─ 1 输入方案 Input schemes
                  │    ├─ 双拼方案（单选对话框）
                  │    └─ 模糊音（子页）
                  ├─ 2 语音引擎 Voice engines
                  │    ├─ 插件详情 + configSchema 表单
                  │    └─ 导入 .xipk（系统文件选择器 → 确认弹层）
                  ├─ 3 外观与手感 Look & feel（含「记录剪贴板」开关）
                  ├─ 4 词库 Dictionary
                  │    ├─ 用户词列表
                  │    ├─ 专业词库（逐个下载 / 删除）
                  │    └─ 云端热词（卡片内开关）
                  ├─ 5 互联 WeaveLink（配对、设备、剪贴板同步、最近传输）
                  └─ 6 关于 About
                       ├─ 使用帮助 / 隐私说明
                       └─ 开源许可
```

- 共 **6 个一级入口**，外加首页底部「试一试」输入框。剪贴板没有单独页面，唯一的开关放在「外观与手感」。
  *5 top-level entries plus a "try it" field. Clipboard has no page of its own; its single toggle lives in Look & feel.*
- 键盘内工具箱「设置」→ 首页；语音面板 ⚙ → 当前插件详情（深链 `weavetext://settings/voice/{pluginId}`）。
  *Deep links from keyboard.*

---

## 2. 通用组件 / Shared components

| 组件 | 规格 |
|---|---|
| 顶栏 TopAppBar | 首页用 `LargeTopAppBar`（标题 28sp 折叠为 22sp）；二级页 `TopAppBar`，左 `ic_arrow_back`，标题 22sp |
| 分组卡片 Group card | `surfaceContainer` 圆角 16dp，左右外边距 16dp，组间 24dp；组标题 `labelLarge 14sp primary`，在卡片外上方 8dp、左缩进 32dp |
| 列表项 ListItem | 单行 56dp / 双行 72dp；leading 图标 24dp `onSurfaceVariant`；trailing：`ic_chevron_right` / Switch / 当前值文本（`bodyMedium onSurfaceVariant`）|
| 卡片内分割 | 0.5dp `outlineVariant`，左缩进 56dp（有图标时）|
| 滑块 Slider | M3 离散 Slider，下方刻度文字 12sp |
| 分段按钮 | M3 `SingleChoiceSegmentedButtonRow`，高 40dp |
| 弹层 Sheet | `ModalBottomSheet`，确认类操作使用 |
| Snackbar | 撤销类操作（删除用户词、删除插件）|

---

## 3. 启用引导 / Onboarding（3 步）

单页，步骤卡片纵向排列；完成一项自动勾选并展开下一项（`onResume` 时检测状态）。
*Single page; steps auto-check on resume.*

```
┌──────────────────────────────────────┐
│                                      │
│   ◎  织文输入法                       │ Logo 56dp + 标题 headlineSmall
│      三步开始使用 · Get started       │ bodyMedium onSurfaceVariant
│                                      │
│  ┌────────────────────────────────┐  │
│  │ ✓  启用织文输入法                │  │ 已完成：前缀圆 ✓ success 色，
│  │    在系统设置中打开开关           │  │ 卡片折叠为单行
│  └────────────────────────────────┘  │
│  ┌────────────────────────────────┐  │
│  │ ②  切换为默认输入法              │  │ 当前步骤：primaryContainer 边框 1.5dp，
│  │    选择「织文输入法」              │  │ 展开显示说明 + 按钮
│  │                                │  │
│  │              [ 选择输入法 ]      │  │ FilledButton
│  └────────────────────────────────┘  │
│  ┌────────────────────────────────┐  │
│  │ ③  允许使用麦克风（可选）         │  │ 未到达：onSurfaceVariant，折叠
│  │    用于语音输入                   │  │
│  └────────────────────────────────┘  │
│                                      │
│  ┌────────────────────────────────┐  │
│  │ 在这里试试输入…                  │  │ 仅步骤 2 完成后出现
│  └────────────────────────────────┘  │
│                                      │
│            [ 完成 ]      跳过         │ 步骤 1、2 完成后「完成」可用
└──────────────────────────────────────┘
```

| 步骤 | 动作 Action | 完成判定 Done when |
|---|---|---|
| 1 启用 | `startActivity(Settings.ACTION_INPUT_METHOD_SETTINGS)` | `InputMethodManager.enabledInputMethodList` 含本 IME |
| 2 设为默认 | `imm.showInputMethodPicker()` | `Settings.Secure.DEFAULT_INPUT_METHOD` == 本 IME（`onResume` 与 `ACTION_INPUT_METHOD_CHANGED` 广播）|
| 3 麦克风 | `requestPermissions(RECORD_AUDIO)`；二次拒绝后按钮变「去系统设置」→ `ACTION_APPLICATION_DETAILS_SETTINGS` | `checkSelfPermission == GRANTED` |

- 步骤 1 说明文字附带安全提示：「系统会提示输入法可能收集输入内容。织文所有输入均在本机处理，仅语音会发送至你选择的语音服务商。」
  *Step 1 explains the system privacy warning.*
- 步骤 3 可跳过；跳过后首页顶部不再提示，语音面板内提示授权（`02 §12.3`）。

---

## 4. 首页 / Home

```
┌──────────────────────────────────────┐
│ 织文输入法                            │ LargeTopAppBar
│                                      │
│ ┌──────────────────────────────────┐ │ 状态横幅（仅异常时显示）：
│ │ ⚠ 织文不是默认输入法    [ 切换 ] │ │ errorContainer / tertiary
│ └──────────────────────────────────┘ │
│ ┌──────────────────────────────────┐ │
│ │ ⌨  输入方案                     › │ │ 副文字：全拼 26 键 · 小鹤双拼
│ │    全拼 26 键 · 小鹤双拼          │ │
│ ├──────────────────────────────────┤ │
│ │ 〰  语音引擎                     › │ │ 副文字：当前插件名
│ │    系统语音识别            │ │
│ ├──────────────────────────────────┤ │
│ │ ◐  外观与手感                   › │ │ 副文字：跟随系统 · 标准高度
│ │    跟随系统 · 标准高度            │ │
│ ├──────────────────────────────────┤ │
│ │ ▤  词库                         › │ │ 副文字：1,284 个用户词
│ │    1,284 个用户词                 │ │
│ └──────────────────────────────────┘ │
│ ┌──────────────────────────────────┐ │
│ │ ⓘ  关于                         › │ │ 单独一组：v0.1.0
│ └──────────────────────────────────┘ │
│                                      │
│ ┌──────────────────────────────────┐ │ 「试一试」：OutlinedTextField，
│ │ 在这里试试输入…                  │ │ 固定在底部（imePadding）
│ └──────────────────────────────────┘ │
└──────────────────────────────────────┘
```
- 每个入口都是**双行 ListItem**，副文字实时显示当前值，让用户不点进去也知道状态。
  *Two-line items show current values.*

---

## 5. 输入方案 / Input schemes

```
┌──────────────────────────────────────┐
│ ←  输入方案                           │
│                                      │
│ 启用的键盘（至少一个）                  │ 组标题
│ ┌──────────────────────────────────┐ │
│ │ ☑ 全拼 26 键                   ≡ │ │ Checkbox + 拖拽手柄排序
│ │ ☑ 双拼                          ≡ │ │ 顺序 = 「中/英」长按气泡顺序
│ │ ☐ 九键拼音                      ≡ │ │
│ │ ☐ 五笔 86                       ≡ │ │
│ │ ☑ 英文 26 键                    ≡ │ │
│ └──────────────────────────────────┘ │
│                                      │
│ 拼音                                  │
│ ┌──────────────────────────────────┐ │
│ │ 双拼方案                   小鹤 › │ │ → 单选对话框：小鹤/自然码/微软/
│ │ 显示双拼按键提示             [●] │ │    搜狗/智能ABC/拼音加加
│ │ 模糊音                    7 项 › │ │ → 子页
│ │ 自动纠错                     [●] │ │ 颠倒/漏打/多打自动改正，拼音上标红
│ │ 联想词                       [●] │ │ 上屏后推荐下一个词
│ │ 成对符号                     [●] │ │ 输入“（《【时补上另一半
│ └──────────────────────────────────┘ │
│                                      │
│ 五笔                                  │
│ ┌──────────────────────────────────┐ │
│ │ 显示字根提示                 [○] │ │
│ │ 五笔拼音混输                 [●] │ │ 拼音结果带灰色编码提示
│ └──────────────────────────────────┘ │
└──────────────────────────────────────┘
```

**模糊音子页 / Fuzzy pinyin**：一张卡片，`FilterChip` 流式排列：`z=zh` `c=ch` `s=sh` `n=l` `f=h` `r=l` `an=ang` `en=eng` `in=ing` `ian=iang` `uan=uang`，默认开启 `z=zh` `c=ch` `s=sh` `n=l` `an=ang` `en=eng` `in=ing`（可增删）；顶部一句说明「开启后，这些读音会互相匹配」。
*Fuzzy pinyin: one card of FilterChips; the seven common pairs are on by default and can be toggled.*

- 删除的设置（有意不做）：候选数量、候选字号、自动上屏阈值、中英标点单独开关——这些由默认值和内核自动处理。
  *Intentionally omitted: candidate count/size, commit thresholds, punctuation toggles.*

---

## 6. 语音引擎 / Voice engines

### 6.1 列表页 / List

```
┌──────────────────────────────────────┐
│ ←  语音引擎                    [导入] │ 右上 TextButton「导入」（ic_import）
│                                      │
│ 当前使用                              │
│ ┌──────────────────────────────────┐ │
│ │ ┌──┐ 系统语音识别      ◉  ⚙ │ │ 行高 ≥ 80dp（描述 2 行）
│ │ │系│ 调用手机自带的语音识别服务，  │ │ leading：icon.png 40dp，圆角 10dp
│ │ └──┘ 无需联网配置。     │ │ 标题 bodyLarge 500；描述 bodySmall
│ │      v1.0.3                        │ │ 2 行截断；版本 labelSmall
│ ├──────────────────────────────────┤ │
│ │ ┌──┐ 示例云端识别      ○  ⚙ │ │ trailing：RadioButton + 设置图标按钮
│ │ │云│ 示例：流式云端识别插件…        │ │ （无 configSchema 时 ⚙ 换成 ⓘ）
│ │ └──┘ v1.0.4                        │ │
│ ├──────────────────────────────────┤ │
│ │ ┌──┐ 示例插件 B              ○  ⚙ │ │
│ │ │B │ …                              │ │
│ ├──────────────────────────────────┤ │
│ │ ┌──┐ 示例插件 C              ○  ⓘ │ │
│ │ │C │ …                              │ │
│ └──────────────────────────────────┘ │
│                                      │
│ ⓘ 语音会发送到所选服务商进行识别，       │ bodySmall onSurfaceVariant
│   插件只能访问其声明的域名。            │
└──────────────────────────────────────┘
```

| 交互 | 行为 |
|---|---|
| 点击行主体 / 单选 | 设为当前引擎（`activation: single`，同时只有一个）；Snackbar「已切换到 示例云端识别」|
| ⚙ / ⓘ | 进入详情页 §6.2 |
| 长按行 | 无（删除放在详情页，防误删）|
| 空状态 | 插图位置放 `ic_waveform` 48dp + 「还没有语音引擎」+ FilledTonalButton「导入 .xipk 插件」|
| 加载失败的插件 | 行半透明（alpha 0.5），描述替换为 error 色「加载失败：minHostVersion 2.9.0 高于当前 2.8.0」|

图标来源：`.xipk` 内 manifest 的 `icon` 字段（如 `icon.png`，相对插件根目录解析）；缺失时用名称首字 + `primaryContainer` 底生成字母头像。
*Icon from manifest `icon`; fallback letter avatar.*

### 6.2 插件详情 + 配置表单 / Plugin detail & config form

```
┌──────────────────────────────────────┐
│ ←  示例云端识别                 │
│                                      │
│        ┌────┐                        │ 头部：icon 64dp r16，居中
│        │ 云 │                        │ 名称 titleLarge，版本 + id bodySmall
│        └────┘                        │
│   示例云端识别 · v1.0.4         │
│   com.example.asr.cloud           │
│   示例：通过 WebSocket 流式识别的插件    │ description 全文 bodyMedium
│                                      │
│        [ ✓ 当前引擎 ]                 │ 未选中时为 FilledButton「设为当前引擎」
│                                      │
│ 识别                              │ ← configSchema.section
│ ┌──────────────────────────────────┐ │
│ │ 自动标点                  [●] │ │ type: switch
│ │ 识别结果自动添加标点        │ │ helpText → supportingContent
│ └──────────────────────────────────┘ │
│ 通用                                  │ ← 无 section 的字段归入「通用」
│ ┌──────────────────────────────────┐ │
│ │ 识别语言                 普通话 › │ │ type: select → 单选对话框
│ │ 普通话（默认）  │ │
│ ├──────────────────────────────────┤ │
│ │ 过滤语气词                  开启 › │ │
│ └──────────────────────────────────┘ │
│                                      │
│ 网络访问                              │
│ ┌──────────────────────────────────┐ │
│ │ asr.example.com │ │ network.hosts，只读，bodySmall 等宽
│ │ api.example.com                  │ │
│ └──────────────────────────────────┘ │
│                                      │
│ 恢复默认设置          删除插件         │ TextButton；删除为 error 色
└──────────────────────────────────────┘
```

**configSchema 渲染规则 / Rendering rules**

| schema 字段 | 渲染 |
|---|---|
| `section` | 分组标题；按字段首次出现顺序排列分组；缺省归入「通用」|
| `label` | ListItem `headlineContent`；`required: true` 时后缀 ` *`（error 色）|
| `helpText` | `supportingContent`，最多 3 行，点击展开 |
| `type: switch` | trailing `Switch`；整行可点；值存 `"true"/"false"` 字符串（与 manifest `defaultValue` 一致）|
| `type: select` | trailing 当前值文本 + ›；点击 → `AlertDialog` 单选列表（选项 = `options`），选择即保存 |
| `type: text` | 行内展示当前值（空时显示 `placeholder` 或「未设置」）；点击 → `AlertDialog` 内 `OutlinedTextField`；若 `key` 含 `key/secret/token/password` 或 `secret: true` → 密码输入 + 显示/隐藏眼睛图标，列表中显示 `••••末4位` |
| `type: number`（兼容）| 同 text，`KeyboardType.Number` |
| 未知 type | 以只读文本行显示 `label：值`，并在 Logcat 警告；不崩溃 |
| `defaultValue` | 未设置时的值；「恢复默认设置」清空全部用户值 |
| 校验 | `required` 且为空时，「设为当前引擎」按钮禁用并提示「请先填写：API Key」|

- 值变更**立即保存**（无「保存」按钮），通过 `host.config` 通知插件；配置存于 `DataStore`，密钥类字段用 `EncryptedSharedPreferences`/Keystore 加密。
  *Values save instantly; secret fields are encrypted.*

### 6.3 导入 .xipk / Import

1. 点「导入」→ `ActivityResultContracts.OpenDocument(arrayOf("application/octet-stream", "application/zip", "*/*"))`。
2. 解析 manifest 后弹出 `ModalBottomSheet` 确认：

```
┌──────────────────────────────────────┐
│               ────                    │
│  ┌──┐  示例插件 B                    │
│  │B │  v1.2.0 · speech                 │
│  └──┘                                 │
│  实时流式识别，支持中英混说…              │
│                                      │
│  将会连接 / Network                   │
│   • dashscope.aliyuncs.com            │
│                                      │
│  ⚠ 已安装 v1.1.0，将会升级              │ 同 id 情况；更低版本显示「降级」警告
│                                      │
│   [ 取消 ]              [ 安装 ]      │
└──────────────────────────────────────┘
```
3. 失败情况（非 zip、缺 manifest、`type` 非 speech、`minHostVersion` 过高、`entry` 不存在）→ 弹层直接显示错误原因 + 「关闭」。
4. 安装成功 → 列表中出现并高亮 1 秒；若是第一个语音插件，自动设为当前。
- 也支持从文件管理器用「打开方式 → 织文」直接打开 `.xipk`（intent-filter `ACTION_VIEW`）。

---

## 7. 外观与手感 / Look & feel

```
┌──────────────────────────────────────┐
│ ←  外观与手感                          │
│ ┌──────────────────────────────────┐ │
│ │  ┌────────────────────────────┐  │ │ 实时预览：缩小版键盘（真实 KeyboardView，
│ │  │  Q W E R T Y U I O P       │  │ │ 非交互，scale 0.6），随下方设置即时变化
│ │  │   A S D F G H J K L        │  │ │
│ │  └────────────────────────────┘  │ │
│ └──────────────────────────────────┘ │
│ 主题                                  │
│ [ 浅色 │ 深色 │ 跟随系统 ]              │ SegmentedButton，默认跟随系统
│                                      │
│ 键盘高度                              │
│ ○────○────○────●────○                 │ 5 档离散 Slider：紧凑…高，默认较高（06 §2）
│ 紧凑  较矮  适中  较高  高             │
│                                      │
│ 按键手感                              │ 两行：按键音（风格 + 音量）、按键震动
│ ┌──────────────────────────────────┐ │ （关 / 轻 / 中 / 强），默认都关，见 06 §4
│ │ 按键音 [关][跟随系统][清脆]…        │ │
│ │ 按键震动 [ 关 │ 轻 │ 中 │ 强 ]      │ │
│ └──────────────────────────────────┘ │
│                                      │
│ ┌──────────────────────────────────┐ │
│ │ 按键气泡                      [●] │ │ 按下时放大显示字符
│ │ 记录剪贴板                    [○] │ │ 隐私：默认关，首次打开剪贴板面板时询问；关闭时只显示当前系统剪贴板
│ │ 清空剪贴板历史                     │ │ 确认后删除全部历史（含已固定），常用语不受影响
│ └──────────────────────────────────┘ │
└──────────────────────────────────────┘
```
- 键盘里的「键盘调节」浮层与这里的「键盘高度」共用同一个值。
  *Keyboard-side height adjust shares the value.*

---

## 8. 词库 / Dictionary

```
┌──────────────────────────────────────┐
│ ←  词库                               │
│ ┌──────────────────────────────────┐ │
│ │ 用户词                  1,284 个 › │ │ → 用户词列表
│ │ 系统词库         2026.09 · 42 万词 │ │ 只读信息
│ │ 专业词库           医学、法律  2 个 › │ │ → 专业词库页
│ └──────────────────────────────────┘ │
│ ┌──────────────────────────────────┐ │
│ │ 云端热词                     [○] │ │ 默认关；只下载公开热词库、不上传
│ │ 热词  1280 个词 · 9 月 27 日检查  立即更新 │ │ 开启后出现
│ └──────────────────────────────────┘ │
│ ┌──────────────────────────────────┐ │
│ │ ⤓  导入用户词                      │ │ OpenDocument(text/plain)
│ │ ⤒  导出用户词                      │ │ CreateDocument → weavetext-userdict-YYYYMMDD.txt
│ └──────────────────────────────────┘ │
│ ┌──────────────────────────────────┐ │
│ │ 清除学习记录                        │ │ error 色文字，二次确认对话框
│ └──────────────────────────────────┘ │
│  导入格式：每行「词语<Tab>拼音<Tab>词频」，   │
│  拼音用空格分隔，词频可省略。              │
└──────────────────────────────────────┘
```

**用户词列表 / User words**：顶部 `SearchBar`（汉字或拼音首字母过滤）；列表项 `词语`（bodyLarge）+ `ni hao · 使用 32 次`（bodySmall）；左滑删除（Snackbar 撤销）；右上「添加」→ 对话框（词语 + 拼音，拼音可自动生成）。排序：最近使用优先。
*Search, swipe-to-delete with undo, add dialog.*

- 导入完成后 Snackbar：「导入 356 个，跳过 12 个（格式错误）」。*Import result summary.*

**专业词库 / Domain packs**：一张卡片列出 14 个包（名称、说明 · 词数 · 大小），行尾「下载 / 取消 / 重试 / 删除」，下载中显示进度；
删除前确认。页首说明「不会排到常用词前面，选过一次后会自动靠前」，页尾列出词表来源与许可。
*One card listing the packs with download / cancel / retry / delete and progress; a note that packs never outrank
common words; sources and licences at the bottom.*

---

## 8.1 互联 / WeaveLink

```
┌──────────────────────────────────────┐
│ ←  互联                               │
│ ┌──────────────────────────────────┐ │
│ │ ▭▯ 织文互联                  [●] │ │ 默认关；说明端到端加密、不经服务器
│ └──────────────────────────────────┘ │
│ 本机                                  │
│ │ 名称  安全码 AB12-…      Pixel 9 › │ │ 改名对话框
│ │ 同步剪贴板                    [●] │ │
│ 我的设备                              │
│ │ 工作用的 MacBook   已连接 · Mac  › │ │ → 对话框：取消配对
│ 附近的设备                            │
│ │ 会议室 Mac mini          配对     │ │ → 输入 6 位配对码
│ │ 用地址配对                      › │ │ 地址 + 配对码
│ 最近传输（进度条）                     │
│  收到的文件保存在「下载/WeaveText」……   │
└──────────────────────────────────────┘
```

- 扫描电脑上的二维码（系统相机）打开 `weavelink://pair?…`，直接弹出确认配对。
  *Scanning the computer's QR code opens the pairing confirmation directly.*
- 系统分享菜单「发送到电脑」、键盘工具箱「发到电脑」共用这里的连接。 *The share target and toolbox reuse it.*

---

## 9. 关于 / About

```
┌──────────────────────────────────────┐
│ ←  关于                               │
│             ◎                         │ Logo 72dp
│         织文输入法                     │
│      WeaveText · v0.1.0 (12)          │
│ ┌──────────────────────────────────┐ │
│ │ 使用帮助                        › │ │ 手势说明（滑动光标、左滑清空等）静态页
│ │ 隐私说明                        › │ │ 本地处理 / 语音插件数据流 说明
│ │ 开源许可                        › │ │ 读取 THIRD_PARTY 生成的 licenses 列表
│ │ 反馈问题                        ↗ │ │ 打开仓库 Issues 页
│ └──────────────────────────────────┘ │
└──────────────────────────────────────┘
```

---

## 10. 设置项总表 / Complete settings inventory

为了守住「精简」原则，下表即**全部**用户可见设置（共 22 项）。新增须在 PR 中说明理由。
*This is the complete list of user-facing settings (22). Additions need justification.*

| # | 页面 | 设置 | 默认 |
|---|---|---|---|
| 1 | 输入方案 | 启用的键盘 + 顺序 | 全拼 26、英文 26 |
| 2 | 输入方案 | 双拼方案 | 小鹤 |
| 3 | 输入方案 | 显示双拼按键提示 | 开 |
| 4 | 输入方案 | 模糊音 | 常用 7 组开（z/zh c/ch s/sh n/l an/ang en/eng in/ing） |
| 5 | 输入方案 | 五笔字根提示 | 关 |
| 6 | 输入方案 | 五笔拼音混输 | 开 |
| 7 | 语音引擎 | 主引擎 / 同时使用（06 §6） | 第一个安装的 / 无 |
| 8 | 语音引擎 | 插件配置（由插件定义） | manifest defaultValue |
| 9 | 外观 | 主题 | 跟随系统 |
| 10 | 外观 | 键盘高度 | 较高 |
| 11 | 外观 | 按键震动 | 关 |
| 12 | 外观 | 按键音（风格 + 音量） | 关 · 50% |
| 13 | 外观 | 按键气泡 | 开 |
| 14 | 外观 | 记录剪贴板 | 关（首次打开剪贴板面板时询问 / asked on first open） |
| 15 | 键盘内 | 繁体输出 / 单手模式 / 悬浮键盘（工具箱开关） | 关 |
| 16 | 键盘内 | 语音「点按/按住」模式（面板内记忆） | 点按 |
| 17 | 输入方案 | 联想词 | 开 |
| 18 | 输入方案 | 成对符号 | 开 |
| 19 | 词库 | 专业词库（逐个下载） | 未安装 |
| 20 | 词库 | 云端热词 | 关 |
| 21 | 输入方案 | 自动纠错 | 开 |
| 22 | 互联 | 织文互联（含本机名称） | 关 |
| 23 | 互联 | 同步剪贴板 | 开（仅在互联开启时生效） |
