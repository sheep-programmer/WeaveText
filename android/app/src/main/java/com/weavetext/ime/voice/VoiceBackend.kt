package com.weavetext.ime.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
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
private const val KEY_SYSTEM_DISABLED = "system_disabled"
private const val KEY_SYSTEM_FAILURES = "system_failures"
private const val KEY_SYSTEM_SERVICE = "system_service"

/**
 * 系统识别连续失败这么多次（期间没成功过）后，有本地识别时不再默认用它。
 * After this many failures in a row (no success in between), the system engine stops being the default when
 * local recognition is available.
 */
internal const val SYSTEM_FAILURE_LIMIT = 2

/**
 * 主引擎：保存的选择（仍在列表里）或列表第一个；系统识别连续失败 [SYSTEM_FAILURE_LIMIT] 次且有本地识别时改用本地。
 * The primary engine: the saved choice if still listed, else the first entry; the local engine instead of the
 * system one after [SYSTEM_FAILURE_LIMIT] failures in a row.
 */
internal fun pickActive(saved: String?, ids: List<String>, systemFailures: Int): String? {
    val id = saved?.takeIf { it in ids } ?: ids.firstOrNull()
    return if (id == SYSTEM_ENGINE_ID && systemFailures >= SYSTEM_FAILURE_LIMIT && LOCAL_ENGINE_ID in ids) LOCAL_ENGINE_ID else id
}

/**
 * 系统设置里选定的默认识别服务：已选定且装着时返回它；没选定或已失效返回空串；读不到这项设置返回 null。
 * The default recognition service from system settings: its component when set and installed, "" when unset or
 * stale, null when the setting can't be read.
 */
private fun defaultRecognizer(ctx: Context): String? = try {
    val cn = android.content.ComponentName.unflattenFromString(
        android.provider.Settings.Secure.getString(ctx.contentResolver, "voice_recognition_service").orEmpty(),
    )
    val intent = Intent(android.speech.RecognitionService.SERVICE_INTERFACE)
    if (cn != null && ctx.packageManager.queryIntentServices(intent.setComponent(cn), 0).isNotEmpty()) cn.flattenToShortString() else ""
} catch (_: Exception) {
    null
}

