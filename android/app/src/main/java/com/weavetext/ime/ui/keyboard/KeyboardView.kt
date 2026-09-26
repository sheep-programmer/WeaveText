package com.weavetext.ime.ui.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.weavetext.ime.R
import com.weavetext.ime.style.KeyboardStyle
import com.weavetext.ime.style.LayoutStyle
import kotlin.math.abs
import kotlin.math.max

/** 键区回调（由键盘根视图实现）。 Callbacks from the key area. */
interface KeyboardHost {
    val overlay: PopupOverlay?
    val feedback: Feedback?
    val previewEnabled: Boolean
    fun onKey(key: Key)
    fun onKeyText(key: Key, text: String)
    fun onCursorSteps(steps: Int)
    fun onDeleteRepeat(count: Int)
    fun onDeleteClear()
    /** 功能键长按；返回 [KeyboardView.LONG_VOICE] / [KeyboardView.LONG_CONSUMED] / 0。 */
    fun onLongPressFunc(key: Key): Int
    fun onVoiceHoldMove(dy: Float) {}
    fun onVoiceHoldEnd(cancelled: Boolean) {}
    fun onSideItem(index: Int)
}

/**
 * 整块键区的自绘 View（04 §1）：不为每个键建子 View，onDraw 零分配。
 * The whole key area in one custom view (04 §1); no per-key views, no allocation in onDraw.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(ctx: Context, private val host: KeyboardHost?) : View(ctx) {

    lateinit var palette: KbPalette
    lateinit var metrics: KbMetrics
    lateinit var icons: Icons
    /** 当前布局风格。 Current layout style. */
    var layoutStyle: LayoutStyle = Layouts.DEFAULT
        private set
    /** 字母副标签位置（"top" / "topRight" / "none"）。 Letter hint position. */
    private var hintMode = "top"

    var keys: List<Key> = emptyList()
        private set
    var side: SideList? = null
        private set
    private var layoutKind = Layouts.QWERTY
    private var builder: ((Float) -> Unit)? = null

    // 渲染状态 / render state
    var enterLabel: String? = null
    var enterIcon = R.drawable.ic_enter
    var enterAccent = false
    var chinese = true
    var cornerMode = false
    var spaceHint: String? = null
    /** 回车键的朗读文本（随编辑框动作变化）。 Spoken Enter action. */
    var enterDescription = "换行"

    private val a11y = VirtualA11y(this, A11ySource())

    // 画笔 / paints
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val tmp = RectF()
    private val tmp2 = RectF()
    private val mediumTf = Typeface.create(Typeface.DEFAULT, Typeface.BOLD).let {
        if (android.os.Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 500, false) else it
    }

    // 触摸状态 / touch state
    private var pointerId = -1
    private var down: Key? = null
    private var downX = 0f
    private var downY = 0f
    private var mode = M_NONE
    private var cursorAnchor = 0f
    private var lastMoveT = 0L
    private var lastMoveX = 0f
    private var repeatCount = 0
    private var dangerKey: Key? = null
    private var sideDownScroll = 0f
    private var sideDragging = false

    private val longPress = Runnable { onLongPress() }
    private val repeat = object : Runnable {
        override fun run() {
            val k = down ?: return
            if (k.code != KeyCode.DELETE || mode != M_TAP) return
            repeatCount++
            if (repeatCount == 1 || repeatCount % 5 == 0) host?.feedback?.haptic(this@KeyboardView)
            host?.onDeleteRepeat(repeatCount)
            postDelayed(this, if (repeatCount > 20) 80 else 50)
        }
    }

    fun applyStyle(s: KeyboardStyle, i: Icons) {
        palette = s.palette
        metrics = s.metrics
        layoutStyle = s.layout
        hintMode = s.hint
        icons = i
        // API 28 前硬件加速不支持 setShadowLayer。 Soft shadows need a software layer before API 28.
        if (android.os.Build.VERSION.SDK_INT < 28) setLayerType(if (s.palette.shadow == KeyShadow.SOFT) LAYER_TYPE_SOFTWARE else LAYER_TYPE_NONE, null)
        relayout()
        invalidate()
    }

    fun setQwerty(list: List<Key>) {
        keys = list; side = null; layoutKind = Layouts.QWERTY
        builder = { w -> Layouts.layoutQwerty(list, w, metrics, layoutStyle.qwerty) }
        relayout()
    }

    fun setT9(list: List<Key>) {
        val s = SideList()
        keys = list; side = s; layoutKind = Layouts.T9
        builder = { w -> Layouts.layoutT9(list, s, w, metrics, layoutStyle.t9) }
        relayout()
    }

    fun setNumpad(list: List<Key>) {
        val s = SideList()
        keys = list; side = s; layoutKind = Layouts.NUMPAD
        builder = { w -> Layouts.layoutNumpad(list, s, w, metrics, layoutStyle.numpad) }
        relayout()
    }

    fun keyOf(code: Int): Key? = keys.firstOrNull { it.code == code }

    private fun relayout() {
        if (width > 0 && ::metrics.isInitialized) builder?.invoke(width.toFloat())
        invalidate()
        a11y.invalidate()
    }

    /** 标签或状态变化后通知无障碍服务。 Tell accessibility services labels changed. */
    fun labelsChanged() {
        invalidate()
        a11y.invalidate()
    }

    override fun getAccessibilityNodeProvider(): android.view.accessibility.AccessibilityNodeProvider = a11y

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = a11y.onHover(event) || super.dispatchHoverEvent(event)

    /** 按键的朗读文本（中文）。 Spoken description of a key. */
    fun describe(k: Key): String = when (k.code) {
        KeyCode.SHIFT -> if (k.icon == 0) k.label else "大写"
        KeyCode.DELETE -> "删除"
        KeyCode.SYMBOL -> "符号"
        KeyCode.NUMBER -> "数字"
        KeyCode.EMOJI -> "表情"
        KeyCode.SPACE -> spaceHint?.let { "空格，$it" } ?: "空格"
        KeyCode.LANG -> if (chinese) "中英切换，当前中文" else "中英切换，当前英文"
        KeyCode.ENTER -> enterDescription
        KeyCode.BACK -> "返回"
        KeyCode.T9_RESET -> if (k.label == "@") "艾特" else k.label
        KeyCode.T9_ONE -> if (k.medium) k.label else "1，标点"
        else -> when {
            k.sub != null -> "${k.sub}，${k.label}"
            k.code in 'a'.code..'z'.code -> if (chinese) k.code.toChar().toString() else k.label
            else -> VirtualA11y.speak(k.label.ifEmpty { String(Character.toChars(k.code)) })
        }
    }

    private inner class A11ySource : VirtualA11y.Source {
        override fun a11yIds(): IntArray {
            val s = side
            val sideIds = if (s == null) IntArray(0) else IntArray(s.items.size) { SIDE_BASE + it }
            return IntArray(keys.size) { it } + sideIds
        }

        override fun a11yBounds(id: Int, out: RectF): Boolean {
            if (id >= SIDE_BASE) {
                val s = side ?: return false
                val i = id - SIDE_BASE
                if (i !in s.items.indices) return false
                val top = s.rect.top + i * s.itemHeight - s.scroll
                out.set(s.rect.left, maxOf(top, s.rect.top), s.rect.right, minOf(top + s.itemHeight, s.rect.bottom))
                return out.height() > 1f
            }
            val k = keys.getOrNull(id) ?: return false
            out.set(k.cell)
            return !out.isEmpty
        }

        override fun a11yLabel(id: Int): CharSequence? {
            if (id >= SIDE_BASE) return side?.items?.getOrNull(id - SIDE_BASE)?.let { VirtualA11y.speak(it) }
            return keys.getOrNull(id)?.let { describe(it) }
        }

        override fun a11yState(id: Int): CharSequence? {
            val k = keys.getOrNull(id) ?: return null
            if (k.code != KeyCode.SHIFT || k.icon == 0) return null
            return when (k.icon) {
                R.drawable.ic_shift_lock -> "大写锁定"
                R.drawable.ic_shift_filled -> "下一个字母大写"
                else -> "关闭"
            }
        }

        override fun a11yClick(id: Int): Boolean {
            if (id >= SIDE_BASE) {
                val i = id - SIDE_BASE
                if (side?.items?.indices?.contains(i) != true) return false
                host?.onSideItem(i)
                return true
            }
            val k = keys.getOrNull(id) ?: return false
            if (k.disabled) return false
            host?.feedback?.haptic(this@KeyboardView)
            host?.onKey(k)
            return true
        }

        override fun a11yLongClickLabel(id: Int): CharSequence? {
            val k = keys.getOrNull(id) ?: return null
            return when {
                k.code == KeyCode.SYMBOL -> "表情"
                k.isChar && k.up != null && k.sub == null -> "输入${VirtualA11y.speak(k.up!!)}"
                else -> null
            }
        }

        override fun a11yLongClick(id: Int): Boolean {
            val k = keys.getOrNull(id) ?: return false
            val up = k.up
            when {
                k.code == KeyCode.SYMBOL -> host?.onLongPressFunc(k)
                k.isChar && up != null -> host?.onKeyText(k, up)
                else -> return false
            }
            return true
        }

        override fun a11yLiftToActivate(id: Int) = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { relayout() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (::metrics.isInitialized) metrics.mainHeight.toInt() else MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec))
    }

    // ================================================================ drawing

    override fun onDraw(canvas: Canvas) {
        if (!::palette.isInitialized) return
        side?.let { drawSide(canvas, it) }
        for (k in keys) drawKey(canvas, k)
    }

    private fun drawKey(c: Canvas, k: Key) {
        val p = palette
        val m = metrics
        val pressed = k === down && mode != M_NONE && mode != M_SIDE
        val danger = k === dangerKey
        val radius = if (k.pill) k.rect.height() / 2f else if (k.large) m.keyRadiusLarge else m.keyRadius
        val bg = when {
            danger -> p.danger
            k.active -> p.accentSoft
            k.style == KeyStyle.ACCENT || (k.code == KeyCode.ENTER && enterAccent) -> if (pressed) p.keyAccentPressed else p.keyAccent
            k.style == KeyStyle.FUNC -> if (pressed) p.keyFuncPressed else p.keyFunc
            else -> if (pressed) p.keyPressed else p.key
        }
        if (k.disabled) {
            // 禁用：无填充描边。 Disabled: outline only.
            tmp.set(k.rect)
            fill.style = Paint.Style.STROKE
            fill.strokeWidth = m.dp(1f)
            fill.color = p.labelDisabled
            tmp.inset(m.dp(0.5f), m.dp(0.5f))
            c.drawRoundRect(tmp, radius, radius, fill)
            fill.style = Paint.Style.FILL
        } else {
            KeyPainter.draw(c, k.rect, tmp, tmp2, fill, bg, pressed, radius, p, m)
        }

        val onAccent = danger || k.style == KeyStyle.ACCENT || (k.code == KeyCode.ENTER && enterAccent)
        val labelColor = when {
            k.disabled -> p.labelDisabled
            onAccent -> p.onAccent
            k.active -> p.keyAccent
            else -> p.label
        }
        val cx = tmp.centerX()
        val cy = tmp.centerY()
        when (k.code) {
            KeyCode.SPACE -> drawSpace(c, k, cx, cy)
            KeyCode.LANG -> if (layoutStyle.labels.lang == "globe") icons.draw(c, R.drawable.ic_globe, p.icon, cx, cy, m.icon(22f)) else drawLang(c, k, cx, cy)
            KeyCode.ENTER -> {
                val lbl = enterLabel
                if (lbl == null) icons.draw(c, enterIcon, if (onAccent) p.onAccent else p.icon, cx, cy, m.icon(22f))
                else drawText(c, lbl, cx, cy, m.label(16f), labelColor, true)
            }
            else -> {
                if (k.icon != 0) {
                    val col = when { danger || onAccent -> p.onAccent; k.active -> p.keyAccent; k.disabled -> p.labelDisabled; else -> p.icon }
                    icons.draw(c, k.icon, col, cx, cy, m.icon(22f))
                } else if (k.sub != null) {
                    // 九键：字母组 + 数字副标签；大字号挤不下时只留主字。 T9: letters + digit; drop the digit when crowded.
                    val h = tmp.height()
                    hintPaint.textSize = m.hint(10.5f)
                    if (tmp.top + h * 0.42f + 0.36f * k.labelSize + m.dp(2f) > tmp.top + h * 0.8f - 0.72f * hintPaint.textSize) {
                        drawText(c, k.label, cx, cy, k.labelSize, labelColor, k.medium)
                    } else {
                        drawText(c, k.label, cx, tmp.top + h * 0.42f, k.labelSize, labelColor, k.medium)
                        hintPaint.color = p.labelHint
                        c.drawText(k.sub!!, cx, tmp.top + h * 0.8f, hintPaint)
                    }
                } else {
                    drawCharLabel(c, k, labelColor)
                }
            }
        }
    }

    private fun drawCharLabel(c: Canvas, k: Key, color: Int) {
        val m = metrics
        val p = palette
        val h = tmp.height()
        // 大字号下键内放不下时隐藏副标记（按实际几何判断），主字不缩小。
        // With large font scales hide secondary marks that would touch the main label; never shrink it.
        val cap = 0.36f // 半个大写字高 ≈ 0.36 × 字号 / half cap height
        val cornerSize = m.hint(9.5f)
        val cornerLabel = k.labelSize * 20f / 22f
        val medium = k.medium || (p.letterMedium && k.code in 'a'.code..'z'.code)
        val corner = k.corner.takeIf {
            cornerMode && it != null && tmp.top + h * 0.47f + cap * cornerLabel + m.dp(1f) <= tmp.bottom - m.dp(5f) - 0.72f * cornerSize + m.dp(2f)
        }
        val hint = k.hint?.takeIf {
            if (hintMode == "none" && corner == null) false
            else if (hintMode == "topRight" && corner == null) tmp.width() > m.dp(26f)
            else if (corner != null) tmp.top + m.dp(3f) + m.hint(9f) + m.dp(1f) <= tmp.top + h * 0.47f - cap * cornerLabel
            else tmp.top + m.dp(4f) + 0.75f * m.hint(10.5f) + m.dp(2f) <= tmp.top + h * 0.58f - cap * k.labelSize
        }
        if (corner != null) {
            hintPaint.textSize = m.hint(9f)
            hintPaint.color = p.labelHint
            if (hint != null) c.drawText(hint, tmp.centerX(), tmp.top + m.dp(3f) - hintPaint.ascent(), hintPaint)
            drawText(c, k.label, tmp.centerX(), tmp.top + h * 0.47f, cornerLabel, color, medium)
            cornerPaint.textSize = cornerSize
            cornerPaint.color = if (k.cornerAccent) p.candidateFirst else p.labelHint
            c.drawText(corner, tmp.right - m.dp(4f), tmp.bottom - m.dp(5f), cornerPaint)
        } else if (hint != null && k.code in 'a'.code..'z'.code && hintMode == "top") {
            hintPaint.textSize = m.hint(layoutStyle.qwerty.hintSize)
            hintPaint.color = p.labelHint
            c.drawText(hint, tmp.centerX(), tmp.top + m.dp(4f) - hintPaint.ascent() * 0.8f, hintPaint)
            drawText(c, k.label, tmp.centerX(), tmp.top + h * 0.58f, k.labelSize, color, medium)
        } else if (hint != null && k.code in 'a'.code..'z'.code && hintMode == "topRight") {
            cornerPaint.textSize = m.hint(layoutStyle.qwerty.hintSize)
            cornerPaint.color = p.labelHint
            c.drawText(hint, tmp.right - m.dp(4f), tmp.top + m.dp(3f) - cornerPaint.ascent() * 0.8f, cornerPaint)
            drawText(c, k.label, tmp.centerX(), tmp.centerY(), k.labelSize, color, medium)
        } else {
            drawText(c, k.label, tmp.centerX(), tmp.centerY(), k.labelSize, color, medium)
        }
    }

    private fun drawSpace(c: Canvas, k: Key, cx: Float, cy: Float) {
        val m = metrics
        val p = palette
        if (mode == M_CURSOR && k === down) {
            drawText(c, "‹  移动光标  ›", cx, cy, m.label(13f), p.labelSecondary, false)
            return
        }
        val hint = spaceHint
        if (hint != null) {
            drawText(c, hint, cx, cy, m.label(13f), p.labelSecondary, false)
            return
        }
        when (layoutStyle.labels.space) {
            "text" -> { drawText(c, layoutStyle.labels.spaceText, cx, cy, m.label(15f), p.labelSecondary, false); return }
            "lang" -> { drawText(c, if (chinese) "中文" else "English", cx, cy, m.label(14f), p.labelSecondary, false); return }
            "none" -> return
        }
        val iconSize = m.icon(24f)
        icons.draw(c, R.drawable.ic_space, p.labelHint, cx, cy + m.dp(3f), iconSize)
        icons.draw(c, R.drawable.ic_mic, p.labelHint, cx, cy - m.dp(7f), m.icon(12f))
        // 上滑字符放右上角，避开话筒图标。 Swipe-up hint in the top-right corner, clear of the mic icon.
        k.up?.let {
            cornerPaint.textSize = m.hint(10.5f); cornerPaint.color = p.labelHint
            c.drawText(it, tmp.right - m.dp(6f), tmp.top + m.dp(3f) - cornerPaint.ascent() * 0.8f, cornerPaint)
        }
    }

    private fun drawLang(c: Canvas, k: Key, cx: Float, cy: Float) {
        val m = metrics
        val p = palette
        // 大字号时整体缩到键宽内。 Fit the whole 中/英 into the key at large font scales.
        text.typeface = mediumTf; text.textSize = m.label(16f)
        var probe = text.measureText("中")
        text.typeface = Typeface.DEFAULT; text.textSize = m.label(12f)
        probe += text.measureText("/英")
        val fit = ((tmp.width() - m.dp(6f)) / probe).coerceAtMost(1f)
        val big = m.label(16f) * fit
        val small = m.label(12f) * fit
        text.typeface = mediumTf
        text.textSize = if (chinese) big else small
        val wZh = text.measureText("中")
        text.typeface = Typeface.DEFAULT
        text.textSize = small
        val wSlash = text.measureText("/")
        text.typeface = if (!chinese) mediumTf else Typeface.DEFAULT
        text.textSize = if (!chinese) big else small
        val wEn = text.measureText("英")
        val total = wZh + wSlash + wEn
        var x = cx - total / 2
        text.textSize = big
        val base = cy - (text.ascent() + text.descent()) / 2
        text.textAlign = Paint.Align.LEFT
        text.typeface = if (chinese) mediumTf else Typeface.DEFAULT
        text.textSize = if (chinese) big else small
        text.color = if (chinese) p.label else p.labelHint
        c.drawText("中", x, base, text); x += wZh
        text.typeface = Typeface.DEFAULT
        text.textSize = small
        text.color = p.labelHint
        c.drawText("/", x, base, text); x += wSlash
        text.typeface = if (!chinese) mediumTf else Typeface.DEFAULT
        text.textSize = if (!chinese) big else small
        text.color = if (!chinese) p.label else p.labelHint
        c.drawText("英", x, base, text)
        text.textAlign = Paint.Align.CENTER
        text.typeface = Typeface.DEFAULT
    }

    private fun drawText(c: Canvas, s: String, cx: Float, cy: Float, size: Float, color: Int, medium: Boolean) {
        // 不超过键高的 0.62（大字号时）。 Never taller than 0.62 × key height.
        text.textSize = minOf(size, tmp.height() * 0.62f)
        text.color = color
        text.typeface = if (medium) mediumTf else Typeface.DEFAULT
        // 过宽时缩小（防裁切）。 Shrink to fit width so nothing is clipped.
        val w = text.measureText(s)
        val maxW = tmp.width() - metrics.dp(6f)
        if (w > maxW && w > 0) text.textSize = text.textSize * maxW / w
        c.drawText(s, cx, cy - (text.ascent() + text.descent()) / 2, text)
    }

    private fun drawSide(c: Canvas, s: SideList) {
        val p = palette
        val m = metrics
        val r = m.keyRadiusLarge
        val keyColour = layoutStyle.t9.sideColor == "key"
        KeyPainter.draw(c, s.rect, tmp, tmp2, fill, if (keyColour) p.key else p.keyFunc, false, r, p, m)
        c.save()
        c.clipRect(s.rect)
        text.textSize = s.textSize
        text.typeface = Typeface.DEFAULT
        val hair = max(1f, m.dp(0.5f))
        for (i in s.items.indices) {
            val top = s.rect.top + i * s.itemHeight - s.scroll
            if (top > s.rect.bottom || top + s.itemHeight < s.rect.top) continue
            if (i == s.highlighted && mode == M_SIDE && !sideDragging) {
                fill.color = if (keyColour) p.keyPressed else p.keyFuncPressed
                c.drawRect(s.rect.left, top, s.rect.right, top + s.itemHeight, fill)
            }
            text.color = p.label
            c.drawText(s.items[i], s.rect.centerX(), top + s.itemHeight / 2 - (text.ascent() + text.descent()) / 2, text)
            if (i > 0) {
                fill.color = p.divider
                c.drawRect(s.rect.left + m.dp(8f), top - hair / 2, s.rect.right - m.dp(8f), top + hair / 2, fill)
            }
        }
        c.restore()
    }

    // ================================================================ touch

    private fun keyAt(x: Float, y: Float): Key? {
        for (k in keys) if (k.cell.contains(x, y)) return k
        // 容差：取最近的格子。 Fallback to nearest cell.
        var best: Key? = null
        var bd = Float.MAX_VALUE
        for (k in keys) {
            val dx = if (x < k.cell.left) k.cell.left - x else if (x > k.cell.right) x - k.cell.right else 0f
            val dy = if (y < k.cell.top) k.cell.top - y else if (y > k.cell.bottom) y - k.cell.bottom else 0f
            val d = dx + dy
            if (d < bd) { bd = d; best = k }
        }
        return if (bd < metrics.dp(8f)) best else null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!::metrics.isInitialized) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> startPointer(e, 0)
            MotionEvent.ACTION_POINTER_DOWN -> {
                // 快速打字 rollover：第一指立即按点击输出。 Rollover: commit the first finger as a tap.
                if (mode == M_TAP || mode == M_SWIPE) finishPointer(commit = true)
                else if (mode != M_NONE) return true
                startPointer(e, e.actionIndex)
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(pointerId)
                if (i >= 0) movePointer(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_POINTER_UP -> if (e.getPointerId(e.actionIndex) == pointerId) finishPointer(commit = true)
            MotionEvent.ACTION_UP -> if (e.getPointerId(e.actionIndex) == pointerId) finishPointer(commit = true)
            MotionEvent.ACTION_CANCEL -> finishPointer(commit = false)
        }
        return true
    }

    private fun startPointer(e: MotionEvent, index: Int) {
        val x = e.getX(index)
        val y = e.getY(index)
        pointerId = e.getPointerId(index)
        downX = x; downY = y
        val s = side
        if (s != null && s.rect.contains(x, y)) {
            mode = M_SIDE
            sideDragging = false
            sideDownScroll = s.scroll
            s.highlighted = ((y - s.rect.top + s.scroll) / s.itemHeight).toInt()
            host?.feedback?.key(this)
            invalidate()
            return
        }
        val k = keyAt(x, y) ?: run { mode = M_NONE; return }
        if (k.disabled) { mode = M_NONE; return }
        down = k
        mode = M_TAP
        repeatCount = 0
        host?.feedback?.key(
            this,
            when (k.code) {
                KeyCode.DELETE -> Feedback.Sound.DELETE
                KeyCode.ENTER -> Feedback.Sound.RETURN
                KeyCode.SPACE -> Feedback.Sound.SPACE
                else -> Feedback.Sound.STANDARD
            },
        )
        if (k.isChar && k.preview && host?.previewEnabled == true) {
            host.overlay?.let { ov -> ov.map(this, k.rect, tmp); ov.showBubble(tmp, k.label) }
        }
        postDelayed(longPress, if (k.code == KeyCode.SPACE) 350L else 300L)
        if (k.code == KeyCode.DELETE) postDelayed(repeat, 400)
        invalidate()
    }

    private fun movePointer(x: Float, y: Float) {
        val m = metrics
        val dx = x - downX
        val dy = y - downY
        val slop = m.dp(8f)
        when (mode) {
            M_SIDE -> {
                val s = side ?: return
                if (!sideDragging && abs(dy) > slop) sideDragging = true
                if (sideDragging) {
                    s.scroll = (sideDownScroll - dy).coerceIn(0f, s.maxScroll())
                    invalidate()
                }
            }
            M_TAP, M_SWIPE -> {
                val k = down ?: return
                val swipeMin = max(m.dp(20f), m.keyHeight * 0.45f)
                if (k.code == KeyCode.SPACE && abs(dx) > slop && abs(dx) > abs(dy)) {
                    removeCallbacks(longPress)
                    mode = M_CURSOR
                    cursorAnchor = x
                    lastMoveT = SystemClock.uptimeMillis(); lastMoveX = x
                    invalidate()
                    return
                }
                if (k.code == KeyCode.DELETE) {
                    if (abs(dx) > slop || abs(dy) > slop) removeCallbacks(repeat)
                    val unit = keys.firstOrNull { it.code == 'q'.code }?.cell?.width() ?: m.dp(40f)
                    if (dx <= -1.5f * unit) {
                        removeCallbacks(longPress)
                        mode = M_CLEAR
                        dangerKey = k
                        host?.feedback?.haptic(this)
                        host?.overlay?.let { ov -> ov.map(this, k.rect, tmp); ov.showInfo(tmp, "松手清空") }
                        invalidate()
                    }
                    return
                }
                val up = k.up
                if (up != null && dy <= -swipeMin && abs(dx) < abs(dy)) {
                    if (mode != M_SWIPE) {
                        mode = M_SWIPE
                        removeCallbacks(longPress)
                        host?.feedback?.haptic(this)
                        host?.overlay?.updateBubble(up, true)
                    }
                } else if (mode == M_SWIPE) {
                    mode = M_TAP
                    host?.overlay?.updateBubble(k.label, false)
                } else if (abs(dx) > slop || abs(dy) > slop) {
                    removeCallbacks(longPress)
                }
            }
            M_CURSOR -> {
                val now = SystemClock.uptimeMillis()
                val dt = (now - lastMoveT).coerceAtLeast(1)
                val speed = abs(x - lastMoveX) / m.density / dt * 1000f
                lastMoveT = now; lastMoveX = x
                val step = m.dp(12f) * (if (speed > 600f) 0.5f else 1f)
                var n = 0
                while (x - cursorAnchor >= step) { cursorAnchor += step; n++ }
                while (cursorAnchor - x >= step) { cursorAnchor -= step; n-- }
                if (n != 0) {
                    host?.feedback?.haptic(this)
                    host?.onCursorSteps(n)
                }
            }
            M_CLEAR -> {
                val k = down ?: return
                val unit = keys.firstOrNull { it.code == 'q'.code }?.cell?.width() ?: m.dp(40f)
                if (dx > -1.5f * unit) {
                    mode = M_TAP
                    dangerKey = null
                    host?.overlay?.hideInfo()
                    invalidate()
                }
                if (k.code != KeyCode.DELETE) return
            }
            M_ALT -> {
                val ov = host?.overlay ?: return
                if (ov.moveAlternatives(ov.mapX(this, x), ov.mapY(this, y))) host.feedback?.haptic(this)
            }
            M_VOICE -> host?.onVoiceHoldMove(dy)
        }
    }

    private fun onLongPress() {
        val k = down ?: return
        if (mode != M_TAP) return
        val ov = host?.overlay
        val info = k.longInfo
        if (info != null && ov != null) {
            mode = M_INFO
            ov.hideBubble(true)
            ov.map(this, k.rect, tmp)
            ov.showInfo(tmp, info)
            host.feedback?.haptic(this)
            return
        }
        val list = k.longPress
        if (k.isChar || k.code == KeyCode.LANG) {
            if (!list.isNullOrEmpty() && ov != null) {
                mode = M_ALT
                ov.map(this, k.rect, tmp)
                val init = if (k.code == KeyCode.LANG) list.indexOf(k.label).coerceAtLeast(0) else list.indexOf(k.up).coerceAtLeast(0)
                ov.showAlternatives(tmp, list, init)
                host.feedback?.haptic(this)
            }
            return
        }
        when (host?.onLongPressFunc(k) ?: 0) {
            LONG_VOICE -> { mode = M_VOICE; invalidate() }
            LONG_CONSUMED -> { mode = M_CONSUMED; invalidate() }
        }
    }

    private fun finishPointer(commit: Boolean) {
        removeCallbacks(longPress)
        removeCallbacks(repeat)
        val k = down
        val ov = host?.overlay
        val m = mode
        mode = M_NONE
        down = null
        dangerKey = null
        pointerId = -1
        ov?.hideBubble()
        when (m) {
            M_SIDE -> {
                val s = side
                if (commit && s != null && !sideDragging && s.highlighted in s.items.indices) host?.onSideItem(s.highlighted)
                s?.highlighted = -1
            }
            M_TAP -> if (commit && k != null && !(k.code == KeyCode.DELETE && repeatCount > 0)) host?.onKey(k)
            M_SWIPE -> if (commit && k != null) k.up?.let { host?.onKeyText(k, it) }
            M_ALT -> {
                val sel = ov?.selectedAlternative()
                ov?.hideAlternatives()
                if (commit && k != null && sel != null) host?.onKeyText(k, sel)
            }
            M_INFO -> ov?.hideInfo()
            M_CLEAR -> {
                ov?.hideInfo()
                if (commit) host?.onDeleteClear()
            }
            M_VOICE -> host?.onVoiceHoldEnd(!commit)
        }
        invalidate()
    }

    /** 面板切换等场合复位触摸。 Reset any in-flight gesture. */
    fun cancelTouch() {
        if (mode != M_NONE) finishPointer(commit = false)
    }

    companion object {
        /** 左侧列表项的虚拟 id 起点。 Virtual id base for side-list items. */
        private const val SIDE_BASE = 1000
        const val LONG_VOICE = 1
        const val LONG_CONSUMED = 2
        private const val M_NONE = 0
        private const val M_TAP = 1
        private const val M_SWIPE = 2
        private const val M_ALT = 3
        private const val M_CURSOR = 4
        private const val M_CLEAR = 5
        private const val M_VOICE = 6
        private const val M_SIDE = 7
        private const val M_INFO = 8
        private const val M_CONSUMED = 9
    }
}
