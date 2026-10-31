package com.weavetext.ime.ui.keyboard

/**
 * 滑行输入的轨迹解码：把手指经过的按键依次记下，相邻重复的去掉，只留字母。
 * 少于两个不同字母的轨迹不是滑行（单击或上滑），返回空串。
 *
 * Glide trace decoding: record the keys the finger crosses in order, drop consecutive repeats, keep letters only.
 * A trace with fewer than two distinct letters is a tap or a swipe, not a glide, and gives an empty string.
 */
object GlideTrace {
    fun letters(keysAlongPath: List<Char?>): String {
        val out = StringBuilder()
        for (k in keysAlongPath) {
            if (k == null || k !in 'a'..'z') continue
            if (out.lastOrNull() != k) out.append(k)
        }
        return if (out.toSet().size >= 2) out.toString() else ""
    }
}
