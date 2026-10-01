package com.weavetext.ime.voice

/**
 * 多引擎会话的结果汇总（06 §6），纯逻辑、时间由调用方传入，便于单元测试。
 * 每个引擎一行：说话时累积它的最终分段与中间结果；停止收音后进入「识别中」，
 * 引擎结束即完成，超过 [timeoutMs] 仍未结束记为超时。
 * Aggregates a multi-engine session; pure logic with caller-supplied time. One row per engine:
 * segments accumulate while speaking; after the recording stops a row is loading until its engine
 * ends, or times out after [timeoutMs].
 */
class MultiEngineResults(
    engines: List<Pair<String, String>>,
    val primaryId: String,
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    enum class Status { LISTENING, LOADING, DONE, ERROR, TIMEOUT }

    data class Row(
        val id: String,
        val name: String,
        val text: String,
        val status: Status,
        val error: String? = null,
        /** 停止收音到出结果的时间。 Time from end of recording to result. */
        val latencyMs: Long? = null,
    ) {
        /** 可以点选上屏。 Can be committed. */
        val selectable get() = text.isNotBlank() && (status == Status.DONE || status == Status.TIMEOUT)
        val pending get() = status == Status.LISTENING || status == Status.LOADING
    }

    private class Acc(val id: String, val name: String) {
        val text = StringBuilder()
        var partial = ""
        var status = Status.LISTENING
        var error: String? = null
        var latency: Long? = null
        fun full() = text.toString() + com.weavetext.ime.voice.local.TwoPassRecognizer.continuation(text.lastOrNull(), partial)
    }

    private val acc = LinkedHashMap<String, Acc>().apply { for ((id, name) in engines) put(id, Acc(id, name)) }

    /** 停止收音的时刻；null = 还在说话。 When the recording stopped; null while speaking. */
    var stoppedAt: Long? = null
        private set

    fun partial(id: String, text: String) {
        val a = acc[id] ?: return
        if (a.pending) a.partial = text
    }

    fun final(id: String, text: String) {
        val a = acc[id] ?: return
        if (!a.pending) return
        a.text.append(com.weavetext.ime.voice.local.TwoPassRecognizer.continuation(a.text.lastOrNull(), text))
        a.partial = ""
    }

    fun replace(id: String, old: String, new: String) {
        val a = acc[id] ?: return
        val i = a.text.lastIndexOf(old)
        if (i >= 0 && old.isNotEmpty()) a.text.replace(i, i + old.length, new)
    }

    fun error(id: String, message: String, now: Long) {
        val a = acc[id] ?: return
        if (!a.pending) return
        a.status = Status.ERROR
        a.error = message
        a.latency = stoppedAt?.let { now - it }
    }

    /** 引擎结束：有文字即完成，没有则记为「没有识别到内容」。 Engine ended. */
    fun end(id: String, now: Long) {
        val a = acc[id] ?: return
        if (!a.pending) return
        if (a.partial.isNotEmpty()) { a.text.append(com.weavetext.ime.voice.local.TwoPassRecognizer.continuation(a.text.lastOrNull(), a.partial)); a.partial = "" }
        if (a.text.isBlank()) { a.status = Status.ERROR; a.error = NO_SPEECH } else a.status = Status.DONE
        a.latency = (now - (stoppedAt ?: now)).coerceAtLeast(0)
    }

    /** 停止收音：说话中的行变为识别中。 Recording stopped. */
    fun stop(now: Long) {
        if (stoppedAt != null) return
        stoppedAt = now
        for (a in acc.values) if (a.status == Status.LISTENING) a.status = Status.LOADING
    }

    /** 检查超时，返回是否有变化。 Apply timeouts; true if anything changed. */
    fun tick(now: Long): Boolean {
        val start = stoppedAt ?: return false
        if (now - start < timeoutMs) return false
        var changed = false
        for (a in acc.values) if (a.pending) {
            if (a.partial.isNotEmpty()) { a.text.append(com.weavetext.ime.voice.local.TwoPassRecognizer.continuation(a.text.lastOrNull(), a.partial)); a.partial = "" }
            a.status = Status.TIMEOUT
            a.error = TIMEOUT
            a.latency = now - start
            changed = true
        }
        return changed
    }

    private val Acc.pending get() = status == Status.LISTENING || status == Status.LOADING

    /** 全部引擎都有了结论。 Every engine has settled. */
    val settled get() = acc.values.none { it.pending }

    fun rows(): List<Row> = acc.values.map { Row(it.id, it.name, it.full(), it.status, it.error, it.latency) }

    /** 说话时显示的主引擎文本。 Primary engine's text while speaking. */
    fun primaryText(): String = acc[primaryId]?.full().orEmpty()

    /** 默认高亮的行：主引擎，否则第一个成功的；都没有为 -1。 Default row: primary, else first success. */
    fun defaultIndex(): Int {
        val rows = rows()
        val p = rows.indexOfFirst { it.id == primaryId }
        if (p >= 0 && rows[p].selectable) return p
        return rows.indexOfFirst { it.selectable }
    }

    /** 全部完成且文字一致时直接上屏的文本。 Text to commit directly when all engines agree. */
    fun unanimous(): String? {
        if (!settled) return null
        val rows = rows()
        if (rows.isEmpty() || rows.any { it.status != Status.DONE }) return null
        val first = rows[0].text.trim()
        return first.takeIf { t -> t.isNotEmpty() && rows.all { it.text.trim() == t } }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val NO_SPEECH = "没有识别到内容"
        const val TIMEOUT = "超时"
    }
}
