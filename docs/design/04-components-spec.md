# 04 · 组件规格 / Component Specs

> 给实现者：键盘侧全部是自绘 `View`（一个 `KeyboardView` 绘制整块键区，不为每个键建子 View）。
> For implementers: the keyboard is one custom `KeyboardView` that draws all keys; no per-key child views.

---

## 1. KeyView（按键绘制）/ Key drawing

### 1.1 几何 / Geometry

```
            cell（格子，触控区域 = 整个格子，含间隙）
┌───────────────────────────────┐
│ ↕5dp                          │
│  ┌─────────────────────────┐  │
│  │  ↕ hintTop = 4dp         │  │ ← 副标签基线 y = key.top + 4dp + hintAscent
│  │          1               │  │
│  │                          │  │
│  │          Q               │  │ ← 主标签中心 y = key.top + keyH × 0.58（有副标签时）
│  │                          │  │   无副标签时 y = key.centerY
│  └─────────────────────────┘  │ ← 1dp 阴影条
│←3dp                       3dp→│
└───────────────────────────────┘
```

| 属性 | 值 |
|---|---|
| `keyRect` | `cell.inset(3dp, 5dp)`（小屏 2.5dp / 4dp）|
| 圆角 | `radius.key` 6dp（大键 8dp）|
| 阴影 | `keyRect.offset(0, 1dp)` 以 `kb.keyShadow` 先画，圆角相同 |
| 背景 | 字符键 `kb.key`，功能键 `kb.keyFunc`，强调键 `kb.keyAccent` |
| 主标签（字母） | `type.keyLetter`，`kb.label`，水平居中；文字垂直居中用 `(fm.ascent + fm.descent)/2` 修正 |
| 副标签（上方） | `type.keyHint`，`kb.labelHint`，水平居中，顶部距 keyRect 4dp |
| 副标签（右下，双拼/五笔） | `type.keyHintCjk`，右对齐 `keyRect.right − 4dp`，基线 `keyRect.bottom − 5dp` |
| 图标键 | 图标 22dp 居中，`kb.icon`（强调键为 `kb.onAccent`）|
| 「中/英」 | 「中」16dp 500 `kb.label` + 「/」 12dp `kb.labelHint` + 「英」12dp `kb.labelHint`，整体居中，字符基线对齐 |
| 空格 | 中心 `ic_space` 24dp（`kb.labelHint`），其上 2dp 处 `ic_mic` 12dp（`kb.labelHint`）；光标移动模式时替换为「‹ 移动光标 ›」13dp |

### 1.2 状态 / States

| 状态 | 视觉 |
|---|---|
| 正常 Normal | 背景 + 1dp 阴影 |
| 按下 Pressed | 背景换 `*Pressed`；**不画阴影**；keyRect 下移 0.5dp；0ms 切换 |
| 禁用 Disabled | 标签 `kb.labelDisabled`，背景不变，不响应 |
| 激活 Active（Shift 锁定、选择模式）| 背景 `kb.accentSoft`，标签/图标 `kb.keyAccent` |
| 危险 Danger（删除左滑清空）| 背景 `kb.danger`，图标 `#FFFFFF` |

### 1.3 性能约束 / Performance
- 所有 `Paint`、`RectF`、`Path` 在 `onSizeChanged` / 主题切换时预创建，`onDraw` 中**零分配**。
- 标签宽度与基线在布局阶段缓存到 `KeyGeometry`；按下只 `invalidate(keyRect ∪ shadowRect)` 局部重绘。
- 字母键可在主题/尺寸变化时预渲染为 `Bitmap` 图集（可选优化）；图标使用 `VectorDrawable` 预先 `setBounds` + `setTint`。
- 目标：按下到按下态出现 ≤ 1 帧（16ms），按下到 `commitText` ≤ 8ms（不含内核解码）。

---

## 2. 候选栏 Item / Candidate item

