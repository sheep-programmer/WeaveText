package com.weavetext.ime.stats

import android.content.SharedPreferences
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 一天的计数：上屏的字数与上屏次数。不含任何文字。 A day's counts: characters and commits. No text is kept. */
data class Day(val chars: Long = 0, val commits: Long = 0) {
    operator fun plus(other: Day) = Day(chars + other.chars, commits + other.commits)
}

/**
 * 输入统计（按本地日期）。只记录计数：今天、近 7 天与累计的上屏字数和次数，不保存输入的内容。
 * Typing statistics by local date. Only counts: characters and commits for today, the last seven days, and all time.
 */
class TypingStats(private val zone: ZoneId = ZoneId.systemDefault()) {
    private val days = sortedMapOf<LocalDate, Day>()

    fun record(epochMillis: Long, chars: Int) {
        if (chars <= 0) return
        val date = date(epochMillis)
        val old = days[date] ?: Day()
        days[date] = old + Day(chars.toLong(), 1)
    }

    fun day(epochMillis: Long): Day = days[date(epochMillis)] ?: Day()

    /** 包含今天在内的最近 [count] 天。 The most recent [count] days, today included. */
    fun lastDays(nowMillis: Long, count: Int): Day {
        val today = date(nowMillis)
        val first = today.minusDays(count.toLong() - 1)
        return days.filterKeys { it >= first && it <= today }.values.fold(Day()) { acc, d -> acc + d }
    }

    fun total(): Day = days.values.fold(Day()) { acc, d -> acc + d }

    fun clear() = days.clear()

    /** 每行「日期<Tab>字数<Tab>次数」。 One "date<TAB>chars<TAB>commits" line per day. */
    fun encode(): String = days.entries.joinToString("") { (date, d) -> "$date\t${d.chars}\t${d.commits}\n" }

    private fun date(epochMillis: Long): LocalDate = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    companion object {
        fun decode(text: String, zone: ZoneId = ZoneId.systemDefault()): TypingStats {
            val stats = TypingStats(zone)
            for (line in text.lines()) {
                val f = line.split('\t')
                if (f.size != 3) continue
                val date = runCatching { LocalDate.parse(f[0]) }.getOrNull() ?: continue
                val chars = f[1].toLongOrNull() ?: continue
                val commits = f[2].toLongOrNull() ?: continue
                stats.days[date] = Day(chars, commits)
            }
            return stats
        }
    }
}

/** 偏好里的统计存取；按次写回，数据很小。 Load and save the stats in preferences; the data is tiny. */
object TypingStatsStore {
    const val KEY = "typing_stats"

    @Synchronized
    fun record(prefs: SharedPreferences, chars: Int, now: Long = System.currentTimeMillis()) {
        val stats = TypingStats.decode(prefs.getString(KEY, "").orEmpty())
        stats.record(now, chars)
        prefs.edit().putString(KEY, stats.encode()).apply()
    }

    @Synchronized
    fun load(prefs: SharedPreferences): TypingStats = TypingStats.decode(prefs.getString(KEY, "").orEmpty())

    @Synchronized
    fun clear(prefs: SharedPreferences) = prefs.edit().remove(KEY).apply()
}
