package com.weavetext.ime.voice.local

import android.content.Context
import android.util.Log
import com.weavetext.ime.models.AsrRuntime
import com.weavetext.ime.models.ModelKind
import com.weavetext.ime.models.ModelManager
import com.weavetext.ime.voice.ConfigField
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 本地离线语音识别引擎：两遍识别（实时模型 + 终稿模型）+ 可选智能标点，全部在手机上完成。
 * 选中的模型提前加载并缓存，闲置 20 分钟或真正内存紧张时释放。
 *
 * On-device two-pass recognition (live + final models) with optional punctuation. Models load on first
 * use, cached across recordings, and released after 20 idle minutes or real memory pressure.
 */
internal class LocalAsrEngine(private val ctx: Context, private val modelId: String? = null) {
    private val models = ModelManager.get(ctx)
    private val prefs = ctx.getSharedPreferences(LocalAsrChoice.PREFS, Context.MODE_PRIVATE)
    private val choice = LocalAsrChoice(ctx, models)
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "weave-asr").apply { isDaemon = true } }
    private var loaded: Loaded? = null
    private var idleRelease: ScheduledFuture<*>? = null
    /** 正在进行的会话数（>0 时不因内存压力释放）。 Active sessions; no pressure release while > 0. */
    private val activeSessions = java.util.concurrent.atomic.AtomicInteger()
    private val sessions = java.util.concurrent.ConcurrentHashMap.newKeySet<Session>()

    init {
        // 模型被删除/替换前先释放。 Release before a model is deleted or replaced.
        models.addReleaseHook { id ->
            val latch = java.util.concurrent.CountDownLatch(1)
            worker.execute {
                // 删除运行库时也释放全部识别器。 Deleting the runtime releases everything too.
                if (id == AsrRuntime.ID || loaded?.key?.split('|')?.contains(id) == true) {
                    for (session in sessions.toList()) session.modelRemoved()
                    loaded?.release(); loaded = null
                }
                latch.countDown()
            }
            latch.await(3, TimeUnit.SECONDS)
        }
        // 系统内存紧张且没在说话时立即释放。 Release right away under memory pressure when idle.
        ctx.registerComponentCallbacks(object : android.content.ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (AsrCachePolicy.releaseForTrim(level) && activeSessions.get() == 0) {
                    worker.execute { if (activeSessions.get() == 0) { loaded?.release(); loaded = null } }
                }
            }
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                worker.execute { if (activeSessions.get() == 0) { loaded?.release(); loaded = null } }
            }
        })
    }

    private class Loaded(val key: String, val requestKey: String, val streaming: StreamingAsr?, val offline: OfflineAsr?, val punct: Punctuator?, val detector: SpeechDetector?) {
        fun release() {
            streaming?.release()
            offline?.release()
            punct?.release()
            detector?.release()
        }
    }

    // ------------------------------------------------------------ configuration (rendered as a form)

    private fun streamingModels() = choice.streamingModels()
    private fun offlineModels() = choice.offlineModels()
    private fun streamId(): String? = if (modelId == null) choice.streamId() else
        modelId.takeIf { models.catalog.find(it)?.kind == ModelKind.ASR_STREAMING && models.isAvailable(it) }
    private fun finalId(): String? = if (modelId == null) choice.finalId() else
        modelId.takeIf { models.catalog.find(it)?.kind == ModelKind.ASR_OFFLINE && models.isAvailable(it) }

    private fun punctuationOn(): Boolean =
        prefs.getBoolean(KEY_PUNCT, true) && models.isAvailable(PUNCT_ID)

    /**
     * 运行库在（随包或已下载）且有识别模型：实时模型，或只有终稿模型（整句识别）。
     * Runtime present (bundled or downloaded) and a model: a streaming one, or a final one alone (whole sentences).
     */
    val isAvailable: Boolean get() = AsrRuntime.ready(models) && (streamId() != null || finalId() != null)

    /** 已装的识别模型组成的签名：变了就要重建引擎列表与设置表单。 Installed-model signature; a change rebuilds the list. */
    fun modelSignature(): String =
        (streamingModels() + offlineModels()).joinToString(",") { it.id } + "|" + models.isAvailable(PUNCT_ID) + "|" + models.isAvailable(VAD_ID) + "|" + AsrRuntime.ready(models)

    fun fields(): List<ConfigField> {
        val punctInstalled = models.isAvailable(PUNCT_ID)
        val stream = streamingModels()
        val fields = listOfNotNull(
            ConfigField(
                KEY_STREAM, "实时模型", "select", section = "模型",
                options = stream.map { it.name } + if (offlineModels().isNotEmpty()) listOf(NONE_LABEL) else emptyList(),
                defaultValue = stream.firstOrNull()?.name,
                helpText = "说话时实时显示文字",
            ).takeIf { stream.isNotEmpty() },
            ConfigField(
                KEY_FINAL, "终稿模型", "select", section = "模型",
                // 没有实时模型时终稿模型就是唯一的识别模型，不能不用。 Without a streaming model it can't be turned off.
                options = offlineModels().map { it.name } + (if (streamId() == null) emptyList() else listOf(NONE_LABEL)),
                defaultValue = offlineModels().firstOrNull()?.name ?: NONE_LABEL,
                helpText = if (stream.isEmpty()) "没有实时模型：说完一句后整句识别"
                    else "每句说完后用它再识别一遍，更准；选「不使用」则直接用实时结果",
            ),
            ConfigField(
                KEY_PUNCT, "智能标点", "switch", section = "模型",
                defaultValue = "true",
                helpText = if (punctInstalled) "为识别结果补全标点" else "需先在「离线模型」中下载「智能标点」",
            ),
        )
        return if (modelId == null) fields else fields.filter { it.key == KEY_PUNCT }
    }

    fun getConfig(key: String): String? = when (key) {
        KEY_STREAM -> streamId()?.let { id -> models.catalog.find(id)?.name } ?: NONE_LABEL
        KEY_FINAL -> finalId()?.let { id -> models.catalog.find(id)?.name } ?: NONE_LABEL
        KEY_PUNCT -> prefs.getBoolean(KEY_PUNCT, true).toString()
        else -> null
    }

    fun setConfig(key: String, value: String) {
        when (key) {
            KEY_STREAM -> if (value == NONE_LABEL && offlineModels().isNotEmpty()) choice.setStream(null)
                else streamingModels().firstOrNull { it.name == value }?.let { choice.setStream(it.id) }
            KEY_FINAL -> if (value == NONE_LABEL) choice.setFinal(null)
                else offlineModels().firstOrNull { it.name == value }?.let { choice.setFinal(it.id) }
            KEY_PUNCT -> prefs.edit().putBoolean(KEY_PUNCT, value == "true").apply()
        }
    }

    // ------------------------------------------------------------ sessions

    /** 一次识别会话；所有方法可在任意线程调用，识别在专属线程上进行。 One session; thread-safe entry points. */
    inner class Session(private val listener: TwoPassListener, private val onEnd: () -> Unit, private val onError: (String) -> Unit, private val onReady: () -> Unit = {}) {
        private val language = OfflineModelSelection(ctx, models).mode.key
        @Volatile private var cancelled = false
        private var recognizer: TwoPassRecognizer? = null
        /**
         * 会话计数只减一次：先停止再取消（按住说话时上滑取消、多引擎超时）会两条路都走到，减两次会让别的会话
         * 在用的模型被内存回收释放掉。
         * The session count drops exactly once: stop followed by cancel (swipe-up cancel while finalizing, a
         * multi-engine timeout) reaches both paths, and a double drop would let a memory trim release models
         * another session is using.
         */
        private val counted = java.util.concurrent.atomic.AtomicBoolean(true)

        private fun uncount() {
            if (counted.compareAndSet(true, false)) { sessions.remove(this); activeSessions.updateAndGet { maxOf(0, it - 1) } }
        }

        /** 停止后到的录音丢掉（录音线程可能还在交最后一块）。 Audio arriving after stop is dropped. */
        @Volatile private var stopped = false
        private val pendingBytes = java.util.concurrent.atomic.AtomicInteger()

        init {
            activeSessions.incrementAndGet()
            sessions.add(this)
            worker.execute {
                idleRelease?.cancel(false)
                if (cancelled) { uncount(); scheduleIdleRelease(); return@execute }
                recognizer = runCatching {
                    val l = ensureLoaded()
                    if (cancelled) return@runCatching null
                    l.offline?.setLanguage(language)
                    l.streaming?.startSession()
                    l.detector?.reset()
                    TwoPassRecognizer(l.streaming, l.offline, l.punct, object : TwoPassListener {
                        override fun onPartial(text: String) { if (!cancelled) listener.onPartial(text) }
                        override fun onFinal(text: String) { if (!cancelled) listener.onFinal(text) }
                    }, speechDetector = l.detector)
                }.onFailure {
                    Log.e(TAG, "load failed", it)
                    if (!cancelled) { onError("离线模型准备失败：${it.message}"); stopped = true; uncount(); onEnd() }
                }.getOrNull()
                if (recognizer != null && !cancelled && !stopped) onReady()
            }
        }

        fun feed(pcm: ByteArray, size: Int) {
            if (stopped || cancelled) return
            val copy = pcm.copyOf(size)
            if (pendingBytes.addAndGet(copy.size) > 16_000 * 2 * 60) {
                pendingBytes.addAndGet(-copy.size)
                cancel()
                onError("这个模型在当前手机上处理较慢，请改用「实时识别 · 小」")
                return
            }
            worker.execute {
                pendingBytes.addAndGet(-copy.size)
                if (!cancelled) runCatching { recognizer?.feedPcm16(copy) }.onFailure {
                    Log.e(TAG, "decode failed", it)
                    cancelled = true
                    uncount()
                    scheduleIdleRelease()
                    onError("离线识别失败，请重试或换一个模型")
                }
            }
        }

        /** 录音结束：出最后一句终稿后回调 onEnd。 Finish: emit the last final, then onEnd. */
        fun stop() {
            if (stopped) return
            stopped = true
            worker.execute {
                if (!cancelled) runCatching { recognizer?.finish() }.onFailure {
                    Log.w(TAG, "finish", it)
                    onError("离线识别未能完成，请重试或换一个模型")
                }
                if (!counted.get()) return@execute
                uncount()
                scheduleIdleRelease()
                onEnd()
            }
        }

        fun cancel() {
            if (cancelled) return
            cancelled = true
            worker.execute {
                if (!counted.get()) return@execute
                uncount()
                scheduleIdleRelease()
            }
        }

        fun modelRemoved() {
            cancelled = true
            stopped = true
            uncount()
            onError("模型已被卸载或更新，请重新选择模型")
        }
    }

    /**
     * 在工作线程上加载（或复用）当前配置的模型。可用内存确实不够时依次放弃标点、终稿模型（有实时模型时），
     * 避免输入法进程被系统杀掉；任一步创建失败都会释放已创建的部分。
     * Load or reuse the configured models on the worker thread. When memory really is short, drop punctuation
     * and then the final pass (if there is a streaming model) rather than risk the IME being killed; partial
     * loads are released on failure.
     */
    private fun ensureLoaded(): Loaded {
        val sid = streamId()
        var fid = finalId()
        if (sid == null && fid == null) error("没有可用的识别模型")
        var punct = punctuationOn()
        val detectorId = VAD_ID.takeIf { models.isAvailable(VAD_ID) }
        val requestKey = "$sid|$fid|${if (punct) PUNCT_ID else null}|$detectorId"
        loaded?.let { if (it.requestKey == requestKey) return it }
        val budget = memoryBudget()
        val size = { id: String? -> id?.let { models.catalog.find(it)?.installedSize } ?: 0L }
        // 运行时占用约为模型文件的 1.2 倍（int8 权重 + 工作区）。 Runtime RSS ≈ 1.2 × file size (int8 weights + work area).
        val need = { size(sid) + size(fid) + (if (punct) size(PUNCT_ID) else 0L) }
        if (need() * 6 / 5 > budget && punct) punct = false
        if (need() * 6 / 5 > budget && fid != null && sid != null) fid = null
        if (fid != finalId() || punct != punctuationOn()) Log.w(TAG, "low memory ($budget bytes): final=$fid punct=$punct")
        // 键里放模型 id（含标点模型），删除某个模型时据此找到要释放的识别器。 Model ids, so a delete finds its user.
        val key = "$sid|$fid|${if (punct) PUNCT_ID else null}|$detectorId"
        loaded?.let { if (it.key == key) return it; it.release(); loaded = null }
        val t0 = System.nanoTime()
        var streaming: StreamingAsr? = null
        var offline: OfflineAsr? = null
        var detector: SpeechDetector? = null
        var p: Punctuator? = null
        try {
            AsrLoadQueue.load {
                SherpaModels.prepare(models.runtimeDir())
                val threads = AsrCachePolicy.threads(OfflineModelSelection(ctx, models).ids().size)
                val speechBytes = size(sid) + size(fid)
                val reserve = if (fid?.let { models.catalog.find(it)?.arch } == "whisper") speechBytes * 3 / 2 + 64L * 1024 * 1024
                    else speechBytes * 6 / 5 + 32L * 1024 * 1024
                if (reserve > memoryBudget()) error("当前内存不足，请减少同时使用的模型或选择较小的模型")
                streaming = sid?.let { id -> SherpaModels.streaming(models.catalog.find(id)!!, models.location(id)!!, threads) }
                offline = fid?.let { id -> SherpaModels.offline(models.catalog.find(id)!!, models.location(id)!!, threads) }
                detector = detectorId?.let { models.location(it)?.let(SherpaModels::detector) }
                p = if (punct) models.location(PUNCT_ID)?.let { SherpaModels.punctuator(it) } else null
            }
            Log.i(TAG, "models $key loaded in ${(System.nanoTime() - t0) / 1_000_000} ms")
            return Loaded(key, requestKey, streaming, offline, p, detector).also { loaded = it }
        } catch (t: Throwable) {
            runCatching { offline?.release() }
            runCatching { streaming?.release() }
            runCatching { detector?.release() }
            runCatching { p?.release() }
            throw t
        }
    }

    /**
     * 可用于模型的内存：系统可用内存减去低内存线（系统从这里开始杀后台），至少留可用内存的一半。
     * Memory for models: available memory minus the low-memory threshold (where the system starts killing), at
     * least half of what is available.
     */
    private fun memoryBudget(): Long {
        val am = ctx.getSystemService(android.app.ActivityManager::class.java) ?: return Long.MAX_VALUE
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return maxOf(info.availMem - info.threshold, info.availMem / 2)
    }

    /** 预热：打开语音面板时调用，缩短第一次说话的等待。 Warm up when the voice panel opens. */
    fun preload(done: () -> Unit = {}) {
        worker.execute {
            // 有会话在用时不换模型（换会释放它正用着的）。 Don't swap models under a running session.
            if (activeSessions.get() > 0) { done(); return@execute }
            idleRelease?.cancel(false)
            runCatching { ensureLoaded() }.onFailure { Log.w(TAG, "preload failed", it) }
            scheduleIdleRelease()
            done()
        }
    }

    private fun scheduleIdleRelease() {
        idleRelease?.cancel(false)
        idleRelease = worker.schedule({ if (activeSessions.get() == 0) { loaded?.release(); loaded = null } }, AsrCachePolicy.IDLE_MINUTES, TimeUnit.MINUTES)
    }

    fun releaseIdle() {
        worker.execute {
            if (activeSessions.get() == 0) { idleRelease?.cancel(false); loaded?.release(); loaded = null }
        }
    }

    companion object {
        private const val TAG = "WeaveLocalAsr"
        const val PUNCT_ID = "punc-ct"
        const val VAD_ID = "vad-silero"
        private const val KEY_STREAM = LocalAsrChoice.KEY_STREAM
        private const val KEY_FINAL = LocalAsrChoice.KEY_FINAL
        private const val KEY_PUNCT = "punctuation"
        private const val NONE_LABEL = "不使用"
    }
}
