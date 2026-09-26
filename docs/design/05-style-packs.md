# 05 · 键盘风格包 / Keyboard Style Packs

> 版本 v1（`"version": 1`）· 实现：`android/app/src/main/java/com/weavetext/ime/style/`
> Version 1 · Implementation: `android/app/src/main/java/com/weavetext/ime/style/`

---

## 1. 模型 / Model

风格 = **布局风格**（Layout）× **配色主题**（Theme，亮/暗两版）× **用户微调**（Overrides）。三者在风格切换时由
`StyleRepository` 一次性解析、合并成不可变的 `KeyboardStyle`（含 `KbPalette` 颜色与 `KbMetrics` 尺寸）并缓存；
键区、候选栏、工具栏、面板与气泡只读 `KeyboardStyle`，`onDraw` 不分配对象。

A style is **layout × theme (light/dark) × user overrides**. `StyleRepository` parses and merges them once per switch into an
immutable, cached `KeyboardStyle` (colours in `KbPalette`, sizes in `KbMetrics`). Every keyboard view reads only from it;
`onDraw` never allocates.

| 来源 Source | 位置 Location |
|---|---|
| 内置预设 Built-in presets | `assets/styles/layout-*.json`、`assets/styles/theme-*.json` |
| 当前选择 Current choice | 设置键 `style_layout`、`style_theme`（`auto` = 跟随布局默认）、`style_overrides`（JSON）、`style_stamp` |
| 我的风格 Saved packs | `filesDir/styles/packs/<id>/style.json` + 图片 |
| 微调背景图 Tweak background | `filesDir/styles/current/bg.*` |

设置 App 写设置键，键盘监听后即时重新解析，无需重启输入法。亮/暗由「深浅色」（浅色 / 深色 / 跟随系统）决定。
The settings app writes the keys; the keyboard listens and re-resolves immediately. Light/dark follows the existing
light / dark / system switch.

## 2. 通用规则 / General rules

- 每个文件都有 `version`（整数）。高于当前支持版本（1）的文件拒绝导入。
  Every file has an integer `version`; files newer than 1 are rejected.
- **未知字段忽略**；已知字段类型、取值范围或枚举不合法时整份拒绝（导入时给出中文原因）。
  **Unknown fields are ignored**; a known field with a wrong type, range or enum rejects the whole file.
- `extends`: 以某个内置项为底，逐键**深合并**（对象合并，数组与其它值整体替换），最多 4 层。
  `extends` deep-merges onto a built-in (objects merge, arrays and scalars replace), up to 4 levels.
- `id`：小写字母、数字、`-`、`_`，最长 40。颜色：`#RRGGBB` 或 `#AARRGGBB`。尺寸单位均为 dp。
  Ids: lowercase letters, digits, `-`, `_`, up to 40 chars. Colours `#RRGGBB` / `#AARRGGBB`. All sizes in dp.
- 缺省字段取内置默认（即「清爽」布局 `layout-fresh.json` 中列出的值）。
  Omitted fields take the built-in defaults, i.e. the values listed in `layout-fresh.json`.

## 3. 布局风格 / Layout style（`"kind": "layout"`）

