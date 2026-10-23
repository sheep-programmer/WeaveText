package com.weavetext.ime.voice

import com.weavetext.ime.voice.MultiEngineResults.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 多引擎结果汇总：累积、结束、超时、默认行；相同文字可比较，是否上屏由会话的用户确认决定。 */
class MultiEngineResultsTest {
    @Test fun englishSegmentsKeepSpacesAndChineseDoesNotAcquireSpaces() {
        val r = res()
        r.final("local", "hello")
        r.partial("local", "world")
        assertEquals("hello world", r.primaryText())
        r.final("local", "world")
        r.final("local", "下午见")
        assertEquals("hello world下午见", r.primaryText())
    }
    private fun res(timeout: Long = 8_000) =
        MultiEngineResults(listOf("local" to "本地", "a" to "插件 A", "b" to "插件 B"), primaryId = "local", timeoutMs = timeout)

    @Test fun accumulatesSegmentsAndPartials() {
        val r = res()
        r.final("local", "今天")
        r.partial("local", "下午")
        assertEquals("今天下午", r.primaryText())
        r.final("local", "下午开会")
        assertEquals("今天下午开会", r.primaryText())
        assertEquals(Status.LISTENING, r.rows()[0].status)
    }

    @Test fun stopThenEndsSettle() {
        val r = res()
        r.final("local", "你好"); r.final("a", "你好！"); r.final("b", "拟好")
        r.stop(1_000)
        assertTrue(r.rows().all { it.status == Status.LOADING })
        r.end("local", 1_200); r.end("a", 1_900)
        assertFalse(r.settled)
        r.end("b", 2_500)
        assertTrue(r.settled)
        assertEquals(listOf(200L, 900L, 1_500L), r.rows().map { it.latencyMs })
        assertEquals(0, r.defaultIndex())
        assertNull(r.unanimous())
    }

    @Test fun defaultFallsBackToFirstSuccessWhenPrimaryFails() {
        val r = res()
        r.final("a", "结果 A"); r.final("b", "结果 B")
        r.stop(0)
        r.error("local", "模型加载失败", 100)
        r.end("local", 120)
        r.end("a", 300); r.end("b", 400)
        assertEquals(Status.ERROR, r.rows()[0].status)
        assertEquals("模型加载失败", r.rows()[0].error)
        assertEquals(1, r.defaultIndex())
    }

    @Test fun endWithoutTextIsNoSpeech() {
        val r = res()
        r.stop(0)
        r.end("local", 10)
        assertEquals(Status.ERROR, r.rows()[0].status)
        assertEquals(MultiEngineResults.NO_SPEECH, r.rows()[0].error)
    }

    @Test fun perEngineTimeout() {
        val r = res(timeout = 8_000)
        r.final("local", "本地结果"); r.partial("b", "半句")
        r.stop(1_000)
        r.end("local", 1_300)
        assertFalse(r.tick(8_999))
        assertTrue(r.tick(9_000))
        assertTrue(r.settled)
        val rows = r.rows()
        assertEquals(Status.TIMEOUT, rows[1].status)
        assertFalse(rows[1].selectable) // 没有文字 / no text
        // 超时但已有部分文字，仍可选。 Timed out with partial text: still selectable.
        assertEquals(Status.TIMEOUT, rows[2].status)
        assertEquals("半句", rows[2].text)
        assertTrue(rows[2].selectable)
        // 迟到的结果被忽略。 Late results are ignored.
        r.final("a", "迟到"); r.end("a", 12_000)
        assertEquals(Status.TIMEOUT, r.rows()[1].status)
    }

    @Test fun detectsMatchingResultsWithoutCommittingThem() {
        val r = res()
        for (id in listOf("local", "a", "b")) r.final(id, "一样的结果 ")
        r.stop(0)
        for (id in listOf("local", "a", "b")) r.end(id, 10)
        assertEquals("一样的结果", r.unanimous())
    }

    @Test fun explicitQualityPutsTheHighestScoringResultFirst() {
        val r = res()
        r.final("local", "本地结果")
        r.final("a", "更准确的结果")
        r.final("b", "较差的结果")
        r.score("local", 0.42f)
        r.score("a", 0.97f)
        r.score("b", 0.61f)
        r.stop(0)
        r.rows().forEach { r.end(it.id, 1) }

        assertEquals(listOf("a", "b", "local"), r.rows().map { it.id })
        assertEquals(0, r.defaultIndex())
        assertEquals(0.97f, r.rows().first().quality)
    }

    @Test fun missingQualityKeepsPrimaryAsTheStableFallback() {
        val r = res()
        r.final("local", "主引擎结果")
        r.final("a", "插件结果")
        r.stop(0)
        r.end("local", 1); r.end("a", 1)

        assertEquals(listOf("local", "a", "b"), r.rows().map { it.id })
        assertEquals(0, r.defaultIndex())
    }

    @Test fun invalidQualityIsIgnored() {
        val r = res()
        r.final("local", "主引擎结果")
        r.final("a", "插件结果")
        r.score("local", Float.NaN)
        r.score("a", 2f)
        r.stop(0)
        r.end("local", 1); r.end("a", 1)

        assertTrue(r.rows().all { it.quality == null })
        assertEquals(0, r.defaultIndex())
    }

    @Test fun aFailedHighScoreCannotBecomeTheDefault() {
        val r = res()
        r.score("a", 0.99f)
        r.final("a", "失败前的文字")
        r.final("local", "可用的主引擎")
        r.stop(0)
        r.error("a", "中断", 1)
        r.end("local", 1)
        assertEquals("local", r.rows()[r.defaultIndex()].id)
    }

    @Test fun lateQualityCannotReorderSettledResults() {
        val r = res()
        r.final("local", "主引擎")
        r.final("a", "其它结果")
        r.stop(0)
        r.end("local", 1); r.end("a", 1)
        val before = r.rows()
        r.score("a", 0.99f)
        assertEquals(before, r.rows())
    }

    @Test fun defaultWhenNothingSucceeded() {
        val r = res()
        r.stop(0)
        assertEquals(-1, r.defaultIndex())
        r.tick(10_000)
        assertEquals(-1, r.defaultIndex())
    }
}
