package com.weavetext.ime.stickers

import android.app.Instrumentation
import android.graphics.drawable.AnimatedImageDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * 收纳袋的预览要和原文件一致：动图必须拿到可播放的动画，静态图仍是位图。
 * 这个检查只能在设备上做，图片解码器的动画支持不在单元测试的运行环境里。
 * Shelf previews must match the stored file: an animation becomes a playable drawable, a still stays a bitmap.
 * The check needs a device, because the animation support of the image decoder is absent from the unit-test runtime.
 */
@RunWith(AndroidJUnit4::class)
class StickerPreviewDeviceTest {
    private val instrumentation: Instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val recipient get() = instrumentation.targetContext

    private fun preview(name: String): StickerImageView {
        val bytes = instrumentation.context.assets.open("stickers/$name").use { it.readBytes() }
        val repository = StickerRepository.get(recipient)
        val (item, _) = repository.store.import(bytes.inputStream(), name)
        var view: StickerImageView? = null
        instrumentation.runOnMainSync { view = StickerImageView(recipient).also { it.bind(repository.store.file(item), item.id) } }
        val result = requireNotNull(view)
        val end = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < end && result.drawable == null) {
            StickerRepository.io.submit {}.get(15, TimeUnit.SECONDS)
            instrumentation.waitForIdleSync()
            Thread.sleep(30)
        }
        return result
    }

    @Test fun animatedStickerDecodesToPlayableAnimation() {
        val view = preview("animated.gif")
        val drawable = view.drawable
        assertNotNull("动图应当解码出预览", drawable)
        assertTrue(
            "动图预览应是可播放的动画，实际为 ${drawable!!::class.java.name}（回退原因：${view.previewError}）",
            drawable is AnimatedImageDrawable,
        )
    }

    @Test fun stillStickerStaysABitmap() {
        val drawable = preview("sample.png").drawable
        assertNotNull("静态图应当解码出预览", drawable)
        assertFalse("静态图不应该被当成动画", drawable is AnimatedImageDrawable)
    }
}
