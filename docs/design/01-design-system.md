# 01 · 设计系统与设计令牌 / Design System & Tokens

> 版本 v0.1 · 适用：键盘（自绘 Canvas View）+ 设置 App（Compose Material 3）
> Version v0.1 · Scope: keyboard (custom Canvas views) + settings app (Compose M3)

---

## 0. 设计原则 / Principles

1. **布局沿用主流输入法，视觉做减法。** 用户熟悉的键位、工具栏顺序、面板结构保持不变；去掉 logo 吉祥物、广告位、账号头像、渐变与装饰插画。
   *Keep the mainstream familiar layout; subtract visual noise — no mascot, ads, avatars, gradients or decorative illustrations.*
2. **一个平面。** 工具栏/候选栏与键盘区使用**同一背景色**（一体化平面），不再用白色顶栏 + 灰色键区的两段式；层级只靠按键白色块和 1dp 底阴影表达。
   *One surface: top bar and key area share one background; hierarchy comes only from key fills and a 1dp bottom shadow.*
3. **颜色只有一个强调色。** 蓝色只出现在：首选候选、回车键动作态、选中态、语音主按钮。
   *One accent colour, used only for: first candidate, action-state Enter, selection, voice main button.*
4. **速度优先于动画。** 按下反馈必须在同一帧内出现（0ms），动画只用于面板切换。
   *Speed over motion: press feedback is same-frame; motion is reserved for panel transitions.*
5. **设置极少。** 能自动判断的就不做开关（例如回车键文字跟随 `imeOptions`）。
   *Few settings: anything that can be inferred is not a toggle.*

---

## 1. 单位与基准设备 / Units & reference device

| 项目 Item | 值 Value |
|---|---|
| 基准设备 Reference | 1080×2400 px, 420 dpi → density 2.625 → **411.4 × 914.3 dp** |
| 小屏基准 Small reference | 720×1600 px, 320 dpi → 360 × 800 dp |
| 预览换算 Preview | `preview/index.html` 内部 1 CSS px = 1 dp，整体缩放 0.875 → 360×800 CSS px |

- 键盘内全部尺寸以 **dp** 定义；键盘内文字以 **dp 为主、有上限地跟随系统字号**（见 §4.3）。
  *All keyboard sizes are dp; keyboard text is dp-based and follows system font scale only up to a cap (§4.3).*
- 设置 App 文字使用 **sp**，完全跟随系统字号。
  *Settings app text uses sp and fully follows system font scale.*

---

## 2. 颜色令牌 / Colour tokens

命名规则：`kb.*` 键盘，`st.*` 设置 App。取值以 `assets/styles/theme-fresh.json` 为准（默认主题，见 05 键盘风格包）。 Values live in `assets/styles/theme-fresh.json` (the default theme, see 05).
*Naming: `kb.*` keyboard, `st.*` settings app.*

### 2.1 键盘 · 亮色 / Keyboard · Light

