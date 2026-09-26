package com.weavetext.ime.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * 真实语音后端：Lua 插件（经 Rust 宿主）+ 系统自带识别（无插件时也能用）。
 * Real voice backend: Lua plugins via the Rust host, plus the platform recognizer as a built-in
 * engine that works without any plugin.
 */
internal object VoiceBackend {
    fun create(ctx: Context): Pair<VoiceEngines, VoiceRecognizer> {
        val engines = PluginEngines(ctx)
        return engines to Recognizer(ctx, engines)
    }
}

private const val TAG = "WeaveVoice"
private const val RELEASE_DELAY_MS = 15_000L
private const val MAX_LOG_CHARS = 512

/**
 * 插件日志可能含识别文本或配置：正式版只记插件 id、级别与长度；调试版截断后输出。
 * Plugin logs may contain transcripts or config: release builds log only id, level and length;
 * debug builds log a truncated message.
 */
internal fun pluginLog(id: String, level: Int, message: String) {
    val text = if (com.weavetext.ime.BuildConfig.DEBUG) message.take(MAX_LOG_CHARS) else "(${message.length} chars)"
    when {
        level >= 3 -> Log.e(TAG, "[$id] $text")
        level == 2 -> Log.w(TAG, "[$id] $text")
        com.weavetext.ime.BuildConfig.DEBUG -> Log.d(TAG, "[$id] $text")
    }
}

/** 系统识别引擎的保留 id。 Reserved id of the built-in platform engine. */
internal const val SYSTEM_ENGINE_ID = "weave.system"

/** 本地离线识别引擎的保留 id。 Reserved id of the on-device engine. */
internal const val LOCAL_ENGINE_ID = "weave.local"

/** 内置引擎（不能卸载、没有插件包）。 Built-in engines (not uninstallable, no package). */
internal fun isBuiltinEngine(id: String) = id == SYSTEM_ENGINE_ID || id == LOCAL_ENGINE_ID

private class PluginEngines(private val ctx: Context) : VoiceEngines {
    /** 本地离线识别。 On-device recognition. */
    val local = com.weavetext.ime.voice.local.LocalAsrEngine(ctx)
    private val prefs = ctx.getSharedPreferences("weave_voice", Context.MODE_PRIVATE)
    private val pluginsDir = File(ctx.filesDir, "plugins")
    private val configDir = File(ctx.filesDir, "plugin-config")
    /** 宿主句柄，0 表示不可用。 Host handle; 0 means unavailable. */
    val host: Long
    @Volatile private var cache: List<VoicePlugin> = emptyList()
    private val systemAvailable = SpeechRecognizer.isRecognitionAvailable(ctx)

    init {
        pluginsDir.mkdirs()
        configDir.mkdirs()
        installBundled()
        host = try {
            NativePluginHost.nativeCreate(pluginsDir.absolutePath, configDir.absolutePath)
        } catch (t: Throwable) {
            Log.e(TAG, "plugin host unavailable", t)
            0L
        }
        refresh()
    }

    /** 首次启动 / 升级后，安装 APK 内置的插件包（若构建时提供）。 Install bundled packages once. */
    private fun installBundled() {
        val names = runCatching { ctx.assets.list("plugins") }.getOrNull().orEmpty().filter { it.endsWith(".xipk") }
        if (names.isEmpty()) return
        val pkg = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val stamp = pkg.lastUpdateTime.toString()
        if (prefs.getString("bundled_stamp", null) == stamp) return
        for (n in names) {
            runCatching {
                ctx.assets.open("plugins/$n").use { i -> File(pluginsDir, n).outputStream().use { i.copyTo(it) } }
            }.onFailure { Log.w(TAG, "bundle $n failed", it) }
        }
        prefs.edit().putString("bundled_stamp", stamp).apply()
    }

