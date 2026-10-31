package com.weavetext.ime.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer

/** 完整版：用 ONNX Runtime 跑 PP-OCRv5 模型。Full build: PP-OCRv5 on ONNX Runtime. */
object OcrRuntime {
    fun create(dir: File): OcrPipeline {
        val env = OrtEnvironment.getEnvironment()
        val det = env.createSession(File(dir, OcrPlugin.DET).path, OrtSession.SessionOptions())
        val rec = env.createSession(File(dir, OcrPlugin.REC).path, OrtSession.SessionOptions())
        // 字符表每行一个字符，与识别模型配套。 One character per line, paired with the recognition model.
        val chars = File(dir, OcrPlugin.DICT).readLines(Charsets.UTF_8).filter { it.isNotEmpty() }
        return OcrPipeline(OnnxOcrNet(env, det, rec), chars)
    }
}

/** 把两个会话包装成 [OcrNet]。Wraps the two sessions as an [OcrNet]. */
class OnnxOcrNet(private val env: OrtEnvironment, private val det: OrtSession, private val rec: OrtSession) : OcrNet {
    override fun detect(input: FloatArray, h: Int, w: Int): FloatArray {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, h.toLong(), w.toLong())).use { t ->
            det.run(mapOf(det.inputNames.first() to t)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val map = out.get(0).value as Array<Array<Array<FloatArray>>>
                return FloatArray(h * w) { i -> map[0][0][i / w][i % w] }
            }
        }
    }

    override fun recognize(input: FloatArray, w: Int): Recognition {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, OcrPipeline.REC_H.toLong(), w.toLong())).use { t ->
            rec.run(mapOf(rec.inputNames.first() to t)).use { out ->
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
