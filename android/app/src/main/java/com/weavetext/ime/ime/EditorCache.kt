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
    private var expectedCount = 0

    /** 每次编辑器（可能）变化时递增。 Bumped whenever the editor (may have) changed. */
    var version = 0
        private set

    val selectionKnown get() = selStart >= 0 && selEnd >= 0 && !composingRegion
    val selectionEmpty get() = selectionKnown && selStart == selEnd

    fun reset(start: Int, end: Int) {
        selStart = minOf(start, end)
        selEnd = maxOf(start, end)
        composingRegion = false
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
        composingRegion = candStart >= 0 && candEnd > candStart
        if (start == end) {
            // 我们自己改动的回声：保留（可能更新的）预测。 An echo of our own edit: keep the newer prediction.
            for (i in 0 until expectedCount) if (expected[i] == start) {
                System.arraycopy(expected, i + 1, expected, 0, expectedCount - i - 1)
                expectedCount -= i + 1
                return false
            }
        }
        if (expectedCount == 0 && start == selStart && end == selEnd) return false
        // 之前不知道选区（刚开始输入、镜像失效）时的第一次回报不算「被挪走」。 The first report after an unknown selection isn't a move.
        val known = selStart >= 0
        reset(start, end)
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

    /** 光标前最后一个字形簇（表情、组合字符）的长度；没有或未知时为 0。 Length of the last grapheme cluster. */
    fun lastClusterLength(): Int {
        if (!selectionEmpty || !beforeValid || before.isEmpty()) return 0
        val it = android.icu.text.BreakIterator.getCharacterInstance()
        // 只看末尾一小段即可。 Only the tail matters.
        val from = (before.length - 32).coerceAtLeast(0)
        it.setText(before.substring(from))
        val end = it.last()
        val start = it.previous()
        return if (start == android.icu.text.BreakIterator.DONE) 0 else end - start
    }

    /** 我们上屏了 [text]（替换选区）。 We committed [text], replacing the selection. */
    fun onCommit(text: String) {
        version++
        if (!selectionKnown) return
        if (beforeValid) { before.append(text); trim() }
        selStart += text.length
        selEnd = selStart
        expect(selStart)
    }

    /** 我们删除了光标前 [n] 个字符。 We deleted [n] chars before an empty selection. */
    fun onDeleteBefore(n: Int) {
        if (!selectionEmpty) { invalidate(); return }
        version++
        if (beforeValid) {
            if (n > before.length && !complete) { before.setLength(0); beforeValid = false }
            else before.setLength((before.length - n).coerceAtLeast(0))
        }
        selStart = (selStart - n).coerceAtLeast(0)
        selEnd = selStart
        expect(selStart)
    }

    private fun expect(pos: Int) {
        if (expectedCount == MAX_EXPECTED) {
            System.arraycopy(expected, 1, expected, 0, MAX_EXPECTED - 1)
            expectedCount--
        }
        expected[expectedCount++] = pos
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
    }
}
