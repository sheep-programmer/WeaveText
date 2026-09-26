package com.weavetext.ime.ui.keyboard

import android.graphics.RectF
import com.weavetext.ime.style.LabelSpec
import com.weavetext.ime.style.LayoutStyle
import com.weavetext.ime.style.NumpadSpec
import com.weavetext.ime.style.QwertySpec
import com.weavetext.ime.style.StyleParser
import com.weavetext.ime.style.T9Spec

/** 按键功能码（负数）；正数为字符码点。 Function key codes (negative); positive = code point. */
object KeyCode {
    const val SHIFT = -1
    const val DELETE = -2
    const val SYMBOL = -3
    const val NUMBER = -4
    const val SPACE = -5
    const val LANG = -6
    const val ENTER = -7
    const val BACK = -8
    const val T9_RESET = -9
    const val SEPARATOR = -10
    const val T9_ONE = -11
    const val EMOJI = -12
}

/** 按键风格。 Key styles. */
object KeyStyle {
    const val CHAR = 0
    const val FUNC = 1
    const val ACCENT = 2
}

/**
 * 一个按键（布局 + 缓存的几何）。标签等可在状态变化时就地更新，绘制时零分配。
 * One key (layout + cached geometry). Labels may be updated in place; drawing allocates nothing.
 */
class Key(
    val code: Int,
    var label: String = "",
    /** 上方副标签（显示用）。 Top hint label. */
    var hint: String? = null,
    /** 上滑输出。 Swipe-up output. */
    var up: String? = null,
    /** 长按候选。 Long-press alternatives. */
    var longPress: List<String>? = null,
    var style: Int = KeyStyle.CHAR,
    var icon: Int = 0,
) {
    /** 触控格子（含间隙）。 Touch cell (incl. gaps). */
    val cell = RectF()
    /** 视觉矩形。 Visual rect. */
    val rect = RectF()
    /** 右下角提示（双拼韵母 / 五笔键名）。 Bottom-right hint. */
    var corner: String? = null
    var cornerAccent = false
    /** 九键数字等下方副标签。 Sub label below (T9 digit). */
    var sub: String? = null
    /** 长按展示（仅显示，不输入），如五笔口诀。 Display-only long-press text. */
    var longInfo: String? = null
    var large = false
    var disabled = false
    /** 激活态（Shift 锁定等）。 Active state. */
    var active = false
    /** 主标签字号 px（布局时计算）。 Label size in px. */
    var labelSize = 0f
    /** 主标签为中文功能字（500 字重）。 Label uses the medium CJK style. */
    var medium = false
    /** 预览气泡。 Show preview bubble. */
    var preview = true
    /** 26 键：所在行与键宽权重。 QWERTY row and width weight. */
    var row = 0
    var weight = 1f
    /** 键前 / 键后留空（单位宽），触控区各分一半给两侧键。 Spacing before / after the key; touch area split between neighbours. */
    var gapBefore = 0f
    var gapAfter = 0f
    /** 画成胶囊。 Drawn as a pill. */
    var pill = false
    /** 网格键盘（九键、数字）：列、行与跨度。 Grid keyboards: column, row and spans. */
    var gx = 0
    var gy = 0
    var gw = 1
    var gh = 1
    val isChar get() = code > 0
}

/** 左侧可滚动列表（九键拼音/标点、数字键盘符号）。 Left scrollable column. */
class SideList {
    val rect = RectF()
    var items: List<String> = emptyList()
    var highlighted = -1
    var itemHeight = 0f
    var scroll = 0f
    var textSize = 0f
    fun maxScroll() = (items.size * itemHeight - rect.height()).coerceAtLeast(0f)
}

/**
 * 键盘布局构建（02 §3、§7、§13）。 Layout builders.
 */
object Layouts {
    const val QWERTY = 0
    const val T9 = 1
    const val NUMPAD = 2
    const val T14 = 3

