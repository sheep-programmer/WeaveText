package com.weavetext.ime.ime

import android.app.Activity
import android.app.Application
import android.text.InputType
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.testing.FakeEngine
import com.weavetext.ime.testing.FakeInputConnection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

/**
 * 成对符号：左半边补右半边并停在中间，紧接着输入右半边只跨过去；有选中文字时输入右半边是替换选区，
 * 不是跨过选区后面那个符号。
 * Paired punctuation: an opening mark also inserts its closer and leaves the caret between; typing the closer right
 * after steps over it. With a selection, typing the closer replaces that selection instead of stepping over a mark
 * that happens to sit after it.
 */
@RunWith(RobolectricTestRunner::class)
class PairedPunctuationTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var controller: InputController
    private lateinit var ic: FakeInputConnection

    @Before fun setUp() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val frame = FrameLayout(activity)
        activity.setContentView(frame, ViewGroup.LayoutParams(-1, -1))
        ic = FakeInputConnection(frame)
        controller = InputController { ic }
        controller.attachEngine(FakeEngine())
        controller.onStartInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, false)
        ShadowLooper.idleMainLooper()
    }

    @After fun tearDown() { controller.onFinishInput() }

    private fun idle() = ShadowLooper.idleMainLooper()

    @Test fun typingTheCloserAfterThePairStepsOverIt() {
        controller.onText("“")
        assertEquals("“”", ic.text)
        val before = android.text.Selection.getSelectionStart(ic.editable)
        controller.onText("”")
        assertEquals("光标跨过已有右半边，不重复插入", "“”", ic.text)
        assertEquals("光标向后走了一步", before + 1, android.text.Selection.getSelectionStart(ic.editable))
    }

    @Test fun typingTheCloserOverASelectionReplacesIt() {
        controller.onText("“")
        assertEquals("“”", ic.text)
        // 选中右半边左边的那一段，再输入右半边：应当替换选区，而不是跨过选区后面的那个 ”。
        // Select the text just before the closer, then type the closer: it has to replace the selection rather than
        // step over the ” that happens to sit after it.
        ic.setSelection(0, 1)
        idle()
        controller.onText("”")
        assertEquals("有选区时右半边替换选区", "””", ic.text)
    }
}
