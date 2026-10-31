package com.weavetext.ime.stats

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** 输入统计只保存按日的计数，不保存文字；按本地日期划分今天、近 7 天与累计。 */
class TypingStatsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun millis(y: Int, m: Int, d: Int, h: Int = 12) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun countsCharactersAndCommitsPerLocalDay() {
        val stats = TypingStats(zone)
        stats.record(millis(2026, 10, 30), 4)
        stats.record(millis(2026, 10, 30, 20), 2)
        stats.record(millis(2026, 10, 29), 10)
        assertEquals(Day(chars = 6, commits = 2), stats.day(millis(2026, 10, 30)))
        assertEquals(Day(chars = 16, commits = 3), stats.total())
    }

    @Test fun lastSevenDaysIncludesToday_andNoOlder() {
        val stats = TypingStats(zone)
        stats.record(millis(2026, 10, 24), 100)  // 6 days before 10-30: inside
        stats.record(millis(2026, 10, 23), 1000) // 7 days before: outside
        stats.record(millis(2026, 10, 30), 1)
        assertEquals(Day(chars = 101, commits = 2), stats.lastDays(millis(2026, 10, 30), 7))
    }

    @Test fun encodingRoundTripsAndClearingEmptiesIt() {
        val stats = TypingStats(zone)
        stats.record(millis(2026, 10, 30), 7)
        val back = TypingStats.decode(stats.encode(), zone)
        assertEquals(stats.total(), back.total())
        stats.clear()
        assertEquals(Day(), stats.total())
        assertEquals("", stats.encode())
    }
}
