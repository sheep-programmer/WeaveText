package com.weavetext.ime.voice

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.weavetext.ime.BuildConfig
import com.weavetext.ime.models.Downloader
import com.weavetext.ime.models.Mirror
import com.weavetext.ime.models.ModelManager
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 轻量版一键装上离线语音：下载同版本的离线语音版 APK（走模型下载同一套 GitHub 镜像、断点续传、SHA-256 校验），
 * 确认签名与当前安装的一致后交给系统安装器覆盖安装——设置、词库、用户词都保留。
 *
 * One-tap offline voice for the lite build: download the offline-voice APK of the same version (same GitHub
 * mirrors, resume and SHA-256 checks as model downloads), confirm it is signed like the installed app, then
 * hand it to the system installer as an in-place update that keeps settings and user words.
 */
object VoiceUpgrade {
    private const val TAG = "WeaveVoiceUpgrade"
    private const val REPO = "sheep-programmer/WeaveText"
    private const val DIR = "updates"

    sealed interface State {
        data object Idle : State
        data class Downloading(val done: Long, val total: Long, val bytesPerSecond: Long, val mirror: String) : State
        data object Verifying : State
        data class Ready(val file: File) : State
        data class Failed(val message: String) : State
    }

    @Volatile var state: State = State.Idle
        private set
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val cancel = AtomicBoolean(false)

    /** 只有运行库不随包的轻量版需要（离线语音版本身已含端侧识别）。 Only the lite build needs this. */
    val available: Boolean get() = !com.weavetext.ime.models.AsrRuntime.bundled

    private val tag get() = "v" + BuildConfig.VERSION_NAME
    private val assetName get() = "WeaveText-${BuildConfig.VERSION_NAME}-arm64-voice.apk"

    fun addListener(l: (State) -> Unit) { listeners += l }
    fun removeListener(l: (State) -> Unit) { listeners -= l }

    private fun set(s: State) {
        state = s
        main.post { for (l in listeners) l(s) }
    }

    /** 开始（或继续）下载；已在进行则忽略。 Start or resume the download; ignored while running. */
    fun start(ctx: Context) {
        if (state is State.Downloading || state is State.Verifying) return
        val app = ctx.applicationContext
        cancel.set(false)
        set(State.Downloading(0, 0, 0, ""))
        Thread({ run(app) }, "weave-voice-upgrade").apply { isDaemon = true }.start()
    }

    fun cancel() {
        cancel.set(true)
    }

    private fun run(ctx: Context) {
        try {
            val mirrors = ModelManager.get(ctx).mirrors()
            val (sha, size) = assetInfo(mirrors) ?: throw IOException("暂时取不到离线语音版的下载信息，请检查网络后重试")
            val dest = File(File(ctx.cacheDir, DIR), assetName)
            val url = "https://github.com/$REPO/releases/download/$tag/$assetName"
            Downloader(mirrors).download(url, sha, dest, cancel, expectedSize = size) { p ->
                set(State.Downloading(p.downloaded, p.total, p.bytesPerSecond, p.mirror))
            }
            set(State.Verifying)
            if (!verifySigner(ctx, dest)) {
                dest.delete()
                throw IOException("下载的安装包签名不一致，已丢弃")
            }
            set(State.Ready(dest))
        } catch (e: Exception) {
            Log.w(TAG, "download failed", e)
            set(if (cancel.get()) State.Idle else State.Failed(friendly(e)))
        }
    }

    private fun friendly(e: Exception): String {
        val m = e.message.orEmpty()
        return when {
            m.startsWith("暂时") || m.startsWith("下载的") -> m
            m.contains("sha256", true) -> "下载的文件校验失败，请重试"
            else -> "下载失败，请检查网络后重试（可在「离线模型」里换下载源）"
        }
    }

    /**
     * 大小与 SHA-256：优先读 GitHub 发布接口（数据来自 GitHub 本身，不经镜像），取不到再经镜像读 SHA256SUMS.txt。
     * Size and SHA-256 from the GitHub release API (served by GitHub, not a mirror), else SHA256SUMS.txt via mirrors.
     */
    private fun assetInfo(mirrors: List<Mirror>): Pair<String, Long>? {
        runCatching {
            val json = JSONObject(httpText("https://api.github.com/repos/$REPO/releases/tags/$tag"))
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val digest = a.optString("digest")
                if (a.optString("name") == assetName && digest.startsWith("sha256:")) {
                    return digest.removePrefix("sha256:") to a.optLong("size")
                }
            }
        }.onFailure { Log.i(TAG, "release API unavailable: ${it.message}") }
        val sums = "https://github.com/$REPO/releases/download/$tag/SHA256SUMS.txt"
        for (m in mirrors) {
            val text = runCatching { httpText(m.apply(sums)) }.getOrNull() ?: continue
            val sha = text.lineSequence()
                .map { it.trim().split(Regex("\\s+"), limit = 2) }
                .firstOrNull { it.size == 2 && it[1].trimStart('*') == assetName }
                ?.first()
            if (sha != null && sha.length == 64) return sha.lowercase() to 0L
        }
        return null
    }

    private fun httpText(url: String): String {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "WeaveText-Updater")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { i ->
                val bytes = i.readNBytesCompat(256 * 1024)
                String(bytes, Charsets.UTF_8)
            }
        } finally {
            c.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, minOf(n, max - out.size()))
        }
        return out.toByteArray()
    }

    /** 安装包与当前应用同包名、同签名。 Same package name and signer as the installed app. */
    @Suppress("DEPRECATION")
    fun verifySigner(ctx: Context, apk: File): Boolean {
        val pm = ctx.packageManager
        return runCatching {
            if (Build.VERSION.SDK_INT >= 28) {
                val flag = PackageManager.GET_SIGNING_CERTIFICATES
                val theirs = pm.getPackageArchiveInfo(apk.path, flag) ?: return false
                val ours = pm.getPackageInfo(ctx.packageName, flag)
                val a = theirs.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
                val b = ours.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
                theirs.packageName == ctx.packageName && a != null && a == b
            } else {
                val flag = PackageManager.GET_SIGNATURES
                val theirs = pm.getPackageArchiveInfo(apk.path, flag) ?: return false
                val ours = pm.getPackageInfo(ctx.packageName, flag)
                theirs.packageName == ctx.packageName &&
                    theirs.signatures?.map { it.toCharsString() }?.toSet() == ours.signatures?.map { it.toCharsString() }?.toSet()
            }
        }.getOrDefault(false)
    }

    /**
     * 调起系统安装器；还没允许「安装未知应用」时先打开该授权页，返回 false。
     * Launch the system installer; if installing unknown apps isn't allowed yet, open that page and return false.
     */
    fun install(ctx: Context, file: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /** 已经是离线语音版时删掉残留的安装包。 Remove the leftover package once the voice build is installed. */
    fun cleanUp(ctx: Context) {
        if (com.weavetext.ime.models.AsrRuntime.bundled) File(ctx.cacheDir, DIR).deleteRecursively()
    }
}
