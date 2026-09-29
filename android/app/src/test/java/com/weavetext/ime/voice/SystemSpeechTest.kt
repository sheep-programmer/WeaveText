package com.weavetext.ime.voice

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * 系统语音识别会话（假识别服务）：服务不回话、拒绝、停止后报错、不支持中文、连不上服务器、收尾不给结果。
 * System recognizer session with fake services: silence, refusal, an error after stop, no Chinese, no server,
 * no result after the stop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemSpeechTest {
    private class FakeClient(val key: String) : SpeechClient {
        var listener: RecognitionListener? = null
        var intent: Intent? = null
        var stops = 0
        var released = false
        override fun listen(intent: Intent, listener: RecognitionListener) { this.intent = intent; this.listener = listener }
        override fun stop() { stops++ }
        override fun release() { released = true }
        val l get() = listener!!
    }

    private class Sink : SystemSpeech.Sink {
        val partials = ArrayList<String>()
        val finals = ArrayList<String>()
        val notices = ArrayList<String>()
        var readies = 0
        var outcome: SystemSpeech.Outcome? = null
        var ends = 0
        override fun ready() { readies++ }
        override fun level(level: Float) {}
        override fun partial(text: String) { partials += text }
        override fun final(text: String) { finals += text }
        override fun notice(message: String) { notices += message }
        override fun end(outcome: SystemSpeech.Outcome) { this.outcome = outcome; ends++ }
    }

    private val opened = ArrayList<FakeClient>()
    private fun service(key: String, onDevice: Boolean = false) = SpeechService(key, onDevice) { FakeClient(key).also { opened += it } }

    private fun speech(vararg services: SpeechService) = SystemSpeech(Handler(Looper.getMainLooper()), { services.toList() }, "com.weavetext.ime")

    private fun idle(ms: Long = 0) = ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS)

    private fun results(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    @Test fun serviceThatNeverAnswersTimesOutAndMovesOn() {
        val sink = Sink()
        val s = speech(service("a"), service("b"))
        s.start(sink, canFallback = true)
        assertEquals(1, opened.size)
        idle(SystemSpeech.FIRST_CONNECT_MS + 10)
        assertTrue(opened[0].released)
        assertEquals(2, opened.size)
        idle(SystemSpeech.CONNECT_MS + 10)
        val o = sink.outcome!!
        assertEquals(SystemSpeech.Kind.FAILED, o.kind)
        assertEquals(SYSTEM_NO_RESPONSE, o.message)
        assertTrue("用户还在等 / user still waiting", o.retryable)
        assertFalse(s.isRunning)
        assertTrue(opened[1].released)
    }

    @Test fun refusedServiceFallsThroughToNextOne() {
        val sink = Sink()
        val s = speech(service("a"), service("b"))
        s.start(sink, canFallback = false)
        opened[0].l.onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        assertEquals(2, opened.size)
        val b = opened[1]
        assertEquals("zh-CN", b.intent!!.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        b.l.onReadyForSpeech(null)
        assertEquals(1, sink.readies)
        b.l.onPartialResults(results("你好"))
        b.l.onResults(results("你好。"))
        assertEquals(listOf("你好"), sink.partials)
        assertEquals(listOf("你好。"), sink.finals)
        assertEquals(SystemSpeech.Kind.OK, sink.outcome!!.kind)
        assertEquals("b", sink.outcome!!.service)
        // 之后不再有看门狗动作。 No watchdog afterwards.
        idle(20_000)
        assertEquals(1, sink.ends)
    }

    @Test fun refusalMessageIsNotAboutMicPermission() {
        assertEquals(SYSTEM_REFUSED, SystemSpeech.message(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertEquals(SYSTEM_NETWORK, SystemSpeech.message(SpeechRecognizer.ERROR_NETWORK))
    }

    @Test fun clientErrorAfterStopDoesNotRestartListening() {
        val sink = Sink()
        val s = speech(service("a"), service("b"))
        s.start(sink, canFallback = true)
        opened[0].l.onReadyForSpeech(null)
        s.stop()
        assertEquals(1, opened[0].stops)
        opened[0].l.onError(SpeechRecognizer.ERROR_CLIENT)
        idle(1_000)
        assertEquals("不应换服务重新收音 / must not listen again", 1, opened.size)
        assertEquals(SystemSpeech.Kind.USER, sink.outcome!!.kind)
        assertFalse(sink.outcome!!.retryable)
    }

    @Test fun languageFallbackIsPerService() {
        val sink = Sink()
        val s = speech(service("a"), service("b"))
        s.start(sink, canFallback = false)
        opened[0].l.onError(12)
        idle()
        // 同一服务改用默认语言。 Same service, default language.
        assertEquals(2, opened.size)
        assertEquals("a", opened[1].key)
        assertNull(opened[1].intent!!.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        opened[1].l.onError(SpeechRecognizer.ERROR_SERVER)
        // 下一个服务重新要中文。 The next service is asked for Chinese again.
        assertEquals("b", opened[2].key)
        assertEquals("zh-CN", opened[2].intent!!.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
    }

    @Test fun onDeviceWithoutChineseIsSkipped() {
        val sink = Sink()
        val s = speech(service("ondevice", onDevice = true))
        s.start(sink, canFallback = true)
        opened[0].l.onError(13)
        idle()
        assertEquals(1, opened.size)
        assertEquals(SystemSpeech.Kind.FAILED, sink.outcome!!.kind)
        assertEquals(SYSTEM_NO_CHINESE, sink.outcome!!.message)
    }

    @Test fun missingResultAfterStopKeepsShownText() {
        val sink = Sink()
        val s = speech(service("a"))
        s.start(sink, canFallback = true)
        val a = opened[0]
        a.l.onReadyForSpeech(null)
        a.l.onPartialResults(results("今天"))
        s.stop()
        idle(SystemSpeech.FINALIZE_MS + 10)
        assertEquals(SystemSpeech.Kind.OK, sink.outcome!!.kind)
        assertNull(sink.outcome!!.message)
        assertTrue(a.released)
    }

    @Test fun missingResultWithoutTextFails() {
        val sink = Sink()
        val s = speech(service("a"))
        s.start(sink, canFallback = true)
        opened[0].l.onReadyForSpeech(null)
        opened[0].l.onEndOfSpeech()
        idle(SystemSpeech.FINALIZE_MS + 10)
        assertEquals(SystemSpeech.Kind.FAILED, sink.outcome!!.kind)
        assertEquals(SYSTEM_NO_RESULT, sink.outcome!!.message)
    }

    @Test fun networkErrorHandsOverOrTriesOffline() {
        val sink = Sink()
        val s = speech(service("a"))
        s.start(sink, canFallback = true)
        opened[0].l.onError(SpeechRecognizer.ERROR_NETWORK)
        assertEquals(SystemSpeech.Kind.FAILED, sink.outcome!!.kind)
        assertTrue(sink.outcome!!.retryable)

        val sink2 = Sink()
        s.start(sink2, canFallback = false)
        opened[1].l.onError(SpeechRecognizer.ERROR_NETWORK_TIMEOUT)
        idle()
        assertEquals(listOf(SYSTEM_OFFLINE_RETRY), sink2.notices)
        assertTrue(opened[2].intent!!.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
        assertNull(sink2.outcome)
    }

    @Test fun busyIsRetriedOnceAfterAShortWait() {
        val sink = Sink()
        val s = speech(service("a"))
        s.start(sink, canFallback = false)
        opened[0].l.onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        assertEquals(1, opened.size)
        idle(SystemSpeech.BUSY_RETRY_MS + 10)
        assertEquals(2, opened.size)
        opened[1].l.onError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        assertEquals(SystemSpeech.Kind.FAILED, sink.outcome!!.kind)
        assertEquals(SYSTEM_BUSY, sink.outcome!!.message)
    }

    @Test fun cancelSilencesLateCallbacks() {
        val sink = Sink()
        val s = speech(service("a"))
        s.start(sink, canFallback = true)
        val a = opened[0]
        s.cancel()
        assertTrue(a.released)
        a.l.onResults(results("迟到"))
        idle(20_000)
        assertTrue(sink.finals.isEmpty())
        assertNull(sink.outcome)
    }

    @Test fun serviceThatFailsToOpenIsSkipped() {
        val sink = Sink()
        val bad = SpeechService("bad", false) { throw SecurityException("not allowed") }
        val s = speech(bad, service("b"))
        s.start(sink, canFallback = true)
        idle()
        assertEquals(listOf("b"), opened.map { it.key })
    }

    @Test fun systemStopsBeingDefaultAfterRepeatedFailures() {
        val ids = listOf(LOCAL_ENGINE_ID, SYSTEM_ENGINE_ID)
        assertEquals(SYSTEM_ENGINE_ID, pickActive(SYSTEM_ENGINE_ID, ids, 1))
        assertEquals(LOCAL_ENGINE_ID, pickActive(SYSTEM_ENGINE_ID, ids, SYSTEM_FAILURE_LIMIT))
        // 没有本地识别时仍用系统识别（面板会给出其它办法）。 Without local the system engine stays.
        assertEquals(SYSTEM_ENGINE_ID, pickActive(null, listOf(SYSTEM_ENGINE_ID), 5))
        assertEquals(LOCAL_ENGINE_ID, pickActive(null, ids, 0))
        assertEquals("org.example.a", pickActive("org.example.a", ids + "org.example.a", 5))
    }
}
