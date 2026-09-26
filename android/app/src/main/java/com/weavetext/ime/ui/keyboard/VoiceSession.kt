package com.weavetext.ime.ui.keyboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.weavetext.ime.ime.InputController
import com.weavetext.ime.ui.VoiceAccess
import com.weavetext.ime.voice.VoiceListener
import com.weavetext.ime.voice.VoiceRecognizer

/**
 * 一次语音输入会话的状态机（02 §12.1）：Idle → Connecting → Listening → Finalizing → Idle，出错 → Error。
 * 识别中间结果作为 composing 文本上屏，最终结果 commit。语音面板与浮动语音条共用。
 * Voice session state machine shared by the voice panel and the floating strip.
 */
class VoiceSession(
    private val ctx: Context,
    private val controller: InputController,
    private val recognizerProvider: () -> VoiceRecognizer = { VoiceAccess.recognizer(ctx) },
) {
    enum class State { IDLE, CONNECTING, LISTENING, FINALIZING, ERROR }

    var state = State.IDLE
        private set
    /** 本次会话已确定（已上屏）的文本。 Final text committed in this session. */
    val committed = StringBuilder()
    var partial = ""
        private set
    var error: String? = null
        private set
    /** 平滑后的音量 0..1。 Smoothed input level. */
    var level = 0f
        private set
    /** 点按模式下静音 2.5s 自动结束。 Auto-stop after silence (tap mode). */
    var autoStop = true

    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<() -> Unit>()
    private var rec: VoiceRecognizer? = null
    private var lastLoud = 0L
    private var token = 0

    fun addListener(l: () -> Unit) { listeners += l }
    private fun changed() = listeners.forEach { it() }

    val active get() = state == State.CONNECTING || state == State.LISTENING || state == State.FINALIZING

    fun hasPermission(): Boolean =
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private val connectTimeout = Runnable { if (state == State.CONNECTING) { state = State.LISTENING; changed() } }
    private val silenceCheck = object : Runnable {
        override fun run() {
            if (state != State.LISTENING) return
            if (autoStop && SystemClock.uptimeMillis() - lastLoud > SILENCE_MS) { stop(); return }
            main.postDelayed(this, 250)
        }
    }

    /** 面板打开时预热识别器（如提前加载本地模型），失败不影响后续使用。 Warm up when the panel opens. */
    fun warmUp() {
        runCatching { recognizerProvider().warmUp() }
    }

    /** 开始；缺少权限返回 false。 Start; false without mic permission. */
    fun start(): Boolean {
        if (active) return true
        if (!hasPermission()) { error = "需要麦克风权限"; changed(); return false }
        val r = recognizerProvider()
        rec = r
        committed.clear(); partial = ""; error = null; level = 0f
        state = State.CONNECTING
        lastLoud = SystemClock.uptimeMillis()
        val my = ++token
        val ok = r.start(object : VoiceListener {
            fun live() = my == token
            override fun onPartial(text: String) {
                if (!live()) return
                enterListening()
                partial = text
                if (text.isNotEmpty()) lastLoud = SystemClock.uptimeMillis()
                controller.voicePartial(text)
                changed()
            }
            override fun onFinal(text: String) {
                if (!live()) return
                controller.voiceFinal(text)
                committed.append(text)
                partial = ""
                changed()
            }
            override fun onReplace(old: String, new: String) {
                if (!live()) return
                controller.voiceReplace(old, new)
                val i = committed.lastIndexOf(old)
                if (i >= 0) committed.replace(i, i + old.length, new)
                changed()
            }
            override fun onError(message: String) {
                if (!live()) return
                controller.voiceFinal("")
                partial = ""
                error = message
                state = State.ERROR
                changed()
            }
            override fun onEnd() {
                if (!live()) return
                if (partial.isNotEmpty()) { controller.voiceFinal(partial); committed.append(partial); partial = "" }
                controller.voiceFinal("")
                if (state != State.ERROR) state = State.IDLE
                level = 0f
                main.removeCallbacks(silenceCheck)
                changed()
            }
            override fun onLevel(level: Float) {
                if (!live()) return
                enterListening()
                this@VoiceSession.level = this@VoiceSession.level * 0.65f + level.coerceIn(0f, 1f) * 0.35f
                if (level > LOUD) lastLoud = SystemClock.uptimeMillis()
            }
        })
        if (!ok) {
            if (state == State.CONNECTING) { state = State.ERROR; error = error ?: "无法开始录音" }
            changed()
            return false
        }
        main.postDelayed(connectTimeout, 1500)
        changed()
        return true
    }

    private fun enterListening() {
        if (state == State.CONNECTING) {
            state = State.LISTENING
            main.removeCallbacks(connectTimeout)
            main.postDelayed(silenceCheck, 250)
        }
    }

    /** 结束收音，等待最终结果。 Stop and wait for the final result. */
    fun stop() {
        if (!active || state == State.FINALIZING) return
        state = State.FINALIZING
        main.removeCallbacks(silenceCheck)
        changed()
        rec?.stop()
    }

    /** 取消：丢弃未确定的文本。 Cancel and drop interim text. */
    fun cancel() {
        if (!active) { if (state == State.ERROR) { state = State.IDLE; changed() }; return }
        token++
        rec?.cancel()
        controller.voiceCancel()
        partial = ""; level = 0f
        state = State.IDLE
        main.removeCallbacks(silenceCheck); main.removeCallbacks(connectTimeout)
        changed()
    }

    /** 截图测试 / 预览：直接设定展示状态。 Screenshot tests & previews only. */
    @androidx.annotation.VisibleForTesting
    fun preview(state: State, committed: String, partial: String, level: Float, error: String? = null) {
        this.state = state
        this.committed.clear(); this.committed.append(committed)
        this.partial = partial; this.level = level; this.error = error
        changed()
    }

    companion object {
        private const val SILENCE_MS = 2500L
        private const val LOUD = 0.08f
    }
}