| 字段 Field | 类型 / 取值 Type / values | 默认 Default | 说明 Meaning |
|---|---|---|---|
| `id` `name` `description` | 字符串 | — | 标识、显示名、一句话特征 / id, name, one-line summary |
| `order` | 整数 | 100 | 预设排序 / preset order |
| `theme` | 主题 id | `fresh` | 默认配色 / default theme |
| `geometry.gapH` | 0–16 | 6 | 左右键距 / horizontal key gap |
| `geometry.gapV` | 2–20 | 10 | 行间距（行高 − 键高）/ vertical gap |
| `geometry.padH` | 0–16 | 3 | 键区左右留白 / side padding |
| `geometry.rowScale` | 0.8–1.25 | 1.0 | 行高比例 / row pitch scale |
| `geometry.radius` / `radiusLarge` | 0–24 / 0–28 | 6 / 8 | 普通键 / 大键（九键、数字）圆角 / corner radii |
| `qwerty.rows` | 4 行记号数组（可在前面加一行数字，共 5 行） | 见 fresh | 26 键行定义，见 §3.1 / QWERTY rows, see §3.1 |
| `qwerty.rowsEnglish` | 同上，可省 | — | 英文键盘单独的行 / separate English rows |
| `qwerty.letterCase` | `upper` / `lower` | `upper` | 中文键盘字母大小写 / letter case (Chinese) |
| `qwerty.hint` | `top` / `topRight` | `top` | 副标签（数字符号提示）位置 / hint position |
| `qwerty.showHints` | 布尔 | true | 默认是否显示副标签 / show hints by default |
| `qwerty.pillKeys` | 功能键名数组 | `[]` | 画成胶囊的键 / keys drawn as pills |
| `qwerty.letterSize` `hintSize` `punctSize` `funcSize` | 数字 | 22 / 10.5 / 20 / 16 | 字号 / text sizes |
| `t9.columns` | 5 个权重（0.3–4） | `[1.1,1.3,1.3,1.3,1.1]` | 第 0 列为左侧列表 / column 0 is the side list |
| `t9.right` | 记号数组，`:n` 为占行数，合计 4 | `delete, reset, enter:2` | 右列 / right column |
| `t9.bottom` | 记号数组，`:n` 为占列数，合计 4 | `symbol, number, space, lang` | 底行第 0–3 列 / bottom row |
| `t9.side` / `t9.sideColor` | `punct`/`symbols`；`func`/`key` | `punct` / `func` | 左列内容与底色 / side list content and colour |
| `numpad.columns` | 5 个权重 | `[1,1.3,1.3,1.3,1.1]` | 数字键盘列宽 / numpad columns |
| `labels.symbol` / `labels.number` | ≤ 4 字 | `符` / `123` | 键面文字 / key labels |
| `labels.lang` | `zhEn` / `globe` | `zhEn` | 中/英 或地球图标 / language key |
| `labels.shift` | `icon` / `split` | `icon` | `split`：中文时 Shift 位常驻「分词」/ always show 分词 |
| `labels.space` | `iconMic` / `text` / `lang` / `none` | `iconMic` | 空格键内容 / space bar content |
| `labels.spaceText` | ≤ 8 字 | `空格` | `text` 时显示 / text for `text` |
| `labels.enter` | `auto` / `text` | `auto` | `text`：总是文字（换行/确认/发送…）/ always text |
| `labels.enterAccent` | `action` / `always` / `never` | `action` | 回车何时用强调色 / when Enter is accented |
| `candidates.english` | `list` / `strip3` | `list` | 英文联想：滚动列表 / 居中三格 / English suggestions |
| `candidates.preedit` | `inline` / `floating` | `inline` | 组合串在栏内左上 / 栏上方浮层 / composing text |
| `candidates.expandIcon` | `chevron` / `grid` | `chevron` | 展开按钮 / expand icon |
| `candidates.textSize` | 14–24 | 19 | 候选字号 / candidate size |
| `candidates.dividers` | 布尔 | false | 候选间竖线 / dividers |
| `toolbar.items` | 2–7 项：`menu keyboard voice cursor clipboard hide emoji settings` | 前 6 项 | 顺序即显示顺序，须含 `menu` 或 `settings` |
| `toolbar.icons` | `outline` / `filled` | `outline` | 线性 / 面性图标 / icon style |
| `toolbar.show` | `idle` / `always` | `idle` | `always`：输入中左侧常驻首个图标 / keep first icon while composing |
| `toolbar.align` | `spread` / `edges` | `spread` | 均分 / 首项靠左其余靠右 / placement |
| `toolbar.buttons` | `none` / `circle` | `none` | 图标下垫按键色圆底 / circular backing |
| `toolbar.menuIcon` / `menuAccent` | `logo`/`grid`；布尔 | `logo` / false | 菜单图标及是否着强调色 |
| `popup.bubble` | `float` / `attached` / `none` | `float` | 按键气泡：悬浮 / 与按键相连 / 无 |
| `popup.radius` `textSize` `altRadius` | 数字 | 10 / 30 / 10 | 气泡圆角、字号、长按候选框圆角 |
| `symbols.indicator` | `underline` / `pill` | `underline` | 符号面板分类选中样式 |

### 3.1 行记号 / Row tokens

- 一串小写字母（如 `"qwertyuiop"`）展开为若干字母键；前 3 行合计须恰好 26 个不重复字母，底行不能放字母。
  A run of letters expands to letter keys; the first three rows must hold exactly the 26 letters once; the bottom row holds none.
- 功能键：`shift delete symbol number emoji lang comma period space enter`；底行必须有 `space`，全体必须有 `delete`、`enter`。
  Function tokens as listed; the bottom row needs `space`, and `delete` and `enter` must appear somewhere.
- `gap`：留空，不产生按键，触控区由两侧键平分。
  `gap` is empty space; its touch area is split between neighbours.
- 可选数字行：共 5 行时首行须恰好含 `0`–`9` 十个数字（如 `"1234567890"`，可加 `gap`），其后 4 行规则同上；5 行均分键区高度（见内置「数字行」布局）。
  Optional number row: with five rows the first must hold exactly the digits 0–9; the other four follow the rules above and the rows share the height.
