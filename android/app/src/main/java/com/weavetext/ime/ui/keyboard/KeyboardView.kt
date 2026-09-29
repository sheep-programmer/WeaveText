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
import kotlin.math.sqrt

/** 键区回调（由键盘根视图实现）。 Callbacks from the key area. */
interface KeyboardHost {
    val overlay: PopupOverlay?
    val feedback: Feedback?
    val previewEnabled: Boolean
    /** 按键输出：字符键在按下时调用，功能键在抬起时调用。 Key output: char keys on DOWN, function keys on release. */
    fun onKey(key: Key)
    fun onKeyText(key: Key, text: String)
    /** 用上滑/长按选中的 [text] 替换按下时已输出的字符。 Replace the char already emitted on DOWN with [text]. */
    fun onKeyReplace(key: Key, text: String) = onKeyText(key, text)
    /**
     * 系统截走了手势（边缘返回手势等），撤回按下时已输出的字符。
     * The system took the gesture over (edge back gesture and the like): take back the char emitted on DOWN.
     */
    fun onKeyRevert(key: Key) {}
    /** 空格横滑能否移动光标（中文组合中不能）。 Whether a space-bar slide may move the cursor. */
    fun cursorDragAllowed(): Boolean = true
    fun onCursorSteps(steps: Int)
    /** 长按删除连发；返回 false 停止连发。 Delete auto-repeat; false stops repeating. */
    fun onDeleteRepeat(count: Int): Boolean
    fun onDeleteClear()
    /** 功能键长按；返回 [KeyboardView.LONG_VOICE] / [KeyboardView.LONG_CONSUMED] / 0。 */
    fun onLongPressFunc(key: Key): Int
    fun onVoiceHoldMove(dy: Float) {}
    fun onVoiceHoldEnd(cancelled: Boolean) {}
    fun onSideItem(index: Int)
    /** 手写：一笔写完，[strokes] 为这个字的全部笔画。 Handwriting: a stroke ended; [strokes] are all of this char's. */
    fun onHandStroke(strokes: List<FloatArray>) {}
    /** 手写：停笔后又落笔，先上屏首选。 Handwriting: pen down after a pause; commit the top candidate first. */
    fun onHandCommit() {}
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
    /** 手写书写区（仅手写布局）。 The handwriting pad (handwriting layout only). */
    var hand: HandPad? = null
        private set
    private val handPad = HandPad()
    /** 手写停笔多久算写完一个字（毫秒，来自设置）。 Pause that ends a handwritten char, in ms (from settings). */
    var handPauseMs = HandPad.COMMIT_PAUSE_MS
    /** 正在处理的触摸事件时刻（笔画按事件时间计时，不受主线程忙闲影响）。 Time of the event being handled. */
    private var evTime = 0L
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
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).zh().apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).zh().apply { textAlign = Paint.Align.CENTER }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).zh().apply { textAlign = Paint.Align.RIGHT }
    private val tmp = RectF()
    private val tmp2 = RectF()
    private val mediumTf = Typeface.create(Typeface.DEFAULT, Typeface.BOLD).let {
        if (android.os.Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 500, false) else it
    }

    // 触摸状态：每根手指一份，预先分配，按下时零分配。 Touch state: one preallocated slot per finger.
    private inner class Ptr {
        var id = -1
        var key: Key? = null
        var downX = 0f
        var downY = 0f
        var mode = M_NONE
        /** 字符已在按下时输出。 The char was emitted on DOWN. */
        var emitted = false
        var cursorAnchor = 0f
        var cursorSteps = 0
        var lastMoveT = 0L
        var lastMoveX = 0f
        var repeatCount = 0
        val longPress = Runnable { onLongPress(this) }
        val repeat: Runnable = object : Runnable {
            override fun run() {
                val k = key ?: return
                if (k.code != KeyCode.DELETE || mode != M_TAP) return
                repeatCount++
                if (repeatCount == 1 || repeatCount % 5 == 0) host?.feedback?.haptic(this@KeyboardView)
                if (host?.onDeleteRepeat(repeatCount) == false) return
                postDelayed(this, if (repeatCount > 20) 80 else 50)
            }
        }
    }

    private val ptrs = Array(MAX_POINTERS) { Ptr() }
    /** 当前显示按键气泡的手指。 Finger owning the preview bubble. */
    private var bubbleOwner: Ptr? = null
    /** 正在操作左侧列表的手指。 Finger on the side list. */
    private var sideOwner: Ptr? = null
    /** 正在书写的手指。 Finger writing on the pad. */
    private var inkOwner: Ptr? = null
    private var dangerKey: Key? = null
    private var sideDownScroll = 0f
    private var sideDragging = false

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

    /** 允许宽屏分体（设置项）。 Split allowed on wide screens (setting). */
    var splitWide = true
        set(v) { if (field != v) { field = v; relayout() } }

    fun setQwerty(list: List<Key>) {
        keys = list; side = null; dropHand(); layoutKind = Layouts.QWERTY
        builder = { w -> Layouts.layoutQwerty(list, w, metrics, layoutStyle.qwerty, Layouts.splitGap(w, metrics, splitWide)) }
        relayout()
    }

    fun setT9(list: List<Key>) {
        val s = SideList()
        keys = list; side = s; dropHand(); layoutKind = Layouts.T9
        builder = { w -> Layouts.layoutT9(list, s, w, metrics, layoutStyle.t9) }
        relayout()
    }

    fun setT14(list: List<Key>) {
        val s = SideList()
        keys = list; side = s; dropHand(); layoutKind = Layouts.T14
        builder = { w -> Layouts.layoutT14(list, s, w, metrics, layoutStyle.t9) }
        relayout()
    }

    fun setNumpad(list: List<Key>) {
        val s = SideList()
        keys = list; side = s; dropHand(); layoutKind = Layouts.NUMPAD
        builder = { w -> Layouts.layoutNumpad(list, s, w, metrics, layoutStyle.numpad) }
        relayout()
    }

    /** 手写布局；笔迹在重建布局（换主题等）时保留。 Handwriting layout; the ink survives rebuilds (theme changes). */
    fun setHand(list: List<Key>) {
        keys = list; side = null; hand = handPad; layoutKind = Layouts.HAND
        builder = { w -> Layouts.layoutHand(list, handPad, w, metrics, layoutStyle.t9) }
        relayout()
    }

    private fun dropHand() {
        if (hand == null) return
        cancelTouch()
        hand = null
        handPad.reset()
        inkLayer?.invalidate()
    }

    /**
     * 清掉手写区已写完的笔画（上屏或丢弃了这个字）；有动画时短暂淡出。
     * Clear the pad's finished strokes (the char was committed or dropped), fading briefly when animations are on.
     */
    fun clearInk() {
        val pad = hand ?: return
        pad.clear(fade = android.animation.ValueAnimator.areAnimatorsEnabled())
        invalidate()
    }

    /** 手写区退一笔。 Drop the pad's last stroke. */
    fun undoStroke(): Boolean = (hand?.undo() == true).also { if (it) invalidate() }

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
        KeyCode.HAND_CLEAR -> "重写"
        else -> when {
            k.sub != null -> "${k.sub}，${k.label}"
            layoutKind == Layouts.T14 && k.code in 'A'.code..'N'.code -> k.label.lowercase().toList().joinToString("，")
            k.code in 'a'.code..'z'.code -> if (chinese) k.code.toChar().toString() else k.label
            else -> VirtualA11y.speak(k.label.ifEmpty { String(Character.toChars(k.code)) })
        }
    }

    private inner class A11ySource : VirtualA11y.Source {
        override fun a11yIds(): IntArray {
            val s = side
            val sideIds = if (s == null) IntArray(0) else IntArray(s.items.size) { SIDE_BASE + it }
            val padIds = if (hand == null) IntArray(0) else intArrayOf(PAD_ID)
            return IntArray(keys.size) { it } + sideIds + padIds
        }

        override fun a11yBounds(id: Int, out: RectF): Boolean {
            if (id == PAD_ID) {
                out.set(hand?.rect ?: return false)
                return true
            }
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
            if (id == PAD_ID) return "手写区，用手指书写一个字"
            if (id >= SIDE_BASE) return side?.items?.getOrNull(id - SIDE_BASE)?.let { VirtualA11y.speak(it) }
            return keys.getOrNull(id)?.let { describe(it) }
        }

        override fun a11yState(id: Int): CharSequence? {
            if (id == PAD_ID) return hand?.strokes?.size?.takeIf { it > 0 }?.let { "已写 $it 笔" }
            val k = keys.getOrNull(id) ?: return null
            if (k.code != KeyCode.SHIFT || k.icon == 0) return null
            return when (k.icon) {
                R.drawable.ic_shift_lock -> "大写锁定"
                R.drawable.ic_shift_filled -> "下一个字母大写"
                else -> "关闭"
            }
        }

        override fun a11yClick(id: Int): Boolean {
            if (id == PAD_ID) return false
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
        hand?.let { drawHand(canvas, it) }
    }

    private fun drawKey(c: Canvas, k: Key) {
        val p = palette
        val m = metrics
        val pressed = isPressed(k)
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

        val onAccent = k.style == KeyStyle.ACCENT || (k.code == KeyCode.ENTER && enterAccent)
        val labelColor = when {
            k.disabled -> p.labelDisabled
            danger -> p.onDanger
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
                    val col = when { danger -> p.onDanger; onAccent -> p.onAccent; k.active -> p.keyAccent; k.disabled -> p.labelDisabled; else -> p.icon }
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
            else tmp.top + m.dp(3f) + 0.75f * m.hint(10.5f) + m.dp(2f) <= tmp.top + h * LETTER_Y - cap * k.labelSize
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
            c.drawText(hint, tmp.centerX(), tmp.top + m.dp(3f) - hintPaint.ascent() * 0.8f, hintPaint)
            drawText(c, k.label, tmp.centerX(), tmp.top + h * LETTER_Y, k.labelSize, color, medium)
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
        if (inCursorMode(k)) {
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
            if (i == s.highlighted && sideOwner != null && !sideDragging) {
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

    /** 墨迹是填充轮廓（粗细已在 [HandPad] 里按速度算好）。 Ink is a filled outline; widths come from [HandPad]. */
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var guideDash: android.graphics.DashPathEffect? = null
    private var guideDashFor = 0f

    /**
     * 书写区：键色底板、居中的虚线十字参考线，空白时显示提示；墨迹用标签色，上一个字短暂淡出。
     * The pad: a key-coloured plate with a dashed centre cross, a hint while empty; ink in the label colour,
     * the previous character fading briefly.
     */
    private fun drawHand(c: Canvas, pad: HandPad) {
        val p = palette
        val m = metrics
        val r = m.keyRadiusLarge
        val keyColour = layoutStyle.t9.sideColor == "key"
        KeyPainter.draw(c, pad.rect, tmp, tmp2, fill, if (keyColour) p.keyFunc else p.key, false, r, p, m)
        val dashLen = m.dp(5f)
        if (guideDashFor != dashLen) { guideDash = android.graphics.DashPathEffect(floatArrayOf(dashLen, dashLen), 0f); guideDashFor = dashLen }
        guidePaint.pathEffect = guideDash
        guidePaint.strokeWidth = max(1f, m.dp(1f))
        guidePaint.color = p.divider
        val cx = pad.rect.centerX()
        val cy = pad.rect.centerY()
        val inset = m.dp(12f)
        c.drawLine(pad.rect.left + inset, cy, pad.rect.right - inset, cy, guidePaint)
        c.drawLine(cx, pad.rect.top + inset, cx, pad.rect.bottom - inset, guidePaint)
        if (!pad.hasInk) {
            text.textSize = m.label(13f)
            text.typeface = Typeface.DEFAULT
            text.color = p.labelHint
            text.textAlign = Paint.Align.LEFT
            c.drawText("在此书写", pad.rect.left + m.dp(12f), pad.rect.top + m.dp(10f) - text.ascent(), text)
            text.textAlign = Paint.Align.CENTER
        }
        val layer = inkLayer
        if (layer == null) drawInk(c, 0f, 0f) else layer.invalidate()
    }

    /**
     * 墨迹层：盖在书写区上、不接触摸的透明视图。写字时只重画它，不必每次移动都重画整块键盘（按键、文字）。
     * The ink layer: a transparent, non-touchable view over the pad. While writing only it is redrawn, instead of
     * every key and label on each move.
     */
    var inkLayer: View? = null

    /** 画墨迹；[dx]/[dy] 为本视图相对画布所在视图的偏移。 Draw the ink; [dx]/[dy] offset this view within the canvas's view. */
    fun drawInk(c: Canvas, dx: Float, dy: Float) {
        val pad = hand ?: return
        if (!::palette.isInitialized) return
        val p = palette
        c.save()
        c.translate(dx, dy)
        c.clipRect(pad.rect)
        c.translate(pad.rect.left, pad.rect.top)
        inkPaint.color = p.label
        val fade = pad.fadeProgress()
        if (fade >= 0f) {
            inkPaint.alpha = ((1f - fade) * (p.label ushr 24)).toInt()
            c.drawPath(pad.fading, inkPaint)
            inkPaint.color = p.label
            (inkLayer ?: this).postInvalidateOnAnimation()
        }
        c.drawPath(pad.ink, inkPaint)
        c.restore()
    }

    private fun invalidateInk() {
        inkLayer?.invalidate() ?: invalidate()
    }

    // ================================================================ touch

    private fun isPressed(k: Key): Boolean {
        for (p in ptrs) if (p.key === k && p.mode != M_NONE && p.mode != M_SIDE) return true
        return false
    }

    private fun inCursorMode(k: Key): Boolean {
        for (p in ptrs) if (p.key === k && p.mode == M_CURSOR) return true
        return false
    }

    private fun keyAt(x: Float, y: Float): Key? {
        val list = keys
        for (i in list.indices) if (list[i].cell.contains(x, y)) return list[i]
        // 容差：取最近的格子。 Fallback to nearest cell.
        var best: Key? = null
        var bd = Float.MAX_VALUE
        for (i in list.indices) {
            val k = list[i]
            val dx = if (x < k.cell.left) k.cell.left - x else if (x > k.cell.right) x - k.cell.right else 0f
            val dy = if (y < k.cell.top) k.cell.top - y else if (y > k.cell.bottom) y - k.cell.bottom else 0f
            val d = dx + dy
            if (d < bd) { bd = d; best = k }
        }
        return if (bd < metrics.dp(8f)) best else null
    }

    private fun ptrOf(id: Int): Ptr? {
        for (p in ptrs) if (p.id == id) return p
        return null
    }

    /**
     * 多指按 pointer id 各自跟踪：按下顺序即输出顺序，任何手势进行中新手指的按键照常输出。
     * Every pointer is tracked on its own: output follows press order, and a new finger always types,
     * whatever gesture another finger is in.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!::metrics.isInitialized) return false
        evTime = e.eventTime
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 上一轮没收到抬起（被系统截走等）时先复位。 Drop leftovers of a gesture that never ended.
                for (p in ptrs) if (p.id >= 0) finishPointer(p, commit = false)
                startPointer(e, 0)
            }
            MotionEvent.ACTION_POINTER_DOWN -> startPointer(e, e.actionIndex)
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                val p = ptrOf(e.getPointerId(i)) ?: continue
                if (p.mode == M_INK) { inkMove(e, i); continue }
                movePointer(p, e.getX(i), e.getY(i), e.eventTime)
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> ptrOf(e.getPointerId(e.actionIndex))?.let { finishPointer(it, commit = true) }
            MotionEvent.ACTION_CANCEL -> systemCancel()
        }
        return true
    }

    /**
     * 系统发来的取消（边缘返回手势、下拉通知栏等截走了手指）：单指按字符键时撤回按下即输出的那个字，
     * 否则手势结束时键盘收起，留下一个没人想打的字母。面板切换等内部复位走 [cancelTouch]，不撤回。
     * A cancel from the system (an edge back gesture, the notification shade, …took the finger over): for a
     * single finger on a char key, take back the char emitted on DOWN, otherwise the keyboard closes and leaves a
     * letter nobody meant to type. Internal resets (panel switches) go through [cancelTouch] and keep it.
     */
    private fun systemCancel() {
        var active: Ptr? = null
        var n = 0
        for (p in ptrs) if (p.id >= 0) { n++; active = p }
        val p = active
        val k = p?.key
        val revert = n == 1 && p != null && k != null && p.emitted && (p.mode == M_TAP || p.mode == M_SWIPE || p.mode == M_ALT)
        for (q in ptrs) if (q.id >= 0) finishPointer(q, commit = false)
        if (revert && k != null) host?.onKeyRevert(k)
    }

    /**
     * 这次落笔算不算写字：在书写区内；或者刚写过一笔（停顿不到判字时间）、落在书写区外 8dp 以内——
     * 贴边起笔不会误按到删除键或底行的键。
     * Does this pen-down ink: inside the pad, or within 8dp outside it while a character is in progress (the last
     * lift was less than the pause ago), so a stroke started at the edge doesn't hit Delete or a bottom-row key.
     */
    private fun inPad(pad: HandPad, x: Float, y: Float, t: Long): Boolean {
        if (pad.rect.contains(x, y)) return true
        if (pad.strokes.isEmpty() || pad.sinceLastStroke(t) >= handPauseMs) return false
        val band = metrics.dp(8f)
        return x >= pad.rect.left - band && x <= pad.rect.right + band && y >= pad.rect.top - band && y <= pad.rect.bottom + band
    }

    private fun startPointer(e: MotionEvent, index: Int) {
        var p: Ptr? = null
        for (q in ptrs) if (q.id < 0) { p = q; break }
        if (p == null) return // 超过 4 根手指忽略。 More than four fingers are ignored.
        // 正在书写时忽略其它手指（手掌、误触）。 While writing, other fingers are ignored (palm, stray touches).
        if (inkOwner != null) return
        val x = e.getX(index)
        val y = e.getY(index)
        val pad = hand
        if (pad != null && inPad(pad, x, y, e.eventTime)) {
            settleOthers()
            p.id = e.getPointerId(index)
            p.key = null
            p.mode = M_INK
            inkOwner = p
            // 停笔够久后落笔：先上屏上一个字的首选。 Pen down after a pause commits the previous char first.
            // 按事件时刻算停顿：主线程忙时处理得晚，不会被误当成停笔。 Pause by event time, so a busy main thread doesn't fake one.
            if (pad.strokes.isNotEmpty() && pad.sinceLastStroke(e.eventTime) >= handPauseMs) {
                host?.onHandCommit()
                pad.clear(fade = android.animation.ValueAnimator.areAnimatorsEnabled())
            }
            // 书写要跟手：这根手指的移动不等下一帧批量送达。 Ink must follow the finger: moves are not batched per frame.
            requestUnbufferedDispatch(e)
            pad.begin((x - pad.rect.left).coerceIn(0f, pad.rect.width()), (y - pad.rect.top).coerceIn(0f, pad.rect.height()), e.eventTime)
            invalidate()
            return
        }
        val s = side
        // 按列表所在的整格判断，边距里的按下不会落到相邻的键上。 Hit-test the whole cell, so its margins don't hit a neighbour.
        if (s != null && s.cell.contains(x, y)) {
            if (sideOwner != null) return
            p.id = e.getPointerId(index)
            p.downX = x; p.downY = y
            p.mode = M_SIDE
            sideOwner = p
            sideDragging = false
            sideDownScroll = s.scroll
            s.highlighted = ((y.coerceIn(s.rect.top, s.rect.bottom - 1f) - s.rect.top + s.scroll) / s.itemHeight).toInt()
            host?.feedback?.key(this)
            invalidate()
            return
        }
        val k = keyAt(x, y) ?: return
        if (k.disabled) return
        settleOthers()
        p.id = e.getPointerId(index)
        p.key = k
        p.downX = x; p.downY = y
        p.mode = M_TAP
        p.emitted = false
        p.repeatCount = 0
        p.cursorSteps = 0
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
            host.overlay?.let { ov -> ov.map(this, k.rect, tmp); ov.showBubble(tmp, k.label); bubbleOwner = p }
        }
        invalidate()
        // 长按计时从按下事件的时刻算起，主线程忙时也稳定。 Long press counts from the event time, stable under load.
        val timeout = if (k.code == KeyCode.SPACE) LONG_PRESS_SPACE_MS else LONG_PRESS_MS
        postDelayed(p.longPress, (e.eventTime + timeout - SystemClock.uptimeMillis()).coerceAtLeast(0L))
        if (k.code == KeyCode.DELETE) postDelayed(p.repeat, 400)
        // 字符键按下即输出；上滑、长按改写时替换这一个字。 Char keys emit on DOWN; swipe/long-press replace it.
        if (k.isChar) {
            p.emitted = true
            measureNear(k, x, y)
            host?.onKey(k)
            nearCode = 0
        }
    }

    /**
     * 本次按下的字母键若靠近交界：交界另一侧的字母（否则 0）与贴近度（1 = 正压在交界上），仅在 [KeyboardHost.onKey]
     * 回调期间有效。内核据此纠正按到邻键的误触。
     * For the letter being pressed near a border: the letter across it (else 0) and the closeness (1 = right on
     * the border); valid only during [KeyboardHost.onKey]. The engine uses it to fix taps on the neighbouring key.
     */
    var nearCode = 0
        private set
    var nearCloseness = 0f
        private set

    /** 与各字母键中心的归一化距离（按键距、行距）比较，次近者差距在交界带内即为邻键。 Normalised centre distances. */
    private fun measureNear(k: Key, x: Float, y: Float) {
        nearCode = 0
        nearCloseness = 0f
        if (k.code !in 'a'.code..'z'.code) return
        val m = metrics
        val sx = k.rect.width() + 2 * m.insetH
        val sy = k.rect.height() + 2 * m.insetV
        if (sx <= 0f || sy <= 0f) return
        val own = centreDistance(k, x, y, sx, sy)
        var best: Key? = null
        var bd = Float.MAX_VALUE
        val list = keys
        for (i in list.indices) {
            val o = list[i]
            if (o === k || o.code !in 'a'.code..'z'.code) continue
            val d = centreDistance(o, x, y, sx, sy)
            if (d < bd) { bd = d; best = o }
        }
        val margin = bd - own
        if (best != null && margin < NEAR_BAND) {
            nearCode = best.code
            nearCloseness = 1f - margin.coerceAtLeast(0f) / NEAR_BAND
        }
    }

    private fun centreDistance(k: Key, x: Float, y: Float, sx: Float, sy: Float): Float {
        val dx = (x - k.rect.centerX()) / sx
        val dy = (y - k.rect.centerY()) / sy
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * 新手指按下前，结算其它手指：未输出的功能键立即按点击输出（保持按下顺序），
     * 已输出的字符键不再触发上滑/长按，打开的长按浮层收起。
     * Before a new finger lands, settle the others: pending function keys fire now (press order is kept),
     * emitted char keys lose their swipe/long-press, and an open popup closes.
     */
    private fun settleOthers() {
        val ov = host?.overlay
        for (o in ptrs) {
            if (o.id < 0) continue
            val k = o.key
            when (o.mode) {
                M_TAP, M_SWIPE -> {
                    removeCallbacks(o.longPress)
                    removeCallbacks(o.repeat)
                    if (o.mode == M_SWIPE && bubbleOwner === o && k != null) ov?.updateBubble(k.label, false)
                    val m = o.mode
                    o.mode = M_DONE
                    if (k != null && !o.emitted) {
                        if (m == M_SWIPE) k.up?.let { host?.onKeyText(k, it) }
                        else if (!(k.code == KeyCode.DELETE && o.repeatCount > 0)) host?.onKey(k)
                    }
                }
                M_ALT -> { ov?.hideAlternatives(); o.mode = M_DONE }
                M_INFO -> { ov?.hideInfo(); o.mode = M_DONE }
                M_CURSOR -> if (o.cursorSteps == 0 && k != null) { o.mode = M_DONE; host?.onKey(k) }
            }
        }
    }

    private fun movePointer(p: Ptr, x: Float, y: Float, t: Long) {
        val m = metrics
        val dx = x - p.downX
        val dy = y - p.downY
        val slop = m.dp(8f)
        when (p.mode) {
            M_SIDE -> {
                val s = side ?: return
                if (!sideDragging && abs(dy) > slop) sideDragging = true
                if (sideDragging) {
                    s.scroll = (sideDownScroll - dy).coerceIn(0f, s.maxScroll())
                    invalidate()
                }
            }
            M_TAP, M_SWIPE -> {
                val k = p.key ?: return
                // 上滑要明显朝上、够长，快速连打时的手指拖动不会把字母变成数字。
                // Swipe-up needs a clearly vertical, long movement so fast typing never turns letters into digits.
                val swipeMin = max(m.dp(SWIPE_MIN_DP), m.keyHeight * SWIPE_MIN_KEY)
                if (k.code == KeyCode.SPACE && abs(dx) > slop && abs(dx) > abs(dy) && host?.cursorDragAllowed() != false) {
                    removeCallbacks(p.longPress)
                    p.mode = M_CURSOR
                    p.cursorAnchor = x
                    p.cursorSteps = 0
                    p.lastMoveT = t; p.lastMoveX = x
                    invalidate()
                    return
                }
                if (k.code == KeyCode.DELETE) {
                    // 只有还没开始连发时，移动才取消连发；连发中手指缓慢漂移很正常，只有左滑清空或抬手结束连发。
                    // Movement only cancels a repeat that hasn't started: once repeating, a slow drift of the thumb is
                    // normal, and only the swipe-left clear or lifting the finger ends it.
                    if (p.repeatCount == 0 && (abs(dx) > slop || abs(dy) > slop)) removeCallbacks(p.repeat)
                    val unit = keyOf('q'.code)?.cell?.width() ?: m.dp(40f)
                    if (dx <= -1.5f * unit) {
                        removeCallbacks(p.longPress)
                        p.mode = M_CLEAR
                        dangerKey = k
                        host?.feedback?.haptic(this)
                        host?.overlay?.let { ov -> ov.map(this, k.rect, tmp); ov.showInfo(tmp, "松手清空") }
                        invalidate()
                    }
                    return
                }
                val up = k.up
                if (up != null && dy <= -swipeMin && -dy > SWIPE_VERTICAL * abs(dx)) {
                    if (p.mode != M_SWIPE) {
                        p.mode = M_SWIPE
                        removeCallbacks(p.longPress)
                        host?.feedback?.haptic(this)
                        if (bubbleOwner === p) host?.overlay?.updateBubble(up, true)
                    }
                } else if (p.mode == M_SWIPE) {
                    p.mode = M_TAP
                    if (bubbleOwner === p) host?.overlay?.updateBubble(k.label, false)
                } else if (abs(dx) > slop || abs(dy) > slop) {
                    removeCallbacks(p.longPress)
                }
            }
            M_CURSOR -> {
                // 速度按事件时间计算，主线程卡顿时不会虚高。 Speed from event time, not processing time.
                val dt = (t - p.lastMoveT).coerceAtLeast(1)
                val speed = abs(x - p.lastMoveX) / m.density / dt * 1000f
                p.lastMoveT = t; p.lastMoveX = x
                val step = m.dp(12f) * (if (speed > 600f) 0.5f else 1f)
                var n = 0
                while (x - p.cursorAnchor >= step) { p.cursorAnchor += step; n++ }
                while (p.cursorAnchor - x >= step) { p.cursorAnchor -= step; n-- }
                if (n != 0) {
                    p.cursorSteps += n
                    host?.feedback?.tick(this)
                    host?.onCursorSteps(n)
                }
            }
            M_CLEAR -> {
                val k = p.key ?: return
                val unit = keyOf('q'.code)?.cell?.width() ?: m.dp(40f)
                if (dx > -1.5f * unit) {
                    p.mode = M_TAP
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

    /** 书写中的移动：连同历史点一起追加，平滑且不分配。 Ink move: historical points too; smooth, no allocation. */
    private fun inkMove(e: MotionEvent, i: Int) {
        val pad = hand ?: return
        val l = pad.rect.left
        val t = pad.rect.top
        for (h in 0 until e.historySize) pad.add(e.getHistoricalX(i, h) - l, e.getHistoricalY(i, h) - t, e.getHistoricalEventTime(h))
        pad.add(e.getX(i) - l, e.getY(i) - t, e.eventTime)
        invalidateInk()
    }

    private fun onLongPress(p: Ptr) {
        val k = p.key ?: return
        if (p.mode != M_TAP) return
        val ov = host?.overlay
        val info = k.longInfo
        if (info != null && ov != null) {
            p.mode = M_INFO
            ov.hideBubble(true)
            if (bubbleOwner === p) bubbleOwner = null
            ov.map(this, k.rect, tmp)
            ov.showInfo(tmp, info)
            host.feedback?.haptic(this)
            return
        }
        val list = k.longPress
        if (k.isChar || k.code == KeyCode.LANG) {
            if (!list.isNullOrEmpty() && ov != null) {
                p.mode = M_ALT
                if (bubbleOwner === p) bubbleOwner = null
                ov.map(this, k.rect, tmp)
                if (k.code == KeyCode.LANG) {
                    val init = list.indexOf(k.label).coerceAtLeast(0)
                    ov.showAlternatives(tmp, list, init, init)
                } else {
                    // 浮层对齐上滑字符，但不预选：不移动就松手仍是这个键本身。
                    // Anchored at the swipe-up char but nothing preselected: lifting in place keeps the key itself.
                    ov.showAlternatives(tmp, list, list.indexOf(k.up).coerceAtLeast(0), -1)
                }
                host.feedback?.haptic(this)
            }
            return
        }
        val r = host?.onLongPressFunc(k) ?: 0
        // 回调里打开了面板时触摸已被复位（cancelTouch），这根手指不再跟踪。 A panel opened in the callback reset the touch.
        if (p.id < 0) return
        when (r) {
            LONG_VOICE -> { p.mode = M_VOICE; invalidate() }
            LONG_CONSUMED -> { p.mode = M_CONSUMED; invalidate() }
        }
    }

    private fun finishPointer(p: Ptr, commit: Boolean) {
        removeCallbacks(p.longPress)
        removeCallbacks(p.repeat)
        val k = p.key
        val ov = host?.overlay
        val m = p.mode
        p.id = -1
        p.key = null
        p.mode = M_NONE
        if (bubbleOwner === p) { ov?.hideBubble(); bubbleOwner = null }
        if (m == M_CLEAR) dangerKey = null
        when (m) {
            M_INK -> {
                inkOwner = null
                val pad = hand
                if (pad != null) {
                    if (commit && pad.end(evTime) != null) host?.onHandStroke(pad.strokes) else pad.cancel()
                }
            }
            M_SIDE -> {
                sideOwner = null
                val s = side
                if (commit && s != null && !sideDragging && s.highlighted in s.items.indices) host?.onSideItem(s.highlighted)
                s?.highlighted = -1
            }
            M_TAP -> if (commit && k != null && !p.emitted && !(k.code == KeyCode.DELETE && p.repeatCount > 0)) host?.onKey(k)
            M_SWIPE -> if (commit && k != null) k.up?.let { if (p.emitted) host?.onKeyReplace(k, it) else host?.onKeyText(k, it) }
            M_ALT -> {
                val sel = ov?.selectedAlternative()
                ov?.hideAlternatives()
                if (commit && k != null && sel != null) {
                    if (p.emitted) host?.onKeyReplace(k, sel) else host?.onKeyText(k, sel)
                }
            }
            M_INFO -> ov?.hideInfo()
            M_CLEAR -> {
                ov?.hideInfo()
                if (commit) host?.onDeleteClear()
            }
            // 横滑没真正移动过光标：按一次空格处理。 A slide that never moved the cursor is a plain space.
            M_CURSOR -> if (commit && k != null && p.cursorSteps == 0) host?.onKey(k)
            M_VOICE -> host?.onVoiceHoldEnd(!commit)
        }
        invalidate()
    }

    /** 面板切换等场合复位触摸。 Reset any in-flight gesture. */
    fun cancelTouch() {
        for (p in ptrs) if (p.id >= 0) finishPointer(p, commit = false)
    }

    companion object {
        /** 左侧列表项的虚拟 id 起点。 Virtual id base for side-list items. */
        private const val SIDE_BASE = 1000
        /** 书写区的虚拟 id。 Virtual id of the handwriting pad. */
        private const val PAD_ID = 999
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
        /** 已输出、不再有手势（另一根手指按下后）。 Emitted, no gesture left (another finger landed). */
        private const val M_DONE = 10
        /** 在书写区写字。 Writing on the handwriting pad. */
        private const val M_INK = 11
        private const val MAX_POINTERS = 4
        const val LONG_PRESS_MS = 450L
        /**
         * 带副标签时字母的垂直中心（键高比例）：贴近键中心，手指瞄准字形时不会偏向下一行。
         * Vertical centre of letters under a hint (fraction of key height): close to the key centre, so aiming at
         * the glyph doesn't pull taps toward the row below.
         */
        private const val LETTER_Y = 0.52f
        /**
         * 交界带：到次近字母键与到所按键的归一化中心距之差小于此值时，把另一侧的字母交给内核纠错
         * （与 core/weave-engine/examples/eval.rs 的模拟一致）。
         * Border band: when the normalised centre distance to the runner-up letter is within this of the pressed
         * key's, the runner-up goes to the engine for correction (matches the simulation in the engine's eval).
         */
        const val NEAR_BAND = 0.45f
        /** 上滑的最小距离：max(28 dp, 0.55 × 键高)，且 |dy| > 1.5 |dx|。 Swipe-up minimum and verticality. */
        const val SWIPE_MIN_DP = 28f
        const val SWIPE_MIN_KEY = 0.55f
        const val SWIPE_VERTICAL = 1.5f
        const val LONG_PRESS_SPACE_MS = 500L
    }
}
