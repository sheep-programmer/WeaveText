package com.weavetext.ime.ime

import android.app.Application
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputContentInfo
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RichContentTest {
    @Test fun mediaPasteUsesUriGrantAndUnsupportedEditorsReceiveNoUriText() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        var content: InputContentInfo? = null; var granted = false
        val connection = object : BaseInputConnection(View(app), true) {
            override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean {
                content = inputContentInfo
                granted = flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0
                return true
            }
        }
        val controller = InputController { connection }
        val editor = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
        val uri = Uri.parse("content://com.weavetext.ime.files/clip_media/test.png")
        controller.onStartInput(editor, false)
        assertFalse(controller.onContent(uri, "image/png", "photo.png"))
        assertNull(content); assertEquals("", connection.editable.toString())
        EditorInfoCompat.setContentMimeTypes(editor, arrayOf("image/*"))
        controller.onStartInput(editor, false)
        assertTrue(controller.onContent(uri, "image/png", "photo.png"))
        assertEquals(uri, content!!.contentUri); assertTrue(granted)
        content = null
        editor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        controller.onStartInput(editor, false)
        assertFalse(controller.onContent(uri, "image/png", "photo.png")); assertNull(content)
        controller.onFinishInput()
    }
}