private const val ON_DEVICE_KEY = "on-device"
private const val FALLBACK_ONCE = "系统语音识别用不了，已改用本地识别"
private const val FALLBACK_DEFAULT = "系统语音识别多次失败，已改为默认用本地识别"
private const val FALLBACK_DELAY_MS = 300L
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
    /**
     * 系统识别能用：系统设置里的默认识别服务装着（读不到设置时看有没有识别服务），或有端侧识别（Android 13 起）。
     * 没选定默认服务时一般调不起来，就不列出，免得默认用上一个用不了的引擎。刷新与 [recheck] 时重新检查。
     * The platform engine is usable when the default service from settings is installed (any service when the
     * setting can't be read) or on-device recognition exists (Android 13+). Without a default service it usually
     * can't be used, so it isn't listed rather than becoming a broken default. Re-checked on refresh and [recheck].
     */
    private val systemAvailable: Boolean get() = runCatching {
        val default = defaultRecognizer(ctx)
        (if (default == null) SpeechRecognizer.isRecognitionAvailable(ctx) else default.isNotEmpty()) ||
            (android.os.Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx))
    }.getOrDefault(false)

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
        // 装好或删掉模型时重建列表：本地引擎可能出现/消失，设置表单里的模型选项也要跟着变（新下载的模型
        // 要能选上）。下载进度也会回调，所以只在已装模型变化时刷新。
        // Rebuild when a model is installed or removed: the local engine may appear or vanish and the form's model
        // options must follow (so a new download can be picked). Progress ticks also call back, so refresh only
        // when the installed set changes.
        var seen = runCatching { local.modelSignature() }.getOrDefault("")
        com.weavetext.ime.models.ModelManager.get(ctx).addListener {
            val now = runCatching { local.modelSignature() }.getOrDefault("")
            if (now != seen) { seen = now; refresh() }
        }
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

    override var systemDisabled: Boolean
        get() = prefs.getBoolean(KEY_SYSTEM_DISABLED, false)
        set(v) {
            prefs.edit().putBoolean(KEY_SYSTEM_DISABLED, v).apply()
            if (v && activeId == SYSTEM_ENGINE_ID) activeId = null
            refresh()
        }

    override fun systemPresent(): Boolean = systemAvailable

    override fun recheck() {
        val listed = cache.any { it.id == SYSTEM_ENGINE_ID }
        if (listed != (systemAvailable && !systemDisabled)) refresh()
    }

    /** 系统识别连续失败次数（成功或用户重新选它时清零）。 System-engine failures in a row. */
    val systemFailures: Int get() = prefs.getInt(KEY_SYSTEM_FAILURES, 0)

    /** 上次成功的识别服务，下次先试它。 The service that worked last; tried first next time. */
    val lastSystemService: String? get() = prefs.getString(KEY_SYSTEM_SERVICE, null)

    fun systemWorked(service: String?) {
        val e = prefs.edit().putInt(KEY_SYSTEM_FAILURES, 0)
        if (service != null) e.putString(KEY_SYSTEM_SERVICE, service)
        e.apply()
    }

    /** 记一次失败，返回连续失败次数。 Record a failure; returns the count in a row. */
    fun systemFailed(): Int {
        val n = systemFailures + 1
        prefs.edit().putInt(KEY_SYSTEM_FAILURES, n).apply()
        return n
    }

    /** 本地识别可用。 The local engine is listed. */
    val localListed: Boolean get() = cache.any { it.id == LOCAL_ENGINE_ID }

    fun refresh() {
        val list = mutableListOf<VoicePlugin>()
        // 本地离线识别排第一：不联网、语音不离开手机，没有选择时默认使用它。
        // The on-device engine comes first and is the default: offline, audio never leaves the phone.
        if (runCatching { local.isAvailable }.getOrDefault(false)) {
            list += VoicePlugin(
                LOCAL_ENGINE_ID, "本地离线识别", "识别在手机上完成，无需联网，语音不离开设备。", "", null, local.fields(),
            )
        }
        if (systemAvailable && !systemDisabled) {
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
        get() = pickActive(prefs.getString("active", null), cache.map { it.id }, systemFailures)
        set(value) {
            val e = prefs.edit().putString("active", value)
            // 用户重新选了系统识别：再给它机会。 The user picked the system engine again: give it another chance.
            if (value == SYSTEM_ENGINE_ID) e.putInt(KEY_SYSTEM_FAILURES, 0)
            e.apply()
        }

    override var extraIds: Set<String>
        get() = prefs.getStringSet("also", null)?.toSet().orEmpty()
        set(value) { prefs.edit().putStringSet("also", value.toSet()).apply() }

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
 * 录音 + 识别。本地引擎与插件引擎共用一次 AudioRecord 录音：16k PCM 每 40ms 一块，扇出给本次
 * 选中的每个引擎（06 §6）；系统引擎用 SpeechRecognizer，自己占用麦克风，只能单独使用。
 * Recording + recognition. The on-device engine and plugins share one AudioRecord capture whose
 * 40 ms chunks of 16 kHz PCM fan out to every selected engine; the platform engine uses
 * SpeechRecognizer, owns the mic and always runs alone.
 */
private class Recognizer(private val ctx: Context, private val engines: PluginEngines) : VoiceRecognizer {
    private val main = Handler(Looper.getMainLooper())
    private val audioThread = Executors.newSingleThreadExecutor { r -> Thread(r, "weave-audio") }
    @Volatile private var recording = false
    private var listener: VoiceListener? = null
    private val system = SystemSpeech(main, ::systemServices, ctx.packageName)
    /** 每次会话递增；旧会话的迟到回调据此丢弃。 Bumped per session to drop late callbacks. */
    @Volatile private var generation = 0
    /** 本次会话中共用录音的引擎。 Engines sharing the recording in this session. */
    @Volatile private var runs: List<EngineRun> = emptyList()

    override val isRunning: Boolean get() = listener != null

    // ------------------------------------------------------------ audio focus
    // 只在自己录音时申请短暂音频焦点，让其它应用暂停播放。系统识别由识别服务自己管焦点：我们再抢一次，
    // 服务一拿焦点我们就会收到「失去焦点」并把它停掉。永久失去焦点或来电时结束会话并上屏已识别的内容；
    // 短暂失去（导航播报、提示音）不打断说话。
    // Request transient focus only while we record ourselves, so other apps pause. The system recognizer's service
    // manages focus itself: holding our own would make us stop it as soon as the service takes focus. On a
    // permanent loss or a phone call, stop and keep what was recognized; a transient loss (navigation prompt,
    // notification sound) doesn't cut the user off.
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

    override fun start(listener: VoiceListener): Boolean {
        cancel()
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.onError("需要麦克风权限")
            listener.onEnd()
            return false
        }
        val selection = engines.selection()
        if (selection.isEmpty()) {
            listener.onError("没有可用的语音引擎")
            listener.onEnd()
            return false
        }
        this.listener = listener
        val gen = ++generation
        val primary = selection.first()
        // 系统识别的失败经监听者异步报告（含改用本地识别），所以总是返回 true。
        // System-engine failures (and a switch to local) are reported through the listener, so this returns true.
        if (primary.id == SYSTEM_ENGINE_ID) { startSystem(gen); return true }
        requestFocus()
        // 多引擎只在界面支持时启用；否则只用主引擎。 Multi-engine only when the UI supports it.
        val multi = listener as? MultiVoiceListener
        val ids = if (multi != null && selection.size > 1) selection.map { it.id } else listOf(primary.id)
        if (ids.size > 1) multi!!.onEngines(selection)
        val ok = startShared(ids, gen, multi = ids.size > 1)
        if (!ok) { abandonFocus(); this.listener = null }
        return ok
    }

    // ------------------------------------------------------------ shared-recording engines

    /**
     * 共用录音的一个引擎。单引擎会话把结果当作普通回调转发；多引擎会话带上引擎 id。
     * One engine on the shared recording. Single-engine sessions forward plain callbacks;
     * multi-engine sessions tag them with the engine id.
     */
    private inner class EngineRun(val id: String, val gen: Int, val multi: Boolean) {
        @Volatile var ended = false
        var pluginSession = 0L
        var local: com.weavetext.ime.voice.local.LocalAsrEngine.Session? = null

        fun partial(text: String) = post(gen) { l -> if (multi) (l as MultiVoiceListener).onEnginePartial(id, text) else l.onPartial(text) }
        fun final(text: String) = post(gen) { l -> if (multi) (l as MultiVoiceListener).onEngineFinal(id, text) else l.onFinal(text) }
        fun error(message: String) = post(gen) { l ->
            if (multi) { (l as MultiVoiceListener).onEngineError(id, message); return@post }
            l.onError(message)
            // 单引擎出错即结束：停止录音、放掉音频焦点，否则麦克风会一直开着。
            // A single engine's error ends the session: stop recording and release focus, or the mic stays on.
            if (!ended) {
                ended = true
                cancel()
                if (runs.all { it.ended }) finishShared()
            }
        }
        fun replace(old: String, new: String) {
            // 替换可能在会话结束后才到，转发给最后一个监听者。 May arrive after onEnd.
            main.post {
                val l = lastListener ?: return@post
                if (multi) (l as? MultiVoiceListener)?.onEngineReplace(id, old, new) else l.onReplace(old, new)
            }
        }

        /** 引擎结束（主线程）。 Engine finished (main thread). */
        fun onEnded() {
            if (gen != generation || ended) return
            ended = true
            if (multi) (listener as? MultiVoiceListener)?.onEngineEnd(id)
            if (runs.all { it.ended }) finishShared()
        }

        fun feed(b: ByteArray, n: Int) {
            if (ended) return
            val s = pluginSession
            if (s != 0L) NativePluginHost.nativeFeed(s, b, n) else local?.feed(b, n)
        }

        fun stop() {
            local?.stop()
            if (pluginSession != 0L) NativePluginHost.nativeStop(pluginSession)
        }

        fun cancel() {
            local?.cancel()
            local = null
            val s = pluginSession
            pluginSession = 0L
            if (s != 0L) {
                NativePluginHost.nativeCancel(s)
                // 取消后宿主仍会回调 onEnd（已被 generation 过滤），稍后释放句柄。
                main.postDelayed({ NativePluginHost.nativeRelease(s) }, RELEASE_DELAY_MS)
            }
        }
    }

    private var lastListener: VoiceListener? = null

    private fun startShared(ids: List<String>, gen: Int, multi: Boolean): Boolean {
        lastListener = listener
        val started = ArrayList<EngineRun>()
        for (id in ids) {
            val run = EngineRun(id, gen, multi)
            val ok = if (id == LOCAL_ENGINE_ID) startLocal(run) else startPlugin(run)
            if (ok) started += run
            else if (multi) (listener as? MultiVoiceListener)?.let { l ->
                // 单个引擎起不来不影响其它引擎。 One engine failing to start doesn't stop the others.
                l.onEngineError(id, "无法启动")
                l.onEngineEnd(id)
            }
        }
        if (started.isEmpty()) return false
        runs = started
        startRecording(
            gen,
            sink = { b, n -> for (r in started) runCatching { r.feed(b, n) } },
            onFail = { main.post { for (r in started) r.cancel(); if (gen == generation) finishShared() } },
        )
        return true
    }

    private fun startPlugin(run: EngineRun): Boolean {
        val id = run.id
        val cb = object : NativeSpeechCallback {
            override fun onPartial(text: String) = run.partial(text)
            override fun onFinal(text: String) = run.final(text)
            override fun onReplace(old: String, new: String) = run.replace(old, new)
            override fun onError(message: String) = run.error(message)
            override fun onEnd() {
                main.post {
                    val s = run.pluginSession
                    val current = run.gen == generation
                    run.onEnded()
                    // 释放会取消会话；插件在 onEnd 之后仍可能回填（onReplace），所以延迟释放。
                    // Releasing cancels the session and plugins may still send onReplace after
                    // onEnd, so release later.
                    if (s != 0L && current) main.postDelayed({ NativePluginHost.nativeRelease(s) }, RELEASE_DELAY_MS)
                }
            }
            override fun onLog(level: Int, message: String) = pluginLog(id, level, message)
        }
        val s = NativePluginHost.nativeStartSpeech(engines.host, id, cb)
        if (s == 0L) return false
        run.pluginSession = s
        return true
    }

    private fun startLocal(run: EngineRun): Boolean {
        run.local = engines.local.Session(
            listener = object : com.weavetext.ime.voice.local.TwoPassListener {
                override fun onPartial(text: String) = run.partial(text)
                override fun onFinal(text: String) = run.final(text)
            },
            onEnd = { main.post { run.onEnded() } },
            onError = { msg -> run.error(msg) },
        )
        return true
    }

    /** 共用录音的所有引擎都结束了。 Every shared-recording engine has ended. */
    private fun finishShared() {
        runs = emptyList()
        recording = false
        abandonFocus()
        val l = listener
        listener = null
        l?.onEnd()
    }

    @SuppressLint("MissingPermission")
    private fun startRecording(gen: Int, sink: (ByteArray, Int) -> Unit, onFail: () -> Unit) {
        recording = true
        audioThread.execute {
            val rate = 16000
            val chunk = rate / 25 * 2 // 40ms × 16bit
            val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            // 先用语音识别音源（系统做降噪）；打不开、或录到的全是数字静音（有的机型对它静音）时换普通麦克风。
            // Prefer the voice-recognition source (system noise suppression); fall back to the plain mic when it
            // can't open or only delivers digital silence (some phones mute it for third-party apps).
            val sources = intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
            var si = 0
            var rec: AudioRecord? = null
            while (rec == null && si < sources.size) {
                rec = openRecorder(sources[si], rate, maxOf(minBuf, chunk * 4))
                if (rec == null) si++
            }
            if (rec == null) {
                post(gen) { it.onError("无法打开麦克风，可能被其他应用占用") }
                onFail()
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
                            r = openRecorder(sources[si], rate, maxOf(minBuf, chunk * 4))
                                ?: openRecorder(sources[si - 1], rate, maxOf(minBuf, chunk * 4))
                                ?: throw IllegalStateException("no recorder")
                            r.startRecording()
                            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { error = MIC_BUSY; continue }
                            silentChunks = -1
                        }
                    }
                    if (!recording) break
                    sink(buf, off)
                    val level = rms(buf, off)
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

    override fun hasEngine(): Boolean = runCatching { engines.selection().isNotEmpty() }.getOrDefault(true)

    /** 打开语音面板时预热本地模型。 Warm up local models when the voice panel opens. */
    override fun warmUp() {
        if (engines.selection().any { it.id == LOCAL_ENGINE_ID }) engines.local.preload()
    }

    // ------------------------------------------------------------ system engine

    private fun startSystem(gen: Int) {
        system.start(object : SystemSpeech.Sink {
            fun l(): VoiceListener? = if (gen == generation) listener else null
            override fun ready() { l()?.onReady(selfEnd = true) }
            override fun level(level: Float) { l()?.onLevel(level) }
            override fun partial(text: String) { l()?.onPartial(text) }
            override fun final(text: String) { l()?.onFinal(text) }
            override fun notice(message: String) { l()?.onNotice(message) }
            override fun end(outcome: SystemSpeech.Outcome) = systemEnded(gen, outcome)
        }, canFallback = engines.localListed)
    }

    /**
     * 系统识别结束：记下它能不能用；用户还在等着说话而本地识别在时，当场改用本地识别并说一声。
     * The system session ended: record whether it works; if the user is still waiting to speak and local
     * recognition exists, switch to it right away and say so.
     */
    private fun systemEnded(gen: Int, outcome: SystemSpeech.Outcome) {
        if (gen != generation) return
        val failures = when (outcome.kind) {
            SystemSpeech.Kind.OK -> { engines.systemWorked(outcome.service); 0 }
            SystemSpeech.Kind.FAILED -> engines.systemFailed()
            SystemSpeech.Kind.USER -> 0
        }
        val l = listener ?: return
        if (outcome.kind == SystemSpeech.Kind.FAILED && outcome.retryable && engines.localListed) {
            Log.w(TAG, "system engine failed (${outcome.message}), using local")
            l.onNotice(if (failures >= SYSTEM_FAILURE_LIMIT) FALLBACK_DEFAULT else FALLBACK_ONCE)
            // 稍等片刻再录音：识别服务放开麦克风是异步的。 Wait a moment: the service frees the mic asynchronously.
            val go = Runnable {
                fallback = null
                if (gen != generation) return@Runnable
                requestFocus()
                if (!startShared(listOf(LOCAL_ENGINE_ID), gen, multi = false)) { abandonFocus(); endSession() }
            }
            fallback = go
            main.postDelayed(go, FALLBACK_DELAY_MS)
            return
        }
        outcome.message?.let { l.onError(it) }
        endSession()
    }

    /** 等着改用本地识别（见 [systemEnded]）。 Pending switch to local recognition. */
    private var fallback: Runnable? = null

    private fun endSession() {
        val l = listener
        listener = null
        l?.onEnd()
    }

    /**
     * 依次尝试的识别服务：上次成功的 → 系统设置里的默认服务 → 手机上找到的其它识别服务 → Android 13 起的端侧识别。
     * 按组件去重；默认服务没选定时不试（一定连不上）。
     * Services tried in order: the one that worked last, the default from settings, other installed recognition
     * services, then on-device recognition on Android 13+. De-duplicated by component; an unset default is skipped
     * (it can't connect).
     */
    private fun systemServices(): List<SpeechService> {
        val list = ArrayList<SpeechService>()
        val seen = HashSet<String>()
        val default = defaultRecognizer(ctx)
        if (default != "") {
            val key = default ?: "default"
            seen += key
            list += SpeechService(key, onDevice = false) { PlatformSpeech(SpeechRecognizer.createSpeechRecognizer(ctx)) }
        }
        val services = runCatching {
            ctx.packageManager.queryIntentServices(Intent(android.speech.RecognitionService.SERVICE_INTERFACE), 0)
        }.getOrDefault(emptyList())
        for (info in services) {
            val si = info.serviceInfo ?: continue
            val cn = android.content.ComponentName(si.packageName, si.name)
            if (seen.add(cn.flattenToShortString())) {
                list += SpeechService(cn.flattenToShortString(), onDevice = false) {
                    PlatformSpeech(SpeechRecognizer.createSpeechRecognizer(ctx, cn))
                }
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) }.getOrDefault(false)
        ) {
            list += SpeechService(ON_DEVICE_KEY, onDevice = true) { PlatformSpeech(SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)) }
        }
        val last = engines.lastSystemService
        return list.sortedBy { if (it.key == last) 0 else 1 }
    }

    // ------------------------------------------------------------ control

    override fun stop() {
        recording = false
        for (r in runs) r.stop()
        system.stop()
        // 还没开始本地录音就停了：直接结束。 Stopped before the local recording began: just end.
        fallback?.let { main.removeCallbacks(it); fallback = null; endSession() }
    }

    override fun cancel() {
        recording = false
        val l = listener
        listener = null
        generation++
        val old = runs
        runs = emptyList()
        for (r in old) r.cancel()
        system.cancel()
        fallback?.let { main.removeCallbacks(it) }
        fallback = null
        abandonFocus()
        l?.onEnd()
    }

    private inline fun post(gen: Int, crossinline f: (VoiceListener) -> Unit) {
        main.post { if (gen == generation) listener?.let(f) }
    }
}
