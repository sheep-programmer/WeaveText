package com.weavetext.translate.google

import android.os.IBinder
import com.weavetext.translation.contract.ITranslationCallback

/** UID-scoped requests. Cancellation/death suppress late events even when the SDK download continues. */
internal class RequestBroker(private val dispatch: (String, (PluginEvent) -> Unit) -> PluginCall) : AutoCloseable {
    private data class Key(val uid: Int, val id: String)
    private val lock = Any()
    private val requests = mutableMapOf<Key, Entry>()
    private var stopped = false

    fun request(uid: Int, id: String, json: String, callback: ITranslationCallback) {
        if (!PluginProtocol.validRequestId(id)) {
            sendError(callback, id.take(PluginProtocol.MAX_REQUEST_ID_CHARS), "INVALID_REQUEST", "请求编号无效")
            return
        }
        val key = Key(uid, id)
        val entry = Entry(key, callback)
        var replaced: Entry? = null
        var rejected: String? = null
        synchronized(lock) {
            if (stopped || requests.size >= 16 && !requests.containsKey(key)) rejected = if (stopped) "PLUGIN_STOPPED" else "BUSY"
            else replaced = requests.put(key, entry)
        }
        rejected?.let { sendError(callback, id, it, "插件暂时无法接受新请求"); return }
        replaced?.let { retire(it, cancel = true) }
        // Link before dispatch. A client may die before the engine returns its handle.
        try {
            entry.binder.linkToDeath(entry.death, 0)
            val alive = synchronized(lock) { entry.linked = true; !entry.closed && requests[key] === entry }
            if (!alive) { runCatching { entry.binder.unlinkToDeath(entry.death, 0) }; return }
            val call = dispatch(json) { entry.event(it) }
            val keep = synchronized(lock) {
                if (!entry.closed && requests[key] === entry) { entry.call = call; true } else false
            }
            if (!keep) call.cancel()
        } catch (_: Exception) {
            retire(entry, cancel = true)
        }
    }

    fun cancel(uid: Int, id: String) {
        val entry = synchronized(lock) { requests[Key(uid, id)] } ?: return
        retire(entry, cancel = true)
    }

    fun cancelAll() {
        val pending = synchronized(lock) { requests.values.toList() }
        pending.forEach { retire(it, cancel = true) }
    }

    override fun close() { synchronized(lock) { stopped = true }; cancelAll() }

    private fun retire(entry: Entry, cancel: Boolean) {
        val call: PluginCall?
        synchronized(lock) {
            if (entry.closed) return
            entry.closed = true
            if (requests[entry.key] === entry) requests.remove(entry.key)
            call = entry.call
            entry.call = null
        }
        if (entry.linked) runCatching { entry.binder.unlinkToDeath(entry.death, 0) }
        if (cancel) call?.cancel()
    }

    private fun sendError(callback: ITranslationCallback, id: String, code: String, message: String) {
        runCatching { callback.onEvent(id, PluginEvent.error(code, message).encode()) }
    }

    private inner class Entry(val key: Key, val callback: ITranslationCallback) {
        val binder: IBinder = callback.asBinder()
        val death = IBinder.DeathRecipient { retire(this, cancel = true) }
        @Volatile var linked = false
        var closed = false
        var call: PluginCall? = null

        fun event(event: PluginEvent) {
            val json = event.encode()
            if (json.toByteArray(Charsets.UTF_8).size > PluginProtocol.MAX_EVENT_BYTES) {
                this.event(PluginEvent.error("RESULT_TOO_LARGE", "插件结果过大，请分段翻译"))
                return
            }
            var failed = false
            synchronized(lock) {
                if (closed || requests[key] !== this) return
                try { callback.onEvent(key.id, json) } catch (_: Exception) { failed = true }
            }
            if (failed || event.terminal) retire(this, cancel = failed)
        }
    }
}

/** Two independent checks: possession of the signature permission and a matching caller certificate. */
internal class CallerPolicy(private val permitted: (Int) -> Boolean, private val sameSignature: (Int) -> Boolean) {
    fun requireTrusted(uid: Int) {
        if (!permitted(uid) || !sameSignature(uid)) throw SecurityException("Unauthorized translation plugin caller")
    }
}
