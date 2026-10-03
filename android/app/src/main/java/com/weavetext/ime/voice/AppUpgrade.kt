package com.weavetext.ime.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.weavetext.ime.BuildConfig
import com.weavetext.ime.models.Downloader
import com.weavetext.ime.models.Mirror
import com.weavetext.ime.models.ModelManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** GitHub Release 更新：元数据和安装包都走 ModelManager 的镜像列表。 */
object AppUpgrade {
    private const val TAG = "WeaveAppUpgrade"
    private const val REPO = "sheep-programmer/WeaveText"
    private const val API = "https://api.github.com/repos/$REPO/releases?per_page=20"

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val current: String) : State
        data class Available(val tag: String, val asset: String, val size: Long, val notes: String) : State
        data class Downloading(val done: Long, val total: Long, val speed: Long, val mirror: String) : State
        data object Verifying : State
        data class Ready(val file: File, val tag: String) : State
        data class Failed(val message: String) : State
    }

    @Volatile var state: State = State.Idle
        private set
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val cancel = AtomicBoolean(false)

    fun addListener(listener: (State) -> Unit) { listeners += listener }
    fun removeListener(listener: (State) -> Unit) { listeners -= listener }

    private fun set(next: State) {
        state = next
        main.post { listeners.forEach { it(next) } }
    }

    fun check(ctx: Context) {
        if (state is State.Checking || state is State.Downloading || state is State.Verifying) return
        val app = ctx.applicationContext
        set(State.Checking)
        Thread({
            try {
                val release = latest(ModelManager.get(app).mirrors()) ?: throw IOException("暂时无法读取 GitHub Release")
                val tag = release.optString("tag_name").removePrefix("v")
                if (compareVersions(tag, BuildConfig.VERSION_NAME) <= 0) {
                    set(State.UpToDate(BuildConfig.VERSION_NAME))
                    return@Thread
                }
                val assetName = "WeaveText-${tag}-arm64.apk"
                val assets = release.optJSONArray("assets") ?: JSONArray()
                val asset = (0 until assets.length()).map {assets.getJSONObject(it)}.firstOrNull {it.optString("name") == assetName}
                    ?: throw IOException("Release 中没有找到适合当前版本的安装包")
                val digest = asset.optString("digest").removePrefix("sha256:").takeIf {it.length == 64}
                val notes = release.optString("body").lineSequence().take(3).joinToString(" ").take(180)
                set(State.Available(release.optString("tag_name"),assetName,asset.optLong("size"),notes))
                if (digest != null) knownDigest = digest
            } catch (e:Exception) {
                Log.i(TAG,"update check failed: ${e.message}")
                set(State.Failed("检查更新失败，请检查网络后重试"))
            }
        },"weave-update-check").apply {isDaemon=true}.start()
    }

    @Volatile private var knownDigest: String? = null

    fun download(ctx:Context, available:State.Available) {
        if (state is State.Downloading || state is State.Verifying) return
        val app=ctx.applicationContext;val tag=available.tag;val dest=File(File(app.cacheDir,"updates"),available.asset)
        cancel.set(false);set(State.Downloading(0,available.size,0,""))
        Thread({
            try {
                val mirrors=ModelManager.get(app).mirrors()
                val url="https://github.com/$REPO/releases/download/$tag/${available.asset}"
                Downloader(mirrors).download(url,knownDigest ?: sums(mirrors,tag,available.asset) ?: throw IOException("没有可用的校验值"),dest,cancel,expectedSize=available.size){p->set(State.Downloading(p.downloaded,p.total,p.bytesPerSecond,p.mirror))}
                set(State.Verifying)
                if(!VoiceUpgrade.verifySigner(app,dest)) throw IOException("安装包签名不一致，已丢弃")
                set(State.Ready(dest,tag))
            }catch(e:Exception){if(cancel.get())set(State.Idle)else set(State.Failed("下载失败，请检查网络后重试"))}
        },"weave-update-download").apply {isDaemon=true}.start()
    }

    fun cancel(){cancel.set(true)}
    fun install(ctx:Context,ready:State.Ready)=VoiceUpgrade.install(ctx,ready.file)

    private fun latest(mirrors:List<Mirror>):JSONObject? {
        val urls=(listOf(API)+mirrors.map {it.apply(API)}).distinct()
        for(url in urls) runCatching {
            val a=JSONArray(httpText(url))
            for(i in 0 until a.length()) {val r=a.getJSONObject(i);if(!r.optBoolean("draft"))return r}
        }
        return null
    }
    private fun sums(mirrors:List<Mirror>,tag:String,asset:String):String? {
        val raw="https://github.com/$REPO/releases/download/$tag/SHA256SUMS.txt"
        for(url in (listOf(raw)+mirrors.map {it.apply(raw)}).distinct()) runCatching {
            val line=httpText(url).lineSequence().firstOrNull {it.trim().endsWith(asset)} ?: return@runCatching
            val sha=line.trim().split(Regex("\\s+"),limit=2).firstOrNull();if(sha?.length==64)return sha
        }
        return null
    }
    private fun httpText(url:String):String {
        val c=URI(url).toURL().openConnection() as HttpURLConnection;c.connectTimeout=8000;c.readTimeout=15000
        c.setRequestProperty("User-Agent","WeaveText-Updater");c.setRequestProperty("Accept","application/vnd.github+json")
        try {if(c.responseCode!=200)throw IOException("HTTP ${c.responseCode}");return c.inputStream.use {readLimited(it,1024*1024).toString(Charsets.UTF_8)}} finally {c.disconnect()}
    }
    private fun readLimited(input:java.io.InputStream,max:Int):ByteArray {val out=ByteArrayOutputStream();val b=ByteArray(8192);while(true){val n=input.read(b);if(n<0)break;require(out.size()+n<=max);out.write(b,0,n)};return out.toByteArray()}

    internal fun compareVersions(a:String,b:String):Int {
        fun nums(v:String)=v.removePrefix("v").substringBefore('-').split('.').map {it.toIntOrNull() ?: 0}
        val x=nums(a);val y=nums(b);for(i in 0 until maxOf(x.size,y.size)){val d=(x.getOrNull(i)?:0)-(y.getOrNull(i)?:0);if(d!=0)return d};return 0
    }
}
