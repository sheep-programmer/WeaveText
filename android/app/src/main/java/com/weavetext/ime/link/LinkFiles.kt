package com.weavetext.ime.link

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** 把收到的文件放进「下载/WeaveText」。 Put received files into Downloads/WeaveText. */
object LinkFiles {
    const val FOLDER = "WeaveText"

    fun saveToDownloads(ctx: Context, src: File, name: String, mime: String): Uri? = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val cr = ctx.contentResolver
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            cr.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
            cr.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            uri
        } else {
            // Android 8–9：应用专属下载目录（无需存储权限），经 FileProvider 打开。
            // Android 8–9: the app's own downloads dir (no storage permission), opened via FileProvider.
            val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FOLDER).apply { mkdirs() }
            val dest = File(dir, name)
            src.copyTo(dest, overwrite = true)
            androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", dest)
        }
    }.getOrNull()
}
