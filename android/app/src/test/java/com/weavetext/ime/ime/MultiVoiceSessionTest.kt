package com.weavetext.ime.ime

import android.Manifest
import android.app.Application
import android.content.Context
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.testing.FakeEngines
import com.weavetext.ime.testing.ScriptedRecognizer
import com.weavetext.ime.ui.keyboard.VoiceSession
import com.weavetext.ime.voice.MultiEngineResults.Status
import com.weavetext.ime.voice.VoicePlugin
import com.weavetext.ime.voice.VoiceAutoDownload
import com.weavetext.ime.voice.MultiVoiceListener
import com.weavetext.ime.voice.VoiceListener
import com.weavetext.ime.voice.VoiceRecognizer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * 多引擎语音会话（假引擎）：主引擎预览、结果列表点选上屏、超时、相同结果仍需确认。
 * Multi-engine voice session with fake engines.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MultiVoiceSessionTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var edit: EditText
    private lateinit var engines: FakeEngines
    private lateinit var rec: ScriptedRecognizer
    private lateinit var session: VoiceSession
    private var previousEnsure: ((Context) -> Boolean)? = null
    private var preparations = 0

    private val local = VoicePlugin("weave.local", "本地离线识别", "", "", null, emptyList())
    private val a = VoicePlugin("org.example.a", "示例插件 A", "", "1.0", null, emptyList())
    private val b = VoicePlugin("org.example.b", "示例插件 B", "", "1.0", null, emptyList())

    @Before fun setUp() {
        previousEnsure = VoiceAutoDownload.ensureOverride
        VoiceAutoDownload.ensureOverride = { preparations++; false }
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        edit = EditText(app)
        val ic = edit.onCreateInputConnection(EditorInfo())
        engines = FakeEngines(listOf(local, a, b)).apply { activeId = local.id; extraIds = setOf(a.id, b.id) }
        rec = ScriptedRecognizer(engines)
        session = VoiceSession(app, InputController { ic }, recognizerProvider = { rec })
    }
    @After fun restoreDownloads() { VoiceAutoDownload.ensureOverride = previousEnsure }

    private fun idle() = ShadowLooper.idleMainLooper()

    @Test fun showsPrimaryWhileSpeakingAndCommitsTappedRow() {
        assertTrue(session.start())
        val m = rec.multi
        m.onEnginePartial(a.id, "插件的中间结果")
        m.onEnginePartial(local.id, "今天下午")
        assertEquals(VoiceSession.State.LISTENING, session.state)
        assertEquals("今天下午", session.partial)
        // 多引擎说话时不往输入框写任何东西。 Nothing reaches the editor while speaking.
        assertEquals("", edit.text.toString())

        m.onEngineFinal(local.id, "今天下午三点开会")
        m.onEngineFinal(a.id, "今天下午3点开会")
        m.onEngineFinal(b.id, "今天下午三点开会。")
        session.stop()
        assertEquals(VoiceSession.State.CHOOSING, session.state)
        assertEquals(1, rec.stops)
        assertTrue(session.results!!.rows().all { it.status == Status.LOADING })

        m.onEngineEnd(local.id); m.onEngineEnd(a.id); m.onEngineEnd(b.id); rec.endAll()
        assertEquals(VoiceSession.State.CHOOSING, session.state)
        assertEquals(0, session.defaultRow)

        session.choose(1)
        assertEquals("今天下午3点开会", edit.text.toString())
        assertEquals(VoiceSession.State.IDLE, session.state)
        assertNull(session.results)
    }

    @Test fun slowEngineTimesOutAndDefaultFallsBack() {
        session.start()
        val m = rec.multi
        m.onEngineFinal(a.id, "插件 A 的结果")
        session.stop()
        m.onEngineError(local.id, "模型未就绪"); m.onEngineEnd(local.id)
        m.onEngineEnd(a.id)
        // 插件 B 一直不回。 Plugin B never answers.
        assertEquals(Status.LOADING, session.results!!.rows()[2].status)
        assertEquals(1, session.defaultRow)
        ShadowLooper.idleMainLooper(com.weavetext.ime.voice.MultiEngineResults.DEFAULT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(Status.TIMEOUT, session.results!!.rows()[2].status)
        // 超时后取消仍在运行的引擎。 Remaining engines are cancelled after the timeout.
        assertEquals(1, rec.cancels)
        assertEquals(VoiceSession.State.CHOOSING, session.state)
        assertEquals(1, session.defaultRow)
    }

    @Test fun identicalResultsStillRequireChoosingARow() {
        session.start()
        val m = rec.multi
        for (id in listOf(local.id, a.id, b.id)) m.onEngineFinal(id, "好的")
        session.stop()
        for (id in listOf(local.id, a.id, b.id)) m.onEngineEnd(id)
        assertEquals("", edit.text.toString())
        assertEquals(VoiceSession.State.CHOOSING, session.state)
        assertEquals(3, session.results!!.rows().size)
        session.choose(2)
        assertEquals("好的", edit.text.toString())
        assertEquals(VoiceSession.State.IDLE, session.state)
    }

    @Test fun manuallyCombiningChineseStreamAndFinalStillRequiresChoosingOneOfTwoRows() {
        val stream = VoicePlugin("asr-stream-small", "中文实时小模型", "", "", null, emptyList())
        val finalModel = VoicePlugin("asr-final-small", "中文终稿小模型", "", "", null, emptyList())
        engines.plugins = listOf(stream, finalModel)
        engines.activeId = stream.id
        engines.extraIds = setOf(finalModel.id)
        session.start()
        val m = rec.multi
        m.onEnginePartial(stream.id, "下午十点")
        m.onEngineFinal(stream.id, "下午十点开会")
        m.onEngineFinal(finalModel.id, "下午四点开会")
        session.stop()
        m.onEngineEnd(stream.id)
        m.onEngineEnd(finalModel.id)

        assertEquals(VoiceSession.State.CHOOSING, session.state)
        assertEquals(2, session.results!!.rows().size)
        assertEquals("", edit.text.toString())
        session.choose(1)
        assertEquals("下午四点开会", edit.text.toString())
    }

    @Test fun detachedSessionCommitsDefaultWhenReady() {
        session.start()
        val m = rec.multi
        m.onEngineFinal(local.id, "本地"); m.onEngineFinal(a.id, "插件")
        session.detach()
        m.onEngineEnd(local.id); m.onEngineEnd(a.id); m.onEngineError(b.id, "网络错误"); m.onEngineEnd(b.id)
        assertEquals("本地", edit.text.toString())
        assertEquals(VoiceSession.State.IDLE, session.state)
    }

    @Test fun cancelDiscardsList() {
        session.start()
        rec.multi.onEngineFinal(local.id, "不要了")
        session.stop()
        session.cancel()
        assertEquals("", edit.text.toString())
        assertEquals(VoiceSession.State.IDLE, session.state)
    }

    @Test fun cancellingWhileListeningClearsResultsAndRejectsEveryOldCallback() {
        session.start()
        val old = rec.multi
        old.onEnginePartial(local.id, "未确认")
        session.cancel()
        assertNull(session.results)
        assertEquals("", session.partial)
        assertFalse(rec.isRunning)
        old.onEngineFinal(local.id, "迟到结果")
        old.onEngineEnd(local.id)
        old.onEnd()
        session.choose(0)
        assertNull(session.results)
        assertEquals("", edit.text.toString())
        assertEquals(VoiceSession.State.IDLE, session.state)
    }

    @Test fun aggregateEndSettlesRemainingRowsWithoutWaitingForTimeout() {
        session.start()
        rec.multi.onEnginePartial(local.id, "主引擎半句")
        rec.multi.onEngineFinal(a.id, "插件结果")
        rec.endAll() // onEnd means all engines finished, even if individual end callbacks were omitted.
        assertEquals(VoiceSession.State.CHOOSING, session.state)
        assertTrue(session.results!!.settled)
        assertEquals(Status.DONE, session.results!!.rows()[0].status)
        assertEquals(Status.ERROR, session.results!!.rows()[2].status)
        session.choose(0)
        assertEquals("主引擎半句", edit.text.toString())
    }

    @Test fun endedRecognizerCannotReplaceRowsWhileWaitingForUserChoice() {
        session.start()
        val old = rec.multi
        old.onEngineFinal(local.id, "已完成")
        old.onEngineEnd(local.id); old.onEngineEnd(a.id); old.onEngineEnd(b.id)
        rec.endAll()
        val rows = session.results!!.rows()
        old.onEngineReplace(local.id, "已完成", "不应改变")
        old.onPartial("不应写编辑器"); old.onFinal("不应上屏")
        old.onError("迟到错误")
        assertEquals(rows, session.results!!.rows())
        assertEquals("", edit.text.toString())
        assertEquals(VoiceSession.State.CHOOSING, session.state)
        session.choose(0)
        assertEquals("已完成", edit.text.toString())
    }

    @Test fun detachedTimeoutPreservesPrimaryPartialWithoutChangingEngineSelection() {
        val selected = engines.activeId to engines.extraIds
        session.timeoutMs = 100
        session.start()
        val old = rec.multi
        old.onEnginePartial(local.id, "保留主引擎半句")
        session.detach()
        ShadowLooper.idleMainLooper(101, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals("保留主引擎半句", edit.text.toString())
        assertEquals(VoiceSession.State.IDLE, session.state)
        assertNull(session.results)
        assertFalse(rec.isRunning)
        old.onEngineFinal(local.id, "迟到主引擎")
        old.onEngineReplace(local.id, "保留主引擎半句", "不应覆写")
        assertEquals("保留主引擎半句", edit.text.toString())
        assertEquals(selected, engines.activeId to engines.extraIds)
    }

    @Test fun captureErrorRetainsMultiEngineTextForAnExplicitChoice() {
        session.start()
        val old = rec.multi
        old.onEnginePartial(local.id, "保留主引擎")
        old.onEngineFinal(a.id, "保留插件")
        old.onError("麦克风中断")
        assertFalse(rec.isRunning)
        assertEquals(VoiceSession.State.CHOOSING, session.state)
        assertTrue(session.results!!.settled)
        assertEquals("", edit.text.toString())
        old.onEngineReplace(a.id, "保留插件", "迟到修正")
        session.choose(1)
        assertEquals("保留插件", edit.text.toString())
        assertNull(session.error)
    }

    @Test fun synchronousStopCompletionCommitsDetachedTextOnce() {
        val synchronous = object : VoiceRecognizer {
            lateinit var listener: MultiVoiceListener
            override var isRunning = false
            override fun start(listener: VoiceListener): Boolean {
                this.listener = listener as MultiVoiceListener
                isRunning = true
                this.listener.onEngines(listOf(local, a))
                this.listener.onEnginePartial(local.id, "同步半句")
                return true
            }
            override fun stop() {
                listener.onEngineFinal(local.id, "同步终稿")
                listener.onEngineFinal(a.id, "插件终稿")
                isRunning = false
                listener.onEnd()
            }
            override fun cancel() { isRunning = false; listener.onEnd() }
        }
        val ic = edit.onCreateInputConnection(EditorInfo())
        session = VoiceSession(app, InputController { ic }) { synchronous }
        session.start()
        session.detach()
        assertEquals(VoiceSession.State.IDLE, session.state)
        assertNull(session.results)
        assertEquals("同步终稿", edit.text.toString())
        ShadowLooper.idleMainLooper(session.timeoutMs + 1, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals("同步终稿", edit.text.toString())
        assertEquals(VoiceSession.State.IDLE, session.state)
    }

    @Test fun redoFromCandidateListDropsOldResultsAndCommitsTheNewRecording() {
        session.start()
        rec.multi.onEngineFinal(local.id, "第一遍")
        rec.multi.onEngineFinal(a.id, "第一遍插件")
        session.stop()
        assertEquals(VoiceSession.State.CHOOSING, session.state)

        // The result-list microphone is a new recording, not another choice from the old list.
        assertTrue(session.start())
        assertEquals(VoiceSession.State.CONNECTING, session.state)
        rec.multi.onEngineFinal(local.id, "第二遍")
        rec.multi.onEngineFinal(a.id, "第二遍插件")
        session.stop()
        rec.multi.onEngineEnd(local.id)
        rec.multi.onEngineEnd(a.id)
        assertEquals(listOf("第二遍", "第二遍插件", ""), session.results!!.rows().map { it.text })
        session.choose(0)

        assertEquals("第二遍", edit.text.toString())
        assertEquals(VoiceSession.State.IDLE, session.state)
    }

    @Test fun pendingOrFailedRowsCannotBeConfirmed() {
        session.start()
        rec.multi.onEngineFinal(local.id, "可确认")
        session.stop()
        rec.multi.onEngineError(a.id, "插件失败")
        rec.multi.onEngineEnd(local.id)

        session.choose(1)
        assertEquals("", edit.text.toString())
        session.choose(2)
        assertEquals("等待中的行不能确认", "", edit.text.toString())
        session.choose(0)
        assertEquals("可确认", edit.text.toString())
    }

    @Test fun singleEngineUnchanged() {
        engines.extraIds = emptySet()
        session.start()
        assertNull(session.results)
        rec.listener!!.onFinal("单引擎")
        assertEquals("单引擎", edit.text.toString())
        idle()
    }

    @Test fun preparationRunsOncePerNewStartWithoutChangingAnActiveCapture() {
        engines.extraIds = emptySet()
        session.start()
        val first = rec.listener!!
        first.onReady(false)
        assertEquals(1, preparations)
        assertNull(session.results)

        // 准备/设置更新只影响下一次录音，当前单模型回调和输出仍保持稳定。
        engines.extraIds = setOf(a.id)
        assertTrue(session.start())
        session.warmUp()
        assertEquals(1, preparations)
        assertTrue(first === rec.listener)
        assertNull(session.results)
        first.onFinal("当前录音")
        rec.endAll()
        assertEquals("当前录音", edit.text.toString())

        session.start()
        assertEquals(2, preparations)
        assertEquals(2, session.results!!.rows().size)
    }
}