| Token | HEX | 用途 Usage |
|---|---|---|
| `kb.background` | `#E8EBF0` | 整个键盘窗口背景（含顶栏）/ whole keyboard window incl. top bar |
| `kb.key` | `#FFFFFF` | 字母/数字/标点等字符键 / character keys |
| `kb.keyPressed` | `#D6DBE3` | 字符键按下 / character key pressed |
| `kb.keyFunc` | `#CDD3DC` | 功能键（⇧ ⌫ 符 123 中/英 ⏎ 分词 重输）/ function keys |
| `kb.keyFuncPressed` | `#B8C0CB` | 功能键按下 / function key pressed |
| `kb.keyShadow` | `#A9B1BD` | 按键底部 1dp 阴影 / 1dp bottom shadow |
| `kb.keyAccent` | `#2E6CF6` | 回车动作态（发送/搜索/前往）、语音主按钮、「返回」/ Enter action state, voice button |
| `kb.keyAccentPressed` | `#2257D1` | 强调键按下 / accent key pressed |
| `kb.onAccent` | `#FFFFFF` | 强调键上的文字/图标 / label on accent (4.57:1) |
| `kb.label` | `#1B1E23` | 主标签、候选文字 / primary labels, candidates |
| `kb.labelHint` | `#737983` | 按键副标签（数字/符号提示）/ key hint labels |
| `kb.labelSecondary` | `#5E6570` | 拼音组合串、面板次要文字 / composing text, secondary text (4.9:1 on bg) |
| `kb.labelDisabled` | `#80868F` | 禁用（≥3:1，禁用键另画描边无填充）/ disabled (≥3:1, outlined) |
| `kb.candidateFirst` | `#255FE0` | 首选候选文字 / first candidate text |
| `kb.icon` | `#3E444D` | 工具栏与功能键图标 / icons |
| `kb.divider` | `#D5DAE1` | 分割线（0.5dp）/ hairline dividers |
| `kb.toolbarActive` | `#D9DEE6` | 顶栏图标选中/按下底块 / top-bar item active pill |
| `kb.accentSoft` | `#DCE6FD` | 选中底色（符号分类、单选行）/ selection tint |
| `kb.popup` | `#FFFFFF` | 气泡、长按候选气泡 / bubbles |
| `kb.popupSelected` | `#2E6CF6` | 长按气泡中当前选中项底色 / selected cell in long-press bubble |
| `kb.popupShadow` | `#1E283C` @ 18% | 气泡投影 / bubble shadow |
| `kb.card` | `#FFFFFF` | 工具箱卡片、剪贴板卡片 / panel cards |
| `kb.danger` | `#E5484D` | 清空提示、删除 / destructive |
| `kb.voiceWave` | `#2E6CF6` | 语音波形 / waveform |

### 2.2 键盘 · 暗色 / Keyboard · Dark

| Token | HEX | 备注 Note |
|---|---|---|
| `kb.background` | `#121315` | 近黑但非纯黑，OLED 友好 / near-black |
| `kb.key` | `#2D2F34` | 字符键比背景亮 / lighter than bg |
| `kb.keyPressed` | `#45484F` | |
| `kb.keyFunc` | `#202226` | 功能键介于背景与字符键之间 / between bg and key |
| `kb.keyFuncPressed` | `#34373D` | |
| `kb.keyShadow` | `#050607` | |
| `kb.keyAccent` | `#3B6FE6` | 白字对比 4.56:1 / white 4.56:1 |
| `kb.keyAccentPressed` | `#335FC8` | |
| `kb.onAccent` | `#FFFFFF` | |
| `kb.label` | `#E9EBEF` | |
| `kb.labelHint` | `#9AA0AA` | |
| `kb.labelSecondary` | `#A2A8B2` | |
| `kb.labelDisabled` | `#7A7F88` | |
| `kb.candidateFirst` | `#7FA6FF` | 6.9:1 on bg |
| `kb.icon` | `#C9CDD4` | |
| `kb.divider` | `#2A2C31` | |
| `kb.toolbarActive` | `#26282D` | |
| `kb.accentSoft` | `#1F2B47` | |
| `kb.popup` | `#3A3D43` | |
| `kb.popupSelected` | `#3B6FE6` | |
| `kb.popupShadow` | `#000000` @ 40% | |
| `kb.card` | `#202226` | |
| `kb.danger` | `#FF6166` | |
| `kb.voiceWave` | `#7FA6FF` | |

### 2.3 设置 App（Material 3 色彩角色）/ Settings app (M3 roles)

设置 App **不使用**系统动态取色（保持品牌一致、减少一个开关），直接提供两套 `ColorScheme`：
*The settings app does **not** use dynamic colour; it ships two fixed `ColorScheme`s:*

| Role | Light | Dark |
|---|---|---|
| `primary` | `#2E6CF6` | `#8AAEFF` |
| `onPrimary` | `#FFFFFF` | `#0A2566` |
| `primaryContainer` | `#DCE6FD` | `#1F3A7A` |
| `onPrimaryContainer` | `#0B2A6B` | `#DCE6FD` |
| `background` / `surface` | `#F5F6F8` | `#121315` |
| `surfaceContainerLowest` | `#FFFFFF` | `#0D0E10` |
| `surfaceContainer`（卡片 card） | `#FFFFFF` | `#1E2024` |
| `surfaceContainerHigh` | `#ECEFF3` | `#26282D` |
| `onSurface` | `#1B1E23` | `#E9EBEF` |
| `onSurfaceVariant` | `#5E6570` | `#A2A8B2` |
| `outline` | `#C4CAD3` | `#4A4E56` |
| `outlineVariant` | `#E3E6EB` | `#2A2C31` |
| `error` | `#D93A3F` | `#FF8A8D` |
| 成功 success（自定义 custom） | `#1F9D55` | `#4CC38A` |

