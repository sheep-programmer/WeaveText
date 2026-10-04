package com.weavetext.ime.ui

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.ui.keyboard.VoicePanel
import com.weavetext.ime.ui.keyboard.VoiceSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 语音字幕：说话时一行字幕（短时居中、变长后左渐隐），点一下看全文、再点返回；说完上屏后字幕消失，设置里可以留着。
 * The voice caption: one line while speaking, tap for the full text and back; it disappears once committed unless the
 * setting keeps it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoiceCaptionTest : KeyboardSnapshotSupport() {
    private val long = "今天下午我们讨论一下 GitHub 上的这个 pull request，然后再看看 release notes 要不要补充，最后确认发布时间。"

    private fun panel(keep: Boolean = false): VoicePanel.VoiceView {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val (k, _) = keyboard(false) { putBoolean(WeavePrefs.VOICE_KEEP_TEXT, keep) }
        k.showPanel("voice")
        val v = (k.panel as VoicePanel).view
        v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1080, 900)
        return v
    }
    private val session get() = (kb!!.panel as VoicePanel).session
    private fun render(v: View) = v.draw(Canvas(Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)))
    /** 等新字淡入结束再截图。 Let the fade-in finish before capturing. */
    private fun settle(v: View) {
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        render(v)
    }
    private fun tap(v: View, x: Float, y: Float) {
        for (a in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val e = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), a, x, y, 0)
            v.dispatchTouchEvent(e); e.recycle()
        }
        render(v)
    }

    @Test fun compactCaptionKeepsTheWholeGlyphBelowTheLanguageButtons() {
        val v = panel()
        for (height in listOf(600, 768, 900)) {
            v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, 1080, height)
            session.preview(VoiceSession.State.LISTENING, "", "权力 English", 0.5f)
            render(v)
            val (languageBottom, glyphTop, glyphBottom, captionBottom) = v.captionVerticalBounds()
            assertTrue("caption overlaps language buttons at height $height", glyphTop > languageBottom)
            assertTrue("caption clips glyph bottoms at height $height", glyphBottom <= captionBottom)
        }
    }

    @Test fun shortCaptionIsCentredAndLongOneKeepsTheLatestText() {
        val v = panel()
        session.preview(VoiceSession.State.LISTENING, "你好，", "今天", 0.5f)
        render(v)
        assertTrue(v.captionShown)
        settle(v)
        snap("voice_caption_short")
        session.preview(VoiceSession.State.LISTENING, long, "", 0.5f)
        render(v)
        settle(v)
        snap("voice_caption_long")
    }

    @Test fun tapOpensTheFullTextPageAndTapAgainReturns() {
        val v = panel()
        session.preview(VoiceSession.State.LISTENING, long, "", 0.5f)
        render(v)
        assertFalse(v.fullTextOpen)
        val (x, y) = v.captionCenter().let { it[0] to it[1] }
        tap(v, x, y)
        assertTrue(v.fullTextOpen)
        snap("voice_fulltext")
        val (x2, y2) = v.captionCenter().let { it[0] to it[1] }
        tap(v, x2, y2)
        assertFalse(v.fullTextOpen)
    }

    @Test fun captionLeavesOnceTheSpeechIsCommittedUnlessKept() {
        val v = panel(keep = false)
        session.preview(VoiceSession.State.LISTENING, long, "", 0.5f)
        render(v)
        assertTrue(v.captionShown)
        val (x, y) = v.captionCenter().let { it[0] to it[1] }
        tap(v, x, y)
        assertTrue(v.fullTextOpen)
        // 说完、已上屏：字幕与整页一起消失。 Finished and committed: the caption and the page go together.
        session.preview(VoiceSession.State.IDLE, long, "", 0f)
        render(v)
        assertFalse(v.captionShown)
        assertFalse(v.fullTextOpen)
        assertEquals(long, session.committed.toString())
    }

    @Test fun keepSettingLeavesTheTextOnThePanel() {
        val v = panel(keep = true)
        session.preview(VoiceSession.State.IDLE, long, "", 0f)
        render(v)
        assertTrue(v.captionShown)
        snap("voice_caption_kept")
    }
}
