package com.weavetext.ime.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrPipelineLogicTest {
    private val charset = listOf("H", "E", "L", "O")

    /** 每步一个最大类的 logits（其余为 0）。 One-hot logits: the chosen class wins each step. */
    private fun steps(vararg winners: Int, classes: Int = 6): FloatArray {
        val out = FloatArray(winners.size * classes)
        for ((t, c) in winners.withIndex()) out[t * classes + c] = 1f
        return out
    }

    private fun pipeline() = OcrPipeline(object : OcrNet {
        override fun detect(input: FloatArray, h: Int, w: Int) = FloatArray(h * w)
        override fun recognize(input: FloatArray, w: Int) = Recognition(FloatArray(0), 0, 0)
    }, charset)

    @Test fun ctcCollapsesRepeatsAndDropsBlanks() {
        // H H 空白 E L 空白 L O → HELLO（中间的空白把两个 L 分开）。
        assertEquals("HELLO", CtcDecoder.decode(steps(1, 1, 0, 2, 3, 0, 3, 4), 8, 6, charset))
    }

    @Test fun ctcMapsTheClassAfterTheCharsetToSpace() {
        // 类别 5 = 字符表长度 + 1，即空格。 Class 5 is the space right after the four characters.
        assertEquals("H E", CtcDecoder.decode(steps(1, 5, 2), 3, 6, charset))
    }

    @Test fun detectionBoxesAreTheConnectedRegionsAboveThreshold() {
        val w = 12
        val h = 10
        val prob = FloatArray(w * h)
        for (y in 1..3) for (x in 1..4) prob[y * w + x] = 0.9f
        for (y in 5..7) for (x in 6..9) prob[y * w + x] = 0.8f
        prob[9 * w + 0] = 0.05f // 低于阈值，不成框
        // 高或宽不足 3 个像素的区域直接忽略（噪点）。 Regions under 3 px in either direction are noise and dropped.
        prob[0 * w + 11] = 0.9f
        val boxes = pipeline().textBoxes(prob, h, w, 0.3f).map { it.toList() }
        assertEquals(listOf(listOf(1, 1, 4, 3), listOf(6, 5, 9, 7)), boxes)
    }

    @Test fun boxesOnOneLineAreJoinedLeftToRight() {
        val lines = pipeline().assemble(listOf(
            OcrBox(60, 10, 90, 30, "B"),
            OcrBox(0, 12, 28, 32, "A"),
            OcrBox(0, 100, 40, 120, "C"),
        ))
        assertEquals(listOf("A B", "C"), lines.map { it.text })
    }

    @Test fun planarInputIsNormalizedPerChannelInRgbOrder() {
        // 纯红的 2×2 图：R 归一化为 (1 - 0.5)/0.5 = 1，G、B 为 -1。
        val red = OcrFrame(2, 2, IntArray(4) { 0xffff0000.toInt() })
        val t = pipeline().planar(red, 0, 0, 2, 2, 2, 2, OcrPipeline.REC_MEAN, OcrPipeline.REC_STD)
        assertEquals(12, t.size)
        assertTrue(t.slice(0 until 4).all { it == 1f })
        assertTrue(t.slice(4 until 8).all { it == -1f })
        assertTrue(t.slice(8 until 12).all { it == -1f })
    }
}
