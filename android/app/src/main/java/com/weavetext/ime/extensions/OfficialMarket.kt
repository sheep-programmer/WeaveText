package com.weavetext.ime.extensions

import com.weavetext.ime.models.Mirror
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

data class MarketTransfer(val stage: String, val received: Long = 0, val total: Long = 0)

/** Only public data from the official repository; no GitHub credential or typed content is sent. */
class OfficialMarket(private val mirrors: () -> List<Mirror> = { emptyList() }) {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var connection: HttpURLConnection? = null
    fun cancel() { cancelled.set(true); connection?.disconnect() }
    fun begin() { cancelled.set(false) }

    fun read(file: String, expectedBytes: Long = 0, sha256: String? = null, progress: (MarketTransfer) -> Unit = {}): ByteArray {
        require(file == "catalog.json" || file.matches(Regex("(themes/theme|layouts/layout)-[a-z][a-z0-9_-]{0,63}\\.json")))
        val raw = "$RAW/$file"
        val sources = (listOf(raw, "$API/$file?ref=main") + mirrors().filter { it.id != "direct" }.map { it.apply(raw) }).distinct()
        var failure: Exception? = null
        for (source in sources) {
            if (cancelled.get()) throw IOException("下载已取消")
            try {
                progress(MarketTransfer("正在连接官方仓库", total = expectedBytes))
                val c = URL(source).openConnection() as HttpURLConnection
                connection = c
                c.connectTimeout = 3000; c.readTimeout = 5000
                c.setRequestProperty("User-Agent", "WeaveText-Market")
                c.setRequestProperty("Accept", "application/vnd.github.raw+json")
                try {
                    if (cancelled.get()) throw IOException("下载已取消")
                    if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
                    if (c.contentLengthLong > MAX_ENVELOPE) throw IOException("目录或扩展文件过大")
                    val out = ByteArrayOutputStream()
                    c.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            if (cancelled.get()) throw IOException("下载已取消")
                            val n = input.read(buffer); if (n < 0) break
                            if (out.size() + n > MAX_ENVELOPE) throw IOException("目录或扩展文件过大")
                            out.write(buffer, 0, n)
                            progress(MarketTransfer("正在下载", out.size().toLong(), c.contentLengthLong.coerceAtLeast(0)))
                        }
                    }
                    val payload = unwrap(out.toByteArray())
                    if (expectedBytes > 0 && payload.size.toLong() != expectedBytes) throw IOException("扩展大小不匹配")
                    if (sha256 != null && digest(payload) != sha256) throw IOException("扩展校验失败")
                    if (cancelled.get()) throw IOException("下载已取消")
                    return payload
                } finally { c.disconnect(); connection = null }
            } catch (e: Exception) { if (cancelled.get()) throw IOException("下载已取消"); failure = e }
        }
        throw IOException("官方仓库暂时无法访问，请重试", failure)
    }
    companion object {
        const val REPOSITORY = "https://github.com/sheep-programmer/weavetext-market"
        const val RAW = "https://raw.githubusercontent.com/sheep-programmer/weavetext-market/main"
        const val API = "https://api.github.com/repos/sheep-programmer/weavetext-market/contents"
        private const val MAX_ENVELOPE = 768 * 1024
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun unwrap(bytes: ByteArray): ByteArray {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            return if (json.optString("encoding") == "base64" && json.has("content"))
                java.util.Base64.getMimeDecoder().decode(json.getString("content")) else bytes
        }
        fun parse(bytes: ByteArray): List<ExtensionItem> {
            require(bytes.size <= 512 * 1024) { "市场目录过大" }
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            require(json.getInt("version") == 1) { "市场目录版本不受支持" }
            val a = json.getJSONArray("items"); require(a.length() <= 256)
            val protected = setOf("theme:fresh", "theme:ink", "theme:mint", "theme:dusk", "theme:dynamic", "layout:fresh", "layout:classic")
            val items = (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("source") == "remote" }.map { item ->
                val id = item.getString("id"); val kind = item.getString("kind")
                require(kind in setOf("theme", "layout") && id.matches(Regex("[a-z][a-z0-9_-]{0,63}")) && "$kind:$id" !in protected)
                val file = item.getString("file")
                require(file == "${kind}s/$kind-$id.json" && item.getString("url") == "$RAW/$file")
                val sha = item.getString("sha256"); val size = item.getLong("bytes")
                require(sha.matches(Regex("[a-f0-9]{64}")) && size in 1..256 * 1024L)
                val platforms = item.getJSONArray("platforms")
                val supported = (0 until platforms.length()).any { platforms.getString(it) == "android" }
                if (!supported) null else ExtensionItem(id, kind, item.getString("name").take(80), item.optString("summary").take(512), "remote", file, sha, size)
            }.filterNotNull()
            require(items.map { it.key }.distinct().size == items.size) { "市场目录含重复项目" }
            return items
        }
    }
}
