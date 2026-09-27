package com.weavetext.ime.ui.keyboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.weavetext.ime.ime.InputController
import com.weavetext.ime.ui.VoiceAccess
import com.weavetext.ime.voice.VoiceHelp
import com.weavetext.ime.voice.MultiEngineResults
import com.weavetext.ime.voice.MultiVoiceListener
import com.weavetext.ime.voice.VoicePlugin
import com.weavetext.ime.voice.VoiceRecognizer

/**
 * 一次语音输入会话的状态机（02 §12.1）：Idle → Connecting → Listening → Finalizing → Idle，出错 → Error。
 * 单引擎：识别中间结果作为 composing 文本上屏，最终结果 commit。
 * 多引擎（06 §6）：说话时只在面板里显示主引擎的文字、不上屏；停止后进入 Choosing，
 * 显示每个引擎一行的结果列表，点哪行上屏哪行；所有引擎结果一致时直接上屏。
 * 语音面板与浮动语音条共用同一个会话。
 * Voice session state machine shared by the voice panel and the floating strip. Single engine:
 * interim text is composing, finals commit. Multi-engine: the primary engine's text shows in the
 * panel only; after stopping, Choosing lists one row per engine and the tapped row is committed;
 * identical results commit directly.
 */
class VoiceSession(
    private val ctx: Context,
    private val controller: InputController,
    private val recognizerProvider: () -> VoiceRecognizer = { VoiceAccess.recognizer(ctx) },
) {
    enum class State { IDLE, CONNECTING, LISTENING, FINALIZING, CHOOSING, ERROR }

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
    /** 点按模式下说完后静音 2.5s（一直没说话则 6s）自动结束。 Auto-stop after silence (tap mode). */
    var autoStop = true
    /** 多引擎会话的结果；单引擎为 null。 Multi-engine results; null for single-engine sessions. */
    var results: MultiEngineResults? = null
        private set
    /** 结果列表中高亮的行。 Highlighted row of the result list. */
    val defaultRow get() = results?.defaultIndex() ?: -1
    /** 键盘收起时仍在识别：结果出来后自动上屏默认行。 Detached: commit the default row when ready. */
    private var detached = false
    private val clock: () -> Long = { SystemClock.uptimeMillis() }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<() -> Unit>()
    private var rec: VoiceRecognizer? = null
    private var lastLoud = 0L
    /** 本次是否已听到说话（响度或识别出字）。 Whether speech was heard in this session. */
    private var spoke = false
    /** 底噪（自适应）。 Adaptive noise floor. */
    private var floor = -1f
    private var token = 0

    /**
     * 没有任何可用引擎时调用（不报错）：默认打开设置里的引导页，语音面板在时改为在面板里给出办法。
     * Called instead of an error when no engine is available: opens the guidance page by default; the
     * voice panel replaces it with in-panel guidance.
     */
    var onNoEngine: () -> Unit = {
        val route = if (VoiceHelp.canOfferOfflineBuild) "voice/upgrade" else "voice"
        runCatching {
            ctx.startActivity(
                android.content.Intent(ctx, com.weavetext.ime.settings.SettingsActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .setData(android.net.Uri.parse("weavetext://settings/$route")),
            )
        }
    }

    private fun hasEngine(): Boolean = runCatching { recognizerProvider().hasEngine() }.getOrDefault(true)

    fun addListener(l: () -> Unit) { listeners += l }
    private fun changed() = listeners.forEach { it() }

    val active get() = state == State.CONNECTING || state == State.LISTENING || state == State.FINALIZING

    fun hasPermission(): Boolean =
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private val connectTimeout = Runnable { if (state == State.CONNECTING) { state = State.LISTENING; changed() } }
    private val silenceCheck = object : Runnable {
        override fun run() {
            if (state != State.LISTENING) return
            // 还没开口时多等一会儿（模型可能还在加载、人也要想一想）。 Wait longer before the first word.
            val limit = if (spoke) SILENCE_MS else NO_SPEECH_MS
            if (autoStop && SystemClock.uptimeMillis() - lastLoud > limit) { stop(); return }
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
        if (state == State.CHOOSING) discard()
        if (!hasPermission()) { error = "需要麦克风权限"; changed(); return false }
        if (!hasEngine()) { error = null; state = State.IDLE; changed(); onNoEngine(); return false }
        val r = recognizerProvider()
        rec = r
        committed.clear(); partial = ""; error = null; level = 0f
        results = null; detached = false
        state = State.CONNECTING
        lastLoud = SystemClock.uptimeMillis()
        spoke = false
        floor = -1f
        val my = ++token
        val ok = r.start(object : MultiVoiceListener {
            fun live() = my == token
            override fun onEngines(engines: List<VoicePlugin>) {
                if (!live() || engines.size < 2) return
                results = MultiEngineResults(engines.map { it.id to it.name }, engines.first().id, timeoutMs)
            }
            override fun onEnginePartial(id: String, text: String) {
                val res = results ?: return
                if (!live()) return
                res.partial(id, text)
                if (id == res.primaryId) {
                    enterListening()
                    if (text.isNotEmpty()) { lastLoud = SystemClock.uptimeMillis(); spoke = true }
                }
                multiChanged()
            }
            override fun onEngineFinal(id: String, text: String) {
                val res = results ?: return
                if (!live()) return
                res.final(id, text)
                multiChanged()
            }
            override fun onEngineReplace(id: String, old: String, new: String) {
                if (!live()) return
                results?.replace(id, old, new); multiChanged()
            }
            override fun onEngineError(id: String, message: String) {
                if (!live()) return
                results?.error(id, message, clock()); multiChanged()
            }
            override fun onEngineEnd(id: String) {
                if (!live()) return
                results?.end(id, clock()); multiChanged()
            }
            override fun onPartial(text: String) {
                if (!live()) return
                enterListening()
                partial = text
                if (text.isNotEmpty()) { lastLoud = SystemClock.uptimeMillis(); spoke = true }
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
                if (results != null) {
                    // 引擎们自己结束了（未等用户停止）：直接进入结果列表。 Engines ended on their own.
                    main.removeCallbacks(silenceCheck); main.removeCallbacks(connectTimeout)
                    level = 0f
                    if (active) enterChoosing() else multiChanged()
                    return
                }
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
                if (isLoud(level)) { lastLoud = SystemClock.uptimeMillis(); spoke = true }
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

    /**
     * 是否算在说话：高于底噪约 2.5 倍且不低于一个很小的下限。有的机型语音音源不做增益、说话声很小，固定门槛会
     * 把正在说的话当成静音而提前结束。
     * Whether this level counts as speech: about 2.5× the adaptive noise floor and above a small minimum. Some
     * phones apply no gain to the voice source, so a fixed threshold would cut people off mid-sentence.
     */
    private fun isLoud(level: Float): Boolean {
        floor = when {
            floor < 0f -> level
            level < floor -> floor * 0.8f + level * 0.2f
            else -> floor + (level - floor) * 0.01f
        }
        return level > maxOf(LOUD_MIN, floor * 2.5f)
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
        main.removeCallbacks(silenceCheck)
        if (results != null) {
            rec?.stop()
            enterChoosing()
            return
        }
        state = State.FINALIZING
        changed()
        rec?.stop()
    }

    /**
     * 键盘收起 / 面板关闭时调用：单引擎等同 [stop]；多引擎在说话中则停止并在结果出来后自动上屏默认行，
     * 结果列表已显示则丢弃（用户看过列表却没选）。
     * Called when the keyboard or panel goes away. Single engine: [stop]. Multi-engine while speaking:
     * stop and auto-commit the default row when ready; if the list was already shown, discard it.
     */
    fun detach() {
        when {
            state == State.CHOOSING -> discard()
            active && results != null -> { detached = true; stop() }
            active -> stop()
        }
    }

    private fun enterChoosing() {
        val res = results ?: return
        res.stop(clock())
        state = State.CHOOSING
        partial = ""
        main.removeCallbacks(timeoutCheck)
        main.postDelayed(timeoutCheck, res.timeoutMs)
        multiChanged()
    }

    private val timeoutCheck = Runnable { multiChanged() }

    /** 结果有变化：检查超时、一致即上屏、收起状态下自动上屏。 Re-evaluate the multi-engine results. */
    private fun multiChanged() {
        val res = results ?: return
        res.tick(clock())
        if (state != State.CHOOSING) { partial = res.primaryText(); changed(); return }
        if (res.settled) {
            main.removeCallbacks(timeoutCheck)
            val same = res.unanimous()
            if (same != null) { commitText(same); return }
            if (detached) { val i = res.defaultIndex(); if (i >= 0) choose(i) else discard(); return }
            // 超时后不再等剩下的引擎。 Stop waiting for engines that timed out.
            if (rec?.isRunning == true) { token++; rec?.cancel() }
        }
        changed()
    }

    /** 点选结果列表的一行上屏。 Commit one row of the result list. */
    fun choose(index: Int) {
        val row = results?.rows()?.getOrNull(index) ?: return
        if (!row.selectable) return
        commitText(row.text)
    }

    private fun commitText(text: String) {
        controller.voiceFinal(text)
        committed.clear(); committed.append(text)
        finishMulti()
    }

    /** 丢弃结果列表。 Drop the result list. */
    private fun discard() = finishMulti()

    private fun finishMulti() {
        main.removeCallbacks(timeoutCheck)
        token++
        if (rec?.isRunning == true) rec?.cancel()
        results = null
        detached = false
        partial = ""; level = 0f
        state = State.IDLE
        changed()
    }

    /** 取消：丢弃未确定的文本。 Cancel and drop interim text. */
    fun cancel() {
        if (state == State.CHOOSING) { discard(); return }
        if (!active) { if (state == State.ERROR) { state = State.IDLE; changed() }; return }
        token++
        rec?.cancel()
        controller.voiceCancel()
        partial = ""; level = 0f
        state = State.IDLE
        main.removeCallbacks(silenceCheck); main.removeCallbacks(connectTimeout)
        changed()
    }

    /** 截图测试 / 预览：直接显示结果列表。 Screenshot tests & previews: show a result list. */
    @androidx.annotation.VisibleForTesting
    fun previewResults(res: MultiEngineResults) {
        results = res
        state = State.CHOOSING
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

    /** 每个引擎停止收音后的最长等待。 Per-engine wait after the recording stops. */
    var timeoutMs = MultiEngineResults.DEFAULT_TIMEOUT_MS

    companion object {
        private const val SILENCE_MS = 2500L
        private const val NO_SPEECH_MS = 6000L
        private const val LOUD_MIN = 0.02f
    }
}
