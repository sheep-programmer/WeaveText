package com.weavetext.ime.ui.keyboard

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import android.view.animation.PathInterpolator
import com.weavetext.ime.style.KeyboardStyle
import com.weavetext.ime.style.PopupSpec

/**
 * 覆盖整个 IME 窗口（含键盘上方透明区）的气泡层，不接收触摸（04 §3）。
 * Bubble overlay over the whole IME window incl. the transparent area above; never touchable.
 */
@SuppressLint("ViewConstructor")
class PopupOverlay(ctx: Context) : View(ctx) {
    lateinit var palette: KbPalette
    lateinit var metrics: KbMetrics
    private var spec: PopupSpec = Layouts.DEFAULT.popup

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).zh().apply { textAlign = Paint.Align.CENTER }
    private val loc = IntArray(2)
    private val myLoc = IntArray(2)

    // 预览气泡 / preview bubble
    private val bubble = RectF()
    private var bubbleText = ""
    private var bubbleAccent = false
    private var bubbleShown = false
    private val fadeOut = Runnable { animateBubbleOut() }
    /** 与按键相连的气泡轮廓（显示时构建一次）。 Attached bubble outline, built once per show. */
    private val bubblePath = Path()
    private var bubbleAttached = false
    /** 气泡在自己的小视图里画（与本层同一坐标原点）。 [bubbleView] 左上角在本层坐标中的位置。 */
    private var bubbleOx = 0f
    private var bubbleOy = 0f

    /**
     * 预览气泡单独一个小视图（需加到与本层相同的父布局、左上对齐）：每键只重录这一小块，
     * 淡出只改视图透明度，不重画整个覆盖层。
     * The preview bubble lives in its own small view (add it to the same parent, top-left aligned): a key
     * press re-records only that view, and the fade only animates its alpha — the overlay is never redrawn.
     */
    val bubbleView: View = BubbleView(ctx)

    // 浮动组合串 / floating composing text
    private var preedit: String? = null
    private val preeditBox = RectF()

    // 长按候选 / alternatives
    private var altItems: List<String> = emptyList()
    private val altBox = RectF()
    private var altCells = Array(0) { RectF() }
    var altSelected = -1
        private set
    private var altScale = 1f
    private var altAlpha = 1f
    private var altPivotX = 0f
    private var altPivotY = 0f
    val altShown get() = altItems.isNotEmpty()

    // 提示气泡 / info bubble
    private val info = RectF()
    private var infoText = ""
    private var infoDanger = false
    private var infoMaxWidth = 0f
    private val infoLines = ArrayList<String>()

    // 浮动语音条 / floating voice strip
    private val strip = RectF()
    private var stripText = ""
    private var stripLevel = 0f
    private var stripDanger = false
    private var stripLive = false
    private var stripShown = false
    private val stripAutoHide = Runnable { hideStrip() }

    /** 悬浮键盘时卡片的上沿（左、上、右）；null = 常规底部键盘。 Floating card top edge; null when docked. */
    private var anchor: RectF? = null

    fun setAnchor(r: RectF?) { anchor = r }

    private fun anchorLeft() = anchor?.left ?: 0f
    private fun anchorRight() = anchor?.right ?: width.toFloat()
    private fun anchorTop() = anchor?.top ?: metrics.bubbleSpace

    private val decel = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

    /** 画着键盘背景的视图：半透明气泡在它的背景上取样。 The view carrying the keyboard backdrop, sampled by translucent popups. */
    var surface: View? = null
    private var under: BackdropDrawable? = null
    private val underBounds = android.graphics.Rect()
    private val surfLoc = IntArray(2)

    fun applyStyle(s: KeyboardStyle) {
        palette = s.palette
        metrics = s.metrics
        spec = s.layout.popup
        under = s.palette.backdrop?.let { BackdropDrawable(it) }
        underBounds.setEmpty()
        // API 28 起硬件加速支持 setShadowLayer。 HW shadow layers need API 28+.
        if (android.os.Build.VERSION.SDK_INT < 28) {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            bubbleView.setLayerType(LAYER_TYPE_SOFTWARE, null)
        }
        invalidate()
        bubbleView.invalidate()
    }

    /** 把 [src] 内的矩形映射到本层坐标。 Map a rect from [src] into overlay coordinates. */
    fun map(src: View, r: RectF, out: RectF) {
        src.getLocationInWindow(loc)
        getLocationInWindow(myLoc)
        out.set(r)
        out.offset((loc[0] - myLoc[0]).toFloat(), (loc[1] - myLoc[1]).toFloat())
    }

    fun mapX(src: View, x: Float): Float { src.getLocationInWindow(loc); getLocationInWindow(myLoc); return x + loc[0] - myLoc[0] }
    fun mapY(src: View, y: Float): Float { src.getLocationInWindow(loc); getLocationInWindow(myLoc); return y + loc[1] - myLoc[1] }

    // ------------------------------------------------------------ popup surface

    /** 让垫底背景与键盘背景对齐（本层坐标）。 Align the under-layer with the keyboard backdrop, in overlay coordinates. */
    private fun syncUnder() {
        val u = under ?: return
        val s = surface ?: return
        s.getLocationInWindow(surfLoc)
        getLocationInWindow(myLoc)
        val l = surfLoc[0] - myLoc[0]
        val t = surfLoc[1] - myLoc[1]
        if (underBounds.left != l || underBounds.top != t || underBounds.width() != s.width || underBounds.height() != s.height) {
            underBounds.set(l, t, l + s.width, t + s.height)
            u.bounds = underBounds
        }
    }

    /**
     * 气泡底板。主题的气泡色半透明（如「玻璃」）时，先垫一层键盘背景（渐变 / 图片按原位取样）再叠气泡色：
     * 看上去与按键一样是背景上的半透明面板，但不会透出下面的按键文字，对比度只取决于主题（05 §4.2 断言）。
     * Popup plate. A translucent popup colour (e.g. glass) is laid over the keyboard backdrop sampled in place, so it reads
     * as a translucent pane like the keys without showing the key labels below; contrast depends on the theme only.
     */
    private fun plate(c: Canvas, r: RectF, radius: Float, path: Path?, color: Int, alpha: Int, shadow: Float, dy: Float) {
        val a = color ushr 24
        if (a == 255) {
            fill.color = color
            fill.alpha = alpha
            fill.setShadowLayer(shadow, 0f, dy, palette.popupShadow)
            if (path != null) c.drawPath(path, fill) else c.drawRoundRect(r, radius, radius, fill)
            fill.clearShadowLayer()
            return
        }
        fill.color = palette.background
        fill.alpha = alpha
        fill.setShadowLayer(shadow, 0f, dy, palette.popupShadow)
        if (path != null) c.drawPath(path, fill) else c.drawRoundRect(r, radius, radius, fill)
        fill.clearShadowLayer()
        under?.let { if (path != null) it.drawPath(c, path, alpha) else it.drawRoundRect(c, r, radius, alpha) }
        fill.color = color
        fill.alpha = a * alpha / 255
        if (path != null) c.drawPath(path, fill) else c.drawRoundRect(r, radius, radius, fill)
        // 与按键同样的细描边勾出玻璃边缘。 The keys' hairline outline marks the glass edge.
        if (palette.strokeWidth > 0f && palette.stroke ushr 24 != 0) {
            stroke.color = palette.stroke
            stroke.alpha = (palette.stroke ushr 24) * alpha / 255
            stroke.strokeWidth = metrics.dp(palette.strokeWidth)
            if (path != null) c.drawPath(path, stroke) else c.drawRoundRect(r, radius, radius, stroke)
        }
    }

    // ------------------------------------------------------------ preview bubble

    fun showBubble(key: RectF, label: String) {
        if (spec.bubble == "none") return
        removeCallbacks(fadeOut)
        val bv = bubbleView
        bv.animate().cancel()
        val m = metrics
        bubbleAttached = spec.bubble == "attached"
        if (bubbleAttached) {
            // 气泡头部在键上方，经过渡段与按键本身连成一体。 Head above the key, joined to the key itself.
            val w = (key.width() * 1.45f).coerceAtLeast(m.dp(44f))
            val h = key.height() * 1.05f
            var left = key.centerX() - w / 2
            left = left.coerceIn(m.dp(2f), width - m.dp(2f) - w)
            val headBottom = key.top - key.height() * 0.2f
            bubble.set(left, headBottom - h, left + w, headBottom)
            val r = m.dp(spec.radius)
            bubblePath.reset()
            bubblePath.addRoundRect(bubble, r, r, Path.Direction.CW)
            bubblePath.addRoundRect(key.left, headBottom - r, key.right, key.bottom, r * 0.6f, r * 0.6f, Path.Direction.CW)
            bubblePath.moveTo(bubble.left, headBottom - r)
            bubblePath.lineTo(bubble.right, headBottom - r)
            bubblePath.lineTo(key.right, key.top + r * 0.6f)
            bubblePath.lineTo(key.left, key.top + r * 0.6f)
            bubblePath.close()
        } else {
            val w = (key.width() + m.dp(16f)).coerceAtLeast(m.dp(48f))
            val h = key.height() + m.dp(12f)
            var left = key.centerX() - w / 2
            left = left.coerceIn(m.dp(4f), width - m.dp(4f) - w)
            val bottom = key.top - m.dp(6f)
            bubble.set(left, bottom - h, left + w, bottom)
        }
        bubbleText = label
        bubbleAccent = false
        bubbleShown = true
        syncUnder()
        // 视图只需覆盖气泡（与相连的按键）加阴影；尺寸只增不减，平时不触发布局。
        // The view covers the bubble (and the joined key) plus the shadow; it only ever grows, so no layout per key.
        val pad = m.dp(16f)
        val bottom = if (bubbleAttached) key.bottom else bubble.bottom
        bubbleOx = bubble.left.coerceAtMost(key.left) - pad
        bubbleOy = bubble.top - pad
        val needW = (maxOf(bubble.right, key.right) - bubbleOx + pad).toInt() + 1
        val needH = (bottom - bubbleOy + pad).toInt() + 1
        val lp = bv.layoutParams
        if (lp != null && (lp.width < needW || lp.height < needH)) {
            lp.width = maxOf(lp.width, needW)
            lp.height = maxOf(lp.height, needH)
            bv.layoutParams = lp
        }
        bv.translationX = bubbleOx
        bv.translationY = bubbleOy
        bv.alpha = 1f
        bv.visibility = VISIBLE
        bv.invalidate()
    }

    fun updateBubble(label: String, accent: Boolean) {
        if (!bubbleShown) return
        bubbleText = label
        bubbleAccent = accent
        bubbleView.invalidate()
    }

    fun hideBubble(immediate: Boolean = false) {
        if (!bubbleShown) return
        if (immediate || animScale() == 0f) {
            removeCallbacks(fadeOut)
            bubbleView.animate().cancel()
            bubbleShown = false
            bubbleView.visibility = INVISIBLE
        } else {
            postDelayed(fadeOut, 40)
        }
    }

    /** 已显示气泡（测试用）。 Whether the preview bubble shows (for tests). */
    val bubbleVisible get() = bubbleShown

    private fun animateBubbleOut() {
        bubbleView.animate().alpha(0f).setDuration(70).withEndAction {
            bubbleShown = false
            bubbleView.visibility = INVISIBLE
        }.start()
    }

    private inner class BubbleView(c: Context) : View(c) {
        init { visibility = INVISIBLE }

        // 淡出时不必离屏合成（填充与文字不重叠）。 No offscreen pass needed while fading.
        override fun hasOverlappingRendering() = false

        override fun onDraw(canvas: Canvas) {
            if (!bubbleShown || !::palette.isInitialized) return
            val p = palette
            val m = metrics
            val r = m.dp(spec.radius)
            canvas.translate(-bubbleOx, -bubbleOy)
            plate(canvas, bubble, r, if (bubbleAttached) bubblePath else null, p.popup, 255, m.dp(12f), m.dp(4f))
            text.color = if (bubbleAccent) p.keyAccent else p.label
            text.textSize = m.dp(spec.textSize)
            text.typeface = Typeface.DEFAULT
            canvas.drawText(bubbleText, bubble.centerX(), bubble.centerY() - (text.ascent() + text.descent()) / 2, text)
        }
    }

    // ------------------------------------------------------------ alternatives

    /**
     * 长按候选浮层。[anchor] 项对齐按键；[selected] 为初始高亮（-1 = 不预选，需移动手指才选中）。
     * Long-press alternatives; item [anchor] sits over the key, [selected] is highlighted initially (-1 = none).
     */
    fun showAlternatives(key: RectF, items: List<String>, anchor: Int, selected: Int = anchor) {
        hideBubble(true)
        val m = metrics
        val cw = key.width().coerceAtLeast(m.dp(40f))
        val ch = key.height().coerceAtLeast(m.dp(44f))
        val perRow = items.size.coerceAtMost(6)
        val rows = (items.size + perRow - 1) / perRow
        val pad = m.dp(4f)
        val boxW = perRow * cw + 2 * pad
        val boxH = rows * ch + 2 * pad
        val init = anchor.coerceIn(0, items.size - 1)
        var left = key.centerX() - pad - (init % perRow) * cw - cw / 2
        left = left.coerceIn(m.dp(4f), width - m.dp(4f) - boxW)
        val bottom = key.top - m.dp(6f)
        altBox.set(left, bottom - boxH, left + boxW, bottom)
        altCells = Array(items.size) { i ->
            val r = rows - 1 - i / perRow // 首行在最下方，向上生长 / first row at bottom, grows upward
            val c = i % perRow
            RectF(left + pad + c * cw, altBox.top + pad + r * ch, left + pad + (c + 1) * cw, altBox.top + pad + (r + 1) * ch)
        }
        altItems = items
        altSelected = if (selected in items.indices) selected else -1
        altPivotX = key.centerX()
        altPivotY = altBox.bottom
        syncUnder()
        if (animScale() == 0f) {
            altScale = 1f; altAlpha = 1f
        } else {
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 120
                interpolator = decel
                addUpdateListener { val t = it.animatedValue as Float; altScale = 0.92f + 0.08f * t; altAlpha = t; invalidate() }
                start()
            }
        }
        invalidate()
    }

    /** 根据手指位置（本层坐标）更新选中；返回是否变化。 Update selection from a finger position. */
    fun moveAlternatives(x: Float, y: Float): Boolean {
        if (altItems.isEmpty()) return false
        val slack = metrics.dp(24f)
        var sel = -1
        if (x >= altBox.left - slack && x <= altBox.right + slack && y >= altBox.top - slack * 3 && y <= altBox.bottom + metrics.rowPitch * 1.5f) {
            // 纵向只在多行时区分；横向按最近格。 Pick nearest cell.
            var best = Float.MAX_VALUE
            for (i in altCells.indices) {
                val c = altCells[i]
                val dx = if (x < c.left) c.left - x else if (x > c.right) x - c.right else 0f
                val dy = if (y < c.top) c.top - y else if (y > c.bottom) (y - c.bottom) * 0.25f else 0f
                val d = dx + dy
                if (d < best) { best = d; sel = i }
            }
        }
        if (sel != altSelected) {
            altSelected = sel
            invalidate()
            return sel >= 0
        }
        return false
    }

    fun selectedAlternative(): String? = altItems.getOrNull(altSelected)

    fun hideAlternatives() {
        altItems = emptyList()
        altSelected = -1
        invalidate()
    }

    // ------------------------------------------------------------ info bubble

    fun showInfo(anchor: RectF, msg: String, danger: Boolean = false) {
        val m = metrics
        text.textSize = m.dp(13f)
        infoMaxWidth = width - m.dp(24f)
        infoLines.clear()
        // 按宽度折行。 Wrap by width.
        var start = 0
        while (start < msg.length) {
            val n = text.breakText(msg, start, msg.length, true, infoMaxWidth - m.dp(24f), null).coerceAtLeast(1)
            infoLines += msg.substring(start, start + n)
            start += n
        }
        var tw = 0f
        for (l in infoLines) tw = maxOf(tw, text.measureText(l))
        val w = tw + m.dp(24f)
        val lineH = m.dp(18f)
        val h = (m.dp(32f)).coerceAtLeast(infoLines.size * lineH + m.dp(14f))
        var left = anchor.centerX() - w / 2
        left = left.coerceIn(m.dp(8f), width - m.dp(8f) - w)
        val bottom = anchor.top - m.dp(8f)
        info.set(left, bottom - h, left + w, bottom)
        infoText = msg
        infoDanger = danger
        syncUnder()
        invalidate()
    }

    fun hideInfo() {
        if (infoText.isEmpty()) return
        infoText = ""
        invalidate()
    }

    /** 浮动组合串（键盘上方左侧的小条）；null 隐藏。 Floating composing text above the keyboard; null hides. */
    fun showPreedit(text: String?) {
        if (text == preedit) return
        preedit = text
        if (text != null) {
            val m = metrics
            this.text.textSize = m.dp(15f)
            val w = (this.text.measureText(text) + m.dp(20f)).coerceAtMost(anchorRight() - anchorLeft() - m.dp(16f))
            val top = (anchorTop() - m.dp(38f)).coerceAtLeast(0f)
            preeditBox.set(anchorLeft() + m.dp(8f), top, anchorLeft() + m.dp(8f) + w, top + m.dp(32f))
            syncUnder()
        }
        invalidate()
    }

    fun hideAll() {
        hideBubble(true)
        hideAlternatives()
        hideInfo()
        hideStrip()
    }

    // ------------------------------------------------------------ voice strip (02 §12.2)

    fun showStrip(msg: String, level: Float, danger: Boolean, live: Boolean) {
        removeCallbacks(stripAutoHide)
        val m = metrics
        val top = (anchorTop() - m.dp(68f)).coerceAtLeast(0f)
        strip.set(anchorLeft() + m.dp(12f), top, anchorRight() - m.dp(12f), top + m.dp(64f))
        stripText = msg; stripLevel = level; stripDanger = danger; stripLive = live
        stripShown = true
        syncUnder()
        invalidate()
    }

    /** 短暂提示（无权限 / 无引擎）。 Short message that hides itself. */
    fun showStripMessage(msg: String) {
        showStrip(msg, 0f, danger = false, live = false)
        postDelayed(stripAutoHide, 1800)
    }

    fun hideStrip() {
        if (!stripShown) return
        removeCallbacks(stripAutoHide)
        stripShown = false
        invalidate()
    }

    private fun drawStrip(canvas: Canvas) {
        val p = palette
        val m = metrics
        plate(canvas, strip, m.dp(16f), null, if (stripDanger) p.danger else p.popup, 255, m.dp(12f), m.dp(4f))
        // 左侧 9 根波形条 / nine waveform bars on the left
        val bw = m.dp(3f); val gap = m.dp(3f)
        var x = strip.left + m.dp(16f)
        val cy = strip.centerY()
        val t = android.os.SystemClock.uptimeMillis()
        fill.color = if (stripDanger) p.onAccent else p.voiceWave
        for (i in 0 until 9) {
            val win = kotlin.math.sin(Math.PI * i / 8).toFloat()
            val jitter = 0.55f + 0.45f * kotlin.math.sin(t * 0.011 + i * 1.7).toFloat()
            val h = if (stripLive) (m.dp(4f) + m.dp(24f) * (stripLevel * 1.6f).coerceAtMost(1f) * win * jitter) else m.dp(4f)
            canvas.drawRoundRect(x, cy - h / 2, x + bw, cy + h / 2, bw / 2, bw / 2, fill)
            x += bw + gap
        }
        text.color = if (stripDanger) p.onAccent else p.label
        text.textSize = m.dp(15f)
        text.typeface = Typeface.DEFAULT
        text.textAlign = Paint.Align.LEFT
        val left = x + m.dp(10f)
        val avail = strip.right - m.dp(16f) - left
        // 显示末尾部分。 Show the tail when too long.
        var start = 0
        while (start < stripText.length && text.measureText(stripText, start, stripText.length) > avail) start++
        canvas.drawText(stripText, start, stripText.length, left, cy - (text.ascent() + text.descent()) / 2, text)
        text.textAlign = Paint.Align.CENTER
        if (stripLive) postInvalidateOnAnimation()
    }

    private fun animScale(): Float = android.provider.Settings.Global.getFloat(
        context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
    )

    override fun onDraw(canvas: Canvas) {
        if (!::palette.isInitialized) return
        val p = palette
        val m = metrics
        val pre = preedit
        if (pre != null) {
            plate(canvas, preeditBox, m.dp(8f), null, p.popup, 255, m.dp(8f), m.dp(2f))
            text.color = p.label
            text.textSize = m.dp(15f)
            text.typeface = Typeface.DEFAULT
            text.textAlign = Paint.Align.LEFT
            canvas.save()
            canvas.clipRect(preeditBox)
            val tw = text.measureText(pre)
            // 过长时显示末尾。 Show the tail when too long.
            val x = if (tw > preeditBox.width() - m.dp(20f)) preeditBox.right - m.dp(10f) - tw else preeditBox.left + m.dp(10f)
            canvas.drawText(pre, x, preeditBox.centerY() - (text.ascent() + text.descent()) / 2, text)
            canvas.restore()
            text.textAlign = Paint.Align.CENTER
        }
        if (altItems.isNotEmpty()) {
            canvas.save()
            canvas.scale(altScale, altScale, altPivotX, altPivotY)
            plate(canvas, altBox, m.dp(spec.altRadius), null, p.popup, (255 * altAlpha).toInt(), m.dp(12f), m.dp(4f))
            text.textSize = m.dp(20f)
            text.typeface = Typeface.DEFAULT
            for (i in altItems.indices) {
                val c = altCells[i]
                if (i == altSelected) {
                    fill.color = p.popupSelected
                    fill.alpha = (255 * altAlpha).toInt()
                    val ins = m.dp(3f)
                    canvas.drawRoundRect(c.left + ins, c.top + ins, c.right - ins, c.bottom - ins, m.dp(8f), m.dp(8f), fill)
                    text.color = p.onAccent
                } else {
                    text.color = p.label
                }
                text.alpha = (255 * altAlpha).toInt()
                val s = altItems[i]
                val size = if (s.length > 2) m.dp(20f) * 2.2f / s.length.coerceAtLeast(3) else m.dp(20f)
                text.textSize = size
                canvas.drawText(s, c.centerX(), c.centerY() - (text.ascent() + text.descent()) / 2, text)
            }
            text.alpha = 255
            canvas.restore()
        }
        if (stripShown) drawStrip(canvas)
        if (infoText.isNotEmpty()) {
            val rr = if (infoLines.size > 1) m.dp(12f) else info.height() / 2
            plate(canvas, info, rr, null, if (infoDanger) p.danger else p.popup, 255, m.dp(12f), m.dp(4f))
            text.color = if (infoDanger) p.onAccent else p.label
            text.textSize = m.dp(13f)
            val lineH = m.dp(18f)
            val top = info.centerY() - infoLines.size * lineH / 2
            for (i in infoLines.indices) {
                canvas.drawText(infoLines[i], info.centerX(), top + lineH * i + lineH / 2 - (text.ascent() + text.descent()) / 2, text)
            }
        }
    }
}
