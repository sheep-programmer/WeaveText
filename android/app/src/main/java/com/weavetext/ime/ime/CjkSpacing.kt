package com.weavetext.ime.ime

/**
 * 中英之间自动加空格（可在设置中关闭，默认关闭）：中文与英文字母、数字相邻时，在两者之间加一个空格。
 * 标点、空白与同一文字之间不加。
 *
 * Optional space between Chinese and Latin text (off by default): one space where Chinese meets a Latin letter or a
 * digit, in either order. Punctuation, whitespace and same-script neighbours never get one.
 */
object CjkSpacing {
    fun isCjk(c: Char): Boolean = c in '㐀'..'䶿' || c in '一'..'鿿' || c in '豈'..'﫿'

    fun isLatin(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    /** [before] 是光标前的字符（null 表示输入框开头），[committed] 是将要上屏的第一个字符。 */
    fun needsSpace(before: Char?, committed: Char?): Boolean {
        if (before == null || committed == null) return false
        return (isCjk(before) && isLatin(committed)) || (isLatin(before) && isCjk(committed))
    }
}
