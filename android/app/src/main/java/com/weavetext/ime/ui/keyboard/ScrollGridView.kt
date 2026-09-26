package com.weavetext.ime.ui.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.widget.OverScroller
import kotlin.math.abs

/**
 * 纵向滚动的自绘面板基类：拖动/惯性、点击、长按命中（内容坐标）。
 * Base for vertically scrolling custom-drawn panels: drag/fling, tap and long-press hit testing.
 */
abstract class ScrollGridView(ctx: Context) : View(ctx) {
    protected var scroll = 0f
    private val scroller = OverScroller(ctx)
    private var velocity: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var downScroll = 0f
    private var dragging = false
    private var longFired = false
    /** 当前按下的项（-1 无）。 Pressed item. */
    protected var pressed = -1
    protected var density = ctx.resources.displayMetrics.density
    var onPressFeedback: (() -> Unit)? = null
    var onLongFeedback: (() -> Unit)? = null

    private val longPress = Runnable {
        if (pressed >= 0) {
            longFired = true
            val idx = pressed
            if (onItemLong(idx)) onLongFeedback?.invoke()
            pressed = -1
            invalidate()
        }
    }

    /** 内容总高。 Content height. */
    abstract fun contentHeight(): Float
    /** 内容坐标下的命中项。 Hit test in content coordinates. */
    abstract fun hit(x: Float, y: Float): Int
    abstract fun onItemTap(index: Int)
    open fun onItemLong(index: Int): Boolean = false
    abstract fun drawContent(c: Canvas)
    open fun onScrolledNearEnd() {}
    /** 翻页高度（> 0 时松手后停在整页，无障碍滚动也按页）；0 = 自由滚动。 Page height: > 0 snaps to whole pages; 0 scrolls freely. */
    open fun pageHeight(): Float = 0f
    /** 滚动位置变化（拖动、惯性、翻页、回顶）。 Scroll position changed. */
    open fun onScrollMoved() {}
    /** 长按成立后的手指移动 / 抬起（如肤色选择气泡）。 Finger move / up after a long-press fired. */
    open fun onLongMove(x: Float, y: Float) {}
    open fun onLongUp(cancel: Boolean) {}

    // 无障碍：子类给出项目数、内容坐标下的矩形与朗读文本即可；点击走 onItemTap。
    // Accessibility: subclasses expose count, content-space rect and label; clicks go to onItemTap.
    protected open fun a11yCount(): Int = 0
    protected open fun a11yRect(index: Int, out: android.graphics.RectF) {}
    protected open fun a11yLabel(index: Int): CharSequence? = null
    protected open fun a11yLongLabel(index: Int): CharSequence? = null
    /** 无障碍点击（默认同 onItemTap）。 Accessibility click; defaults to onItemTap. */
    protected open fun a11yTap(index: Int) = onItemTap(index)

    private val a11y = VirtualA11y(this, object : VirtualA11y.Source {
        override fun a11yIds() = IntArray(a11yCount()) { it }
        override fun a11yBounds(id: Int, out: android.graphics.RectF): Boolean {
            if (id !in 0 until a11yCount()) return false
            a11yRect(id, out)
            out.offset(0f, -scroll)
            if (!out.intersect(0f, 0f, width.toFloat(), height.toFloat())) return false
            return out.height() > 1f
        }
        override fun a11yLabel(id: Int) = this@ScrollGridView.a11yLabel(id)
        override fun a11yClick(id: Int): Boolean { a11yTap(id); invalidate(); a11yChanged(); return true }
        override fun a11yLongClickLabel(id: Int) = a11yLongLabel(id)
        override fun a11yLongClick(id: Int) = onItemLong(id).also { invalidate() }
        override fun a11yLiftToActivate(id: Int) = true
        override fun a11yCanScroll(forward: Boolean) = if (forward) scroll < maxScroll() - 1f else scroll > 0f
        override fun a11yScroll(forward: Boolean): Boolean {
            if (!a11yCanScroll(forward)) return false
            val page = pageHeight()
            scroll = if (page > 0f) ((Math.round(scroll / page) + if (forward) 1 else -1) * page).coerceIn(0f, maxScroll())
            else (scroll + (if (forward) 0.8f else -0.8f) * height).coerceIn(0f, maxScroll())
            onScrollMoved()
            invalidate()
            a11yChanged()
            return true
        }
    })