    fun refresh() {
        val list = mutableListOf<VoicePlugin>()
        // 本地离线识别排第一：不联网、语音不离开手机，没有选择时默认使用它。
        // The on-device engine comes first and is the default: offline, audio never leaves the phone.
        if (runCatching { local.isAvailable }.getOrDefault(false)) {
            list += VoicePlugin(
                LOCAL_ENGINE_ID, "本地离线识别", "识别在手机上完成，无需联网，语音不离开设备。", "", null, local.fields(),
            )
        }
        if (systemAvailable) {
            list += VoicePlugin(SYSTEM_ENGINE_ID, "系统语音识别", "使用手机自带的语音识别服务。", "", null, emptyList())
        }
        if (host != 0L) {
            val json = runCatching { JSONArray(NativePluginHost.nativeScan(host) ?: "[]") }.getOrDefault(JSONArray())
            for (i in 0 until json.length()) {
                val o = json.getJSONObject(i)
                if (o.optString("kind") != "speech") continue
                list += parse(o)
            }
        }
        cache = list
    }

    private fun parse(o: JSONObject, withIcon: Boolean = true): VoicePlugin {
        val id = o.getString("id")
        val schema = o.optJSONArray("configSchema") ?: JSONArray()
        val fields = (0 until schema.length()).map { i ->
            val f = schema.getJSONObject(i)
            val opts = f.optJSONArray("options")
            ConfigField(
                key = f.optString("key"),
                label = f.optString("label", f.optString("key")),
                type = f.optString("type", "text"),
                section = f.optString("section").ifEmpty { null },
                options = if (opts == null) emptyList() else (0 until opts.length()).map { opts.optString(it) },
                defaultValue = if (f.isNull("defaultValue")) null else f.optString("defaultValue"),
                helpText = f.optString("helpText").ifEmpty { null },
                required = f.optBoolean("required", false),
            )
        }
        val icon = if (withIcon && o.optBoolean("hasIcon") && host != 0L) NativePluginHost.nativeIcon(host, id) else null
        val hosts = o.optJSONArray("networkHosts")
        return VoicePlugin(
            id = id,
            name = o.optString("name", id),
            description = o.optString("description"),
            version = o.optString("version"),
            iconPng = icon,
            configSchema = fields,
            networkHosts = if (hosts == null) emptyList() else (0 until hosts.length()).map { hosts.optString(it) },
            unrestrictedNetwork = o.optBoolean("unrestrictedNetwork"),
        )
    }

    override fun inspect(xipkPath: String): Result<VoicePlugin> = runCatching {
        val o = JSONObject(NativePluginHost.nativeInspect(xipkPath) ?: """{"error":"inspect failed"}""")
        if (o.has("error")) throw IllegalArgumentException(o.getString("error"))
        val p = o.getJSONObject("plugin")
        if (p.optString("kind") != "speech") throw IllegalArgumentException("不是语音插件（类型：${p.optString("kind")}）")
        parse(p, withIcon = false)
    }

    override fun list(): List<VoicePlugin> = cache

    override var activeId: String?
        get() = prefs.getString("active", null)?.takeIf { id -> cache.any { it.id == id } } ?: cache.firstOrNull()?.id
        set(value) { prefs.edit().putString("active", value).apply() }

    override fun install(xipkPath: String): Result<VoicePlugin> {
        if (host == 0L) return Result.failure(IllegalStateException("插件宿主不可用"))
        val o = JSONObject(NativePluginHost.nativeInstall(host, xipkPath) ?: """{"error":"install failed"}""")
        if (o.has("error")) return Result.failure(IllegalArgumentException(o.getString("error")))
        refresh()
        val id = o.getJSONObject("plugin").getString("id")
        return cache.firstOrNull { it.id == id }?.let { Result.success(it) }
            ?: Result.failure(IllegalArgumentException("不是语音插件"))
    }

    override fun uninstall(id: String): Result<Unit> {
        if (isBuiltinEngine(id)) return Result.failure(IllegalArgumentException("内置引擎不能删除"))
        if (host == 0L) return Result.failure(IllegalStateException("插件宿主不可用"))
        val err = NativePluginHost.nativeUninstall(host, id)
        refresh()
        return if (err == null) Result.success(Unit) else Result.failure(IllegalStateException(err))
    }

    override fun getConfig(id: String, key: String): String? = when {
        id == LOCAL_ENGINE_ID -> local.getConfig(key)
        host == 0L || id == SYSTEM_ENGINE_ID -> null
        else -> NativePluginHost.nativeGetConfig(host, id, key)
    }

