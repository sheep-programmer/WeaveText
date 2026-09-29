package com.weavetext.ime.ime

import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 光标面板动作（InputController 新增的方法），不接内核。 Cursor-panel actions without the engine.
 */
@RunWith(RobolectricTestRunner::class)
class CursorActionsTest {
    /** 记录按键并维护可编辑文本的输入连接。 Records key events over a real Editable. */
    private class FakeConnection(view: View, private val extract: Boolean) : BaseInputConnection(view, true) {
        val keys = ArrayList<KeyEvent>()
        override fun sendKeyEvent(event: KeyEvent): Boolean { keys += event; return true }
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
            if (!extract) return null
            val e = editable ?: return null
            return ExtractedText().apply {
                text = e.toString(); startOffset = 0
                selectionStart = android.text.Selection.getSelectionStart(e)
                selectionEnd = android.text.Selection.getSelectionEnd(e)
            }
        }
        val selStart get() = android.text.Selection.getSelectionStart(editable)
        val selEnd get() = android.text.Selection.getSelectionEnd(editable)
    }

    private lateinit var ic: FakeConnection
    private lateinit var c: InputController

    private fun setup(extract: Boolean = true) {
        ic = FakeConnection(View(ApplicationProvider.getApplicationContext()), extract)
        c = InputController { ic }
        ic.commitText("hello world", 1)
    }

    @Before fun before() = setup()

    private fun downs() = ic.keys.filter { it.action == KeyEvent.ACTION_DOWN }

    @Test fun arrowWithoutSelectionHasNoShift() {
        c.cursorArrow(KeyEvent.KEYCODE_DPAD_LEFT, select = false)
        val d = downs().single()
        assertEquals(KeyEvent.KEYCODE_DPAD_LEFT, d.keyCode)
        assertEquals(0, d.metaState and KeyEvent.META_SHIFT_ON)
    }

    @Test fun arrowInSelectModeCarriesShift() {
        c.cursorArrow(KeyEvent.KEYCODE_DPAD_UP, select = true)
        val d = downs().single()
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, d.keyCode)
        assertEquals(KeyEvent.META_SHIFT_ON, d.metaState and KeyEvent.META_SHIFT_ON)
        assertEquals(2, ic.keys.size) // down + up
    }

    @Test fun homeAndEndMoveCaret() {
        c.cursorToEdge(end = false, select = false)
        assertEquals(0, ic.selStart); assertEquals(0, ic.selEnd)
        c.cursorToEdge(end = true, select = false)
        assertEquals(11, ic.selStart); assertEquals(11, ic.selEnd)
    }

    @Test fun homeInSelectModeKeepsAnchor() {
        ic.setSelection(5, 5)
        c.cursorToEdge(end = false, select = true)
        assertEquals(5, ic.selStart); assertEquals(0, ic.selEnd)
        // 再到末尾：锚点仍是 5。 Extending to the end keeps anchor 5.
        c.cursorToEdge(end = true, select = true)
        assertEquals(5, ic.selStart); assertEquals(11, ic.selEnd)
    }

    @Test fun fallsBackToCtrlHomeEndWithoutExtractedText() {
        setup(extract = false)
        c.cursorToEdge(end = true, select = true)
        val d = downs().single()
        assertEquals(KeyEvent.KEYCODE_MOVE_END, d.keyCode)
        assertEquals(KeyEvent.META_CTRL_ON, d.metaState and KeyEvent.META_CTRL_ON)
        assertEquals(KeyEvent.META_SHIFT_ON, d.metaState and KeyEvent.META_SHIFT_ON)
    }

    @Test fun tabInsertsTabCharacter() {
        c.onTab()
        assertEquals("hello world\t", ic.editable.toString())
    }

    @Test fun clearBeforeCursorReturnsRemovedTextForUndo() {
        ic.setSelection(5, 5)
        val removed = c.clearBeforeCursor()
        assertEquals("hello", removed)
        assertEquals(" world", ic.editable.toString())
        c.onText(removed!!)
        assertEquals("hello world", ic.editable.toString())
    }

    @Test fun clearBeforeCursorOnEmptyReturnsNull() {
        ic.setSelection(0, 0)
        assertNull(c.clearBeforeCursor())
    }

    @Test fun pairedTextPlacesCursorInside() {
        c.onPairedText("（", "）")
        assertEquals("hello world（）", ic.editable.toString())
        assertEquals(12, ic.selStart)
        assertEquals(12, ic.selEnd)
        // 不发方向键（文本边缘会让焦点跳走）。 No arrow keys (at the text edge they move focus away).
        assertEquals(0, downs().size)
    }

    @Test fun deleteWordRemovesTrailingRun() {
        c.deleteWordBefore()
        assertEquals("hello ", ic.editable.toString())
    }
}
