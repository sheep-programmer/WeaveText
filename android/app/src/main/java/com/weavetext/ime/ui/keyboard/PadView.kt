package com.weavetext.ime.ui.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.weavetext.ime.R

/** 面板里的按键。 A key inside a panel. */
class PadKey(val id: Int, var label: String = "", var icon: Int = 0, var style: Int = KeyStyle.FUNC) {
    val cell = RectF()
    val rect = RectF()
    var active = false
    var disabled = false
    /** 长按连发。 Auto-repeat while held. */
    var repeat = false
    var textSize = 0f
    var large = true
    var iconSize = 0f
    /** 图标与文字同时显示（图标在上）。 Icon above label. */
    var stacked = false
    /** 朗读文本（缺省用 label 或图标名）。 Spoken description; defaults to label or icon name. */
    var description: String? = null
}

/**
 * 面板用的简易按键网格（光标面板、候选网格侧栏、符号面板底行、语音侧键）。
 * Simple key pad for panels, drawn exactly like keyboard keys.
 */
@SuppressLint("ViewConstructor")
class PadView(ctx: Context, private val kb: WeaveKeyboard) : View(ctx) {
    var keys: List<PadKey> = emptyList()
        set(v) { field = v; relayout() }
    private val a11y = VirtualA11y(this, object : VirtualA11y.Source {
        override fun a11yIds() = IntArray(keys.size) { it }
        override fun a11yBounds(id: Int, out: RectF): Boolean {
            val k = keys.getOrNull(id) ?: return false
            out.set(k.cell)
            return !out.isEmpty
        }
        override fun a11yLabel(id: Int) = keys.getOrNull(id)?.let { describe(it) }
        override fun a11yEnabled(id: Int) = keys.getOrNull(id)?.disabled != true
        override fun a11yState(id: Int) = keys.getOrNull(id)?.let { if (it.active) "已开启" else null }
        override fun a11yClick(id: Int): Boolean {
            val k = keys.getOrNull(id)?.takeIf { !it.disabled } ?: return false
            onTap?.invoke(k)
            return true
        }
        override fun a11yLongClickLabel(id: Int): CharSequence? = if (onLong != null && keys.getOrNull(id)?.repeat == false) "更多" else null
        override fun a11yLongClick(id: Int): Boolean = keys.getOrNull(id)?.let { onLong?.invoke(it) } == true
    })

    override fun getAccessibilityNodeProvider(): android.view.accessibility.AccessibilityNodeProvider = a11y

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = a11y.onHover(event) || super.dispatchHoverEvent(event)