| 属性 | 值 |
|---|---|
| 区域 | 候选行 y = 18–48dp（英文模式 0–48dp）|
| 内边距 | 左右各 12dp；候选行左边距 0，使首项文字 x=12dp 与组合串对齐 |
| 最小宽度 | 40dp |
| 文字 | `type.candidate` 19dp；第 1 项 `kb.candidateFirst` 500，其余 `kb.label` 400 |
| 附加注释（五笔编码、英文补全来源）| 10dp `kb.labelHint`，距文字右侧 3dp，基线同主文字 |
| 按下 | `kb.toolbarActive` 圆角 8dp，高 30dp，宽 = item 宽 |
| 超长词 | 单项最大宽 = 候选行宽 × 0.7，超出中间省略 |
| 右侧渐隐 | 展开按钮左侧 16dp：候选行画进图层，再用 DST_OUT 线性渐变把文字擦到全透明，透出真实背景（渐变 / 图片）；展开区不再铺背景色 / the row is drawn in a layer and erased with a DST_OUT ramp, so the real backdrop shows through |
| 展开按钮 | 44×48dp，`ic_chevron_down` 22dp `kb.icon`；展开后旋转为 ⌃ |
| 无障碍 | 每项 `contentDescription = "候选 1，你好"` |

**组合串 / Composing text**

- x = 12dp，基线 y = 15dp，`type.composing` 12.5dp，`kb.labelSecondary`；九键中已锁定的音节用 `kb.candidateFirst`。
- 光标：1dp × 12dp，`kb.candidateFirst`，530ms 闪烁（可省电关闭：输入停止 5s 后常亮）。
- **组合串不写入编辑框**（不调用 `setComposingText` 显示拼音），编辑框保持干净；语音识别的中间结果除外（见 02 §12）。
  *Pinyin is not written inline into the editor; only voice partial results are.*

---

## 3. 气泡 / Popups

### 3.1 按键预览气泡 / Key preview bubble

```
      ┌───────────┐
      │           │  宽 = keyRect.width + 16dp（最小 48dp）
      │     Q     │  高 = keyRect.height + 12dp
      │           │  圆角 10dp，kb.popup，E3 阴影
      └───────────┘
       ↕ 6dp 间距
      ┌─────┐
      │  Q  │  ← 按下的键
      └─────┘
```
- 字 `type.bubble` 30dp，`kb.label`；上滑命中副字符时字改为副字符、颜色 `kb.keyAccent`。
- 位置：水平与按键中心对齐，但**不超出键盘左右边缘 4dp**（边缘键气泡向内平移）。
- 仅字符键显示；功能键、空格、九键**不显示**预览气泡。
- 气泡绘制在 `PopupWindow` 还是同一 View 的顶层？→ 第一行按键的气泡会超出键盘窗口，**使用覆盖整个 IME 窗口 + 顶部额外 `bubbleHeight` 透明区的 overlay View**，并在 `onComputeInsets` 中把这块透明区排除在 `touchableRegion` 与 `contentTopInsets` 外。
  *Draw bubbles in an overlay that extends above the keyboard; exclude it from touchable region and content insets.*

### 3.2 长按候选气泡 / Long-press alternatives

```
  ┌─────┬─────┬─────┬─────┐
  │  1  │ ¹   │ ₁   │ ①   │   单元格 = keyRect.width × keyRect.height（最小 40×44dp）
  └─────┴─────┴─────┴─────┘   单行最多 6 个，超出换行（向上生长）
            ┌─────┐           选中格 kb.popupSelected 底 + 白字，圆角 8dp（内缩 3dp）
            │  Q  │
            └─────┘
```
- 背景 `kb.popup` 圆角 10dp，E3 阴影，内边距 4dp；字 `type.popupItem` 20dp。
- 初始选中项位于按键正上方（数组按离按键中心的距离重排，选中项放在手指上方）。
- 出现动画见 01 §8；手指移动 → 选中格随之切换（无动画）并轻振动。

### 3.3 提示气泡 / Hint toast（「松手清空」「已清空 撤销」等）
- 高 32dp，圆角 16dp，`kb.popup`，文字 13dp；位于触发键正上方 8dp，或候选栏内居中。

---

## 4. 顶栏工具栏 Item / Toolbar item
- 格子宽 = 键盘宽 / 6，高 48dp；图标 24dp `kb.icon` 居中。
- 按下 / 当前面板：底块 40×36dp，r10，`kb.toolbarActive`。
- 无文字标签；`contentDescription` 为 02 §2.1 中的名称。