    private const val ROW1 = "qwertyuiop"
    private const val ROW2 = "asdfghjkl"
    private const val ROW3 = "zxcvbnm"
    private val CN_UP = arrayOf("1234567890", "~!@#%“”*?", "（）-_：；、")
    private val EN_UP = arrayOf("1234567890", "~!@#%\"'*?", "()-_:;/")
    private val DIGIT_VARIANTS = arrayOf(
        listOf("1", "¹", "₁", "①"), listOf("2", "²", "₂", "②"), listOf("3", "³", "₃", "③"),
        listOf("4", "⁴", "₄", "④"), listOf("5", "⁵", "₅", "⑤"), listOf("6", "⁶", "₆", "⑥"),
        listOf("7", "⁷", "₇", "⑦"), listOf("8", "⁸", "₈", "⑧"), listOf("9", "⁹", "₉", "⑨"),
        listOf("0", "⁰", "₀", "⓪"),
    )

    private val HINTS_CN = hintMap(CN_UP)
    private val HINTS_EN = hintMap(EN_UP)

    private fun hintMap(up: Array<String>): Map<Char, Char> {
        val m = HashMap<Char, Char>()
        for ((r, row) in arrayOf(ROW1, ROW2, ROW3).withIndex()) row.forEachIndexed { i, c -> m[c] = up[r][i] }
        return m
    }

    private fun func(code: Int, label: String = "", icon: Int = 0) =
        Key(code, label, style = KeyStyle.FUNC, icon = icon).apply { preview = false; medium = label.isNotEmpty() }

    /**
     * 26 键（中文或英文），按布局风格的行定义生成；默认行与原固定布局逐键一致。
     * QWERTY for Chinese or English built from the layout style's rows; the default rows match the original layout key by key.
     */
    fun qwerty(english: Boolean, spec: QwertySpec = DEFAULT.qwerty, labels: LabelSpec = DEFAULT.labels): List<Key> {
        val hints = if (english) HINTS_EN else HINTS_CN
        val rows = if (english) spec.rowsEnglish ?: spec.rows else spec.rows
        val keys = ArrayList<Key>(36)
        for ((r, tokens) in rows.withIndex()) {
            var gap = 0f
            val rowStart = keys.size
            for (t in tokens) {
            if (t.name == "gap") { gap += t.weight; continue }
            if (t.isLetters) {
                for (c in t.name) {
                    val s = hints[c]?.toString()
                    val i = ROW1.indexOf(c)
                    val alts = if (i >= 0) DIGIT_VARIANTS[i] else if (s != null) listOf(s, c.toString()) else null
                    keys += Key(c.code, c.uppercase(), s, s, alts).apply { row = r; weight = t.weight; gapBefore = gap }
                    gap = 0f
                }
                continue
            }
            keys += when (t.name) {
                "shift" -> func(KeyCode.SHIFT, icon = com.weavetext.ime.R.drawable.ic_shift)
                "delete" -> func(KeyCode.DELETE, icon = com.weavetext.ime.R.drawable.ic_backspace)
                "symbol" -> func(KeyCode.SYMBOL, labels.symbol)
                "number" -> func(KeyCode.NUMBER, labels.number)
                "emoji" -> func(KeyCode.EMOJI, icon = com.weavetext.ime.R.drawable.ic_emoji)
                "lang" -> func(KeyCode.LANG)
                "enter" -> func(KeyCode.ENTER)
                "space" -> Key(KeyCode.SPACE).apply { preview = false }
                "comma" -> if (english) Key(','.code, ",", longPress = listOf(",", ";", ":", "'"))
                else Key(','.code, "，", ",", ",", listOf("，", "、", "；", "：", ","))
                else -> if (english) Key('.'.code, ".", longPress = listOf(".", "?", "!", "…", "@", "/"))
                else Key('.'.code, "。", ".", ".", listOf("。", "？", "！", "…", "·"))
            }.apply { row = r; weight = t.weight; gapBefore = gap; pill = t.name in spec.pillKeys }
            gap = 0f
            }
            if (gap > 0f && keys.size > rowStart) keys.last().gapAfter = gap
        }
        if (!english && spec.letterCase == "lower") for (k in keys) if (k.code in 'a'.code..'z'.code) k.label = k.code.toChar().toString()
        return keys
    }

