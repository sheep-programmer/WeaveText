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

    @Test fun aComposingWordCanCommitOnlyItsFirstOrLastChar() {
        val engine = FakeEngine()
        val (keyboard, controller) = keyboard(false)
        controller.attachEngine(engine)
        controller.onStartInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, false)
        "shi".forEach { controller.onChar(it.code) }
        idle()
        assertTrue(keyboard.onCandidateLong(2))
        val labels = texts(shadowOf(app).latestPopupWindow!!.contentView)
        labels.first { it.text.toString() == "只上屏末字「2」" }.performClick()
        assertEquals(listOf(2 to true), engine.charPicks)
        assertFalse(controller.state.composing)
    }

    @Test fun theExpandedGridCanShowOnlySingleCharsFetchedFromLaterPages() {
        // 前 80 个是词，单字都在首页之后。 Words first; every single char is beyond the first page.
        val texts = List(80) { "词语$it" } + List(20) { ('一' + it).toString() }
        val picked = mutableListOf<Int>()
        val base = FakeEngine()
        val engine = object : com.weavetext.ime.core.KeyEngine by base {
            override fun snapshot() = base.snapshot().let { s ->
                if (!s.composing) s else s.copy(candidates = texts.take(60).map { com.weavetext.ime.core.Candidate(it, "", false) }, totalCandidates = texts.size)
            }
            override fun candidates(offset: Int, limit: Int) =
                texts.drop(offset).take(limit).map { com.weavetext.ime.core.Candidate(it, "", false) }
            override fun select(index: Int): Boolean { picked += index; return base.select(index) }
        }
        val (keyboard, controller) = keyboard(false)
        controller.attachEngine(engine)
        controller.onStartInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, false)
        "yi".forEach { controller.onChar(it.code) }
        idle()
        keyboard.onExpand(); idle()
        fun find(view: View, suffix: String): View? {
            if (view.javaClass.name.endsWith(suffix)) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), suffix)?.let { return it }
            return null
        }
        val side = find(keyboard.view, ".PadView")!!.accessibilityNodeProvider!!
        val key = (0 until 6).first { side.createAccessibilityNodeInfo(it)?.contentDescription?.toString() == "只看单字" }
        assertTrue(side.performAction(key, AccessibilityNodeInfo.ACTION_CLICK, null))
        idle()
        val grid = find(keyboard.view, "CandidateGridPanel\$Grid")!!.accessibilityNodeProvider!!
        assertEquals("一", grid.createAccessibilityNodeInfo(0)!!.contentDescription.toString())
        assertEquals("丁", grid.createAccessibilityNodeInfo(1)!!.contentDescription.toString())
        assertNull("only the 20 single chars are shown", grid.createAccessibilityNodeInfo(20))
        assertTrue(grid.performAction(2, AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals("the tap reaches the candidate's real index", listOf(82), picked)
    }

    @Test fun strokeKeysUnderTheGridStartAShapeFilterOnlyWhenTheEngineTakesIt() {
        val base = FakeEngine()
        var shape: String? = null
        var accepts = false
        val engine = object : com.weavetext.ime.core.KeyEngine by base {
            override fun inputChar(codePoint: Int): Boolean {
                val c = codePoint.toChar()
                if (c == '`') { if (accepts && shape == null) shape = ""; return accepts }
                shape?.let { shape = it + c; return true }
                return base.inputChar(codePoint)
            }
            override fun snapshot() = base.snapshot().let { s -> shape?.let { s.copy(preedit = s.preedit + "`" + it) } ?: s }
        }
        val (keyboard, controller) = keyboard(false)
        controller.attachEngine(engine)
        controller.onStartInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, false)
        "shi".forEach { controller.onChar(it.code) }
        idle()
        keyboard.onExpand(); idle()
        fun pads(view: View): List<View> = when {
            view.javaClass.name.endsWith(".PadView") -> listOf(view)
            view is ViewGroup -> (0 until view.childCount).flatMap { pads(view.getChildAt(it)) }
            else -> emptyList()
        }
        val strokes = pads(keyboard.view).first { it.visibility == View.VISIBLE && it.accessibilityNodeProvider!!.createAccessibilityNodeInfo(0)
            ?.contentDescription?.toString() == "笔画筛选：横" }
        assertTrue(strokes.height > 0)
        val provider = strokes.accessibilityNodeProvider!!
        // 内核不收 `（没有字形表）：笔画键什么都不做，不会把 ` 或笔画字母打进拼音。
        // An engine without shapes: nothing happens, neither ` nor the letter reaches the pinyin.
        assertTrue(provider.performAction(1, AccessibilityNodeInfo.ACTION_CLICK, null))
        idle()
        assertTrue(controller.state.composing)
        assertEquals("shi", base.raw.toString())
        accepts = true
        assertTrue(provider.performAction(0, AccessibilityNodeInfo.ACTION_CLICK, null))
        assertTrue(provider.performAction(4, AccessibilityNodeInfo.ACTION_CLICK, null))
        idle()
        assertEquals("shi`HZ", controller.state.preedit)
        assertEquals("shi`一乛", com.weavetext.ime.ui.keyboard.shapeShown(controller.state.preedit))
    }

    @Test fun shapeCodesShowAsStrokesOnlyWhenTheyAreAllStrokeLetters() {
        assertEquals("shi`一丨丿丶乛", com.weavetext.ime.ui.keyboard.shapeShown("shi`HSPNZ"))
        assertEquals("shi`hs", com.weavetext.ime.ui.keyboard.shapeShown("shi`hs"))
        assertEquals("shi`rc", com.weavetext.ime.ui.keyboard.shapeShown("shi`rc"))
        assertEquals("shi`", com.weavetext.ime.ui.keyboard.shapeShown("shi`"))
        assertEquals("shihs", com.weavetext.ime.ui.keyboard.shapeShown("shihs"))
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