## 5. 面板卡片 / Panel card（工具箱、剪贴板）
- 背景 `kb.card`，r12，无阴影；按下 `kb.keyPressed`。
- 工具箱：图标 26dp 居中偏上（图标中心 y = 卡片高 × 0.40），文字 12dp `kb.labelSecondary`，距图标 6dp。
- 剪贴板：内边距 10dp；正文 14dp 行高 20dp 最多 3 行；底部时间 11dp `kb.labelHint`；已固定项右上 `ic_pin` 14dp `kb.keyAccent`。

## 6. 语音主按钮 / Voice button
- 72dp 圆，`kb.keyAccent`；按下 `kb.keyAccentPressed`；图标 32dp 白色。
- 收音中：外圈呼吸环（同心圆，初始直径 72dp，stroke 0，填充 `kb.keyAccent` @ 35%，动画见 01 §8）。
- 取消态（按住上滑）：`kb.danger`，图标 `ic_close`。

## 7. 底部弹层（键盘内）/ In-keyboard sheet
- 覆盖顶栏 + 主区域；遮罩见 01 §5 E4；面板背景 `kb.background`，顶部圆角 16dp，拖拽条 32×4dp `kb.labelDisabled`。
- 行高 56dp，左图标 32dp（插件 icon 圆角 8dp），标题 15dp `kb.label`，描述 12dp `kb.labelSecondary` 单行。
- 右侧单选：20dp 圆，选中为 `kb.keyAccent` 外圈 2dp + 内点 10dp。

## 8. 设置 App 组件 / Settings app components
全部使用 Material 3 标准组件（`ListItem`、`Switch`、`Slider`、`SegmentedButton`、`ModalBottomSheet`、`AlertDialog`、`SearchBar`、`Snackbar`），只覆盖颜色与形状，不自定义控件。插件表单渲染见 03 §6.2。
*Stock M3 components only; plugin form rules in 03 §6.2.*

---

## 9. 图标清单 / Icon inventory

**风格 / Style**：24dp 画布，**1.75dp 描边**，`round` 端点与连接，主体落在 3–21dp 安全区，线性为主（仅 Shift 单次/锁定、停止使用实心子路径）。与参考图（主流输入法线性图标）同一气质，但更细、更圆润。
*24dp canvas, 1.75dp stroke, round caps/joins, 3–21dp live area; outline only except marked fill sub-paths.*

**使用方式 / How to use**

- 可直接使用的 VectorDrawable 已生成在 `docs/design/icons/drawable/`，实现时拷贝到 `android/app/src/main/res/drawable/`。颜色写死为黑色，运行时 `drawable.setTint(kb.icon)`（Compose 中 `Icon(tint = …)`）。
  *Ready-made VectorDrawables are in `docs/design/icons/drawable/`; tint at runtime.*
- 圆点使用 `h0.01` 零长度线段 + 圆端点绘制（VectorDrawable 支持）。*Dots are zero-length round-cap segments.*
- 修改图标请改 `docs/design/icons/build_icons.py` 后运行它，它会同时更新 XML、下表与预览页的 sprite。
  *Edit `build_icons.py` and re-run; it regenerates XML, this table and the preview sprite.*
- 「繁」字、「中/英」、「符」、「123」、「分词」等使用文字绘制，不做图标。设置 App 中若需要表中未列的图标，使用 **Material Symbols Rounded（weight 300, grade 0, opsz 24）**，该参数下观感与本套接近。
  *Text-drawn labels need no icons. For anything missing, use Material Symbols Rounded at weight 300.*

