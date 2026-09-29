package com.weavetext.ime.voice

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

// SpeechRecognizer 在 API 31 起新增的错误码（minSdk 26，自行定义）。 Error codes added in API 31.
private const val ERROR_TOO_MANY_REQUESTS = 10
private const val ERROR_SERVER_DISCONNECTED = 11
private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
private const val ERROR_LANGUAGE_UNAVAILABLE = 13

/** 系统语音服务不可用时的说明。 Shown when the system speech service cannot be used. */
internal const val SYSTEM_UNAVAILABLE = "系统语音服务连接失败"
internal const val SYSTEM_REFUSED = "系统语音服务拒绝了织文的请求"
internal const val SYSTEM_NO_RESPONSE = "系统语音服务没有响应"
internal const val SYSTEM_NO_RESULT = "系统语音识别没有返回结果"
internal const val SYSTEM_NETWORK = "系统语音服务连不上它的服务器"
internal const val SYSTEM_BUSY = "系统语音服务正忙，请稍后再试"
internal const val SYSTEM_NO_CHINESE = "系统语音服务不支持中文"
internal const val SYSTEM_OFFLINE_RETRY = "系统语音服务连不上服务器，已改用离线模式，请再说一遍"
internal const val MIC_BUSY = "麦克风被其他应用占用"
internal const val NO_MATCH = "没听清，请再说一次"
internal const val NO_SPEECH = "没有听到声音，请靠近麦克风再说"

/**
 * 说明是用户这边的原因（没说话、没听清、麦克风被占用），不是系统语音服务坏了：界面不必给出换引擎的办法。
 * Messages caused by the user's side (silence, not understood, mic busy) rather than a broken service: the UI
 * need not offer other engines for these.
 */
internal val VOICE_USER_MESSAGES = setOf(NO_MATCH, NO_SPEECH, MIC_BUSY)

/** 平台识别器的最小封装（测试用假实现替换）。 Minimal wrapper over the platform recognizer; faked in tests. */
internal interface SpeechClient {
    /** 开始收音；失败时抛异常。 Start listening; throws on failure. */
    fun listen(intent: Intent, listener: RecognitionListener)
    fun stop()
    /** 取消并释放，之后不再回调。 Cancel and release; no callbacks afterwards. */
    fun release()
}

/** 一个可以尝试的识别服务。 One recognition service to try. */
internal class SpeechService(
    /** 用来记住上次能用的服务。 Used to remember the service that worked last. */
    val key: String,
    /** Android 13 起的端侧识别：不支持中文时不改用它的默认语言。 On-device recognizer: no language-less retry. */
    val onDevice: Boolean,
    val open: () -> SpeechClient,
)

/** 真实的平台识别器。 The real platform recognizer. */
internal class PlatformSpeech(private val sr: SpeechRecognizer) : SpeechClient {
    override fun listen(intent: Intent, listener: RecognitionListener) {
        sr.setRecognitionListener(listener)
        sr.startListening(intent)
    }

    override fun stop() { runCatching { sr.stopListening() } }

    override fun release() {
        runCatching { sr.cancel() }
        runCatching { sr.destroy() }
    }
}

/**
 * 系统语音识别的一次会话：依次尝试手机上的识别服务，按错误类别决定换服务、重试还是结束；服务不回话时由
 * 看门狗结束（连接、说话中、收尾各有时限），不会一直卡住。只在主线程调用。
 *
 * One platform-recognizer session: tries the phone's recognition services in turn and, by error class, moves on
 * to the next service, retries or ends. Watchdogs (connect, listening, finalize) end a service that stops
 * answering, so a session never hangs. Main thread only.
 */
