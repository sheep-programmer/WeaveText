package com.weavetext.ime.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal interface EngineSession {
    fun feed(bytes: ByteArray, count: Int)
    fun stop()
    fun cancel()
}

/** Installed Lua plugins use their own config directory; GitHub authorization never enters this host. */
internal class NativePlugins(ctx: Context) {
    private val root = File(ctx.filesDir, "plugins")
    private val config = File(ctx.filesDir, "plugin-config")
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { Thread(it, "weave-plugin-control").apply { isDaemon = true } }
    private val host by lazy {
        NativePluginHost.nativeCreate(root.absolutePath, config.absolutePath).also { check(it != 0L) { "无法启动插件宿主" } }
    }
    @Volatile private var cached: List<VoicePlugin>? = null

    fun list(): List<VoicePlugin> {
        cached?.let { return it }
        if (root.listFiles().isNullOrEmpty()) return emptyList()
        return refresh()
    }
    @Synchronized private fun refresh(): List<VoicePlugin> {
        val a = JSONArray(NativePluginHost.nativeScan(host) ?: "[]")
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("kind") == "speech" }
            .filter { !isBuiltinEngine(it.optString("id")) }.map { info(it, installed = true) }.also { cached = it }
    }
    fun inspect(path: String): Result<VoicePlugin> = runCatching {
        val p = plugin(JSONObject(NativePluginHost.nativeInspect(path) ?: error("无法读取插件")))
        info(p, installed = false)
    }
    @Synchronized fun install(path: String): Result<VoicePlugin> = runCatching {
        inspect(path).getOrThrow() // Reject unsupported types and IDs before writing installed files.
        val p = plugin(JSONObject(NativePluginHost.nativeInstall(host, path) ?: error("无法安装插件")))
        cached = null
        refresh().first { it.id == p.getString("id") }
    }
    @Synchronized fun uninstall(id: String): Result<Unit> = runCatching {
        require(!isBuiltinEngine(id))
        NativePluginHost.nativeUninstall(host, id)?.let { error(it) }
        cached = null
    }
    fun getConfig(id: String, key: String): String? = NativePluginHost.nativeGetConfig(host, id, key)
    fun setConfig(id: String, key: String, value: String) { NativePluginHost.nativeSetConfig(host, id, key, value); cached = null }

    private fun plugin(o: JSONObject): JSONObject {
        if (o.has("error")) error(o.getString("error"))
        val p = o.getJSONObject("plugin")
        require(p.getString("kind") == "speech") { "目前仅支持语音插件" }
        require(!isBuiltinEngine(p.getString("id"))) { "插件不能使用内置模型的标识" }
        return p
    }
    private fun info(o: JSONObject, installed: Boolean): VoicePlugin {
        val id = o.getString("id")
        val schema = o.optJSONArray("configSchema") ?: JSONArray()
        val fields = (0 until schema.length()).map { i ->
            val f = schema.getJSONObject(i)
            val options = f.optJSONArray("options") ?: JSONArray()
            ConfigField(f.getString("key"), f.optString("label", f.getString("key")), f.optString("type", "text"),
                f.optString("section").takeIf(String::isNotEmpty), (0 until options.length()).map { options.getString(it) },
                f.opt("defaultValue")?.takeUnless { it == JSONObject.NULL }?.toString(),
                f.optString("helpText").takeIf(String::isNotEmpty), f.optBoolean("required"))
        }
        val hosts = o.optJSONArray("networkHosts") ?: JSONArray()
        return VoicePlugin(id, o.optString("name", id), o.optString("description"), o.optString("version"),
            if (installed && o.optBoolean("hasIcon")) NativePluginHost.nativeIcon(host, id) else null, fields,
            fields.none { it.required && (if (installed) getConfig(id, it.key) else null).orEmpty().ifEmpty { it.defaultValue.orEmpty() }.isBlank() },
            (0 until hosts.length()).map { hosts.getString(it) }, o.optBoolean("unrestrictedNetwork"))
    }

    fun session(id: String, callback: NativeSpeechCallback): EngineSession = PluginSession(id, callback)

    private inner class PluginSession(id: String, private val callback: NativeSpeechCallback) : EngineSession {
        private var handle = 0L
        private val cancelled = AtomicBoolean()
        private val ended = AtomicBoolean()
        private val state = Any()
        init {
            io.execute {
                if (cancelled.get()) return@execute
                runCatching {
                    check(NativePluginHost.nativeIsConfigured(host, id)) { "请先完成插件配置" }
                    val h = NativePluginHost.nativeStartSpeech(host, id, object : NativeSpeechCallback {
                        override fun onPartial(text: String) { main.post { if (!cancelled.get()) callback.onPartial(text) } }
                        override fun onFinal(text: String) { main.post { if (!cancelled.get()) callback.onFinal(text) } }
                        override fun onReplace(old: String, new: String) { main.post { if (!cancelled.get()) callback.onReplace(old, new) } }
                        override fun onError(message: String) { main.post { if (!cancelled.get()) callback.onError(message) } }
                        override fun onEnd() {
                            ended.set(true)
                            io.execute { releaseEnded() }
                            main.post { if (!cancelled.get()) callback.onEnd() }
                        }
                        override fun onLog(level: Int, message: String) {} // Plugin logs may contain its service credentials.
                    })
                    synchronized(state) { handle = h }
                    if (cancelled.get() && h != 0L) NativePluginHost.nativeCancel(h)
                    releaseEnded()
                }.onFailure {
                    ended.set(true)
                    main.post { if (!cancelled.get()) { callback.onError(it.message ?: "插件启动失败"); callback.onEnd() } }
                }
            }
        }
        private fun releaseEnded() {
            val h = synchronized(state) { if (ended.get()) handle.also { handle = 0 } else 0 }
            if (h != 0L) NativePluginHost.nativeRelease(h)
        }
        override fun feed(bytes: ByteArray, count: Int) {
            if (cancelled.get() || ended.get()) return
            val pcm = bytes.copyOf(count)
            io.execute { if (!cancelled.get() && !ended.get()) {
                val h = synchronized(state) { handle }
                if (h != 0L) NativePluginHost.nativeFeed(h, pcm, pcm.size)
            } }
        }
        override fun stop() { io.execute {
            val h = synchronized(state) { handle }
            if (h != 0L && !cancelled.get() && !ended.get()) NativePluginHost.nativeStop(h)
        } }
        override fun cancel() {
            cancelled.set(true)
            io.execute {
                val h = synchronized(state) { handle }
                if (h != 0L && !ended.get()) NativePluginHost.nativeCancel(h)
                releaseEnded()
            }
        }
    }
}
