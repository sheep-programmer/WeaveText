package com.weavetext.ime.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.weavetext.ime.models.ModelManager
import com.weavetext.ime.settings.WeavePrefs
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 云端热词的状态。 Cloud hot-words status. */
data class CloudStatus(
    val enabled: Boolean,
    val words: Int = 0,
    val version: String = "",
    /** 上次成功检查的时间（毫秒），0 = 从未。 Last successful check, ms; 0 = never. */
    val checkedAt: Long = 0,
    val updating: Boolean = false,
    val error: String? = null,
    val attached: Boolean = true,
)

/** 界面看到的云端热词操作（截图测试用假实现）。 Cloud hot-word operations seen by the UI; faked in tests. */
interface CloudWordsRepository {
    fun status(): CloudStatus
    fun setEnabled(on: Boolean)
    fun refreshNow()
    fun addListener(l: () -> Unit)
    fun removeListener(l: () -> Unit)
}

/**
 * 云端热词（默认关闭）：每天最多从织文热词仓库下载一次 `hotwords.tsv` 与签名（带 ETag，没变化就不下载），
 * 内核用内置公钥验签、去掉过期词后作为扩展词库挂上。只下载，不上传任何输入内容。
 * Cloud hot words (off by default): at most once a day, fetch `hotwords.tsv` and its signature from the hot-words
 * repository (with ETag, nothing downloaded when unchanged); the engine verifies it with the pinned key, drops
 * expired words and attaches it as an extra lexicon. Download only; nothing typed is ever uploaded.
 */
