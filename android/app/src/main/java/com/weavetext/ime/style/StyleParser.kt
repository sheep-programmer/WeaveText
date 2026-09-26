package com.weavetext.ime.style

import com.weavetext.ime.ui.keyboard.KbGeometry
import com.weavetext.ime.ui.keyboard.KbPalette
import com.weavetext.ime.ui.keyboard.KeyShadow
import org.json.JSONArray
import org.json.JSONObject

/** 风格 JSON 校验失败。 A style JSON failed validation. */
class StyleException(message: String) : Exception(message)

/** 背景描述（解析结果，图片尚未解码）。 Background description; images are decoded later. */
class BackgroundSpec(
    /** "solid" / "gradient" / "image" */
    val type: String,
    val colors: IntArray,
    val angle: Float = 90f,
    /** 图片文件名（相对风格包目录）。 Image file name relative to the pack directory. */
    val image: String? = null,
    /** 模糊半径 dp（0–25）。 Blur radius in dp. */
    val blur: Float = 0f,
    /** 压暗 0–0.8。 Dim amount. */
    val dim: Float = 0f,
)

/** 配色主题：亮 / 暗两版。 Colour theme with light and dark variants. */
class ThemeStyle(
    val id: String,
    val name: String,
    val description: String,
    val light: KbPalette,
    val dark: KbPalette,
    val lightBackground: BackgroundSpec,
    val darkBackground: BackgroundSpec,
    /** Android 12+ 跟随壁纸取色。 Follows the wallpaper colours on Android 12+. */
    val dynamic: Boolean = false,
) {
    fun palette(dark: Boolean) = if (dark) this.dark else light
    fun background(dark: Boolean) = if (dark) darkBackground else lightBackground
}

/** 用户微调；null 表示沿用布局与主题。 User tweaks; null keeps the layout / theme value. */
class StyleOverrides(
    val accent: Int? = null,
    /** 普通键圆角 dp（0–20）；大键 +2。 Key corner radius in dp. */
    val radius: Float? = null,
    /** 键间距缩放（0.5–1.6）。 Key gap scale. */
    val gap: Float? = null,
    /** 键内字号缩放（0.85–1.2）。 Key text scale. */
    val textScale: Float? = null,
    val hints: Boolean? = null,
    val shadow: Boolean? = null,
    /** 按键不透明度（0.3–1），配合背景图使用。 Key face opacity, for image backgrounds. */
    val keyOpacity: Float? = null,
    val background: BackgroundSpec? = null,
) {
    val isEmpty get() = accent == null && radius == null && gap == null && textScale == null && hints == null &&
        shadow == null && keyOpacity == null && background == null

    fun toJson(): JSONObject = JSONObject().apply {
        accent?.let { put("accent", StyleParser.hex(it)) }
        radius?.let { put("radius", it.toDouble()) }
        gap?.let { put("gap", it.toDouble()) }
        textScale?.let { put("textScale", it.toDouble()) }
        hints?.let { put("hints", it) }
        shadow?.let { put("shadow", it) }
        keyOpacity?.let { put("keyOpacity", it.toDouble()) }
        background?.let { put("background", StyleParser.backgroundJson(it)) }
    }

    fun copy(
        accent: Int? = this.accent, radius: Float? = this.radius, gap: Float? = this.gap, textScale: Float? = this.textScale,
        hints: Boolean? = this.hints, shadow: Boolean? = this.shadow, keyOpacity: Float? = this.keyOpacity,
        background: BackgroundSpec? = this.background,
    ) = StyleOverrides(accent, radius, gap, textScale, hints, shadow, keyOpacity, background)

    companion object { val NONE = StyleOverrides() }
}

/**
 * 风格 JSON 解析与校验（docs/design/05）。未知字段忽略；类型、取值范围或枚举不合法时抛 [StyleException]。
 * Parses and validates style JSON (05). Unknown fields are ignored; bad types, ranges or enums throw.
 */
object StyleParser {
    /** 当前格式版本；更高版本拒绝导入。 Current format version; newer versions are rejected. */
    const val VERSION = 1

    // ------------------------------------------------------------ merge

