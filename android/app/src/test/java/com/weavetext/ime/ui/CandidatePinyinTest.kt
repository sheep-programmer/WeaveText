package com.weavetext.ime.ui

import com.github.takahirom.roborazzi.captureRoboImage
import com.weavetext.ime.core.Candidate
import com.weavetext.ime.settings.WeavePrefs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-420dpi")
class CandidatePinyinTest : KeyboardSnapshotSupport() {
    override fun snap(name: String) {
        idle()
        kb!!.view.captureRoboImage(File("/tmp/weavetext-pinyin-android-ui", "$name.png").path)
    }
    private val annotated = listOf(
        Candidate("银行", "", true, true, "yín háng"),
        Candidate("行走", "", false, pinyin = "xíng zǒu"),
        Candidate("爱好", "", false, pinyin = "ài hào"),
        Candidate("时间复杂度", "", false, pinyin = "shí jiān fù zá dù"),
    )
    @Test fun onlyTheTopCandidateShowsBracketedPinyinAndRefreshesWhenOnlyPronunciationChanges() {
        for (dark in listOf(false, true)) {
            val (keyboard, controller) = keyboard(dark)
            controller.previewState(composing(preedit = "yin'hang", cands = annotated)); idle()
            assertEquals("(yín háng)", keyboard.topBar.candidateHintAt(0))
            for (i in 1 until annotated.size) assertNull(keyboard.topBar.candidateHintAt(i))
            snap("pinyin_inline_${if (dark) "dark" else "light"}")
            keyboard.topBar.setCandidates("yin'hang", listOf(annotated[0].copy(pinyin = "xíng")), 1, false, true)
            assertEquals("(xíng)", keyboard.topBar.candidateHintAt(0))
            // 联想（没有组合串）不是在选拼音，不标。 Predictions (no preedit) aren't a pinyin pick, so no hint.
            keyboard.topBar.setCandidates("", annotated, annotated.size, false, false)
            assertNull(keyboard.topBar.candidateHintAt(0))
            keyboard.dispose()
        }
    }
    @Test fun expandedCandidatesShowOnlyWordsWithLargeFontAndLongWords() {
        fontScale(1.3f)
        val (keyboard, controller) = keyboard(false)
        val text = "你好世界".repeat(10)
        val py = List(10) { "nǐ hǎo shì jiè" }.joinToString(" ")
        controller.previewState(composing(cands = annotated + Candidate(text, "", false, pinyin = py))); idle()
        keyboard.onExpand(); idle();snap("words_only_expanded_font13")
        fun grid(view:android.view.View):android.view.View? {
            if(view.javaClass.name.endsWith("CandidateGridPanel\$Grid")) return view
            if(view is android.view.ViewGroup) for(i in 0 until view.childCount) grid(view.getChildAt(i))?.let{return it}
            return null
        }
        val label = grid(keyboard.view)!!.accessibilityNodeProvider.createAccessibilityNodeInfo(0)!!.contentDescription.toString()
        assertTrue(label.contains("银行"));assertFalse(label.contains("yín"))
        assertTrue(keyboard.topBar.candidatePinyinAt(0)!!.isNotEmpty())
    }
    @Test fun annotationsAreOffByDefaultAndNeverChangeKeyboardHeight() {
        val (keyboard, _) = keyboard(false)
        assertEquals(0, WeavePrefs.pinyinHint(WeavePrefs.of(app)))
        val height = keyboard.metrics.kbHeight
        assertEquals(48f * keyboard.metrics.topScale * keyboard.metrics.density, keyboard.metrics.topBar, 0.01f)
        WeavePrefs.of(app).edit().putInt(WeavePrefs.PINYIN_HINT, 1).commit();idle()
        assertEquals(height, keyboard.metrics.kbHeight, 0.01f)
        WeavePrefs.of(app).edit().putInt(WeavePrefs.PINYIN_HINT, 0).commit();idle()
        assertEquals(height, keyboard.metrics.kbHeight, 0.01f)
    }

    @Test fun bracketedPinyinFollowsTheHardwareSelectionWithoutChangingHeight() {
        val (keyboard, controller) = keyboard(false)
        val state = composing(preedit = "yin'hang", cands = annotated)
        controller.previewState(state); idle()
        val height = keyboard.view.height
        controller.previewState(state.copy(highlightedCandidate = 2)); idle()
        assertNull(keyboard.topBar.candidateHintAt(0))
        assertNull(keyboard.topBar.candidateHintAt(1))
        assertEquals("(ài hào)", keyboard.topBar.candidateHintAt(2))
        assertNull(keyboard.topBar.candidateHintAt(3))
        assertEquals(height, keyboard.view.height)
        snap("pinyin_selected_candidate")
    }

    @Test fun selectionBeyondTheMeasuredPrefixBecomesVisibleInBothCandidateViews() {
        val (keyboard, controller) = keyboard(false)
        val candidates = List(80) { Candidate("词语$it", "", false) }
        val state = composing(cands = candidates)
        controller.previewState(state); idle()
        controller.previewState(state.copy(highlightedCandidate = 70)); idle()
        val provider = keyboard.topBar.accessibilityNodeProvider!!
        // Candidate virtual ids start at 100; only the visible bounds are exposed.
        val node = (0 until 300).mapNotNull { provider.createAccessibilityNodeInfo(it) }
            .first { it.contentDescription?.toString() == "词语70" }
        val bounds = android.graphics.Rect()
        node.getBoundsInParent(bounds)
        assertTrue(bounds.width() > 1)
        assertTrue(bounds.left >= 0 && bounds.right <= keyboard.topBar.width)
        keyboard.onExpand(); idle()
        fun grid(view: android.view.View): android.view.View? {
            if (view.javaClass.name.endsWith("CandidateGridPanel\$Grid")) return view
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) grid(view.getChildAt(i))?.let { return it }
            return null
        }
        val expanded = grid(keyboard.view)!!.accessibilityNodeProvider!!.createAccessibilityNodeInfo(70)
        assertNotNull("Expanded selection must be visible after its first layout", expanded)
        assertTrue(expanded!!.contentDescription.toString().contains("已选中"))
        snap("hardware_candidate_expanded")
    }
}
