package com.weavetext.ime.style

import com.weavetext.ime.ui.keyboard.KbGeometry

/**
 * 布局风格（docs/design/05 §3）：键位、几何、标签、候选栏、工具栏与气泡。全部不可变，解析一次后缓存。
 * Layout style (05 §3): key rows, geometry, labels, candidate bar, toolbar and bubbles. Immutable, parsed once.
 */
class LayoutStyle(
    val id: String,
    val name: String,
    val description: String,
    /** 默认配色主题 id。 Default colour theme id. */
    val theme: String,
    val geometry: KbGeometry,
    val qwerty: QwertySpec,
    val t9: T9Spec,
    val numpad: NumpadSpec,
    val labels: LabelSpec,
    val candidates: CandidateSpec,
    val toolbar: ToolbarSpec,
    val popup: PopupSpec,
    val symbols: SymbolSpec,
)

/** 一行中的一个键位记号：功能键名或一串字母；[weight] 为键宽权重，[span] 为九键跨格数。 One row token. */
class KeyToken(val name: String, val weight: Float = 1f, val span: Int = 1) {
    val isLetters get() = name !in FUNC_TOKENS && name.all { it in 'a'..'z' }
    /** 一串数字（可选的数字行）。 A run of digits (optional number row). */
    val isDigits get() = name.isNotEmpty() && name.all { it in '0'..'9' }

    override fun toString() = if (weight == 1f && span == 1) name else "$name:${if (span != 1) span else weight}"

    companion object {
        /** 26 键可用的功能键。 Function tokens usable in QWERTY rows. */
        val FUNC_TOKENS = setOf("shift", "delete", "symbol", "number", "emoji", "lang", "comma", "period", "space", "enter", "gap")
        /** 九键右列与底行可用的键。 Tokens usable in the 9-key right column and bottom row. */
        val T9_TOKENS = setOf("delete", "reset", "enter", "symbol", "number", "space", "lang", "emoji", "zero")
    }
}

class QwertySpec(
    /** 4 行（字母 3 行 + 底行），或前面再加一行数字共 5 行。 Four rows (three letter rows + bottom), optionally led by a number row. */
    val rows: List<List<KeyToken>>,
    /** 英文键盘单独的底行（null = 同 [rows]）。 Optional English rows. */
    val rowsEnglish: List<List<KeyToken>>?,
    /** 中文键盘字母显示："upper" / "lower"。 Letter case on the Chinese keyboard. */
    val letterCase: String,
    /** 字母键副标签位置："top" / "topRight"。 Secondary label position. */
    val hint: String,
    /** 默认是否显示副标签（微调可改）。 Whether secondary labels show by default. */
    val showHints: Boolean,
    /** 画成胶囊（圆角 = 半高）的功能键。 Function keys drawn as pills. */
    val pillKeys: Set<String>,
    val letterSize: Float,
    val hintSize: Float,
    val punctSize: Float,
    val funcSize: Float,
)

class T9Spec(
    /** 5 列宽度权重（第 0 列为左侧列表）。 Five column weights; column 0 is the side list. */
    val columns: FloatArray,
    /** 右列自上而下，[KeyToken.span] 为占行数，合计 4。 Right column top-down; spans sum to 4. */
    val right: List<KeyToken>,
    /** 底行第 0–3 列，[KeyToken.span] 为占列数，合计 4。 Bottom row across columns 0–3; spans sum to 4. */
    val bottom: List<KeyToken>,
    /** 左侧列表："punct"（标点）/ "symbols"（常用符号）。 Side list content. */
    val side: String,
    /** 左侧列表底色："func" / "key"。 Side list colour. */
    val sideColor: String,
)

class NumpadSpec(val columns: FloatArray)

class LabelSpec(
    val symbol: String,
    val number: String,
    /** 中英键："zhEn"（中/英）/ "globe"（地球图标）。 Language key look. */
    val lang: String,
    /** Shift 位中文时："icon"（⇧，输入中变分词）/ "split"（始终显示分词）。 Shift slot in Chinese mode. */
    val shift: String,
    /** 空格键："iconMic" / "text" / "lang"（当前语言名）/ "none"。 Space bar content. */
    val space: String,
    val spaceText: String,
    /** 回车键："auto"（图标或动作文字）/ "text"（总是文字）。 Enter key: icon/text or always text. */
    val enter: String,
    /** 回车强调色："action"（发送等动作时）/ "always" / "never"。 When Enter uses the accent colour. */
    val enterAccent: String,
)

class CandidateSpec(
    /** 英文模式："list"（左起滚动）/ "strip3"（居中三格）。 English suggestions form. */
    val english: String,
    /** 组合串："inline"（栏内左上）/ "floating"（栏上方浮层）。 Composing text placement. */
    val preedit: String,
    /** 展开按钮图标："chevron" / "grid"。 Expand button icon. */
    val expandIcon: String,
    val textSize: Float,
    /** 候选之间画竖分隔线。 Vertical dividers between candidates. */
    val dividers: Boolean,
)

class ToolbarSpec(
    /** 工具栏图标顺序（[ToolIds] 名）。 Toolbar items in order. */
    val items: List<String>,
    /** "outline" / "filled"。 */
    val icons: String,
    /** "idle"（仅空闲时）/ "always"（输入中左侧常驻首个图标）。 */
    val show: String,
    /** "spread"（均分整行）/ "edges"（首项靠左，其余靠右）。 Item placement. */
    val align: String,
    /** 图标底："none" / "circle"（按键色圆底）。 Icon backing. */
    val buttons: String,
    /** 菜单图标："logo" / "grid"。 Menu icon. */
    val menuIcon: String,
    /** 菜单图标用强调色。 Tint the menu icon with the accent colour. */
    val menuAccent: Boolean,
)

class PopupSpec(
    /** 按键气泡："float"（悬浮圆角块）/ "attached"（与按键相连）/ "none"。 Key preview bubble form. */
    val bubble: String,
    val radius: Float,
    val textSize: Float,
    val altRadius: Float,
)

class SymbolSpec(
    /** 分类选中指示："underline" / "pill"。 Selected category indicator. */
    val indicator: String,
    /** 面板结构："bottom"（底行分类 + 滚动网格）/ "side"（左侧分类 + 翻页网格）。 Panel structure. */
    val categories: String = "bottom",
)

/** 工具栏项名与功能编号（编号与 TopBarHost.onToolbar 一致）。 Toolbar item names and ids. */
object ToolIds {
    const val MENU = 0
    const val KEYBOARD = 1
    const val VOICE = 2
    const val CURSOR = 3
    const val CLIPBOARD = 4
    const val HIDE = 5
    const val EMOJI = 6
    const val SETTINGS = 7
    const val STICKERS = 8
    val NAMES = mapOf(
        "menu" to MENU, "keyboard" to KEYBOARD, "voice" to VOICE, "cursor" to CURSOR,
        "clipboard" to CLIPBOARD, "hide" to HIDE, "emoji" to EMOJI, "settings" to SETTINGS, "stickers" to STICKERS,
    )
}
