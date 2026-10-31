package com.weavetext.ime.ocr

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 离线文字识别（PP-OCRv5 mobile 的检测 + 识别流程）：像素预处理、DB 检测的连通域框、CTC 贪心解码、按行拼接。
 * 模型推理由 [OcrNet] 提供，与平台无关，便于在 JVM 上测试。
 *
 * Offline OCR (the PP-OCRv5 mobile detection + recognition flow): pixel preprocessing, DB boxes from connected
 * components, greedy CTC decoding and line assembly. Inference is behind [OcrNet], so this runs on the JVM too.
 */
class OcrFrame(val width: Int, val height: Int, val argb: IntArray)

/** 原图坐标里的一段文字。 One piece of recognized text, in frame coordinates. */
data class OcrBox(val left: Int, val top: Int, val right: Int, val bottom: Int, val text: String)

/** 识别网络的输出：[steps] × [classes] 的 logits，按行优先排列。 Recognition output: steps × classes logits, row-major. */
class Recognition(val logits: FloatArray, val steps: Int, val classes: Int)

/** 模型推理接口。输入都是 NCHW 的归一化 float 张量。 Model interface; inputs are normalized NCHW float tensors. */
interface OcrNet {
    /** 输入 [1,3,h,w]，返回 h×w 的文字概率图。 Returns the h×w text probability map. */
    fun detect(input: FloatArray, h: Int, w: Int): FloatArray

    /** 输入 [1,3,48,w]。 Input [1,3,48,w]. */
    fun recognize(input: FloatArray, w: Int): Recognition
}

class OcrPipeline(private val net: OcrNet, private val charset: List<String>) {

    fun run(frame: OcrFrame): List<OcrBox> {
        if (frame.width <= 0 || frame.height <= 0) return emptyList()
        val boxes = detect(frame)
        val out = ArrayList<OcrBox>(boxes.size)
        for (b in boxes) {
            val text = recognizeCrop(frame, b)
            if (text.isNotEmpty()) out += OcrBox(b[0], b[1], b[2], b[3], text)
        }
        return assemble(out)
    }

    /** 检测：返回原图坐标的 [left, top, right, bottom]。 Detection: boxes in frame coordinates. */
    internal fun detect(frame: OcrFrame): List<IntArray> {
        val scale = min(1f, LIMIT_SIDE.toFloat() / max(frame.width, frame.height))
        val dw = max(32, (frame.width * scale / 32).roundToInt() * 32)
        val dh = max(32, (frame.height * scale / 32).roundToInt() * 32)
        val input = planar(frame, 0, 0, frame.width, frame.height, dw, dh, DET_MEAN, DET_STD)
        val prob = net.detect(input, dh, dw)
        val boxes = textBoxes(prob, dh, dw, DET_THRESH)
        val sx = frame.width.toFloat() / dw
        val sy = frame.height.toFloat() / dh
        return boxes.mapNotNull { b ->
            // 概率图上的框向外扩一点（DB 的 unclip），再映射回原图。 Unclip a little, then map back to the frame.
            val w = (b[2] - b[0] + 1).toFloat()
            val h = (b[3] - b[1] + 1).toFloat()
            val d = w * h * UNCLIP / (2 * (w + h))
            val left = max(0, ((b[0] - d) * sx).roundToInt())
            val top = max(0, ((b[1] - d) * sy).roundToInt())
            val right = min(frame.width, ((b[2] + 1 + d) * sx).roundToInt())
            val bottom = min(frame.height, ((b[3] + 1 + d) * sy).roundToInt())
            if (right - left < MIN_BOX || bottom - top < MIN_BOX) null
            else intArrayOf(left, top, right, bottom)
        }
    }

    private fun recognizeCrop(frame: OcrFrame, b: IntArray): String {
        val cw = b[2] - b[0]
        val ch = b[3] - b[1]
        val tw = max(16, ceil(REC_H * cw.toFloat() / ch).toInt())
        val input = planar(frame, b[0], b[1], cw, ch, tw, REC_H, REC_MEAN, REC_STD)
        val rec = net.recognize(input, tw)
        return CtcDecoder.decode(rec.logits, rec.steps, rec.classes, charset)
    }

    /** 同一行（纵向重叠）的文字按 x 排序并拼接，行与行之间换行。 Boxes that overlap vertically form a line. */
    internal fun assemble(boxes: List<OcrBox>): List<OcrBox> {
        // 先按纵向中心排序，再把纵向重叠的框归为一行。 Sort by vertical centre, then group vertically overlapping boxes.
        val byY = boxes.sortedBy { (it.top + it.bottom) / 2 }
        val lines = ArrayList<MutableList<OcrBox>>()
        for (b in byY) {
            val line = lines.lastOrNull()
            val overlap = line != null && line.any { o -> min(o.bottom, b.bottom) - max(o.top, b.top) > min(o.bottom - o.top, b.bottom - b.top) / 2 }
            if (line != null && overlap) line += b else lines += mutableListOf(b)
        }
        return lines.map { line ->
            line.sortedBy { it.left }.reduce { acc, b ->
                val gap = b.left - acc.right
                val sep = if (gap > (acc.bottom - acc.top)) " " else ""
                OcrBox(min(acc.left, b.left), min(acc.top, b.top), max(acc.right, b.right), max(acc.bottom, b.bottom), acc.text + sep + b.text)
            }
        }
    }