    private fun describe(k: PadKey): String = k.description ?: when (k.icon) {
        R.drawable.ic_backspace -> "删除"
        R.drawable.ic_tab -> "制表符"
        R.drawable.ic_chevron_up -> "上移"
        R.drawable.ic_chevron_down -> "下移"
        R.drawable.ic_chevron_left -> "左移"
        R.drawable.ic_chevron_right -> "右移"
        else -> if (k.label == "Del") "向后删除" else k.label
    }
    var layouter: ((w: Float, h: Float) -> Unit)? = null
        set(v) { field = v; relayout() }
    var onTap: ((PadKey) -> Unit)? = null
    var onLong: ((PadKey) -> Boolean)? = null
    /** 无背景阴影的扁平模式（顶栏按钮用）。 */
    var drawShadow = true

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).zh().apply { textAlign = Paint.Align.CENTER }
    private val tmp = RectF()
    private val tmp2 = RectF()
    private var down: PadKey? = null
    private var repeating = false
    private val mediumTf = if (android.os.Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 500, false) else Typeface.DEFAULT_BOLD

    private val repeatTask = object : Runnable {
        override fun run() {
            val k = down ?: return
            repeating = true
            onTap?.invoke(k)
            postDelayed(this, 50)
        }
    }
    private val longTask = Runnable {
        val k = down ?: return@Runnable
        if (onLong?.invoke(k) == true) { down = null; kb.feedback.haptic(this); invalidate() }
    }

    fun relayout() {
        if (width > 0) layouter?.invoke(width.toFloat(), height.toFloat())
        invalidate()
        a11y.invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = relayout()

    override fun onDraw(c: Canvas) {
        val p = kb.palette
        val m = kb.metrics
        for (k in keys) {
            val pressed = k === down
            val r = if (k.large) m.keyRadiusLarge else m.keyRadius
            val bg = when {
                k.active -> p.accentSoft
                k.style == KeyStyle.ACCENT -> if (pressed) p.keyAccentPressed else p.keyAccent
                k.style == KeyStyle.FUNC -> if (pressed) p.keyFuncPressed else p.keyFunc
                else -> if (pressed) p.keyPressed else p.key
            }
            tmp.set(k.rect)
            if (k.disabled) {
                // 禁用：无填充描边，形状上也与可按的键区分。 Disabled: outline only, no fill.
                fill.style = Paint.Style.STROKE
                fill.strokeWidth = m.dp(1f)
                fill.color = p.labelDisabled
                tmp.inset(m.dp(0.5f), m.dp(0.5f))
                c.drawRoundRect(tmp, r, r, fill)
                fill.style = Paint.Style.FILL
            } else if (drawShadow) {
                KeyPainter.draw(c, k.rect, tmp, tmp2, fill, bg, pressed, r, p, m)
            } else {
                if (pressed) tmp.offset(0f, m.dp(0.5f))
                fill.color = bg
                c.drawRoundRect(tmp, r, r, fill)
            }
            val color = when {
                k.disabled -> p.labelDisabled
                k.style == KeyStyle.ACCENT -> p.onAccent
                k.active -> p.keyAccent
                else -> p.label
            }
            val iconColor = when {
                k.disabled -> p.labelDisabled
                k.style == KeyStyle.ACCENT -> p.onAccent
                k.active -> p.keyAccent
                else -> p.icon
            }
            val size = if (k.iconSize > 0) k.iconSize else m.icon(22f)
            if (k.icon != 0 && k.label.isNotEmpty() && k.stacked) {
                kb.icons.draw(c, k.icon, iconColor, tmp.centerX(), tmp.centerY() - m.dp(9f), size)
                text.textSize = m.dp(12f)
                text.color = color
                text.typeface = Typeface.DEFAULT
                c.drawText(k.label, tmp.centerX(), tmp.centerY() + m.dp(17f), text)
            } else if (k.icon != 0) {
                kb.icons.draw(c, k.icon, iconColor, tmp.centerX(), tmp.centerY(), size)
            } else {
                text.textSize = minOf(if (k.textSize > 0) k.textSize else m.label(16f), tmp.height() * 0.62f)
                text.color = color
                text.typeface = mediumTf
                // 大字号时缩到键宽内。 Fit the key width at large font scales.
                val w = text.measureText(k.label)
                val maxW = tmp.width() - m.dp(8f)
                if (w > maxW && w > 0) text.textSize *= maxW / w
                c.drawText(k.label, tmp.centerX(), tmp.centerY() - (text.ascent() + text.descent()) / 2, text)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val k = keys.firstOrNull { it.cell.contains(e.x, e.y) } ?: return true
                if (k.disabled) return true
                down = k
                repeating = false
                kb.feedback.key(this)
                if (k.repeat) postDelayed(repeatTask, 400) else if (onLong != null) postDelayed(longTask, 400)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val k = down ?: return true
                if (!k.cell.contains(e.x, e.y)) {
                    val slack = kb.metrics.dp(24f)
                    if (e.x < k.cell.left - slack || e.x > k.cell.right + slack || e.y < k.cell.top - slack || e.y > k.cell.bottom + slack) {
                        removeCallbacks(repeatTask); removeCallbacks(longTask)
                        down = null
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(repeatTask); removeCallbacks(longTask)
                val k = down
                down = null
                if (k != null && !repeating) onTap?.invoke(k)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(repeatTask); removeCallbacks(longTask)
                down = null
                invalidate()
            }
        }
        return true
    }

    companion object {
        /** 按列权重 + 行数铺满，[spec] 为 (id, col, row, colSpan, rowSpan)。 Grid helper. */
        fun grid(keys: List<PadKey>, spec: List<IntArray>, weights: FloatArray, rows: Int, w: Float, h: Float, m: KbMetrics, padH: Float = m.padH) {
            val total = weights.sum()
            val xs = FloatArray(weights.size + 1)
            xs[0] = padH
            for (i in weights.indices) xs[i + 1] = xs[i] + (w - 2 * padH) * weights[i] / total
            val rowH = h / rows
            for (s in spec) {
                val k = keys.firstOrNull { it.id == s[0] } ?: continue
                k.cell.set(xs[s[1]], s[2] * rowH, xs[s[1] + s[3]], (s[2] + s[4]) * rowH)
                k.rect.set(k.cell)
                k.rect.inset(m.insetH, m.insetV)
                if (k.cell.left <= padH + 1f) k.cell.left = 0f
                if (k.cell.right >= w - padH - 1f) k.cell.right = w
            }
        }
    }
}
