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
     * Apply returned dimensions too when the requested card is larger than the usable window.
     */
    fun place(fx: Float, fy: Float, windowW: Int, cardW: Int, cardH: Int, minTop: Int, maxBottom: Int): Box {
        val width = cardW.coerceIn(0, windowW.coerceAtLeast(0))
        val height = cardH.coerceIn(0, (maxBottom - minTop).coerceAtLeast(0))
        val rangeX = (windowW - width).coerceAtLeast(0)
        val rangeY = (maxBottom - height - minTop).coerceAtLeast(0)
        val left = (fx.takeIf { it.isFinite() } ?: DEFAULT.first).coerceIn(0f, 1f).times(rangeX).roundToInt()
        val top = minTop + (fy.takeIf { it.isFinite() } ?: DEFAULT.second).coerceIn(0f, 1f).times(rangeY).roundToInt()
        return Box(left, top, left + width, top + height)
    }

    /** Clamp position AND oversized dimensions. The caller must apply the returned width/height. */
    fun clamp(left: Int, top: Int, windowW: Int, cardW: Int, cardH: Int, minTop: Int, maxBottom: Int): Box {
        val width = cardW.coerceIn(0, windowW.coerceAtLeast(0))
        val height = cardH.coerceIn(0, (maxBottom - minTop).coerceAtLeast(0))
        val l = left.coerceIn(0, (windowW - width).coerceAtLeast(0))
        val t = top.coerceIn(minTop, (maxBottom - height).coerceAtLeast(minTop))
        return Box(l, t, l + width, t + height)
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
    private const val GRIP_W_DP = 44f
    private const val DOCK_W_DP = 44f
    const val MAX_SCALE = 1.3f

    /**
     * 卡片缩放的允许范围：宽不小于 220 dp、不超过窗口宽的 95%，高不超过窗口高的 65%。
     * Allowed card scale: at least 220 dp wide, at most 95% of the window width and 65% of its height.
     * @param baseW 缩放为 1 时的卡片宽；@param baseH 缩放为 1 时的卡片高。 Card size at scale 1.
     */
    fun clampScale(scale: Float, windowW: Int, windowH: Int, baseW: Int, baseH: Int, density: Float): Float {
        if (baseW <= 0 || baseH <= 0) return 1f
        val hi = minOf(MAX_SCALE, windowW.coerceAtLeast(0) * 0.95f / baseW, windowH.coerceAtLeast(0) * 0.65f / baseH)
        val d = density.takeIf { it.isFinite() && it > 0f } ?: 1f
        val lo = maxOf(MIN_SCALE, 220 * d / baseW).coerceAtMost(hi)
        return (scale.takeIf { it.isFinite() } ?: 1f).coerceIn(lo, hi)
    }

    /**
     * 拖动条左端的缩放手柄（卡片坐标）：只在拖动条里，不占任何按键的触控区。
     * The resize grip at the left end of the drag bar (card coordinates); it never covers a key.
     */
    fun gripBox(handleH: Int, density: Float) = Box(0, 0, (GRIP_W_DP * density).roundToInt(), handleH)

    /** 拖动条右端的停靠按钮（卡片坐标）。 The dock button at the right end of the drag bar. */
    fun dockBox(cardW: Int, handleH: Int, density: Float) = Box(cardW - (DOCK_W_DP * density).roundToInt(), 0, cardW, handleH)

    /** 拖动条下方的键区（卡片坐标）。 The key area below the drag bar (card coordinates). */
    fun keyArea(cardW: Int, cardH: Int, handleH: Int) = Box(0, handleH, cardW, cardH)

    /**
     * 拖动左上角：向左上为放大，宽、高的相对变化取平均，按键保持比例。
     * Top-left corner drag: up/left grows; average of the relative width and height change.
     */
    fun resizeScale(startScale: Float, startW: Int, startH: Int, dx: Float, dy: Float): Float {
        return resizeScale(startScale, startW, startH, dx, dy, FloatingResizeCorner.TOP_LEFT)
    }

    /** Same aspect-preserving calculation for any corner; dx/dy are cumulative raw-pointer deltas. */
    fun resizeScale(startScale: Float, startW: Int, startH: Int, dx: Float, dy: Float, corner: FloatingResizeCorner): Float {
        if (startW <= 0 || startH <= 0 || !dx.isFinite() || !dy.isFinite()) return startScale
        return startScale * (1f + (corner.horizontalSign * dx / startW + corner.verticalSign * dy / startH) / 2f)
    }

    data class ResizeResult(val box: Box, val scale: Float, val corner: FloatingResizeCorner)

    fun oppositeAnchor(start: Box, corner: FloatingResizeCorner): Pair<Int, Int> =
        (if (corner.horizontalSign < 0) start.right else start.left) to
            (if (corner.verticalSign < 0) start.bottom else start.top)

    /** Anchor the opposite corner. Apply the returned dimensions as well as its translation. */
    fun anchorOpposite(start: Box, corner: FloatingResizeCorner, cardW: Int, cardH: Int): Box {
        val left = if (corner.horizontalSign < 0) start.right - cardW else start.left
        val top = if (corner.verticalSign < 0) start.bottom - cardH else start.top
        return Box(left, top, left + cardW, top + cardH)
    }

    /**
     * Anchor an actual measured size, clipping dimensions BEFORE anchoring to handle integer
     * row-pitch rounding in KbMetrics. Apply the resulting width/height too, not just translation.
     * Strict by default. With allowSlideAtEdge, use the whole usable window for size limits, then
     * translate only overflowing axes just enough to fit. The original opposite anchor is retained
     * whenever the requested size fits there. Always use the DOWN box, not the last shifted box.
     */
    fun anchorOpposite(
        start: Box, corner: FloatingResizeCorner, cardW: Int, cardH: Int, bounds: Box,
        allowSlideAtEdge: Boolean = false,
    ): Box {
        require(fits(start, bounds))
        val maxW = if (allowSlideAtEdge) bounds.width else if (corner.horizontalSign < 0) start.right - bounds.left else bounds.right - start.left
        val maxH = if (allowSlideAtEdge) bounds.height else if (corner.verticalSign < 0) start.bottom - bounds.top else bounds.bottom - start.top
        val anchored = anchorOpposite(start, corner, cardW.coerceIn(0, maxW), cardH.coerceIn(0, maxH))
        if (!allowSlideAtEdge) return anchored
        val left = anchored.left.coerceIn(bounds.left, bounds.right - anchored.width)
        val top = anchored.top.coerceIn(bounds.top, bounds.bottom - anchored.height)
        return Box(left, top, left + anchored.width, top + anchored.height)
    }

    /**
     * Resize inside usable window bounds (including navigation insets), limiting the size BEFORE
     * anchoring so the opposite corner never moves in the default strict mode.
     * With allowSlideAtEdge, expansion may translate the card into bounds when an edge blocks it;
     * the nominal MAX_SCALE/95%-width/65%-height limits still apply to the whole usable window.
     * Measured layout must use the same option in anchorOpposite (or controller.anchorMeasured).
     * The start box must already fit the bounds.
     * fixedHeight is ALL unscaled height (control bars + candidate topBar + padTop + padBottom).
     * In WeaveKeyboard this is card.height - metrics.mainHeight.roundToInt().
     * Use the same DOWN snapshot for every MOVE, never the last MOVE box.
     * A valid existing box is unchanged at zero delta, even if old settings exceed nominal limits.
     * Such a box may shrink (subject to the minimum) but cannot grow past its initial size during
     * this gesture. The next gesture uses its new start box and reevaluates the nominal limits.
     */
    fun resizeFromCorner(
        start: Box, startScale: Float, corner: FloatingResizeCorner, dx: Float, dy: Float,
        bounds: Box, density: Float, fixedHeight: Int = 0, allowSlideAtEdge: Boolean = false,
    ): ResizeResult {
        require(start.width > 0 && start.height > 0 && fits(start, bounds)) { "Place the card inside usable bounds before resizing" }
        require(startScale.isFinite() && startScale > 0f && density.isFinite() && density > 0f)
        require(fixedHeight in 0 until start.height)
        val bodyH = start.height - fixedHeight
        val maxW = if (allowSlideAtEdge) bounds.width else if (corner.horizontalSign < 0) start.right - bounds.left else bounds.right - start.left
        val maxH = if (allowSlideAtEdge) bounds.height else if (corner.verticalSign < 0) start.bottom - bounds.top else bounds.bottom - start.top
        val nominalMax = minOf(
            MAX_SCALE / startScale,
            (bounds.width * 0.95f).coerceAtLeast(1f) / start.width,
            (bounds.height * 0.65f - fixedHeight).coerceAtLeast(1f) / bodyH,
        )
        val hi = minOf(maxOf(1f, nominalMax), maxW.toFloat() / start.width, (maxH - fixedHeight).toFloat() / bodyH)
        // On small windows, a minimum size must yield to the fixed anchor and available space.
        val lo = minOf(1f, hi, maxOf(MIN_SCALE / startScale, 220 * density / start.width))
        val rawScale = resizeScale(startScale, start.width, bodyH, dx, dy, corner)
        val ratio = (rawScale / startScale).coerceIn(lo, hi)
        val width = (start.width * ratio).roundToInt().coerceIn(1, maxW)
        val height = (fixedHeight + bodyH * ratio).roundToInt().coerceIn(fixedHeight + 1, maxH)
        return ResizeResult(anchorOpposite(start, corner, width, height, bounds, allowSlideAtEdge), startScale * ratio, corner)
    }

    fun fits(card: Box, bounds: Box) = card.left >= bounds.left && card.top >= bounds.top &&
        card.right <= bounds.right && card.bottom <= bounds.bottom && card.width >= 0 && card.height >= 0

    const val MIN_TOUCH_TARGET_DP = 48f

    /**
     * Corner grip in reserved TOP/BOTTOM control bars, card-local coordinates. The board must occupy
     * [handleH, cardH - handleH), with candidates/keys strictly inside that range. A >=48dp bar makes
     * each target 48dp square. The legacy gripBox(handleH,density) is retained for existing callers.
     */
    fun gripBox(corner: FloatingResizeCorner, cardW: Int, cardH: Int, handleH: Int, density: Float): Box {
        require(density.isFinite() && density > 0f)
        val width = cardW.coerceAtLeast(0)
        val height = cardH.coerceAtLeast(0)
        val side = (MIN_TOUCH_TARGET_DP * density).roundToInt().coerceAtLeast(1)
        val tw = minOf(side, width)
        val th = minOf(side, handleH.coerceAtLeast(0), height / 2)
        val left = if (corner.horizontalSign < 0) 0 else width - tw
        val top = if (corner.verticalSign < 0) 0 else height - th
        return Box(left, top, left + tw, top + th)
    }

    /**
     * Card-local corner targets, 48dp square when space permits. Reserve a frame/gutters for these
     * targets in the UI; overlaying bottom targets directly on keys would steal key touches.
     * Tiny cards clip targets to the card; hitCorner resolves overlapping targets by distance.
     */
    fun cornerTargets(cardW: Int, cardH: Int, density: Float, policy: FloatingResizePolicy = FloatingResizePolicy()): Map<FloatingResizeCorner, Box> {
        require(density.isFinite() && density > 0f)
        val width = cardW.coerceAtLeast(0)
        val height = cardH.coerceAtLeast(0)
        val side = (MIN_TOUCH_TARGET_DP * density).roundToInt().coerceAtLeast(1)
        return FloatingResizeCorner.entries.filter(policy::allows).associateWith { corner ->
            gripBox(corner, width, height, side, density)
        }
    }

    /** Card-local coordinates; disabled corners never claim the gesture. */
    fun hitCorner(x: Float, y: Float, cardW: Int, cardH: Int, density: Float, policy: FloatingResizePolicy = FloatingResizePolicy()): FloatingResizeCorner? {
        if (!x.isFinite() || !y.isFinite()) return null
        return cornerTargets(cardW, cardH, density, policy).filterValues { box ->
            x >= box.left && x < box.right && y >= box.top && y < box.bottom
        }.keys.minByOrNull { corner ->
            val dx = x - if (corner.horizontalSign < 0) 0 else cardW
            val dy = y - if (corner.verticalSign < 0) 0 else cardH
            dx * dx + dy * dy
        }
    }

    /**
     * Close button directly LEFT of the top-right grip. Use R.drawable.ic_close
     * and FloatingResizeAccessibility.CLOSE_DESCRIPTION. A >=48dp-high bar and >=144dp card width
     * provide three separate 48dp targets; a legacy 22dp bar must be enlarged by the UI integrator.
     * Below 144dp place this centred close target in its OWN row to avoid competing with grips.
     */
    fun closeBox(cardW: Int, handleH: Int, density: Float): Box {
        require(density.isFinite() && density > 0f)
        val width = cardW.coerceAtLeast(0)
        val side = (MIN_TOUCH_TARGET_DP * density).roundToInt().coerceAtLeast(1)
        val targetW = minOf(side, width)
        if (width < 3 * side) {
            val left = (width - targetW) / 2
            return Box(left, 0, left + targetW, minOf(side, handleH.coerceAtLeast(0)))
        }
        val right = (width - targetW).coerceAtLeast(0)
        val left = (right - targetW).coerceAtLeast(0)
        return Box(left, 0, right, minOf(side, handleH.coerceAtLeast(0)))
    }

    /** 缩放时右下角保持不动：新尺寸 → 左上角。 Resizing keeps the bottom-right corner: new size → top-left. */
    fun anchorBottomRight(right: Int, bottom: Int, cardW: Int, cardH: Int) = (right - cardW) to (bottom - cardH)

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
