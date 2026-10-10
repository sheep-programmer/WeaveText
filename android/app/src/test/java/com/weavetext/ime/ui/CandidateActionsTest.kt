package com.weavetext.ime.ui

import android.content.ClipboardManager
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import com.weavetext.ime.testing.FakeEngine
import com.weavetext.ime.ime.HardwareKeys
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-420dpi")
class CandidateActionsTest : KeyboardSnapshotSupport() {
    private fun texts(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun prepared(raw: String = "shi") = keyboard(false).also { (_, controller) ->
        controller.attachEngine(FakeEngine())
        controller.onStartInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, false)
        raw.forEach { controller.onChar(it.code) }
        idle()
    }

    @Test fun aPagedCandidateOffersCompleteTextAndCopy() {
        val raw = "ni".repeat(30)
        val (keyboard, _) = prepared(raw)
        assertTrue(keyboard.onCandidateLong(65))
        val popup = shadowOf(app).latestPopupWindow!!
        val labels = texts(popup.contentView)
        assertTrue(labels.any { it.text.toString() == "${raw}65" })
        labels.first { it.text.toString() == "复制完整候选" }.performClick()
        assertEquals("${raw}65", app.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
        assertFalse(popup.isShowing)
    }

    @Test fun changedCompositionDismissesThePopupAndBlocksItsSavedAction() {
        val (keyboard, controller) = prepared()
        assertTrue(keyboard.onCandidateLong(1))
        val popup = shadowOf(app).latestPopupWindow!!
        val copy = texts(popup.contentView).first { it.text.toString() == "复制完整候选" }
        controller.onBackspace()
        assertFalse(popup.isShowing)
        copy.performClick()
        assertNull(app.getSystemService(ClipboardManager::class.java).primaryClip)
    }

    @Test fun expandedGridProvidesTheFullCandidateLongPressActionAndHidingDismissesIt() {
        val (keyboard, controller) = prepared()
        repeat(8) { controller.moveCandidate(9) }
        idle()
        keyboard.onExpand(); idle()
        fun grid(view: View): View? {
            if (view.javaClass.name.endsWith("CandidateGridPanel\$Grid")) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) grid(view.getChildAt(i))?.let { return it }
            return null
        }
        val provider = grid(keyboard.view)!!.accessibilityNodeProvider!!
        assertTrue(provider.performAction(72, AccessibilityNodeInfo.ACTION_LONG_CLICK, null))
        val popup = shadowOf(app).latestPopupWindow!!
        assertTrue(texts(popup.contentView).any { it.text.toString() == "shi72" })
        keyboard.onHidden()
        assertFalse(popup.isShowing)
    }

    @Test fun tappingOrTypingANumberBeforeTheUpdatedCandidatesRenderCannotPickAnotherWord() {
        val (keyboard, controller) = prepared()
        val oldGeneration = controller.state.candidateGeneration
        controller.onChar('a'.code)
        assertNotEquals(oldGeneration, controller.state.candidateGeneration)
        assertEquals("shi1", keyboard.topBar.candidateAt(1))
        keyboard.onCandidate(1)
        assertEquals("shia", controller.state.preedit)
        val keys = HardwareKeys(controller)
        assertTrue(keys.onKeyDown(KeyEvent.KEYCODE_2, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_2)))
        assertEquals("shia", controller.state.preedit)
        assertFalse(keyboard.onCandidateLong(1))
        idle()
        keyboard.onCandidate(1)
        assertFalse(controller.state.composing)
    }

    @Test fun identicalTextInANewCompositionStillRejectsAnOldCandidateIdentity() {
        val (keyboard, controller) = prepared()
        val oldGeneration = controller.state.candidateGeneration
        controller.reset()
        "shi".forEach { controller.onChar(it.code) }
        assertEquals("shi1", keyboard.topBar.candidateAt(1))
        assertFalse(controller.onVisibleCandidate(1, oldGeneration))
        keyboard.onCandidate(1)
        assertTrue(controller.state.composing)
        idle()
        keyboard.onCandidate(1)
        assertFalse(controller.state.composing)
    }
}