- `名称:权重` 设置键宽（字母键 = 1）；键 0.5–6，`gap` 0.05–3。每行按 `max(10, 最宽行)` 等分，较窄的行居中。
  `name:weight` sets the width (letter = 1); keys 0.5–6, `gap` 0.05–3. Rows share `max(10, widest row)` units; narrower rows are centred.

```json
"rows": [["qwertyuiop"], ["asdfghjkl"],
         ["shift:1.2", "gap:0.3", "zxcvbnm", "gap:0.3", "delete:1.2"],
         ["number:2", "comma", "space:4", "lang", "enter:2"]]
```

## 4. 配色主题 / Theme（`"kind": "theme"`）

顶层：`id` `name` `description` `order`，`light` 与 `dark` 两版（都必须有），`dynamic: true` 表示 Android 12+ 用系统壁纸取色覆盖基础色
（低版本使用文件内颜色）。
Top level: `id`, `name`, `description`, `order`, required `light` and `dark` variants; `dynamic: true` replaces the base colours
with the wallpaper palette on Android 12+ (file colours below that).

### 4.1 每版字段 / Variant fields

| 字段 Field | 必填 Req. | 缺省推导 Derived default | 说明 Meaning |
|---|---|---|---|
| `background` | ✔ | — | 颜色字符串或对象，见 §4.3 / colour or object |
| `key` `label` `accent` | ✔ | — | 字符键底色、文字、强调色 / key face, text, accent |
| `keyPressed` | | key 向 label 混 12% | 字符键按下 / pressed key |
| `keyFunc` / `keyFuncPressed` | | 背景向 label 混 10% / 再混 12% | 功能键 / function keys |
| `keyShadow` | | 背景加深 | 阴影色 / shadow colour |
| `accentPressed` / `onAccent` | | accent 加深 15% / 白或近黑（按对比度） | 强调键按下、强调键上的文字 |
| `labelHint` `labelSecondary` `labelDisabled` | | label 向 key 混 45% / 35% / 50% | 副标签、次要文字、禁用 |
| `candidate` | | accent | 首选候选文字 / first candidate text |
| `candidatePill` | | 背景向 accent 混 18% | `candidateStyle: pill` 时的胶囊底色 |
| `icon` `divider` `toolbarActive` `accentSoft` | | 由 label / 背景 / accent 推导 | 图标、分隔线、工具栏按下、激活态底 |
| `popup` `popupSelected` `popupShadow` `card` `danger` `voiceWave` `scrim` | | 见代码 `StyleParser.palette` | 气泡、选中、阴影、卡片、危险、波形、遮罩 |
| `shadow` | | `bar` | `bar` 底部实色条 / `soft` 柔和投影 / `none` |
| `stroke` / `strokeWidth` | | 无 / 0（0–3） | 按键描边 / key outline |
| `weight` | | 400 | ≥ 500 时字母用中等字重 / medium letters |
| `candidateStyle` | | `text` | `text` 彩色字 / `pill` 胶囊底 |

### 4.2 无障碍 / Accessibility

所有内置主题由单元测试 `StyleContrastTest` 自动断言（半透明按键先叠到背景上，渐变逐色检查）：
文字对按键、功能键、背景、气泡 ≥ 4.5:1；首选候选对背景（或胶囊）≥ 4.5:1；强调键文字 ≥ 4.5:1；副文字、图标、按下态 ≥ 3:1。
Built-in themes are checked by `StyleContrastTest` (translucent keys composited first, every gradient stop checked):
text on keys / function keys / background / popups ≥ 4.5:1, first candidate ≥ 4.5:1, text on accent ≥ 4.5:1, secondary text,
icons and pressed state ≥ 3:1. 用户微调的强调色会自动向文字色靠拢直到首选候选 ≥ 4.5:1。

### 4.3 背景 / Background

```json
"background": "#E8EBF0"
"background": { "type": "gradient", "colors": ["#E6E0F7", "#F5E4EF"], "angle": 0 }
"background": { "type": "image", "colors": ["#223344"], "image": "bg.jpg", "blur": 8, "dim": 0.3 }
```

`colors` 1–4 个（渐变至少 2 个）；`angle` 0–360（0 = 从左到右）；`image` 为风格包内文件名；`blur` 0–25 dp；`dim` 0–0.8。
图片在解析时解码、缩放、模糊一次；渐变以首末色中点作为导航栏的代表色（候选栏与分类的渐隐直接擦成透明，不用代表色）。
`colors`: 1–4 (≥ 2 for gradients); `angle` 0–360; `image` is a file inside the pack; `blur` 0–25 dp; `dim` 0–0.8. Images are
decoded, scaled and blurred once; gradients use the midpoint of the first and last stop as the navigation-bar colour (edge
fades erase to transparent instead of painting a representative colour).

