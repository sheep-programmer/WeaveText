package com.weavetext.ime.ui

import com.weavetext.ime.ui.keyboard.ExpressionSearch
import com.weavetext.ime.ui.keyboard.ExpressionSearch.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表情名称搜索的匹配与排序（不依赖 Android）。 Matching and ranking of the expression name search (no Android).
 */
class ExpressionSearchTest {
    private val rows = listOf(
        Row("😀", "嘿嘿", "grinning face", "笑脸", "hei hei"),
        Row("😊", "羞涩微笑", "smiling face with smiling eyes", "笑脸", "xiu se wei xiao"),
        Row("🙂", "微笑", "slightly smiling face", "笑脸", "wei xiao"),
        Row("😭", "放声大哭", "loudly crying face", "笑脸", "fang sheng da ku"),
        Row("🐶", "狗脸", "dog face", "动物", "gou lian"),
        Row("🐱", "猫脸", "cat face", "动物", "mao lian"),
        Row("☀️", "太阳", "sun", "出行", "tai yang"),
        Row("(＾▽＾)", "开心笑", "", "开心", "kai xin xiao"),
    )

    private fun names(q: String) = ExpressionSearch.rank(rows, q).map { it.name }

    @Test fun pinyinMatchesNamesWithoutTones() {
        // 全拼、音节前缀、跨音节与隔音符：weixiao、weix、wei'xiao 都找得到「微笑」。 Full pinyin, syllable prefix, and an apostrophe.
        assertEquals("微笑", names("weixiao").first())
        assertEquals("微笑", names("weix").first())
        assertEquals("微笑", names("wei'xiao").first())
        assertTrue(names("xiao").contains("羞涩微笑"))
        // 只有拼音中间部分（ixiao）不算音节开头，但仍按包含命中。 Mid-syllable only still matches, but ranks lower.
        assertTrue(names("ixiao").contains("微笑"))
        // 拼音与名字一样能分档：狗脸的 gou 比分类命中靠前。 Pinyin hits outrank group-only hits.
        assertEquals(listOf("狗脸"), names("gou"))
        assertEquals("开心笑", names("kaixin").first())
    }

    @Test fun chineseNamesMatchByPrefixAndContainment() {
        assertEquals("微笑", names("微笑").first())
        // 「微笑」也是「羞涩微笑」的一部分，但以关键词开头的名字排前面。 A prefix hit leads a containment hit.
        assertEquals(listOf("微笑", "羞涩微笑"), names("微笑"))
        assertTrue(names("哭").contains("放声大哭"))
        assertTrue(names("动物").any { it == "狗脸" || it == "猫脸" })
    }

    @Test fun englishNamesAndGroupsMatchCaseInsensitively() {
        assertTrue(names("dog").contains("狗脸"))
        assertTrue(names("SUN").contains("太阳"))
        assertTrue(names("动物").contains("猫脸"))
    }

    @Test fun everyTermHasToMatchAndJoinedTermsStillMatch() {
        assertTrue(names("动物 猫").contains("猫脸"))
        assertTrue(names("动物 太阳").isEmpty())
        // 拆开的词连起来仍是一个词：「微 笑」找得到「微笑」。 The concatenated terms still count as one term.
        assertEquals("微笑", names("微 笑").first())
        assertEquals(listOf("微", "笑"), ExpressionSearch.terms("微 笑"))
    }

    @Test fun typingTheExpressionItselfLeadsAndEmptyQueryKeepsOrder() {
        assertEquals(listOf("🐱", "猫脸", "动物"), ExpressionSearch.rank(rows, "🐱").first().let { listOf(it.text, it.name, it.group) })
        assertEquals(rows, ExpressionSearch.rank(rows, "   "))
    }

    @Test fun prefixHitsCountsNamesStartingWithTheQuery() {
        assertEquals(1, ExpressionSearch.prefixHits(rows, "微笑"))
        assertEquals(0, ExpressionSearch.prefixHits(rows, "脸"))
        assertEquals(1, ExpressionSearch.prefixHits(rows, "wei"))
        assertEquals(1, ExpressionSearch.prefixHits(rows, "wei xiao"))
        assertEquals(0, ExpressionSearch.prefixHits(rows, "xiao"))
    }
}
