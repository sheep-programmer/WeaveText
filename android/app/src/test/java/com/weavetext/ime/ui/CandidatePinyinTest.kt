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
    @Test fun annotationsRenderAboveWordsAndRefreshWhenOnlyPronunciationChanges() {
        for (dark in listOf(false, true)) {
            val (keyboard, controller) = keyboard(dark)
            controller.previewState(composing(preedit = "yin'hang", cands = annotated)); idle()
            assertEquals("yín háng", keyboard.topBar.candidatePinyinAt(0))
            snap("pinyin_above_${if (dark) "dark" else "light"}")
            keyboard.topBar.setCandidates("yin'hang", listOf(annotated[0].copy(pinyin = "xíng")), 1, false, true)
            assertEquals("xíng", keyboard.topBar.candidatePinyinAt(0))
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
    @Test fun disablingAnnotationsUpdatesKeyboardHeightAndKeepsNavigationInsets() {
        val (keyboard, _) = keyboard(false)
        val height = keyboard.metrics.kbHeight
        WeavePrefs.of(app).edit().putInt(WeavePrefs.PINYIN_HINT, 0).commit();idle()
        assertTrue(keyboard.metrics.kbHeight < height)
        assertEquals(48f * keyboard.metrics.topScale * keyboard.metrics.density, keyboard.metrics.topBar, 0.01f)
        WeavePrefs.of(app).edit().putInt(WeavePrefs.PINYIN_HINT, 1).commit();idle()
        assertEquals(height, keyboard.metrics.kbHeight, 0.01f)
    }
}
