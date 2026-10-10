package com.weavetext.ime.ui

import android.os.SystemClock
import android.view.MotionEvent
import com.weavetext.ime.ime.ImeState
import com.weavetext.ime.ui.keyboard.Key
import com.weavetext.ime.ui.keyboard.KeyCode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 候选栏：联想词与打字时的候选同一行高度；联想词右侧是叉，点了清空；回车收起本地候选。
 * Candidate bar: predictions share the composing row's height; predictions get a cross that clears them; Enter
 * drops local candidates.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-420dpi")
class CandidateBarTest : KeyboardSnapshotSupport() {
    private fun predictions() = ImeState(preedit = "", candidates = nihao, totalCandidates = nihao.size, composing = false,
        chinese = true, schema = "pinyin", engineReady = true)

    private fun tapBar(x: Float, y: Float) {
        val bar = kb!!.topBar
        val t = SystemClock.uptimeMillis()
        for (a in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val e = MotionEvent.obtain(t, t, a, x, y, 0)
            try { bar.dispatchTouchEvent(e) } finally { e.recycle() }
        }
        idle()
    }

    /** 栏上有没有这个无障碍标签（虚拟节点编号都在 0..199）。 Whether a virtual node carries this label. */
    private fun label(bar: android.view.View, text: String): Boolean {
        val p = bar.accessibilityNodeProvider ?: return false
        return (0 until 200).any { p.createAccessibilityNodeInfo(it)?.contentDescription?.toString() == text }
    }

    @Test fun predictionsGetACrossThatClearsLocalCandidates() {
        val (keyboard, controller) = keyboard(false)
        controller.previewState(composing()); idle()
        assertFalse(label(keyboard.topBar, "清空候选"))
        controller.previewState(predictions()); idle()
        assertTrue(label(keyboard.topBar, "清空候选"))
        controller.previewState(predictions().copy(candidates = emptyList(), totalCandidates = 0)); idle()
        controller.onCalc!!(listOf("640", "六百四十")); idle()
        assertEquals("640", keyboard.topBar.candidateAt(0))
        tapBar(keyboard.topBar.width - keyboard.metrics.dp(20f), keyboard.topBar.height / 2f)
        assertNull(keyboard.topBar.candidateAt(0))
    }

    @Test fun enterDropsLocalCandidates() {
        val (keyboard, controller) = keyboard(false)
        controller.onCalc!!(listOf("640")); idle()
        assertEquals("640", keyboard.topBar.candidateAt(0))
        keyboard.onKey(Key(KeyCode.ENTER)); idle()
        assertNull(keyboard.topBar.candidateAt(0))
    }
}
