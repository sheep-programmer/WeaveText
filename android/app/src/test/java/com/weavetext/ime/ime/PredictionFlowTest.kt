package com.weavetext.ime.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.testing.FakeEngine
import com.weavetext.ime.testing.FakeInputConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 联想词的完整交互：上屏后出现、点选上屏并接着联想、退格删字并收起、光标被移走时收起、打字替换。
 * The whole prediction flow: shown after a commit, a tap commits and chains, backspace deletes and dismisses,
 * a cursor moved elsewhere dismisses, typing replaces them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PredictionFlowTest {
    private val ic = FakeInputConnection(FrameLayout(ApplicationProvider.getApplicationContext()))
    private val controller = InputController { ic }
    private val engine = FakeEngine().apply { predicts = true }

    private fun text() = ic.editable.toString()
    private fun cursor() = android.text.Selection.getSelectionStart(ic.editable)
    private fun type(s: String) = s.forEach { controller.onChar(it.code) }
    private fun echo() = controller.onSelectionUpdate(cursor(), cursor(), -1, -1)

    @Before fun setUp() {
        controller.attachEngine(engine)
        controller.onStartInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; initialSelStart = 0; initialSelEnd = 0 }, false)
    }

    @Test fun tapCommitsAndChains() {
        type("ni")
        controller.onCandidate(0)
        echo()
        assertEquals("【ni】", text())
        assertEquals(listOf("P1", "P2", "P3"), controller.state.candidates.map { it.text })
        controller.onCandidate(1)
        echo()
        assertEquals("【ni】P2", text())
        assertEquals(3, controller.state.candidates.size)
    }

    @Test fun backspaceDeletesAndDismisses() {
        type("ni")
        controller.onCandidate(0)
        echo()
        controller.onBackspace()
        assertEquals("【ni", text())
        assertTrue("predictions must be gone", controller.state.candidates.isEmpty())
        controller.onBackspace()
        controller.onBackspace()
        assertEquals("【", text())
    }

    @Test fun movingTheCursorElsewhereDismisses() {
        type("ni")
        controller.onCandidate(0)
        echo()
        assertEquals(3, controller.state.candidates.size)
        ic.setSelection(1, 1)
        controller.onSelectionUpdate(1, 1, -1, -1)
        assertTrue(controller.state.candidates.isEmpty())
    }

    @Test fun spacePunctuationAndTypingDismiss() {
        type("ni"); controller.onCandidate(0); echo()
        controller.onSpace()
        assertTrue(controller.state.candidates.isEmpty())
        type("ni"); controller.onCandidate(0); echo()
        controller.onText("，")
        assertTrue(controller.state.candidates.isEmpty())
        type("ni"); controller.onCandidate(0); echo()
        type("h")
        assertEquals("h", controller.state.preedit)
    }

    @Test fun voiceTextDismissesStalePredictions() {
        type("ni"); controller.onCandidate(0); echo()
        assertEquals(3, controller.state.candidates.size)
        controller.voicePartial("你好")
        echo()
        controller.voiceFinal("你好")
        echo()
        assertEquals("【ni】你好", text())
        // 语音写进去的字让联想的上文作废：不能留着点了不上屏的旧联想。 Voice text makes the context stale.
        assertTrue("stale predictions must be gone", controller.state.candidates.isEmpty())
        controller.onBackspace()
        assertEquals("【ni】你", text())
        repeat(6) { controller.onBackspace() }
        assertEquals("", text())
    }
}
