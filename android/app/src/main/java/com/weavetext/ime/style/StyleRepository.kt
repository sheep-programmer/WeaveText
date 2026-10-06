package com.weavetext.ime.style

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.ui.keyboard.KbBackdrop
import com.weavetext.ime.ui.keyboard.KbMetrics
import com.weavetext.ime.ui.keyboard.KbPalette
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 一个已保存的风格包（「我的风格」或导入的 .wvskin）。 A saved style pack. */
class StylePack(val id: String, val name: String, val dir: File, val json: JSONObject) {
    val layoutJson: JSONObject get() = json.optJSONObject("layout") ?: JSONObject()
    val themeJson: JSONObject get() = json.optJSONObject("theme") ?: JSONObject()
    val overridesJson: JSONObject? get() = json.optJSONObject("overrides")
    /** 包内布局 / 主题是否不只是引用内置项。 Whether the pack defines its own layout / theme. */
    val ownLayout get() = layoutJson.keys().asSequence().any { it != "extends" }
    val ownTheme get() = themeJson.keys().asSequence().any { it != "extends" }
}

/** 待确认的导入。 A staged import awaiting confirmation. */
class StagedImport(val dir: File, val name: String, val layoutName: String, val themeName: String, val hasImage: Boolean, val json: JSONObject)

/**
 * 风格仓库：内置预设（assets/styles/ 下的 JSON）、用户风格包（filesDir/styles）、当前组合的解析与缓存、导入导出。
 * Style repository: built-in presets, saved packs, resolving and caching the current combination, import / export.
 */
class StyleRepository private constructor(private val app: Context) {
    private val rawLayouts = LinkedHashMap<String, JSONObject>()
    private val rawThemes = LinkedHashMap<String, JSONObject>()
    private val layoutCache = HashMap<String, LayoutStyle>()
    private val themeCache = HashMap<String, ThemeStyle>()
    private val imageCache = HashMap<String, Bitmap?>()
    private var lastSig: String? = null
    private var lastStyle: KeyboardStyle? = null

    val root = File(app.filesDir, "styles")
    val packsDir = File(root, "packs")
    /** 当前微调使用的背景图目录。 Directory holding the current tweak background image. */
    val currentDir = File(root, "current")

    init {
        val names = app.assets.list("styles").orEmpty().filter { it.endsWith(".json") }.sorted()
        val loaded = names.map { n -> JSONObject(app.assets.open("styles/$n").bufferedReader().use { it.readText() }) }
        for (o in loaded.sortedBy { it.optInt("order", 100) }) {
            when (o.optString("kind")) {
                "layout" -> rawLayouts[o.getString("id")] = o
                "theme" -> rawThemes[o.getString("id")] = o
            }
        }
    }

    // ------------------------------------------------------------ built-ins

    val layoutIds: List<String> get() = rawLayouts.keys.toList()

    /** 可选主题（动态取色仅 Android 12+）。 Selectable themes; dynamic colour needs Android 12+. */
    val themeIds: List<String> get() = rawThemes.filter { !it.value.optBoolean("dynamic") || Build.VERSION.SDK_INT >= 31 }.keys.toList()

    fun layout(id: String): LayoutStyle = synchronized(this) {
        layoutCache.getOrPut(id) {
            if (id.startsWith(PACK)) {
                val pack = pack(id.removePrefix(PACK)) ?: return@synchronized layout(DEFAULT_LAYOUT)
                StyleParser.layout(expand(pack.layoutJson, rawLayouts, id))
            } else {
                val raw = rawLayouts[id] ?: return@synchronized layout(DEFAULT_LAYOUT)
                StyleParser.layout(expand(raw, rawLayouts, id))
            }
        }
    }

    fun theme(id: String): ThemeStyle = synchronized(this) {
        themeCache.getOrPut(id) {
            val raw = if (id.startsWith(PACK)) {
                val pack = pack(id.removePrefix(PACK)) ?: return@synchronized theme(DEFAULT_THEME)
                expand(pack.themeJson, rawThemes, id)
            } else expand(rawThemes[id] ?: return@synchronized theme(DEFAULT_THEME), rawThemes, id)
            StyleParser.theme(if (raw.optBoolean("dynamic")) dynamicColors(raw) else raw)
        }
    }

