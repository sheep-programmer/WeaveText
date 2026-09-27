package com.weavetext.ime.link

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.weavetext.ime.settings.WeavePrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 已配对设备。 A paired device. */
data class LinkPeer(val id: String, val name: String, val platform: String, val connected: Boolean, val nearby: Boolean, val addrs: List<String> = emptyList())

/** 附近未配对的设备。 A nearby device that is not paired yet. */
data class LinkNearby(val id: String, val name: String, val platform: String, val addrs: List<String>)

/** 一次传输。 One transfer. */
data class LinkTransfer(
    val id: String, val name: String, val incoming: Boolean, val peer: String,
    val done: Long, val size: Long, val state: State, val path: String? = null,
) {
    enum class State { RUNNING, DONE, FAILED }
    val fraction get() = if (size <= 0) 0f else (done.toFloat() / size).coerceIn(0f, 1f)
}

sealed class PairState {
    data object Idle : PairState()
    data object Working : PairState()
    data class Failed(val reason: String) : PairState()
    data class Done(val name: String) : PairState()
}

data class LinkUiState(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val name: String = "",
    val fingerprint: String = "",
    val trusted: List<LinkPeer> = emptyList(),
    val nearby: List<LinkNearby> = emptyList(),
    val transfers: List<LinkTransfer> = emptyList(),
    val pairing: PairState = PairState.Idle,
    val clipSync: Boolean = true,
    /** 扫码打开的配对请求（地址 + 配对码），设置页据此直接配对。 A pairing request from a scanned QR code. */
    val pendingPair: PendingPair? = null,
) {
    val connected get() = trusted.filter { it.connected }
}

data class PendingPair(val name: String, val addrs: List<String>, val code: String)

/** 设置页与分享页看到的互联操作（截图测试用假实现）。 Link operations seen by the UI; faked in screenshot tests. */
interface LinkController {
    val state: StateFlow<LinkUiState>
    fun setEnabled(on: Boolean)
    fun setClipSync(on: Boolean)
    fun rename(name: String)
    fun pair(addrs: List<String>, code: String)
    fun forget(id: String)
    fun sendText(to: String?, text: String, clip: Boolean): Boolean
    /** [fd] 的所有权交给内核。 Ownership of [fd] passes to the core. */
    fun sendFd(to: String?, fd: Int, name: String, mime: String): Boolean
    fun offerPair(p: PendingPair?)
    fun resetPairing()
}

/**
 * 织文互联在应用进程里的唯一实例：启停内核、在后台线程轮询事件、维护界面状态，
 * 并把收到的文字写进剪贴板、文件存进「下载/WeaveText」。
 * The single in-process WeaveLink instance: starts/stops the core, polls events on a background thread, keeps the UI
 * state, puts received text on the clipboard and saves received files to Downloads/WeaveText.
 */
