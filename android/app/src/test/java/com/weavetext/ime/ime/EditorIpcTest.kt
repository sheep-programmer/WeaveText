package com.weavetext.ime.ime

import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.testing.FakeEngine
import com.weavetext.ime.testing.FakeInputConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 按键路径上的编辑器 IPC：用计数的假连接检查退格、自动大写的调用次数，以及不回报选区的编辑器仍然正确。
 * Editor IPCs on the key path: a counting fake connection checks backspace and auto-caps, and that editors
 * which never report the selection still behave.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditorIpcTest {

    /** 记录每次调用的假连接。 Counts every call. */
    private class CountingConnection : FakeInputConnection(FrameLayout(ApplicationProvider.getApplicationContext())) {
        var calls = 0
        val keyEvents = mutableListOf<Int>()
        var selectedTextCalls = 0
        var capsCalls = 0
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? { calls++; return super.getTextBeforeCursor(n, flags) }
        override fun getSelectedText(flags: Int): CharSequence? { calls++; selectedTextCalls++; return super.getSelectedText(flags) }
        override fun getCursorCapsMode(reqModes: Int): Int { calls++; capsCalls++; return super.getCursorCapsMode(reqModes) }
        override fun deleteSurroundingText(before: Int, after: Int): Boolean { calls++; return super.deleteSurroundingText(before, after) }
        override fun commitText(text: CharSequence?, pos: Int): Boolean { calls++; return super.commitText(text, pos) }
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            calls++
            if (event.action == KeyEvent.ACTION_DOWN) keyEvents += event.keyCode
            return super.sendKeyEvent(event)
        }
        val selStart get() = android.text.Selection.getSelectionStart(editable!!)
        val selEnd get() = android.text.Selection.getSelectionEnd(editable!!)
    }

    private val ic = CountingConnection()
    private val controller = InputController { ic }

    private fun start(text: String, inputType: Int = InputType.TYPE_CLASS_TEXT, reportsSelection: Boolean = true) {
        ic.commitText(text, 1)
        controller.attachEngine(FakeEngine())
        controller.onStartInput(
            EditorInfo().apply {
                this.inputType = inputType
                initialSelStart = if (reportsSelection) text.length else -1
                initialSelEnd = if (reportsSelection) text.length else -1
            },
            false,
        )
        ic.calls = 0
    }

    /** 像编辑器那样回报当前选区。 Report the current selection like the editor does. */
    private fun echo() = controller.onSelectionUpdate(ic.selStart, ic.selEnd, -1, -1)

    @Test fun backspaceIsOneKeyOnceTheMirrorIsFilled() {
        start("hello world")
        controller.onBackspace()
        // 首次：读一次光标前文字 + 删除键（按下、抬起）。 First press: one read plus the DEL key (down, up).
        assertEquals(3, ic.calls)
        echo()
        ic.calls = 0
        repeat(5) { controller.onBackspace() } // 回报晚到也不影响。 Echoes may lag.
        echo()
        assertEquals("hello", ic.text)
        assertEquals("only the key events, no reads", 10, ic.calls)
        assertEquals(0, ic.selectedTextCalls)
        assertEquals(List(6) { KeyEvent.KEYCODE_DEL }, ic.keyEvents)
    }

    @Test fun backspaceRemovesAWholeEmojiCluster() {
        start("ok👍🏽")
        controller.onBackspace()
        assertEquals("ok", ic.text)
        controller.onBackspace()
        assertEquals("o", ic.text)
    }

    @Test fun backspaceWithASelectionDeletesIt() {
        start("hello")
        android.text.Selection.setSelection(ic.editable!!, 1, 4)
        echo()
        ic.calls = 0
        controller.onBackspace()
        assertEquals("ho", ic.text)
        assertEquals(1, ic.calls)
    }

    @Test fun externalCursorMoveDropsTheMirror() {
        start("abcdef")
        controller.onBackspace() // 镜像："abcde"。 Mirror now "abcde".
        echo()
        // 用户在 App 里把光标点到 b 后面。 The user taps after "b".
        android.text.Selection.setSelection(ic.editable!!, 2)
        echo()
        controller.onBackspace()
        assertEquals("acde", ic.text)
    }

    @Test fun editorsThatNeverReportTheSelectionUseKeyEvents() {
        start("abc", reportsSelection = false)
        controller.onBackspace()
        controller.onBackspace()
        assertEquals("a", ic.text)
        assertEquals(listOf(KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_DEL), ic.keyEvents)
    }

    @Test fun terminalsGetKeyEvents() {
        start("ls", inputType = InputType.TYPE_NULL)
        controller.onBackspace()
        assertEquals(listOf(KeyEvent.KEYCODE_DEL), ic.keyEvents)
    }

    @Test fun backspaceAtTheStartSendsTheKey() {
        start("")
        controller.onBackspace()
        assertEquals(listOf(KeyEvent.KEYCODE_DEL), ic.keyEvents)
    }

    @Test fun capsModeIsComputedLocally() {
        val type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        start("", inputType = type)
        controller.toggleChinese()
        assertTrue(controller.capsModeActive())
        // 无联想的框里字母直接上屏：每键都要知道是否大写。 No suggestions: letters commit directly.
        for (c in "Hi. ") {
            controller.onChar(c.code)
            echo()
            controller.capsModeActive()
        }
        assertTrue("after '. '", controller.capsModeActive())
        controller.onChar('Y'.code)
        assertFalse(controller.capsModeActive())
        assertEquals("Hi. Y", ic.text)
        assertEquals("no getCursorCapsMode on the key path", 0, ic.capsCalls)
        // 1 次读取 + 5 次上屏。 One read plus five commits.
        assertEquals(6, ic.calls)
    }

    @Test fun composingRegionKeepsTheMirrorUntrusted() {
        start("abc")
        // 语音中间结果（composing）的第一次回报：选区不可信。 First report with a voice composing region.
        controller.onSelectionUpdate(5, 5, 3, 5)
        assertFalse(controller.editor.selectionKnown)
        controller.onSelectionUpdate(5, 5, -1, -1)
        assertTrue(controller.editor.selectionKnown)
    }

    @Test fun editorsThatStopReportingAreNotTrustedForever() {
        start("", reportsSelection = true)
        // 给了初始选区却从不回报：多次改动之后不再相信镜像。 Initial selection but no reports: stop trusting it.
        repeat(20) { controller.onText("a") }
        assertFalse(controller.editor.selectionKnown)
    }

    @Test fun capsModeIsReusedWhileComposing() {
        val type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        start("", inputType = type)
        controller.toggleChinese() // 英文，字母在内核里组合。 English; letters compose in the engine.
        ic.calls = 0
        controller.capsModeActive()
        val afterFirst = ic.calls
        for (c in "hello") { controller.onChar(c.code); controller.capsModeActive() }
        assertEquals("no IPC while the editor is unchanged", afterFirst, ic.calls)
    }
}
