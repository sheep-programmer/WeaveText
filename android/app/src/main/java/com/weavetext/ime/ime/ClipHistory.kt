package com.weavetext.ime.ime

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一条剪贴板/常用语记录。 One clipboard (or quick phrase) entry. */
data class ClipItem(val id: Long, val text: String, val time: Long, val pinned: Boolean = false)

/**
 * 剪贴板历史（02 §10）：已固定在前，其余按时间倒序；未固定最多 [maxUnpinned] 条，
 * 超过 [ttlMs] 的未固定项自动删除（ttl ≤ 0 表示不过期，常用语用）。单条超过 [maxItemBytes]
 * （UTF-8）不记录；总量超过 [maxTotalBytes] 时先删最旧的未固定项，仍超出再删最旧的固定项。
 * 持久化为私有目录下的 JSON。纯逻辑，时间由调用方传入，便于单元测试。
 * Clipboard history: pinned first, the rest newest first; capped and expiring. Entries larger than
 * [maxItemBytes] (UTF-8) are not recorded; above [maxTotalBytes] the oldest unpinned (then pinned)
 * entries are dropped. Persisted as JSON in private storage. Time is injected for tests.
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

    init { load() }

    /** 排序后的视图。 Sorted view. */
    fun list(now: Long): List<ClipItem> {
        if (prune(now)) save()
        return items.sortedWith(compareByDescending<ClipItem> { it.pinned }.thenByDescending { it.time }.thenByDescending { it.id })
    }

    /**
     * 记录一条；与已有文本相同则移到最前（保留固定状态）。空白文本忽略。
     * Record text; an existing identical entry moves to the top. Blank or oversized text is ignored.
     * @return 是否有变化。
     */
    fun add(text: String, now: Long): Boolean {
        if (text.isBlank() || utf8Size(text) > maxItemBytes) return false
        val old = items.indexOfFirst { it.text == text }
        val item = if (old >= 0) items.removeAt(old).copy(time = now) else ClipItem(nextId++, text, now)
        items += item
        prune(now, keep = item.id)
        save()
        return true
    }

    fun setPinned(id: Long, pinned: Boolean) {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return
        items[i] = items[i].copy(pinned = pinned)
        save()
    }

    fun delete(ids: Collection<Long>) {
        if (items.removeAll { it.id in ids }) save()
    }

    /** 清空未固定项。 Clear all unpinned entries. */
    fun clearUnpinned() {
        if (items.removeAll { !it.pinned }) save()
    }

    /** 清空全部（含已固定）并删除文件。 Clear everything, pinned included, and delete the file. */
    fun clearAll() {
        items.clear()
        file?.let { runCatching { it.delete() } }
    }

    val size get() = items.size

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
        return changed
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        runCatching {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val item = ClipItem(o.getLong("id"), o.getString("text"), o.getLong("time"), o.optBoolean("pinned"))
                nextId = maxOf(nextId, item.id + 1)
                if (utf8Size(item.text) <= maxItemBytes) items += item
            }
        }
    }

    private fun save() {
        val f = file ?: return
        val arr = JSONArray()
        for (it in items) arr.put(JSONObject().put("id", it.id).put("text", it.text).put("time", it.time).put("pinned", it.pinned))
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) { f.writeText(arr.toString()); tmp.delete() }
        }
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