---

## 3. 形状 / Shape

| Token | 值 Value | 用途 Usage |
|---|---|---|
| `radius.key` | **6dp** | 所有按键 / all keys |
| `radius.keyLarge` | 8dp | 九键、数字键盘等大键 / 9-key & numpad keys (height ≥ 52dp) |
| `radius.toolbarItem` | 10dp | 顶栏选中底块 / top-bar active pill (40×36dp) |
| `radius.popup` | 10dp | 按键气泡与长按气泡 / key bubbles |
| `radius.card` | 12dp | 工具箱、剪贴板卡片 / panel cards |
| `radius.sheet` | 16dp（仅顶部两角 top corners） | 键盘内底部弹层 / in-keyboard bottom sheet |
| `radius.voiceButton` | 50%（圆 circle） | 语音主按钮 / voice button |
| 设置 App settings | M3 默认：卡片 16dp、按钮全圆角、对话框 28dp / M3 defaults |

---

## 4. 字体与字号 / Typography

### 4.1 字体 / Typeface
- 键盘：`Typeface.DEFAULT`（系统中文字体：Noto Sans CJK / MiSans / HarmonyOS Sans 等），**不内置字体**以节省体积、与系统一致。
  *Keyboard: system default typeface; no bundled font.*
- 字母键使用 Regular(400)，中文功能键（符/中/分词/重输）Medium(500)，首选候选 Medium(500)，其余候选 Regular。
  *Letters 400; Chinese function labels 500; first candidate 500; others 400.*
- 数字键盘数字用 `fontFeatureSettings = "tnum"`（等宽数字）。*Numpad uses tabular figures.*

### 4.2 键盘字号（标准档，键高 46dp 时）/ Keyboard sizes at standard key height (46dp)

| Token | 尺寸 | 字重 | 说明 |
|---|---|---|---|
| `type.keyLetter` | 22dp | 400 | 26 键字母主标签（大写显示）/ letter labels |
| `type.keyLetterLower` | 23dp | 400 | 英文小写态 / English lowercase |
| `type.keyHint` | 10.5dp | 400 | 字母上方副标签 / hint above letter |
| `type.keyHintCjk` | 9.5dp | 400 | 双拼韵母/五笔字根提示 / shuangpin & Wubi hints |
| `type.keyFuncCjk` | 16dp | 500 | 符 / 中 / 分词 / 重输 / 返回 |
| `type.keyFuncLatin` | 16dp | 500 | 123 / ABC / Tab / Del |
| `type.keyFuncMinor` | 12dp | 400 | 「中/英」里的非当前语言「英」/ inactive half of 中/英 |
| `type.keyPunct` | 20dp | 400 | ， 。 键 |
| `type.t9Main` | 17dp | 500 | 九键 ABC/DEF 字母组 |
| `type.t9Digit` | 10.5dp | 400 | 九键数字副标签 |
| `type.numpad` | 24dp | 400 | 数字键盘数字 |
| `type.candidate` | 19dp | 400/500 | 候选词 / candidates |
| `type.composing` | 12.5dp | 400 | 候选栏内拼音串 / composing string |
| `type.bubble` | 30dp | 400 | 按键气泡放大字 / key preview bubble |
| `type.popupItem` | 20dp | 400 | 长按气泡候选 / long-press alternatives |
| `type.panelLabel` | 12dp | 400 | 工具箱图标下文字 / toolbox captions |
| `type.panelTitle` | 15dp | 500 | 面板标题（剪贴板/工具箱）/ panel titles |
| `type.voiceText` | 18dp | 400 | 语音实时识别文本 / live transcript |
| 图标 icon | 24dp（顶栏）/ 22dp（功能键）/ 26dp（工具箱） | — | |

### 4.3 字号缩放规则 / Scaling rule

