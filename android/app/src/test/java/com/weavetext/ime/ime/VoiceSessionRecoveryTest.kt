package com.weavetext.ime.ime

import android.Manifest
import android.app.Application
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.testing.FakeEngines
import com.weavetext.ime.testing.FakeEngine
import com.weavetext.ime.testing.FakeInputConnection
import com.weavetext.ime.testing.ScriptedRecognizer
import com.weavetext.ime.ui.keyboard.VoiceSession
import com.weavetext.ime.voice.VoiceListener
import com.weavetext.ime.voice.VoiceRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * 语音会话不会卡住：识别器不回话、收尾不给结果、出错后仍在录音、回车时结果还没到。
 * The voice session never hangs: a silent recognizer, no result after the stop, recording left on after an
 * error, Enter pressed before the result arrives.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceSessionRecoveryTest {
    /** 开始后什么也不回调的识别器。 A recognizer that never calls back. */
    private class SilentRecognizer : VoiceRecognizer {
        var starts = 0
        var cancels = 0
        override var isRunning = false
        override fun start(listener: VoiceListener): Boolean { starts++; isRunning = true; return true }
        override fun stop() {}
        override fun cancel() { cancels++; isRunning = false }
    }

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var edit: EditText
    private lateinit var controller: InputController

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        edit = EditText(app)
        val ic = edit.onCreateInputConnection(EditorInfo())
        controller = InputController { ic }
    }

    private fun idle(ms: Long) = ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS)

    /** 可处理删除键的编辑器，测试中每次退格都会真的删字。 An editor that handles DEL in these tests. */
    private fun deletableEditor(): FakeInputConnection {
        val ic = FakeInputConnection(edit)
        controller = InputController { ic }
        controller.attachEngine(FakeEngine().apply { predicts = true })
        controller.onStartInput(EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT; initialSelStart = 0; initialSelEnd = 0
        }, false)
        return ic
    }

    @Test fun hungFinalizeEndsAndMicWorksAgain() {
        val rec = SilentRecognizer()
        val session = VoiceSession(app, controller, recognizerProvider = { rec })
        assertTrue(session.start())
        session.stop()
        assertEquals(VoiceSession.State.FINALIZING, session.state)
        idle(VoiceSession.FINALIZE_LIMIT_MS + 100)
        assertEquals(VoiceSession.State.IDLE, session.state)
        assertEquals(1, rec.cancels)
        assertTrue(session.start())
        assertEquals(2, rec.starts)
    }

    @Test fun startTakesOverAStuckSession() {
        val rec = SilentRecognizer()
        val session = VoiceSession(app, controller, recognizerProvider = { rec })
        session.start()
        session.stop()
        // 刚点停止时再点不打断（可能马上就出结果）。 Right after stop a second tap doesn't interrupt.
        assertTrue(session.start())
        assertEquals(1, rec.starts)
        idle(VoiceSession.TAKEOVER_MS + 100)
        assertTrue(session.start())
        assertEquals(2, rec.starts)
        assertEquals(VoiceSession.State.CONNECTING, session.state)
    }

    @Test fun recognizerThatNeverConnectsEndsWithAnError() {
        val rec = SilentRecognizer()
        val session = VoiceSession(app, controller, recognizerProvider = { rec })
        session.start()
        idle(VoiceSession.CONNECT_LIMIT_MS + 100)
        assertEquals(VoiceSession.State.ERROR, session.state)
        assertFalse(rec.isRunning)
        // 关面板：回到空闲。 Closing the panel resets it.
        session.detach()
        assertEquals(VoiceSession.State.IDLE, session.state)
    }

    @Test fun errorStopsARecognizerThatKeepsRecording() {
        val rec = ScriptedRecognizer(FakeEngines())
        val session = VoiceSession(app, controller, recognizerProvider = { rec })
        session.start()
        rec.listener!!.onError("离线模型加载失败")
        idle(10)
        assertEquals(VoiceSession.State.ERROR, session.state)
        assertEquals(1, rec.cancels)
    }

    @Test fun settleCommitsShownTextAndDropsTheLateResult() {
        val rec = ScriptedRecognizer(FakeEngines())
        val session = VoiceSession(app, controller, recognizerProvider = { rec })
        session.start()
        val l = rec.listener!!
        l.onPartial("明天见")
        session.stop()
        session.settle()
        assertEquals(VoiceSession.State.IDLE, session.state)
        assertEquals("明天见", edit.text.toString())
        // 迟到的结果不再写进输入框。 A late result no longer reaches the editor.
        l.onFinal("明天见。")
        l.onEnd()
        assertEquals("明天见", edit.text.toString())
    }

    @Test fun noticeIsShownAndClearedOnNextStart() {
        val rec = ScriptedRecognizer(FakeEngines())
        val session = VoiceSession(app, controller, recognizerProvider = { rec })
        session.start()
        rec.listener!!.onNotice("已改用本地识别")
        assertEquals("已改用本地识别", session.notice)
        rec.endAll()
        session.start()
        assertEquals(null, session.notice)
    }

    @Test fun endedSessionCannotRestoreDeletedText() {
        val ic = deletableEditor()
        val rec = ScriptedRecognizer(FakeEngines())
        val session = VoiceSession(app, controller, recognizerProvider = { rec })
        session.start()
        val l = rec.listener!!
        l.onPartial("明天见")
        l.onFinal("明天见")
        rec.endAll()
        repeat(3) { controller.onBackspace(); idle(10) }
        assertEquals("", ic.text)
        l.onPartial("明天见")
        l.onFinal("明天见。")
        l.onReplace("明天见", "明天见。")
        l.onEnd()
        assertEquals("late callbacks must not restore deleted text", "", ic.text)
        assertTrue(controller.state.candidates.isEmpty())
        assertEquals(VoiceSession.State.IDLE, session.state)
    }

    @Test fun erroredSessionCannotRestoreDeletedText() {
        val ic = deletableEditor()
        val rec = ScriptedRecognizer(FakeEngines())
        val session = VoiceSession(app, controller, recognizerProvider = { rec })
        session.start()
        val l = rec.listener!!
        l.onPartial("明天见")
        l.onError("识别中断")
        // 连按删除，停止识别器的异步任务还没执行。 Delete before recognizer cleanup runs.
        repeat(3) { controller.onBackspace() }
        assertEquals("", ic.text)
        l.onFinal("明天见。")
        idle(10)
        assertEquals("", ic.text)
        assertEquals(VoiceSession.State.ERROR, session.state)
        assertFalse(rec.isRunning)
    }
}