internal class SystemSpeech(
    private val main: Handler,
    private val services: () -> List<SpeechService>,
    private val callingPackage: String,
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
) {
    enum class Kind {
        /** 识别出了文字。 Text came back. */
        OK,
        /** 没说话、没听清、麦克风被占用：不算服务的问题。 Silence, no match, mic busy: not the service's fault. */
        USER,
        /** 服务拒绝、连不上或没有响应。 Refused, unreachable or unresponsive. */
        FAILED,
    }

    /**
     * 会话结果。[retryable]：用户还在等着说话（没按停止、也还没出字），可以当场改用别的引擎。
     * [service]：成功时是那个服务的 key。
     * Session outcome. [retryable]: the user is still waiting to speak (no stop, no text yet), so another engine
     * may take over right away. [service]: the key of the service that worked.
     */
    class Outcome(val kind: Kind, val message: String?, val retryable: Boolean, val service: String?)

    /** 会话事件，全部在主线程。 Session events, main thread. */
    interface Sink {
        fun ready()
        fun level(level: Float)
        fun partial(text: String)
        fun final(text: String)
        fun notice(message: String)
        fun end(outcome: Outcome)
    }

    private class Run(val sink: Sink, val canFallback: Boolean, val list: List<SpeechService>, val startedAt: Long) {
        var index = 0
        /** 每次连接递增，旧连接的迟到回调据此丢弃。 Bumped per attempt to drop late callbacks. */
        var attempt = 0
        var client: SpeechClient? = null
        var language = true
        var offline = false
        var retried = false
        /** 服务有过任何回调。 The service has called back at all. */
        var alive = false
        /** 已出过字。 Some text came back. */
        var heard = false
        /** 用户按了停止。 The user pressed stop. */
        var stopping = false
        /** 服务判断话说完了，正在出结果。 The service detected the end of speech and is working on the result. */
        var processing = false
    }

    private var run: Run? = null
    private val watchdog = Runnable { onWatchdog() }

    val isRunning: Boolean get() = run != null

    /** 开始；[canFallback] 为 true 时连不上服务器就直接结束，让调用方改用本地识别。 Start a session. */
    fun start(sink: Sink, canFallback: Boolean) {
        cancel()
        val r = Run(sink, canFallback, runCatching { services() }.getOrDefault(emptyList()), clock())
        run = r
        if (r.list.isEmpty()) {
            main.post { finish(r, Kind.FAILED, SYSTEM_UNAVAILABLE) }
            return
        }
        open(r)
    }

    /** 停止收音，等最终结果（有时限）。 Stop listening and wait for the result, with a time limit. */
    fun stop() {
        val r = run ?: return
        if (r.stopping) return
        r.stopping = true
        r.client?.stop()
        // 还没连上时保留连接时限：到时就结束，不会一直「识别中」。 Not connected yet: keep the connect limit.
        if (r.alive) arm(FINALIZE_MS)
    }

    /** 立即取消，不再回调。 Cancel right away; no more callbacks. */
    fun cancel() {
        val r = run ?: return
        run = null
        r.attempt++
        main.removeCallbacks(watchdog)
        r.client?.release()
        r.client = null
    }

    private fun open(r: Run) {
        val svc = r.list[r.index]
        val a = ++r.attempt
        r.alive = false
        r.processing = false
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, callingPackage)
        if (r.language) intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        if (r.offline) intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        arm(if (a == 1) FIRST_CONNECT_MS else CONNECT_MS)
        try {
            val c = svc.open()
            r.client = c
            c.listen(intent, listener(r, a))
        } catch (t: Throwable) {
            Log.w(TAG, "service ${svc.key} failed to start", t)
            // 异步处理，调用方的 start 先返回。 Handled asynchronously so the caller's start returns first.
            main.post { if (run === r && r.attempt == a) next(r, SYSTEM_UNAVAILABLE) }
        }
    }

    private fun listener(r: Run, a: Int) = object : RecognitionListener {
        fun live() = run === r && r.attempt == a

        fun alive() {
            if (!r.alive) { r.alive = true; r.sink.ready() }
            if (!r.stopping && !r.processing) arm(LISTEN_IDLE_MS)
        }

        override fun onReadyForSpeech(params: Bundle?) { if (live()) alive() }
        override fun onBeginningOfSpeech() { if (live()) alive() }
        override fun onRmsChanged(rmsdB: Float) {
            if (!live()) return
            alive()
            r.sink.level(((rmsdB + 2) / 12f).coerceIn(0f, 1f))
        }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            if (!live()) return
            alive()
            if (!r.processing) { r.processing = true; arm(FINALIZE_MS) }
        }
        override fun onError(error: Int) { if (live()) onError(r, error) }
        override fun onResults(results: Bundle?) {
            if (!live()) return
            val text = first(results)
            when {
                text.isNotEmpty() -> { r.heard = true; r.sink.final(text); finish(r, Kind.OK, null) }
                r.heard -> finish(r, Kind.OK, null)
                else -> finish(r, Kind.USER, NO_MATCH)
            }
        }
        override fun onPartialResults(partialResults: Bundle?) {
            if (!live()) return
            alive()
            val text = first(partialResults)
            if (text.isNotEmpty()) { r.heard = true; r.sink.partial(text) }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun onError(r: Run, code: Int) {
        val svc = r.list[r.index]
        Log.w(TAG, "service ${svc.key} error $code")
        val msg = message(code)
        // 已经出字：不再重试，已识别的文字照常上屏。 Text already came back: keep it, no retry.
        if (r.heard) { finish(r, Kind.OK, null); return }
        // 用户已按停止：不再换服务重新收音；很快点停时服务常报这类错，不算服务坏了。
        // The user stopped: never start listening again. Services often answer an early stop with an error; that
        // doesn't count against the service.
        if (r.stopping) {
            finish(r, Kind.USER, if (r.alive && msg !in VOICE_USER_MESSAGES) NO_MATCH else msg)
            return
        }
        when (code) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> finish(r, Kind.USER, msg)
            // 不支持中文：同一服务改用它的默认语言；端侧识别没有中文包就换下一个。
            // Chinese unsupported: same service in its default language; on-device without a Chinese pack: next.
            ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE ->
                if (r.language && !svc.onDevice) { r.language = false; retry(r, 0) } else next(r, msg)
            // 正忙（常见于刚释放上一次会话）或麦克风还没放开：稍等再试一次。
            // Busy (often right after the previous session was released) or the mic not yet free: retry once.
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, ERROR_TOO_MANY_REQUESTS, SpeechRecognizer.ERROR_AUDIO ->
                if (!r.retried) { r.retried = true; retry(r, BUSY_RETRY_MS) }
                else finish(r, if (code == SpeechRecognizer.ERROR_AUDIO) Kind.USER else Kind.FAILED, msg)
            // 连不上服务器：有本地识别就交给它；没有就让同一服务试一次离线模式。
            // Server unreachable: hand over to local recognition if there is one; else try offline mode once.
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> when {
                r.canFallback -> finish(r, Kind.FAILED, msg)
                !r.offline -> { r.offline = true; r.sink.notice(SYSTEM_OFFLINE_RETRY); retry(r, 0) }
                else -> next(r, msg)
            }
            // 拒绝、断开、服务器错误等：换下一个服务。 Refused, disconnected, server error and the rest: next service.
            else -> next(r, msg)
        }
    }

    /** 同一服务重新连接。 Reconnect to the same service. */
    private fun retry(r: Run, delayMs: Long) {
        r.client?.release()
        r.client = null
        val a = ++r.attempt
        main.postDelayed({
            if (run !== r || r.attempt != a) return@postDelayed
            if (r.stopping) finish(r, Kind.USER, NO_MATCH) else open(r)
        }, delayMs)
    }

    /** 换下一个服务；没有了（或已等太久）就结束。 Move to the next service, or end when none is left. */
    private fun next(r: Run, message: String) {
        r.client?.release()
        r.client = null
        r.index++
        r.language = true
        r.offline = false
        r.retried = false
        if (r.stopping || r.index >= r.list.size || clock() - r.startedAt > CONNECT_BUDGET_MS) {
            finish(r, Kind.FAILED, message)
        } else {
            open(r)
        }
    }

    private fun onWatchdog() {
        val r = run ?: return
        val key = r.list.getOrNull(r.index)?.key
        when {
            // 连接超时：服务一直没回话。 Connect timeout: the service never answered.
            !r.alive -> {
                Log.w(TAG, "service $key did not answer")
                next(r, SYSTEM_NO_RESPONSE)
            }
            // 说话中长时间没有任何回调：当作说完。 No callback at all for a long while: treat as the end of speech.
            !r.stopping && !r.processing -> {
                Log.w(TAG, "service $key went quiet")
                r.processing = true
                r.client?.stop()
                arm(FINALIZE_MS)
            }
            // 收尾超时：已出的字照常上屏。 Finalize timeout: keep whatever text came back.
            else -> {
                Log.w(TAG, "service $key gave no result")
                if (r.heard) finish(r, Kind.OK, null) else finish(r, Kind.FAILED, SYSTEM_NO_RESULT)
            }
        }
    }

    private fun finish(r: Run, kind: Kind, message: String?) {
        if (run !== r) return
        run = null
        r.attempt++
        main.removeCallbacks(watchdog)
        r.client?.release()
        r.client = null
        val service = if (kind == Kind.OK) r.list.getOrNull(r.index)?.key else null
        r.sink.end(Outcome(kind, message, retryable = !r.stopping && !r.heard, service = service))
    }

    private fun arm(ms: Long) {
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, ms)
    }

    private fun first(b: Bundle?): String =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    companion object {
        private const val TAG = "WeaveVoice"
        /** 第一次连接的时限（服务可能要冷启动）。 First connect limit; the service may be cold-starting. */
        const val FIRST_CONNECT_MS = 4_000L
        const val CONNECT_MS = 3_000L
        /** 说话中服务完全不回调的时限。 Limit for a listening service that sends nothing at all. */
        const val LISTEN_IDLE_MS = 10_000L
        /** 说完后等结果的时限。 Limit for the result after the end of speech. */
        const val FINALIZE_MS = 6_000L
        const val BUSY_RETRY_MS = 400L
        /** 超过这个时间不再换服务，免得用户一直等。 No more services after this long, so the user isn't kept waiting. */
        const val CONNECT_BUDGET_MS = 6_000L

        /** 系统识别错误码 → 用户看得懂的说明。 System error code → a message the user can act on. */
        fun message(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> SYSTEM_NETWORK
            SpeechRecognizer.ERROR_AUDIO -> MIC_BUSY
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SYSTEM_REFUSED
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, ERROR_TOO_MANY_REQUESTS -> SYSTEM_BUSY
            ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> SYSTEM_NO_CHINESE
            SpeechRecognizer.ERROR_NO_MATCH -> NO_MATCH
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> NO_SPEECH
            else -> SYSTEM_UNAVAILABLE
        }
    }
}
