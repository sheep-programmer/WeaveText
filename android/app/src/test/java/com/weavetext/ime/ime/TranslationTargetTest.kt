package com.weavetext.ime.ime

import android.app.Application
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.testing.FakeInputConnection
import com.weavetext.ime.testing.FakeEngine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranslationTargetTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun info(start: Int, end: Int, id: Int = 1) = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT; packageName = "test.app"; fieldId = id
        initialSelStart = start; initialSelEnd = end
    }
    private fun fixture(): Pair<InputController, FakeInputConnection> {
        val ic = FakeInputConnection(FrameLayout(app))
        ic.commitText("hello world", 1); ic.setSelection(0, 5)
        val controller = InputController { ic }
        controller.attachEngine(FakeEngine())
        controller.onStartInput(info(0, 5), false)
        return controller to ic
    }
    @Test fun replacementUsesTheBoundSelectionAndInvalidatesEditorCache() {
        val (controller, ic) = fixture()
        val target = controller.captureTranslationTarget()!!
        assertEquals("hello", target.selectedText)
        assertTrue(controller.writeTranslation(target, "你好", true))
        assertEquals("你好 world", ic.text)
        assertFalse(controller.editor.selectionKnown)
        assertFalse(controller.writeTranslation(target, "重复", true))
    }
    @Test fun insertPreservesOriginalSelectedText() {
        val (controller, ic) = fixture()
        val target = controller.captureTranslationTarget()!!
        assertTrue(controller.writeTranslation(target, "你好", false))
        assertEquals("hello你好 world", ic.text)
    }
    @Test fun movedSelectionEvenWhenRestoredCannotReceiveOldTranslation() {
        val (controller, ic) = fixture()
        val target = controller.captureTranslationTarget()!!
        ic.setSelection(11, 11); controller.onSelectionUpdate(11, 11, -1, -1)
        ic.setSelection(0, 5); controller.onSelectionUpdate(0, 5, -1, -1)
        assertFalse(controller.writeTranslation(target, "你好", true))
        assertEquals("hello world", ic.text)
    }
    @Test fun changedSourceWithSameSelectionOffsetsCannotBeReplaced() {
        val (controller, ic) = fixture()
        val target = controller.captureTranslationTarget()!!
        ic.commitText("other", 1); ic.setSelection(0, 5)
        assertFalse(controller.writeTranslation(target, "你好", true))
        assertEquals("other world", ic.text)
    }
    @Test fun restartedFieldWithIdenticalIdsCannotReceiveOldTranslation() {
        val (controller, ic) = fixture()
        val target = controller.captureTranslationTarget()!!
        controller.onStartInput(info(0, 5), true)
        assertFalse(controller.writeTranslation(target, "你好", true))
        assertEquals("hello world", ic.text)
    }
    @Test fun translationDropsUnconfirmedPinyinInsteadOfCommittingIt() {
        val (controller, ic) = fixture()
        controller.onChar('n'.code); controller.onChar('i'.code)
        assertTrue(controller.state.composing)
        val target = controller.captureTranslationTarget()!!
        assertFalse(controller.state.composing)
        assertTrue(controller.writeTranslation(target, "你好", true))
        assertEquals("你好 world", ic.text)
    }
    @Test fun privateFieldsCannotBeRead() {
        val (controller, _) = fixture()
        controller.onStartInput(info(0, 5).apply { imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING }, false)
        assertNull(controller.captureTranslationTarget())
    }
    @Test fun cancelAndLateVoiceResultDoNotTouchANewFieldWithSameId() {
        val a = FakeInputConnection(FrameLayout(app))
        val b = FakeInputConnection(FrameLayout(app))
        var active = a
        val controller = InputController { active }
        controller.onStartInput(info(0, 0), false); controller.voiceBegin(); controller.voicePartial("旧语音")
        active = b; controller.onStartInput(info(0, 0), false)
        b.setComposingText("新的组合", 1)
        controller.voiceCancel(); controller.voiceFinal("迟到结果"); controller.voiceReplace("新的组合", "错误")
        assertEquals("新的组合", b.text)
    }
}
