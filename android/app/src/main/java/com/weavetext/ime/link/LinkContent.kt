package com.weavetext.ime.link

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.weavetext.ime.ime.ClipHistory
import com.weavetext.ime.ime.ClipItem
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Copies provider streams while the grant is valid; never sends a pipe with an unknown length. */
object LinkContent {
    private const val CLIP_LIMIT = 512L * 1024 * 1024
    val io = Executors.newSingleThreadExecutor { r -> Thread(r, "weave-content").apply { isDaemon = true } }
    fun history(ctx: Context) = ClipHistory.shared(File(ctx.filesDir, "clipboard/history.json"))
    fun describe(ctx: Context, uri: Uri): Pair<String, String> {
        val name = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "file"
        return safeName(name) to (ctx.contentResolver.getType(uri) ?: "application/octet-stream")
    }
    fun safeName(name: String) = name.substringAfterLast('/').substringAfterLast('\\')
        .filter { !it.isISOControl() }.take(180).takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "file"

    fun send(ctx: Context, link: LinkController, to: String?, uri: Uri, clip: Boolean = false): Boolean {
        require(uri.scheme == "content") { "请通过系统文件选择器或原应用的分享功能授权此文件" }
        val (name, mime) = describe(ctx, uri)
        val dir = File(ctx.cacheDir, "link-outgoing").apply { mkdirs() }
        val snapshot = File.createTempFile("send-", ".tmp", dir)
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { input -> snapshot.outputStream().use { out ->
                // 剪贴板自动同步与剪贴板历史同一上限，复制大视频不会在后台悄悄写满存储。
                // Automatic clip sync shares the clipboard-history cap, so copying a huge video can't fill storage.
                val buffer = ByteArray(64 * 1024)
                var bytes = 0L
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    bytes += n; require(!clip || bytes <= CLIP_LIMIT) { "剪贴板文件超过 512 MB，未同步；请直接发送文件" }
                    out.write(buffer, 0, n)
                }
            } } ?: error("无法读取文件，需重新从原应用分享")
            val fd = ParcelFileDescriptor.open(snapshot, ParcelFileDescriptor.MODE_READ_ONLY).detachFd()
            link.sendFd(to, fd, name, mime, clip)
        } finally { snapshot.delete() } // Core owns an open FD, so unlinking cannot interrupt the transfer.
    }

    fun import(ctx: Context, uri: Uri, mime: String? = null, name: String? = null, record: Boolean = true): ClipItem {
        val (givenName, givenMime) = describe(ctx, uri)
        val dir = (if (record) File(ctx.filesDir, "clipboard/media") else File(ctx.cacheDir, "clip-current")).apply { mkdirs() }
        val tmp = File.createTempFile("import-", ".tmp", ctx.cacheDir)
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            ctx.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var bytes = 0L
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    bytes += n; require(bytes <= CLIP_LIMIT) { "文件超过剪贴板历史的 512 MB 上限，请直接发送文件" }
                    hash.update(buffer, 0, n); out.write(buffer, 0, n)
                }
            } } ?: error("无法读取剪贴板文件")
            val digest = hash.digest().joinToString("") { "%02x".format(it) }
            val ext = safeName(name ?: givenName).substringAfterLast('.', "").take(12).filter { it.isLetterOrDigit() }
            val dest = File(dir, digest + if (ext.isEmpty()) "" else ".$ext")
            val owned = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", dest)
            val now = System.currentTimeMillis()
            if (!record) {
                if (!dest.exists()) tmp.copyTo(dest)
                dir.listFiles()?.filter { it != dest }?.forEach { it.delete() }
                return ClipItem(-1, safeName(name ?: givenName), now, uri = owned.toString(), mime = mime ?: givenMime, bytes = dest.length())
            }
            val store = history(ctx)
            synchronized(store) {
                if (!dest.exists()) tmp.copyTo(dest)
                store.addMedia(owned.toString(), mime ?: givenMime, safeName(name ?: givenName), dest.length(), now)
                return store.list(now).first { it.uri == owned.toString() }
            }
        } finally { tmp.delete() }
    }
    fun importFile(ctx: Context, file: File, mime: String, name: String, record: Boolean = true) =
        import(ctx, Uri.fromFile(file), mime, name, record)
}
