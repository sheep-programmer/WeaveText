package com.weavetext.ime.ui.keyboard

import kotlin.math.roundToInt

/**
 * 悬浮键盘的几何计算（06 §5），不依赖 View，便于单元测试。
 * 输入法窗口保持全屏宽高且透明；卡片的位置以「可移动范围内的比例」保存，横竖屏各一份，
 * 屏幕尺寸变化后仍落在屏幕内。
 * Floating-keyboard geometry, View-free for unit tests. The IME window stays full size and
 * transparent; the card position is stored as fractions of the movable range, per orientation.
 */
object FloatingGeometry {
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
        fun contains(x: Int, y: Int) = x in left until right && y in top until bottom
    }

    /** 交给系统的窗口 insets：内容区与可见区都从窗口底部开始，因此 App 不会被压缩。 */
    data class Insets(val contentTop: Int, val visibleTop: Int, val touchable: Box)

    /** 没保存过位置时：水平居中、贴近底部。 Default: centred, near the bottom. */
    val DEFAULT = 0.5f to 1f

    /** 卡片宽度：竖屏为窗口的 75%，横屏为 50% 且不超过 480 dp。 Card width. */
    fun cardWidth(windowW: Int, landscape: Boolean, density: Float): Int =
        if (landscape) minOf((windowW * 0.5f).roundToInt(), (480 * density).roundToInt()) else (windowW * 0.75f).roundToInt()

    /**
     * 比例位置 → 卡片矩形。卡片被限制在 [minTop, maxBottom] 与窗口左右边界之间。
     * Fractions → card rect, kept between [minTop, maxBottom] and the window's side edges.
     */
    fun place(fx: Float, fy: Float, windowW: Int, cardW: Int, cardH: Int, minTop: Int, maxBottom: Int): Box {
        val rangeX = (windowW - cardW).coerceAtLeast(0)
        val rangeY = (maxBottom - cardH - minTop).coerceAtLeast(0)
        val left = (fx.coerceIn(0f, 1f) * rangeX).roundToInt()
        val top = minTop + (fy.coerceIn(0f, 1f) * rangeY).roundToInt()
        return Box(left, top, left + cardW, top + cardH)
    }

    /** 拖动后的左上角（可能越界）→ 夹回屏幕内的矩形。 Clamp a dragged card back on screen. */
    fun clamp(left: Int, top: Int, windowW: Int, cardW: Int, cardH: Int, minTop: Int, maxBottom: Int): Box {
        val l = left.coerceIn(0, (windowW - cardW).coerceAtLeast(0))
        val t = top.coerceIn(minTop, (maxBottom - cardH).coerceAtLeast(minTop))
        return Box(l, t, l + cardW, t + cardH)
    }

    /** 卡片矩形 → 保存用的比例。 Card rect → fractions to store. */
    fun fractions(card: Box, windowW: Int, minTop: Int, maxBottom: Int): Pair<Float, Float> {
        val rangeX = windowW - card.width
        val rangeY = maxBottom - card.height - minTop
        val fx = if (rangeX <= 0) 0.5f else card.left.toFloat() / rangeX
        val fy = if (rangeY <= 0) 1f else (card.top - minTop).toFloat() / rangeY
        return fx.coerceIn(0f, 1f) to fy.coerceIn(0f, 1f)
    }

    fun insets(windowH: Int, card: Box) = Insets(windowH, windowH, card)

    const val MIN_SCALE = 0.7f
    const val MAX_SCALE = 1.3f

    /**
     * 卡片缩放的允许范围：宽不小于 220 dp、不超过窗口宽的 95%，高不超过窗口高的 65%。
     * Allowed card scale: at least 220 dp wide, at most 95% of the window width and 65% of its height.
     * @param baseW 缩放为 1 时的卡片宽；@param baseH 缩放为 1 时的卡片高。 Card size at scale 1.
     */
    fun clampScale(scale: Float, windowW: Int, windowH: Int, baseW: Int, baseH: Int, density: Float): Float {
        if (baseW <= 0 || baseH <= 0) return 1f
        val hi = minOf(MAX_SCALE, windowW * 0.95f / baseW, windowH * 0.65f / baseH)
        val lo = maxOf(MIN_SCALE, 220 * density / baseW).coerceAtMost(hi)
        return scale.coerceIn(lo, hi)
    }

    /** 拖动右下角：宽、高的相对变化取平均，按键保持比例。 Corner drag: average of the relative width and height change. */
    fun resizeScale(startScale: Float, startW: Int, startH: Int, dx: Float, dy: Float): Float {
        if (startW <= 0 || startH <= 0) return startScale
        return startScale * ((startW + dx) / startW + (startH + dy) / startH) / 2f
    }

    fun decodeScale(s: String?): Float = s?.toFloatOrNull()?.takeIf { !it.isNaN() }?.coerceIn(MIN_SCALE, MAX_SCALE) ?: 1f

    fun encode(f: Pair<Float, Float>) = "%.4f,%.4f".format(java.util.Locale.ROOT, f.first, f.second)

    fun decode(s: String?): Pair<Float, Float> {
        val parts = s?.split(',') ?: return DEFAULT
        val x = parts.getOrNull(0)?.toFloatOrNull() ?: return DEFAULT
        val y = parts.getOrNull(1)?.toFloatOrNull() ?: return DEFAULT
        if (x.isNaN() || y.isNaN()) return DEFAULT
        return x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f)
    }
}
