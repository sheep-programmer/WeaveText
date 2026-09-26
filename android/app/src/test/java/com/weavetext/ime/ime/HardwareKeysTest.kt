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
}
