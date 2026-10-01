package com.weavetext.ime.ime

import android.Manifest
import android.app.Application
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.testing.FakeEngines
import com.weavetext.ime.testing.ScriptedRecognizer
import com.weavetext.ime.ui.keyboard.VoiceSession
import com.weavetext.ime.voice.MultiEngineResults.Status
import com.weavetext.ime.voice.VoicePlugin
import org.junit.Assert.assertEquals
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
 * 多引擎语音会话（假引擎）：只显示主引擎、停止后出结果列表、点选上屏、超时、一致直接上屏。
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

    private val local = VoicePlugin("weave.local", "本地离线识别", "", "", null, emptyList())
    private val a = VoicePlugin("org.example.a", "示例插件 A", "", "1.0", null, emptyList())
    private val b = VoicePlugin("org.example.b", "示例插件 B", "", "1.0", null, emptyList())

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        edit = EditText(app)
        val ic = edit.onCreateInputConnection(EditorInfo())
        engines = FakeEngines(listOf(local, a, b)).apply { activeId = local.id; extraIds = setOf(a.id, b.id) }
        rec = ScriptedRecognizer(engines)
        session = VoiceSession(app, InputController { ic }, recognizerProvider = { rec })
    }

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

    @Test fun singleEngineUnchanged() {
        engines.extraIds = emptySet()
        session.start()
        assertNull(session.results)
        rec.listener!!.onFinal("单引擎")
        assertEquals("单引擎", edit.text.toString())
        idle()
    }
}
