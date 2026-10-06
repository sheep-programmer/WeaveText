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
    val mime: String = "application/octet-stream",
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
    val discovery: String = "idle",
    val discoveryError: String? = null,
    val receiveDirectory: String = "",
    val serviceError: String? = null,
    val syncMessage: String? = null,
    val addrs: List<String> = emptyList(),
    val pairingCode: String = "",
    val pairingUri: String = "",
    val directTicket: String = "",
    val directBusy: Boolean = false,
    val directMessage: String? = null,
    /** 扫码打开的配对请求（地址 + 配对码），设置页据此直接配对。 A pairing request from a scanned QR code. */
    val pendingPair: PendingPair? = null,
    val pendingDirect: String? = null,
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
    fun offerDirect(ticket: String?) {}
    fun resetPairing()
    fun rescan() {}
    fun connect(id: String, addrs: List<String> = emptyList()) {}
    fun setReceiveDirectory(uri: String) {}
    fun openPairing(addrs: List<String> = emptyList()) {}
    fun openDirect() {}
    fun joinDirect(ticket: String) {}
    fun retrySave(id: String) {}
    fun sendPersonal(id: String) {}
    fun importPersonal(id: String) {}
    fun sendFd(to: String?, fd: Int, name: String, mime: String, clip: Boolean): Boolean = sendFd(to, fd, name, mime)
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
    private val discoveryFactory: (((JSONObject) -> Unit, (String, String?) -> Unit) -> LinkDiscoveryAgent)? = null,
) : LinkController {

    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(
        LinkUiState(enabled = WeavePrefs.linkEnabled(prefs), name = WeavePrefs.linkName(prefs), clipSync = WeavePrefs.linkClipSync(prefs), receiveDirectory = prefs.getString("link_receive_directory", "").orEmpty()),
    )
    override val state: StateFlow<LinkUiState> = _state

    @Volatile private var backend: LinkBackend? = null
    private var poller: Thread? = null
    private var discoveryAgent: LinkDiscoveryAgent? = null
    private var info = JSONObject()
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
            .put("mdns", discoveryFactory == null)
        if (!b.start(cfg.toString())) {
            update { it.copy(running = false) }
            return
        }
        backend = b
        sink.onStarted()
        info = runCatching { JSONObject(b.call("""{"op":"info"}""")) }.getOrDefault(JSONObject())
        update { it.copy(running = true, fingerprint = info.optString("fingerprint"), addrs = info.optJSONArray("addrs").strings(), serviceError = null) }
        discoveryAgent = discoveryFactory?.invoke({ command -> backend?.call(command.toString()) }, { phase, error ->
            update { it.copy(discovery = phase, discoveryError = error) }
        })
        discoveryAgent?.start(info)
        refreshPeers()
        poller = Thread({ pollLoop(b) }, "weavelink-poll").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun shutdown() {
        val b = backend ?: return
        backend = null
        discoveryAgent?.stop(); discoveryAgent = null
        b.stop()
        poller?.join(1500)
        poller = null
        (b as? NativeLink)?.destroy()
        sink.onStopped()
        update { it.copy(running = false, trusted = it.trusted.map { p -> p.copy(connected = false, nearby = false) }, nearby = emptyList(), directTicket = "", directBusy = false, directMessage = null) }
    }

    private fun pollLoop(b: LinkBackend) {
        while (true) {
            val ev = b.poll(1000) ?: break
            val o = runCatching { JSONObject(ev) }.getOrNull() ?: continue
            if (o.optString("type") == "idle") continue
            main.post { if (backend === b) onEvent(o) }
        }
    }

    /** 处理一个事件（主线程）。 Handle one event on the main thread. */
    internal fun onEvent(o: JSONObject) {
        when (o.optString("type")) {
            "peerFound", "peerLost", "connected", "disconnected" -> {
                if (o.optString("transport") == "direct-udp") update { it.copy(directTicket = "", directBusy = false, directMessage = "已建立 P2P 直连，文件不经过中转") }
                refreshPeers()
            }
            "directReady" -> {
                val ticket = o.optString("ticket")
                update { it.copy(directTicket = ticket, directBusy = false, directMessage = if (o.optBoolean("public")) "连接码已生成，5 分钟内有效" else "连接码已生成；公网地址探测未成功，跨网直连可能失败") }
                main.postDelayed({ update { if (it.directTicket == ticket) it.copy(directTicket = "", directMessage = "连接码已过期，请两端重新生成") else it } }, o.optLong("expiresIn", 300).coerceIn(1, 300) * 1000)
            }
            "directFailed" -> update { it.copy(directBusy = false, directMessage = directReason(o.optString("reason"))) }
            "paired" -> { update { it.copy(pairing = PairState.Done(o.optString("name")), pendingPair = null) }; refreshPeers() }
            "pairFailed" -> update { it.copy(pairing = PairState.Failed(pairReason(o.optString("reason")))) }
            "text" -> onRemoteText(o.optString("text"), o.optBoolean("clip"), o.optString("fromName"))
            "fileStart" -> upsert(LinkTransfer(o.optString("id"), o.optString("name"), o.optBoolean("incoming"), o.optString(if (o.optBoolean("incoming")) "fromName" else "to"), 0, o.optLong("size"), LinkTransfer.State.RUNNING, mime = o.optString("mime", "application/octet-stream")))
            "fileProgress" -> transfer(o.optString("id")) { it.copy(done = o.optLong("done"), size = o.optLong("size", it.size)) }
            "fileDone" -> onFileDone(o)
            "fileFailed" -> {
                transfer(o.optString("id")) { it.copy(state = LinkTransfer.State.FAILED) }
                update { it.copy(serviceError = "传输失败：${o.optString("reason")}") }
            }
            "error" -> update { it.copy(serviceError = o.optString("message")) }
        }
    }

    private fun pairReason(r: String) = when {
        r.contains("wrong code") || r.contains("rejected") || r.contains("hello") -> "配对码不对或已过期"
        r.contains("unreachable") || r.contains("refused") || r.contains("timed out") -> "连不上这台设备：检查地址、端口、本地网络权限或远程入站规则"
        else -> "配对失败"
    }

    private fun directReason(reason: String) = when {
        reason.contains("expired") -> "连接码已过期，请两端重新生成"
        reason.contains("own connection") -> "请粘贴对方的连接码"
        reason.contains("generate your") -> "请先生成本机连接码"
        reason.contains("already") -> "正在准备或连接，请稍候"
        reason.contains("invalid") || reason.contains("certificate") -> "连接码无效，请完整复制对方的连接码"
        else -> "直连失败：请确认两端已互换连接码。当前网络可能限制 UDP 或打洞；没有使用中转"
    }

    private fun onRemoteText(text: String, clip: Boolean, from: String) {
        if (clip && !_state.value.clipSync) return
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
        if (mime==PERSONAL_MIME) {
            update {it.copy(syncMessage="收到来自 ${o.optString("fromName")} 的个人词库，请在最近传输中点「合并个人资料」")}
            return
        }
        if (o.optBoolean("clip")) {
            if (_state.value.clipSync) sink.setClipboardFileAt(File(path), mime, o.optString("name")) { uri, error ->
                main.post {
                    transfer(id) { it.copy(path = uri ?: path, state = if (uri == null) LinkTransfer.State.FAILED else LinkTransfer.State.DONE) }
                    if (error != null) update { it.copy(serviceError = error) }
                }
            }
            return
        }
        sink.saveReceivedAt(File(path), o.optString("name"), mime, o.optString("fromName"), _state.value.receiveDirectory) { destination, error ->
            main.post {
                transfer(id) { it.copy(path = destination ?: path, state = if (destination == null) LinkTransfer.State.FAILED else LinkTransfer.State.DONE) }
                if (error != null) update { it.copy(serviceError = error) }
            }
        }
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
        info.put("name", n)
        discoveryAgent?.start(info)
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

    @Synchronized override fun sendText(to: String?, text: String, clip: Boolean): Boolean {
        val b = backend ?: return false
        val cmd = JSONObject().put("op", "sendText").put("text", text).put("clip", clip)
        if (to != null) cmd.put("to", to)
        return runCatching { JSONObject(b.call(cmd.toString())).optBoolean("ok") }.getOrDefault(false)
    }

    override fun sendFd(to: String?, fd: Int, name: String, mime: String): Boolean = sendFd(to, fd, name, mime, false)
    @Synchronized override fun sendFd(to: String?, fd: Int, name: String, mime: String, clip: Boolean): Boolean {
        val b = backend ?: run { runCatching { android.os.ParcelFileDescriptor.adoptFd(fd).close() }; return false }
        val cmd = JSONObject().put("op", "sendFile").put("fd", fd).put("name", name).put("mime", mime).put("clip", clip)
        if (to != null) cmd.put("to", to)
        return runCatching { JSONObject(b.call(cmd.toString())).optBoolean("ok") }.getOrDefault(false)
    }

    override fun offerPair(p: PendingPair?) = update { it.copy(pendingPair = p, pendingDirect = null, pairing = PairState.Idle) }
    override fun offerDirect(ticket: String?) = update { it.copy(pendingDirect = ticket, pendingPair = null) }

    override fun resetPairing() = update { it.copy(pairing = PairState.Idle) }
    override fun rescan() { discoveryAgent?.start(info) }
    override fun connect(id: String, addrs: List<String>) {
        val result = backend?.call(JSONObject().put("op", "connect").put("id", id).put("addrs", JSONArray(addrs)).toString()) ?: return
        if (!JSONObject(result).optBoolean("ok")) update { it.copy(serviceError = "地址无效，请使用 IPv4:端口 或 [IPv6]:端口") }
    }
    override fun setReceiveDirectory(uri: String) {
        prefs.edit().putString("link_receive_directory", uri).apply()
        update { it.copy(receiveDirectory = uri) }
    }
    override fun openPairing(addrs: List<String>) {
        val result = backend?.call(JSONObject().put("op", "openPairing").put("addrs", JSONArray(addrs)).toString()) ?: return
        val r = JSONObject(result)
        if (r.optString("code").isEmpty()) { update { it.copy(serviceError = "无法生成配对码，请检查本机地址") }; return }
        update { it.copy(pairingCode = r.optString("code"), pairingUri = r.optString("uri")) }
        val code = r.optString("code")
        main.postDelayed({ update { if (it.pairingCode == code) it.copy(pairingCode = "", pairingUri = "") else it } }, 120_000)
    }
    override fun openDirect() {
        val b = backend ?: return
        update { it.copy(directBusy = true, directMessage = "正在准备跨网连接…") }
        val result = JSONObject(b.call("""{"op":"openDirect"}"""))
        if (!result.optBoolean("ok")) update { it.copy(directBusy = false, directMessage = directReason(result.optString("error"))) }
    }
    override fun joinDirect(ticket: String) {
        val b = backend ?: return
        update { it.copy(directBusy = true, directMessage = "正在尝试 P2P 直连…") }
        val result = JSONObject(b.call(JSONObject().put("op", "joinDirect").put("ticket", ticket.trim()).toString()))
        if (!result.optBoolean("ok")) update { it.copy(directBusy = false, directMessage = directReason(result.optString("error"))) }
    }
    override fun retrySave(id: String) {
        val t = _state.value.transfers.firstOrNull { it.id == id && it.incoming && it.state == LinkTransfer.State.FAILED && it.path?.startsWith('/') == true } ?: return
        sink.saveReceivedAt(File(t.path!!), t.name, t.mime, t.peer, _state.value.receiveDirectory) { uri, error -> main.post {
            transfer(id) { it.copy(path = uri ?: t.path, state = if (uri == null) LinkTransfer.State.FAILED else LinkTransfer.State.DONE) }
            update { it.copy(serviceError = error) }
        } }
    }

    override fun sendPersonal(id: String) {
        LinkContent.io.execute {
            runCatching {
                val engine=com.weavetext.ime.core.EngineHolder.getBlocking(ctx) ?: error("输入引擎未就绪")
                val result=JSONObject(engine.features("""{"op":"exportPersonal"}"""))
                val file=File(ctx.cacheDir,"link-outgoing/personal-${System.nanoTime()}.json").apply {parentFile!!.mkdirs();writeText(result.getString("data"))}
                try {
                    val fd=android.os.ParcelFileDescriptor.open(file,android.os.ParcelFileDescriptor.MODE_READ_ONLY).detachFd()
                    check(sendFd(id,fd,"WeaveText个人资料.weaveprofile",PERSONAL_MIME)) {"设备不在线"}
                } finally {file.delete()}
            }.fold({main.post {update {it.copy(syncMessage="已开始向设备发送个人词、候选偏好与快捷短语")}}}, {e->main.post {update {it.copy(serviceError=e.message)}}})
        }
    }
    override fun importPersonal(id: String) {
        val item=_state.value.transfers.firstOrNull {it.id==id && it.incoming && it.mime==PERSONAL_MIME} ?: return
        val path=item.path ?: return
        LinkContent.io.execute {
            runCatching {
                val file=File(path);require(file.length()<=8*1024*1024) {"个人资料文件过大"}
                val engine=com.weavetext.ime.core.EngineHolder.getBlocking(ctx) ?: error("输入引擎未就绪")
                val result=JSONObject(engine.features(JSONObject().put("op","importPersonal").put("data",file.readText()).toString()))
                check(result.optBoolean("ok")) {result.optString("error","无法合并个人资料")};file.delete()
            }.fold({main.post {
                transfer(id) {it.copy(path=null)};update {it.copy(syncMessage="个人词库与偏好已合并，重复导入不会增加词频",serviceError=null)}
            }}, {e->main.post {update {it.copy(serviceError=e.message)}}})
        }
    }

    fun onLocalMedia(uri: android.net.Uri) {
        if (!_state.value.clipSync || _state.value.connected.isEmpty()) return
        if (uri.authority == ctx.packageName + ".files") return // Owned remote clips are never echoed.
        LinkContent.io.execute {
            runCatching { _state.value.connected.forEach { LinkContent.send(ctx, this, it.id, uri, true) } }
                .onFailure { e -> main.post { update { it.copy(serviceError = e.message) } } }
        }
    }

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
        const val PERSONAL_MIME="application/x-weavetext-personal"
        private const val MAX_TRANSFERS = 20
        /** 超长文本不自动同步（避免把整篇文档悄悄发出去）。 Very long text isn't synced silently. */
        const val MAX_CLIP_CHARS = 20_000

        @Volatile private var instance: LinkManager? = null

        fun get(ctx: Context): LinkManager = instance ?: synchronized(this) {
            instance ?: LinkManager(ctx.applicationContext, WeavePrefs.of(ctx), { NativeLink() }, AndroidLinkSink(ctx.applicationContext),
                { command, status -> LinkDiscovery(ctx.applicationContext, command, status) }).also { instance = it }
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
    fun setClipboardFile(file: File, mime: String, name: String) = setClipboardImage(file, mime)
    fun setClipboardFileAt(file: File, mime: String, name: String, done: (String?, String?) -> Unit) {
        setClipboardFile(file, mime, name); done(file.path, null)
    }
    fun saveReceivedAt(file: File, name: String, mime: String, from: String, directory: String, done: (String?, String?) -> Unit) {
        saveReceived(file, name, mime, from); done(file.path, null)
    }
}

internal class AndroidLinkSink(private val ctx: Context) : LinkSink {
    private val cm get() = ctx.getSystemService(ClipboardManager::class.java)

    override fun onStarted() = LinkService.start(ctx)
    override fun onStopped() = LinkService.stop(ctx)
    override fun onConnections(count: Int, firstName: String?) = LinkNotifications.ongoing(ctx, count, firstName)

    override fun setClipboardText(text: String) {
        if (WeavePrefs.clipboardRecord(WeavePrefs.of(ctx)) && !com.weavetext.ime.ime.ClipPrivacy.privateField) LinkContent.history(ctx).add(text, System.currentTimeMillis())
        runCatching { cm?.setPrimaryClip(ClipData.newPlainText("WeaveLink", text)) }
    }

    override fun setClipboardImage(file: File, mime: String) {
        setClipboardFile(file, mime, file.name)
    }

    override fun setClipboardFile(file: File, mime: String, name: String) {
        setClipboardFileAt(file, mime, name) { _, _ -> }
    }
    override fun setClipboardFileAt(file: File, mime: String, name: String, done: (String?, String?) -> Unit) {
        LinkContent.io.execute {
            runCatching {
                val item = LinkContent.importFile(ctx, file, mime, name, WeavePrefs.clipboardRecord(WeavePrefs.of(ctx)) && !com.weavetext.ime.ime.ClipPrivacy.privateField)
                main.post {
                    runCatching { cm?.setPrimaryClip(ClipData("WeaveLink", arrayOf(mime), ClipData.Item(android.net.Uri.parse(item.uri)))) }
                        .fold({ done(item.uri, null) }, { done(null, "剪贴板写入失败：${it.message}") })
                }
            }.onFailure { done(null, "剪贴板文件读取失败：${it.message}") }
        }
    }

    override fun saveReceived(file: File, name: String, mime: String, from: String) {
        saveReceivedAt(file, name, mime, from, "") { _, _ -> }
    }

    override fun saveReceivedAt(file: File, name: String, mime: String, from: String, directory: String, done: (String?, String?) -> Unit) {
        LinkContent.io.execute {
            runCatching { LinkFiles.save(ctx, file, name, mime, directory) }.fold({ uri ->
                file.delete()
                main.post { LinkNotifications.received(ctx, name, mime, from, uri); done(uri.toString(), null) }
            }, { error -> done(null, "${error.message}；收到的原文件已保留，可重新保存或转发") })
        }
    }

    override fun notifyText(from: String, text: String) = LinkNotifications.text(ctx, from, text)

    private val main = Handler(Looper.getMainLooper())
}