    /** 键区宽于此值（dp）时分体。 Split when the key area is wider than this (dp). */
    const val SPLIT_MIN_DP = 600f

    /** 分体中缝宽度 px；不分体为 0。 Width of the split gap in px; 0 when not split. */
    fun splitGap(w: Float, m: KbMetrics, allowed: Boolean): Float =
        if (allowed && w / m.density > SPLIT_MIN_DP) w * 0.2f else 0f

    /**
     * 布置 26 键几何：单位宽 = 可用宽 / max(10, 最宽行权重)，较窄的行居中，行两端空白并入首尾键的触控区（无死区）。
     * Lay out QWERTY: unit = width / max(10, widest row); narrower rows are centred and their side gaps join the edge keys.
     */
    fun layoutQwerty(keys: List<Key>, w: Float, m: KbMetrics, spec: QwertySpec = DEFAULT.qwerty, splitGap: Float = 0f) {
        if (splitGap > 0f) { layoutSplit(keys, w, m, spec, splitGap); return }
        val rows = (keys.maxOfOrNull { it.row } ?: -1) + 1
        val rowWeights = FloatArray(rows)
        for (k in keys) rowWeights[k.row] += k.weight + k.gapBefore + k.gapAfter
        // 浮点累加误差（如 1.3 × 4 + 2.8 + 2）不算超宽。 Ignore float rounding when summing weights.
        val units = (rowWeights.maxOrNull() ?: 10f).let { if (it < 10.01f) 10f else it }
        val unit = (w - 2 * m.padH) / units
        for (r in 0 until rows) {
            val top = r * m.rowPitch
            val indent = (units - rowWeights[r]) / 2f
            var x = m.padH + (if (indent < 0.005f) 0f else unit * indent)
            var first: Key? = null
            var last: Key? = null
            for (k in keys) {
                if (k.row != r) continue
                if (k.gapBefore > 0f) x += unit * k.gapBefore
                k.cell.set(x, top, x + unit * k.weight, top + m.rowPitch)
                k.rect.set(k.cell)
                k.rect.inset(m.insetH, m.insetV)
                x += unit * k.weight
                if (k.gapBefore > 0f) {
                    // 空隙的触控区两侧各分一半。 Split the gap's touch area between neighbours.
                    val half = unit * k.gapBefore / 2f
                    k.cell.left -= half
                    last?.let { it.cell.right += half }
                }
                if (k.gapAfter > 0f) { k.cell.right += unit * k.gapAfter; x += unit * k.gapAfter }
                if (first == null) first = k
                last = k
            }
            if (first != null && first.cell.left > m.padH + 1f) first.cell.left = m.padH
            if (last != null && last.cell.right < w - m.padH - 1f) last.cell.right = w - m.padH
        }
        // 边缘格子延伸到视图边缘。 Edge cells reach the view edges.
        for (k in keys) {
            if (k.cell.left <= m.padH + 1f) k.cell.left = 0f
            if (k.cell.right >= w - m.padH - 1f) k.cell.right = w
        }
        for (k in keys) {
            k.labelSize = when {
                k.code in 'a'.code..'z'.code -> m.letter(spec.letterSize)
                k.code == ','.code || k.code == '.'.code -> m.label(spec.punctSize)
                else -> m.label(spec.funcSize)
            }
        }
    }

