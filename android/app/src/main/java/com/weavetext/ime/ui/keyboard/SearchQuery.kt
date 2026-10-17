package com.weavetext.ime.ui.keyboard

/**
 * 面板搜索框的按键处理，不依赖内核：字母进组合串（显示原始拼音），数字与标点直接进查询，退格删一个字，
 * 空格确定组合串（中文里空格常被当作「取首选」，在搜索框里只把拼音变成文字）。
 * Search-box key handling that does not depend on the engine: letters compose (the raw pinyin is shown), digits and
 * punctuation go straight into the query, backspace removes one char, and space commits the composition (in the
 * search box it never picks a candidate).
 *
 * 汉字与表情可以通过 [set] 直接放进查询（例如长按表情把名字填入搜索）。
 * Han characters and emoji can be put straight into the query with [set], e.g. long-pressing an entry to search its name.
 */
class SearchQuery {
    /** 组合中的字母（原始拼音）。 Letters being composed (raw pinyin). */
    val composing = StringBuilder()
    /** 已确定的查询。 The committed query. */
    val committed = StringBuilder()

    val text: String get() = committed.toString() + composing
    /** 查询非空（正在搜索）。 The query is non-empty (a search is running). */
    val active get() = committed.isNotEmpty() || composing.isNotEmpty()

    /** 按下一个字母。 A letter was typed. */
    fun letter(c: Char): Boolean {
        if (!c.isLetter() || composing.length >= MAX_COMPOSING) return false
        composing.append(c.lowercaseChar())
        return true
    }

    /** 数字或标点：先把组合串确定下来，再接上这个字符。 A digit or punctuation: commit the composition, then add it. */
    fun literal(s: String): Boolean {
        if (s.isEmpty() || text.length + s.length > MAX_QUERY) return false
        commitComposing()
        committed.append(s)
        return true
    }

    /** 空格：确定当前的组合串。 Space: commit what is composing. */
    fun commitComposing(): Boolean {
        if (composing.isEmpty()) return false
        committed.append(composing)
        composing.setLength(0)
        return true
    }

    fun backspace(): Boolean {
        if (composing.isNotEmpty()) { composing.setLength(composing.length - 1); return true }
        if (committed.isNotEmpty()) {
            // 删一个码位（表情是两个 char）。 Remove one code point (an emoji is two chars).
            val end = committed.toString().let { it.offsetByCodePoints(it.length, -1) }
            committed.setLength(end)
            return true
        }
        return false
    }

    fun clear() {
        composing.setLength(0)
        committed.setLength(0)
    }

    /** 直接设定查询（长按名称、点分类）。 Set the query outright. */
    fun set(s: String) {
        clear()
        committed.append(s.take(MAX_QUERY))
    }

    companion object {
        /** 组合串上限。 Max composing length. */
        const val MAX_COMPOSING = 24
        /** 查询上限。 Max query length. */
        const val MAX_QUERY = 48
    }
}
