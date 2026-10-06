package com.weavetext.ime.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.weavetext.ime.voice.local.LocalAsrEngine
import com.weavetext.ime.voice.local.TwoPassListener
import java.util.concurrent.Executors

/** Offline models are the default; explicitly installed and selected plugins share the same capture. */
internal object VoiceBackend {
    fun create(ctx: Context): Pair<VoiceEngines, VoiceRecognizer> {
        val engines = OfflineEngines(ctx)
        return engines to Recognizer(ctx, engines)
    }
}

private const val TAG = "WeaveVoice"
internal const val LOCAL_ENGINE_ID = "weave.local"
internal fun isBuiltinEngine(id: String) = id == LOCAL_ENGINE_ID || id.startsWith("asr-")

/** Every installed recognizer is a selectable engine; all selected engines share PCM. */
private class OfflineEngines(private val ctx: Context) : VoiceEngines {
    private val models = com.weavetext.ime.models.ModelManager.get(ctx)
    private val selected = com.weavetext.ime.voice.local.OfflineModelSelection(ctx, models)
    val plugins = NativePlugins(ctx)
    private val pluginSelection = ctx.getSharedPreferences("weave_plugin_selection", Context.MODE_PRIVATE)
    override var language: VoiceLanguage
        get() = selected.mode
        set(value) { selected.mode = value }
    private val instances = java.util.concurrent.ConcurrentHashMap<String, LocalAsrEngine>()
    fun local(id: String) = instances.getOrPut(id) { LocalAsrEngine(ctx, id) }
    override fun list(): List<VoicePlugin> = offline() + plugins.list()
    private fun offline(): List<VoicePlugin> = if (!com.weavetext.ime.models.AsrRuntime.ready(models)) emptyList() else selected.available().map { model ->
        VoicePlugin(model.id, model.name, com.weavetext.ime.voice.local.OfflineModelSelection.language(model) + " · " + model.description,
            "", null, listOf(ConfigField("punctuation", "智能标点", "switch", defaultValue = "true")))
    }
    override var activeId: String?
        get() = pluginSelection.getString("active", null)?.takeIf { id -> plugins.list().any { it.id == id } } ?: selected.ids().firstOrNull()
        set(value) {
            val id = if (value == LOCAL_ENGINE_ID) selected.ids().firstOrNull() else value
            if (id != null) setSelection(listOf(id))
        }
    override var extraIds: Set<String>
        get() = if (activeId?.let(::isBuiltinEngine) == false) pluginSelection.getStringSet("also", emptySet()).orEmpty()
            else selected.ids().drop(1).toSet() + pluginSelection.getStringSet("also", emptySet()).orEmpty()
        set(value) { setSelection(listOfNotNull(activeId) + value) }
    override fun setSelection(ids: List<String>) {
        val available = list().map { it.id }.toSet()
        val valid = ids.filter { it in available }.distinct().take(3)
        val primary = valid.firstOrNull() ?: return
        if (isBuiltinEngine(primary)) {
            selected.select(valid.filter(::isBuiltinEngine))
            pluginSelection.edit().remove("active").putStringSet("also", valid.filterNot(::isBuiltinEngine).toSet()).apply()
        } else pluginSelection.edit().putString("active", primary).putStringSet("also", valid.drop(1).toSet()).apply()
    }
    override fun selectionSatisfiesMode(): Boolean = activeId?.let(::isBuiltinEngine) == false || selected.primaryOk()
    override fun selection(): List<VoicePlugin> {
        val installed = list().associateBy { it.id }
        val primary = activeId ?: return emptyList()
        return (listOf(primary) + extraIds).distinct().take(3).mapNotNull { installed[it] }
    }
    fun preload() {
        val ids = selection().map { it.id }.filter(::isBuiltinEngine).toSet()
        for ((id, engine) in instances) if (id !in ids) engine.releaseIdle()
        val ordered = ids.toList()
        val first = ordered.firstOrNull() ?: return
        local(first).preload {
            if (selection().map { it.id }.toSet() == ids) for (id in ordered.drop(1)) local(id).preload()
        }
    }
    override fun inspect(xipkPath: String) = plugins.inspect(xipkPath)
    override fun install(xipkPath: String) = plugins.install(xipkPath)
    override fun uninstall(id: String): Result<Unit> = if (isBuiltinEngine(id)) Result.failure(IllegalArgumentException("请在语音包页卸载模型")) else plugins.uninstall(id).onSuccess {
        if (pluginSelection.getString("active", null) == id) pluginSelection.edit().remove("active").remove("also").apply()
    }
    override fun getConfig(id: String, key: String): String? = if (isBuiltinEngine(id)) local(id).getConfig(key) else plugins.getConfig(id, key)
    override fun setConfig(id: String, key: String, value: String) { if (isBuiltinEngine(id)) local(id).setConfig(key, value) else plugins.setConfig(id, key, value) }
}