    /** 深合并：对象逐键合并，其它值（含数组）整体替换。 Deep merge: objects merge key-wise, other values replace. */
    fun merge(base: JSONObject, over: JSONObject): JSONObject {
        val out = JSONObject(base.toString())
        for (k in over.keys()) {
            val v = over.get(k)
            val b = out.opt(k)
            out.put(k, if (v is JSONObject && b is JSONObject) merge(b, v) else v)
        }
        return out
    }

    fun checkVersion(o: JSONObject) {
        val v = o.optInt("version", VERSION)
        if (v > VERSION) throw StyleException("格式版本 $v 高于当前支持的 $VERSION")
    }

    // ------------------------------------------------------------ layout

    fun layout(o: JSONObject): LayoutStyle {
        checkVersion(o)
        val id = id(o)
        val geo = o.optJSONObject("geometry") ?: JSONObject()
        val q = o.optJSONObject("qwerty") ?: JSONObject()
        val t = o.optJSONObject("t9") ?: JSONObject()
        val n = o.optJSONObject("numpad") ?: JSONObject()
        val l = o.optJSONObject("labels") ?: JSONObject()
        val c = o.optJSONObject("candidates") ?: JSONObject()
        val tb = o.optJSONObject("toolbar") ?: JSONObject()
        val p = o.optJSONObject("popup") ?: JSONObject()
        val s = o.optJSONObject("symbols") ?: JSONObject()
        val rows = q.optJSONArray("rows")?.let { rows(it, "qwerty.rows") } ?: DEFAULT_ROWS
        val rowsEn = q.optJSONArray("rowsEnglish")?.let { rows(it, "qwerty.rowsEnglish") }
        val right = t.optJSONArray("right")?.let { tokens(it, "t9.right", t9 = true) } ?: DEFAULT_T9_RIGHT
        val bottom = t.optJSONArray("bottom")?.let { tokens(it, "t9.bottom", t9 = true) } ?: DEFAULT_T9_BOTTOM
        if (right.sumOf { it.span } != 4) throw StyleException("t9.right 占行数合计须为 4")
        if (bottom.sumOf { it.span } != 4) throw StyleException("t9.bottom 占列数合计须为 4")
        return LayoutStyle(
            id = id,
            name = o.optString("name", id),
            description = o.optString("description", ""),
            theme = o.optString("theme", "fresh"),
            geometry = KbGeometry(
                gapH = num(geo, "gapH", 6f, 0f, 16f),
                gapV = num(geo, "gapV", 10f, 2f, 20f),
                padH = num(geo, "padH", 3f, 0f, 16f),
                rowScale = num(geo, "rowScale", 1f, 0.8f, 1.25f),
                radius = num(geo, "radius", 6f, 0f, 24f),
                radiusLarge = num(geo, "radiusLarge", 8f, 0f, 28f),
            ),
            qwerty = QwertySpec(
                rows = rows,
                rowsEnglish = rowsEn,
                letterCase = enum(q, "letterCase", "upper", "upper", "lower"),
                hint = enum(q, "hint", "top", "top", "topRight"),
                showHints = bool(q, "showHints", true),
                pillKeys = q.optJSONArray("pillKeys")?.let { a ->
                    (0 until a.length()).map { a.optString(it) }.onEach {
                        if (it !in KeyToken.FUNC_TOKENS || it == "gap") throw StyleException("qwerty.pillKeys 含未知键「$it」")
                    }.toSet()
                } ?: emptySet(),
                letterSize = num(q, "letterSize", 22f, 14f, 30f),
                hintSize = num(q, "hintSize", 10.5f, 7f, 14f),
                punctSize = num(q, "punctSize", 20f, 12f, 28f),
                funcSize = num(q, "funcSize", 16f, 11f, 22f),
            ),
            t9 = T9Spec(
                columns = floats(t, "columns", floatArrayOf(1.1f, 1.3f, 1.3f, 1.3f, 1.1f), 5),
                right = right,
                bottom = bottom,
                side = enum(t, "side", "punct", "punct", "symbols"),
                sideColor = enum(t, "sideColor", "func", "func", "key"),
            ),
            numpad = NumpadSpec(floats(n, "columns", floatArrayOf(1f, 1.3f, 1.3f, 1.3f, 1.1f), 5)),
            labels = LabelSpec(
                symbol = str(l, "symbol", "符", 4),
                number = str(l, "number", "123", 4),
                lang = enum(l, "lang", "zhEn", "zhEn", "globe"),
                shift = enum(l, "shift", "icon", "icon", "split"),
                space = enum(l, "space", "iconMic", "iconMic", "text", "lang", "none"),
                spaceText = str(l, "spaceText", "空格", 8),
                enter = enum(l, "enter", "auto", "auto", "text"),
                enterAccent = enum(l, "enterAccent", "action", "action", "always", "never"),
            ),
            candidates = CandidateSpec(
                english = enum(c, "english", "list", "list", "strip3"),
                preedit = enum(c, "preedit", "inline", "inline", "floating"),
                expandIcon = enum(c, "expandIcon", "chevron", "chevron", "grid"),
                textSize = num(c, "textSize", 19f, 14f, 24f),
                dividers = bool(c, "dividers", false),
            ),
            toolbar = ToolbarSpec(
                items = tb.optJSONArray("items")?.let { toolItems(it) } ?: DEFAULT_TOOLS,
                icons = enum(tb, "icons", "outline", "outline", "filled"),
                show = enum(tb, "show", "idle", "idle", "always"),
                align = enum(tb, "align", "spread", "spread", "edges"),
                buttons = enum(tb, "buttons", "none", "none", "circle"),
                menuIcon = enum(tb, "menuIcon", "logo", "logo", "grid"),
                menuAccent = bool(tb, "menuAccent", false),
            ),
            popup = PopupSpec(
                bubble = enum(p, "bubble", "float", "float", "attached", "none"),
                radius = num(p, "radius", 10f, 0f, 24f),
                textSize = num(p, "textSize", 30f, 18f, 40f),
                altRadius = num(p, "altRadius", 10f, 0f, 24f),
            ),
            symbols = SymbolSpec(indicator = enum(s, "indicator", "underline", "underline", "pill")),
        )
    }

