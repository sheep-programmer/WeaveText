package com.weavetext.ime.ui.keyboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.weavetext.ime.ime.InputController
import com.weavetext.ime.ui.VoiceAccess
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
    /**
     * 一行提示（如系统识别用不了、已改用本地识别），不算错误；下次开始时清掉。
     * A one-line note (e.g. the system engine failed and local took over), not an error; cleared on the next start.
     */
    var notice: String? = null
        private set
    /** 平滑后的音量 0..1。 Smoothed input level. */
    var level = 0f
        private set
    /** Recent microphone levels, oldest first, for the waveform. */
    val levels = FloatArray(17)
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
    private var token = 0
    /** 识别器最近一次回调的时间：长时间没有回调说明它卡住了。 Last callback; a long silence means it is stuck. */
    private var lastActivity = 0L
    /** 进入「识别中」的时间。 When FINALIZING began. */
    private var finalizingSince = 0L

    /**
     * 没有任何可用引擎时调用（不报错）：默认打开设置里的引导页，语音面板在时改为在面板里给出办法。
     * Called instead of an error when no engine is available: opens the guidance page by default; the
     * voice panel replaces it with in-panel guidance.
     */
    var onNoEngine: () -> Unit = {
        val route = "voice/upgrade"
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

    // 兜底时限：识别器一直连不上、或说完后迟迟不给结果时结束会话（已显示的文字照常上屏），不会卡住。
    // Last-resort limits: end the session (keeping the text shown) when the recognizer never connects or never
    // delivers after the stop, so the session can't hang.
    private val connectGuard = Runnable { if (state == State.CONNECTING) settle(NO_RESPONSE) }
    private val finalizeGuard = Runnable { if (state == State.FINALIZING) settle() }
    private fun clearTimers() {
        main.removeCallbacks(connectGuard)
        main.removeCallbacks(finalizeGuard)
    }

    /** 面板打开时预热识别器（如提前加载本地模型），失败不影响后续使用。 Warm up when the panel opens. */
    fun warmUp() {
        runCatching { recognizerProvider().warmUp() }
    }

    /** 开始；缺少权限返回 false。 Start; false without mic permission. */
    fun start(): Boolean {
        if (active) {
            // 正常进行中：不重复开始。旧会话卡住（识别中已等了一会儿，或很久没有任何回调）：结束它，重新开始。
            // Running normally: nothing to do. A stuck session (finalizing for a while, or no callback for long):
            // end it and start over.
            val now = clock()
            val stuck = (state == State.FINALIZING && now - finalizingSince > TAKEOVER_MS) || now - lastActivity > STALE_MS
            if (!stuck) return true
            settle()
        }
        if (state == State.CHOOSING) discard()
        if (!hasPermission()) { error = "需要麦克风权限"; changed(); return false }
        if (!hasEngine()) { error = null; state = State.IDLE; changed(); onNoEngine(); return false }
        val r = recognizerProvider()
        rec = r
        committed.clear(); partial = ""; error = null; notice = null; level = 0f
        results = null; detached = false; levels.fill(0f)
        state = State.CONNECTING
        lastActivity = clock()
        val my = ++token
        val ok = r.start(object : MultiVoiceListener {
            // 已结束或报错的会话不能再把迟到结果写回编辑器；多引擎选结果时仍接收各引擎的收尾。
            // Ended/failed sessions must not write late results; multi-engine choosing still accepts engine completions.
            fun live() = (my == token && (active || state == State.CHOOSING)).also { if (it) lastActivity = clock() }
            override fun onEngines(engines: List<VoicePlugin>) {
                if (!live() || engines.size < 2) return
                results = MultiEngineResults(engines.take(3).map { it.id to it.name }, engines.first().id, timeoutMs)
            }
            override fun onEnginePartial(id: String, text: String) {
                val res = results ?: return
                if (!live()) return
                res.partial(id, text)
                if (id == res.primaryId) {
                    enterListening()
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
                partial = com.weavetext.ime.voice.local.TwoPassRecognizer.continuation(committed.lastOrNull(), text)
                controller.voicePartial(partial)
                changed()
            }
            override fun onFinal(text: String) {
                if (!live()) return
                val segment = com.weavetext.ime.voice.local.TwoPassRecognizer.continuation(committed.lastOrNull(), text)
                controller.voiceFinal(segment)
                committed.append(segment)
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
                // 先上屏已显示的文字：出错前识别出的内容不丢。 Keep the text already shown.
                if (partial.isNotEmpty() && results == null) { controller.voiceFinal(partial); committed.append(partial) }
                controller.voiceFinal("")
                partial = ""
                error = message
                state = State.ERROR
                clearTimers()
                changed()
                // 出错即结束：识别器若还在录音就停掉（放开麦克风与音频焦点）。
                // An error ends the session: stop a recognizer that is still recording (frees the mic and focus).
                main.post { if (my == token && state == State.ERROR && rec?.isRunning == true) { token++; rec?.cancel() } }
            }
            override fun onReady(selfEnd: Boolean) {
                if (!live()) return
                enterListening()
                changed()
            }
            override fun onNotice(message: String) {
                if (!live()) return
                notice = message
                changed()
            }
            override fun onEnd() {
                if (!live()) return
                if (results != null) {
                    // 引擎们自己结束了（未等用户停止）：直接进入结果列表。 Engines ended on their own.
                    clearTimers()
                    level = 0f
                    if (active) enterChoosing() else multiChanged()
                    return
                }
                if (partial.isNotEmpty()) { controller.voiceFinal(partial); committed.append(partial); partial = "" }
                controller.voiceFinal("")
                if (state != State.ERROR) state = State.IDLE
                level = 0f
                clearTimers()
                changed()
            }
            override fun onLevel(level: Float) {
                if (!live()) return
                val target = level.coerceIn(0f, 1f)
                val weight = if (target > this@VoiceSession.level) 0.65f else 0.18f
                this@VoiceSession.level += (target - this@VoiceSession.level) * weight
                System.arraycopy(levels, 1, levels, 0, levels.size - 1)
                levels[levels.lastIndex] = this@VoiceSession.level
                changed()
            }
        })
        if (!ok) {
            if (state == State.CONNECTING) { state = State.ERROR; error = error ?: "无法开始录音" }
            changed()
            return false
        }
        if (state == State.CONNECTING) main.postDelayed(connectGuard, CONNECT_LIMIT_MS)
        changed()
        return true
    }

    private fun enterListening() {
        if (state == State.CONNECTING) {
            state = State.LISTENING
            main.removeCallbacks(connectGuard)
        }
    }

    /** 结束收音，等待最终结果。 Stop and wait for the final result. */
    fun stop() {
        if (!active || state == State.FINALIZING) return
        main.removeCallbacks(connectGuard)
        if (results != null) {
            rec?.stop()
            enterChoosing()
            return
        }
        state = State.FINALIZING
        finalizingSince = clock()
        main.postDelayed(finalizeGuard, FINALIZE_LIMIT_MS)
        changed()
        rec?.stop()
    }

    /**
     * 马上结束：已显示的文字上屏，不再等（也不要）识别器后面的结果。面板上的回车、逗号、删除键先调它，
     * 否则晚到的结果会落进已经发出去的输入框，或排到逗号后面。[message] 非空时以错误结束。
     * End right now: commit the text on screen and drop whatever the recognizer would still deliver. The panel's
     * Enter, comma and delete keys call this first, or a late result would land in an already-sent box or after
     * the comma. A non-null [message] ends in the error state.
     */
    fun settle(message: String? = null) {
        if (!active && state != State.CHOOSING) return
        val res = results
        val shown = if (res != null) res.primaryText() else partial
        token++
        rec?.cancel()
        clearTimers()
        main.removeCallbacks(timeoutCheck)
        if (res != null && state == State.CHOOSING) {
            // 结果列表：上屏默认行。 Result list: commit the default row.
            res.rows().getOrNull(res.defaultIndex())?.takeIf { it.selectable }?.let { controller.voiceFinal(it.text) }
        } else if (shown.isNotEmpty()) {
            controller.voiceFinal(shown)
            committed.append(shown)
        }
        controller.voiceFinal("")
        results = null; detached = false; levels.fill(0f)
        partial = ""; level = 0f
        if (message != null) { error = message; state = State.ERROR } else state = State.IDLE
        changed()
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
            // 已在收尾：等结果，最多等到兜底时限。 Already finalizing: the finalize limit bounds the wait.
            active -> stop()
            // 错误状态：确保识别器已停，下次打开面板从头开始。 Error: make sure the recognizer is stopped.
            state == State.ERROR -> cancel()
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
            // Even matching models remain separate rows: the user explicitly chooses one.
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
        if (!active) {
            if (state == State.ERROR) {
                if (rec?.isRunning == true) { token++; rec?.cancel() }
                state = State.IDLE
                changed()
            }
            return
        }
        token++
        rec?.cancel()
        controller.voiceCancel()
        partial = ""; level = 0f
        state = State.IDLE
        clearTimers()
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
        this.partial = partial; this.level = level; levels.fill(level); this.error = error
        changed()
    }

    /** 每个引擎停止收音后的最长等待。 Per-engine wait after the recording stops. */
    var timeoutMs = MultiEngineResults.DEFAULT_TIMEOUT_MS

    companion object {
        /** Cold model loading can take tens of seconds on slower phones. */
        const val CONNECT_LIMIT_MS = 90_000L
        /** 停止后等结果的时限。 Limit for the result after stopping. */
        const val FINALIZE_LIMIT_MS = 60_000L
        /** 「识别中」超过这么久，再点麦克风就不等了，重新开始。 After this long in FINALIZING a mic tap starts over. */
        const val TAKEOVER_MS = 8_000L
        /** 这么久没有任何回调算卡住。 No callback for this long counts as stuck. */
        const val STALE_MS = 120_000L
        private const val NO_RESPONSE = "离线模型加载超时，请重试或换一个较小模型"
    }
}
