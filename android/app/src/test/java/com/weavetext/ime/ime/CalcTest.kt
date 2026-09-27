package com.weavetext.ime.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.testing.FakeEngine
import com.weavetext.ime.testing.FakeInputConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 敲等号后，光标前的算式结果作为候选给出。 After "=", the result of the preceding expression is offered. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalcTest {
    private val ic = FakeInputConnection(FrameLayout(ApplicationProvider.getApplicationContext()))
    private val controller = InputController { ic }
    private var offered: List<String>? = null

    private fun start(text: String) {
        ic.commitText(text, 1)
        controller.attachEngine(FakeEngine())
        controller.onStartInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT; initialSelStart = text.length; initialSelEnd = text.length }, false)
        controller.onCalc = { offered = it }
    }

    @Test fun equalsOffersTheResult() {
        start("单价 128*4")
        controller.onText("=")
        assertEquals(listOf("512"), offered)
    }

    private fun text() = ic.editable.toString()

    @Test fun pairsBracketsAndStepsOverTheCloser() {
        start("")
        controller.onText("（")
        assertEquals("（）", text())
        assertEquals(1, android.text.Selection.getSelectionStart(ic.editable))
        controller.onText("好")
        controller.onText("）")
        assertEquals("（好）", text())
        assertEquals(3, android.text.Selection.getSelectionStart(ic.editable))
        controller.autoPair = false
        controller.onText("《")
        assertEquals("（好）《", text())
    }

    @Test fun noExpressionNoOffer() {
        start("你好")
        controller.onText("=")
        assertNull(offered)
        controller.onText("+")
        assertNull(offered)
    }
}
