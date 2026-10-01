package com.weavetext.ime.ui

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.weavetext.ime.ui.keyboard.VoicePanel
import com.weavetext.ime.ui.keyboard.VoiceSession
import com.weavetext.ime.voice.MultiEngineResults
import com.weavetext.ime.voice.VoiceLanguage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoiceCandidateGestureTest : KeyboardSnapshotSupport() {
    private val texts = listOf(
        "中文为主，今天讨论 OpenAI API and GitHub 项目。".repeat(8),
        "完整的增强候选，hello world, on time and in time。".repeat(8),
        "第三个模型的完整文本，最后选择这一段。".repeat(8),
    )
    private fun results(): VoicePanel.VoiceView {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val (k, _) = keyboard(false)
        k.showPanel("voice")
        val panel = k.panel as VoicePanel
        val r = MultiEngineResults(listOf("a" to "中英标准", "b" to "中英增强", "c" to "Whisper"), "a")
        texts.forEachIndexed { i, text -> r.final(('a' + i).toString(), text) }
        r.stop(0)
        for (id in listOf("a", "b", "c")) r.end(id, 100)
        panel.session.previewResults(r)
        val v = panel.view
        v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1080, 800)
        render(v)
        return v
    }
    private fun render(v: View) = v.draw(Canvas(Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)))
    private fun event(v: View, action: Int, x: Float, y: Float) {
        val e = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), action, x, y, 0)
        v.dispatchTouchEvent(e); e.recycle()
    }
    private fun swipe(v: View, x1: Float, y1: Float, x2: Float, y2: Float) {
        event(v, MotionEvent.ACTION_DOWN, x1, y1)
        event(v, MotionEvent.ACTION_MOVE, x2, y2)
        event(v, MotionEvent.ACTION_UP, x2, y2)
        render(v)
    }
    @Test fun draggingAResultDoesNotCommitAndATapCommitsTheEntireTranscript() {
        val v = results()
        val y = kb!!.metrics.topBar + kb!!.metrics.dp(45f)
        swipe(v, 850f, y, 180f, y)
        val session = (kb!!.panel as VoicePanel).session
        assertEquals(VoiceSession.State.CHOOSING, session.state)
        assertEquals("", session.committed.toString())
        event(v, MotionEvent.ACTION_DOWN, 500f, y)
        event(v, MotionEvent.ACTION_UP, 500f, y)
        assertEquals(texts[0], session.committed.toString())
    }
    @Test fun expandedPreviewSwipesBetweenModelsAndCommitsTheViewedModel() {
        val v = results()
        val y = kb!!.metrics.topBar + kb!!.metrics.dp(45f)
        event(v, MotionEvent.ACTION_DOWN, 500f, y)
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)
        event(v, MotionEvent.ACTION_UP, 500f, y)
        render(v)
        swipe(v, 850f, 420f, 180f, 420f)
        swipe(v, 500f, 450f, 500f, 260f)
        val bottom = v.height - kb!!.metrics.dp(28f)
        event(v, MotionEvent.ACTION_DOWN, v.width - kb!!.metrics.dp(50f), bottom)
        event(v, MotionEvent.ACTION_UP, v.width - kb!!.metrics.dp(50f), bottom)
        assertEquals(texts[1], (kb!!.panel as VoicePanel).session.committed.toString())
    }
    @Test fun changingLanguageDoesNotCommitAnUnselectedCandidate() {
        val v = results()
        val y = kb!!.metrics.topBar + kb!!.metrics.dp(10f)
        event(v, MotionEvent.ACTION_DOWN, 900f, y)
        event(v, MotionEvent.ACTION_UP, 900f, y)
        assertEquals(VoiceLanguage.ENGLISH, engines.language)
        assertEquals("", (kb!!.panel as VoicePanel).session.committed.toString())
    }
}