```
s        = clamp(keyHeightDp / 46, 0.85, 1.20)          // 随键高 / follows key height
f        = clamp(systemFontScale, 1.0, 2.0)             // 系统字号完整生效（只放大不缩小）/ full system scale
labelPx  = tokenDp × s × f × density                    // 主字；字母另限 ≤ 0.62 × 键高 / letters ≤ 0.62 × key height
hintPx   = tokenDp × s × min(f, 1.15) × density         // 副标记（数字/符号提示、角标）/ secondary hints
iconPx   = tokenDp × s × density                        // 键内图标不随系统字号 / icons ignore font scale
topBar   = 48dp × (1 + 0.55 × (f − 1))                  // 顶栏随字号加高 / top bar grows with f
```
- 候选栏字号只乘 `f`，不乘 `s`。*Candidate text uses `f` only.*
- 键内放不下时依次隐藏副标记（上方提示、角标、九键数字），主字不缩小；过宽时才按键宽缩小以免裁切。
  *When crowded, hide secondary hints first; the main label only shrinks to avoid horizontal clipping.*
- 屏幕宽度 < 360dp 时 `type.keyLetter` 额外 ×0.92。*Width < 360dp: letters ×0.92.*

### 4.4 设置 App / Settings app
使用 M3 默认 Typography（`titleLarge 22sp`、`titleMedium 16sp`、`bodyLarge 16sp`、`bodyMedium 14sp`、`labelLarge 14sp`），页面大标题使用 `headlineSmall 24sp`。
*Default M3 type scale; page titles `headlineSmall 24sp`.*

---

## 5. 阴影与层级 / Elevation

**按键不使用 Android elevation / 模糊阴影。**用「底部 1dp 实色条」模拟轻微立体感（与主流输入法一致，但更淡）。
*Keys do not use blurred shadows. A solid 1dp bottom strip mimics depth (like mainstream IMEs, lighter).*

```
绘制顺序 / draw order (per key):
1. shadowRect = keyRect.offset(0, 1dp)，颜色 kb.keyShadow，圆角 radius.key
2. keyRect，颜色 kb.key / kb.keyFunc，圆角 radius.key
按下态 pressed: 不画阴影，keyRect 向下平移 0.5dp（视觉“按下去”）
```

| 层级 Level | 用法 | 规格 Spec |
|---|---|---|
| E0 | 键盘背景 | 无 / none |
| E1 | 按键 | 1dp 实色底边 / 1dp solid bottom strip |
| E2 | 卡片（工具箱/剪贴板） | 无阴影，靠 `kb.card` 与背景色差 / no shadow, fill contrast only |
| E3 | 按键气泡、长按气泡 | `setShadowLayer(12dp, 0, 4dp, kb.popupShadow)` |
| E4 | 键盘内底部弹层 | 顶部 0.5dp `kb.divider` + 背景遮罩 `#000` @ 24%（亮）/ 48%（暗） |

---

## 6. 间距与栅格 / Spacing & grid

基础单位 4dp，允许 2dp 半步。*Base unit 4dp, 2dp half-steps allowed.*

| Token | 值 | 用途 |
|---|---|---|
| `space.keyGapH` | 6dp | 相邻按键水平间隙（每键左右各 3dp inset）/ horizontal gap |
| `space.keyGapV` | 10dp | 相邻行垂直间隙（每键上下各 5dp inset）/ vertical gap |
| `space.kbPadH` | 3dp | 键盘左右外边距（加上 inset 后视觉边距 6dp）/ side padding |
| `space.kbPadTop` | 2dp | 顶栏与第一行之间 / below top bar |
| `space.kbPadBottom` | 4dp | 最后一行下方（再叠加导航栏 inset）/ above nav inset |
| `space.panelPad` | 12dp | 工具箱/剪贴板面板内边距 / panel padding |
| `space.cardGap` | 8dp | 卡片间距 / card gap |
| `space.candidateGapH` | 24dp | 候选词间距（每项左右各 12dp）/ candidate h-padding |
| 设置 App | 页面左右 16dp，分组间 24dp，列表项最小高度 56dp（两行 72dp） | |

小屏（宽 < 360dp）：`keyGapH = 5dp`，`keyGapV = 8dp`。*Small screens tighten gaps.*

---

## 7. 按键高度规则 / Key height rule

**行高（row pitch）= 按键高度 + `keyGapV`。**先按屏幕高度算基础值，再乘用户档位。
*Row pitch = key height + vertical gap. Base from screen height × user level.*