    private fun rows(a: JSONArray, field: String): List<List<KeyToken>> {
        if (a.length() != 4 && a.length() != 5) throw StyleException("$field 须为 4 行（或首行为数字行的 5 行）")
        val all5 = List(a.length()) { tokens(a.optJSONArray(it) ?: throw StyleException("$field[$it] 不是数组"), "$field[$it]", t9 = false, digits = it == 0 && a.length() == 5) }
        val numberRow = if (all5.size == 5) all5[0] else null
        if (numberRow != null) {
            val digits = numberRow.filter { it.isDigits }.joinToString("") { it.name }
            if (numberRow.any { !it.isDigits && it.name != "gap" } || digits.toSet().size != 10 || digits.length != 10) {
                throw StyleException("$field 数字行须恰好包含 0–9")
            }
        }
        val out = all5.takeLast(4)
        if (out[3].any { it.isLetters }) throw StyleException("$field 底行只能放功能键")
        val letters = out.take(3).flatten().filter { it.isLetters }.joinToString("") { it.name }
        if (letters.toSet().size != 26 || letters.length != 26) throw StyleException("$field 前 3 行须恰好包含 26 个字母")
        if (out[3].none { it.name == "space" }) throw StyleException("$field 底行须有空格键")
        for (r in out) if (r.first().name == "gap" && r.last().name == "gap" && r.size == 1) throw StyleException("$field 有空行")
        val all = out.flatten().map { it.name }
        for (need in listOf("delete", "enter")) if (need !in all) throw StyleException("$field 缺少 $need")
        return all5
    }

    private fun tokens(a: JSONArray, field: String, t9: Boolean, digits: Boolean = false): List<KeyToken> {
        val out = ArrayList<KeyToken>()
        for (i in 0 until a.length()) {
            val raw = a.optString(i)
            val name = raw.substringBefore(':')
            val arg = raw.substringAfter(':', "").ifEmpty { null }
            val valid = if (t9) name in KeyToken.T9_TOKENS else name in KeyToken.FUNC_TOKENS || (name.isNotEmpty() && name.all { it in 'a'..'z' }) ||
                (digits && name.isNotEmpty() && name.all { it in '0'..'9' })
            if (!valid) throw StyleException("$field 含未知键「$raw」")
            if (t9) {
                val span = arg?.toIntOrNull() ?: if (arg == null) 1 else throw StyleException("$field「$raw」跨格数无效")
                if (span !in 1..4) throw StyleException("$field「$raw」跨格数须为 1–4")
                out += KeyToken(name, 1f, span)
            } else {
                val w = arg?.toFloatOrNull() ?: if (arg == null) 1f else throw StyleException("$field「$raw」宽度无效")
                val range = if (name == "gap") 0.05f..3f else 0.5f..6f
                if (w !in range) throw StyleException("$field「$raw」宽度须在 ${range.start}–${range.endInclusive}")
                out += KeyToken(name, w)
            }
        }
        if (out.isEmpty()) throw StyleException("$field 为空")
        return out
    }

