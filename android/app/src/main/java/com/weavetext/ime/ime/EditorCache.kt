package com.weavetext.ime.ime

import android.text.TextUtils

/**
 * 编辑器状态的本地镜像：选区（来自 EditorInfo 与 onUpdateSelection）和光标前的一小段文字
 * （读过一次后，由我们自己的上屏、删除同步更新）。按键路径上用它代替到编辑器 App 的同步 IPC；
 * 拿不准时返回 null / false，调用方走原来的查询路径。
 *
 * Local mirror of the editor: the selection (from EditorInfo and onUpdateSelection) and a short run of
 * text before the cursor (read once, then kept up to date by our own commits and deletes). The key path
 * uses it instead of synchronous IPCs to the editor app; when unsure it returns null / false and the
 * caller falls back to querying the editor.
 */
class EditorCache {
    var selStart = -1
        private set
    var selEnd = -1
        private set
    /** 编辑器里有 composing 区域（语音中间结果）：此时不信任镜像。 A composing region exists: don't trust the mirror. */
    private var composingRegion = false
    private val before = StringBuilder()
    private var beforeValid = false
    /** [before] 一直延伸到文本开头。 [before] reaches the start of the field. */
    private var complete = false
    /** 我们自己改动后预期的光标位置（编辑器的回报会晚到）。 Cursor positions we expect the editor to echo. */
    private val expected = IntArray(MAX_EXPECTED)
    /** 对应的预期是否来自上屏（上屏可能被编辑器拒收，删除几乎不会）。 Whether each expectation came from a commit. */
    private val expectedCommit = BooleanArray(MAX_EXPECTED)
    private var expectedCount = 0
    /** 本次输入以来编辑器回报过选区。 The editor has reported a selection since the input started. */
    private var reported = false

    /** 每次编辑器（可能）变化时递增。 Bumped whenever the editor (may have) changed. */
    var version = 0
        private set

    val selectionKnown get() = selStart >= 0 && selEnd >= 0 && !composingRegion
    val selectionEmpty get() = selectionKnown && selStart == selEnd

    /**
     * 还有我们的上屏没等到编辑器回报：它可能被拒收了（字数上限、输入过滤），镜像里的文字不能用来决定删什么。
     * One of our commits hasn't been echoed yet: the editor may have rejected it (max length, input filter), so
     * the mirrored text must not decide what to delete.
     */
    val commitPending: Boolean get() {
        for (i in 0 until expectedCount) if (expectedCommit[i]) return true
        return false
    }

    /** 新的输入框：选区来自 EditorInfo，编辑器还没回报过。 A new field: the selection comes from EditorInfo. */
    fun reset(start: Int, end: Int) {
        selStart = minOf(start, end)
        selEnd = maxOf(start, end)
        composingRegion = false
        reported = false
        dropText()
    }

    /** 发生了我们没有建模的改动（方向键、粘贴、语音…）：等编辑器下一次回报。 An edit we don't model. */
    fun invalidate() {
        selStart = -1
        selEnd = -1
        dropText()
    }

    private fun dropText() {
        before.setLength(0)
        beforeValid = false
        complete = false
        expectedCount = 0
        version++
    }

    /**
     * 编辑器回报的新选区；返回 true 表示这是我们没做过的改动（光标被挪走等）。
     * The selection reported by the editor; true when it's a change we didn't make (the cursor moved away…).
     */
    fun onUpdate(start: Int, end: Int, candStart: Int, candEnd: Int): Boolean {
        val composing = candStart >= 0 && candEnd > candStart
        composingRegion = composing
        reported = true
        if (start == end) {
            // 我们自己改动的回声：保留（可能更新的）预测。 An echo of our own edit: keep the newer prediction.
            for (i in 0 until expectedCount) if (expected[i] == start) {
                System.arraycopy(expected, i + 1, expected, 0, expectedCount - i - 1)
                System.arraycopy(expectedCommit, i + 1, expectedCommit, 0, expectedCount - i - 1)
                expectedCount -= i + 1
                return false
            }
        }
        if (expectedCount == 0 && start == selStart && end == selEnd) return false
        // 之前不知道选区（刚开始输入、镜像失效）时的第一次回报不算「被挪走」。 The first report after an unknown selection isn't a move.
        val known = selStart >= 0
        reset(start, end)
        // reset 会清掉 composing 标记，这里放回去（语音中间结果还在编辑器里）。 reset clears the flag; restore it.
        composingRegion = composing
        reported = true
        return known
    }

    /** 从编辑器读到的光标前文字（请求了 [requested] 个字符）。 Text before the cursor read from the editor. */
    fun fill(text: CharSequence, requested: Int) {
        if (!selectionKnown) return
        before.setLength(0)
        before.append(text)
        beforeValid = true
        complete = text.length < requested
        trim()
    }

    /** 光标前 [n] 个字符（开头不足 n 个时返回全部）；镜像里没有时返回 null。 The last [n] chars, or null. */
    fun textBefore(n: Int): String? {
        if (!selectionKnown || !beforeValid) return null
        if (before.length >= n) return before.substring(before.length - n)
        return if (complete) before.toString() else null
    }

