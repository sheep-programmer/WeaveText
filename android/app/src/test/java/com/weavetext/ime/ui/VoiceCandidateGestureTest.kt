package com.weavetext.ime.ui

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.github.takahirom.roborazzi.captureRoboImage
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
        "中文为主，今天讨论 API and the project。".repeat(8),
        "完整的增强候选，hello world, on time and in time。".repeat(8),
        "第三个引擎的完整文本，最后选择这一段。".repeat(8),
    )
    private val ids = listOf("a", "b", "c")
    private fun data(count: Int = 3, pending: Set<String> = emptySet()): MultiEngineResults {
        val r = MultiEngineResults(ids.take(count).mapIndexed { i, id -> id to "语音引擎${i + 1}" }, "a")
        texts.take(count).forEachIndexed { i, text -> r.final(ids[i], text) }
        r.stop(0)
        ids.take(count).filterNot { it in pending }.forEach { r.end(it, 100) }
        return r
    }
    private fun results(r: MultiEngineResults = data(), width: Int = 1080, height: Int = 800, fontScaleValue: Float? = null): VoicePanel.VoiceView {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        fontScaleValue?.let { fontScale(it) }
        val (k, _) = keyboard(false)
        k.showPanel("voice")
        val panel = k.panel as VoicePanel
        panel.session.previewResults(r)
        val v = panel.view
        v.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, width, height)
        render(v)
        return v
    }
    private val session get() = (kb!!.panel as VoicePanel).session
    private fun render(v: View): Bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
    private var gestureStart = 0L
    private fun event(v: View, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) gestureStart = now
        val e = MotionEvent.obtain(gestureStart, now, action, x, y, 0)
        v.dispatchTouchEvent(e)
        e.recycle()
    }
    private fun tap(v: View, rect: RectF) {
        event(v, MotionEvent.ACTION_DOWN, rect.centerX(), rect.centerY())
        event(v, MotionEvent.ACTION_UP, rect.centerX(), rect.centerY())
        render(v)
    }
    private fun swipe(v: View, x1: Float, y1: Float, x2: Float, y2: Float) {
        event(v, MotionEvent.ACTION_DOWN, x1, y1)
        event(v, MotionEvent.ACTION_MOVE, (x1 + x2) / 2, (y1 + y2) / 2)
        event(v, MotionEvent.ACTION_MOVE, x2, y2)
        event(v, MotionEvent.ACTION_UP, x2, y2)
        ShadowLooper.idleMainLooper(250, TimeUnit.MILLISECONDS)
        render(v)
    }
    // Reflection lets the first red run compile before the production geometry hooks are added.
    private fun hook(v: View, name: String): Any? = v.javaClass.getMethod(name).invoke(v)
    @Suppress("UNCHECKED_CAST")
    private fun cards(v: View) = hook(v, "resultCardBounds") as List<Pair<String, RectF>>
    private fun actions(v: View): List<RectF> {
        val m = kb!!.metrics
        val cy = v.height - m.dp(28f)
        return listOf(
            RectF(m.dp(12f), cy - m.dp(20f), m.dp(100f), cy + m.dp(20f)),
            RectF(v.width / 2f - m.dp(24f), cy - m.dp(24f), v.width / 2f + m.dp(24f), cy + m.dp(24f)),
            RectF(v.width - m.dp(100f), cy - m.dp(20f), v.width - m.dp(12f), cy + m.dp(20f)),
        )
    }
    private fun selected(v: View) = hook(v, "getSelectedResultId") as String?
    private fun preview(v: View) = hook(v, "getResultPreviewId") as String?
    private fun selectedCard(v: View) = cards(v).first { it.first == selected(v) }.second
    private fun swipeLeft(v: View) {
        val card = selectedCard(v)
        swipe(v, 850f, card.centerY(), 180f, card.centerY())
    }

    @Test fun fullTranscriptPreviewSnapshot() {
        val v = results()
        tap(v, selectedCard(v))
        assertEquals("a", preview(v))
        v.captureRoboImage(java.io.File(dir, "voice_result_preview.png").path)
        assertEquals("", session.committed.toString())
    }

    @Test fun narrowFontGeometryKeepsThreeCardsAndBodyAreaUsable() {
        val v = results(width = 720, height = 620, fontScaleValue = 1.3f)
        val bitmap = render(v)
        assertEquals(3, cards(v).size)
        cards(v).forEach { (_, rect) ->
            assertTrue(rect.width() > 0f && rect.height() > 0f)
            assertTrue("Short cards must retain space for transcript text", rect.height() > kb!!.metrics.dp(46f))
            val bodyTop = (rect.top + kb!!.metrics.dp(32f)).toInt()
            val bodyBottom = (rect.bottom - kb!!.metrics.dp(2f)).toInt()
            assertTrue("Each short card must actually draw transcript text", (bodyTop until bodyBottom).any { y ->
                (rect.left.toInt() + 4 until rect.right.toInt() - 4).any { x -> bitmap.getPixel(x, y) == kb!!.palette.label }
            })
        }
    }

    @Test fun showsThreeCompleteCardsSideBySide() {
        val v = results()
        val initial = cards(v)
        assertEquals(3, initial.size)
        assertEquals(initial[0].second.width(), initial[1].second.width(), 0.1f)
        assertEquals(initial[1].second.width(), initial[2].second.width(), 0.1f)
        assertEquals(initial[0].second.top, initial[1].second.top, 0.1f)
        assertEquals(initial[1].second.top, initial[2].second.top, 0.1f)
        initial.forEach { (_, rect) ->
            assertTrue(rect.width() > 0 && rect.height() > 0)
            assertTrue(rect.left >= 0 && rect.top >= kb!!.metrics.topBar)
            assertTrue(rect.right <= v.width && rect.bottom <= actions(v)[0].top)
        }
        for (i in initial.indices) for (j in i + 1 until initial.size) {
            assertFalse("Card bounds must not overlap", RectF.intersects(initial[i].second, initial[j].second))
        }
        assertEquals("a", selected(v))
        repeat(2) { swipeLeft(v) }
        assertEquals("c", selected(v))
        assertEquals(3, cards(v).size)
        assertEquals("", session.committed.toString())
        tap(v, actions(v)[2])
        assertEquals(texts[2], session.committed.toString())
    }

    @Test fun tappingACardOpensTheFullTranscriptWithoutCommitting() {
        val v = results()
        // The old vertical list commits this tap; the card UI must only open a preview.
        val y = kb!!.metrics.topBar + kb!!.metrics.dp(45f)
        tap(v, RectF(490f, y - 1f, 510f, y + 1f))
        assertEquals("", session.committed.toString())
        assertEquals(VoiceSession.State.CHOOSING, session.state)
        assertEquals("b", preview(v))
        assertEquals("b", selected(v))
        assertEquals(VoiceSession.State.CHOOSING, session.state)
        assertEquals("", session.committed.toString())
        tap(v, actions(v)[0])
        assertNull(preview(v))
        assertEquals("b", selected(v))
        assertEquals("", session.committed.toString())
    }

    @Test fun horizontalSwipesSelectAndFooterCommitsTheMatchingTranscript() {
        val v = results()
        swipeLeft(v)
        assertEquals("b", selected(v))
        assertEquals("", session.committed.toString())
        val y = selectedCard(v).centerY()
        swipe(v, 180f, y, 850f, y)
        assertEquals("a", selected(v))
        swipeLeft(v)
        tap(v, actions(v)[2])
        assertEquals(texts[1], session.committed.toString())
    }

    @Test fun expandedPreviewScrollsVerticallyAndSwitchesHorizontallyBeforeConfirmation() {
        val v = results()
        tap(v, selectedCard(v))
        swipe(v, 850f, 420f, 180f, 420f)
        assertEquals("b", preview(v))
        val beforeScroll = render(v)
        swipe(v, 500f, 450f, 500f, 260f)
        val afterScroll = render(v)
        assertFalse("Vertical drag must scroll the transcript", beforeScroll.sameAs(afterScroll))
        assertEquals("b", preview(v))
        assertEquals("", session.committed.toString())
        tap(v, actions(v)[2])
        assertEquals(texts[1], session.committed.toString())
    }

    @Test fun cancelAfterSwipingDiscardsAllCandidates() {
        val v = results()
        swipeLeft(v)
        tap(v, actions(v)[0])
        assertEquals(VoiceSession.State.IDLE, session.state)
        assertEquals("", session.committed.toString())
    }

    @Test fun cancelledSwipeDoesNotSelectOrCommit() {
        val v = results()
        val card = selectedCard(v)
        event(v, MotionEvent.ACTION_DOWN, 850f, card.centerY())
        event(v, MotionEvent.ACTION_MOVE, 180f, card.centerY())
        event(v, MotionEvent.ACTION_CANCEL, 180f, card.centerY())
        render(v)
        assertEquals("a", selected(v))
        assertEquals("", session.committed.toString())
    }

    @Test fun selectedEngineSurvivesQualityReorderingAndFooterCommitsItsText() {
        val r = data(pending = setOf("c"))
        val v = results(r)
        swipeLeft(v)
        assertEquals("b", selected(v))
        r.score("c", 0.99f)
        r.end("c", 120)
        render(v)
        assertEquals("c", r.rows().first().id)
        assertEquals("b", selected(v))
        tap(v, actions(v)[2])
        assertEquals(texts[1], session.committed.toString())
    }

    @Test fun footerConfirmsTheEngineSelectedWhenThePressStarted() {
        val r = data(pending = setOf("c"))
        val v = results(r)
        val button = actions(v)[2]
        assertEquals("a", selected(v))
        event(v, MotionEvent.ACTION_DOWN, button.centerX(), button.centerY())
        r.score("c", 0.99f)
        r.end("c", 120)
        render(v)
        event(v, MotionEvent.ACTION_UP, button.centerX(), button.centerY())
        assertEquals(texts[0], session.committed.toString())
    }

    @Test fun footerReleaseCannotConfirmAReplacementSession() {
        val v = results()
        val button = actions(v)[2]
        event(v, MotionEvent.ACTION_DOWN, button.centerX(), button.centerY())
        session.previewResults(data())
        render(v)
        event(v, MotionEvent.ACTION_UP, button.centerX(), button.centerY())
        assertEquals(VoiceSession.State.CHOOSING, session.state)
        assertEquals("", session.committed.toString())
    }

    @Test fun followsSessionDefaultUntilTheFirstSelectionGesture() {
        val r = data(pending = setOf("a"))
        val v = results(r)
        assertEquals("b", selected(v))
        r.end("a", 120)
        render(v)
        assertEquals(r.rows()[session.defaultRow].id, selected(v))
        assertEquals("a", selected(v))
    }

    @Test fun pendingAndFailedCardsCanBeInspectedButCannotBeCommitted() {
        val r = data(pending = setOf("b", "c"))
        r.error("c", "识别失败", 100)
        val v = results(r)
        tap(v, cards(v).first { it.first == "b" }.second)
        assertEquals("b", preview(v))
        tap(v, actions(v)[2])
        assertEquals(VoiceSession.State.CHOOSING, session.state)
        assertEquals("", session.committed.toString())
        swipe(v, 850f, 420f, 180f, 420f)
        assertEquals("c", preview(v))
        tap(v, actions(v)[2])
        assertEquals(VoiceSession.State.CHOOSING, session.state)
        assertEquals("", session.committed.toString())
    }

    @Test fun changingLanguageDoesNotCommitAnUnselectedCandidate() {
        val v = results()
        val y = kb!!.metrics.topBar + kb!!.metrics.dp(10f)
        tap(v, RectF(890f, y - 1f, 910f, y + 1f))
        assertEquals(VoiceLanguage.ENGLISH, engines.language)
        assertEquals("", session.committed.toString())
    }
}