    /**
     * 分体：先按去掉中缝的宽度正常排布，再把中线右侧的键整体右移；跨中线的功能键（空格）横跨中缝，
     * 跨中线的字母键按中心归到一侧。中缝的触控区两侧各分一半，没有死区。
     * Split: lay out at the width minus the gap, then shift keys right of the midline; function keys straddling
     * it (the space bar) span the gap, straddling letters go by their centre. The gap's touch area is shared.
     */
    private fun layoutSplit(keys: List<Key>, w: Float, m: KbMetrics, spec: QwertySpec, gap: Float) {
        val inner = w - gap
        layoutQwerty(keys, inner, m, spec)
        val mid = inner / 2f
        val eps = 1f
        val rows = (keys.maxOfOrNull { it.row } ?: -1) + 1
        for (r in 0 until rows) {
            var lastLeft: Key? = null
            var firstRight: Key? = null
            for (k in keys) {
                if (k.row != r) continue
                val straddles = k.rect.left < mid - eps && k.rect.right > mid + eps
                when {
                    straddles && !(k.code in 'a'.code..'z'.code) -> { k.rect.right += gap; k.cell.right += gap }
                    k.rect.centerX() > mid + eps -> {
                        k.rect.offset(gap, 0f); k.cell.offset(gap, 0f)
                        if (firstRight == null) firstRight = k
                    }
                    else -> lastLeft = k
                }
            }
            val a = lastLeft
            val b = firstRight
            if (a != null && b != null && b.cell.left > a.cell.right) {
                val half = (a.cell.right + b.cell.left) / 2f
                a.cell.right = half
                b.cell.left = half
            }
        }
        for (k in keys) if (k.cell.right >= inner - 1f && k.cell.right < w) k.cell.right = w
    }

    private val T9_LETTERS = arrayOf("ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ")

    private fun t9Key(name: String, labels: LabelSpec): Key = when (name) {
        "delete" -> func(KeyCode.DELETE, icon = com.weavetext.ime.R.drawable.ic_backspace)
        "reset" -> func(KeyCode.T9_RESET, "重输")
        "enter" -> func(KeyCode.ENTER)
        "symbol" -> func(KeyCode.SYMBOL, labels.symbol)
        "number" -> func(KeyCode.NUMBER, labels.number)
        "space" -> Key(KeyCode.SPACE, up = "0").apply { preview = false }
        "lang" -> func(KeyCode.LANG)
        "emoji" -> func(KeyCode.EMOJI, icon = com.weavetext.ime.R.drawable.ic_emoji)
        else -> Key('0'.code, "0", longPress = listOf("0", "°")).apply { preview = false }
    }.apply { large = true }

    /** 九键：返回按键列表，左侧列表另行布置。 T9 keys (side list laid out separately). */
    fun t9(spec: T9Spec = DEFAULT.t9, labels: LabelSpec = DEFAULT.labels): List<Key> {
        val keys = ArrayList<Key>(16)
        keys += Key(KeyCode.T9_ONE, "，。?!").apply { sub = "1"; preview = false; large = true; gx = 1; gy = 0 }
        for (d in 2..9) {
            val letters = T9_LETTERS[d - 2]
            keys += Key('0'.code + d, letters, d.toString(), d.toString(), letters.lowercase().map { it.toString() } + d.toString()).apply {
                sub = d.toString(); preview = false; large = true; medium = true
                gx = 1 + (d - 1) % 3; gy = (d - 1) / 3
            }
        }
        var y = 0
        for (t in spec.right) { keys += t9Key(t.name, labels).apply { gx = 4; gy = y; gh = t.span }; y += t.span }
        var x = 0
        for (t in spec.bottom) { keys += t9Key(t.name, labels).apply { gx = x; gy = 3; gw = t.span }; x += t.span }
        // 有独立 0 键时空格不再上滑出 0。 With a dedicated 0 key the space bar drops its swipe-up 0.
        if (keys.any { it.code == '0'.code }) keys.firstOrNull { it.code == KeyCode.SPACE }?.up = null
        return keys
    }

    /** 14 键的字母分组（键码 'A' 起，与内核 t14 方案一致）。 14-key letter groups, coded from 'A' as in the engine. */
    val T14_GROUPS = arrayOf("qw", "er", "ty", "ui", "op", "as", "df", "gh", "jk", "l", "zx", "cv", "bn", "m")