class LinkManager internal constructor(
    private val ctx: Context,
    private val prefs: SharedPreferences,
    private val backendFactory: () -> LinkBackend,
    private val sink: LinkSink,
) : LinkController {

    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(
        LinkUiState(enabled = WeavePrefs.linkEnabled(prefs), name = WeavePrefs.linkName(prefs), clipSync = WeavePrefs.linkClipSync(prefs)),
    )
    override val state: StateFlow<LinkUiState> = _state

    @Volatile private var backend: LinkBackend? = null
    private var poller: Thread? = null
    /** 刚从电脑收到并写进剪贴板的文字：不再发回去。 Text just received and put on the clipboard: never echoed back. */
    @Volatile private var lastRemote: String? = null
    @Volatile private var lastSent: String? = null

    private fun update(f: (LinkUiState) -> LinkUiState) { _state.value = f(_state.value) }

    /** 开启时确保内核在跑（键盘显示、设置页打开时调用）。 Make sure the core runs when enabled. */
    @Synchronized
    fun ensureRunning() {
        if (!WeavePrefs.linkEnabled(prefs) || backend != null) return
        val b = backendFactory()
        val cfg = JSONObject()
            .put("name", WeavePrefs.linkName(prefs))
            .put("platform", "android")
            .put("stateDir", File(ctx.filesDir, "link").absolutePath)
            .put("inboxDir", File(ctx.cacheDir, "link-inbox").absolutePath)
        if (!b.start(cfg.toString())) {
            update { it.copy(running = false) }
            return
        }
        backend = b
        sink.onStarted()
        val info = runCatching { JSONObject(b.call("""{"op":"info"}""")) }.getOrNull()
        update { it.copy(running = true, fingerprint = info?.optString("fingerprint").orEmpty()) }
        refreshPeers()
        poller = Thread({ pollLoop(b) }, "weavelink-poll").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun shutdown() {
        val b = backend ?: return
        backend = null
        b.stop()
        poller?.join(1500)
        poller = null
        (b as? NativeLink)?.destroy()
        sink.onStopped()
        update { it.copy(running = false, trusted = it.trusted.map { p -> p.copy(connected = false, nearby = false) }, nearby = emptyList()) }
    }

    private fun pollLoop(b: LinkBackend) {
        while (true) {
            val ev = b.poll(1000) ?: break
            val o = runCatching { JSONObject(ev) }.getOrNull() ?: continue
            if (o.optString("type") == "idle") continue
            main.post { onEvent(o) }
        }
    }

    /** 处理一个事件（主线程）。 Handle one event on the main thread. */
    internal fun onEvent(o: JSONObject) {
        when (o.optString("type")) {
            "peerFound", "peerLost", "connected", "disconnected" -> refreshPeers()
            "paired" -> { update { it.copy(pairing = PairState.Done(o.optString("name")), pendingPair = null) }; refreshPeers() }
            "pairFailed" -> update { it.copy(pairing = PairState.Failed(pairReason(o.optString("reason")))) }
            "text" -> onRemoteText(o.optString("text"), o.optBoolean("clip"), o.optString("fromName"))
            "fileStart" -> upsert(LinkTransfer(o.optString("id"), o.optString("name"), o.optBoolean("incoming"), o.optString(if (o.optBoolean("incoming")) "fromName" else "to"), 0, o.optLong("size"), LinkTransfer.State.RUNNING))
            "fileProgress" -> transfer(o.optString("id")) { it.copy(done = o.optLong("done"), size = o.optLong("size", it.size)) }
            "fileDone" -> onFileDone(o)
            "fileFailed" -> transfer(o.optString("id")) { it.copy(state = LinkTransfer.State.FAILED) }
        }
    }

    private fun pairReason(r: String) = when {
        r.contains("wrong code") || r.contains("rejected") || r.contains("hello") -> "配对码不对或已过期"
        r.contains("unreachable") || r.contains("refused") || r.contains("timed out") -> "连不上这台设备，请确认在同一个 Wi-Fi 下"
        else -> "配对失败"
    }

    private fun onRemoteText(text: String, clip: Boolean, from: String) {
        if (text.isEmpty()) return
        lastRemote = text
        sink.setClipboardText(text)
        if (!clip) sink.notifyText(from, text)
    }

    private fun onFileDone(o: JSONObject) {
        val id = o.optString("id")
        val incoming = o.optBoolean("incoming")
        val path = o.optString("path").takeIf { it.isNotEmpty() }
        transfer(id) { it.copy(state = LinkTransfer.State.DONE, done = maxOf(it.done, it.size), path = path) }
        if (!incoming || path == null) return
        val mime = o.optString("mime", "application/octet-stream")
        if (o.optBoolean("clip") && mime.startsWith("image/")) sink.setClipboardImage(File(path), mime)
        else sink.saveReceived(File(path), o.optString("name"), mime, o.optString("fromName"))
    }

    private fun upsert(t: LinkTransfer) = update { s -> s.copy(transfers = (listOf(t) + s.transfers.filter { it.id != t.id }).take(MAX_TRANSFERS)) }

    private fun transfer(id: String, f: (LinkTransfer) -> LinkTransfer) =
        update { s -> s.copy(transfers = s.transfers.map { if (it.id == id) f(it) else it }) }

    private fun refreshPeers() {
        val b = backend ?: return
        val o = runCatching { JSONObject(b.call("""{"op":"peers"}""")) }.getOrNull() ?: return
        val trusted = o.optJSONArray("trusted").objects().map {
            LinkPeer(it.optString("id"), it.optString("name"), it.optString("platform"), it.optBoolean("connected"), it.optBoolean("nearby"), it.optJSONArray("addrs").strings())
        }
        val nearby = o.optJSONArray("nearby").objects().map {
            LinkNearby(it.optString("id"), it.optString("name"), it.optString("platform"), it.optJSONArray("addrs").strings())
        }
        update { it.copy(trusted = trusted, nearby = nearby) }
        sink.onConnections(trusted.count { it.connected }, trusted.firstOrNull { it.connected }?.name)
    }

    // ------------------------------------------------------------ LinkController

    override fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(WeavePrefs.LINK_ENABLED, on).apply()
        update { it.copy(enabled = on) }
        if (on) ensureRunning() else shutdown()
    }

    override fun setClipSync(on: Boolean) {
        prefs.edit().putBoolean(WeavePrefs.LINK_CLIP_SYNC, on).apply()
        update { it.copy(clipSync = on) }
    }

    override fun rename(name: String) {
        val n = name.trim().take(40).ifEmpty { return }
        prefs.edit().putString(WeavePrefs.LINK_NAME, n).apply()
        backend?.call(JSONObject().put("op", "rename").put("name", n).toString())
        update { it.copy(name = n) }
    }

    override fun pair(addrs: List<String>, code: String) {
        val b = backend ?: return
        update { it.copy(pairing = PairState.Working) }
        val r = JSONObject(b.call(JSONObject().put("op", "pair").put("addrs", JSONArray(addrs)).put("code", code.trim()).toString()))
        if (!r.optBoolean("ok")) update { it.copy(pairing = PairState.Failed(if (r.optString("error") == "code") "配对码是 6 位数字" else "地址无效")) }
    }

    override fun forget(id: String) {
        backend?.call(JSONObject().put("op", "forget").put("id", id).toString())
        refreshPeers()
    }

    override fun sendText(to: String?, text: String, clip: Boolean): Boolean {
        val b = backend ?: return false
        val cmd = JSONObject().put("op", "sendText").put("text", text).put("clip", clip)
        if (to != null) cmd.put("to", to)
        return runCatching { JSONObject(b.call(cmd.toString())).optBoolean("ok") }.getOrDefault(false)
    }

    override fun sendFd(to: String?, fd: Int, name: String, mime: String): Boolean {
        val b = backend ?: return false
        val cmd = JSONObject().put("op", "sendFile").put("fd", fd).put("name", name).put("mime", mime)
        if (to != null) cmd.put("to", to)
        return runCatching { JSONObject(b.call(cmd.toString())).optBoolean("ok") }.getOrDefault(false)
    }

    override fun offerPair(p: PendingPair?) = update { it.copy(pendingPair = p, pairing = PairState.Idle) }

    override fun resetPairing() = update { it.copy(pairing = PairState.Idle) }

    /**
     * 键盘看到本机新复制的文字：开启同步且已连接时发给电脑（刚从电脑收到的不回传）。
     * The keyboard saw a new local copy: send it to the computer when syncing (never echo what just came from it).
     */
    fun onLocalClip(text: String) {
        if (!_state.value.clipSync || text.isBlank() || text == lastRemote || text == lastSent) return
        if (_state.value.connected.isEmpty()) return
        if (text.length > MAX_CLIP_CHARS) return
        lastSent = text
        sendText(null, text, clip = true)
    }

    companion object {
        private const val MAX_TRANSFERS = 20
        /** 超长文本不自动同步（避免把整篇文档悄悄发出去）。 Very long text isn't synced silently. */
        const val MAX_CLIP_CHARS = 20_000

        @Volatile private var instance: LinkManager? = null

        fun get(ctx: Context): LinkManager = instance ?: synchronized(this) {
            instance ?: LinkManager(ctx.applicationContext, WeavePrefs.of(ctx), { NativeLink() }, AndroidLinkSink(ctx.applicationContext)).also { instance = it }
        }

        /** 测试用：换掉单例。 For tests: replace the singleton. */
        internal fun install(m: LinkManager?) { instance = m }

        fun defaultName(): String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"
    }
}

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }

