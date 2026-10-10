package com.weavetext.ime.ime

import android.Manifest
import android.app.Application
import android.content.Context
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
import com.weavetext.ime.voice.VoiceAutoDownload
import org.junit.After
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

    /** Faults happen after acquiring capture, with synchronous callbacks during teardown. */
    private class FaultyRecognizer : VoiceRecognizer {
        lateinit var listener: VoiceListener
        var startAction: (VoiceListener) -> Boolean = { true }
        var stopFailure = false
        var cancelFailure = false
        var cancels = 0
        override var isRunning = false
        override fun start(listener: VoiceListener): Boolean {
            this.listener = listener
            isRunning = true
            return startAction(listener)
        }
        override fun stop() {
            if (stopFailure) error("停止失败")
            listener.onEnd()
        }
        override fun cancel() {
            cancels++
            isRunning = false
            listener.onFinal("取消时的迟到结果")
            listener.onEnd()
            if (cancelFailure) error("取消失败")
        }
    }

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var edit: EditText
    private lateinit var controller: InputController
    private var previousEnsure: ((Context) -> Boolean)? = null

    @Before fun setUp() {
        previousEnsure = VoiceAutoDownload.ensureOverride
        VoiceAutoDownload.ensureOverride = { false }
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        edit = EditText(app)
        val ic = edit.onCreateInputConnection(EditorInfo())
        controller = InputController { ic }
    }
    @After fun restoreDownloads() { VoiceAutoDownload.ensureOverride = previousEnsure }

    private fun idle(ms: Long) = ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS)

    @Test fun falseStartAfterReadyReleasesCaptureAndAllowsRetry() {
        val rec = FaultyRecognizer().apply { startAction = { it.onReady(false); false } }
        val session = VoiceSession(app, controller) { rec }
        assertFalse(session.start())
        assertEquals(VoiceSession.State.ERROR, session.state)
        assertFalse(rec.isRunning)
        assertEquals(1, rec.cancels)
        val old = rec.listener
        old.onPartial("旧字幕"); old.onFinal("旧结果"); old.onEnd()
        assertEquals("", edit.text.toString())
        rec.startAction = { true }
        assertTrue(session.start())
        old.onError("旧错误")
        rec.listener.onFinal("重试成功")
        rec.listener.onEnd()
        assertEquals("重试成功", edit.text.toString())
        assertEquals(VoiceSession.State.IDLE, session.state)
    }

    @Test fun thrownStartReleasesCaptureKeepsShownTextAndAllowsRetry() {
        val rec = FaultyRecognizer().apply {
            startAction = { it.onPartial("已经听到"); error("启动失败") }
        }
        val session = VoiceSession(app, controller) { rec }
        assertFalse(session.start())
        assertEquals(VoiceSession.State.ERROR, session.state)
        assertFalse(rec.isRunning)
        assertEquals(1, rec.cancels)
        assertEquals("已经听到", edit.text.toString())
        rec.startAction = { true }
        assertTrue(session.start())
        session.cancel()
        assertEquals("已经听到", edit.text.toString())
    }

    @Test fun providerFailureIsAnErrorAndCanBeRetried() {
        val rec = SilentRecognizer()
        var fail = true
        val session = VoiceSession(app, controller) { if (fail) error("宿主不可用") else rec }
        assertFalse(session.start())
        assertEquals(VoiceSession.State.ERROR, session.state)
        assertFalse(session.active)
        fail = false
        assertTrue(session.start())
        session.cancel()
    }

    @Test fun stopFailurePreservesInterimTextAndReleasesCapture() {
        val rec = FaultyRecognizer().apply { stopFailure = true }
        val session = VoiceSession(app, controller) { rec }
        session.start()
        rec.listener.onPartial("保留半句")
        session.stop()
        assertEquals(VoiceSession.State.ERROR, session.state)
        assertEquals("保留半句", edit.text.toString())
        assertEquals("保留半句", session.committed.toString())
        assertFalse(rec.isRunning)
        rec.listener.onFinal("不要重复")
        assertEquals("保留半句", edit.text.toString())
    }

    @Test fun cancelFailureStillDropsComposingAndAllLateCallbacks() {
        val rec = FaultyRecognizer().apply { cancelFailure = true }
        val session = VoiceSession(app, controller) { rec }
        session.start()
        rec.listener.onPartial("取消这句")
        rec.listener.onNotice("旧提示")
        rec.listener.onLevel(0.8f)
        session.cancel()
        assertEquals(VoiceSession.State.IDLE, session.state)
        assertEquals("", edit.text.toString())
        assertEquals(null, session.notice)
        assertTrue(session.levels.all { it == 0f })
        rec.listener.onPartial("迟到字幕"); rec.listener.onFinal("迟到结果")
        rec.listener.onReplace("", "迟到修正"); rec.listener.onError("迟到错误")
        assertEquals("", edit.text.toString())
        assertEquals(null, session.error)
    }

    @Test fun finalizeTimeoutPreservesPartialExactlyOnce() {
        val rec = ScriptedRecognizer(FakeEngines())
        val session = VoiceSession(app, controller) { rec }
        session.start()
        val old = rec.listener!!
        old.onFinal("前半句"); old.onPartial("后半句")
        session.stop()
        idle(VoiceSession.FINALIZE_LIMIT_MS + 1)
        old.onFinal("后半句。"); old.onEnd()
        assertEquals("前半句后半句", edit.text.toString())
        assertEquals("前半句后半句", session.committed.toString())
        assertEquals(VoiceSession.State.IDLE, session.state)
        assertFalse(rec.isRunning)
    }

    @Test fun failedSessionReleasesCaptureBeforeImmediateRetry() {
        val first = ScriptedRecognizer(FakeEngines())
        val next = SilentRecognizer()
        var current: VoiceRecognizer = first
        val session = VoiceSession(app, controller) { current }
        session.start()
        first.listener!!.onError("识别中断")
        current = next
        assertTrue(session.start()) // No looper idle between error and retry.
        idle(1)
        assertFalse("旧麦克风必须已释放", first.isRunning)
        assertTrue("旧清理不能取消新录音", next.isRunning)
        session.cancel()
    }

    @Test fun detachedFinalCannotWriteToAnotherAppOrANewInputEpoch() {
        val first = EditText(app)
        val next = EditText(app)
        var ic = first.onCreateInputConnection(EditorInfo())
        controller = InputController { ic }
        val info = EditorInfo().apply { packageName = "example.first"; fieldId = 7; inputType = InputType.TYPE_CLASS_TEXT }
        controller.onStartInput(info, false)
        val rec = ScriptedRecognizer(FakeEngines())
        val session = VoiceSession(app, controller) { rec }
        session.start()
        val old = rec.listener!!
        old.onPartial("旧输入框")
        session.detach()
        ic = next.onCreateInputConnection(EditorInfo())
        controller.onStartInput(EditorInfo().apply { packageName = "example.next"; fieldId = 7; inputType = InputType.TYPE_CLASS_TEXT }, false)
        old.onPartial("迟到字幕"); old.onFinal("迟到结果"); old.onReplace("", "迟到修正"); old.onEnd()
        assertEquals("", next.text.toString())

        // Returning to the same package and field still creates a different editor epoch.
        controller.onStartInput(info, false)
        session.start()
        val previousEpoch = rec.listener!!
        session.stop()
        controller.onStartInput(info, true)
        previousEpoch.onFinal("同字段迟到结果"); previousEpoch.onEnd()
        assertEquals("", next.text.toString())
    }

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

    @Test fun startingAgainDuringFinalizingImmediatelyBeginsANewRecording() {
        val rec = SilentRecognizer()
        val session = VoiceSession(app, controller, recognizerProvider = { rec })
        session.start()
        session.stop()
        // 工具栏/空格入口调用 start 也立即重录，与面板的 restart 行为一致。
        assertTrue(session.start())
        assertEquals(2, rec.starts)
        assertEquals(1, rec.cancels)
        assertEquals(VoiceSession.State.CONNECTING, session.state)
    }

    @Test fun explicitRestartImmediatelyCancelsFinalizingAndDropsAllOldCallbacks() {
        val rec = ScriptedRecognizer(FakeEngines())
        val session = VoiceSession(app, controller, recognizerProvider = { rec })
        session.start()
        val old = rec.listener!!
        old.onFinal("已确定")
        old.onPartial("未确认")
        session.stop()
        assertEquals(VoiceSession.State.FINALIZING, session.state)

        assertTrue(session.restart()) // 不必等八秒，取消的同步 onEnd 也不能落定旧 partial。
        assertEquals(1, rec.cancels)
        assertEquals(VoiceSession.State.CONNECTING, session.state)
        assertEquals("已确定", edit.text.toString())
        old.onPartial("旧字幕")
        old.onFinal("旧终稿")
        old.onReplace("已确定", "不该覆写")
        old.onError("旧错误")
        old.onEnd()
        assertEquals(VoiceSession.State.CONNECTING, session.state)
        assertEquals("", session.partial)
        assertEquals(null, session.error)
        assertEquals("已确定", edit.text.toString())

        rec.listener!!.onReady(false)
        rec.listener!!.onPartial("重新录音")
        idle(VoiceSession.FINALIZE_LIMIT_MS + 1)
        assertEquals("旧收尾计时器不能结束新录音", VoiceSession.State.LISTENING, session.state)
        rec.listener!!.onFinal("重新录音")
        rec.endAll()
        assertEquals("已确定重新录音", edit.text.toString())
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

    @Test fun postHocCorrectionReplacesTheLastCommittedVoiceSegmentBeforeEnd() {
        val rec = ScriptedRecognizer(FakeEngines())
        val session = VoiceSession(app, controller, recognizerProvider = { rec })
        session.start()
        val l = rec.listener!!
        l.onFinal("识别错误")
        l.onReplace("识别错误", "识别正确")

        assertEquals("识别正确", edit.text.toString())
        assertEquals("识别正确", session.committed.toString())
        l.onEnd()
    }

    @Test fun thinkingPauseDoesNotStopRecordingOrLoseTheNextSentence() {
        val rec = ScriptedRecognizer(FakeEngines())
        val session = VoiceSession(app, controller, recognizerProvider = { rec })
        session.start()
        val l = rec.listener!!
        l.onReady(false)
        l.onPartial("我想")
        repeat(200) { l.onLevel(0f); idle(40) } // eight-second thinking pause
        assertEquals(VoiceSession.State.LISTENING, session.state)
        assertTrue(rec.isRunning)
        l.onFinal("我想")
        l.onPartial("继续说")
        l.onFinal("继续说")
        session.stop()
        l.onEnd()
        assertEquals("我想继续说", edit.text.toString())
    }

    @Test fun quietSpeechDoesNotStopAndLevelsDoNotPretendModelIsReady() {
        val rec = ScriptedRecognizer(FakeEngines())
        val session = VoiceSession(app, controller, recognizerProvider = { rec })
        session.start()
        val l = rec.listener!!
        l.onLevel(0.01f)
        assertEquals(VoiceSession.State.CONNECTING, session.state)
        idle(20_000)
        assertEquals(VoiceSession.State.CONNECTING, session.state)
        l.onReady(false)
        repeat(1000) { l.onLevel(0.01f); idle(40) }
        assertEquals(VoiceSession.State.LISTENING, session.state)
        assertTrue(session.level > 0f)
        assertTrue(rec.isRunning)
        session.stop()
        l.onFinal("轻声说话也能继续")
        l.onEnd()
        assertEquals("轻声说话也能继续", edit.text.toString())
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