    private fun toolItems(a: JSONArray): List<String> {
        val out = (0 until a.length()).map { a.optString(it) }
        for (s in out) if (s !in ToolIds.NAMES) throw StyleException("toolbar.items 含未知项「$s」")
        if (out.size !in 2..7) throw StyleException("toolbar.items 须为 2–7 项")
        if ("menu" !in out && "settings" !in out) throw StyleException("toolbar.items 须包含 menu 或 settings")
        if (out.toSet().size != out.size) throw StyleException("toolbar.items 有重复项")
        return out
    }

    // ------------------------------------------------------------ theme

    fun theme(o: JSONObject): ThemeStyle {
        checkVersion(o)
        val id = id(o)
        val lo = o.optJSONObject("light") ?: throw StyleException("主题缺少 light")
        val dk = o.optJSONObject("dark") ?: throw StyleException("主题缺少 dark")
        return ThemeStyle(
            id = id,
            name = o.optString("name", id),
            description = o.optString("description", ""),
            light = palette(lo, false, "light"),
            dark = palette(dk, true, "dark"),
            lightBackground = background(lo.opt("background"), "light.background"),
            darkBackground = background(dk.opt("background"), "dark.background"),
            dynamic = o.optBoolean("dynamic", false),
        )
    }

    /** 解析主题的一版；缺省的颜色由基础色推导（05 §4.2）。 Missing colours are derived from the base ones. */
    fun palette(o: JSONObject, dark: Boolean, field: String): KbPalette {
        val bgSpec = background(o.opt("background"), "$field.background")
        val bg = if (bgSpec.colors.size == 1) bgSpec.colors[0] else mix(bgSpec.colors.first(), bgSpec.colors.last(), 0.5f)
        val key = color(o, "key", field) ?: throw StyleException("$field 缺少 key")
        val label = color(o, "label", field) ?: throw StyleException("$field 缺少 label")
        val accent = color(o, "accent", field) ?: throw StyleException("$field 缺少 accent")
        fun c(name: String, def: () -> Int) = color(o, name, field) ?: def()
        val keyFunc = c("keyFunc") { mix(bg, label, 0.1f) }
        val candidate = c("candidate") { accent }
        return KbPalette(
            dark = dark,
            background = bg,
            key = key,
            keyPressed = c("keyPressed") { mix(key, label, 0.12f) },
            keyFunc = keyFunc,
            keyFuncPressed = c("keyFuncPressed") { mix(keyFunc, label, 0.12f) },
            keyShadow = c("keyShadow") { mix(bg, 0xFF000000.toInt(), if (dark) 0.6f else 0.2f) },
            keyAccent = accent,
            keyAccentPressed = c("accentPressed") { mix(accent, 0xFF000000.toInt(), 0.15f) },
            onAccent = c("onAccent") { if (Contrast.ratio(0xFFFFFFFF.toInt(), accent) >= 3.5) 0xFFFFFFFF.toInt() else 0xFF111111.toInt() },
            label = label,
            labelHint = c("labelHint") { mix(label, key, 0.45f) },
            labelSecondary = c("labelSecondary") { mix(label, key, 0.35f) },
            labelDisabled = c("labelDisabled") { mix(label, key, 0.5f) },
            candidateFirst = candidate,
            icon = c("icon") { mix(label, bg, 0.15f) },
            divider = c("divider") { mix(bg, label, 0.1f) },
            toolbarActive = c("toolbarActive") { mix(bg, label, 0.08f) },
            accentSoft = c("accentSoft") { mix(bg, accent, 0.18f) },
            popup = c("popup") { if (dark) mix(key, label, 0.08f) else key },
            popupSelected = c("popupSelected") { accent },
            popupShadow = c("popupShadow") { if (dark) 0x66000000 else 0x2E1E283C },
            card = c("card") { if (dark) mix(bg, label, 0.06f) else key },
            danger = c("danger") { if (dark) 0xFFFF6166.toInt() else 0xFFE5484D.toInt() },
            voiceWave = c("voiceWave") { candidate },
            scrim = c("scrim") { if (dark) 0x7A000000 else 0x3D000000 },
            shadow = when (enum(o, "shadow", "bar", "bar", "soft", "none")) { "soft" -> KeyShadow.SOFT; "none" -> KeyShadow.NONE; else -> KeyShadow.BAR },
            stroke = c("stroke") { 0 },
            strokeWidth = num(o, "strokeWidth", 0f, 0f, 3f),
            letterMedium = num(o, "weight", 400f, 300f, 700f) >= 500f,
            candidatePill = enum(o, "candidateStyle", "text", "text", "pill") == "pill",
            candidatePillColor = c("candidatePill") { mix(bg, accent, 0.18f) },
        )
    }

