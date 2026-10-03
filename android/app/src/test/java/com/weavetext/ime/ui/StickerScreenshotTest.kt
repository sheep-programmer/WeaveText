package com.weavetext.ime.ui

import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.weavetext.ime.settings.WeaveSettingsTheme
import com.weavetext.ime.stickers.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],qualifiers="w411dp-h914dp-port-420dpi")
class StickerScreenshotTest:KeyboardSnapshotSupport() {
    @get:Rule val compose = createComposeRule()

    @Before fun samples() {
        StickerRepository.io.submit {}.get();StickerRepository.reset();File(app.filesDir,"stickers").deleteRecursively()
        val root=generateSequence(File(checkNotNull(System.getProperty("user.dir")))) {it.parentFile}.first {File(it,"tests/fixtures/stickers").isDirectory}
        val store=StickerRepository.get(app).store
        for((name,title) in listOf("sample.png" to "开心","animated.gif" to "晚安","animated.webp" to "收到")) {
            val (item,_)=store.import(File(root,"tests/fixtures/stickers/$name").inputStream(),title)
            store.edit(item.id,title,"日常",listOf("常用"),name=="sample.png")
        }
    }
    private fun images(view:View):List<StickerImageView> = if(view is StickerImageView)listOf(view)else if(view is ViewGroup)(0 until view.childCount).flatMap {images(view.getChildAt(it))}else emptyList()
    private fun previews(view:View) {
        val decor=activity.window.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(activity.resources.displayMetrics.widthPixels,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(activity.resources.displayMetrics.heightPixels,View.MeasureSpec.EXACTLY))
        decor.layout(0,0,decor.measuredWidth,decor.measuredHeight)
        val end=System.nanoTime()+5_000_000_000L
        do {Thread.sleep(30);idle()}while(System.nanoTime()<end && images(view).count {it.drawable!=null}<3)
        assertEquals("Visible thumbnails: views=${images(view).size}, errors=${images(view).map {it.previewError}}",3,images(view).count {it.drawable!=null})
    }
    @Test fun keyboardLight(){val (k,_)=keyboard(false);k.showPanel("stickers");idle();previews(k.view);snap("stickers_light")}
    @Test fun keyboardDark(){val (k,_)=keyboard(true);k.showPanel("stickers");idle();previews(k.view);snap("stickers_dark")}

    /** 管理页现在是与设置页同一套 Compose 风格；截图直接渲染它。 The manager is now the Compose screen. */
    @Test fun manager() {
        compose.setContent {
            WeaveSettingsTheme(dark = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    StickerManagerScreen(onBack = {})
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(dir, "sticker_manager_light.png").path)
    }
}
