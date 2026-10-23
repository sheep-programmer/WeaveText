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
        var deletionAccepted = true
        var deletionNoOp = false
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
            if (!deletionAccepted) false else if (deletionNoOp) true else super.deleteSurroundingText(beforeLength, afterLength)
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

    @Test fun arrowInSelectModeHoldsShift() {
        c.cursorArrow(KeyEvent.KEYCODE_DPAD_UP, select = true)
        // Shift 键本身按下、方向键、Shift 抬起：普通文本框靠 Shift 键按下的状态扩选。
        // Shift down, the arrow, Shift up: plain text views extend the selection while the Shift key is down.
        assertEquals(listOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_DPAD_UP), downs().map { it.keyCode })
        assertEquals(KeyEvent.META_SHIFT_ON, downs()[1].metaState and KeyEvent.META_SHIFT_ON)
        assertEquals(KeyEvent.KEYCODE_SHIFT_LEFT, ic.keys.last().keyCode)
        assertEquals(KeyEvent.ACTION_UP, ic.keys.last().action)
    }

    @Test fun leftAndRightInSelectModeMoveTheFocusAndKeepTheAnchor() {
        c.cursorArrow(KeyEvent.KEYCODE_DPAD_LEFT, select = true)
        c.cursorArrow(KeyEvent.KEYCODE_DPAD_LEFT, select = true)
        assertEquals(11, ic.selStart); assertEquals(9, ic.selEnd)
        c.cursorArrow(KeyEvent.KEYCODE_DPAD_RIGHT, select = true)
        assertEquals(11, ic.selStart); assertEquals(10, ic.selEnd)
        assertEquals(emptyList<KeyEvent>(), ic.keys)
        // 读不到文本时退回到带 Shift 的方向键。 Without readable text: the arrow with Shift held.
        setup(extract = false)
        c.cursorArrow(KeyEvent.KEYCODE_DPAD_LEFT, select = true)
        assertEquals(listOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_DPAD_LEFT), downs().map { it.keyCode })
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
        assertEquals(KeyEvent.KEYCODE_SHIFT_LEFT, downs().first().keyCode)
        val d = downs().last()
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
        c.restoreCleared(removed!!)
        assertEquals("hello world", ic.editable.toString())
    }

    @Test fun clearRecoveryRejectsAMovedCaret() {
        ic.setSelection(5, 5)
        val removed = c.clearBeforeCursor()!!
        ic.setSelection(3, 3)
        c.onSelectionUpdate(3, 3, -1, -1)
        c.restoreCleared(removed)
        assertEquals(" world", ic.editable.toString())
    }

    @Test fun clearRecoveryRejectsHostChangesAtTheSameCaret() {
        ic.setSelection(5, 5)
        val removed = c.clearBeforeCursor()!!
        ic.editable!!.replace(0, 6, " other")
        ic.setSelection(0, 0)
        c.restoreCleared(removed)
        assertEquals(" other", ic.editable.toString())
    }

    @Test fun clearRecoveryIsOneUseAndAcceptsItsOwnSelectionEcho() {
        ic.setSelection(5, 5)
        val removed = c.clearBeforeCursor()!!
        c.onSelectionUpdate(0, 0, -1, -1)
        c.restoreCleared(removed)
        c.restoreCleared(removed)
        assertEquals("hello world", ic.editable.toString())
    }

    @Test fun clearRecoveryExpiresOnSameFieldRestart() {
        ic.setSelection(5, 5)
        val removed = c.clearBeforeCursor()!!
        c.onStartInput(android.view.inputmethod.EditorInfo().apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            initialSelStart = 0; initialSelEnd = 0
        }, true)
        c.restoreCleared(removed)
        assertEquals(" world", ic.editable.toString())
    }

    @Test fun clearingASelectionRestoresItsOriginalEndpoints() {
        ic.setSelection(8, 2)
        val removed = c.clearBeforeCursor()!!
        assertEquals("llo wo", removed)
        assertEquals("herld", ic.editable.toString())
        c.restoreCleared(removed)
        assertEquals("hello world", ic.editable.toString())
        assertEquals(8, ic.selStart)
        assertEquals(2, ic.selEnd)
    }

    @Test fun aLargerRequestedClearLimitStillKeepsASurrogatePairWhole() {
        ic.editable!!.clear()
        ic.commitText("😀" + "a".repeat(1999), 1)
        c.clearBeforeCursor(limit = 3000)
        assertEquals("😀", ic.editable.toString())
    }

    @Test fun unreadableSelectionDoesNotOfferUnverifiableRecovery() {
        setup(extract = false)
        c.onSelectionUpdate(11, 11, -1, -1)
        assertNull(c.clearBeforeCursor())
        assertEquals("", ic.editable.toString())
        c.restoreCleared("hello world")
        assertEquals("", ic.editable.toString())
    }

    @Test fun rejectedOrNoOpDeletionDoesNotOfferRecovery() {
        ic.deletionAccepted = false
        assertNull(c.clearBeforeCursor())
        assertEquals("hello world", ic.editable.toString())
        ic.deletionAccepted = true; ic.deletionNoOp = true
        assertNull(c.clearBeforeCursor())
        assertEquals("hello world", ic.editable.toString())
    }

    @Test fun clearRecoveryDoesNotFollowAReplacedConnection() {
        val removed = c.clearBeforeCursor()!!
        ic = FakeConnection(View(ApplicationProvider.getApplicationContext()), true)
        ic.commitText("new editor", 1)
        c.restoreCleared(removed)
        assertEquals("new editor", ic.editable.toString())
    }

    @Test fun clearRecoveryRejectsMovementAwayAndBack() {
        ic.setSelection(5, 5)
        val removed = c.clearBeforeCursor()!!
        ic.setSelection(2, 2); c.onSelectionUpdate(2, 2, -1, -1)
        ic.setSelection(0, 0); c.onSelectionUpdate(0, 0, -1, -1)
        c.restoreCleared(removed)
        assertEquals(" world", ic.editable.toString())
    }

    @Test fun clearRecoveryExpiresAfterTheChipTimeout() {
        val removed = c.clearBeforeCursor()!!
        org.robolectric.shadows.ShadowLooper.idleMainLooper(3, java.util.concurrent.TimeUnit.SECONDS)
        c.restoreCleared(removed)
        assertEquals("", ic.editable.toString())
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