    fun background(v: Any?, field: String): BackgroundSpec = when (v) {
        null -> throw StyleException("缺少 $field")
        is String -> BackgroundSpec("solid", intArrayOf(parseColor(v, field)))
        is JSONObject -> {
            val type = enum(v, "type", "solid", "solid", "gradient", "image")
            val colors = v.optJSONArray("colors")?.let { a -> IntArray(a.length()) { parseColor(a.optString(it), "$field.colors") } }
                ?: v.optString("color", "").ifEmpty { null }?.let { intArrayOf(parseColor(it, "$field.color")) }
                ?: throw StyleException("$field 缺少 colors")
            if (colors.isEmpty() || colors.size > 4) throw StyleException("$field.colors 须为 1–4 个")
            if (type == "gradient" && colors.size < 2) throw StyleException("$field 渐变至少 2 色")
            val image = if (type == "image") v.optString("image", "").ifEmpty { throw StyleException("$field 缺少 image") } else null
            if (image != null && !SAFE_FILE.matches(image)) throw StyleException("$field.image 文件名不合法")
            BackgroundSpec(
                type, colors, num(v, "angle", 90f, 0f, 360f), image,
                num(v, "blur", 0f, 0f, 25f), num(v, "dim", 0f, 0f, 0.8f),
            )
        }
        else -> throw StyleException("$field 类型错误")
    }

    fun backgroundJson(b: BackgroundSpec): JSONObject = JSONObject().apply {
        put("type", b.type)
        put("colors", JSONArray().apply { b.colors.forEach { put(hex(it)) } })
        if (b.type == "gradient") put("angle", b.angle.toDouble())
        b.image?.let { put("image", it) }
        if (b.blur > 0f) put("blur", b.blur.toDouble())
        if (b.dim > 0f) put("dim", b.dim.toDouble())
    }

    // ------------------------------------------------------------ overrides

    fun overrides(o: JSONObject?): StyleOverrides {
        if (o == null) return StyleOverrides.NONE
        return StyleOverrides(
            accent = color(o, "accent", "overrides"),
            radius = optNum(o, "radius", 0f, 20f),
            gap = optNum(o, "gap", 0.5f, 1.6f),
            textScale = optNum(o, "textScale", 0.85f, 1.2f),
            hints = if (o.has("hints")) bool(o, "hints", true) else null,
            shadow = if (o.has("shadow")) bool(o, "shadow", true) else null,
            keyOpacity = optNum(o, "keyOpacity", 0.3f, 1f),
            background = o.opt("background")?.let { background(it, "overrides.background") },
        )
    }

    // ------------------------------------------------------------ primitives

    /** 内置与导入项的 id；已保存的风格包带 "pack:" 前缀。 Ids; saved packs carry a "pack:" prefix. */
    private val ID = Regex("(pack:)?[a-z0-9][a-z0-9_-]{0,39}")
    /** 风格包内允许的文件名。 File names allowed inside a pack. */
    val SAFE_FILE = Regex("[A-Za-z0-9_-]{1,32}\\.(png|jpg|jpeg|webp)")

