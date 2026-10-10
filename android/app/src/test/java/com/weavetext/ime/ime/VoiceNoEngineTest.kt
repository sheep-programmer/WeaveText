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
import com.weavetext.ime.voice.VoiceAutoDownload
import com.weavetext.ime.voice.VoiceRecognizer
import com.weavetext.ime.voice.VoiceListener
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

/**
 * 没有任何语音引擎（如轻量版且手机无系统语音服务）：不报错、不录音，改为给出安装引导。
 * No voice engine at all (e.g. lite build without a system service): no error, no recording, guidance instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceNoEngineTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private var previousEnsure: ((Context) -> Boolean)? = null
    private var preparations = 0
    @Before fun isolateDownloads() {
        previousEnsure = VoiceAutoDownload.ensureOverride
        VoiceAutoDownload.ensureOverride = { preparations++; false }
    }
    @After fun restoreDownloads() { VoiceAutoDownload.ensureOverride = previousEnsure }

    @Test fun noEngineShowsGuidanceInsteadOfError() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val ic = EditText(app).onCreateInputConnection(EditorInfo())
        val rec = ScriptedRecognizer(FakeEngines(emptyList()))
        val session = VoiceSession(app, InputController { ic }, recognizerProvider = { rec })
        var guided = 0
        session.onNoEngine = { guided++ }
        assertFalse(session.start())
        assertEquals(1, guided)
        assertEquals("缺引擎会准备模型，但不会开始录音", 1, preparations)
        assertEquals(VoiceSession.State.IDLE, session.state)
        assertNull(session.error)
        assertNull("不应开始录音 / must not start recording", rec.listener)
    }

    @Test fun withEngineStartsNormally() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val ic = EditText(app).onCreateInputConnection(EditorInfo())
        val rec = ScriptedRecognizer(FakeEngines())
        val session = VoiceSession(app, InputController { ic }, recognizerProvider = { rec })
        session.onNoEngine = { error("should not be called") }
        assertTrue(session.start())
        assertEquals("已有引擎的新会话也检查一次补装", 1, preparations)
    }

    @Test fun permissionMustBeGrantedBeforeModelPreparation() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val rec = ScriptedRecognizer(FakeEngines())
        val session = VoiceSession(app, InputController { null }, recognizerProvider = { rec })
        assertFalse(session.start())
        assertEquals(0, preparations)
        assertNull(rec.listener)
        assertEquals(VoiceSession.State.IDLE, session.state)
        assertEquals("需要麦克风权限", session.error)
    }

    @Test fun unavailableEngineAfterFailureClearsOldErrorAndNoticeWithoutChangingSelection() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val engines = FakeEngines()
        val rec = ScriptedRecognizer(engines)
        val session = VoiceSession(app, InputController { null }) { rec }
        session.start()
        rec.listener!!.onNotice("旧引擎提示")
        rec.listener!!.onError("旧引擎失败")
        val selected = engines.activeId to engines.extraIds
        engines.plugins = emptyList()
        var guided = 0
        session.onNoEngine = { guided++ }
        assertFalse(session.start())
        assertEquals(VoiceSession.State.IDLE, session.state)
        assertNull(session.error)
        assertNull(session.notice)
        assertNull(session.results)
        assertFalse(rec.isRunning)
        assertEquals(1, guided)
        assertEquals(selected, engines.activeId to engines.extraIds)
    }

    @Test fun availabilityFailureIsReportedWithoutStartingCaptureOrShowingInstallGuidance() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val rec = object : VoiceRecognizer {
            override val isRunning = false
            override fun hasEngine(): Boolean = error("引擎列表损坏")
            override fun start(listener: VoiceListener): Boolean = error("不应开始录音")
            override fun stop() {}
            override fun cancel() {}
        }
        var providers = 0
        val session = VoiceSession(app, InputController { null }) { providers++; rec }
        session.onNoEngine = { error("不是缺少引擎") }
        assertFalse(session.start())
        assertEquals(1, providers)
        assertEquals(VoiceSession.State.ERROR, session.state)
        assertEquals("引擎列表损坏", session.error)
    }

    @Test fun failedPreparationDoesNotPreventRecordingWithAnInstalledEngine() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val rec = ScriptedRecognizer(FakeEngines())
        val session = VoiceSession(app, InputController { null }, recognizerProvider = { rec })
        VoiceAutoDownload.ensureOverride = { preparations++; error("补装失败") }
        assertTrue(session.start())
        assertEquals(1, preparations)
        assertTrue(rec.isRunning)
    }
}
