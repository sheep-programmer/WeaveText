package com.weavetext.ime.ime

import android.text.InputType
import android.view.InputDevice
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
 * 真实 App 里的输入路径：重新开始输入、内核晚到、光标移动、删除、标点与空格、撤销、回车动作。
 * The input path as real apps drive it: restarts, a late engine, cursor moves, deletes, punctuation and spaces,
 * undo and Enter actions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InputPathTest {
    /** 记录编辑器动作的假连接；[maxLength] 模拟字数上限（超出的上屏被拒收）。 Records editor actions; [maxLength] rejects commits. */
    private class Connection : FakeInputConnection(FrameLayout(ApplicationProvider.getApplicationContext())) {
        var maxLength = Int.MAX_VALUE
        val actions = mutableListOf<Int>()
        val menuActions = mutableListOf<Int>()
        override fun commitText(text: CharSequence?, pos: Int): Boolean {
            if (editable!!.length + (text?.length ?: 0) > maxLength) return true
            return super.commitText(text, pos)
        }
        override fun performEditorAction(actionCode: Int): Boolean { actions += actionCode; return true }
        override fun performContextMenuAction(id: Int): Boolean { menuActions += id; return true }
        val cursor get() = android.text.Selection.getSelectionStart(editable!!)
    }

    private val ic = Connection()
    private val controller = InputController { ic }
    private val engine = FakeEngine()

    private fun info(type: Int = InputType.TYPE_CLASS_TEXT, options: Int = 0, reportsSelection: Boolean = true) = EditorInfo().apply {
        inputType = type
        imeOptions = options
        initialSelStart = if (reportsSelection) ic.cursor.coerceAtLeast(0) else -1
        initialSelEnd = initialSelStart
    }

    private fun start(text: String = "", type: Int = InputType.TYPE_CLASS_TEXT, options: Int = 0, reportsSelection: Boolean = true) {
        if (text.isNotEmpty()) ic.commitText(text, 1)
        controller.attachEngine(engine)
        controller.onStartInput(info(type, options, reportsSelection), false)
    }

    private fun type(s: String) = s.forEach { controller.onChar(it.code) }
    private fun echo() = controller.onSelectionUpdate(ic.cursor, ic.cursor, -1, -1)

    // ------------------------------------------------------------ restartInput

    @Test fun restartKeepsEnglishButANewFieldStartsInChinese() {
        start()
        controller.toggleChinese()
        assertFalse(controller.state.chinese)
        // 聊天应用发送后 setText("")：同一输入框重新开始。 A chat app clears the box after sending.
        controller.onStartInput(info(), true)
        assertFalse("restart keeps English", controller.state.chinese)
        assertEquals("english", engine.schema)
        controller.onFinishInput()
        controller.onStartInput(info(), false)
        assertTrue("a new field starts in Chinese", controller.state.chinese)
    }

    @Test fun restartIntoAPasswordFieldSwitchesMode() {
        start()
        controller.onStartInput(info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD), true)
        assertFalse(controller.state.chinese)
        assertFalse(engine.learningValue)
    }

    @Test fun editorInfoIsReadAgainWhenTheKeyboardShows() {
        start()
        assertEquals(EnterAction.NEWLINE, controller.state.enterAction)
        controller.onStartInputView(info(options = EditorInfo.IME_ACTION_SEARCH))
        assertEquals(EnterAction.SEARCH, controller.state.enterAction)
    }

    // ------------------------------------------------------------ late engine

    @Test fun lateEngineGetsTheFieldsLearningAndMode() {
        controller.onStartInput(info(options = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING), false)
        controller.toggleChinese()
        controller.attachEngine(engine)
        assertFalse("incognito: no learning", engine.learningValue)
        assertEquals("english", engine.schema)
    }

    @Test fun keysBeforeTheEngineAreReplayedNotCommitted() {
        controller.onStartInput(info(), false)
        type("nihao")
        controller.onBackspace()
        assertEquals("", ic.text)
        assertEquals("niha", controller.state.preedit)
        controller.onSpace()
        assertEquals("", ic.text)
        controller.attachEngine(engine)
        assertEquals("【niha】", ic.text)
        assertFalse(controller.state.composing)
    }

    @Test fun failedEngineLoadEmitsTheKeysAsTyped() {
        controller.onStartInput(info(), false)
        type("ni")
        controller.engineUnavailable()
        assertEquals("ni", ic.text)
        type("a")
        assertEquals("nia", ic.text)
    }

    @Test fun keptKeysAreDroppedWhenTheFieldChanges() {
        controller.onStartInput(info(), false)
        type("ni")
        controller.onFinishInput()
        controller.onStartInput(info(), false)
        controller.attachEngine(engine)
        assertEquals("", engine.raw.toString())
        assertEquals("", ic.text)
    }

    // ------------------------------------------------------------ cursor moves

    @Test fun cursorMovesUseSetSelectionAndStopAtTheEdges() {
        start("a😂b")
        controller.moveCursor(-1)
        echo()
        assertEquals(3, ic.cursor)
        controller.moveCursor(-1)
        echo()
        assertEquals("the emoji is stepped over whole", 1, ic.cursor)
        controller.moveCursor(-5)
        echo()
        assertEquals(0, ic.cursor)
        controller.moveCursor(-1)
        controller.moveCursor(10)
        echo()
        assertEquals(4, ic.cursor)
        controller.moveCursor(1)
        assertTrue("no arrow keys: focus can't jump away", ic.keyDowns.isEmpty())
        assertEquals(0, ic.cursorMoves)
    }

    @Test fun unknownSelectionFallsBackToSoftKeyboardArrows() {
        start("abc", reportsSelection = false)
        controller.moveCursor(-2)
        assertEquals(-2, ic.cursorMoves)
        for (e in ic.keyDowns) {
            assertEquals(KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE, e.flags and (KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE))
            assertEquals(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, e.deviceId)
        }
    }

    // ------------------------------------------------------------ deletes

    @Test fun backspaceIsASoftKeyboardDelKey() {
        start("ab")
        controller.onBackspace()
        assertEquals("a", ic.text)
        val d = ic.keyDowns.single()
        assertEquals(KeyEvent.KEYCODE_DEL, d.keyCode)
        assertTrue(d.flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0)
    }

    @Test fun chatAppsThatDeleteATagAsAUnitStayInSync() {
        // 聊天应用在删除键里把整个「[微笑]」删掉。 A chat app deletes the whole "[smile]" tag on DEL.
        val chat = object : FakeInputConnection(FrameLayout(ApplicationProvider.getApplicationContext())) {
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                val e = editable!!
                val end = android.text.Selection.getSelectionEnd(e)
                if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_DEL && e.substring(0, end).endsWith("[微笑]")) {
                    e.delete(end - 4, end)
                    return true
                }
                return super.sendKeyEvent(event)
            }
        }
        val c = InputController { chat }
        chat.commitText("好[微笑]", 1)
        c.attachEngine(FakeEngine())
        c.onStartInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; initialSelStart = 5; initialSelEnd = 5 }, false)
        c.onBackspace()
        assertEquals("好", chat.text)
        c.onSelectionUpdate(1, 1, -1, -1)
        c.onBackspace()
        assertEquals("", chat.text)
    }

    @Test fun wordDeleteNeverSplitsSurrogatePairs() {
        assertEquals(2, InputController.wordLengthBefore("哈哈😂"))
        assertEquals(4, InputController.wordLengthBefore("ab👍🏽"))
        // 扩展区汉字按汉字成段。 CJK extension characters form an ideograph run.
        assertEquals(4, InputController.wordLengthBefore("ab𠀀𠀁"))
        assertEquals(6, InputController.wordLengthBefore("x hello "))
        // 读取窗口从代理对中间开始：不删那半个。 A window starting inside a pair keeps that half.
        assertEquals(2, InputController.wordLengthBefore("\uDE02𠀀"))
    }

    @Test fun heldDeleteRemovesWholeEmoji() {
        start("哈哈😂😂")
        controller.deleteWordBefore()
        assertEquals("哈哈😂", ic.text)
        controller.deleteWordBefore()
        assertEquals("哈哈", ic.text)
        controller.deleteWordBefore()
        assertEquals("", ic.text)
    }

    @Test fun rejectedCommitIsNotUndoneFromRealText() {
        start("abcd")
        // 退格一次：镜像里有了光标前的文字。 One backspace fills the mirror with the text before the cursor.
        controller.onBackspace()
        echo()
        ic.maxLength = 3
        // 上滑替换：按下时输出的「，」被拒收，替换不能删掉真实的 c。 The key-down "，" was rejected; don't delete "c".
        controller.onText("，")
        assertEquals("abc", ic.text)
        controller.undoLastInput()
        assertEquals("abc", ic.text)
    }

    // ------------------------------------------------------------ punctuation and spaces

    @Test fun punctuationAfterDigitsStaysAscii() {
        start()
        for (c in "3.14") controller.onChar(c.code)
        controller.onChar(' '.code)
        for (c in "12:30,") controller.onChar(c.code)
        type("a")
        controller.onChar('.'.code)
        assertEquals("3.14 12:30,【a】。", ic.text)
    }

    @Test fun numberFieldsKeepAsciiPunctuation() {
        start(type = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        assertFalse(controller.state.chinese)
        for (c in "1.5") controller.onChar(c.code)
        assertEquals("1.5", ic.text)
    }

    @Test fun doubleSpaceGivesOnePeriod() {
        start()
        controller.toggleChinese()
        type("hello")
        controller.onSpace()
        controller.onSpace()
        assertEquals("hello. ", ic.text)
        controller.onSpace()
        assertEquals("never \"hello.. \"", "hello.  ", ic.text)
    }

    @Test fun noDoubleSpacePeriodInPasswordOrUrlFields() {
        start(type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        type("ab")
        controller.onSpace(); controller.onSpace()
        assertEquals("ab  ", ic.text)
        controller.onFinishInput()
        ic.editable!!.clear()
        start(type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        type("ab")
        controller.onSpace(); controller.onSpace()
        assertEquals("ab  ", ic.text)
    }

    @Test fun punctuationSwallowsTheAutoSpace() {
        start()
        controller.toggleChinese()
        type("hello")
        controller.onCandidate(0)
        assertEquals("hello ", ic.text)
        controller.onChar(','.code)
        assertEquals("hello,", ic.text)
        // 用户自己打的空格保留。 A space the user typed stays.
        controller.onSpace()
        controller.onText("!")
        assertEquals("hello, !", ic.text)
    }

    // ------------------------------------------------------------ physical keyboard

    private val keys by lazy { HardwareKeys(controller) }
    private fun press(code: Int, meta: Int = 0) {
        keys.onKeyDown(code, KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, code, 0, meta, 1, 0, 0, InputDevice.SOURCE_KEYBOARD))
        keys.onKeyUp(code, KeyEvent(0L, 0L, KeyEvent.ACTION_UP, code, 0, meta, 1, 0, 0, InputDevice.SOURCE_KEYBOARD))
    }

    @Test fun physicalShiftLetterWhileComposingIsUppercase() {
        start()
        press(KeyEvent.KEYCODE_N); press(KeyEvent.KEYCODE_I)
        press(KeyEvent.KEYCODE_A, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)
        assertEquals("【ni】A", ic.text)
    }

    @Test fun physicalDigitsStayInTheVMode() {
        start()
        press(KeyEvent.KEYCODE_V); press(KeyEvent.KEYCODE_1); press(KeyEvent.KEYCODE_2)
        assertEquals("v12", engine.raw.toString())
        assertEquals("", ic.text)
    }

    @Test fun physicalKeysBeforeTheEngineAreKept() {
        controller.onStartInput(info(), false)
        press(KeyEvent.KEYCODE_N); press(KeyEvent.KEYCODE_I)
        assertEquals("", ic.text)
        controller.attachEngine(engine)
        assertEquals("ni", engine.raw.toString())
    }

    // ------------------------------------------------------------ undo / enter

    @Test fun undoUsesKeysInWebFieldsAndTheMenuElsewhere() {
        start()
        controller.undoRedo(redo = false)
        assertEquals(listOf(android.R.id.undo), ic.menuActions)
        assertTrue(ic.keyDowns.isEmpty())
        controller.onStartInput(info(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT), false)
        controller.undoRedo(redo = false)
        assertEquals(1, ic.menuActions.size)
        val z = ic.keyDowns.single()
        assertEquals(KeyEvent.KEYCODE_Z, z.keyCode)
        assertTrue(z.isCtrlPressed)
    }

    @Test fun enterUsesACustomActionId() {
        ic.commitText("", 1)
        controller.attachEngine(engine)
        controller.onStartInput(info(options = EditorInfo.IME_ACTION_SEND).apply { actionLabel = "发布"; actionId = 42 }, false)
        controller.onEnter()
        assertEquals(listOf(42), ic.actions)
    }
}
