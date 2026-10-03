package com.weavetext.ime.stickers

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.util.concurrent.Executors

/**
 * 表情缩略图解码：网格滚动时按需解码，同一张只解一次，最多保留 [MAX_CACHE] 张。
 * Thumbnail decoding for the sticker grid: decoded on demand, once per file, capped at [MAX_CACHE] entries.
 */
internal object StickerThumbs {
    private const val MAX_CACHE = 160
    private val worker = Executors.newFixedThreadPool(2) { r -> Thread(r, "weave-sticker-thumb").apply { isDaemon = true } }
    private val cache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) = size > MAX_CACHE
    }

    /** 主线程调用；解码在工作线程完成后回主线程。 Call from the main thread; decoding happens on a worker. */
    fun load(file: File, id: String, target: Int, done: (ImageBitmap) -> Unit) {
        val key = "$id@$target"
        synchronized(cache) { cache[key] }?.let { done(it); return }
        worker.execute {
            val bitmap = runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > target * 2 || bounds.outHeight / sample > target * 2) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull() ?: return@execute
            val image = bitmap.asImageBitmap()
            synchronized(cache) { cache[key] = image }
            done(image)
        }
    }

    internal fun clear() = synchronized(cache) { cache.clear() }
}