## 5. 用户微调 / Overrides（`style_overrides`）

| 字段 Field | 范围 Range | 说明 Meaning |
|---|---|---|
| `accent` | 颜色 | 强调色（回车、选中、首选候选）/ accent colour |
| `radius` | 0–20 | 普通键圆角，大键 +2 / key radius |
| `gap` | 0.5–1.6 | 键距缩放 / gap scale |
| `textScale` | 0.85–1.2 | 键内字号缩放 / key text scale |
| `hints` | 布尔 | 显示副标签 / show hints |
| `shadow` | 布尔 | 按键阴影 / key shadows |
| `keyOpacity` | 0.3–1 | 按键不透明度（配合背景图）/ key opacity |
| `background` | 同 §4.3 | 覆盖主题背景 / replaces the theme background |

键盘高度沿用原有「键盘高度」设置。 Key height stays the existing height setting.

## 6. 风格包文件 / Pack file（`.wvskin`）

zip，根目录放 `style.json`，可选 1–3 张图片（`[A-Za-z0-9_-]{1,32}.(png|jpg|jpeg|webp)`）。
A zip with `style.json` at the root and 1–3 optional images.

```json
{
  "version": 1,
  "kind": "pack",
  "name": "晚霞",
  "layout": { "extends": "plain", "geometry": { "radius": 9 } },
  "theme": { "extends": "dusk" },
  "overrides": { "accent": "#B8185A" }
}
```

- `name` 1–24 字符；`layout` / `theme` 可只写 `extends`（引用内置项），也可带完整或部分定义。
  `name` 1–24 chars; `layout` / `theme` may just reference a built-in or add partial/complete definitions.
- 导入校验 / Import checks：`style.json` ≤ 256 KB，单张图片 ≤ 6 MB 且可解码、边长 ≤ 4096，整包 ≤ 8 MB；
  子目录与其它文件忽略（不会写出临时目录）；布局与主题完整解析通过后才显示真实预览请用户确认。
  `style.json` ≤ 256 KB, images ≤ 6 MB, decodable, ≤ 4096 px, pack ≤ 8 MB; nested paths and other files are ignored; the layout
  and theme must parse fully before a real preview is shown for confirmation.
- 风格包只含颜色、尺寸与图片，没有可执行内容。 Packs contain colours, sizes and images only — nothing executable.

## 7. 内置预设 / Built-in presets

| 布局 Layout | 默认主题 Theme | 特征 Traits |
|---|---|---|
| 清爽 `fresh` | 清爽 | 默认外观：大写字母带数字符号提示、1dp 底边、左下 ⇧（输入中变分词） |
| 经典 `classic` | 暖橙 | 键距紧凑、常驻分词键、九键 0 键、工具栏首图标着强调色 |
| 明快 `bright` | 湛蓝 | 行距宽松、实色底边、回车常驻强调色并显示文字、工具栏圆形按钮靠右 |
| 素雅 `plain` | 青翠 | 极简工具栏、Shift/删除内缩、空格留白、文字回车、相连气泡、胶囊首选 |
| 圆润 `round` | 雾蓝 | 小写、平面键、胶囊数字键与回车、地球键、空格显示语言、英文三格联想 |
| 精致 `refined` | 银灰 | 小写、无副标签、Shift/删除内缩、地球键、文字回车、相连气泡、首选不着色 |

主题 Themes：清爽、暖橙、湛蓝、青翠、雾蓝、银灰、动态取色、墨夜、薄荷、樱粉、暮紫、海盐、松烟、秋枫、高对比、玻璃。
任意主题可与任意布局组合。 Any theme combines with any layout.

**命名约定**：预设只用中性描述性名称，不得出现、暗示或缩写任何商业输入法或厂商名；
图标一律自绘（`docs/design/icons/build_icons.py`，含面性变体）。
**Naming**: presets use neutral descriptive names only, never naming or hinting at commercial IMEs or vendors;
all icons are self-drawn (`build_icons.py`, incl. filled variants).

## 8. 验收截图 / Snapshots

`android/app/src/test/snapshots/`：`style_<布局>_{composing,t9,symbols,idle}_{light,dark}.png`、`theme_<主题>_{light,dark}.png`、
`style_round_english_light.png`、`settings_styles*.png`、`settings_style_tweak.png`、`settings_style_import*.png`。