```kotlin
// 竖屏 portrait
basePitch = clamp(screenHeightDp * 0.0615f, 46f, 62f)        // 914dp → 56.2dp
// 横屏 landscape
basePitch = clamp(screenHeightDp * 0.105f, 38f, 48f)         // 411dp → 43.2dp

levelFactor = [0.88, 0.94, 1.00, 1.06, 1.12][level]         // 5 档，默认 index 2 / 5 levels
rowPitch  = round(clamp(basePitch * levelFactor, 40f, 68f))
keyHeight = rowPitch - keyGapV
```

| 档位 Level | 名称 | 基准机行高 / 键高 | 4 行键区总高 |
|---|---|---|---|
| 0 | 紧凑 Compact | 49 / 39dp | 196dp |
| 1 | 较矮 Low | 53 / 43dp | 212dp |
| **2** | **标准 Standard（默认）** | **56 / 46dp** | **224dp** |
| 3 | 较高 Tall | 60 / 50dp | 240dp |
| 4 | 高 Taller | 63 / 53dp | 252dp |

- 顶栏（工具栏/候选栏）固定 **48dp**，不随档位变化。*Top bar fixed 48dp.*
- 键盘窗口总高 = 48 + 2 + 4 × rowPitch + 4 + navBarInset。标准档：**278dp + 导航栏**（约占屏高 30%）。
  *Total = 48 + 2 + 4×pitch + 4 + nav inset → 278dp + nav at standard (~30% of screen).*
- **所有面板（光标、剪贴板、工具箱、符号、语音、候选展开）与键区等高**，切换时键盘窗口高度不变，避免 App 内容跳动。
  *Every panel equals the key-area height, so the window never resizes when switching panels.*
- 横屏：顶栏 40dp；键区宽度上限 `min(screenWidth, 720dp)` 居中。*Landscape: top bar 40dp, key area max 720dp, centred.*

---

## 8. 动效 / Motion

曲线（Android `PathInterpolator`）：
*Curves:*

| Token | 贝塞尔 Bezier | 用途 |
|---|---|---|
| `ease.standard` | (0.2, 0, 0, 1) | 通用 / general |
| `ease.decelerate` | (0.05, 0.7, 0.1, 1) | 进入 / enter |
| `ease.accelerate` | (0.3, 0, 0.8, 0.15) | 退出 / exit |

| 场景 Scenario | 时长 Duration | 规格 Spec |
|---|---|---|
| 按键按下态 key press | **0ms** | 同帧切换颜色，无过渡 / same frame |
| 按键抬起 key release | 80ms | 颜色回退，`ease.standard` |
| 按键气泡出现 bubble in | **0ms** | 立即显示（延迟感知比美观更重要）/ instant |
| 按键气泡消失 bubble out | 70ms 淡出，抬起后延迟 40ms | alpha 1→0 |
| 长按气泡出现 long-press popup | 120ms | scale 0.92→1 + alpha 0→1，pivot 在按键中心上沿，`ease.decelerate` |
| 面板切换（键盘↔工具箱/剪贴板/光标/符号） | 180ms | 淡入淡出交叉（fade-through）：旧面板 alpha 1→0 于前 70ms，新面板 alpha 0→1 + translateY 8dp→0 于后 110ms |
| 候选展开 candidate expand | 200ms | 展开网格从顶栏下沿向下 reveal（clip 高度 0→100%），箭头旋转 180° |
| 顶栏工具栏 ↔ 候选栏 | 120ms | 交叉淡变 / crossfade；**输入第一个字母时候选栏立即显示，不等动画** |
| 语音波形 waveform | 每帧 | 音量 RMS 映射，平滑系数 0.35（指数平滑）/ exponential smoothing |
| 语音按钮呼吸 voice pulse | 1200ms 循环 | 外圈 scale 1→1.18，alpha 0.35→0，仅在收音中 / only while listening |
| 底部弹层 bottom sheet | 240ms 进 / 180ms 出 | translateY 100%→0，`ease.decelerate` / `ease.accelerate` |

- 读取 `Settings.Global.ANIMATOR_DURATION_SCALE`：为 0 时所有动画直接跳到终态。
  *Respect animator duration scale; 0 = jump to end state.*

---

## 9. 触感与声音反馈 / Haptics & sound