/** Capture and decode have separate lifetimes: a sentence endpoint never closes the microphone. */
private class Recognizer(private val ctx: Context, private val engines: OfflineEngines) : VoiceRecognizer {
    private val main = Handler(Looper.getMainLooper())
    private val gainControl = VoiceGain()
    private val audioThread = Executors.newSingleThreadExecutor { r -> Thread(r, "weave-audio") }
    @Volatile private var recording = false
    @Volatile private var generation = 0
    private var listener: VoiceListener? = null
    private var runs: List<EngineRun> = emptyList()
    private var stopping = false
    private var captureQueued = false
    override val isRunning: Boolean get() = listener != null

    private val audioManager = ctx.getSystemService(android.media.AudioManager::class.java)
    private var focusRequest: android.media.AudioFocusRequest? = null

    private fun requestFocus() {
        if (android.os.Build.VERSION.SDK_INT < 26) return
        val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                if (change == android.media.AudioManager.AUDIOFOCUS_LOSS ||
                    (change == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && inCall())
                ) {
                    main.post { if (isRunning) stop() }
                }
            }
            .build()
        focusRequest = req
        runCatching { audioManager?.requestAudioFocus(req) }
    }

    private fun inCall(): Boolean = when (audioManager?.mode) {
        android.media.AudioManager.MODE_IN_CALL,
        android.media.AudioManager.MODE_IN_COMMUNICATION,
        android.media.AudioManager.MODE_RINGTONE -> true
        else -> false
    }

    private fun abandonFocus() {
        val req = focusRequest ?: return
        focusRequest = null
        if (android.os.Build.VERSION.SDK_INT >= 26) runCatching { audioManager?.abandonAudioFocusRequest(req) }
    }

    private inner class EngineRun(val plugin: VoicePlugin, val gen: Int, val multi: Boolean) {
        var session: EngineSession? = null
        var ended = false
        private fun emit(action: (VoiceListener) -> Unit) = post(gen) { if (!ended) action(it) }
        fun partial(text: String) = emit { if (multi) (it as MultiVoiceListener).onEnginePartial(plugin.id, text) else it.onPartial(text) }
        fun final(text: String) = emit { if (multi) (it as MultiVoiceListener).onEngineFinal(plugin.id, text) else it.onFinal(text) }
        fun error(message: String) = post(gen) {
            if (ended) return@post
            if (multi) (it as MultiVoiceListener).onEngineError(plugin.id, message) else it.onError(message)
            session?.cancel()
            end()
        }
        fun end() {
            if (gen != generation || ended) return
            ended = true
            if (multi) (listener as? MultiVoiceListener)?.onEngineEnd(plugin.id)
            if (runs.all { it.ended }) endSession()
        }
    }

    override fun start(listener: VoiceListener): Boolean {
        cancel()
        if (!hasEngine()) { listener.onError("请选择语音引擎或下载离线语音包"); listener.onEnd(); return false }
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.onError("需要麦克风权限"); listener.onEnd(); return false
        }
        this.listener = listener
        stopping = false
        val gen = ++generation
        val selection = engines.selection().let { if (listener is MultiVoiceListener) it.take(3) else it.take(1) }
        val multi = selection.size > 1
        if (multi) (listener as MultiVoiceListener).onEngines(selection)
        val started = selection.map { EngineRun(it, gen, multi) }
        runs = started
        for (run in started) {
            if (!isBuiltinEngine(run.plugin.id)) {
                run.session = engines.plugins.session(run.plugin.id, object : NativeSpeechCallback {
                    override fun onPartial(text: String) = run.partial(text)
                    override fun onFinal(text: String) = run.final(text)
                    override fun onReplace(old: String, new: String) = post(gen) {
                        if (multi) (it as MultiVoiceListener).onEngineReplace(run.plugin.id, old, new) else it.onReplace(old, new)
                    }
                    override fun onError(message: String) = run.error(message)
                    override fun onEnd() { main.post { run.end() } }
                    override fun onLog(level: Int, message: String) {}
                })
                continue
            }
            val localSession = engines.local(run.plugin.id).Session(
                listener = object : TwoPassListener {
                    override fun onPartial(text: String) = run.partial(text)
                    override fun onFinal(text: String) = run.final(text)
                },
                onReady = {},
                onEnd = { main.post { run.end() } },
                onError = { run.error(it) },
            )
            run.session = object : EngineSession {
                override fun feed(bytes: ByteArray, count: Int) = localSession.feed(bytes, count)
                override fun stop() = localSession.stop()
                override fun cancel() = localSession.cancel()
            }
        }
        requestFocus()
        // Capture immediately, including cold starts and a hold released before loading finishes.
        // Each model's ordered worker buffers its PCM behind the load and then finishes it.
        startRecording(gen, sink = { b, n -> for (run in started) run.session?.feed(b, n) }, onFail = {
            main.post { if (gen == generation) { for (run in started) run.session?.cancel(); endSession() } }
        })
        return true
    }

    private fun captureEnded(gen: Int) {
        main.post {
            if (gen != generation) return@post
            captureQueued = false
            if (stopping) for (run in runs) run.session?.stop()
        }
    }

    private fun endSession() {
        recording = false
        abandonFocus()
        val l = listener
        listener = null
        runs = emptyList()
        l?.onEnd()
    }

    @SuppressLint("MissingPermission")
    private fun startRecording(gen: Int, sink: (ByteArray, Int) -> Unit, onFail: () -> Unit) {
        recording = true
        captureQueued = true
        audioThread.execute {
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
            if (gen != generation || !recording) { captureEnded(gen); return@execute }
            val rate = 16000
            val chunk = rate / 25 * 2 // 40ms × 16bit
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            // One second of capacity survives short CPU/GC stalls without adding read latency.
            // We still consume 40 ms chunks immediately; capacity is not a playback delay.
            val bufferBytes = maxOf(minBuf, rate * 2)
            // 先用语音识别音源（系统做降噪）；打不开、或录到的全是数字静音（有的机型对它静音）时换普通麦克风。
            // Prefer the voice-recognition source (system noise suppression); fall back to the plain mic when it
            // can't open or only delivers digital silence (some phones mute it for third-party apps).
            val sources = intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
            var si = 0
            var rec: AudioRecord? = null
            while (rec == null && si < sources.size) {
                rec = openRecorder(sources[si], rate, bufferBytes)
                if (rec == null) si++
            }
            if (rec == null) {
                post(gen) { it.onError("无法打开麦克风，可能被其他应用占用") }
                onFail()
                captureEnded(gen)
                return@execute
            }
            var r: AudioRecord = rec
            val buf = ByteArray(chunk)
            var silentChunks = 0
            /** 连续读到 0 字节的次数：一直这样说明录音断了。 Zero-byte reads in a row; many means the capture broke. */
            var zeroReads = 0
            var error: String? = null
            try {
                r.startRecording()
                if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) error = MIC_BUSY
                else post(gen) { it.onReady(false) }
                while (error == null && recording && gen == generation) {
                    var off = 0
                    while (off < chunk && recording) {
                        val n = r.read(buf, off, chunk - off)
                        if (n < 0) { error = "录音出错（$n），请重试"; break }
                        if (n == 0) {
                            if (++zeroReads >= 50) { error = "录音中断，请重试"; break }
                            Thread.sleep(10)
                            continue
                        }
                        zeroReads = 0
                        off += n
                    }
                    if (error != null || off <= 0) break
                    // 开头 1 秒全是 0：这个音源被静音了，换下一个继续录。 First second all zeros: source muted; switch.
                    if (silentChunks >= 0 && si + 1 < sources.size) {
                        silentChunks = if (allZero(buf, off)) silentChunks + 1 else -1
                        if (silentChunks >= 25) {
                            // 先放掉当前录音：很多机型同时只允许一路录音。 Release first: many phones allow one capture.
                            Log.w(TAG, "source ${sources[si]} is silent, switching to ${sources[si + 1]}")
                            runCatching { r.stop() }
                            r.release()
                            si++
                            r = openRecorder(sources[si], rate, bufferBytes)
                                ?: openRecorder(sources[si - 1], rate, bufferBytes)
                                ?: throw IllegalStateException("no recorder")
                            r.startRecording()
                            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { error = MIC_BUSY; continue }
                            silentChunks = -1
                        }
                    }
                    // Deliver the last short chunk before scheduling the final decode.
                    if (gen != generation) break
                    // 音量表看原始电平，识别器拿增益后的声音。 The meter shows the raw level; the recognizer gets the gained audio.
                    val level = rms(buf, off)
                    gainControl.apply(buf, off)
                    sink(buf, off)
                    post(gen) { it.onLevel(level) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "recording failed", t)
                error = "录音出错，请重试"
            } finally {
                runCatching { r.stop() }
                r.release()
            }
            // 录音意外中断：告诉用户，并结束本次会话（不再停在「正在聆听」）。
            // The recording broke off: tell the user and end the session instead of hanging in "listening".
            error?.let { msg -> if (gen == generation) { post(gen) { it.onError(msg) }; onFail() } }
            captureEnded(gen)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(source: Int, rate: Int, bufferBytes: Int): AudioRecord? {
        val rec = runCatching {
            AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
        }.getOrNull() ?: return null
        if (rec.state == AudioRecord.STATE_INITIALIZED) return rec
        rec.release()
        return null
    }

    private fun allZero(b: ByteArray, len: Int): Boolean {
        for (i in 0 until len) if (b[i].toInt() != 0) return false
        return true
    }

    private fun rms(b: ByteArray, len: Int): Float = VoiceLevel.fromPcm16(b, len)

    override fun hasEngine(): Boolean = runCatching { engines.selection().isNotEmpty() }.getOrDefault(false)
    override fun warmUp() { if (hasEngine()) engines.preload() }

    override fun stop() {
        if (listener == null || stopping) return
        stopping = true
        recording = false
        // The capture thread sends its last samples before finishing the decoder.
        if (!captureQueued) for (run in runs) run.session?.stop()
    }

    override fun cancel() {
        recording = false
        generation++
        captureQueued = false
        stopping = false
        val l = listener
        listener = null
        for (run in runs) run.session?.cancel()
        runs = emptyList()
        abandonFocus()
        l?.onEnd()
    }

    private inline fun post(gen: Int, crossinline f: (VoiceListener) -> Unit) {
        main.post { if (gen == generation) listener?.let(f) }
    }

    companion object {
        private const val MIC_BUSY = "无法开始录音，麦克风可能被其他应用占用"
    }
}
