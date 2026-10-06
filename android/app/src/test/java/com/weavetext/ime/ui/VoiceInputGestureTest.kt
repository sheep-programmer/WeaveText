package com.weavetext.ime.ui

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.testing.ScriptedRecognizer
import com.weavetext.ime.ui.keyboard.VoicePanel
import com.weavetext.ime.ui.keyboard.VoiceSession
import com.weavetext.ime.voice.VoiceLanguage
import com.weavetext.ime.voice.VoiceAutoDownload
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 点按/按住共用同一停止规则，且切换语言会使旧会话失效。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoiceInputGestureTest : KeyboardSnapshotSupport() {
    private var previousEnsure: ((Context) -> Boolean)? = null
    private var preparations = 0
    @Before fun isolateDownloads() {
        previousEnsure = VoiceAutoDownload.ensureOverride
        VoiceAutoDownload.ensureOverride = { preparations++; false }
    }
    @After fun restoreDownloads() { VoiceAutoDownload.ensureOverride = previousEnsure }
    private fun render(v: View) {
        v.draw(Canvas(Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)))
    }

    private fun event(v: View, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, action, x, y, 0).also { e ->
            v.dispatchTouchEvent(e)
            e.recycle()
        }
    }

    private fun micCenter(v: View): Pair<Float, Float> {
        val m = kb!!.metrics
        val bottom = v.height - m.dp(28f)
        val top = bottom - minOf(m.dp(104f), (v.height - m.topBar) * 0.5f)
        return v.width / 2f to (top + bottom) / 2f - m.dp(9f)
    }

    private fun voiceView(mode: String, rec: ScriptedRecognizer): VoicePanel.VoiceView {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        VoiceAccess.recognizerProvider = { rec }
        val (k, _) = keyboard(false) { putString(WeavePrefs.VOICE_MODE, mode) }
        k.showPanel("voice")
        val v = (k.panel as VoicePanel).view
        v.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1080, 900)
        render(v)
        return v
    }

    @Test fun tapModeStartsOnFirstTapAndStopsOnSecondTap() {
        val rec = ScriptedRecognizer(engines)
        val v = voiceView("tap", rec)
        val (x, y) = micCenter(v)

        event(v, MotionEvent.ACTION_DOWN, x, y)
        event(v, MotionEvent.ACTION_UP, x, y)
        assertEquals(VoiceSession.State.CONNECTING, (kb!!.panel as VoicePanel).session.state)
        assertTrue(rec.listener != null)

        event(v, MotionEvent.ACTION_DOWN, x, y)
        event(v, MotionEvent.ACTION_UP, x, y)
        assertEquals(VoiceSession.State.FINALIZING, (kb!!.panel as VoicePanel).session.state)
        assertEquals(1, rec.stops)
        assertEquals("识别中 · 点击取消并重录", v.microphoneHint())

        val old = rec.listener!!
        event(v, MotionEvent.ACTION_DOWN, x, y)
        event(v, MotionEvent.ACTION_UP, x, y)
        assertEquals(1, rec.cancels)
        assertNotSame(old, rec.listener)
        assertEquals(VoiceSession.State.CONNECTING, (kb!!.panel as VoicePanel).session.state)
        old.onFinal("旧结果")
        assertEquals("", (kb!!.panel as VoicePanel).session.committed.toString())
    }

    @Test fun holdModeStopsOnReleaseAndCancelsAfterUpwardSlide() {
        val rec = ScriptedRecognizer(engines)
        val v = voiceView("hold", rec)
        val (x, y) = micCenter(v)

        event(v, MotionEvent.ACTION_DOWN, x, y)
        assertTrue((kb!!.panel as VoicePanel).session.active)
        event(v, MotionEvent.ACTION_UP, x, y)
        assertEquals(VoiceSession.State.FINALIZING, (kb!!.panel as VoicePanel).session.state)
        assertEquals(1, rec.stops)
        assertEquals("识别中 · 按住取消并重录", v.microphoneHint())

        // A fresh hold is cancelled by the documented upward escape gesture.
        val old = rec.listener!!
        event(v, MotionEvent.ACTION_DOWN, x, y)
        assertNotSame(old, rec.listener)
        assertEquals(1, rec.cancels)
        event(v, MotionEvent.ACTION_MOVE, x, y - kb!!.metrics.dp(65f))
        event(v, MotionEvent.ACTION_UP, x, y - kb!!.metrics.dp(65f))
        assertEquals(VoiceSession.State.IDLE, (kb!!.panel as VoicePanel).session.state)
        assertEquals(2, rec.cancels)
    }

    @Test fun systemTouchCancellationDiscardsAHeldRecording() {
        val rec = ScriptedRecognizer(engines)
        val v = voiceView("hold", rec)
        val (x, y) = micCenter(v)
        event(v, MotionEvent.ACTION_DOWN, x, y)
        val old = rec.listener!!
        old.onPartial("不要提交")
        event(v, MotionEvent.ACTION_CANCEL, x, y)
        old.onFinal("迟到结果")
        assertEquals(1, rec.cancels)
        assertEquals(0, rec.stops)
        assertEquals(VoiceSession.State.IDLE, (kb!!.panel as VoicePanel).session.state)
        assertEquals("", (kb!!.panel as VoicePanel).session.committed.toString())
    }

    @Test fun toolbarStartsAnInstalledRecognizerAndChecksModelsBeforeCapture() {
        val rec = ScriptedRecognizer(engines)
        val v = voiceView("tap", rec)
        val panel = kb!!.panel as VoicePanel
        val before = preparations
        panel.startFromToolbar()
        assertEquals(before + 1, preparations)
        assertTrue(rec.isRunning)
        assertEquals(VoiceSession.State.CONNECTING, panel.session.state)
        assertEquals("正在加载离线模型…", v.microphoneHint())
    }

    @Test fun holdingWithoutAnEnginePreparesModelsAndKeepsDownloadAndImportActions() {
        engines.plugins = emptyList()
        val rec = ScriptedRecognizer(engines)
        val v = voiceView("hold", rec)
        val session = (kb!!.panel as VoicePanel).session
        val before = preparations
        var guided = 0
        session.onNoEngine = { guided++ }
        val (x, y) = micCenter(v)
        event(v, MotionEvent.ACTION_DOWN, x, y)
        event(v, MotionEvent.ACTION_UP, x, y)
        assertEquals(before + 1, preparations)
        assertEquals(1, guided)
        assertEquals(null, rec.listener)
        assertFalse(rec.isRunning)
        assertEquals(VoiceSession.State.IDLE, session.state)

        // 导入不因窄屏而被丢弃；分别检查按钮实际打开的设置路由。
        v.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 720, 900)
        render(v)
        val actions = v.noEngineActionBounds()
        assertTrue(actions.all { !it.isEmpty && it.left >= 0 && it.right <= v.width })
        assertTrue(actions[0].right < actions[1].left)
        for ((bounds, route) in actions.zip(listOf("voice/upgrade", "voice"))) {
            event(v, MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY())
            event(v, MotionEvent.ACTION_UP, bounds.centerX(), bounds.centerY())
            assertEquals("weavetext://settings/$route", shadowOf(activity).nextStartedActivity.data.toString())
        }
    }

    @Test fun changingLanguageCancelsTheOldSessionAndDropsLateResults() {
        val rec = ScriptedRecognizer(engines)
        val v = voiceView("tap", rec)
        val session = (kb!!.panel as VoicePanel).session
        val (micX, micY) = micCenter(v)
        event(v, MotionEvent.ACTION_DOWN, micX, micY)
        event(v, MotionEvent.ACTION_UP, micX, micY)
        val listener = rec.listener!!
        listener.onPartial("旧会话")

        // The third language chip is English; geometry is rebuilt during draw.
        val x = v.width * 5f / 6f
        val y = kb!!.metrics.topBar + kb!!.metrics.dp(10f)
        event(v, MotionEvent.ACTION_DOWN, x, y)
        event(v, MotionEvent.ACTION_UP, x, y)

        assertEquals(VoiceLanguage.ENGLISH, engines.language)
        assertEquals(1, rec.cancels)
        assertEquals(VoiceSession.State.IDLE, session.state)
        listener.onFinal("晚到结果")
        assertEquals("", session.committed.toString())
    }
}