    /**
     * 14 键拼音：三行 5/5/4 个双字母键（第三行末尾是删除），左侧拼音列与底行沿用九键的定义，底行另加分词键与回车。
     * 14-key pinyin: rows of 5/5/4 two-letter keys (Delete ends the third row); the side column and bottom row
     * follow the 9-key definition, plus the separator key and Enter.
     */
    fun t14(spec: T9Spec = DEFAULT.t9, labels: LabelSpec = DEFAULT.labels, lower: Boolean = false): List<Key> {
        val keys = ArrayList<Key>(24)
        for ((i, g) in T14_GROUPS.withIndex()) {
            val row = if (i < 5) 0 else if (i < 10) 1 else 2
            // 首行长按带数字（与 26 键上排一致）。 The first row's long press offers its digits, as on QWERTY.
            val digits = if (row == 0) g.map { ((ROW1.indexOf(it) + 1) % 10).toString() } else emptyList()
            keys += Key('A'.code + i, if (lower) g else g.uppercase(), longPress = g.map { it.toString() } + digits).apply {
                this.row = row; gx = if (row == 0) i else if (row == 1) i - 5 else i - 10
                large = true; medium = true
            }
        }
        keys += func(KeyCode.DELETE, icon = com.weavetext.ime.R.drawable.ic_backspace).apply { row = 2; gx = 4; large = true }
        val bottom = spec.bottom.filter { it.name != "delete" && it.name != "enter" && it.name != "reset" }
        for (t in bottom) {
            if (t.name == "space") keys += Key(KeyCode.T9_ONE, "，。?!", style = KeyStyle.FUNC).apply { preview = false; large = true; row = 3; weight = 1f }
            // 数字走 123 键，空格不再上滑出 0。 Digits live behind 123; the space bar has no swipe-up 0.
            keys += t9Key(t.name, labels).apply { row = 3; weight = if (t.name == "space") 2.2f * t.span else t.span.toFloat(); if (code == KeyCode.SPACE) up = null }
        }
        keys += t9Key("enter", labels).apply { row = 3; weight = 1.4f }
        return keys
    }

    fun layoutT14(keys: List<Key>, side: SideList, w: Float, m: KbMetrics, spec: T9Spec = DEFAULT.t9) {
        val avail = w - 2 * m.padH
        val sideW = avail * spec.columns[0] / spec.columns.sum()
        val x0 = m.padH + sideW
        val unit = (w - m.padH - x0) / 5f
        side.rect.set(m.padH, 0f, x0, 3 * m.rowPitch)
        side.rect.inset(m.insetH, m.insetV)
        side.itemHeight = m.dp(44f).coerceAtMost(side.rect.height() / 4f)
        side.textSize = minOf(m.label(15f), side.itemHeight * 0.6f)
        var bottomUnits = 0f
        for (k in keys) if (k.row == 3) bottomUnits += k.weight
        var bx = m.padH
        for (k in keys) {
            if (k.row < 3) {
                val left = x0 + k.gx * unit
                k.cell.set(left, k.row * m.rowPitch, left + unit, (k.row + 1) * m.rowPitch)
            } else {
                val bw = avail * k.weight / bottomUnits
                k.cell.set(bx, 3 * m.rowPitch, bx + bw, 4 * m.rowPitch)
                bx += bw
            }
            k.rect.set(k.cell)
            k.rect.inset(m.insetH, m.insetV)
            k.labelSize = if (k.code in 'A'.code..'N'.code) m.letter(19f) else m.label(16f)
            if (k.cell.left <= m.padH + 1f) k.cell.left = 0f
            if (k.cell.right >= w - m.padH - 1f) k.cell.right = w
        }
    }

    /** 5 列网格几何。 Five-column grid geometry. */
    private fun grid(k: Key, xs: FloatArray, m: KbMetrics) {
        k.cell.set(xs[k.gx], k.gy * m.rowPitch, xs[k.gx + k.gw], (k.gy + k.gh) * m.rowPitch)
        k.rect.set(k.cell)
        k.rect.inset(m.insetH, m.insetV)
    }

    private fun columns(w: Float, weights: FloatArray, m: KbMetrics): FloatArray {
        val total = weights.sum()
        val avail = w - 2 * m.padH
        val xs = FloatArray(weights.size + 1)
        xs[0] = m.padH
        for (i in weights.indices) xs[i + 1] = xs[i] + avail * weights[i] / total
        return xs
    }