### 9.1 触发时机 / When
- **ACTION_DOWN 时**触发（不是抬起），与视觉按下同帧。*Fire on ACTION_DOWN, same frame as visual press.*
- 长按弹出气泡、滑动输入副字符判定成立、空格滑动光标每移动 1 格、删除左滑进入「松手清空」态：各触发一次轻触感。
  *Also: long-press popup open, swipe-hint committed, each cursor step of space-drag, entering delete-clear state.*
- 连续删除（长按重复）只在前 1 次和之后每 5 次触发，避免“嗡嗡”感。*Repeat-delete: first then every 5th.*

### 9.2 振动强度档位 / Vibration levels（设置「按键震动」关 / 轻 / 中 / 强，默认关，见 06 §4）

| 档 | 名称 | 实现 Implementation |
|---|---|---|
| 0 | 关 Off（默认） | 不振动 |
| 1 | 跟随系统 System（旧版选项，仍生效，界面不再提供） | `view.performHapticFeedback(KEYBOARD_TAP)`（API 27+ 用 `KEYBOARD_PRESS`），遵守系统触感开关 |
| 2 | 轻 Light | 原语组合：`PRIMITIVE_CLICK` 力度 0.35 |
| 3 | 中 Medium | 原语组合：`PRIMITIVE_CLICK` 0.65 + `PRIMITIVE_TICK` 0.25（22 ms 后） |
| 4 | 强 Strong | 原语组合：`PRIMITIVE_CLICK` 1.0 + `PRIMITIVE_TICK` 0.45（30 ms 后） |

- **优先自己用原语拼**（Android 11+，且设备支持这两种原语）：系统预置的 `EFFECT_TICK / _CLICK / _HEAVY_CLICK`
  各家长短不一，听起来更像一次点击事件而不是「按键下去的那一下」；自建之后每档都只是「一记清晰的敲击」，
  档位之间只差力度与一点点尾随刻度。不支持原语时退回预置效果，再退回按毫秒与振幅的一次振动
  （有振幅控制 9/14/20 ms × 60/130/255；没有则 8/13/20 ms 默认振幅）。
  *Prefer primitives (Android 11+ with both supported): the platform presets vary in length by vendor and read as a
  click event rather than a key strike; a composition gives every level one clear strike differing only in weight.
  Fall back to presets, then to an explicit one-shot (9/14/20 ms × 60/130/255, or 8/13/20 ms at default amplitude).*
- **系统的「触摸振动」总开关优先**（`Settings.System.HAPTIC_FEEDBACK_ENABLED`）：关掉时键盘自己的档位再高也不震——
  键盘里的「按键震动」是键盘的设置，系统那一个是全机的。*The device-wide touch-haptics switch wins.*
- **同一时刻只排一次振动**：连打时按键比振动还密，若一键一个任务排下去，马达会先安静、再成串补震，手感就「散」了；
  上一击还没发出去就跳过这一次。（测试里同时按 5 下只产生一记；见 `KeyHapticsTest`。）
  *At most one vibration in flight, so a fast burst stays even instead of falling silent and then firing in bursts.*
- 振动器调用放在独立 HandlerThread，不阻塞 UI 线程。*Vibrate off the UI thread.*

### 9.3 按键音 / Key sound
- 风格：关（默认）/ 跟随系统 / 清脆 / 气泡 / 木质 / 打字机 / 水滴，音量 0–100；自有风格在代码中合成，区分字母、删除、空格、回车四种变体。详见 06 §3。
  *Styles: off (default) / system / five synthesized styles with four variants each, volume 0–100. See 06 §3.*
- 「跟随系统」使用 `AudioManager.playSoundEffect(FX_KEYPRESS_STANDARD / _DELETE / _RETURN / _SPACEBAR, volume)`。静音 / 振动模式下都不发声。
  *"System" uses the platform key sounds. No sound in silent/vibrate ringer modes.*

---

## 10. 无障碍 / Accessibility
- 每个按键提供 `contentDescription`（例：「字母 Q，上滑输入 1」「删除」「回车，发送」）；使用 `ExploreByTouchHelper` 实现虚拟节点。
- TalkBack 开启时：抬手输入（lift-to-type），禁用上滑副字符手势，长按改为双击后长按。
- 最小触控区：按键视觉可小于 48dp，但触控区扩展到整个格子（含间隙），相邻键之间**无死区**。
  *Touch areas extend over gaps — no dead zones between keys.*