/**
 * 管理器对系统的副作用（剪贴板、文件、通知、前台服务），测试里可替换。
 * The manager's side effects on the system (clipboard, files, notifications, the foreground service); fakeable.
 */
interface LinkSink {
    fun onStarted() {}
    fun onStopped() {}
    fun onConnections(count: Int, firstName: String?) {}
    fun setClipboardText(text: String)
    fun setClipboardImage(file: File, mime: String)
    fun saveReceived(file: File, name: String, mime: String, from: String)
    fun notifyText(from: String, text: String)
}

internal class AndroidLinkSink(private val ctx: Context) : LinkSink {
    private val cm get() = ctx.getSystemService(ClipboardManager::class.java)

    override fun onStarted() = LinkService.start(ctx)
    override fun onStopped() = LinkService.stop(ctx)
    override fun onConnections(count: Int, firstName: String?) = LinkNotifications.ongoing(ctx, count, firstName)

    override fun setClipboardText(text: String) {
        runCatching { cm?.setPrimaryClip(ClipData.newPlainText("WeaveLink", text)) }
    }

    override fun setClipboardImage(file: File, mime: String) {
        val uri = runCatching { androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file) }.getOrNull() ?: return
        runCatching { cm?.setPrimaryClip(ClipData.newUri(ctx.contentResolver, "WeaveLink", uri)) }
    }

    override fun saveReceived(file: File, name: String, mime: String, from: String) {
        Thread {
            val uri = LinkFiles.saveToDownloads(ctx, file, name, mime)
            file.delete()
            if (uri != null) main.post { LinkNotifications.received(ctx, name, mime, from, uri) }
        }.start()
    }

    override fun notifyText(from: String, text: String) = LinkNotifications.text(ctx, from, text)

    private val main = Handler(Looper.getMainLooper())
}
