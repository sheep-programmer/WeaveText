package com.weavetext.ime.ocr

import android.content.Context
import android.content.ClipboardManager
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper

/**
 * 「识字」：读取剪贴板里的第一张图片，识别后把文字交给输入框。耗时的部分都在后台线程。
 * 「识字」 reads the first clipboard image, recognises it, and hands the text to the input. Heavy work runs off the UI thread.
 */
object ClipboardOcr {
    private val main = Handler(Looper.getMainLooper())

    fun run(ctx: Context, commit: (String) -> Unit, message: (String) -> Unit) {
        val uri = ctx.getSystemService(ClipboardManager::class.java)?.primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        if (uri == null) {
            message("剪贴板里没有图片")
            return
        }
        Thread {
            val result = runCatching {
                val pipeline = OcrPlugin.pipeline(ctx) ?: return@runCatching Result.Missing
                val bitmap = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    ?: return@runCatching Result.NoImage
                val w = bitmap.width
                val h = bitmap.height
                val pixels = IntArray(w * h)
                bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                bitmap.recycle()
                val text = pipeline.run(OcrFrame(w, h, pixels)).joinToString("\n") { it.text }
                if (text.isEmpty()) Result.Empty else Result.Text(text)
            }.getOrElse { Result.Failed }
            main.post {
                when (result) {
                    is Result.Text -> commit(result.text)
                    Result.Missing -> message("识字模型还没有安装，请到设置里下载")
                    Result.NoImage -> message("剪贴板里的图片无法读取")
                    Result.Empty -> message("没有识别到文字")
                    Result.Failed -> message("识别失败，请重试")
                }
            }
        }.start()
    }

    private sealed class Result {
        class Text(val text: String) : Result()
        data object Missing : Result()
        data object NoImage : Result()
        data object Empty : Result()
        data object Failed : Result()
    }
}
