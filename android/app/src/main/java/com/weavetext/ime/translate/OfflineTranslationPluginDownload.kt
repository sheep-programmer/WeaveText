package com.weavetext.ime.translate

import com.weavetext.ime.models.Progress
import android.content.Context
import com.weavetext.ime.BuildConfig
import com.weavetext.ime.models.Downloader
import com.weavetext.ime.models.ModelManager
import com.weavetext.ime.voice.VoiceUpgrade
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用内一键获取离线翻译插件：下载与本应用同版本发布的插件 APK（同一套 GitHub 镜像、断点续传、SHA-256 校验），
 * 再经 [OfflineTranslationPlugin.verifyAndInstall] 检查签名与清单，交给系统安装器确认。插件仍是独立 APK，
 * 不并入输入法进程。
 *
 * One-tap plugin install inside the app: download the plugin APK released with this app version (same mirrors, resume
 * and SHA-256 checks as other downloads), then check its signer and manifest and hand it to the system installer. The
 * plugin remains a separate APK; it is not loaded into the keyboard process.
 */
object OfflineTranslationPluginDownload {
    /** 与 translation-google 的 versionName 一致；测试会核对，改版本时一起改。 Must match translation-google's versionName. */
    const val PLUGIN_VERSION = "0.1.0"
    const val ASSET = "WeaveText-Google-Offline-Translation-$PLUGIN_VERSION.apk"
    private const val REPO = "sheep-programmer/WeaveText"
    private const val DIR = "translation-plugins"

    /**
     * 后台线程调用。取消时抛 [java.io.InterruptedIOException]；下载完成后再校验，失败则删除文件。
     * Call on a background thread. Cancelling throws InterruptedIOException; a failed check deletes the file.
     */
    fun downloadAndInstall(ctx: Context, cancel: AtomicBoolean, onProgress: (Progress) -> Unit = {}) {
        val app = ctx.applicationContext
        val manager = ModelManager.get(app)
        val mirrors = manager.mirrors()
        val tag = "v" + BuildConfig.VERSION_NAME
        val (sha, size) = VoiceUpgrade.assetInfo(mirrors, cancel, releaseTag = tag, asset = ASSET)
            ?: throw IOException("暂时取不到插件的下载信息，请检查网络后重试")
        Downloader.checkCancelled(cancel)
        val dir = File(app.cacheDir, DIR).apply { mkdirs() }
        val dest = File(dir, ASSET)
        val url = "https://github.com/$REPO/releases/download/$tag/$ASSET"
        Downloader(mirrors).download(url, sha, dest, cancel, preferred = manager.mirrorPreference.takeIf { it != "auto" },
            expectedSize = size, onProgress = onProgress)
        Downloader.checkCancelled(cancel)
        try {
            OfflineTranslationPlugin.verifyAndInstall(app, dest)
        } catch (error: Exception) {
            dest.delete()
            throw error
        }
    }
}
