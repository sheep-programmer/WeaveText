package com.weavetext.ime.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.core.KeyEngine
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

    private fun start(text: String, type: Int = InputType.TYPE_CLASS_TEXT, options: Int = 0, engine: KeyEngine = FakeEngine()) {
        controller.onFinishInput()
        ic.editable!!.clear()
        offered = null
        ic.commitText(text, 1)
        controller.attachEngine(engine)
        controller.onStartInput(EditorInfo().apply {
            inputType = type
            imeOptions = options
            initialSelStart = text.length
            initialSelEnd = text.length
        }, false)
        controller.onCalc = { offered = it }
    }

    @Test fun equalsOffersTheResult() {
        start("单价 128*4")
        controller.onText("=")
        assertEquals(listOf("512"), offered)
    }

    @Test fun characterEqualsOffersTheResultAndKeepsTheFormulaInTheEditor() {
        for (equals in listOf('=', '＝')) {
            start("单价 128*4")
            controller.onChar(equals.code)
            assertEquals("单价 128*4$equals", text())
            assertEquals(listOf("512"), offered)
        }
    }

    @Test fun unicodeFormulaIsOfferedWithoutTruncatingAtOperatorsOrDecimalPoint() {
        // 固定内核响应只负责隔离原生计算；这里验证控制器从编辑框提取整个算式。
        // Fixed engine responses isolate native arithmetic while the controller extracts the whole editor formula.
        val results = mapOf("12＋3*4" to "24", "12－3*4" to "0", "12−3*4" to "0", "1．5*4" to "6")
        val engine = object : KeyEngine by FakeEngine() {
            override fun evaluate(expr: String): String? = results[expr]
        }
        for ((formula, result) in results) for (character in listOf(false, true)) {
            start("合计 $formula", engine = engine)
            if (character) controller.onChar('='.code) else controller.onText("=")
            assertEquals("合计 $formula=", text())
            assertEquals("Formula $formula; character=$character", listOf(result), offered)
        }
    }

    @Test fun sensitiveFieldsNeverOfferResultsFromEitherInputPath() {
        val fields = listOf(
            (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) to 0,
            (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) to 0,
            (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) to 0,
            (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD) to 0,
            InputType.TYPE_CLASS_TEXT to EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
        )
        for ((type, options) in fields) for (equals in listOf('=', '＝')) for (character in listOf(false, true)) {
            start("128*4", type, options)
            if (character) controller.onChar(equals.code) else controller.onText(equals.toString())
            assertEquals("128*4$equals", text())
            assertNull("Sensitive field: type=$type options=$options character=$character", offered)
        }
    }

    @Test fun disablingCalculatorKeepsCharacterEqualsLiteral() {
        start("128*4")
        controller.calcEnabled = false
        controller.onChar('='.code)
        assertEquals("128*4=", text())
        assertNull(offered)
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