    /** 双线性缩放并归一化成 NCHW。 Bilinear resize of a region, normalized to NCHW. */
    internal fun planar(src: OcrFrame, x0: Int, y0: Int, sw: Int, sh: Int, dw: Int, dh: Int, mean: FloatArray, std: FloatArray): FloatArray {
        val out = FloatArray(3 * dw * dh)
        val plane = dw * dh
        for (y in 0 until dh) {
            val fy = ((y + 0.5f) * sh / dh - 0.5f).coerceIn(0f, (sh - 1).toFloat())
            val y1 = fy.toInt()
            val y2 = min(sh - 1, y1 + 1)
            val wy = fy - y1
            for (x in 0 until dw) {
                val fx = ((x + 0.5f) * sw / dw - 0.5f).coerceIn(0f, (sw - 1).toFloat())
                val x1 = fx.toInt()
                val x2 = min(sw - 1, x1 + 1)
                val wx = fx - x1
                for (c in 0 until 3) {
                    val shift = if (c == 0) 16 else if (c == 1) 8 else 0 // RGB
                    fun px(yy: Int, xx: Int) = ((src.argb[(y0 + yy) * src.width + (x0 + xx)] shr shift) and 0xff).toFloat()
                    val v = (px(y1, x1) * (1 - wx) + px(y1, x2) * wx) * (1 - wy) + (px(y2, x1) * (1 - wx) + px(y2, x2) * wx) * wy
                    out[c * plane + y * dw + x] = (v / 255f - mean[c]) / std[c]
                }
            }
        }
        return out
    }

    /** 概率图上的连通域（4 邻接，阈值以上）的外接框。 Bounding boxes of 4-connected regions above the threshold. */
    internal fun textBoxes(prob: FloatArray, h: Int, w: Int, thresh: Float): List<IntArray> {
        val seen = BooleanArray(h * w)
        val boxes = ArrayList<IntArray>()
        val stack = IntArray(h * w)
        for (start in 0 until h * w) {
            if (seen[start] || prob[start] <= thresh) continue
            var top = 0
            stack[top++] = start
            seen[start] = true
            var l = Int.MAX_VALUE
            var t = Int.MAX_VALUE
            var r = Int.MIN_VALUE
            var b = Int.MIN_VALUE
            while (top > 0) {
                val p = stack[--top]
                val y = p / w
                val x = p % w
                l = min(l, x); r = max(r, x); t = min(t, y); b = max(b, y)
                for ((dy, dx) in NEIGHBOURS) {
                    val ny = y + dy
                    val nx = x + dx
                    if (ny < 0 || nx < 0 || ny >= h || nx >= w) continue
                    val q = ny * w + nx
                    if (!seen[q] && prob[q] > thresh) {
                        seen[q] = true
                        stack[top++] = q
                    }
                }
            }
            if (r - l + 1 >= 3 && b - t + 1 >= 3) boxes += intArrayOf(l, t, r, b)
        }
        return boxes
    }

    companion object {
        const val LIMIT_SIDE = 960
        const val DET_THRESH = 0.3f
        const val UNCLIP = 1.5f
        const val MIN_BOX = 4
        const val REC_H = 48
        private val NEIGHBOURS = arrayOf(intArrayOf(-1, 0), intArrayOf(1, 0), intArrayOf(0, -1), intArrayOf(0, 1))
        val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        val REC_MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
        val REC_STD = floatArrayOf(0.5f, 0.5f, 0.5f)
    }
}

/** CTC 贪心解码：每步取最大类，去掉空白（0）和连续重复。 Greedy CTC: argmax per step, drop blank 0 and repeats. */
object CtcDecoder {
    fun decode(logits: FloatArray, steps: Int, classes: Int, charset: List<String>): String {
        // 类别 k（≥1）对应 charset[k-1]；字符表之后的一类是空格（PP-OCR 的约定）。
        // Class k (≥1) is charset[k-1]; a class right after the charset is the space character.
        val sb = StringBuilder()
        var prev = -1
        for (t in 0 until steps) {
            var best = 0
            var bestV = Float.NEGATIVE_INFINITY
            val base = t * classes
            for (c in 0 until classes) {
                val v = logits[base + c]
                if (v > bestV) { bestV = v; best = c }
            }
            if (best != 0 && best != prev) {
                when {
                    best - 1 < charset.size -> sb.append(charset[best - 1])
                    best - 1 == charset.size -> sb.append(' ')
                }
            }
            prev = best
        }
        return sb.toString().trim()
    }
}