    /** 展开 extends 链（最多 4 层）。 Expands the extends chain (up to 4 levels). */
    private fun expand(o: JSONObject, pool: Map<String, JSONObject>, id: String, depth: Int = 0): JSONObject {
        val base = o.optString("extends", "")
        val out = if (base.isEmpty()) JSONObject(o.toString()) else {
            if (depth > 3) throw StyleException("extends 层级过深")
            val b = pool[base] ?: throw StyleException("extends 引用了不存在的「$base」")
            StyleParser.merge(expand(b, pool, base, depth + 1), o)
        }
        out.remove("extends")
        out.put("id", id)
        return out
    }

    /**
     * 动态取色：用系统调色板（Android 12+）替换基础色，其余颜色由解析器推导。
     * Dynamic colour: replace the base colours with the system palette; the parser derives the rest.
     */
    private fun dynamicColors(raw: JSONObject): JSONObject {
        if (Build.VERSION.SDK_INT < 31) return raw
        val r = app.resources
        fun c(id: Int) = StyleParser.hex(r.getColor(id, null))
        val light = JSONObject()
            .put("background", c(android.R.color.system_neutral1_100))
            .put("key", c(android.R.color.system_neutral1_10))
            .put("keyFunc", c(android.R.color.system_neutral2_200))
            .put("label", c(android.R.color.system_neutral1_900))
            .put("accent", c(android.R.color.system_accent1_600))
            .put("candidate", c(android.R.color.system_accent1_700))
            .put("accentSoft", c(android.R.color.system_accent1_100))
        val dark = JSONObject()
            .put("background", c(android.R.color.system_neutral1_900))
            .put("key", c(android.R.color.system_neutral1_700))
            .put("keyFunc", c(android.R.color.system_neutral2_800))
            .put("label", c(android.R.color.system_neutral1_50))
            .put("accent", c(android.R.color.system_accent1_300))
            .put("onAccent", c(android.R.color.system_accent1_900))
            .put("candidate", c(android.R.color.system_accent1_200))
            .put("accentSoft", c(android.R.color.system_accent1_800))
        return StyleParser.merge(raw, JSONObject().put("light", light).put("dark", dark))
    }

    // ------------------------------------------------------------ resolve

