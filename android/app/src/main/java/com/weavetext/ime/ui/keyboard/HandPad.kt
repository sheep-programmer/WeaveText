package com.weavetext.ime.ui.keyboard

import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock

/**
 * 手写区的笔迹（02 §13.2）：已写完的笔画、正在写的一笔和画面上的墨迹。坐标相对书写区左上角（y 向下），
 * 书写中复用缓冲区与 Path，移动时不分配；只有抬笔时为这一笔复制一份点列交给内核。
 * Ink of the handwriting pad: finished strokes, the stroke in progress and the drawn path. Coordinates are
 * relative to the pad's top-left (y down). Buffers and the Path are reused, so MOVE allocates nothing; only
 * a lifted stroke is copied once for the engine.
 */
class HandPad(private val clock: () -> Long = SystemClock::uptimeMillis) {
    /** 书写区（视图坐标）。 Pad bounds in view coordinates. */
    val rect = RectF()
    private val done = ArrayList<FloatArray>()
    /** 这个字已写完的笔画。 Finished strokes of the current character. */
    val strokes: List<FloatArray> get() = done
    private var cur = FloatArray(512)
    private var curLen = 0
    /** 正在写一笔。 A stroke is in progress. */
    var drawing = false
        private set
    /** 上一笔抬起的时刻（[clock]）。 When the last stroke was lifted. */
    var lastStrokeEnd = 0L
        private set
    /** 点距小于此值（px）的移动忽略。 Moves shorter than this (px) are skipped. */
    var minStep = 2f

    /** 当前墨迹（书写区坐标）。 Current ink in pad coordinates. */
    val ink = Path()
    /** 正在淡出的上一个字。 The previous character fading out. */
    val fading = Path()
    /** 淡出开始时刻；-1 表示没有。 Fade start, -1 when none. */
    var fadeStart = -1L
        private set

    val hasInk: Boolean get() = done.isNotEmpty() || drawing

    /** 距上一笔抬起的时间。 Time since the last stroke was lifted. */
    fun sinceLastStroke(): Long = clock() - lastStrokeEnd

    fun begin(x: Float, y: Float) {
        drawing = true
        curLen = 0
        push(x, y)
        // 落笔即显示一个点（圆头短线）。 Show a dot at once (a round-capped stub).
        ink.moveTo(x, y)
        ink.lineTo(x + DOT, y)
    }

    /** 追加一个点（含历史点）。 Add a point (historical ones too). */
    fun add(x: Float, y: Float) {
        if (!drawing || curLen < 2) return
        val px = cur[curLen - 2]
        val py = cur[curLen - 1]
        val dx = x - px
        val dy = y - py
        if (dx * dx + dy * dy < minStep * minStep) return
        push(x, y)
        // 以上一点为控制点、连到两点中点，折线变成平滑曲线。 Quad through midpoints smooths the polyline.
        ink.quadTo(px, py, (px + x) / 2f, (py + y) / 2f)
    }

    /** 抬笔：这一笔记入 [strokes] 并返回。 Lift: the stroke joins [strokes] and is returned. */
    fun end(): FloatArray? {
        if (!drawing) return null
        drawing = false
        lastStrokeEnd = clock()
        if (curLen >= 2) ink.lineTo(cur[curLen - 2], cur[curLen - 1])
        val s = cur.copyOf(curLen)
        done += s
        return s
    }

    /** 放弃正在写的一笔（触摸被取消）。 Drop the stroke in progress (touch cancelled). */
    fun cancel() {
        if (!drawing) return
        drawing = false
        rebuild()
    }

    /** 退一笔。 Drop the last finished stroke. */
    fun undo(): Boolean {
        if (done.isEmpty()) return false
        done.removeAt(done.size - 1)
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
        drawing = false
        curLen = 0
        fadeStart = -1L
        fading.rewind()
        ink.rewind()
    }

    private fun push(x: Float, y: Float) {
        if (curLen + 2 > cur.size) cur = cur.copyOf(cur.size * 2)
        cur[curLen++] = x
        cur[curLen++] = y
    }

    /** 由点列重建墨迹（与书写时同样的平滑）。 Rebuild the path with the same smoothing as live ink. */
    private fun rebuild() {
        ink.rewind()
        for (s in done) trace(s, s.size)
        if (drawing) trace(cur, curLen)
    }

    private fun trace(s: FloatArray, n: Int) {
        if (n < 2) return
        ink.moveTo(s[0], s[1])
        ink.lineTo(s[0] + DOT, s[1])
        var i = 2
        while (i + 1 < n) {
            val px = s[i - 2]
            val py = s[i - 1]
            ink.quadTo(px, py, (px + s[i]) / 2f, (py + s[i + 1]) / 2f)
            i += 2
        }
        if (n >= 4 && !(s === cur && drawing)) ink.lineTo(s[n - 2], s[n - 1])
    }

    companion object {
        /** 停笔这么久后再落笔，先上屏首选（只在代码里调整）。 Pause after which the next pen-down commits the top candidate. */
        const val COMMIT_PAUSE_MS = 600L
        const val FADE_MS = 160L
        private const val DOT = 0.1f
    }
}
