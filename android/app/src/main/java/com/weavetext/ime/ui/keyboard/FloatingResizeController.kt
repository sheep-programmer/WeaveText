package com.weavetext.ime.ui.keyboard

import com.weavetext.ime.ui.keyboard.FloatingGeometry.Box
import com.weavetext.ime.ui.keyboard.FloatingGeometry.ResizeResult

/**
 * View-free, single-pointer resize session. DOWN -> begin, MOVE -> update with MotionEvent rawX/Y,
 * UP -> finish and persist that result once, CANCEL -> cancel and restore the returned start box.
 * The caller owns the pointer id, touch slop and layout. Pass CURRENT usable bounds to each update;
 * a rotation/inset change invalidates the session instead of applying an obsolete fixed anchor.
 * Apply result.box dimensions/translation and result.scale together. While the session is active,
 * root.onLayout must anchor the measured size from startBox, rather than place from saved fractions.
 * Default strict mode fixes the opposite corner. begin(allowSlideAtEdge = true) allows minimal
 * translation into bounds at an edge; update and anchorMeasured both retain that gesture option.
 * For WeaveKeyboard, fixedHeight = card.height - metrics.mainHeight.roundToInt() (bars, candidates,
 * padding). After KbMetrics rounds its row pitch, use anchorMeasured in onLayout and apply the
 * returned dimensions as well as translation. No preferences are written here.
 */
class FloatingResizeController {
    private data class Gesture(
        val start: Box, val startScale: Float, val corner: FloatingResizeCorner, val bounds: Box,
        val density: Float, val downX: Float, val downY: Float, val fixedHeight: Int, val allowSlideAtEdge: Boolean,
    )

    private var gesture: Gesture? = null
    var current: ResizeResult? = null
        private set
    val active get() = gesture != null
    val startBox: Box? get() = gesture?.start

    /** False for a disabled corner, an unmeasured card or a card not yet fitted to usable bounds. */
    fun begin(
        corner: FloatingResizeCorner, card: Box, scale: Float, bounds: Box, density: Float,
        rawX: Float, rawY: Float, policy: FloatingResizePolicy = FloatingResizePolicy(), fixedHeight: Int = 0,
        allowSlideAtEdge: Boolean = false,
    ): Boolean {
        gesture = null
        current = null
        if (!policy.allows(corner) || card.width <= 0 || card.height <= 0 || !FloatingGeometry.fits(card, bounds) ||
            !scale.isFinite() || scale <= 0f || !density.isFinite() || density <= 0f ||
            !rawX.isFinite() || !rawY.isFinite() || fixedHeight !in 0 until card.height) return false
        gesture = Gesture(card, scale, corner, bounds, density, rawX, rawY, fixedHeight, allowSlideAtEdge)
        current = ResizeResult(card, scale, corner)
        return true
    }

    /** Returns null and ends the gesture if bounds changed. Nonfinite pointer samples are ignored. */
    fun update(rawX: Float, rawY: Float, bounds: Box): ResizeResult? {
        val g = gesture ?: return null
        if (bounds != g.bounds) {
            gesture = null
            current = null
            return null
        }
        if (!rawX.isFinite() || !rawY.isFinite()) return current
        return FloatingGeometry.resizeFromCorner(
            g.start, g.startScale, g.corner, rawX - g.downX, rawY - g.downY,
            g.bounds, g.density, g.fixedHeight, g.allowSlideAtEdge,
        ).also { current = it }
    }

    /** Reapply the DOWN anchor and the gesture's edge-slide option after actual metrics are measured. */
    fun anchorMeasured(cardW: Int, cardH: Int, bounds: Box): ResizeResult? {
        val g = gesture ?: return null
        if (bounds != g.bounds) {
            gesture = null
            current = null
            return null
        }
        if (cardW <= 0 || cardH <= 0) return current
        return current?.copy(box = FloatingGeometry.anchorOpposite(g.start, g.corner, cardW, cardH, bounds, g.allowSlideAtEdge))
            ?.also { current = it }
    }

    fun finish(): ResizeResult? = current.also { gesture = null; current = null }

    /** A canceled gesture should restore its start state and never persist a partially dragged size. */
    fun cancel(): ResizeResult? {
        val g = gesture
        gesture = null
        current = null
        return g?.let { ResizeResult(it.start, it.startScale, it.corner) }
    }
}
