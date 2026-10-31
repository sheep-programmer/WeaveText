package com.weavetext.ime.ime

/**
 * 选中文字时 Shift 的大小写循环：小写 → 首字母大写 → 全大写 → 小写。只改 ASCII 字母，中文和标点保持不变。
 * Shift on a selection cycles its case: lower → capitalised → upper → lower. Only ASCII letters change.
 */
object CaseCycle {
    fun next(text: String): String {
        val letters = text.filter { it in 'a'..'z' || it in 'A'..'Z' }
        if (letters.isEmpty()) return text
        return when {
            letters.all { it in 'a'..'z' } -> capitalised(text)
            letters.all { it in 'A'..'Z' } -> lower(text)
            letters.first() in 'A'..'Z' && letters.drop(1).all { it in 'a'..'z' } -> upper(text)
            else -> lower(text)
        }
    }

    private fun map(text: String, f: (Char) -> Char) =
        text.map { if (it in 'a'..'z' || it in 'A'..'Z') f(it) else it }.joinToString("")

    private fun lower(text: String) = map(text) { it.lowercaseChar() }
    private fun upper(text: String) = map(text) { it.uppercaseChar() }

    private fun capitalised(text: String): String {
        val lowered = lower(text)
        val at = lowered.indexOfFirst { it in 'a'..'z' }
        return lowered.substring(0, at) + lowered[at].uppercaseChar() + lowered.substring(at + 1)
    }
}
