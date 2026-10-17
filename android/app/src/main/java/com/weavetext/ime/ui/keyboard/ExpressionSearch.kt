package com.weavetext.ime.ui.keyboard

import java.util.Locale

/**
 * 表情与颜文字的名称搜索（离线，与 Mac 端同一套规则）。
 * Offline name search over emoji and kaomoji, with the same rules on macOS.
 *
 * 关键词按空格拆开，每个词都要命中；命中范围是中文名、英文名、所属分类与名字的拼音（有声调数据、无声调匹配，
 * 所以打 weixiao、wei'xiao 或 weix 都能找到「微笑」）。拆开的词连起来也算一个词，所以「微 笑」找得到「微笑」。
 * 打分：直接打出表情本身 > 名字等于关键词 > 名字（或拼音音节开头）以关键词开头 > 名字里某个词以关键词开头 >
 * 包含（含拼音中间部分）> 只有英文名或分类命中；同档按名字长短、再按目录顺序。
 * The query is split on spaces and every term has to match; terms are matched against the Chinese name, the English
 * name, the group and the name's pinyin (toneless, so weixiao, wei'xiao and weix all find 微笑). The concatenation of
 * the terms counts as one more term, so "微 笑" finds 微笑. Ranking: the expression typed itself, whole name equals
 * the query, the name or a pinyin syllable starts with it, a word in the name starts with it, contains (including
 * mid-pinyin), then English-name or group-only hits; ties go to the shorter name and then to catalogue order.
 *
 * 索引只建一次（[Index]），每次输入只做匹配与排序。 The index is built once ([Index]); each keystroke only matches.
 */
object ExpressionSearch {
    /** 一条可搜索的表情。 A searchable expression. */
    data class Row(val text: String, val name: String, val english: String, val group: String, val pinyin: String = "")

    private fun fold(s: String) = if (s.any { it.code >= 0x80 }) s else s.lowercase(Locale.ROOT)

    /**
     * 一条记录的小写副本、拼音连写与音节起点。 A row lower-cased, with its pinyin joined and syllable starts.
     * 中文不需要大小写折叠；含 à 这类声调字母的片段一律小写（拼音数据已无声调，这里只防 Mac 端手工数据）。
     * Chinese needs no case folding; fragments with tone letters are lower-cased anyway.
     */
    private class Entry(
        val row: Row, val name: String, val english: String, val group: String,
        val pinyin: String, val joined: String, val starts: IntArray,
    ) {
        /** 词组拼接（去空格）是否包含关键词。 Whether the joined pinyin contains the term. */
        fun pinyinHit(term: String) = joined.contains(term)

        /** 关键词是否正好从某个音节开头接上（weix 命中「微笑」，ixiao 不算）。 Whether a syllable starts with the term. */
        fun pinyinStarts(term: String) = starts.any { joined.startsWith(term, it) }

        /**
         * 一条记录是否（部分）命中关键词：中文名、英文名、拼音，或单字分类（「笑」这种一到两字的分类只在
         * 「动物」这类长分类上才参与，免得搜「笑」被整类笑脸淹没）。 A term hits the name, English name or pinyin;
         * one-character groups (笑, 人) don't count, or searching 笑 would return the whole smiley category.
         */
        fun hit(term: String) = name.contains(term) || english.contains(term) ||
            (group.length >= 2 && group.contains(term)) ||
            pinyinHit(term.replace("'", "").replace("\u2019", ""))
    }

    /** 预建索引：目录加载后建一次，之后每次输入只走匹配。 Index built once after the catalogue loads. */
    class Index(val rows: List<Row>) {
        private val entries: List<Entry> = rows.map { row ->
            val pinyin = row.pinyin.lowercase(Locale.ROOT)
            val starts = ArrayList<Int>(8)
            var at = 0
            for (part in pinyin.split(' ')) {
                if (part.isNotEmpty()) starts += at
                at += part.length
            }
            Entry(row, fold(row.name), fold(row.english), fold(row.group), pinyin, pinyin.replace(" ", ""), starts.toIntArray())
        }

        /** 查询为空时原样返回（保持目录顺序）。 An empty query keeps everything in catalogue order. */
        fun rank(query: String): List<Row> {
            val terms = terms(query)
            if (terms.isEmpty()) return rows
            val trimmed = query.trim().replace(Regex("\\s+"), " ")
            val joinedTerm = terms.joinToString("")
            val hits = entries.withIndex().mapNotNull { (i, e) -> score(e, terms, trimmed, joinedTerm)?.let { Triple(it, i, e.row) } }
            return hits.sortedWith(compareBy({ it.first }, { it.second })).map { it.third }
        }

        /** 名字或拼音以关键词开头的条数（搜索框下的提示用）。 Names or pinyin starting with the query. */
        fun prefixHits(query: String): Int {
            val q = fold(query.trim()).replace(" ", "").replace("'", "").replace("\u2019", "")
            if (q.isEmpty()) return 0
            return entries.count { it.name.startsWith(q) || it.pinyin.startsWith(q) || it.joined.startsWith(q) }
        }

        private fun score(e: Entry, terms: List<String>, trimmed: String, joinedTerm: String): Int? {
            if (trimmed.isNotEmpty() && e.row.text == trimmed) return -1
            if (!terms.all(e::hit) && !e.hit(joinedTerm)) return null
            val q = if (e.hit(terms.first())) terms.first() else joinedTerm
            val qJoined = q.replace(" ", "").replace("'", "").replace("\u2019", "")
            val name = e.name
            val band = when {
                name == trimmed -> 0
                name.startsWith(q) -> 1
                // 拼音音节开头：weix → 微笑、害羞的微笑。 A pinyin syllable starts with the query.
                e.pinyinStarts(qJoined) -> 2
                name.split(' ', '·', '，', ',', '、').any { it.trim().startsWith(q) } -> 3
                name.contains(q) || e.pinyinHit(qJoined) -> 4
                else -> 5
            }
            // 同档里短名字优先；两条「微笑」距离相近时按目录顺序。 Shorter names win; ties keep catalogue order.
            return band * 10_000 + name.length.coerceAtMost(9_999)
        }
    }

    /** 查询词：空格分开。 Query terms, split on spaces. */
    fun terms(query: String): List<String> =
        query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map(::fold)

    /** 便捷入口（每次重建索引；面板用 [Index] 缓存）。 Convenience (rebuilds the index; panels cache an [Index]). */
    fun rank(rows: List<Row>, query: String): List<Row> = Index(rows).rank(query)

    fun prefixHits(rows: List<Row>, query: String): Int = Index(rows).prefixHits(query)
}
