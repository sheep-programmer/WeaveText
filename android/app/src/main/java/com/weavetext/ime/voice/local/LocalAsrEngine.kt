package com.weavetext.ime.voice.local

import android.content.Context
import android.util.Log
import com.weavetext.ime.models.ModelKind
import com.weavetext.ime.models.ModelManager
import com.weavetext.ime.voice.ConfigField
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 本地离线语音识别引擎：两遍识别（实时模型 + 终稿模型）+ 可选智能标点，全部在手机上完成。
 * 模型首次使用时加载，闲置 3 分钟后释放（两个内置小模型约占 100 MB 内存）。
 *
 * On-device two-pass recognition (live + final models) with optional punctuation. Models load on first
 * use and are released after 3 idle minutes (~100 MB for the two built-in models).
 */
internal class LocalAsrEngine(private val ctx: Context) {
    private val models = ModelManager.get(ctx)
    private val prefs = ctx.getSharedPreferences("weave_local_asr", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "weave-asr").apply { isDaemon = true } }
    private var loaded: Loaded? = null
    private var idleRelease: ScheduledFuture<*>? = null
    /** 正在进行的会话数（>0 时不因内存压力释放）。 Active sessions; no pressure release while > 0. */
    @Volatile private var activeSessions = 0

    init {
        // 模型被删除/替换前先释放。 Release before a model is deleted or replaced.
        models.addReleaseHook { id ->
            val latch = java.util.concurrent.CountDownLatch(1)
            worker.execute {
                if (loaded?.key?.split('|')?.contains(id) == true) { loaded?.release(); loaded = null }
                latch.countDown()
            }
            latch.await(3, TimeUnit.SECONDS)
        }
        // 系统内存紧张且没在说话时立即释放。 Release right away under memory pressure when idle.
        ctx.registerComponentCallbacks(object : android.content.ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && activeSessions == 0) {
                    worker.execute { if (activeSessions == 0) { loaded?.release(); loaded = null } }
                }
            }
            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}
            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                worker.execute { if (activeSessions == 0) { loaded?.release(); loaded = null } }
            }
        })
    }

    private class Loaded(val key: String, val streaming: StreamingAsr, val offline: OfflineAsr?, val punct: Punctuator?) {
        fun release() {
            streaming.release()
            offline?.release()
            punct?.release()
        }
    }

    // ------------------------------------------------------------ configuration (rendered as a form)

    private fun streamingModels() = models.available(ModelKind.ASR_STREAMING)
    private fun offlineModels() = models.available(ModelKind.ASR_OFFLINE)

    private fun streamId(): String? {
        val saved = prefs.getString(KEY_STREAM, null)
        val list = streamingModels()
        return list.firstOrNull { it.id == saved }?.id ?: list.firstOrNull()?.id
    }

    private fun finalId(): String? {
        val saved = prefs.getString(KEY_FINAL, null)
        if (saved == NONE) return null
        val list = offlineModels()
        return list.firstOrNull { it.id == saved }?.id ?: list.firstOrNull()?.id
    }

    private fun punctuationOn(): Boolean =
        prefs.getBoolean(KEY_PUNCT, true) && models.isAvailable(PUNCT_ID)

    val isAvailable: Boolean get() = streamId() != null

    fun fields(): List<ConfigField> {
        val punctInstalled = models.isAvailable(PUNCT_ID)
        return listOf(
            ConfigField(
                KEY_STREAM, "实时模型", "select", section = "模型",
                options = streamingModels().map { it.name },
                defaultValue = streamingModels().firstOrNull()?.name,
                helpText = "说话时实时显示文字",
            ),
            ConfigField(
                KEY_FINAL, "终稿模型", "select", section = "模型",
                options = offlineModels().map { it.name } + NONE_LABEL,
                defaultValue = offlineModels().firstOrNull()?.name ?: NONE_LABEL,
                helpText = "每句说完后用它再识别一遍，更准；选「不使用」则直接用实时结果",
            ),
            ConfigField(
                KEY_PUNCT, "智能标点", "switch", section = "模型",
                defaultValue = "true",
                helpText = if (punctInstalled) "为识别结果补全标点" else "需先在「离线模型」中下载「智能标点」",
            ),
        )
    }

    fun getConfig(key: String): String? = when (key) {
        KEY_STREAM -> streamId()?.let { id -> models.catalog.find(id)?.name }
        KEY_FINAL -> finalId()?.let { id -> models.catalog.find(id)?.name } ?: NONE_LABEL
        KEY_PUNCT -> prefs.getBoolean(KEY_PUNCT, true).toString()
        else -> null
    }

    fun setConfig(key: String, value: String) {
        val e = prefs.edit()
        when (key) {
            KEY_STREAM -> streamingModels().firstOrNull { it.name == value }?.let { e.putString(KEY_STREAM, it.id) }
            KEY_FINAL -> if (value == NONE_LABEL) e.putString(KEY_FINAL, NONE)
                else offlineModels().firstOrNull { it.name == value }?.let { e.putString(KEY_FINAL, it.id) }
            KEY_PUNCT -> e.putBoolean(KEY_PUNCT, value == "true")
        }
        e.apply()
    }

    // ------------------------------------------------------------ sessions

    /** 一次识别会话；所有方法可在任意线程调用，识别在专属线程上进行。 One session; thread-safe entry points. */
    inner class Session(private val listener: TwoPassListener, private val onEnd: () -> Unit, private val onError: (String) -> Unit) {
        @Volatile private var cancelled = false
        private var recognizer: TwoPassRecognizer? = null

        init {
            activeSessions++
            worker.execute {
                idleRelease?.cancel(false)
                recognizer = runCatching { ensureLoaded() }
                    .onFailure { Log.e(TAG, "load failed", it); if (!cancelled) onError("离线模型加载失败：${it.message}") }
                    .getOrNull()
                    ?.let { l ->
                        l.streaming.reset()
                        TwoPassRecognizer(l.streaming, l.offline, l.punct, object : TwoPassListener {
                            override fun onPartial(text: String) { if (!cancelled) listener.onPartial(text) }
                            override fun onFinal(text: String) { if (!cancelled) listener.onFinal(text) }
                        })
                    }
            }
        }

        fun feed(pcm: ByteArray, size: Int) {
            val copy = pcm.copyOf(size)
            worker.execute { if (!cancelled) runCatching { recognizer?.feedPcm16(copy) } }
        }

        /** 录音结束：出最后一句终稿后回调 onEnd。 Finish: emit the last final, then onEnd. */
        fun stop() {
            worker.execute {
                if (!cancelled) runCatching { recognizer?.finish() }.onFailure { Log.w(TAG, "finish", it) }
                activeSessions = (activeSessions - 1).coerceAtLeast(0)
                scheduleIdleRelease()
                onEnd()
            }
        }

        fun cancel() {
            if (cancelled) return
            cancelled = true
            worker.execute {
                activeSessions = (activeSessions - 1).coerceAtLeast(0)
                scheduleIdleRelease()
            }
        }
    }

    /**
     * 在工作线程上加载（或复用）当前配置的模型。可用内存不够时依次放弃标点、终稿模型，只用实时模型，
     * 避免输入法进程被系统杀掉；任一步创建失败都会释放已创建的部分。
     * Load or reuse the configured models on the worker thread. When memory is short, drop punctuation
     * and then the final pass rather than risk the IME being killed; partial loads are released on failure.
     */
    private fun ensureLoaded(): Loaded {
        val sid = streamId() ?: error("没有可用的实时模型")
        var fid = finalId()
        var punct = punctuationOn()
        val budget = memoryBudget()
        val size = { id: String? -> id?.let { models.catalog.find(it)?.installedSize } ?: 0L }
        // 运行时占用约为模型文件的 1.5 倍（权重 + 工作区）。 Runtime RSS ≈ 1.5 × file size.
        val need = { size(sid) + size(fid) + (if (punct) size(PUNCT_ID) else 0L) }
        if (need() * 3 / 2 > budget && punct) punct = false
        if (need() * 3 / 2 > budget && fid != null) fid = null
        if (fid != finalId() || punct != punctuationOn()) Log.w(TAG, "low memory ($budget bytes): final=$fid punct=$punct")
        val key = "$sid|$fid|$punct"
        loaded?.let { if (it.key == key) return it; it.release(); loaded = null }
        val t0 = System.nanoTime()
        var streaming: StreamingAsr? = null
        var offline: OfflineAsr? = null
        try {
            streaming = SherpaModels.streaming(models.catalog.find(sid)!!, models.location(sid)!!)
            offline = fid?.let { id -> SherpaModels.offline(models.catalog.find(id)!!, models.location(id)!!) }
            val p = if (punct) models.location(PUNCT_ID)?.let { SherpaModels.punctuator(it) } else null
            Log.i(TAG, "models $key loaded in ${(System.nanoTime() - t0) / 1_000_000} ms")
            return Loaded(key, streaming, offline, p).also { loaded = it }
        } catch (t: Throwable) {
            runCatching { offline?.release() }
            runCatching { streaming?.release() }
            throw t
        }
    }

    /** 可用于模型的内存：系统可用内存的一半。 Half of the system's available memory. */
    private fun memoryBudget(): Long {
        val am = ctx.getSystemService(android.app.ActivityManager::class.java) ?: return Long.MAX_VALUE
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / 2
    }

    /** 预热：打开语音面板时调用，缩短第一次说话的等待。 Warm up when the voice panel opens. */
    fun preload() {
        worker.execute {
            idleRelease?.cancel(false)
            runCatching { ensureLoaded() }.onFailure { Log.w(TAG, "preload failed", it) }
            scheduleIdleRelease()
        }
    }

    private fun scheduleIdleRelease() {
        idleRelease?.cancel(false)
        idleRelease = worker.schedule({ loaded?.release(); loaded = null }, IDLE_RELEASE_MINUTES, TimeUnit.MINUTES)
    }

    companion object {
        private const val TAG = "WeaveLocalAsr"
        const val PUNCT_ID = "punc-ct"
        private const val KEY_STREAM = "stream_model"
        private const val KEY_FINAL = "final_model"
        private const val KEY_PUNCT = "punctuation"
        private const val NONE = "none"
        private const val NONE_LABEL = "不使用"
        private const val IDLE_RELEASE_MINUTES = 3L
    }
}
