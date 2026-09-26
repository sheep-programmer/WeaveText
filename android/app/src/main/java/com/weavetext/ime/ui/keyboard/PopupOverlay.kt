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
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val loc = IntArray(2)
    private val myLoc = IntArray(2)

    // 预览气泡 / preview bubble
    private val bubble = RectF()
    private var bubbleText = ""
    private var bubbleAccent = false
    private var bubbleAlpha = 0f
    private var bubbleShown = false
    private val fadeOut = Runnable { animateBubbleOut() }
    private var bubbleAnim: ValueAnimator? = null
    /** 与按键相连的气泡轮廓（显示时构建一次）。 Attached bubble outline, built once per show. */
    private val bubblePath = Path()
    private var bubbleAttached = false

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

    private val decel = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

    fun applyStyle(s: KeyboardStyle) {
        palette = s.palette
        metrics = s.metrics
        spec = s.layout.popup
        // API 28 起硬件加速支持 setShadowLayer。 HW shadow layers need API 28+.
        if (android.os.Build.VERSION.SDK_INT < 28) setLayerType(LAYER_TYPE_SOFTWARE, null)
        invalidate()
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

    // ------------------------------------------------------------ preview bubble

    fun showBubble(key: RectF, label: String) {
        if (spec.bubble == "none") return
        removeCallbacks(fadeOut)
        bubbleAnim?.cancel()
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
        bubbleAlpha = 1f
        bubbleShown = true
        invalidate()
    }

    fun updateBubble(label: String, accent: Boolean) {
        if (!bubbleShown) return
        bubbleText = label
        bubbleAccent = accent
        invalidate()
    }

    fun hideBubble(immediate: Boolean = false) {
        if (!bubbleShown) return
        if (immediate || animScale() == 0f) {
            bubbleShown = false
            invalidate()
        } else {
            postDelayed(fadeOut, 40)
        }
    }

    private fun animateBubbleOut() {
        bubbleAnim = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 70
            addUpdateListener { bubbleAlpha = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { if (bubbleAlpha <= 0.01f) bubbleShown = false; invalidate() }
            })
            start()
        }
    }

    // ------------------------------------------------------------ alternatives

    fun showAlternatives(key: RectF, items: List<String>, initial: Int) {
        hideBubble(true)
        val m = metrics
        val cw = key.width().coerceAtLeast(m.dp(40f))
        val ch = key.height().coerceAtLeast(m.dp(44f))
        val perRow = items.size.coerceAtMost(6)
        val rows = (items.size + perRow - 1) / perRow
        val pad = m.dp(4f)
        val boxW = perRow * cw + 2 * pad
        val boxH = rows * ch + 2 * pad
        val init = initial.coerceIn(0, items.size - 1)
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
        altSelected = init
        altPivotX = key.centerX()
        altPivotY = altBox.bottom
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
            val w = (this.text.measureText(text) + m.dp(20f)).coerceAtMost(width - m.dp(16f))
            preeditBox.set(m.dp(8f), m.bubbleSpace - m.dp(38f), m.dp(8f) + w, m.bubbleSpace - m.dp(6f))
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
        strip.set(m.dp(12f), m.bubbleSpace - m.dp(68f), width - m.dp(12f), m.bubbleSpace - m.dp(4f))
        stripText = msg; stripLevel = level; stripDanger = danger; stripLive = live
        stripShown = true
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
        fill.color = if (stripDanger) p.danger else p.popup
        fill.alpha = 255
        fill.setShadowLayer(m.dp(12f), 0f, m.dp(4f), p.popupShadow)
        canvas.drawRoundRect(strip, m.dp(16f), m.dp(16f), fill)
        fill.clearShadowLayer()
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
        val r = m.dp(spec.radius)
        val pre = preedit
        if (pre != null) {
            fill.color = p.popup
            fill.alpha = 255
            fill.setShadowLayer(m.dp(8f), 0f, m.dp(2f), p.popupShadow)
            canvas.drawRoundRect(preeditBox, m.dp(8f), m.dp(8f), fill)
            fill.clearShadowLayer()
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
        if (bubbleShown) {
            fill.color = p.popup
            fill.alpha = (255 * bubbleAlpha).toInt()
            fill.setShadowLayer(m.dp(12f), 0f, m.dp(4f), p.popupShadow)
            if (bubbleAttached) canvas.drawPath(bubblePath, fill) else canvas.drawRoundRect(bubble, r, r, fill)
            fill.clearShadowLayer()
            text.color = if (bubbleAccent) p.keyAccent else p.label
            text.alpha = (255 * bubbleAlpha).toInt()
            text.textSize = m.dp(spec.textSize)
            text.typeface = Typeface.DEFAULT
            canvas.drawText(bubbleText, bubble.centerX(), bubble.centerY() - (text.ascent() + text.descent()) / 2, text)
            text.alpha = 255
        }
        if (altItems.isNotEmpty()) {
            canvas.save()
            canvas.scale(altScale, altScale, altPivotX, altPivotY)
            fill.color = p.popup
            fill.alpha = (255 * altAlpha).toInt()
            fill.setShadowLayer(m.dp(12f), 0f, m.dp(4f), p.popupShadow)
            val ar = m.dp(spec.altRadius)
            canvas.drawRoundRect(altBox, ar, ar, fill)
            fill.clearShadowLayer()
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
            fill.color = if (infoDanger) p.danger else p.popup
            fill.alpha = 255
            fill.setShadowLayer(m.dp(12f), 0f, m.dp(4f), p.popupShadow)
            val rr = if (infoLines.size > 1) m.dp(12f) else info.height() / 2
            canvas.drawRoundRect(info, rr, rr, fill)
            fill.clearShadowLayer()
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
