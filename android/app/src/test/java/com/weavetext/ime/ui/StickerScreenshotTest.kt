package com.weavetext.ime.ui

import com.weavetext.ime.stickers.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import android.widget.GridView
import android.widget.TextView

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],qualifiers="w411dp-h914dp-port-420dpi")
class StickerScreenshotTest:KeyboardSnapshotSupport() {
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
    private fun nodes(view:View):List<View> = listOf(view)+(if(view is ViewGroup)(0 until view.childCount).flatMap{nodes(view.getChildAt(it))}else emptyList())
    @Test fun deleteFromKeyboardConfirmsAndCancelKeepsTheOriginalFile() {
        val (k,_)=keyboard(false);k.showPanel("stickers");idle();previews(k.view)
        val grid=nodes(k.view).filterIsInstance<GridView>().first()
        val item=grid.adapter.getItem(0) as Sticker
        grid.onItemLongClickListener!!.onItemLongClick(grid,grid.getChildAt(0),0,0)
        idle()
        nodes(k.view).first{it.contentDescription?.toString()=="删除"}.performClick();idle()
        assertNotNull(StickerRepository.get(app).store.get(item.id))
        snap("stickers_delete_confirmation_light")
        nodes(k.view).filterIsInstance<TextView>().first{it.text.toString()=="取消"}.performClick();idle()
        assertNotNull(StickerRepository.get(app).store.get(item.id))
        assertTrue(StickerRepository.get(app).store.file(item).isFile)
        nodes(k.view).first{it.contentDescription?.toString()=="删除"}.performClick();idle()
        nodes(k.view).filterIsInstance<TextView>().first{it.text.toString()=="删除"}.performClick()
        StickerRepository.io.submit{}.get();idle()
        assertEquals(null,StickerRepository.get(app).store.get(item.id))
        assertEquals(false,StickerRepository.get(app).store.file(item).isFile)
    }
}
