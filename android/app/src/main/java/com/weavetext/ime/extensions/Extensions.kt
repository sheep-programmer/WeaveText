package com.weavetext.ime.extensions

import android.content.Context
import android.content.SharedPreferences
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.style.StyleParser
import org.json.JSONObject
import java.io.File

data class ExtensionItem(val id: String, val kind: String, val name: String, val summary: String, val source: String, val file: String, val sha256: String = "", val bytes: Long = 0) {
    val key get() = "$kind:$id"
    val builtin get() = source == "builtin"
    val base get() = source == "base"
}

/** Single gate for optional functionality. Missing state preserves existing users' features. */
object Extensions {
    const val ENABLED = "ext_enabled"
    const val STAMP = "ext_stamp"
    val defaults = setOf("feature:voice", "feature:translate", "feature:stickers", "feature:phrases", "feature:link", "feature:cloudwords", "feature:calc", "scheme:hand", "scheme:wubi86")
    fun enabled(p: SharedPreferences, key: String) = key in (p.getStringSet(ENABLED, defaults) ?: defaults)
    fun feature(p: SharedPreferences, id: String) = enabled(p, "feature:$id")
    fun scheme(p: SharedPreferences, id: String) = id !in setOf("hand", "wubi86") || enabled(p, "scheme:$id")
    fun tool(p: SharedPreferences, id: String) = when (id) {
        "voice", "stickers", "phrases", "link" -> feature(p, id)
        "translation" -> feature(p, "translate")
        else -> true
    }
    fun setEnabled(p: SharedPreferences, key: String, on: Boolean) {
        require(key in defaults)
        val next = (p.getStringSet(ENABLED, defaults) ?: defaults).toMutableSet()
        if (on) next.add(key) else next.remove(key)
        p.edit().putStringSet(ENABLED, next).apply()
    }
}

/** Data extensions are installed in app-private storage, separate from built-in presets. */
class ExtensionStore(private val ctx: Context, private val prefs: SharedPreferences = WeavePrefs.of(ctx)) {
    val root = File(ctx.filesDir, "extensions")
    private val market = OfficialMarket { com.weavetext.ime.models.ModelManager.get(ctx).mirrors() }
    @Volatile private var remoteItems = runCatching { OfficialMarket.parse(File(root, "market-index.json").readBytes()) }.getOrDefault(emptyList())
    fun cancelTransfer() = market.cancel()
    fun refreshMarket(progress: (MarketTransfer) -> Unit = {}) {
        market.begin()
        val payload = market.read("catalog.json", progress = progress)
        val parsed = OfficialMarket.parse(payload)
        root.mkdirs()
        val temp = File(root, "market-index.json.tmp")
        try { temp.writeBytes(payload); check(temp.renameTo(File(root, "market-index.json"))) } finally { temp.delete() }
        remoteItems = parsed
        changed()
    }
    fun updateAvailable(item: ExtensionItem): Boolean = item.source == "remote" && destination(item.kind, item.id).let { it.isFile && OfficialMarket.digest(it.readBytes()) != item.sha256 }
    fun themePreview(item: ExtensionItem): JSONObject? = runCatching {
        val installed = destination(item.kind, item.id)
        val payload = if (installed.isFile) installed.readText() else {
            val path = if (item.base) "styles/theme-${item.id}.json" else "market/${item.file}"
            ctx.assets.open(path).bufferedReader().use { it.readText() }
        }
        JSONObject(payload)
    }.getOrNull()

