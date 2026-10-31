package com.weavetext.ime.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.FloatBuffer

/** 用真正的 PP-OCRv5 模型跑一张渲染出来的文字图。模型缺失时跳过。 Runs the real PP-OCRv5 models on a rendered image. */
class OcrOnnxTest {
    private val dir = File("../../.ref/ocr")

    private class OnnxNet(private val env: OrtEnvironment, private val det: OrtSession, private val rec: OrtSession) : OcrNet {
        override fun detect(input: FloatArray, h: Int, w: Int): FloatArray {
            val t = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, h.toLong(), w.toLong()))
            t.use {
                det.run(mapOf(det.inputNames.first() to it)).use { out ->
                    @Suppress("UNCHECKED_CAST")
                    val map = out.get(0).value as Array<Array<Array<FloatArray>>>
                    return FloatArray(h * w) { i -> map[0][0][i / w][i % w] }
                }
            }
        }

        override fun recognize(input: FloatArray, w: Int): Recognition {
            val t = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, OcrPipeline.REC_H.toLong(), w.toLong()))
            t.use {
                rec.run(mapOf(rec.inputNames.first() to it)).use { out ->
                    @Suppress("UNCHECKED_CAST")
                    val seq = out.get(0).value as Array<Array<FloatArray>>
                    val steps = seq[0].size
                    val classes = seq[0][0].size
                    val flat = FloatArray(steps * classes)
                    for (s in 0 until steps) for (c in 0 until classes) flat[s * classes + c] = seq[0][s][c]
                    return Recognition(flat, steps, classes)
                }
            }
        }
    }

    /** 5×7 点阵字库（只含测试用的字符）。 A 5×7 dot-matrix font with just the glyphs the test needs. */
    private val glyphs = mapOf(
        'H' to listOf("10001", "10001", "10001", "11111", "10001", "10001", "10001"),
        'E' to listOf("11111", "10000", "10000", "11110", "10000", "10000", "11111"),
        'L' to listOf("10000", "10000", "10000", "10000", "10000", "10000", "11111"),
        'O' to listOf("01110", "10001", "10001", "10001", "10001", "10001", "01110"),
        '2' to listOf("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
        '0' to listOf("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
        '6' to listOf("00110", "01000", "10000", "11110", "10001", "10001", "01110"),
        ' ' to listOf("00000", "00000", "00000", "00000", "00000", "00000", "00000"),
    )

    /** 白底黑字，每个点 [scale] 像素。 Black dots on white, [scale] pixels per dot. */
    private fun render(text: String, scale: Int = 8): OcrFrame {
        val gap = scale
        val margin = 4 * scale
        val w = margin * 2 + text.length * (5 * scale + gap)
        val h = margin * 2 + 7 * scale
        val argb = IntArray(w * h) { 0xffffffff.toInt() }
        for ((i, ch) in text.withIndex()) {
            val glyph = glyphs[ch] ?: error("no glyph for $ch")
            val x0 = margin + i * (5 * scale + gap)
            for (gy in 0 until 7) for (gx in 0 until 5) {
                if (glyph[gy][gx] != '1') continue
                for (dy in 0 until scale) for (dx in 0 until scale) {
                    argb[(margin + gy * scale + dy) * w + x0 + gx * scale + dx] = 0xff000000.toInt()
                }
            }
        }
        return OcrFrame(w, h, argb)
    }

    @Test fun readsRenderedEnglishText() {
        val det = File(dir, "ch_PP-OCRv5_det_mobile.onnx")
        val rec = File(dir, "ch_PP-OCRv5_rec_mobile.onnx")
        val chars = File(dir, "ppocrv5_dict.txt")
        assumeTrue("OCR models not fetched", det.isFile && rec.isFile && chars.isFile)
        val env = OrtEnvironment.getEnvironment()
        val recSession = env.createSession(rec.path, OrtSession.SessionOptions())
        // 字符表应当与插件运行时读取的元数据一致。 The charset must match what the plugin reads from the metadata.
        val fileLines = chars.readLines(Charsets.UTF_8).filter { it.isNotEmpty() }
        OnnxNet(env, env.createSession(det.path, OrtSession.SessionOptions()), recSession).let { net ->
            val boxes = OcrPipeline(net, fileLines).run(render("HELLO 2026"))
            val text = boxes.joinToString(" ") { it.text }
            println("OCR result: $text")
            assert(text.replace(" ", "").contains("HELLO2026")) { "got: $text" }
        }
    }
}