    override fun setConfig(id: String, key: String, value: String) {
        when {
            id == LOCAL_ENGINE_ID -> { local.setConfig(key, value); refresh() }
            host != 0L && id != SYSTEM_ENGINE_ID -> NativePluginHost.nativeSetConfig(host, id, key, value)
        }
    }
}

/**
 * 录音 + 识别。插件引擎：AudioRecord 16k PCM 每 40ms 一块送进插件；系统引擎：SpeechRecognizer。
 * Recording + recognition. Plugins get 40 ms chunks of 16 kHz PCM; the system engine uses
 * SpeechRecognizer.
 */
private class Recognizer(private val ctx: Context, private val engines: PluginEngines) : VoiceRecognizer {
    private val main = Handler(Looper.getMainLooper())
    private val audioThread = Executors.newSingleThreadExecutor { r -> Thread(r, "weave-audio") }
    @Volatile private var recording = false
    @Volatile private var session = 0L
    private var listener: VoiceListener? = null
    private var systemRecognizer: SpeechRecognizer? = null
    /** 每次会话递增；旧会话的迟到回调据此丢弃。 Bumped per session to drop late callbacks. */
    @Volatile private var generation = 0

    override val isRunning: Boolean get() = listener != null

    // ------------------------------------------------------------ audio focus
    // 收音时申请短暂音频焦点：其它 App 暂停/压低播放；焦点被抢（来电等）时结束会话并上屏已识别内容。
    // Hold transient focus while listening; on loss (calls etc.) stop and keep what was recognized.
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
                    change == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                ) {
                    main.post { if (isRunning) stop() }
                }
            }
            .build()
        focusRequest = req
        runCatching { audioManager?.requestAudioFocus(req) }
    }

    private fun abandonFocus() {
        val req = focusRequest ?: return
        focusRequest = null
        if (android.os.Build.VERSION.SDK_INT >= 26) runCatching { audioManager?.abandonAudioFocusRequest(req) }
    }

    override fun start(listener: VoiceListener): Boolean {
        cancel()
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.onError("需要麦克风权限")
            listener.onEnd()
            return false
        }
        val id = engines.activeId ?: run {
            listener.onError("没有可用的语音引擎")
            listener.onEnd()
            return false
        }
        this.listener = listener
        val gen = ++generation
        requestFocus()
        val ok = when (id) {
            SYSTEM_ENGINE_ID -> startSystem(gen)
            LOCAL_ENGINE_ID -> startLocal(gen)
            else -> startPlugin(id, gen)
        }
        if (!ok) abandonFocus()
        return ok
    }

    // ------------------------------------------------------------ plugin engine

    private fun startPlugin(id: String, gen: Int): Boolean {
        val cb = object : NativeSpeechCallback {
            override fun onPartial(text: String) = post(gen) { it.onPartial(text) }
            override fun onFinal(text: String) = post(gen) { it.onFinal(text) }
            // 替换可能在会话结束后才到，按 id 转发给最后一个监听者。 May arrive after onEnd.
            override fun onReplace(old: String, new: String) = main.post { lastListener?.onReplace(old, new) }.let { }
            override fun onError(message: String) = post(gen) { it.onError(message) }
            override fun onEnd() {
                main.post {
                    val s = session
                    if (gen == generation) {
                        session = 0L
                        recording = false
                        abandonFocus()
                        val l = listener
                        listener = null
                        l?.onEnd()
                    }
                    // 释放会取消会话；插件在 onEnd 之后仍可能回填（onReplace），所以延迟释放。
                    // Releasing cancels the session and plugins may still send onReplace after
                    // onEnd, so release later.
                    if (s != 0L && gen == generation) main.postDelayed({ NativePluginHost.nativeRelease(s) }, RELEASE_DELAY_MS)
                }
            }
            override fun onLog(level: Int, message: String) = pluginLog(id, level, message)
        }
        lastListener = listener
        val s = NativePluginHost.nativeStartSpeech(engines.host, id, cb)
        if (s == 0L) return false
        session = s
        startRecording(gen, sink = { b, n -> NativePluginHost.nativeFeed(s, b, n) }, onFail = { NativePluginHost.nativeCancel(s) })
        return true
    }

    private var lastListener: VoiceListener? = null

    @SuppressLint("MissingPermission")
    private fun startRecording(gen: Int, sink: (ByteArray, Int) -> Unit, onFail: () -> Unit) {
        recording = true
        audioThread.execute {
            val rate = 16000
            val chunk = rate / 25 * 2 // 40ms × 16bit
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, chunk * 4))
            } catch (t: Throwable) {
                post(gen) { it.onError("无法打开麦克风") }
                onFail()
                return@execute
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                post(gen) { it.onError("无法打开麦克风") }
                onFail()
                return@execute
            }
            val buf = ByteArray(chunk)
            try {
                rec.startRecording()
                while (recording && gen == generation) {
                    var off = 0
                    while (off < chunk && recording) {
                        val n = rec.read(buf, off, chunk - off)
                        if (n <= 0) break
                        off += n
                    }
                    if (off <= 0) break
                    sink(buf, off)
                    val level = rms(buf, off)
                    post(gen) { it.onLevel(level) }
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
        }
    }

    private fun rms(b: ByteArray, len: Int): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < len) {
            val v = (b[i].toInt() and 0xFF) or (b[i + 1].toInt() shl 8)
            sum += v.toShort().toDouble() * v.toShort().toDouble()
            i += 2
        }
        val r = sqrt(sum / maxOf(1, len / 2)) / 32768.0
        return (r * 4).coerceIn(0.0, 1.0).toFloat()
    }

    // ------------------------------------------------------------ on-device engine

    private var localSession: com.weavetext.ime.voice.local.LocalAsrEngine.Session? = null

    private fun startLocal(gen: Int): Boolean {
        val session = engines.local.Session(
            listener = object : com.weavetext.ime.voice.local.TwoPassListener {
                override fun onPartial(text: String) = post(gen) { it.onPartial(text) }
                override fun onFinal(text: String) = post(gen) { it.onFinal(text) }
            },
            onEnd = {
                main.post {
                    if (gen == generation) {
                        localSession = null
                        recording = false
                        abandonFocus()
                        val l = listener
                        listener = null
                        l?.onEnd()
                    }
                }
            },
            onError = { msg -> post(gen) { it.onError(msg) } },
        )
        localSession = session
        startRecording(gen, sink = { b, n -> session.feed(b, n) }, onFail = { session.cancel() })
        return true
    }

    /** 打开语音面板时预热本地模型。 Warm up local models when the voice panel opens. */
    override fun warmUp() {
        if (engines.active()?.id == LOCAL_ENGINE_ID) engines.local.preload()
    }

    // ------------------------------------------------------------ system engine

    private fun startSystem(gen: Int): Boolean {
        val sr = SpeechRecognizer.createSpeechRecognizer(ctx)
        systemRecognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = post(gen) { it.onLevel(((rmsdB + 2) / 12f).coerceIn(0f, 1f)) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    post(gen) { it.onError("系统识别出错（$error）") }
                }
                finishSystem(gen)
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotEmpty()) post(gen) { it.onFinal(text) }
                finishSystem(gen)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotEmpty()) post(gen) { it.onPartial(text) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        sr.startListening(intent)
        return true
    }

    private fun finishSystem(gen: Int) {
        main.post {
            if (gen != generation) return@post
            systemRecognizer?.destroy()
            systemRecognizer = null
            abandonFocus()
            val l = listener
            listener = null
            l?.onEnd()
        }
    }

    // ------------------------------------------------------------ control

    override fun stop() {
        recording = false
        localSession?.stop()
        val s = session
        if (s != 0L) NativePluginHost.nativeStop(s)
        systemRecognizer?.stopListening()
    }

    override fun cancel() {
        recording = false
        localSession?.cancel()
        localSession = null
        val s = session
        val l = listener
        listener = null
        generation++
        session = 0L
        if (s != 0L) {
            NativePluginHost.nativeCancel(s)
            // 取消后宿主仍会回调 onEnd（已被 generation 过滤），稍后释放句柄。
            main.postDelayed({ NativePluginHost.nativeRelease(s) }, RELEASE_DELAY_MS)
        }
        systemRecognizer?.let { it.cancel(); it.destroy() }
        systemRecognizer = null
        abandonFocus()
        l?.onEnd()
    }

    private inline fun post(gen: Int, crossinline f: (VoiceListener) -> Unit) {
        main.post { if (gen == generation) listener?.let(f) }
    }
}
