package com.weavetext.ime.stickers

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.view.ContentInfo
import android.view.View
import android.view.inputmethod.EditorInfo
import android.text.InputType
import android.widget.GridView
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import com.weavetext.ime.ime.InputController
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class StickerIntegrationTest {
    private val app get()=ApplicationProvider.getApplicationContext<Application>()
    private val root get()=generateSequence(File(checkNotNull(System.getProperty("user.dir")))) {it.parentFile}.first {File(it,"tests/fixtures/stickers").isDirectory}
    private fun awaitImports(){StickerRepository.io.submit {}.get(15,TimeUnit.SECONDS);ShadowLooper.idleMainLooper()}
    @Before fun setup(){awaitImports();StickerRepository.reset();File(app.filesDir,"stickers").deleteRecursively()}
    @After fun cleanup(){awaitImports();StickerRepository.reset()}
    private fun source(name:String):Uri {
        val file=File(app.cacheDir,"clip-current/$name");file.parentFile!!.mkdirs();File(root,"tests/fixtures/stickers/$name").copyTo(file,true)
        return Uri.fromFile(file)
    }
    @Test fun shareTargetCopiesStreamImmediatelyAndConfigurationRestoreDoesNotRepeat() {
        val uri=source("animated.gif");val intent=Intent(app,StickerActivity::class.java).setAction(Intent.ACTION_SEND).setType("image/gif").putExtra(Intent.EXTRA_STREAM,uri)
        val activity=Robolectric.buildActivity(StickerActivity::class.java,intent).setup()
        awaitImports();val repository=StickerRepository.get(app);assertEquals(1,repository.store.list().size)
        val original=repository.store.file(repository.store.list().single()).readBytes()
        File(app.cacheDir,"clip-current/animated.gif").delete()
        assertArrayEquals(File(root,"tests/fixtures/stickers/animated.gif").readBytes(),original)
        activity.configurationChange();awaitImports();assertEquals(1,repository.store.list().size)
        activity.close()
    }
    @Test fun receiveContentDropConsumesReadableImagesAndReportsUnreadableUris() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get();val view=View(activity)
        var message="";val repository=StickerRepository.get(app);StickerDrop.bind(view,repository){message=it}
        val clip=ClipData.newUri(app.contentResolver,"drop",source("animated.webp"))
        assertNull(view.performReceiveContent(ContentInfo.Builder(clip,ContentInfo.SOURCE_DRAG_AND_DROP).build()))
        awaitImports();assertTrue(message.contains("已收纳 1 张"));assertEquals("image/webp",repository.store.list().single().mime)
        repository.import(listOf(Uri.parse("content://denied.provider/unreadable"))){message=it}
        awaitImports();assertTrue(message.contains("失败 1 张"));assertEquals(1,repository.store.list().size)
    }
    @Test fun privateFieldsHideStoredImagesAndMissingOriginalDoesNotCrashSharing() {
        val repository=StickerRepository.get(app);val (item,_)=repository.store.import(File(root,"tests/fixtures/stickers/sample.png").inputStream(),"私密图片")
        val controller=InputController {null};controller.onStartInput(EditorInfo().apply {inputType=InputType.TYPE_CLASS_TEXT},false)
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val shelf=StickerShelfView(activity,true,{}, {}, {}, {}, {}, {!controller.isSensitiveField})
        val grid=(shelf.getChildAt(shelf.childCount-1) as android.widget.FrameLayout).getChildAt(0) as GridView
        assertEquals(1,grid.adapter.count)
        controller.onStartInput(EditorInfo().apply {inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD},false)
        shelf.reload();assertEquals(0,grid.adapter.count)
        repository.store.file(item).delete();StickerSending.share(app,item)
        assertFalse(StickerSending.insert(app,item));controller.onFinishInput()
    }
}
