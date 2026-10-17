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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 实体键盘：用合成的 KeyEvent 驱动。 Physical keyboard, driven by synthetic KeyEvents. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HardwareKeysTest {
    private val ic = FakeInputConnection(FrameLayout(ApplicationProvider.getApplicationContext()))
    private val controller = InputController { ic }
    private val engine = FakeEngine()
    private val keys = HardwareKeys(controller)

    @Before fun setUp() {
        controller.attachEngine(engine)
        controller.onStartInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; initialSelStart = 0; initialSelEnd = 0 }, false)
    }

    private fun event(action: Int, code: Int, meta: Int) =
        KeyEvent(0L, 0L, action, code, 0, meta, 1, 0, 0, InputDevice.SOURCE_KEYBOARD)

    /** 按下并抬起；返回按下是否被输入法处理。 Press and release; whether DOWN was handled. */
    private fun press(code: Int, meta: Int = 0): Boolean {
        val down = keys.onKeyDown(code, event(KeyEvent.ACTION_DOWN, code, meta))
        val up = keys.onKeyUp(code, event(KeyEvent.ACTION_UP, code, meta))
        assertEquals("UP follows DOWN", down, up)
        return down
    }

    private fun type(s: String) {
        for (c in s) {
            val code = when (c) {
                in 'a'..'z' -> KeyEvent.KEYCODE_A + (c - 'a')
                in '0'..'9' -> KeyEvent.KEYCODE_0 + (c - '0')
                ',' -> KeyEvent.KEYCODE_COMMA
                ' ' -> KeyEvent.KEYCODE_SPACE
                else -> error(c)
            }
            assertTrue(press(code))
        }
    }

    @Test fun lettersComposeAndSpaceCommits() {
        type("ni")
        assertEquals("ni", engine.raw.toString())
        type(" ")
        assertEquals("【ni】", ic.text)
    }

    @Test fun digitsPickCandidatesWhileComposing() {
        type("hao")
        assertTrue(press(KeyEvent.KEYCODE_3))
        assertEquals("hao2", ic.text)
        // 不在组合中：数字照常输入。 Not composing: digits type.
        type("7")
        assertEquals("hao27", ic.text)
    }

    @Test fun punctuationIsFullWidthInChinese() {
        type(",")
        assertEquals("，", ic.text)
    }

    @Test fun backspaceEditsTheCompositionThenTheText() {
        type("ab")
        assertTrue(press(KeyEvent.KEYCODE_DEL))
        assertEquals("a", engine.raw.toString())
        assertTrue(press(KeyEvent.KEYCODE_ENTER)) // 组合中回车：原样上屏。 Enter while composing: raw letters.
        assertEquals("a", ic.text)
        controller.onSelectionUpdate(1, 1, -1, -1)
        assertTrue(press(KeyEvent.KEYCODE_DEL))
        assertEquals("", ic.text)
    }

    @Test fun arrowsMoveTheCaretInsideTheCompositionWhenTheEngineAllowsIt() {
        engine.caretEditing = true
        type("nhao")
        repeat(3) { assertTrue(press(KeyEvent.KEYCODE_DPAD_LEFT)) }
        assertTrue(controller.state.composing)
        assertEquals(1, controller.state.preeditCaret)
        type("i")
        assertEquals("nihao", engine.raw.toString())
        assertEquals(2, controller.state.preeditCaret)
        assertTrue(press(KeyEvent.KEYCODE_MOVE_END))
        assertEquals(null, controller.state.preeditCaret)
        assertTrue(press(KeyEvent.KEYCODE_MOVE_HOME))
        assertEquals(0, controller.state.preeditCaret)
        assertTrue(press(KeyEvent.KEYCODE_FORWARD_DEL))
        assertEquals("ihao", engine.raw.toString())
        assertEquals("", ic.text)
    }

    @Test fun ctrlArrowsAndCtrlDeleteWorkBySyllable() {
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        engine.caretEditing = true
        type("womenzoule")
        assertTrue(press(KeyEvent.KEYCODE_DPAD_LEFT, ctrl))
        assertEquals(8, controller.state.preeditCaret)
        assertTrue(press(KeyEvent.KEYCODE_DPAD_LEFT, ctrl))
        assertEquals(6, controller.state.preeditCaret)
        assertTrue(press(KeyEvent.KEYCODE_DEL, ctrl))
        // 假内核两个字母一个音节：wo|me|nz|ou|le。 The fake pairs letters: wo|me|nz|ou|le.
        assertEquals("womeoule", engine.raw.toString())
        assertEquals(4, controller.state.preeditCaret)
        assertTrue(press(KeyEvent.KEYCODE_FORWARD_DEL, ctrl))
        assertEquals("womele", engine.raw.toString())
        assertTrue(press(KeyEvent.KEYCODE_DPAD_RIGHT, ctrl))
        assertEquals(null, controller.state.preeditCaret)
        assertEquals("", ic.text)
        // 不支持音节编辑的方案：Ctrl+退格删一个键，Ctrl+方向键像其他快捷键一样交给 App。
        // Without syllable editing: Ctrl+Backspace deletes one key, Ctrl+arrows go to the app like other shortcuts.
        engine.caretEditing = false
        assertTrue(press(KeyEvent.KEYCODE_DEL, ctrl))
        assertEquals("womel", engine.raw.toString())
        assertFalse(press(KeyEvent.KEYCODE_DPAD_LEFT, ctrl))
        assertEquals("womel", ic.text)
    }

    @Test fun forwardDeleteOutsideACompositionBelongsToTheApp() {
        assertFalse(press(KeyEvent.KEYCODE_FORWARD_DEL))
    }

    @Test fun bracketsTakeTheFirstOrLastCharOfTheChosenCandidate() {
        type("ab")
        assertTrue(press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue(press(KeyEvent.KEYCODE_RIGHT_BRACKET))
        assertEquals(listOf(1 to true), engine.charPicks)
        assertEquals("1", ic.text)
        type("cd")
        assertTrue(press(KeyEvent.KEYCODE_LEFT_BRACKET))
        assertEquals(0 to false, engine.charPicks.last())
        assertEquals("1【", ic.text)
        // 不在组合中：[ 照常是标点。 Not composing: [ is punctuation as before.
        assertTrue(press(KeyEvent.KEYCODE_LEFT_BRACKET))
        assertEquals(2, engine.charPicks.size)
        assertEquals("1【【", ic.text)
    }

    @Test fun enterOutsideACompositionBelongsToTheApp() {
        assertFalse(press(KeyEvent.KEYCODE_ENTER))
    }

    @Test fun shiftTypesUppercase() {
        assertTrue(press(KeyEvent.KEYCODE_H, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON))
        assertEquals("H", ic.text)
        controller.toggleChinese()
        assertTrue(press(KeyEvent.KEYCODE_H, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON))
        type("i")
        assertEquals("Hi", engine.raw.toString())
    }

    @Test fun ctrlSpaceTogglesChineseAndEnglish() {
        assertTrue(controller.state.chinese)
        assertTrue(press(KeyEvent.KEYCODE_SPACE, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON))
        assertFalse(controller.state.chinese)
        assertEquals("english", engine.schema)
        assertTrue(press(KeyEvent.KEYCODE_SPACE, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON))
        assertTrue(controller.state.chinese)
    }

    @Test fun shortcutsAndArrowsGoToTheAppAfterFlushing() {
        type("zh")
        assertFalse(press(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON))
        assertEquals("zh", ic.text)
        type("a")
        assertFalse(press(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals("zha", ic.text)
        assertFalse(controller.state.composing)
    }

    @Test fun escapeCancelsTheComposition() {
        type("wo")
        assertTrue(press(KeyEvent.KEYCODE_ESCAPE))
        assertEquals("", engine.raw.toString())
        assertFalse(press(KeyEvent.KEYCODE_ESCAPE))
        assertEquals("", ic.text)
    }

    @Test fun candidateNavigationDoesNotWriteRawLetters() {
        type("shi")
        val snapshots = engine.snapshots
        assertTrue(press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue(press(KeyEvent.KEYCODE_TAB))
        assertEquals(2, controller.state.highlightedCandidate)
        assertEquals("shi", engine.raw.toString())
        assertEquals("", ic.text)
        assertEquals("Navigating reuses candidates without decoding again", snapshots, engine.snapshots)
        assertTrue(press(KeyEvent.KEYCODE_SPACE))
        assertEquals("shi2", ic.text)
        assertEquals(-1, controller.state.highlightedCandidate)
    }

    @Test fun pageNumbersChooseFromTheActivePageAndLoadBeyondSnapshot() {
        type("shi")
        repeat(7) { assertTrue(press(KeyEvent.KEYCODE_PAGE_DOWN)) }
        assertEquals(63, controller.state.highlightedCandidate)
        assertEquals("shi63", controller.state.candidates[63].text)
        assertTrue(press(KeyEvent.KEYCODE_3))
        assertEquals("shi65", ic.text)
    }

    @Test fun enterConfirmsOnlyAfterAnExplicitCandidateSelection() {
        type("ni")
        assertTrue(press(KeyEvent.KEYCODE_ENTER))
        assertEquals("ni", ic.text)
        type("hao")
        assertTrue(press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue(press(KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertEquals("nihao1", ic.text)
    }

    @Test fun changingCompositionDropsTheHardwareSelection() {
        type("shi")
        assertTrue(press(KeyEvent.KEYCODE_DPAD_DOWN))
        type("a")
        assertEquals(-1, controller.state.highlightedCandidate)
        assertTrue(press(KeyEvent.KEYCODE_ENTER))
        assertEquals("shia", ic.text)
    }

    @Test fun shiftTabGoesBackAndBoundsDoNotWrap() {
        type("shi")
        assertTrue(press(KeyEvent.KEYCODE_TAB))
        assertTrue(press(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON))
        assertEquals(0, controller.state.highlightedCandidate)
        assertTrue(press(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals(0, controller.state.highlightedCandidate)
        assertTrue(press(KeyEvent.KEYCODE_ESCAPE))
        assertFalse(press(KeyEvent.KEYCODE_TAB))
    }

    @Test fun englishCandidateSpaceKeepsTheExplicitSeparator() {
        controller.toggleChinese()
        type("hel")
        assertTrue(press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue(press(KeyEvent.KEYCODE_SPACE))
        assertEquals("hel1 ", ic.text)
    }

    @Test fun englishPredictionSpaceKeepsTheExplicitSeparatorForTheNextWord() {
        controller.toggleChinese()
        engine.predicts = true
        type("hello ")
        assertTrue(press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue(press(KeyEvent.KEYCODE_SPACE))
        assertEquals("hello P2 ", ic.text)
        type("world")
        assertTrue(press(KeyEvent.KEYCODE_ENTER))
        assertEquals("hello P2 world", ic.text)
    }

    @Test fun idlePredictionEnterAndEscapeClearTheBar() {
        engine.predicts = true
        type("ni ")
        assertTrue(controller.state.candidates.isNotEmpty())
        assertFalse(press(KeyEvent.KEYCODE_ENTER))
        assertTrue(controller.state.candidates.isEmpty())
        type("hao ")
        assertTrue(press(KeyEvent.KEYCODE_ESCAPE))
        assertTrue(controller.state.candidates.isEmpty())
        assertEquals("【ni】【hao】", ic.text)
    }

    @Test fun idlePredictionsLeaveTabAndPageScrollingToTheApp() {
        engine.predicts = true
        type("ni ")
        assertFalse(press(KeyEvent.KEYCODE_TAB))
        assertFalse(press(KeyEvent.KEYCODE_PAGE_DOWN))
        assertEquals(-1, controller.state.highlightedCandidate)
        assertTrue(press(KeyEvent.KEYCODE_DPAD_DOWN))
        assertTrue(press(KeyEvent.KEYCODE_2))
        assertEquals("【ni】P2", ic.text)
    }

    @Test fun capitalsInsideDoublePinyinReachTheEngineAndEnterPreservesTheKeys() {
        // Model the engine's new uppercase contract without loading JNI in a host key-routing test.
        controller.attachEngine(object : com.weavetext.ime.core.KeyEngine by engine {
            override fun inputChar(codePoint: Int): Boolean {
                if (codePoint in 'A'.code..'Z'.code && engine.schema.startsWith("shuangpin")) {
                    engine.raw.append(codePoint.toChar()); return true
                }
                return engine.inputChar(codePoint)
            }
        })
        assertTrue(controller.setSchema("shuangpin:xiaohe"))
        type("ni")
        assertTrue(press(KeyEvent.KEYCODE_H, KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON))
        type("ub")
        assertEquals("niHub", controller.state.preedit)
        assertEquals("", ic.text)
        assertTrue(press(KeyEvent.KEYCODE_ENTER))
        assertEquals("niHub", ic.text)
    }
}
