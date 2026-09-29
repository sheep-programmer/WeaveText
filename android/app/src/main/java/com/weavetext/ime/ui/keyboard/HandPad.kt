package com.weavetext.ime.ui.keyboard

import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import kotlin.math.hypot
import kotlin.math.min

/**
 * 手写区的笔迹：已写完的笔画、正在写的一笔和画面上的墨迹。坐标相对书写区左上角（y 向下）。
 *
 * 墨迹是人手写的样子，不再是一根等粗死线（docs 手写手感）：宽度随手写速度变化（慢粗快细，经过 0.7/0.3 平滑），
 * 落笔前约 3dp 由 0.6 渐宽到 1，抬笔出笔快时补一段渐细的尖尾（笔锋）。每一笔按各点的宽度向两侧偏移成一个
 * 填充多边形。只在笔画增删时重建 Path，每帧只画一次，移动时不分配（缓冲区复用）。
 *
 * Ink of the handwriting pad: finished strokes, the stroke in progress and the drawn path. Coordinates are
 * relative to the pad's top-left (y down).
 *
 * Ink looks like a hand wrote it rather than a constant-width line: the width follows the pen speed (slow = thick,
 * fast = thin, smoothed 0.7/0.3), the first ~3dp ramp from 0.6 to 1, and a fast lift trails a pointed tail. Each
 * stroke becomes a filled outline by offsetting every point sideways by its own width. The Path is rebuilt only
 * when strokes change; drawing each frame is one call, and moves allocate nothing.
 */
class HandPad(private val clock: () -> Long = SystemClock::uptimeMillis) {
    /** 书写区（视图坐标）。 Pad bounds in view coordinates. */
    val rect = RectF()
    private val done = ArrayList<FloatArray>()
    /**
     * 画面上的已写完笔画（含笔锋尖尾）与各点宽度，与 [done] 一一对应。尖尾只用于显示，不交给识别。
     * Finished strokes as drawn (with the taper tail) and their per-point widths, parallel to [done]. The tail is
     * for display only and never reaches the recogniser.
     */
    private val drawnXy = ArrayList<FloatArray>()
    private val doneW = ArrayList<FloatArray>()
    /** 这个字已写完的笔画。 Finished strokes of the current character. */
    val strokes: List<FloatArray> get() = done
    private var cur = FloatArray(512)
    private var curW = FloatArray(256)
    private var curLen = 0
    private var curN = 0
    /** 正在写一笔。 A stroke is in progress. */
    var drawing = false
        private set
    /** 上一笔抬起的时刻（[clock]）。 When the last stroke was lifted. */
    var lastStrokeEnd = 0L
        private set
    /** 点距小于此值（px）的移动忽略。 Moves shorter than this (px) are skipped. */
    var minStep = 2f
    /** 笔画基准宽度（px），由调用方按设置换算。 Base stroke width in px, set by the caller. */
    var strokeWidth = 6f
    /** 笔锋：抬笔速度快时写出渐细的尖尾。 Taper: a fast lift trails a pointed tail. */
    var taper = true

    /** 当前墨迹（书写区坐标，填充轮廓）。 Current ink in pad coordinates (a filled outline). */
    val ink = Path()
    /** 正在淡出的上一个字。 The previous character fading out. */
    val fading = Path()
    /** 淡出开始时刻；-1 表示没有。 Fade start, -1 when none. */
    var fadeStart = -1L
        private set

    val hasInk: Boolean get() = done.isNotEmpty() || drawing

    /** 距上一笔抬起的时间。 Time since the last stroke was lifted. */
    fun sinceLastStroke(now: Long = clock()): Long = now - lastStrokeEnd

    // 速度滤波与上一采样点。 Speed filter and the last sample.
    private var vel = 0f
    private var lastT = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var strokeStart = 0L

    /** 落笔；[t] 为触摸事件时刻（不传则取当前时刻）。 Pen down at event time [t]. */
    fun begin(x: Float, y: Float, t: Long = clock()) {
        drawing = true
        curLen = 0
        curN = 0
        vel = 0f
        strokeStart = t
        lastT = strokeStart
        lastX = x
        lastY = y
        push(x, y, clampWidth(strokeWidth * 0.6f))
        rebuild()
    }

    /** 追加一个点（含历史点）。 Add a point (historical ones too). */
    fun add(x: Float, y: Float, t: Long = clock()) {
        if (!drawing || curN < 1) return
        val dx = x - cur[curLen - 2]
        val dy = y - cur[curLen - 1]
        if (dx * dx + dy * dy < minStep * minStep) return
        // 平滑速度（dp/ms 量级）：v = 0.7·上次 + 0.3·本次。 Smoothed pen speed.
        val now = t
        val dt = (now - lastT).coerceAtLeast(1L).toFloat()
        vel = 0.7f * vel + 0.3f * (hypot(x - lastX, y - lastY) / dt)
        lastT = now
        lastX = x
        lastY = y
        push(x, y, clampWidth(strokeWidth * (1f - 0.45f * min(vel / 1.6f, 1f))))
        rebuild()
    }