    /** 本地计算 [TextUtils.getCapsMode]；镜像不够时返回 null。 Caps mode computed locally, or null. */
    fun capsMode(reqModes: Int): Int? {
        if (!selectionEmpty || !beforeValid || (!complete && before.length < CAPS_LOOKBEHIND)) return null
        return TextUtils.getCapsMode(before, before.length, reqModes)
    }

    /**
     * 镜像里的文字不够判断最后一个字形簇（没读过，或只剩一小段且不到开头）：删除前先重新读一次。
     * The mirror can't tell the last grapheme cluster (never read, or only a short tail that doesn't reach the
     * start): read again before deleting.
     */
    fun needsFill(): Boolean = !beforeValid || (!complete && before.length < CLUSTER_LOOKBEHIND)

    /** 光标前最后一个字形簇（表情、组合字符）的长度；没有或未知时为 0。 Length of the last grapheme cluster. */
    fun lastClusterLength(): Int {
        if (!selectionEmpty || !beforeValid || before.isEmpty()) return 0
        // 只看末尾一小段即可。 Only the tail matters.
        val from = (before.length - CLUSTER_LOOKBEHIND).coerceAtLeast(0)
        val tail = before.substring(from)
        val start = clusterStart(tail, tail.length)
        // 簇一直延伸到镜像开头、镜像又不完整：可能还没看全，按未知处理。 Reaches the start of a partial mirror: unknown.
        if (start == 0 && from == 0 && !complete) return 0
        return tail.length - start
    }

    /** 我们上屏了 [text]（替换选区）。 We committed [text], replacing the selection. */
    fun onCommit(text: String) {
        version++
        if (!selectionKnown) return
        if (beforeValid) { before.append(text); trim() }
        selStart += text.length
        selEnd = selStart
        expect(selStart, commit = true)
    }

    /**
     * 我们把光标移动了 [delta] 个字符（选区为空）；[passed] 是右移时越过的文字（左移时为 null）。
     * We moved the empty cursor by [delta] chars; [passed] is the text stepped over when moving right.
     */
    fun onCursorMove(delta: Int, passed: String?) {
        if (!selectionEmpty) { invalidate(); return }
        version++
        if (beforeValid) {
            if (delta >= 0) {
                if (passed != null && passed.length == delta) { before.append(passed); trim() } else dropBefore()
            } else if (-delta > before.length && !complete) {
                dropBefore()
            } else {
                before.setLength((before.length + delta).coerceAtLeast(0))
            }
        }
        selStart = (selStart + delta).coerceAtLeast(0)
        selEnd = selStart
        expect(selStart)
    }

    private fun dropBefore() {
        before.setLength(0)
        beforeValid = false
        complete = false
    }

    /** 我们删除了光标前 [n] 个字符。 We deleted [n] chars before an empty selection. */
    fun onDeleteBefore(n: Int) {
        if (!selectionEmpty) { invalidate(); return }
        version++
        if (beforeValid) {
            if (n > before.length && !complete) dropBefore()
            else before.setLength((before.length - n).coerceAtLeast(0))
        }
        selStart = (selStart - n).coerceAtLeast(0)
        selEnd = selStart
        expect(selStart)
    }

    private fun expect(pos: Int, commit: Boolean = false) {
        if (expectedCount == MAX_EXPECTED) {
            // 这么多次改动都没有回报：编辑器不回报选区，镜像会过时（用户点到别处我们也不知道），不再使用。
            // This many edits and not one report: the editor doesn't report the selection, so the mirror would go
            // stale (we'd never see the user tap elsewhere); stop using it.
            if (!reported) { invalidate(); return }
            System.arraycopy(expected, 1, expected, 0, MAX_EXPECTED - 1)
            System.arraycopy(expectedCommit, 1, expectedCommit, 0, MAX_EXPECTED - 1)
            expectedCount--
        }
        expected[expectedCount] = pos
        expectedCommit[expectedCount] = commit
        expectedCount++
    }

    private fun trim() {
        if (before.length > MAX_TEXT) {
            before.delete(0, before.length - MAX_TEXT)
            complete = false
        }
    }

    companion object {
        /** 一次读取的长度。 How much text one read fetches. */
        const val FILL = 64
        private const val MAX_TEXT = 128
        private const val MAX_EXPECTED = 16
        /** 判断句首大写需要回看的字符数（跳过空白与引号括号）。 Look-behind for sentence caps. */
        private const val CAPS_LOOKBEHIND = 16
        /** 找最后一个字形簇时回看的字符数（最长的表情序列也在其内）。 Look-behind for the last grapheme cluster. */
        const val CLUSTER_LOOKBEHIND = 32

        /**
         * [text] 中位置 [end] 之前那个字形簇的起点（表情、国旗、组合字符不拆开）；[end] 为 0 时返回 0。
         * Start of the grapheme cluster that ends at [end] in [text] (emoji, flags and combining marks stay whole).
         */
        fun clusterStart(text: CharSequence, end: Int): Int {
            if (end <= 0) return 0
            val it = android.icu.text.BreakIterator.getCharacterInstance()
            it.setText(text.toString())
            val start = it.preceding(end)
            return if (start == android.icu.text.BreakIterator.DONE) 0 else start
        }
    }
}
