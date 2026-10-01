package com.weavetext.ime.voice

import com.weavetext.ime.voice.MultiEngineResults.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 多引擎结果汇总：累积、结束、超时、默认行、一致即上屏。 Multi-engine result aggregation. */
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

    @Test fun unanimousCommitsDirectly() {
        val r = res()
        for (id in listOf("local", "a", "b")) r.final(id, "一样的结果 ")
        r.stop(0)
        for (id in listOf("local", "a", "b")) r.end(id, 10)
        assertEquals("一样的结果", r.unanimous())
    }

    @Test fun defaultWhenNothingSucceeded() {
        val r = res()
        r.stop(0)
        assertEquals(-1, r.defaultIndex())
        r.tick(10_000)
        assertEquals(-1, r.defaultIndex())
    }
}