    /** 内容变化后通知无障碍服务。 Notify accessibility of content changes. */
    fun a11yChanged() { a11y.invalidate() }

    override fun getAccessibilityNodeProvider(): android.view.accessibility.AccessibilityNodeProvider = a11y
    override fun dispatchHoverEvent(event: MotionEvent): Boolean = a11y.onHover(event) || super.dispatchHoverEvent(event)

    fun maxScroll() = (contentHeight() - height).coerceAtLeast(0f)
    fun scrollToTop() { scroll = 0f; scroller.forceFinished(true); onScrollMoved(); invalidate() }

    /** 平滑滚到第 [page] 页（翻页模式）。 Smoothly scroll to [page] (paged mode). */
    fun scrollToPage(page: Int) {
        val ph = pageHeight()
        if (ph <= 0f) return
        val target = (page * ph).coerceIn(0f, maxScroll())
        scroller.forceFinished(true)
        scroller.startScroll(0, scroll.toInt(), 0, (target - scroll).toInt(), 220)
        postInvalidateOnAnimation()
    }

    /** 当前页（翻页模式）。 Current page in paged mode. */
    val page: Int get() = pageHeight().let { if (it > 0f) Math.round(scroll / it) else 0 }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.translate(0f, -scroll)
        drawContent(canvas)
        canvas.restore()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scroll = scroller.currY.toFloat()
            nearEnd()
            onScrollMoved()
            postInvalidateOnAnimation()
        }
    }

    private fun nearEnd() { if (scroll > maxScroll() - height * 0.5f) onScrolledNearEnd() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                downX = e.x; downY = e.y; downScroll = scroll
                dragging = false; longFired = false
                pressed = hit(e.x, e.y + scroll)
                if (pressed >= 0) {
                    onPressFeedback?.invoke()
                    postDelayed(longPress, 400)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (longFired) { onLongMove(e.x, e.y); return true }
                velocity?.addMovement(e)
                val dy = e.y - downY
                if (!dragging && (abs(dy) > 8 * density || abs(e.x - downX) > 8 * density)) {
                    removeCallbacks(longPress)
                    if (abs(dy) > 8 * density) dragging = true
                    pressed = -1
                    invalidate()
                }
                if (dragging) {
                    scroll = (downScroll - dy).coerceIn(0f, maxScroll())
                    nearEnd()
                    onScrollMoved()
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (longFired) { onLongUp(false); pressed = -1; invalidate(); return true }
                velocity?.addMovement(e)
                if (dragging) {
                    velocity?.computeCurrentVelocity(1000)
                    val vy = velocity?.yVelocity ?: 0f
                    val ph = pageHeight()
                    if (ph > 0f) {
                        // 翻页：快速一划或拖过 1/5 页就翻到相邻页，否则回弹。 Flick or drag past a fifth to turn one page.
                        val from = Math.round(downScroll / ph)
                        val moved = scroll - downScroll
                        val fast = 600 * density
                        scrollToPage(when {
                            vy < -fast || (vy <= fast && moved > ph * 0.2f) -> from + 1
                            vy > fast || moved < -ph * 0.2f -> from - 1
                            else -> from
                        })
                    } else {
                        scroller.fling(0, scroll.toInt(), 0, -vy.toInt(), 0, 0, 0, maxScroll().toInt())
                    }
                    postInvalidateOnAnimation()
                } else if (!longFired && pressed >= 0) {
                    val idx = pressed
                    pressed = -1
                    onItemTap(idx)
                }
                pressed = -1
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (longFired) onLongUp(true)
                pressed = -1
                invalidate()
            }
        }
        return true
    }
}