class CloudWords private constructor(private val ctx: Context) : CloudWordsRepository {
    private val prefs: SharedPreferences = WeavePrefs.of(ctx)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "weave-cloudwords").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<() -> Unit>()
    private val busy = AtomicBoolean(false)
    @Volatile private var error: String? = null
    @Volatile private var attached = false
    @Volatile private var attaching = false
    private val generation=java.util.concurrent.atomic.AtomicInteger()
    private val commitLock=Any()
    private class Cancelled: Exception()
    private fun current(token:Int)=generation.get()==token && WeavePrefs.cloudWords(prefs)

    private val dir get() = File(ctx.filesDir, "dict/cloud")
    private val tsv get() = File(dir, "hotwords.tsv")
    private val sig get() = File(dir, "hotwords.tsv.sig")

    override fun status() = CloudStatus(
        enabled = WeavePrefs.cloudWords(prefs),
        words = prefs.getInt(KEY_COUNT, 0),
        version = prefs.getString(KEY_VERSION, "").orEmpty(),
        checkedAt = prefs.getLong(KEY_CHECKED, 0),
        updating = busy.get() || attaching,
        error = error,
        attached = attached,
    )

    override fun addListener(l: () -> Unit) { synchronized(listeners) { listeners += l } }
    override fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners -= l } }
    private fun changed() = main.post { synchronized(listeners) { listeners.toList() }.forEach { it() } }

    override fun setEnabled(on: Boolean) {
        synchronized(commitLock) {
            generation.incrementAndGet()
            prefs.edit().putBoolean(WeavePrefs.CLOUD_WORDS, on).apply()
            if(!on) {
                EngineHolder.peek()?.unloadPack(PACK_ID);attached=false
                dir.deleteRecursively()
                prefs.edit().remove(KEY_COUNT).remove(KEY_VERSION).remove(KEY_CHECKED).remove(KEY_ETAG).apply()
                error=null
            }
        }
        if(on)refreshNow()
        changed()
    }

    /** 内核刚创建时挂上已下载的热词。 Attach the downloaded hot words to a freshly created engine. */
    fun attach(e: NativeEngine) {
        if (!WeavePrefs.cloudWords(prefs) || !tsv.isFile || !sig.isFile) return
        val token=generation.get()
        attaching=true;changed()
        val n = try {e.loadHotwords(tsv.absolutePath, sig.absolutePath)} finally {attaching=false}
        synchronized(commitLock) {
            if(!current(token)){e.unloadPack(PACK_ID);attached=false}
            else {attached=n>=0;if(n<0)error="热词没能载入，请重试"}
        }
        changed()
    }

    /** 开启且超过一天没检查时更新（键盘出现时调用，很便宜）。 Update when on and stale; cheap to call on keyboard show. */
    fun refreshIfStale() {
        if (!WeavePrefs.cloudWords(prefs)) return
        if (System.currentTimeMillis() - prefs.getLong(KEY_CHECKED, 0) < DAY_MS) return
        refreshNow()
    }

    override fun refreshNow() {
        if (!WeavePrefs.cloudWords(prefs) || !busy.compareAndSet(false, true)) return
        val token=generation.get()
        changed()
        io.execute {
            try {
                fetch(token)
                if(current(token))error = null
            } catch (_:Cancelled) {
            } catch (t: Throwable) {
                Log.w(TAG, "hot words update failed", t)
                if(current(token))error = "更新失败，稍后会自动重试"
            } finally {
                busy.set(false)
                changed()
                if(generation.get()!=token && WeavePrefs.cloudWords(prefs)) main.post {refreshNow()}
            }
        }
    }

    private fun fetch(token:Int) {
        val sources = listOf<(String) -> String>({ it }) + ModelManager.get(ctx).mirrors().map { m -> { u: String -> m.apply(u) } }
        var last: Exception? = null
        for (src in sources) {
            if(!current(token))throw Cancelled()
            try {
                val etag = prefs.getString(KEY_ETAG, null).takeIf { tsv.isFile && sig.isFile && attached }
                val (code, body, newTag) = get(src(TSV_URL), etag)
                if (code == 304) {
                    synchronized(commitLock) {if(!current(token))throw Cancelled();prefs.edit().putLong(KEY_CHECKED, System.currentTimeMillis()).apply()}
                    return
                }
                val (_, sigBody, _) = get(src(TSV_URL + ".sig"), null)
                install(body, sigBody, newTag,token)
                return
            } catch (e: Exception) {
                if(!current(token) || e is Cancelled)throw Cancelled()
                last = e
            }
        }
        throw last ?: IllegalStateException("no source")
    }

    /** 先写临时文件交给内核验签，通过才替换旧文件。 Verify via the engine from temp files; replace only on success. */
    private fun install(body: ByteArray, sigBody: ByteArray, etag: String?,token:Int) {
        if(!current(token))throw Cancelled()
        dir.mkdirs()
        val t = File(dir, "hotwords.tsv.new")
        val s = File(dir, "hotwords.tsv.sig.new")
        t.writeBytes(body)
        s.writeBytes(sigBody)
        val engine = EngineHolder.getBlocking(ctx) ?: error("engine unavailable")
        val n = engine.loadHotwords(t.absolutePath, s.absolutePath)
        if (n < 0) {
            t.delete(); s.delete()
            // Failed verification never replaces the active dictionary.
            error("bad signature")
        }
        synchronized(commitLock) {
        if(!current(token)){engine.unloadPack(PACK_ID);attached=false;t.delete();s.delete();throw Cancelled()}
        check(t.renameTo(tsv) && s.renameTo(sig)) {"cannot save signed words"}
        attached=true
        val version = body.toString(Charsets.UTF_8).lineSequence().take(4).firstOrNull { it.startsWith("#! version") }?.removePrefix("#! version")?.trim().orEmpty()
        prefs.edit().putInt(KEY_COUNT, n).putString(KEY_VERSION, version).putLong(KEY_CHECKED, System.currentTimeMillis())
            .putString(KEY_ETAG, etag).apply()
        }
    }

    private fun get(url: String, etag: String?): Triple<Int, ByteArray, String?> {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 15_000
        if (etag != null) c.setRequestProperty("If-None-Match", etag)
        try {
            val code = c.responseCode
            if (code == 304) return Triple(304, ByteArray(0), etag)
            if (code != 200) error("HTTP $code")
            val bytes = c.inputStream.use { i ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val r = i.read(buf)
                    if (r < 0) break
                    out.write(buf, 0, r)
                    if (out.size() > MAX_BYTES) error("too large")
                }
                out.toByteArray()
            }
            return Triple(200, bytes, c.getHeaderField("ETag"))
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val TAG = "WeaveCloudWords"
        const val PACK_ID = "cloud"
        /** 热词仓库发布分支上的文件。 The file on the hot-words repository's publishing branch. */
        const val TSV_URL = "https://raw.githubusercontent.com/sheep-programmer/weavetext-hotwords/dist/hotwords.tsv"
        private const val DAY_MS = 24 * 3600 * 1000L
        private const val MAX_BYTES = 4 * 1024 * 1024
        private const val KEY_COUNT = "cloud_words_count"
        private const val KEY_VERSION = "cloud_words_version"
        private const val KEY_CHECKED = "cloud_words_checked"
        private const val KEY_ETAG = "cloud_words_etag"

        @Volatile private var instance: CloudWords? = null
        fun get(ctx: Context): CloudWords = instance ?: synchronized(this) { instance ?: CloudWords(ctx.applicationContext).also { instance = it } }
    }
}
