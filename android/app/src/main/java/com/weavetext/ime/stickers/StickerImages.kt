package com.weavetext.ime.stickers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 表情缩略图解码：网格滚动时按需解码，同一张只解一次，缓存按字节数限额。滑出屏幕的格子会取消协程，
 * 排队中的解码随之跳过，快速翻过几千张时屏幕上的格子不用等前面的队列。
 * Thumbnail decoding for the sticker grid: on demand, once per file, with a byte-bounded cache. Tiles that
 * scroll away cancel their coroutine, so queued decodes are skipped and visible tiles never wait behind a
 * backlog of thousands.
 */
internal object StickerThumbs {
    private val decoder = Dispatchers.IO.limitedParallelism(2)
    private val cache = object : LruCache<String, Bitmap>(
        minOf(32L shl 20, Runtime.getRuntime().maxMemory() / 8).toInt(),
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /** 解码到不小于 [target] 像素的最小 2 次幂缩放。 Decodes at the smallest power-of-two scale not below [target] px. */
    suspend fun load(file: File, id: String, target: Int): ImageBitmap? {
        val key = "$id@$target"
        cache.get(key)?.let { return it.asImageBitmap() }
        return withContext(decoder) {
            ensureActive()
            cache.get(key)?.let { return@withContext it.asImageBitmap() }
            val bitmap = runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull() ?: return@withContext null
            cache.put(key, bitmap)
            bitmap.asImageBitmap()
        }
    }

    internal fun clear() = cache.evictAll()
}