    /** 抬笔：这一笔记入 [strokes] 并返回（只含几何点）。 Lift: the stroke joins [strokes] and is returned. */
    fun end(t: Long = clock()): FloatArray? {
        if (!drawing) return null
        drawing = false
        lastStrokeEnd = t
        val geom = cur.copyOf(curLen)
        if (taper && curN >= 2 && vel > 0.8f) {
            val n = curLen
            // 出笔快：沿出笔方向补一段约 8dp、宽度收到 0.2 的尖尾。 Fast lift: a ~8dp tail tapering to 0.2.
            val (px, py) = cur[n - 2] to cur[n - 1]
            val (qx, qy) = cur[n - 4] to cur[n - 3]
            val d = hypot(px - qx, py - qy).coerceAtLeast(1e-3f)
            val tail = min(strokeWidth * 1.4f, 10f)
            push(px + (px - qx) / d * tail, py + (py - qy) / d * tail, strokeWidth * 0.2f)
        }
        done += geom
        drawnXy += cur.copyOf(curLen)
        doneW += curW.copyOf(curN)
        curN = 0
        rebuild()
        return geom
    }

    /** 放弃正在写的一笔（触摸被取消）。 Drop the stroke in progress (touch cancelled). */
    fun cancel() {
        if (!drawing) return
        drawing = false
        curN = 0
        rebuild()
    }

    /** 退一笔。 Drop the last finished stroke. */
    fun undo(): Boolean {
        if (done.isEmpty()) return false
        done.removeAt(done.size - 1)
        drawnXy.removeAt(drawnXy.size - 1)
        doneW.removeAt(doneW.size - 1)
        rebuild()
        return true
    }

    /**
     * 清掉已写完的笔画（正在写的一笔保留，算作下一个字的第一笔）；[fade] 时旧墨迹短暂淡出。
     * Clear the finished strokes (a stroke in progress stays as the next char's first); [fade] fades the old ink.
     */
    fun clear(fade: Boolean) {
        if (done.isEmpty()) return
        if (fade && !drawing) {
            fading.set(ink)
            fadeStart = clock()
        } else {
            fadeStart = -1L
        }
        done.clear()
        drawnXy.clear()
        doneW.clear()
        rebuild()
    }

    /** 淡出进度 0–1；1 或没有淡出时返回 -1。 Fade progress 0–1, or -1 when done / none. */
    fun fadeProgress(): Float {
        if (fadeStart < 0) return -1f
        val f = (clock() - fadeStart).toFloat() / FADE_MS
        if (f >= 1f) { fadeStart = -1L; fading.rewind(); return -1f }
        return f
    }

    /** 全部丢弃（换了布局）。 Drop everything (layout changed). */
    fun reset() {
        done.clear()
        drawnXy.clear()
        doneW.clear()
        drawing = false
        curLen = 0
        curN = 0
        fadeStart = -1L
        fading.rewind()
        ink.rewind()
    }

    private fun clampWidth(w: Float): Float = w.coerceIn(strokeWidth * 0.25f, strokeWidth)

    /** 存一个几何点及其描边宽度。 Store a point and its stroke width. */
    private fun push(x: Float, y: Float, w: Float) {
        if (curLen + 2 > cur.size) cur = cur.copyOf(cur.size * 2)
        cur[curLen++] = x
        cur[curLen++] = y
        if (curN + 1 > curW.size) curW = curW.copyOf(curW.size * 2)
        curW[curN++] = w
    }

    /** 由点列与宽度重建墨迹轮廓。 Rebuild the ink outline from the points and their widths. */
    private fun rebuild() {
        ink.rewind()
        for (i in drawnXy.indices) fill(ink, drawnXy[i], doneW[i], drawnXy[i].size / 2)
        if (drawing && curN >= 1) fill(ink, cur, curW, curN)
    }

    /**
     * 一笔的填充轮廓：沿点列左侧（法线一侧）正向走一遍，再沿右侧走回来，两端收口。
     * One stroke's filled outline: forward along the left offsets, back along the right ones, closed at both ends.
     */
    private fun fill(out: Path, geom: FloatArray, w: FloatArray, k: Int) {
        if (k < 1) return
        if (k == 1) {
            out.addCircle(geom[0], geom[1], w[0] / 2f, Path.Direction.CW)
            return
        }
        var started = false
        // 先沿左侧从头走到尾，再沿右侧从尾走回头。 Forward along the left side, then back along the right.
        for (side in intArrayOf(-1, 1)) {
            val step = -side
            var i = if (side < 0) 0 else k - 1
            while (i in 0 until k) {
                val x = geom[i * 2]
                val y = geom[i * 2 + 1]
                // 切线取前后点的差，法线为切线逆时针 90°。 Tangent from the neighbours; normal is it rotated 90°.
                val p = if (i > 0) i - 1 else 0
                val q = if (i < k - 1) i + 1 else k - 1
                var tx = geom[q * 2] - geom[p * 2]
                var ty = geom[q * 2 + 1] - geom[p * 2 + 1]
                var len = hypot(tx, ty)
                if (len < 1e-3f) { tx = 0f; ty = 0f; len = 1f }
                val h = w[i] / 2f * side
                val nx = if (len < 1e-3f) h else -ty / len * h
                val ny = if (len < 1e-3f) h else tx / len * h
                if (!started) { out.moveTo(x + nx, y + ny); started = true } else out.lineTo(x + nx, y + ny)
                i += step
            }
        }
        out.close()
    }

    companion object {
        /**
         * 停笔默认时间（毫秒）；实际值由设置档位决定（[com.weavetext.ime.settings.WeavePrefs.handPauseMs]）。
         * Default pause in ms; the real value comes from the setting (see WeavePrefs.handPauseMs).
         */
        const val COMMIT_PAUSE_MS = 800L
        const val FADE_MS = 160L
    }
}
