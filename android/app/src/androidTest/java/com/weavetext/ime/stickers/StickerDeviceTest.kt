package com.weavetext.ime.stickers

import android.content.Intent
import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputContentInfo
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weavetext.ime.ime.InputController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class StickerDeviceTest {
    @Test fun temporaryShareGrantIsCopiedAndEditorReceivesOriginalAnimation() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val recipient=instrumentation.targetContext
        val bytes=instrumentation.context.assets.open("stickers/animated.gif").use {it.readBytes()}
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        val repo=StickerRepository.get(recipient);repo.store.delete(setOf(hash))
        fun launchSender(revoke:Boolean=false) {
            recipient.startActivity(Intent().setClassName("com.weavetext.ime.test","com.weavetext.ime.stickers.StickerShareSourceActivity")
                .putExtra("revoke",revoke).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        launchSender()
        val end=System.nanoTime()+20_000_000_000L
        while(repo.store.get(hash)==null && System.nanoTime()<end)Thread.sleep(50)
        val item=repo.store.get(hash) ?: error("Separate source package share was not collected")
        launchSender(true);Thread.sleep(300)
        StickerRepository.io.submit {}.get(20,TimeUnit.SECONDS)
        assertArrayEquals(bytes,repo.store.file(item).readBytes())
        instrumentation.runOnMainSync {
            var received:ByteArray?=null;var flagsReceived=0
            val connection=object:BaseInputConnection(View(recipient),true) {
                override fun commitContent(content:InputContentInfo,flags:Int,opts:android.os.Bundle?):Boolean {
                    flagsReceived=flags;received=recipient.contentResolver.openInputStream(content.contentUri)!!.use {it.readBytes()};return true
                }
            }
            val editor=EditorInfo().apply {inputType=InputType.TYPE_CLASS_TEXT;EditorInfoCompat.setContentMimeTypes(this,arrayOf("image/gif"))}
            val controller=InputController {connection};controller.onStartInput(editor,false)
            assertTrue(controller.onContent(repo.uri(item),item.mime,item.name));assertArrayEquals(bytes,received)
            assertTrue(flagsReceived and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION!=0)
            EditorInfoCompat.setContentMimeTypes(editor,arrayOf("text/plain"));controller.onStartInput(editor,false)
            assertFalse(controller.onContent(repo.uri(item),item.mime,item.name));controller.onFinishInput()
        }
    }
}
