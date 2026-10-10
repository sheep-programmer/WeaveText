package com.weavetext.ime.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.weavetext.ime.BuildConfig
import com.weavetext.ime.models.DownloadPhase
import com.weavetext.ime.models.Downloader
import com.weavetext.ime.models.Mirror
import com.weavetext.ime.models.ModelManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** GitHub Release 更新：元数据和安装包共用模型下载器与已配置的镜像。 */
object AppUpgrade {
    private const val TAG = "WeaveAppUpgrade"
    private const val REPO = "sheep-programmer/WeaveText"
    private const val API = "https://api.github.com/repos/$REPO/releases?per_page=20"
    private val APP_TAG = Regex("v\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.]+)?")
    private val SHA256 = Regex("[0-9a-fA-F]{64}")

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val current: String) : State
        // Bind the digest to this exact release/asset, rather than retaining a previous check's digest.
        data class Available(val tag: String, val asset: String, val size: Long, val notes: String, val sha256: String? = null) : State
        data class Connecting(val done: Long, val total: Long, val mirror: String = "", val label: String = "正在连接下载源…") : State
        data class Downloading(val done: Long, val total: Long, val speed: Long, val mirror: String) : State
        data object Verifying : State
        data object Cancelling : State
        data class Cancelled(val available: Available? = null) : State
        data class Ready(val file: File, val tag: String) : State
        data class Failed(val message: String, val available: Available? = null) : State
    }

    @Volatile var state: State = State.Idle
        private set
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    // Each operation owns its token. A retry cannot clear the old worker's cancellation.
    private var operation: AtomicBoolean? = null

    fun addListener(listener: (State) -> Unit) { listeners += listener }
    fun removeListener(listener: (State) -> Unit) { listeners -= listener }

    private fun set(next: State) {
        state = next
        main.post { listeners.forEach { it(next) } }
    }

    @Synchronized private fun begin(initial: State): AtomicBoolean? {
        if (operation != null) return null
        return AtomicBoolean(false).also { operation = it; set(initial) }
    }

    @Synchronized private fun publish(token: AtomicBoolean, next: State) {
        if (operation === token && !token.get()) set(next)
    }

    @Synchronized private fun finish(token: AtomicBoolean, next: State, available: State.Available? = null) {
        if (operation !== token) return
        operation = null
        set(if (token.get()) State.Cancelled(available) else next)
    }

    fun check(ctx: Context) {
        val token = begin(State.Checking) ?: return
        val app = ctx.applicationContext
        Thread({
            val result = try {
                val release = latest(ModelManager.get(app).mirrors(), token)
                    ?: throw IOException("暂时无法读取 GitHub Release")
                val tag = release.optString("tag_name").removePrefix("v")
                if (compareVersions(tag, BuildConfig.VERSION_NAME) <= 0) State.UpToDate(BuildConfig.VERSION_NAME)
                else availableFrom(release)
            } catch (e: Exception) {
                if (!token.get()) Log.i(TAG, "update check failed: ${e.message}")
                State.Failed("检查更新失败，请检查网络后重试")
            }
            finish(token, result)
        }, "weave-update-check").apply { isDaemon = true }.start()
    }

    fun download(ctx: Context, available: State.Available) {
        val app = ctx.applicationContext
        val dest = File(File(app.cacheDir, "updates"), available.asset)
        val token = begin(State.Connecting(File(dest.path + ".part").length(), available.size, label = "正在读取下载信息…")) ?: return
        Thread({
            val result = try {
                val manager = ModelManager.get(app)
                val mirrors = manager.mirrors()
                val dl = Downloader(mirrors)
                val sha = available.sha256 ?: sums(mirrors, available.tag, available.asset, token)
                    ?: throw IOException("没有可用的校验值，请稍后重试")
                Downloader.checkCancelled(token)
                val url = "https://github.com/$REPO/releases/download/${available.tag}/${available.asset}"
                dl.download(url, sha, dest, token, preferred = manager.mirrorPreference.takeIf { it != "auto" }, expectedSize = available.size) { p ->
                    val next = when (p.phase) {
                        DownloadPhase.CONNECTING -> State.Connecting(p.downloaded, p.total, p.mirror)
                        DownloadPhase.DOWNLOADING -> State.Downloading(p.downloaded, p.total, p.bytesPerSecond, p.mirror)
                        DownloadPhase.VERIFYING -> State.Verifying
                    }
                    publish(token, next)
                }
                Downloader.checkCancelled(token)
                publish(token, State.Verifying)
                requireMatchingSigner(dest) { VoiceUpgrade.verifySigner(app, it) }
                Downloader.checkCancelled(token)
                State.Ready(dest, available.tag)
            } catch (e: Exception) {
                if (!token.get()) Log.w(TAG, "update download failed", e)
                State.Failed(friendly(e), available)
            }
            finish(token, result, available)
        }, "weave-update-download").apply { isDaemon = true }.start()
    }

    @Synchronized fun cancel() {
        operation?.let { it.set(true); set(State.Cancelling) }
    }

    fun retry(ctx: Context) {
        val available = when (val s = state) {
            is State.Failed -> s.available
            is State.Cancelled -> s.available
            else -> null
        }
        if (available != null) download(ctx, available) else check(ctx)
    }

    fun install(ctx: Context, ready: State.Ready) = VoiceUpgrade.install(ctx, ready.file)

    internal fun requireMatchingSigner(file: File, verify: (File) -> Boolean) {
        if (!verify(file)) {
            file.delete()
            throw IOException("安装包签名不一致，已丢弃，请重试")
        }
    }

    private fun friendly(e: Exception): String = when {
        e.message.orEmpty().contains("sha256", true) -> "安装包校验失败，请重试或更换下载源"
        e.message.orEmpty().startsWith("安装包") || e.message.orEmpty().startsWith("没有可用") -> e.message!!
        else -> "下载失败，请检查网络后重试（可在「语音包」中更换下载源）"
    }

    /** A mirror may reject API URLs or return HTML; continue to other configured sources. */
    internal fun latest(
        mirrors: List<Mirror>,
        cancel: AtomicBoolean,
        read: (String) -> String = { Downloader(emptyList()).readText(it, cancel) },
    ): JSONObject? {
        for (url in (listOf(API) + mirrors.map { it.apply(API) }).distinct()) {
            Downloader.checkCancelled(cancel)
            try {
                val releases = JSONArray(read(url))
                val release = (0 until releases.length()).map { releases.getJSONObject(it) }
                    .filter { !it.optBoolean("draft") && APP_TAG.matches(it.optString("tag_name")) }
                    .maxWithOrNull { x, y -> compareVersions(x.optString("tag_name"), y.optString("tag_name")) }
                if (release != null) return release
            } catch (e: Exception) {
                Downloader.checkCancelled(cancel)
                Log.i(TAG, "release source unavailable: ${e.message}")
            }
        }
        return null
    }

    internal fun availableFrom(release: JSONObject): State.Available {
        val tag = release.getString("tag_name")
        if (!APP_TAG.matches(tag)) throw IOException("无效的应用版本")
        val assetName = "WeaveText-${tag.removePrefix("v")}-arm64.apk"
        val assets = release.optJSONArray("assets") ?: JSONArray()
        val asset = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name") == assetName }
            ?: throw IOException("Release 中没有找到适合当前版本的安装包")
        val digest = asset.optString("digest").takeIf { it.startsWith("sha256:") }
            ?.removePrefix("sha256:")?.takeIf { SHA256.matches(it) }?.lowercase()
        val notes = release.optString("body").lineSequence().take(3).joinToString(" ").take(180)
        return State.Available(tag, assetName, asset.optLong("size").coerceAtLeast(0), notes, digest)
    }

    internal fun checksum(text: String, asset: String): String? = text.lineSequence()
        .map { it.trim().split(Regex("\\s+"), limit = 2) }
        .firstOrNull { it.size == 2 && it[1].removePrefix("*") == asset && SHA256.matches(it[0]) }
        ?.first()?.lowercase()

    private fun sums(mirrors: List<Mirror>, tag: String, asset: String, cancel: AtomicBoolean): String? {
        val raw = "https://github.com/$REPO/releases/download/$tag/SHA256SUMS.txt"
        val dl = Downloader(emptyList())
        for (url in (listOf(raw) + mirrors.map { it.apply(raw) }).distinct()) {
            Downloader.checkCancelled(cancel)
            try {
                checksum(dl.readText(url, cancel, maxBytes = 256 * 1024, accept = "text/plain"), asset)?.let { return it }
            } catch (e: Exception) {
                Downloader.checkCancelled(cancel)
                Log.i(TAG, "checksum source unavailable: ${e.message}")
            }
        }
        return null
    }

    /**
     * 按语义化版本比较：先比主版本号，相同时正式版高于预发布版，预发布段逐段比（数字按数值）。
     * Semantic-version order: core numbers first; on a tie a release outranks a prerelease and prerelease
     * identifiers compare piece by piece (numeric ones by value), so beta.18 < beta.19 < 0.1.0.
     */
    internal fun compareVersions(a:String,b:String):Int {
        fun split(v:String)=v.trim().removePrefix("v").substringBefore('+').let {it.substringBefore('-') to it.substringAfter('-',"")}
        val (coreA,preA)=split(a);val (coreB,preB)=split(b)
        val x=coreA.split('.').map {it.toIntOrNull() ?: 0};val y=coreB.split('.').map {it.toIntOrNull() ?: 0}
        for(i in 0 until maxOf(x.size,y.size)){val d=(x.getOrNull(i)?:0).compareTo(y.getOrNull(i)?:0);if(d!=0)return d}
        if(preA.isEmpty()||preB.isEmpty())return (if(preA.isEmpty())1 else 0)-(if(preB.isEmpty())1 else 0)
        val p=preA.split('.');val q=preB.split('.')
        for(i in 0 until minOf(p.size,q.size)){
            val m=p[i].toIntOrNull();val n=q[i].toIntOrNull()
            val d=when{m!=null&&n!=null->m.compareTo(n);m!=null->-1;n!=null->1;else->p[i].compareTo(q[i])}
            if(d!=0)return d
        }
        return p.size.compareTo(q.size)
    }
}