    fun setEnabled(key: String, on: Boolean) {
        Extensions.setEnabled(prefs, key, on)
        if (key == "feature:stickers" && !on) ctx.stopService(android.content.Intent(ctx, com.weavetext.ime.stickers.StickerOverlayService::class.java))
    }
    private val catalogItems: List<ExtensionItem> by lazy {
        val json = JSONObject(ctx.assets.open("market/catalog.json").bufferedReader().use { it.readText() })
        require(json.getInt("version") == 1)
        val a = json.getJSONArray("items")
        (0 until a.length()).map { a.getJSONObject(it) }.filter { o ->
            val platforms = o.getJSONArray("platforms")
            (0 until platforms.length()).any { platforms.getString(it) == "android" }
        }.map { ExtensionItem(it.getString("id"), it.getString("kind"), it.getString("name"), it.optString("summary"), it.getString("source"), it.optString("file")) }
    }
    private val availableItems get() = catalogItems.map { item -> remoteItems.firstOrNull { it.key == item.key } ?: item } + remoteItems.filter { remote -> catalogItems.none { it.key == remote.key } }
    val items: List<ExtensionItem> get() = availableItems + listOf("theme", "layout").flatMap { kind ->
        File(root, kind).listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull { file ->
            runCatching {
                val json = JSONObject(file.readText())
                val id = json.getString("id")
                if (availableItems.any { it.key == "$kind:$id" }) null else {
                    require(file.nameWithoutExtension == id && json.getString("kind") == kind)
                    ExtensionItem(id, kind, json.optString("name", id).take(80), json.optString("description").take(512), "local", "")
                }
            }.getOrNull()
        }
    }
    fun installed(item: ExtensionItem) = item.base || item.builtin || destination(item.kind, item.id).isFile
    private fun destination(kind: String, id: String): File {
        require(kind in setOf("theme", "layout") && id.matches(Regex("[a-z][a-z0-9_-]{0,63}")))
        return File(root, "$kind/$id.json")
    }
    fun install(item: ExtensionItem) = installWithProgress(item)
    fun installWithProgress(item: ExtensionItem, progress: (MarketTransfer) -> Unit = {}) {
        market.begin()
        installAsset(item, progress)
    }
    private fun installAsset(item: ExtensionItem, progress: (MarketTransfer) -> Unit) {
        require(item.source in setOf("bundled", "remote"))
        require(item.file == "${item.kind}s/${item.kind}-${item.id}.json")
        val bytes = if (item.source == "remote") market.read(item.file, item.bytes, item.sha256, progress)
            else ctx.assets.open("market/${item.file}").use { it.readBytes() }
        require(bytes.size <= 256 * 1024)
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.getInt("version") == 1 && json.getString("kind") == item.kind && json.getString("id") == item.id)
        if (item.kind == "theme") StyleParser.theme(json) else {
            StyleParser.layout(json)
            val theme = items.firstOrNull { it.kind == "theme" && it.id == json.optString("theme") }
            if (theme != null && (!installed(theme) || updateAvailable(theme))) installAsset(theme, progress)
        }
        val target = destination(item.kind, item.id)
        target.parentFile!!.mkdirs()
        val temp = File(target.parentFile, "${target.name}.tmp")
        try { temp.writeBytes(bytes); check(temp.renameTo(target)) { "扩展保存失败" } } finally { temp.delete() }
        changed()
    }
    fun importData(input: java.io.InputStream): ExtensionItem {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            require(output.size() + n <= 256 * 1024) { "扩展文件不能超过 256 KB" }
            output.write(buffer, 0, n)
        }
        return importData(output.toByteArray())
    }
    fun importData(bytes: ByteArray): ExtensionItem {
        require(bytes.size <= 256 * 1024) { "扩展文件不能超过 256 KB" }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        val kind = json.getString("kind")
        val id = json.getString("id")
        require(json.getInt("version") == 1)
        val target = destination(kind, id)
        require(catalogItems.none { it.base && it.kind == kind && it.id == id }) { "不能替换基础内置样式" }
        fun expand(raw: JSONObject, depth: Int = 0): JSONObject {
            require(depth <= 4) { "extends 层级过深" }
            val parent = raw.optString("extends")
            val merged = if (parent.isEmpty()) JSONObject(raw.toString()) else {
                require(parent.matches(Regex("[a-z][a-z0-9_-]{0,63}")))
                val file = destination(kind, parent)
                val base = if (file.isFile) JSONObject(file.readText()) else JSONObject(ctx.assets.open("styles/$kind-$parent.json").bufferedReader().use { it.readText() })
                StyleParser.merge(expand(base, depth + 1), raw)
            }
            merged.remove("extends")
            return merged
        }
        val expanded = expand(json)
        if (kind == "theme") StyleParser.theme(expanded) else {
            StyleParser.layout(expanded)
            require(items.any { it.kind == "theme" && it.id == expanded.optString("theme", "fresh") && installed(it) }) { "请先安装布局使用的主题" }
        }
        target.parentFile!!.mkdirs()
        val temp = File(target.parentFile, target.name + ".tmp")
        try { temp.writeText(expanded.toString()); check(temp.renameTo(target)) } finally { temp.delete() }
        changed()
        return items.first { it.kind == kind && it.id == id }
    }
    fun uninstall(item: ExtensionItem) {
        require(!item.base && !item.builtin)
        // Saved skins may still reference this theme; prevent silently breaking them.
        val packNeeds = File(ctx.filesDir, "styles/packs").listFiles().orEmpty().any {
            runCatching { JSONObject(File(it, "style.json").readText()).optJSONObject(item.kind)?.optString("extends") == item.id }.getOrDefault(false)
        }
        require(!packNeeds) { "已保存的风格仍使用此扩展，请先调整或删除该风格" }
        if (item.kind == "theme") {
            val needed = File(root, "layout").listFiles().orEmpty().any { runCatching { JSONObject(it.readText()).optString("theme") == item.id }.getOrDefault(false) }
            require(!needed) { "请先卸载使用此主题的布局" }

        }
        val target = destination(item.kind, item.id)
        check(!target.exists() || target.delete()) { "扩展删除失败" }
        prefs.edit().apply {
            if (item.kind == "theme" && WeavePrefs.styleTheme(prefs) == item.id) putString(WeavePrefs.STYLE_THEME, "fresh")
            if (item.kind == "layout" && WeavePrefs.styleLayout(prefs) == item.id) putString(WeavePrefs.STYLE_LAYOUT, "fresh")
        }.apply()
        changed()
    }
    fun migrateSelected() {
        if (!prefs.getBoolean("ext_migrated", false) && prefs.contains(WeavePrefs.STYLE_LAYOUT) && WeavePrefs.styleLayout(prefs) == "classic" && WeavePrefs.styleTheme(prefs) == "auto") {
            items.firstOrNull { it.key == "theme:amber" }?.let { if (!installed(it)) install(it) }
            prefs.edit().putString(WeavePrefs.STYLE_THEME, "amber").apply()
        }
        val refs = mutableSetOf("layout:${WeavePrefs.styleLayout(prefs)}", "theme:${WeavePrefs.styleTheme(prefs)}")
        File(ctx.filesDir, "styles/packs").listFiles().orEmpty().forEach { dir ->
            runCatching {
                val json = JSONObject(File(dir, "style.json").readText())
                for (kind in listOf("layout", "theme")) refs.add("$kind:${json.optJSONObject(kind)?.optString("extends")}")
            }
        }
        catalogItems.filter { it.source == "bundled" && it.key in refs && !installed(it) }.forEach(::install)
        prefs.edit().putBoolean("ext_migrated", true).apply()
    }
    private fun changed() { prefs.edit().putLong(Extensions.STAMP, prefs.getLong(Extensions.STAMP, 0) + 1).apply() }
}