    fun layoutT9(keys: List<Key>, side: SideList, w: Float, m: KbMetrics, spec: T9Spec = DEFAULT.t9) {
        val xs = columns(w, spec.columns, m)
        for (k in keys) grid(k, xs, m)
        side.rect.set(xs[0], 0f, xs[1], 3 * m.rowPitch)
        side.rect.inset(m.insetH, m.insetV)
        side.itemHeight = m.dp(44f).coerceAtMost(side.rect.height() / 4f)
        side.textSize = minOf(m.label(15f), side.itemHeight * 0.6f)
        for (k in keys) {
            k.labelSize = if (k.code in '2'.code..'9'.code) m.label(17f) else m.label(16f)
            if (k.cell.left <= m.padH + 1f) k.cell.left = 0f
            if (k.cell.right >= w - m.padH - 1f) k.cell.right = w
        }
    }

    const val T9_PUNCT = "，。？！…～、：；"
    const val T9_SYMBOLS = "@#*/+-=_&%"
    const val NUM_SYMBOLS = "+-*/%=.,:@()#"

    fun numpad(fieldKind: Int, labels: LabelSpec = DEFAULT.labels): List<Key> {
        val keys = ArrayList<Key>(16)
        for (d in 1..9) keys += Key('0'.code + d, d.toString()).apply { preview = false; large = true; gx = 1 + (d - 1) % 3; gy = (d - 1) / 3 }
        keys += Key(KeyCode.DELETE, style = KeyStyle.FUNC, icon = com.weavetext.ime.R.drawable.ic_backspace).apply { preview = false; large = true; gx = 4; gy = 0 }
        keys += Key(' '.code, "空格", style = KeyStyle.FUNC).apply { preview = false; large = true; medium = true; gx = 4; gy = 1 }
        keys += Key(KeyCode.ENTER, style = KeyStyle.FUNC).apply { preview = false; large = true; gx = 4; gy = 2; gh = 2 }
        keys += when (fieldKind) {
            1 -> Key(','.code, ",").apply { large = true; preview = false }
            2 -> Key('+'.code, "+").apply { large = true; preview = false }
            else -> Key(KeyCode.BACK, "返回", style = KeyStyle.FUNC).apply { preview = false; large = true; medium = true }
        }.apply { gx = 0; gy = 3 }
        keys += Key(KeyCode.SYMBOL, labels.symbol, style = KeyStyle.FUNC).apply { preview = false; large = true; medium = true; gx = 1; gy = 3 }
        keys += Key('0'.code, "0", longPress = listOf("0", "°")).apply { preview = false; large = true; gx = 2; gy = 3 }
        keys += Key('.'.code, ".", longPress = listOf(".", ",", ":")).apply { preview = false; large = true; gx = 3; gy = 3 }
        return keys
    }

    fun layoutNumpad(keys: List<Key>, side: SideList, w: Float, m: KbMetrics, spec: NumpadSpec = DEFAULT.numpad) {
        val xs = columns(w, spec.columns, m)
        for (k in keys) grid(k, xs, m)
        side.rect.set(xs[0], 0f, xs[1], 3 * m.rowPitch)
        side.rect.inset(m.insetH, m.insetV)
        side.itemHeight = 3 * m.rowPitch / 5f
        side.textSize = minOf(m.label(18f), side.itemHeight * 0.6f)
        for (k in keys) {
            k.labelSize = if (k.code in '0'.code..'9'.code || k.code == '.'.code || k.code == ','.code || k.code == '+'.code) m.label(24f) else m.label(16f)
            if (k.cell.left <= m.padH + 1f) k.cell.left = 0f
            if (k.cell.right >= w - m.padH - 1f) k.cell.right = w
        }
    }

    /** 未加载资源时使用的内置默认布局（与 assets/styles/layout-fresh.json 一致）。 Built-in default layout. */
    val DEFAULT: LayoutStyle by lazy { StyleParser.layout(org.json.JSONObject().put("id", "fresh")) }
}

