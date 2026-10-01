package com.weavetext.ime.link

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import java.io.File

/** Destination writes must complete before a source is deleted. */
object LinkFiles {
    const val FOLDER = "WeaveText"
    fun save(ctx: Context, src: File, name: String, mime: String, tree: String): Uri {
        if (tree.isEmpty()) return saveToDownloads(ctx, src, name, mime) ?: error("无法保存到下载目录，原文件已保留")
        val directory = DocumentFile.fromTreeUri(ctx, Uri.parse(tree)) ?: error("接收目录授权已失效，请重新选择")
        require(directory.canWrite()) { "接收目录不可写，请重新授权" }
        val target = directory.createFile(mime, LinkContent.safeName(name)) ?: error("无法在接收目录创建文件")
        return try {
            ctx.contentResolver.openOutputStream(target.uri, "w")?.use { out -> src.inputStream().use { it.copyTo(out) } }
                ?: error("接收目录无法写入")
            target.uri
        } catch (e: Exception) { target.delete(); throw e }
    }
    fun saveToDownloads(ctx: Context, src: File, name: String, mime: String): Uri? = runCatching {
        val safe = LinkContent.safeName(name)
        if (Build.VERSION.SDK_INT >= 29) {
            val cr = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safe)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("无法创建下载文件")
            try {
                cr.openOutputStream(uri, "w")?.use { out -> src.inputStream().use { it.copyTo(out) } } ?: error("无法打开下载文件")
                cr.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                uri
            } catch (e: Exception) { cr.delete(uri, null, null); throw e }
        } else {
            val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FOLDER).apply { mkdirs() }
            var dest = File(dir, safe); var n = 1
            while (dest.exists()) { dest = File(dir, "${n++}-$safe") }
            src.copyTo(dest)
            FileProvider.getUriForFile(ctx, ctx.packageName + ".files", dest)
        }
    }.getOrNull()
}