    fun isDark(ctx: Context, prefs: SharedPreferences): Boolean = when (WeavePrefs.theme(prefs)) {
        "light" -> false
        "dark" -> true
        else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /** 按当前设置解析（相同设置与配置时返回缓存）。 Resolve from preferences; cached while nothing changes. */
    fun resolve(ctx: Context, prefs: SharedPreferences, level: Int = WeavePrefs.heightLevel(prefs)): KeyboardStyle {
        val layoutId = WeavePrefs.styleLayout(prefs)
        val themeId = WeavePrefs.styleTheme(prefs)
        return resolve(ctx, layoutId, themeId, overrides(prefs), isDark(ctx, prefs), level, prefs.getLong(WeavePrefs.STYLE_STAMP, 0), WeavePrefs.pinyinHint(prefs) != 0)
    }

    fun overrides(prefs: SharedPreferences): StyleOverrides =
        runCatching { StyleParser.overrides(JSONObject(prefs.getString(WeavePrefs.STYLE_OVERRIDES, null) ?: "{}")) }
            .getOrDefault(StyleOverrides.NONE)

    fun resolve(
        ctx: Context, layoutId: String, themeId: String, o: StyleOverrides, dark: Boolean, level: Int, stamp: Long = 0, pinyinAbove: Boolean = true,
    ): KeyboardStyle = synchronized(this) {
        val cfg = ctx.resources.configuration
        val dm = ctx.resources.displayMetrics
        val sig = listOf(layoutId, themeId, o.toJson(), dark, level, stamp, pinyinAbove, cfg.fontScale, cfg.orientation, dm.widthPixels, dm.heightPixels, dm.density).joinToString("|")
        lastStyle?.let { if (sig == lastSig) return it }
        val layout = runCatching { layout(layoutId) }.getOrElse { layout(DEFAULT_LAYOUT) }
        val tid = if (themeId == AUTO) layout.theme else themeId
        val theme = runCatching { theme(tid) }.getOrElse { theme(DEFAULT_THEME) }
        var palette = KeyboardStyle.palette(theme.palette(dark), o)
        val bgSpec = o.background ?: theme.background(dark)
        val bgDir = if (o.background != null) currentDir else themeDir(tid)
        backdrop(bgSpec, bgDir, dm.density)?.let { palette = palette.copy(backdrop = it) }
        val metrics = KbMetrics(ctx, level, KeyboardStyle.geometry(layout, o), pinyinAbove = pinyinAbove, candidateTextSize = layout.candidates.textSize)
        return KeyboardStyle(layout, theme, dark, o, palette, metrics).also { lastSig = sig; lastStyle = it }
    }

    private fun themeDir(themeId: String): File? = if (themeId.startsWith(PACK)) File(packsDir, themeId.removePrefix(PACK)) else null

    /** 渐变或图片背景；纯色返回 null（直接用背景色）。 Gradient or image backdrop; null for solid colours. */
    private fun backdrop(b: BackgroundSpec, dir: File?, density: Float): KbBackdrop? = when (b.type) {
        "gradient" -> KbBackdrop(b.colors, b.angle, dim = b.dim)
        "image" -> {
            val f = if (dir != null && b.image != null) File(dir, b.image) else null
            val bmp = f?.let { loadImage(it, b.blur * density) }
            if (bmp == null) null else KbBackdrop(b.colors, b.angle, bmp, b.dim)
        }
        else -> null
    }

    private fun loadImage(f: File, blurPx: Float): Bitmap? {
        val key = "${f.path}|${f.lastModified()}|$blurPx"
        return imageCache.getOrPut(key) {
            if (!f.isFile) return@getOrPut null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, bounds)
            if (bounds.outWidth <= 0) return@getOrPut null
            val target = app.resources.displayMetrics.widthPixels.coerceAtLeast(360)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= target) sample *= 2
            val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@getOrPut null
            if (blurPx >= 1f) Blur.apply(bmp, blurPx) else bmp
        }
    }

    /** 背景图或包变化后清缓存。 Drop caches after images or packs change. */
    fun invalidate() = synchronized(this) {
        layoutCache.keys.removeAll { it.startsWith(PACK) }
        themeCache.keys.removeAll { it.startsWith(PACK) }
        imageCache.clear()
        lastSig = null
        lastStyle = null
    }

    // ------------------------------------------------------------ packs

    fun packs(): List<StylePack> = packsDir.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { readPack(it) }.sortedBy { it.dir.lastModified() }

    fun pack(id: String): StylePack? = File(packsDir, id).takeIf { it.isDirectory }?.let { readPack(it) }

    private fun readPack(dir: File): StylePack? = runCatching {
        val o = JSONObject(File(dir, STYLE_JSON).readText())
        StylePack(dir.name, o.optString("name", dir.name), dir, o)
    }.getOrNull()

    /** 当前组合的风格包 JSON（另存 / 导出用）。 Pack JSON describing the current combination. */
    fun currentPackJson(prefs: SharedPreferences, name: String): JSONObject {
        val layoutId = WeavePrefs.styleLayout(prefs)
        val themeId = WeavePrefs.styleTheme(prefs)
        val layout = if (layoutId.startsWith(PACK)) pack(layoutId.removePrefix(PACK))?.layoutJson ?: JSONObject().put("extends", DEFAULT_LAYOUT)
        else JSONObject().put("extends", layoutId)
        val resolvedTheme = if (themeId == AUTO) layout(layoutId).theme else themeId
        val theme = if (resolvedTheme.startsWith(PACK)) pack(resolvedTheme.removePrefix(PACK))?.themeJson ?: JSONObject().put("extends", DEFAULT_THEME)
        else JSONObject().put("extends", resolvedTheme)
        return JSONObject()
            .put("version", StyleParser.VERSION)
            .put("kind", "pack")
            .put("name", name)
            .put("layout", layout)
            .put("theme", theme)
            .put("overrides", overrides(prefs).toJson())
    }

    /** 当前组合另存为「我的风格」。 Save the current combination as a pack. */
    fun saveCurrent(prefs: SharedPreferences, name: String): StylePack {
        val json = currentPackJson(prefs, name.trim().take(MAX_NAME).ifEmpty { "我的风格" })
        val dir = newPackDir()
        copyImages(prefs, dir)
        File(dir, STYLE_JSON).writeText(json.toString(2))
        invalidate()
        return readPack(dir)!!
    }

    /** 把当前背景图与引用主题的图片复制进包目录。 Copy referenced images into the pack. */
    private fun copyImages(prefs: SharedPreferences, dir: File) {
        overrides(prefs).background?.image?.let { img -> File(currentDir, img).takeIf { it.isFile }?.copyTo(File(dir, img), true) }
        val themeId = WeavePrefs.styleTheme(prefs)
        if (themeId.startsWith(PACK)) {
            val src = File(packsDir, themeId.removePrefix(PACK))
            src.listFiles().orEmpty().filter { StyleParser.SAFE_FILE.matches(it.name) }.forEach { it.copyTo(File(dir, it.name), true) }
        }
    }

    private fun newPackDir(): File {
        packsDir.mkdirs()
        var i = System.currentTimeMillis()
        while (File(packsDir, "p$i").exists()) i++
        return File(packsDir, "p$i").also { it.mkdirs() }
    }

    /** 应用风格包：写入当前布局 / 主题 / 微调，背景图复制到当前目录。 Apply a pack to the current settings. */
    fun apply(prefs: SharedPreferences, pack: StylePack) {
        val layout = if (pack.ownLayout) PACK + pack.id else pack.layoutJson.optString("extends", DEFAULT_LAYOUT).takeIf { it in rawLayouts } ?: DEFAULT_LAYOUT
        val theme = if (pack.ownTheme) PACK + pack.id else pack.themeJson.optString("extends", AUTO).takeIf { it in rawThemes } ?: AUTO
        val o = runCatching { StyleParser.overrides(pack.overridesJson) }.getOrDefault(StyleOverrides.NONE)
        o.background?.image?.let { img ->
            currentDir.mkdirs()
            File(pack.dir, img).takeIf { it.isFile }?.copyTo(File(currentDir, img), true)
        }
        invalidate()
        prefs.edit()
            .putString(WeavePrefs.STYLE_LAYOUT, layout)
            .putString(WeavePrefs.STYLE_THEME, theme)
            .putString(WeavePrefs.STYLE_OVERRIDES, o.toJson().toString())
            .putLong(WeavePrefs.STYLE_STAMP, System.currentTimeMillis())
            .apply()
    }

    /** 删除风格包；正在使用时退回其引用的内置项。 Delete a pack; fall back to its base presets if in use. */
    fun delete(prefs: SharedPreferences, pack: StylePack) {
        val e = prefs.edit()
        if (WeavePrefs.styleLayout(prefs) == PACK + pack.id) {
            e.putString(WeavePrefs.STYLE_LAYOUT, pack.layoutJson.optString("extends", DEFAULT_LAYOUT).takeIf { it in rawLayouts } ?: DEFAULT_LAYOUT)
        }
        if (WeavePrefs.styleTheme(prefs) == PACK + pack.id) {
            e.putString(WeavePrefs.STYLE_THEME, pack.themeJson.optString("extends", AUTO).takeIf { it in rawThemes } ?: AUTO)
        }
        e.putLong(WeavePrefs.STYLE_STAMP, System.currentTimeMillis()).apply()
        pack.dir.deleteRecursively()
        invalidate()
    }

    // ------------------------------------------------------------ export / import

    /** 导出为 .wvskin（zip：style.json + 图片）。 Export as .wvskin. */
    fun export(json: JSONObject, imagesDir: List<File>, out: OutputStream) {
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry(STYLE_JSON)); z.write(json.toString(2).toByteArray()); z.closeEntry()
            val names = HashSet<String>()
            for (dir in imagesDir) for (f in dir.listFiles().orEmpty()) {
                if (!StyleParser.SAFE_FILE.matches(f.name) || !names.add(f.name) || !referenced(json, f.name)) continue
                z.putNextEntry(ZipEntry(f.name)); f.inputStream().use { it.copyTo(z) }; z.closeEntry()
            }
        }
    }

    fun exportCurrent(prefs: SharedPreferences, name: String, out: OutputStream) {
        val json = currentPackJson(prefs, name)
        val themeId = WeavePrefs.styleTheme(prefs)
        export(json, listOfNotNull(currentDir, themeDir(themeId)), out)
    }

    fun exportPack(pack: StylePack, out: OutputStream) = export(pack.json, listOf(pack.dir), out)

    private fun referenced(json: JSONObject, name: String) = json.toString().contains("\"$name\"")

    /**
     * 读取并校验 .wvskin，解压到临时目录等待确认。大小超限、格式错误或引用缺失时抛 [StyleException]。
     * Read and validate a .wvskin into a staging directory. Throws [StyleException] on size, format or reference errors.
     */
    fun stage(input: InputStream): StagedImport {
        val dir = File(app.cacheDir, "style-import/${System.nanoTime()}").also { it.mkdirs() }
        try {
            var total = 0L
            var images = 0
            ZipInputStream(input.buffered()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = e.name
                    val isJson = name == STYLE_JSON
                    // 只接受根目录的 style.json 与图片，其余忽略。 Only root-level style.json and images; others ignored.
                    if (!isJson && !StyleParser.SAFE_FILE.matches(name)) continue
                    if (!isJson && ++images > MAX_IMAGES) throw StyleException("图片过多（最多 $MAX_IMAGES 张）")
                    val limit = if (isJson) MAX_JSON else MAX_IMAGE
                    val f = File(dir, name)
                    f.outputStream().use { o ->
                        val buf = ByteArray(16 * 1024)
                        var n = 0L
                        while (true) {
                            val r = z.read(buf)
                            if (r < 0) break
                            n += r; total += r
                            if (n > limit) throw StyleException("${if (isJson) "style.json" else name} 超过 ${limit / 1024} KB")
                            if (total > MAX_TOTAL) throw StyleException("风格包超过 ${MAX_TOTAL / 1024 / 1024} MB")
                            o.write(buf, 0, r)
                        }
                    }
                }
            }
            val jf = File(dir, STYLE_JSON)
            if (!jf.isFile) throw StyleException("缺少 style.json")
            val json = runCatching { JSONObject(jf.readText()) }.getOrElse { throw StyleException("style.json 不是合法的 JSON") }
            return validate(json, dir)
        } catch (e: Exception) {
            dir.deleteRecursively()
            if (e is StyleException) throw e
            throw StyleException("无法读取风格包：${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun validate(json: JSONObject, dir: File): StagedImport {
        StyleParser.checkVersion(json)
        val name = json.optString("name", "").trim()
        if (name.isEmpty() || name.length > MAX_NAME) throw StyleException("名称须为 1–$MAX_NAME 个字符")
        val lj = json.optJSONObject("layout") ?: JSONObject().put("extends", DEFAULT_LAYOUT)
        val tj = json.optJSONObject("theme") ?: JSONObject().put("extends", DEFAULT_THEME)
        val layout = StyleParser.layout(expand(lj, rawLayouts, "import"))
        val theme = StyleParser.theme(expand(tj, rawThemes, "import"))
        val o = StyleParser.overrides(json.optJSONObject("overrides"))
        val imgs = listOfNotNull(o.background?.image, theme.lightBackground.image, theme.darkBackground.image)
        for (img in imgs) {
            val f = File(dir, img)
            if (!f.isFile) throw StyleException("缺少图片 $img")
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, b)
            if (b.outWidth <= 0 || b.outHeight <= 0) throw StyleException("图片 $img 无法解码")
            if (b.outWidth > MAX_PIXELS || b.outHeight > MAX_PIXELS) throw StyleException("图片 $img 尺寸超过 $MAX_PIXELS 像素")
        }
        json.put("layout", lj).put("theme", tj)
        return StagedImport(dir, name, layout.name, theme.name, imgs.isNotEmpty(), json)
    }

    /** 待导入风格包的预览（不写入设置）。 Preview of a staged pack without touching settings. */
    fun preview(ctx: Context, s: StagedImport, dark: Boolean, level: Int): KeyboardStyle {
        val layout = StyleParser.layout(expand(s.json.optJSONObject("layout") ?: JSONObject(), rawLayouts, "import"))
        val theme = StyleParser.theme(expand(s.json.optJSONObject("theme") ?: JSONObject(), rawThemes, "import"))
        val o = StyleParser.overrides(s.json.optJSONObject("overrides"))
        var palette = KeyboardStyle.palette(theme.palette(dark), o)
        backdrop(o.background ?: theme.background(dark), s.dir, ctx.resources.displayMetrics.density)?.let { palette = palette.copy(backdrop = it) }
        return KeyboardStyle(layout, theme, dark, o, palette, KbMetrics(ctx, level, KeyboardStyle.geometry(layout, o)))
    }

    /** 设为背景图：校验可解码后复制到当前目录。 Set the tweak background image after checking it decodes. */
    fun setBackground(prefs: SharedPreferences, input: InputStream) {
        currentDir.mkdirs()
        val tmp = File(currentDir, "incoming.tmp")
        tmp.outputStream().use { o ->
            val buf = ByteArray(16 * 1024)
            var n = 0L
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                n += r
                if (n > MAX_IMAGE) { tmp.delete(); throw StyleException("图片超过 ${MAX_IMAGE / 1024 / 1024} MB") }
                o.write(buf, 0, r)
            }
        }
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(tmp.path, b)
        if (b.outWidth <= 0) { tmp.delete(); throw StyleException("无法识别的图片") }
        val ext = when (b.outMimeType) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
        currentDir.listFiles().orEmpty().filter { it.name.startsWith("bg.") }.forEach { it.delete() }
        val name = "bg.$ext"
        tmp.renameTo(File(currentDir, name))
        val o = overrides(prefs)
        val old = o.background
        val bg = BackgroundSpec("image", intArrayOf(resolve(app, prefs).palette.background), image = name, blur = old?.blur ?: 0f, dim = old?.dim ?: 0.2f)
        invalidate()
        prefs.edit().putString(WeavePrefs.STYLE_OVERRIDES, o.copy(background = bg).toJson().toString())
            .putLong(WeavePrefs.STYLE_STAMP, System.currentTimeMillis()).apply()
    }

    /** 确认导入：移入「我的风格」。 Confirm a staged import into the packs. */
    fun commit(s: StagedImport): StylePack {
        val dir = newPackDir()
        s.dir.listFiles().orEmpty().filter { StyleParser.SAFE_FILE.matches(it.name) }.forEach { it.copyTo(File(dir, it.name), true) }
        File(dir, STYLE_JSON).writeText(s.json.toString(2))
        s.dir.deleteRecursively()
        invalidate()
        return readPack(dir)!!
    }

    fun discard(s: StagedImport) { s.dir.deleteRecursively() }

    companion object {
        const val DEFAULT_LAYOUT = "fresh"
        const val DEFAULT_THEME = "fresh"
        /** 主题跟随布局默认。 Theme follows the layout's default. */
        const val AUTO = "auto"
        const val PACK = "pack:"
        const val STYLE_JSON = "style.json"
        const val EXTENSION = "wvskin"
        const val MAX_NAME = 24
        const val MAX_JSON = 256L * 1024
        const val MAX_IMAGE = 6L * 1024 * 1024
        const val MAX_TOTAL = 8L * 1024 * 1024
        const val MAX_IMAGES = 3
        const val MAX_PIXELS = 4096

        @Volatile private var instance: StyleRepository? = null

        fun get(ctx: Context): StyleRepository {
            val app = ctx.applicationContext
            instance?.let { if (it.app === app) return it }
            return synchronized(this) {
                instance?.takeIf { it.app === app } ?: StyleRepository(app).also { instance = it }
            }
        }
    }
}

/** 简单三次盒式模糊（缩小后处理，只在解析时运行）。 Three-pass box blur on a downscaled copy; resolve-time only. */
internal object Blur {
    fun apply(src: Bitmap, radiusPx: Float): Bitmap {
        val scale = 4
        val w = (src.width / scale).coerceAtLeast(1)
        val h = (src.height / scale).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val r = (radiusPx / scale).toInt().coerceIn(1, 32)
        val tmp = IntArray(px.size)
        repeat(3) { pass(px, tmp, w, h, r, true); pass(tmp, px, w, h, r, false) }
        val out = small.copy(Bitmap.Config.ARGB_8888, true)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return Bitmap.createScaledBitmap(out, src.width, src.height, true)
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val div = 2 * r + 1
        for (line in 0 until lines) {
            var sr = 0; var sg = 0; var sb = 0
            fun at(i: Int): Int { val c = i.coerceIn(0, len - 1); return if (horizontal) src[line * w + c] else src[c * w + line] }
            for (i in -r..r) { val c = at(i); sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF }
            for (i in 0 until len) {
                val idx = if (horizontal) line * w + i else i * w + line
                dst[idx] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val out = at(i - r); val inn = at(i + r + 1)
                sr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                sg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                sb += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }
}