/** 双拼韵母提示表（与内核方案一致）。 Shuangpin final hints (mirrors the engine schemes). */
object ShuangpinHints {
    private val XIAOHE = mapOf(
        'q' to "iu", 'w' to "ei", 'e' to "e", 'r' to "uan", 't' to "ue", 'y' to "un", 'u' to "sh/u", 'i' to "ch/i",
        'o' to "uo", 'p' to "ie", 'a' to "a", 's' to "ong", 'd' to "ai", 'f' to "en", 'g' to "eng", 'h' to "ang",
        'j' to "an", 'k' to "ing", 'l' to "iang", 'z' to "ou", 'x' to "ua", 'c' to "ao", 'v' to "zh/ui", 'b' to "in",
        'n' to "iao", 'm' to "ian",
    )
    private val ZIRANMA = mapOf(
        'q' to "iu", 'w' to "ua", 'e' to "e", 'r' to "uan", 't' to "ue", 'y' to "ing", 'u' to "sh/u", 'i' to "ch/i",
        'o' to "uo", 'p' to "un", 'a' to "a", 's' to "ong", 'd' to "iang", 'f' to "en", 'g' to "eng", 'h' to "ang",
        'j' to "an", 'k' to "ao", 'l' to "ai", 'z' to "ei", 'x' to "ie", 'c' to "iao", 'v' to "zh/ui", 'b' to "ou",
        'n' to "in", 'm' to "ian",
    )
    private val MICROSOFT = mapOf(
        'q' to "iu", 'w' to "ua", 'e' to "e", 'r' to "uan", 't' to "ue", 'y' to "uai", 'u' to "sh/u", 'i' to "ch/i",
        'o' to "uo", 'p' to "un", 'a' to "a", 's' to "ong", 'd' to "iang", 'f' to "en", 'g' to "eng", 'h' to "ang",
        'j' to "an", 'k' to "ao", 'l' to "ai", 'z' to "ei", 'x' to "ie", 'c' to "iao", 'v' to "zh/ui", 'b' to "ou",
        'n' to "in", 'm' to "ian",
    )
    private val SOGOU = MICROSOFT

    fun of(scheme: String): Map<Char, String> = when (scheme) {
        "ziranma" -> ZIRANMA
        "microsoft" -> MICROSOFT
        "sogou" -> SOGOU
        else -> XIAOHE
    }

    /** 微软/搜狗用 ; 表示 ing，26 键没有该键，放在 L 的上滑。 These schemes use ';' for "ing". */
    fun usesSemicolon(scheme: String) = scheme == "microsoft" || scheme == "sogou"
}

/** 五笔键名与口诀（02 §6）。 Wubi key names and rhymes. */
object WubiRoots {
    val NAMES = mapOf(
        'g' to "王", 'f' to "土", 'd' to "大", 's' to "木", 'a' to "工",
        'h' to "目", 'j' to "日", 'k' to "口", 'l' to "田", 'm' to "山",
        't' to "禾", 'r' to "白", 'e' to "月", 'w' to "人", 'q' to "金",
        'y' to "言", 'u' to "立", 'i' to "水", 'o' to "火", 'p' to "之",
        'n' to "已", 'b' to "子", 'v' to "女", 'c' to "又", 'x' to "纟", 'z' to "？",
    )
    val RHYMES = mapOf(
        'g' to "王旁青头戋五一", 'f' to "土士二干十寸雨", 'd' to "大犬三羊古石厂", 's' to "木丁西", 'a' to "工戈草头右框七",
        'h' to "目具上止卜虎皮", 'j' to "日早两竖与虫依", 'k' to "口与川，字根稀", 'l' to "田甲方框四车力", 'm' to "山由贝，下框几",
        't' to "禾竹一撇双人立，反文条头共三一", 'r' to "白手看头三二斤", 'e' to "月彡乃用家衣底", 'w' to "人和八，三四里", 'q' to "金勺缺点无尾鱼，犬旁留叉儿一点夕，氏无七",
        'y' to "言文方广在四一，高头一捺谁人去", 'u' to "立辛两点六门疒", 'i' to "水旁兴头小倒立", 'o' to "火业头，四点米", 'p' to "之字军盖建道底，摘礻衤",
        'n' to "已半巳满不出己，左框折尸心和羽", 'b' to "子耳了也框向上", 'v' to "女刀九臼山朝西", 'c' to "又巴马，丢矢矣", 'x' to "慈母无心弓和匕，幼无力",
        'z' to "万能学习键",
    )
}