    private fun id(o: JSONObject): String {
        val id = o.optString("id", "")
        if (!ID.matches(id)) throw StyleException("id「$id」不合法（小写字母数字、- _，最多 40 字符）")
        return id
    }

    private fun num(o: JSONObject, k: String, def: Float, min: Float, max: Float): Float {
        if (!o.has(k)) return def
        val v = o.opt(k) as? Number ?: throw StyleException("$k 须为数字")
        val f = v.toFloat()
        if (f.isNaN() || f < min || f > max) throw StyleException("$k=$f 超出范围 $min–$max")
        return f
    }

    private fun optNum(o: JSONObject, k: String, min: Float, max: Float): Float? = if (o.has(k)) num(o, k, 0f, min, max) else null

    private fun bool(o: JSONObject, k: String, def: Boolean): Boolean {
        if (!o.has(k)) return def
        return o.opt(k) as? Boolean ?: throw StyleException("$k 须为 true/false")
    }

    private fun str(o: JSONObject, k: String, def: String, maxLen: Int): String {
        if (!o.has(k)) return def
        val s = o.opt(k) as? String ?: throw StyleException("$k 须为字符串")
        if (s.isEmpty() || s.length > maxLen) throw StyleException("$k 长度须为 1–$maxLen")
        return s
    }

    private fun enum(o: JSONObject, k: String, def: String, vararg allowed: String): String {
        if (!o.has(k)) return def
        val s = o.opt(k) as? String ?: throw StyleException("$k 须为字符串")
        if (s !in allowed) throw StyleException("$k=「$s」不在 ${allowed.joinToString("/")} 中")
        return s
    }

    private fun floats(o: JSONObject, k: String, def: FloatArray, size: Int): FloatArray {
        val a = o.optJSONArray(k) ?: return def
        if (a.length() != size) throw StyleException("$k 须为 $size 个数字")
        return FloatArray(size) { i ->
            val f = (a.opt(i) as? Number)?.toFloat() ?: throw StyleException("$k 须为数字")
            if (f !in 0.3f..4f) throw StyleException("$k 权重须在 0.3–4")
            f
        }
    }

    private fun color(o: JSONObject, k: String, field: String): Int? {
        if (!o.has(k)) return null
        val s = o.opt(k) as? String ?: throw StyleException("$field.$k 须为颜色字符串")
        return parseColor(s, "$field.$k")
    }

    /** 解析 #RRGGBB / #AARRGGBB。 Parses #RRGGBB or #AARRGGBB. */
    fun parseColor(s: String, field: String): Int {
        if (!s.startsWith("#") || (s.length != 7 && s.length != 9)) throw StyleException("$field 颜色「$s」须为 #RRGGBB 或 #AARRGGBB")
        val v = s.substring(1).toLongOrNull(16) ?: throw StyleException("$field 颜色「$s」无效")
        return if (s.length == 7) (v or 0xFF000000L).toInt() else v.toInt()
    }

    fun hex(c: Int): String = if (c ushr 24 == 0xFF) String.format("#%06X", c and 0xFFFFFF) else String.format("#%08X", c)

    /** 线性混合（含 alpha）。 Linear blend incl. alpha. */
    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a ushr s) and 0xFF) * (1 - t) + ((b ushr s) and 0xFF) * t + 0.5f).toInt().coerceIn(0, 255)
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun t(s: String) = KeyToken(s.substringBefore(':'), s.substringAfter(':', "1").toFloat())

    val DEFAULT_ROWS: List<List<KeyToken>> = listOf(
        listOf(t("qwertyuiop")),
        listOf(t("asdfghjkl")),
        listOf(t("shift:1.5"), t("zxcvbnm"), t("delete:1.5")),
        listOf(t("symbol:1.3"), t("number:1.3"), t("comma"), t("space:2.8"), t("period"), t("lang:1.3"), t("enter:1.3")),
    )
    val DEFAULT_T9_RIGHT = listOf(KeyToken("delete"), KeyToken("reset"), KeyToken("enter", span = 2))
    val DEFAULT_T9_BOTTOM = listOf(KeyToken("symbol"), KeyToken("number"), KeyToken("space"), KeyToken("lang"))
    val DEFAULT_TOOLS = listOf("menu", "keyboard", "voice", "cursor", "clipboard", "hide")
}
