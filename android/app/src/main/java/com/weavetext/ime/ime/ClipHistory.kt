package com.weavetext.ime.ime

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicReference

object ClipPrivacy { @Volatile var privateField = false }

/** 一条剪贴板/常用语记录。 One clipboard (or quick phrase) entry. */
data class ClipItem(val id: Long, val text: String, val time: Long, val pinned: Boolean = false,
    val uri: String? = null, val mime: String? = null, val bytes: Long = 0) {
    val media get() = uri != null
}

/**
 * 剪贴板历史（02 §10）：已固定在前，其余按时间倒序；未固定最多 [maxUnpinned] 条，
 * 超过 [ttlMs] 的未固定项自动删除（ttl ≤ 0 表示不过期，常用语用）。单条超过 [maxItemBytes]
 * （UTF-8）不记录；总量超过 [maxTotalBytes] 时先删最旧的未固定项，仍超出再删最旧的固定项。
 * 持久化为私有目录下的 JSON，读写都在后台线程（复制时的监听回调在主线程，历史文件可达 1 MB）；
 * 第一次用到时才等读取完成。时间由调用方传入，便于单元测试。
 * Clipboard history: pinned first, the rest newest first; capped and expiring. Entries larger than
 * [maxItemBytes] (UTF-8) are not recorded; above [maxTotalBytes] the oldest unpinned (then pinned)
 * entries are dropped. Persisted as JSON in private storage; reads and writes run on a background thread (the
 * copy listener runs on the main thread and the file can reach 1 MB), and the first use waits for the read.
 * Time is injected for tests.
 */