<!-- ICON-TABLE:BEGIN -->
| 名称 Name | 用途 Usage | Material Symbols Rounded | pathData（viewport 24，`fill` 标注实心子路径） |
|---|---|---|---|
| `ic_logo` | 工具箱入口 / 织文标 | `—（品牌 brand）` | `M12,3a9,9 0 1,1 0,18a9,9 0 1,1 0,-18z M7.5,9.5l2.25,5.5l2.25,-4.5l2.25,4.5l2.25,-5.5` |
| `ic_keyboard` | 键盘切换 / 输入方案 | `keyboard` | `M6,5.5h12a3,3 0 0,1 3,3v7a3,3 0 0,1 -3,3h-12a3,3 0 0,1 -3,-3v-7a3,3 0 0,1 3,-3z M7.5,9.5h0.01 M10.5,9.5h0.01 M13.5,9.5h0.01 M16.5,9.5h0.01 M9,14.5h6` |
| `ic_mic` | 语音 | `mic` | `M12,3a3,3 0 0,1 3,3v5a3,3 0 0,1 -6,0v-5a3,3 0 0,1 3,-3z M5.5,11a6.5,6.5 0 0,0 13,0 M12,17.5v3.5` |
| `ic_cursor` | 光标编辑 | `text_select_move_forward_character` | `M12,5v14 M9.5,5h5 M9.5,19h5 M6.5,9l-3,3l3,3 M17.5,9l3,3l-3,3` |
| `ic_clipboard` | 剪贴板 / 粘贴 | `content_paste` | `M9,4.5h-1a2.5,2.5 0 0,0 -2.5,2.5v11.5a2.5,2.5 0 0,0 2.5,2.5h9a2.5,2.5 0 0,0 2.5,-2.5v-11.5a2.5,2.5 0 0,0 -2.5,-2.5h-1 M10,3h4a1,1 0 0,1 1,1v1a1,1 0 0,1 -1,1h-4a1,1 0 0,1 -1,-1v-1a1,1 0 0,1 1,-1z M9,11h6 M9,15h4` |
| `ic_chevron_down` | 收起键盘 / 展开候选 / 下拉 | `keyboard_arrow_down` | `M6,9l6,6l6,-6` |
| `ic_chevron_up` | 收起候选 / 光标上 | `keyboard_arrow_up` | `M6,15l6,-6l6,6` |
| `ic_chevron_left` | 面板返回 / 光标左 | `chevron_left` | `M15,6l-6,6l6,6` |
| `ic_chevron_right` | 列表进入 / 光标右 | `chevron_right` | `M9,6l6,6l-6,6` |
| `ic_backspace` | 删除 | `backspace` | `M8.5,5h10a2.5,2.5 0 0,1 2.5,2.5v9a2.5,2.5 0 0,1 -2.5,2.5h-10l-6,-7z M12,9.5l5,5 M17,9.5l-5,5` |
| `ic_enter` | 回车（无动作） | `keyboard_return` | `M19,5v6a3,3 0 0,1 -3,3h-11 M8.5,10.5l-3.5,3.5l3.5,3.5` |
| `ic_shift` | Shift 关 | `shift` | `M12,3.5l8,8.5h-4.5v7.5h-7v-7.5h-4.5z` |
| `ic_shift_filled` | Shift 单次 | `shift（FILL=1）` | **fill** `M12,3.5l8,8.5h-4.5v7.5h-7v-7.5h-4.5z` |
| `ic_shift_lock` | Shift 锁定 | `shift_lock（FILL=1）` | **fill** `M12,3l7.5,7.5h-4v5h-7v-5h-4z`<br>`M8.5,20h7` |
| `ic_space` | 空格键符号 | `space_bar` | `M5,10.5v3a1.5,1.5 0 0,0 1.5,1.5h11a1.5,1.5 0 0,0 1.5,-1.5v-3` |
| `ic_search` | 搜索（回车动作）/ 用户词搜索 | `search` | `M10.5,4a6.5,6.5 0 1,1 0,13a6.5,6.5 0 1,1 0,-13z M15.3,15.3l4.7,4.7` |
| `ic_emoji` | 表情 | `sentiment_satisfied` | `M12,3a9,9 0 1,1 0,18a9,9 0 1,1 0,-18z M9,10h0.01 M15,10h0.01 M8.5,14.5a4,4 0 0,0 7,0` |
| `ic_settings` | 设置（六边形，呼应参考图） | `settings` | `M12,2.8l8,4.6v9.2l-8,4.6l-8,-4.6v-9.2z M12,9a3,3 0 1,1 0,6a3,3 0 1,1 0,-6z` |
| `ic_moon` | 深色模式 | `dark_mode` | `M20,14.5a8,8 0 1,1 -10.5,-10.5a7.5,7.5 0 0,0 10.5,10.5z` |
| `ic_theme` | 外观（半填充圆） | `contrast` | `M12,3a9,9 0 1,1 0,18a9,9 0 1,1 0,-18z`<br>**fill** `M12,3a9,9 0 0,1 0,18z` |
| `ic_resize` | 键盘调节 | `open_in_full` | `M8,4h-2a2,2 0 0,0 -2,2v2 M16,4h2a2,2 0 0,1 2,2v2 M8,20h-2a2,2 0 0,1 -2,-2v-2 M16,20h2a2,2 0 0,0 2,-2v-2 M9,15l6,-6 M11,9h4v4 M13,15h-4v-4` |
| `ic_quick_phrase` | 常用语 | `chat` | `M6,4.5h12a2.5,2.5 0 0,1 2.5,2.5v8a2.5,2.5 0 0,1 -2.5,2.5h-6l-4,3v-3h-2a2.5,2.5 0 0,1 -2.5,-2.5v-8a2.5,2.5 0 0,1 2.5,-2.5z M8,9.5h8 M8,13h5` |
| `ic_one_hand` | 单手模式 | `splitscreen_left` | `M8,3h8a2,2 0 0,1 2,2v14a2,2 0 0,1 -2,2h-8a2,2 0 0,1 -2,-2v-14a2,2 0 0,1 2,-2z M6,14.5h8.5v6.5` |
| `ic_float` | 悬浮键盘 | `picture_in_picture` | `M5.5,4h13a2.5,2.5 0 0,1 2.5,2.5v11a2.5,2.5 0 0,1 -2.5,2.5h-13a2.5,2.5 0 0,1 -2.5,-2.5v-11a2.5,2.5 0 0,1 2.5,-2.5z M8.5,10.5h7a1.5,1.5 0 0,1 1.5,1.5v3.5a1.5,1.5 0 0,1 -1.5,1.5h-7a1.5,1.5 0 0,1 -1.5,-1.5v-3.5a1.5,1.5 0 0,1 1.5,-1.5z M10.5,8h3` |
| `ic_waveform` | 语音引擎 / 波形 | `graphic_eq` | `M4,10v4 M8,7v10 M12,4v16 M16,7v10 M20,10v4` |
| `ic_toolbox` | 工具箱（备用） | `apps` | `M5.5,4h3a1.5,1.5 0 0,1 1.5,1.5v3a1.5,1.5 0 0,1 -1.5,1.5h-3a1.5,1.5 0 0,1 -1.5,-1.5v-3a1.5,1.5 0 0,1 1.5,-1.5z M15.5,4h3a1.5,1.5 0 0,1 1.5,1.5v3a1.5,1.5 0 0,1 -1.5,1.5h-3a1.5,1.5 0 0,1 -1.5,-1.5v-3a1.5,1.5 0 0,1 1.5,-1.5z M5.5,14h3a1.5,1.5 0 0,1 1.5,1.5v3a1.5,1.5 0 0,1 -1.5,1.5h-3a1.5,1.5 0 0,1 -1.5,-1.5v-3a1.5,1.5 0 0,1 1.5,-1.5z M15.5,14h3a1.5,1.5 0 0,1 1.5,1.5v3a1.5,1.5 0 0,1 -1.5,1.5h-3a1.5,1.5 0 0,1 -1.5,-1.5v-3a1.5,1.5 0 0,1 1.5,-1.5z` |
| `ic_tab` | Tab | `keyboard_tab` | `M3.5,12h12 M11.5,8l4,4l-4,4 M20.5,6v12` |
| `ic_copy` | 复制 | `content_copy` | `M9.5,8.5h8a2,2 0 0,1 2,2v8a2,2 0 0,1 -2,2h-8a2,2 0 0,1 -2,-2v-8a2,2 0 0,1 2,-2z M15.5,8.5v-2a2,2 0 0,0 -2,-2h-7a2,2 0 0,0 -2,2v7a2,2 0 0,0 2,2h1` |
| `ic_cut` | 剪切 | `content_cut` | `M6.5,15a2.5,2.5 0 1,1 0,5a2.5,2.5 0 1,1 0,-5z M17.5,15a2.5,2.5 0 1,1 0,5a2.5,2.5 0 1,1 0,-5z M8,15.5l8.5,-12 M16,15.5l-8.5,-12` |
| `ic_select_all` | 全选 | `select_all` | `M4,8v-2a2,2 0 0,1 2,-2h2 M11,4h2 M16,4h2a2,2 0 0,1 2,2v2 M20,11v2 M20,16v2a2,2 0 0,1 -2,2h-2 M13,20h-2 M8,20h-2a2,2 0 0,1 -2,-2v-2 M4,13v-2 M9.5,9.5h5v5h-5z` |
| `ic_pin` | 固定 | `keep` | `M9,3.5h6 M10,3.5v5.5l-3,3.5v1.5h10v-1.5l-3,-3.5v-5.5 M12,14v6.5` |
| `ic_delete` | 删除（垃圾桶）/ 清空 | `delete` | `M4,6.5h16 M9.5,3.5h5 M6,6.5l0.9,12a2,2 0 0,0 2,1.85h6.2a2,2 0 0,0 2,-1.85l0.9,-12 M10,10.5v6 M14,10.5v6` |
| `ic_edit` | 编辑 | `edit` | `M4,20v-3l11.25,-11.25a2.12,2.12 0 0,1 3,3l-11.25,11.25z M13.5,7.5l3,3` |
| `ic_undo` | 撤销 | `undo` | `M9,14l-5,-5l5,-5 M4,9h10.5a5.5,5.5 0 0,1 0,11h-3.5` |
| `ic_close` | 关闭 | `close` | `M6,6l12,12 M18,6l-12,12` |
| `ic_check` | 完成 / 已选 | `check` | `M5,12.5l4.5,4.5l9.5,-10` |
| `ic_plus` | 添加 | `add` | `M12,5v14 M5,12h14` |
| `ic_import` | 导入文件 / .xipk | `file_open` | `M13.5,3.5h-6.5a2,2 0 0,0 -2,2v13a2,2 0 0,0 2,2h10a2,2 0 0,0 2,-2v-9.5z M13.5,3.5v4a2,2 0 0,0 2,2h3.5 M12,11.5v6 M9.5,15l2.5,2.5l2.5,-2.5` |
| `ic_export` | 导出 | `file_export` | `M13.5,3.5h-6.5a2,2 0 0,0 -2,2v13a2,2 0 0,0 2,2h10a2,2 0 0,0 2,-2v-9.5z M13.5,3.5v4a2,2 0 0,0 2,2h3.5 M12,17.5v-6 M9.5,14l2.5,-2.5l2.5,2.5` |
| `ic_book` | 词库 | `menu_book` | `M6.5,3.5h12v14h-12a1.5,1.5 0 0,0 -1.5,1.5v-14a1.5,1.5 0 0,1 1.5,-1.5z M5,19a1.5,1.5 0 0,0 1.5,1.5h12v-3 M9,7.5h6` |
| `ic_info` | 关于 / 帮助 | `info` | `M12,3a9,9 0 1,1 0,18a9,9 0 1,1 0,-18z M12,11v5.5 M12,7.75h0.01` |
| `ic_warning` | 警告横幅 | `warning` | `M12,4l9,15.5h-18z M12,10v4 M12,16.75h0.01` |
| `ic_volume` | 按键音 | `volume_up` | `M4,9.5h3l4.5,-4v13l-4.5,-4h-3z M15.5,9a4,4 0 0,1 0,6 M18,6.5a7.5,7.5 0 0,1 0,11` |
| `ic_vibration` | 振动 | `vibration` | `M9,4h6a1.5,1.5 0 0,1 1.5,1.5v13a1.5,1.5 0 0,1 -1.5,1.5h-6a1.5,1.5 0 0,1 -1.5,-1.5v-13a1.5,1.5 0 0,1 1.5,-1.5z M4.5,9v6 M19.5,9v6 M2,11v2 M22,11v2` |
| `ic_lock` | 符号面板锁定 | `lock` | `M7,10.5h10a1.5,1.5 0 0,1 1.5,1.5v7a1.5,1.5 0 0,1 -1.5,1.5h-10a1.5,1.5 0 0,1 -1.5,-1.5v-7a1.5,1.5 0 0,1 1.5,-1.5z M8.5,10.5v-3a3.5,3.5 0 0,1 7,0v3 M12,14.5v2` |
| `ic_lock_open` | 符号面板解锁 | `lock_open_right` | `M7,10.5h10a1.5,1.5 0 0,1 1.5,1.5v7a1.5,1.5 0 0,1 -1.5,1.5h-10a1.5,1.5 0 0,1 -1.5,-1.5v-7a1.5,1.5 0 0,1 1.5,-1.5z M8.5,10.5v-3a3.5,3.5 0 0,1 6.8,-1.2 M12,14.5v2` |
| `ic_arrow_back` | 设置页返回 | `arrow_back` | `M19,12h-14 M11,6l-6,6l6,6` |
| `ic_open_external` | 外部链接 | `open_in_new` | `M14,4h6v6 M20,4l-9,9 M18,14v4a2,2 0 0,1 -2,2h-10a2,2 0 0,1 -2,-2v-10a2,2 0 0,1 2,-2h4` |
| `ic_drag_handle` | 拖拽排序 | `drag_handle` | `M5,9h14 M5,15h14` |
| `ic_stop` | 语音收音中（停止） | `stop（FILL=1）` | **fill** `M9,7h6a2,2 0 0,1 2,2v6a2,2 0 0,1 -2,2h-6a2,2 0 0,1 -2,-2v-6a2,2 0 0,1 2,-2z` |
| `ic_globe` | 中英 / 语言键（地球） | `language` | `M12,3a9,9 0 1,1 0,18a9,9 0 1,1 0,-18z M12,3a5,9 0 0,1 0,18a5,9 0 0,1 0,-18z M3,12h18` |
| `ic_logo_filled` | 工具箱入口（面性） | `—（品牌 brand）` | **solid** `M12,2.1a9.9,9.9 0 1,1 0,19.8a9.9,9.9 0 1,1 0,-19.8z M6.42,9.66L9.67,17.48L12.00,12.82L14.33,17.48L17.58,9.66L15.82,8.94L14.17,12.92L12.00,8.58L9.83,12.92L8.18,8.94z` |
| `ic_keyboard_filled` | 键盘切换（面性） | `keyboard（FILL=1）` | **solid** `M5.8,4.7h12.4a3.6,3.6 0 0,1 3.6,3.6v7.4a3.6,3.6 0 0,1 -3.6,3.6h-12.4a3.6,3.6 0 0,1 -3.6,-3.6v-7.4a3.6,3.6 0 0,1 3.6,-3.6z M7.05,8.55h0.9a0.5,0.5 0 0,1 0.5,0.5v0.9a0.5,0.5 0 0,1 -0.5,0.5h-0.9a0.5,0.5 0 0,1 -0.5,-0.5v-0.9a0.5,0.5 0 0,1 0.5,-0.5z M10.05,8.55h0.9a0.5,0.5 0 0,1 0.5,0.5v0.9a0.5,0.5 0 0,1 -0.5,0.5h-0.9a0.5,0.5 0 0,1 -0.5,-0.5v-0.9a0.5,0.5 0 0,1 0.5,-0.5z M13.05,8.55h0.9a0.5,0.5 0 0,1 0.5,0.5v0.9a0.5,0.5 0 0,1 -0.5,0.5h-0.9a0.5,0.5 0 0,1 -0.5,-0.5v-0.9a0.5,0.5 0 0,1 0.5,-0.5z M16.05,8.55h0.9a0.5,0.5 0 0,1 0.5,0.5v0.9a0.5,0.5 0 0,1 -0.5,0.5h-0.9a0.5,0.5 0 0,1 -0.5,-0.5v-0.9a0.5,0.5 0 0,1 0.5,-0.5z M9,13.55h6a0.95,0.95 0 0,1 0.95,0.95v0a0.95,0.95 0 0,1 -0.95,0.95h-6a0.95,0.95 0 0,1 -0.95,-0.95v-0a0.95,0.95 0 0,1 0.95,-0.95z` |
| `ic_mic_filled` | 语音（面性） | `mic（FILL=1）` | **solid** `M12,2.6h0a3.4,3.4 0 0,1 3.4,3.4v5a3.4,3.4 0 0,1 -3.4,3.4h-0a3.4,3.4 0 0,1 -3.4,-3.4v-5a3.4,3.4 0 0,1 3.4,-3.4z`<br>`M5.5,11a6.5,6.5 0 0,0 13,0 M12,17.5v3.5` |
| `ic_cursor_filled` | 光标编辑（面性） | `text_select_move_forward_character（FILL=1）` | **solid** `M6.9,3.4h10.2a4.5,4.5 0 0,1 4.5,4.5v8.2a4.5,4.5 0 0,1 -4.5,4.5h-10.2a4.5,4.5 0 0,1 -4.5,-4.5v-8.2a4.5,4.5 0 0,1 4.5,-4.5z M10.1,6.2h3.8a0.9,0.9 0 0,1 0.9,0.9v0a0.9,0.9 0 0,1 -0.9,0.9h-3.8a0.9,0.9 0 0,1 -0.9,-0.9v-0a0.9,0.9 0 0,1 0.9,-0.9z M11.1,8h1.8v8h-1.8z M10.1,16h3.8a0.9,0.9 0 0,1 0.9,0.9v0a0.9,0.9 0 0,1 -0.9,0.9h-3.8a0.9,0.9 0 0,1 -0.9,-0.9v-0a0.9,0.9 0 0,1 0.9,-0.9z M7.4,9.3l-2.7,2.7l2.7,2.7z M16.6,9.3l2.7,2.7l-2.7,2.7z` |
| `ic_clipboard_filled` | 剪贴板（面性） | `content_paste（FILL=1）` | **solid** `M7.4,3.6h9.2a2.8,2.8 0 0,1 2.8,2.8v12.2a2.8,2.8 0 0,1 -2.8,2.8h-9.2a2.8,2.8 0 0,1 -2.8,-2.8v-12.2a2.8,2.8 0 0,1 2.8,-2.8z M8.95,10.1h6.1a0.9,0.9 0 0,1 0.9,0.9v0a0.9,0.9 0 0,1 -0.9,0.9h-6.1a0.9,0.9 0 0,1 -0.9,-0.9v-0a0.9,0.9 0 0,1 0.9,-0.9z M8.95,14.1h4.1a0.9,0.9 0 0,1 0.9,0.9v0a0.9,0.9 0 0,1 -0.9,0.9h-4.1a0.9,0.9 0 0,1 -0.9,-0.9v-0a0.9,0.9 0 0,1 0.9,-0.9z`<br>**solid** `M9.8,1.8h4.4a1.4,1.4 0 0,1 1.4,1.4v1a1.4,1.4 0 0,1 -1.4,1.4h-4.4a1.4,1.4 0 0,1 -1.4,-1.4v-1a1.4,1.4 0 0,1 1.4,-1.4z` |
| `ic_chevron_down_filled` | 收起键盘（面性） | `arrow_drop_down` | **solid** `M5.2,8.6h13.6l-6.8,7.8z` |
| `ic_emoji_filled` | 表情（面性） | `sentiment_satisfied（FILL=1）` | **solid** `M12,2.1a9.9,9.9 0 1,1 0,19.8a9.9,9.9 0 1,1 0,-19.8z M9,8.7a1.3,1.3 0 1,1 0,2.6a1.3,1.3 0 1,1 0,-2.6z M15,8.7a1.3,1.3 0 1,1 0,2.6a1.3,1.3 0 1,1 0,-2.6z M8,13.6h8a4,4 0 0,1 -8,0z` |
| `ic_settings_filled` | 设置（面性） | `settings（FILL=1）` | **solid** `M12,1.8l8.9,5.1v10.2l-8.9,5.1l-8.9,-5.1v-10.2z M12,8.8a3.2,3.2 0 1,1 0,6.4a3.2,3.2 0 1,1 0,-6.4z` |
| `ic_devices` | 织文互联 / 电脑与手机 | `devices` | `M4,5h10.5a1.5,1.5 0 0,1 1.5,1.5v6.5a1.5,1.5 0 0,1 -1.5,1.5h-10.5a1.5,1.5 0 0,1 -1.5,-1.5v-6.5a1.5,1.5 0 0,1 1.5,-1.5z M1.5,18h11 M17.75,8.5h3a1.25,1.25 0 0,1 1.25,1.25v8.5a1.25,1.25 0 0,1 -1.25,1.25h-3a1.25,1.25 0 0,1 -1.25,-1.25v-8.5a1.25,1.25 0 0,1 1.25,-1.25z M19.25,17h0.01` |
| `ic_send` | 发送到电脑 | `send` | `M4.5,11.5l15,-7l-5.5,15l-2.75,-5.75z M11.25,13.75l8.25,-9.25` |
<!-- ICON-TABLE:END -->