class ClipHistory(
    private val file: File?,
    private val maxUnpinned: Int = 50,
    private val ttlMs: Long = 24L * 3600 * 1000,
    private val maxItemBytes: Int = 10 * 1024,
    private val maxTotalBytes: Int = 1024 * 1024,
) {
    private val items = ArrayList<ClipItem>()
    private var nextId = 1L
    /** 后台读取；读完之前不碰 [items]。 The background read; [items] is untouched until it finishes. */
    private val loaded = FutureTask { load() }
    /** 等待写入的最新内容（多次改动合并成一次写）。 The latest content waiting to be written (changes coalesce). */
    private val pendingSave = AtomicReference<List<ClipItem>?>(null)

    init { if (file == null) loaded.run() else IO.execute(loaded) }

    private fun ready() { runCatching { loaded.get() } }

    /** 排序后的视图。 Sorted view. */
    @Synchronized fun list(now: Long): List<ClipItem> {
        ready()
        if (prune(now)) save()
        return items.sortedWith(compareByDescending<ClipItem> { it.pinned }.thenByDescending { it.time }.thenByDescending { it.id })
    }

    /**
     * 记录一条；与已有文本相同则移到最前（保留固定状态）。空白文本忽略。
     * Record text; an existing identical entry moves to the top. Blank or oversized text is ignored.
     * @return 是否有变化。
     */
    @Synchronized fun add(text: String, now: Long): Boolean {
        if (text.isBlank() || utf8Size(text) > maxItemBytes) return false
        ready()
        val old = items.indexOfFirst { !it.media && it.text == text }
        val item = if (old >= 0) items.removeAt(old).copy(time = now) else ClipItem(nextId++, text, now)
        items += item
        prune(now, keep = item.id)
        save()
        return true
    }

    @Synchronized fun addMedia(uri: String, mime: String, name: String, bytes: Long, now: Long): Boolean {
        ready()
        val old = items.indexOfFirst { it.uri == uri }
        val item = if (old >= 0) items.removeAt(old).copy(time = now) else ClipItem(nextId++, name, now, uri = uri, mime = mime, bytes = bytes)
        items += item
        prune(now, keep = item.id)
        save()
        return true
    }

    @Synchronized fun setPinned(id: Long, pinned: Boolean) {
        ready()
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return
        items[i] = items[i].copy(pinned = pinned)
        save()
    }

    @Synchronized fun delete(ids: Collection<Long>) {
        ready()
        if (items.removeAll { it.id in ids }) save()
    }

    /** 清空未固定项。 Clear all unpinned entries. */
    @Synchronized fun clearUnpinned() {
        ready()
        if (items.removeAll { !it.pinned }) save()
    }

    /** 清空全部（含已固定）并删除文件。 Clear everything, pinned included, and delete the file. */
    @Synchronized fun clearAll() {
        ready()
        items.clear()
        pendingSave.set(null)
        listeners.forEach { it() }
        val f = file ?: return
        IO.execute { synchronized(this) {
            if (items.isEmpty()) {
                f.delete()
                if (f.name == "history.json") File(f.parentFile, "media").deleteRecursively()
            }
        } }
    }

    val size: Int @Synchronized get() { ready(); return items.size }

    /** @param keep 刚加入的一条，总量裁剪时不删。 The entry just added; never dropped by the byte cap. */
    private fun prune(now: Long, keep: Long = -1): Boolean {
        var changed = false
        if (ttlMs > 0) changed = items.removeAll { !it.pinned && now - it.time > ttlMs }
        val unpinned = items.filter { !it.pinned }.sortedBy { it.time }
        val excess = unpinned.size - maxUnpinned
        if (excess > 0) {
            val drop = unpinned.take(excess).map { it.id }.toSet()
            items.removeAll { it.id in drop }
            changed = true
        }
        var total = items.sumOf { utf8Size(it.text).toLong() }
        if (total > maxTotalBytes) {
            // 最旧的未固定项先删，其次最旧的固定项。 Oldest unpinned first, then oldest pinned.
            val order = items.sortedWith(compareBy<ClipItem> { it.pinned }.thenBy { it.time }.thenBy { it.id })
            val drop = HashSet<Long>()
            for (it in order) {
                if (total <= maxTotalBytes) break
                if (it.id == keep) continue
                drop += it.id
                total -= utf8Size(it.text)
            }
            items.removeAll { it.id in drop }
            changed = true
        }
        var mediaBytes = items.sumOf { it.bytes }
        for (item in items.sortedWith(compareBy<ClipItem> { it.pinned }.thenBy { it.time })) {
            if (mediaBytes <= 512L * 1024 * 1024) break
            if (!item.media || item.id == keep) continue
            items.remove(item); mediaBytes -= item.bytes; changed = true
        }
        return changed
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        runCatching {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val item = ClipItem(o.getLong("id"), o.getString("text"), o.getLong("time"), o.optBoolean("pinned"),
                    o.optString("uri").takeIf { it.isNotEmpty() }, o.optString("mime").takeIf { it.isNotEmpty() }, o.optLong("bytes"))
                nextId = maxOf(nextId, item.id + 1)
                if (utf8Size(item.text) <= maxItemBytes) items += item
            }
        }
    }

    /** 把当前内容交给后台写入；已有一次在排队时只更新它要写的内容。 Hand the content to the writer; coalesces. */
    private fun save() {
        val f = file ?: return
        listeners.forEach { it() }
        if (pendingSave.getAndSet(items.toList()) != null) return
        IO.execute {
            val snapshot = pendingSave.getAndSet(null) ?: return@execute
            write(f, snapshot)
        }
    }

    private fun write(f: File, list: List<ClipItem>) {
        val arr = JSONArray()
        for (it in list) arr.put(JSONObject().put("id", it.id).put("text", it.text).put("time", it.time).put("pinned", it.pinned).put("uri", it.uri).put("mime", it.mime).put("bytes", it.bytes))
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) { f.writeText(arr.toString()); tmp.delete() }
        }
        if (f.name == "history.json") synchronized(this) {
            val keep = items.mapNotNull { it.uri?.substringAfterLast('/') }.toSet()
            File(f.parentFile, "media").listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
        }
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    fun observe(listener: () -> Unit) { listeners += listener }
    fun unobserve(listener: () -> Unit) { listeners -= listener }

    companion object {
        private val stores = HashMap<String, ClipHistory>()
        @Synchronized fun shared(file: File): ClipHistory = stores.getOrPut(file.absolutePath) { ClipHistory(file) }
        @androidx.annotation.VisibleForTesting
        @Synchronized fun resetShared() { stores.clear() }
        /** 所有剪贴板文件共用的读写线程（按提交顺序执行）。 One I/O thread for all clip files, in submission order. */
        private val IO: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "weave-clips").apply { isDaemon = true } }

        /** 等之前提交的读写都完成（测试用）。 Wait for all queued reads and writes (for tests). */
        @androidx.annotation.VisibleForTesting
        fun awaitIo() { FutureTask { }.also { IO.execute(it) }.get() }
    }
}

/** UTF-8 字节数（不分配）。 UTF-8 byte length without allocating. */
internal fun utf8Size(s: String): Int {
    var n = 0
    var i = 0
    while (i < s.length) {
        val c = s[i]
        n += when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { i++; 4 }
            else -> 3
        }
        i++
    }
    return n
}
