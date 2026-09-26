package com.weavetext.ime.ui.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextUtils
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.widget.OverScroller
import com.weavetext.ime.R
import com.weavetext.ime.style.KeyboardStyle
import com.weavetext.ime.style.LayoutStyle
import com.weavetext.ime.style.ToolIds
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 顶栏回调。 Top bar callbacks. */
interface TopBarHost {
    val feedback: Feedback?
    fun onToolbar(index: Int)
    fun onToolbarLong(index: Int)
    /** 长按后抬起（顶栏 🎙 按住说话）。 Release after a long-press. */
    fun onToolbarLongEnd(index: Int, cancelled: Boolean) {}
    fun onToolbarLongMove(index: Int, dy: Float) {}
    fun onCandidate(index: Int)
    fun onCandidateLong(index: Int)
    fun onExpand()
    fun onNeedMore()
    fun onClipChip()
    fun onHideByDrag()
    /** 组合串浮在栏上方时显示 / 隐藏（null）。 Show or hide (null) the floating composing text. */
    fun onFloatingPreedit(text: String?) {}
}

/**
 * 顶栏：空闲为 6 格工具栏，输入中为「组合串 + 候选行」两行结构（02 §2、04 §2、§4）。
 * Top bar: idle toolbar with six cells, composing = preedit line + candidate row.
 */
@SuppressLint("ViewConstructor")
class TopBarView(ctx: Context, private val host: TopBarHost) : View(ctx) {
    lateinit var palette: KbPalette
    lateinit var metrics: KbMetrics
    lateinit var icons: Icons
    private var layout: LayoutStyle = Layouts.DEFAULT

    // 工具栏 / toolbar：[tools] 为各格的功能编号（ToolIds），[toolIcons] 为对应图标。
    private var tools = intArrayOf(ToolIds.MENU, ToolIds.KEYBOARD, ToolIds.VOICE, ToolIds.CURSOR, ToolIds.CLIPBOARD, ToolIds.HIDE)
    private var toolIcons = IntArray(6) { OUTLINE_ICONS.getValue(tools[it]) }
    /** 当前打开的面板对应的工具编号（高亮）。 Tool id of the open panel (highlighted). */
    var activeTool = -1
        set(v) { field = v; invalidate() }
    private var pressedTool = -1
    private var pressedChip = false
    private val chipRect = RectF()
    private val cellRect = RectF()
    var clipChip: String? = null
        set(v) { field = v; invalidate(); a11y.invalidate() }
    private val a11y = VirtualA11y(this, A11ySource())

    // 候选 / candidates
    private var preedit = ""
    private var preeditShown = ""
    private var english = false
    private var texts: Array<String> = emptyArray()
    private var shown: Array<String> = emptyArray()
    private var comments: Array<String> = emptyArray()
    private var widths = FloatArray(0)
    private var lefts = FloatArray(0)
    private var contentWidth = 0f
    private var total = 0
    private var hasMore = false
    private var scrollX0 = 0f
    private var pressedCand = -1
    var candidateMode = false
        private set
    var expanded = false
        set(v) { field = v; invalidate() }

    // 操作条（撤销、删除用户词）/ action strip
    private var actionMsg: String? = null
    private var actionLabel: String? = null
    private var action: (() -> Unit)? = null
    private val actionTimeout = Runnable { clearAction() }
    private val actionRect = RectF()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val small = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fade = Paint()
    private var fadeShader: LinearGradient? = null
    private val tmp = RectF()
    private val mediumTf = if (android.os.Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 500, false) else Typeface.DEFAULT_BOLD

    private val scroller = OverScroller(ctx)
    private var velocity: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var downScroll = 0f
    private var longFired = false
    private var longTool = -1
    private var cursorOn = true
    private var lastInput = 0L
    private val blink = object : Runnable {
        override fun run() {
            if (!candidateMode || preedit.isEmpty()) return
            cursorOn = if (SystemClock.uptimeMillis() - lastInput > 5000) true else !cursorOn
            invalidate()
            postDelayed(this, 530)
        }
    }
    private val longPress = Runnable {
        longFired = true
        if (pressedCand >= 0) host.onCandidateLong(pressedCand)
        else if (pressedTool >= 0 && !candidateMode) { longTool = tools[pressedTool]; host.onToolbarLong(longTool) }
        pressedCand = -1
        pressedTool = -1
        pressedChip = false
        invalidate()
    }

    fun applyStyle(s: KeyboardStyle, i: Icons) {
        palette = s.palette; metrics = s.metrics; icons = i
        layout = s.layout
        val tb = s.layout.toolbar
        tools = IntArray(tb.items.size) { ToolIds.NAMES.getValue(tb.items[it]) }
        val set = if (tb.icons == "filled") FILLED_ICONS else OUTLINE_ICONS
        toolIcons = IntArray(tools.size) { set.getValue(tools[it]) }
        if (tb.menuIcon == "grid") tools.indexOf(ToolIds.MENU).takeIf { it >= 0 }?.let { toolIcons[it] = R.drawable.ic_toolbox }
        fadeShader = null
        remeasure()
        invalidate()
        a11y.invalidate()
    }

    /** 组合串浮在栏上方。 Composing text floats above the bar. */
    private val floating get() = layout.candidates.preedit == "floating"
    /** 英文三格建议条。 Three-slot English suggestion strip. */
    private val strip get() = english && layout.candidates.english == "strip3"
    /** 输入中左侧常驻工具按钮的宽度。 Width of the leading tool button while composing. */
    private fun leadW() = if (layout.toolbar.show == "always") metrics.dp(44f) else 0f

    /** 第 [i] 个工具格。 Bounds of toolbar cell [i]. */
    private fun toolCell(i: Int, out: RectF) {
        if (layout.toolbar.align == "edges") {
            val w = metrics.dp(52f)
            val left = if (i == 0) 0f else width - (tools.size - i) * w
            out.set(left, 0f, left + w, height.toFloat())
        } else {
            val cell = width / tools.size.toFloat()
            out.set(cell * i, 0f, cell * (i + 1), height.toFloat())
        }
    }

    /** 剪贴板提示条盖住的工具格（均分时为前两格）。 Cells hidden by the clipboard chip. */
    private fun chipHides(i: Int) = layout.toolbar.align != "edges" && i < 2

    private fun toolAt(x: Float): Int {
        for (i in tools.indices) { toolCell(i, cellRect); if (x >= cellRect.left && x < cellRect.right) return i }
        return -1
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), if (::metrics.isInitialized) metrics.topBar.toInt() else 0)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { fadeShader = null; remeasure() }

    /**
     * 设置候选内容。 Set candidates.
     * @param english 英文模式（不显示组合串，候选行垂直居中）。
     */
    fun setCandidates(preedit: String, items: List<String>, comments: List<String>, total: Int, english: Boolean, keepScroll: Boolean) {
        val changed = preedit != this.preedit || items.size != texts.size || items.indices.any { items[it] != texts[it] }
        this.preedit = preedit
        this.english = english
        this.texts = items.toTypedArray()
        this.comments = comments.toTypedArray()
        this.total = total
        candidateMode = preedit.isNotEmpty() || items.isNotEmpty()
        if (floating) host.onFloatingPreedit(if (!english && preedit.isNotEmpty()) preedit else null)
        if (changed && !keepScroll) { scrollX0 = 0f; scroller.forceFinished(true) }
        lastInput = SystemClock.uptimeMillis()
        cursorOn = true
        removeCallbacks(blink)
        if (preedit.isNotEmpty()) postDelayed(blink, 530)
        remeasure()
        invalidate()
        if (changed) a11y.invalidate()
    }

    /** 追加候选（分页）。 Append a page of candidates. */
    fun appendCandidates(items: List<String>, comments: List<String>) {
        texts = texts + items
        this.comments = this.comments + comments
        remeasure()
        invalidate()
        a11y.invalidate()
    }

    val loadedCount get() = texts.size

    fun showAction(msg: String, label: String?, timeoutMs: Long, onAction: (() -> Unit)?) {
        actionMsg = msg
        actionLabel = label
        action = onAction
        removeCallbacks(actionTimeout)
        postDelayed(actionTimeout, timeoutMs)
        invalidate()
        a11y.invalidate()
        // 提示条（已清空、删除用户词…）直接播报。 Announce the action strip.
        if (a11y.enabled) announceForAccessibility(if (label != null) "$msg，$label" else msg)
    }

    fun clearAction() {
        if (actionMsg == null) return
        actionMsg = null; actionLabel = null; action = null
        removeCallbacks(actionTimeout)
        invalidate()
        a11y.invalidate()
    }

    private fun rowTop() = if (english || floating) 0f else metrics.dp(18f) * metrics.topScale
    private fun expandW() = metrics.dp(44f)

    private fun remeasure() {
        if (!::metrics.isInitialized || width == 0) return
        val m = metrics
        text.textSize = m.dp(layout.candidates.textSize) * m.candScale
        small.textSize = m.dp(10f) * m.candScale
        if (strip) { remeasureStrip(); return }
        val maxItem = (width - expandW()) * 0.7f
        val n = texts.size
        widths = FloatArray(n)
        lefts = FloatArray(n)
        shown = Array(n) { i ->
            text.typeface = if (i == 0) mediumTf else Typeface.DEFAULT
            val s = texts[i]
            if (text.measureText(s) > maxItem - m.dp(24f)) {
                TextUtils.ellipsize(s, android.text.TextPaint(text), maxItem - m.dp(24f), TextUtils.TruncateAt.MIDDLE).toString()
            } else s
        }
        var x = 0f
        for (i in 0 until n) {
            text.typeface = if (i == 0) mediumTf else Typeface.DEFAULT
            var w = text.measureText(shown[i]) + m.dp(24f)
            val c = comments.getOrNull(i)
            if (!c.isNullOrEmpty()) w += small.measureText(c) + m.dp(3f)
            w = max(w, m.dp(40f))
            lefts[i] = x
            widths[i] = w
            x += w
        }
        contentWidth = x
        hasMore = total > n || contentWidth > width - leadW() - expandW()
        // 组合串左侧省略。 Ellipsize the preedit from the left.
        small.textSize = m.dp(12.5f) * m.candScale
        val maxPre = width * 0.6f
        preeditShown = if (small.measureText(preedit) > maxPre) {
            var start = 0
            while (start < preedit.length && small.measureText("…" + preedit.substring(start)) > maxPre) start++
            "…" + preedit.substring(start)
        } else preedit
    }

    /** 三格：首选居中，第二、三名在左右；不滚动、无展开。 Strip: best in the middle, no scrolling or expand. */
    private fun remeasureStrip() {
        val m = metrics
        val n = min(3, texts.size)
        val slot = width / 3f
        widths = FloatArray(n) { slot }
        lefts = FloatArray(n) { i -> slot * STRIP_SLOTS[i] }
        shown = Array(n) { i ->
            val s = texts[i]
            if (text.measureText(s) > slot - m.dp(16f)) TextUtils.ellipsize(s, android.text.TextPaint(text), slot - m.dp(16f), TextUtils.TruncateAt.END).toString() else s
        }
        contentWidth = width.toFloat()
        hasMore = false
        preeditShown = ""
    }

    private fun maxScroll() = max(0f, contentWidth - (width - leadW() - (if (hasMore) expandW() else 0f)))

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollX0 = scroller.currX.toFloat()
            checkMore()
            postInvalidateOnAnimation()
        }
    }

    private fun checkMore() {
        if (texts.size < total && scrollX0 > maxScroll() - width * 0.5f) host.onNeedMore()
    }

    // ================================================================ draw

    override fun onDraw(canvas: Canvas) {
        if (!::palette.isInitialized) return
        val msg = actionMsg
        when {
            msg != null -> drawAction(canvas, msg)
            candidateMode -> drawCandidates(canvas)
            else -> drawToolbar(canvas)
        }
    }

    private fun drawToolbar(c: Canvas) {
        val p = palette
        val m = metrics
        val cy = height / 2f
        val chip = clipChip
        val tb = layout.toolbar
        for (i in tools.indices) {
            if (chip != null && chipHides(i)) continue
            toolCell(i, cellRect)
            // 均分时与原实现同一算式，避免取整差一像素。 Same arithmetic as before when spread, to keep pixels stable.
            val cx = if (tb.align == "edges") cellRect.centerX() else width / tools.size.toFloat() * i + width / tools.size.toFloat() / 2
            val on = tools[i] == activeTool || i == pressedTool
            if (tb.buttons == "circle") {
                fill.color = when { tools[i] == activeTool -> p.accentSoft; i == pressedTool -> p.keyPressed; else -> p.key }
                c.drawCircle(cx, cy, m.dp(18f), fill)
            } else if (on) {
                fill.color = p.toolbarActive
                tmp.set(cx - m.dp(20f), cy - m.dp(18f), cx + m.dp(20f), cy + m.dp(18f))
                c.drawRoundRect(tmp, m.dp(10f), m.dp(10f), fill)
            }
            val col = if (tb.menuAccent && tools[i] == ToolIds.MENU) p.keyAccent else p.icon
            icons.draw(c, toolIcons[i], col, cx, cy, m.dp(if (tb.buttons == "circle") 22f else 24f))
        }
        if (chip != null) {
            chipBounds(cy)
            tmp.set(chipRect)
            fill.color = if (pressedChip) p.keyPressed else p.key
            c.drawRoundRect(tmp, m.dp(16f), m.dp(16f), fill)
            icons.draw(c, R.drawable.ic_clipboard, p.keyAccent, tmp.left + m.dp(18f), cy, m.dp(18f))
            small.textSize = m.dp(13f)
            small.color = p.label
            small.typeface = Typeface.DEFAULT
            val avail = tmp.width() - m.dp(40f)
            val n = small.breakText(chip, true, avail, null)
            val s = if (n < chip.length) chip.substring(0, max(0, n - 1)) + "…" else chip
            c.drawText(s, tmp.left + m.dp(32f), cy - (small.ascent() + small.descent()) / 2, small)
        }
    }

    private fun chipBounds(cy: Float) {
        val m = metrics
        if (layout.toolbar.align == "edges") {
            toolCell(0, cellRect)
            val l = cellRect.right + m.dp(4f)
            toolCell(1, cellRect)
            chipRect.set(l, cy - m.dp(16f), cellRect.left - m.dp(4f), cy + m.dp(16f))
        } else {
            val cell = width / tools.size.toFloat()
            chipRect.set(m.dp(8f), cy - m.dp(16f), cell * 2 - m.dp(4f), cy + m.dp(16f))
        }
    }

    private fun drawCandidates(c: Canvas) {
        val p = palette
        val m = metrics
        if (strip) { drawStrip(c); return }
        val lead = leadW()
        if (lead > 0f) icons.draw(c, toolIcons[0], p.icon, lead / 2, height / 2f, m.dp(22f))
        if (!english && preedit.isNotEmpty() && !floating) {
            small.textSize = m.dp(12.5f) * m.candScale
            small.typeface = Typeface.DEFAULT
            small.color = p.labelSecondary
            val x = m.dp(12f) + lead
            val base = m.dp(15f) * m.topScale
            c.drawText(preeditShown, x, base, small)
            if (cursorOn) {
                val cx = x + small.measureText(preeditShown) + m.dp(1f)
                fill.color = p.candidateFirst
                c.drawRect(cx, base - m.dp(10.5f) * m.candScale, cx + m.dp(1f), base + m.dp(1.5f), fill)
            }
        }
        val top = rowTop()
        val rowH = height - top
        val right = width - (if (hasMore) expandW() else 0f)
        c.save()
        c.clipRect(lead, top, right, height.toFloat())
        text.textSize = m.dp(layout.candidates.textSize) * m.candScale
        small.textSize = m.dp(10f) * m.candScale
        val base = top + rowH / 2 - (text.ascent() + text.descent()) / 2
        for (i in shown.indices) {
            val l = lead + lefts[i] - scrollX0
            if (l > right) break
            if (l + widths[i] < lead) continue
            val pill = i == 0 && p.candidatePill
            if (i == pressedCand || pill) {
                fill.color = if (pill && i != pressedCand) p.candidatePillColor else p.toolbarActive
                val h = min(m.dp(30f), rowH)
                tmp.set(l + (if (pill) m.dp(4f) else 0f), top + (rowH - h) / 2, l + widths[i] - (if (pill) m.dp(4f) else 0f), top + (rowH + h) / 2)
                c.drawRoundRect(tmp, if (pill) h / 2 else m.dp(8f), if (pill) h / 2 else m.dp(8f), fill)
            }
            if (layout.candidates.dividers && i > 0) {
                fill.color = p.divider
                c.drawRect(l - m.dp(0.5f), top + rowH * 0.3f, l + m.dp(0.5f), top + rowH * 0.7f, fill)
            }
            text.typeface = if (i == 0) mediumTf else Typeface.DEFAULT
            text.color = if (i == 0) p.candidateFirst else p.label
            c.drawText(shown[i], l + m.dp(12f), base, text)
            val cm = comments.getOrNull(i)
            if (!cm.isNullOrEmpty()) {
                small.color = p.labelHint
                c.drawText(cm, l + m.dp(12f) + text.measureText(shown[i]) + m.dp(3f), base, small)
            }
        }
        c.restore()
        if (hasMore) {
            // 渐隐遮罩 + 展开按钮。 Fade + expand button.
            val fw = m.dp(16f)
            if (fadeShader == null) {
                fadeShader = LinearGradient(0f, 0f, fw, 0f, p.background and 0x00ffffff, p.background, Shader.TileMode.CLAMP)
            }
            fade.shader = fadeShader
            c.save()
            c.translate(right - fw, 0f)
            c.drawRect(0f, top, fw, height.toFloat(), fade)
            c.restore()
            fill.color = p.background
            c.drawRect(right, 0f, width.toFloat(), height.toFloat(), fill)
            val expandIcon = when {
                layout.candidates.expandIcon == "grid" -> R.drawable.ic_toolbox
                expanded -> R.drawable.ic_chevron_up
                else -> R.drawable.ic_chevron_down
            }
            icons.draw(c, expandIcon, if (expanded && layout.candidates.expandIcon == "grid") p.keyAccent else p.icon, right + expandW() / 2, top + rowH / 2, m.dp(22f))
        }
    }

    private fun drawStrip(c: Canvas) {
        val p = palette
        val m = metrics
        val rowH = height.toFloat()
        text.textSize = m.dp(layout.candidates.textSize) * m.candScale
        val base = rowH / 2 - (text.ascent() + text.descent()) / 2
        text.textAlign = Paint.Align.CENTER
        for (i in shown.indices) {
            val l = lefts[i]
            if (i == pressedCand) {
                fill.color = p.toolbarActive
                val h = min(m.dp(34f), rowH)
                tmp.set(l + m.dp(4f), (rowH - h) / 2, l + widths[i] - m.dp(4f), (rowH + h) / 2)
                c.drawRoundRect(tmp, h / 2, h / 2, fill)
            }
            text.typeface = if (i == 0) mediumTf else Typeface.DEFAULT
            text.color = p.label
            c.drawText(shown[i], l + widths[i] / 2, base, text)
        }
        fill.color = p.divider
        for (k in 1..2) {
            val x = width * k / 3f
            c.drawRect(x - m.dp(0.5f), rowH * 0.3f, x + m.dp(0.5f), rowH * 0.7f, fill)
        }
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawAction(c: Canvas, msg: String) {
        val p = palette
        val m = metrics
        small.textSize = m.dp(13f)
        small.typeface = Typeface.DEFAULT
        val label = actionLabel
        val lw = if (label != null) small.measureText(label) + m.dp(28f) else 0f
        val mw = small.measureText(msg)
        val w = mw + m.dp(32f) + lw
        val cy = height / 2f
        tmp.set(width / 2f - w / 2, cy - m.dp(16f), width / 2f + w / 2, cy + m.dp(16f))
        fill.color = p.popup
        c.drawRoundRect(tmp, m.dp(16f), m.dp(16f), fill)
        small.color = p.label
        val base = cy - (small.ascent() + small.descent()) / 2
        c.drawText(msg, tmp.left + m.dp(16f), base, small)
        if (label != null) {
            small.color = p.candidateFirst
            small.typeface = mediumTf
            c.drawText(label, tmp.right - lw + m.dp(8f), base, small)
            actionRect.set(tmp.right - lw, 0f, tmp.right + m.dp(8f), height.toFloat())
        } else actionRect.setEmpty()
    }

    // ================================================================ accessibility

    override fun getAccessibilityNodeProvider(): android.view.accessibility.AccessibilityNodeProvider = a11y

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = a11y.onHover(event) || super.dispatchHoverEvent(event)

    private inner class A11ySource : VirtualA11y.Source {
        private fun candRight() = width - (if (hasMore) expandW() else 0f)
        private fun toolIndex(id: Int) = tools.indexOf(id)

        override fun a11yIds(): IntArray = when {
            !::metrics.isInitialized -> IntArray(0)
            actionMsg != null -> if (actionLabel != null) intArrayOf(ACTION_MSG, ACTION_BUTTON) else intArrayOf(ACTION_MSG)
            candidateMode -> {
                val lead = if (leadW() > 0f && !strip) intArrayOf(tools[0]) else IntArray(0)
                val first = if (!english && preedit.isNotEmpty() && !floating) intArrayOf(PREEDIT) else IntArray(0)
                lead + first + IntArray(shown.size) { CAND_BASE + it } + (if (hasMore) intArrayOf(EXPAND) else IntArray(0))
            }
            clipChip != null -> intArrayOf(CHIP) + tools.filterIndexed { i, _ -> !chipHides(i) }.toIntArray()
            else -> tools.copyOf()
        }

        override fun a11yBounds(id: Int, out: RectF): Boolean {
            val m = metrics
            when {
                id == ACTION_MSG -> out.set(0f, 0f, if (actionLabel != null) actionRect.left else width.toFloat(), height.toFloat())
                id == ACTION_BUTTON -> out.set(actionRect)
                id == PREEDIT -> out.set(leadW(), 0f, leadW() + width * 0.6f + m.dp(24f), rowTop())
                id == EXPAND -> out.set(candRight(), 0f, width.toFloat(), height.toFloat())
                id >= CAND_BASE -> {
                    val i = id - CAND_BASE
                    if (i !in shown.indices) return false
                    val l = (if (strip) 0f else leadW()) + lefts[i] - (if (strip) 0f else scrollX0)
                    out.set(max(if (strip) 0f else leadW(), l), rowTop(), min(candRight(), l + widths[i]), height.toFloat())
                }
                id == CHIP -> { chipBounds(height / 2f); out.set(if (layout.toolbar.align == "edges") chipRect.left else 0f, 0f, chipRect.right + m.dp(4f), height.toFloat()) }
                candidateMode && id == tools[0] -> out.set(0f, 0f, leadW(), height.toFloat())
                toolIndex(id) >= 0 -> toolCell(toolIndex(id), out)
                else -> return false
            }
            return out.width() > 1f && out.height() > 1f
        }

        override fun a11yLabel(id: Int): CharSequence? = when {
            id == ACTION_MSG -> actionMsg
            id == ACTION_BUTTON -> actionLabel
            id == PREEDIT -> "输入码 $preedit"
            id == EXPAND -> if (expanded) "收起候选" else "展开更多候选"
            id >= CAND_BASE -> texts.getOrNull(id - CAND_BASE)?.let { t ->
                val c = comments.getOrNull(id - CAND_BASE)
                if (c.isNullOrEmpty()) t else "$t，$c"
            }
            id == CHIP -> "粘贴最近复制：${clipChip.orEmpty()}"
            else -> TOOL_NAMES.getOrNull(id)
        }

        override fun a11yState(id: Int): CharSequence? = if (id < CHIP && id == activeTool) "已打开" else null

        override fun a11yClick(id: Int): Boolean {
            when {
                id == ACTION_BUTTON -> { val a = action; clearAction(); a?.invoke() }
                id == ACTION_MSG || id == PREEDIT -> return false
                id == EXPAND -> host.onExpand()
                id >= CAND_BASE -> { if (id - CAND_BASE !in texts.indices) return false; host.onCandidate(id - CAND_BASE) }
                id == CHIP -> host.onClipChip()
                id < CHIP -> host.onToolbar(id)
                else -> return false
            }
            return true
        }

        override fun a11yLongClickLabel(id: Int): CharSequence? = when {
            id == ToolIds.MENU && clipChip == null && !candidateMode -> "打开设置"
            id >= CAND_BASE -> "删除用户词"
            else -> null
        }

        override fun a11yLongClick(id: Int): Boolean {
            when {
                id == ToolIds.MENU -> host.onToolbarLong(ToolIds.MENU)
                id >= CAND_BASE -> host.onCandidateLong(id - CAND_BASE)
                else -> return false
            }
            return true
        }

        override fun a11yLiftToActivate(id: Int) = id >= CAND_BASE

        override fun a11yCanScroll(forward: Boolean) = candidateMode && actionMsg == null &&
            (if (forward) scrollX0 < maxScroll() - 1f || texts.size < total else scrollX0 > 0f)

        override fun a11yScroll(forward: Boolean): Boolean {
            if (!a11yCanScroll(forward)) return false
            val page = candRight() * 0.8f
            scrollX0 = (scrollX0 + if (forward) page else -page).coerceIn(0f, maxScroll())
            checkMore()
            invalidate()
            a11y.invalidate()
            return true
        }
    }

    // ================================================================ touch

    private fun candAt(x: Float): Int {
        val xx = if (strip) x else x - leadW() + scrollX0
        for (i in lefts.indices) if (xx >= lefts[i] && xx < lefts[i] + widths[i]) return i
        return -1
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!::metrics.isInitialized) return false
        val m = metrics
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                dragging = false
                longFired = false
                longTool = -1
                downScroll = scrollX0
                scroller.forceFinished(true)
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                if (actionMsg != null) return true
                if (candidateMode) {
                    val right = width - (if (hasMore) expandW() else 0f)
                    if (e.x >= right) return true
                    if (!strip && e.x < leadW()) { pressedTool = 0; invalidate(); return true }
                    if (e.y >= rowTop() || english) {
                        pressedCand = candAt(e.x)
                        if (pressedCand >= 0) {
                            host.feedback?.key(this)
                            postDelayed(longPress, 400)
                        }
                    }
                } else {
                    pressedTool = toolAt(e.x)
                    if (clipChip != null) {
                        chipBounds(height / 2f)
                        val inChip = if (layout.toolbar.align == "edges") e.x >= chipRect.left && e.x <= chipRect.right else pressedTool in 0..1
                        if (inChip) { pressedChip = true; pressedTool = -1 }
                    }
                    if (pressedTool < 0 && !pressedChip) return true
                    host.feedback?.key(this)
                    postDelayed(longPress, 400)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                val dx = e.x - downX
                val dy = e.y - downY
                if (longTool >= 0) { host.onToolbarLongMove(longTool, dy); return true }
                if (candidateMode && actionMsg == null && pressedTool < 0) {
                    if (strip) return true
                    if (!dragging && abs(dx) > m.dp(8f)) {
                        dragging = true
                        removeCallbacks(longPress)
                        pressedCand = -1
                    }
                    if (dragging) {
                        scrollX0 = (downScroll - dx).coerceIn(0f, maxScroll())
                        checkMore()
                        invalidate()
                    }
                } else if (!candidateMode) {
                    if (abs(dx) > m.dp(8f) || abs(dy) > m.dp(8f)) {
                        removeCallbacks(longPress)
                        if (pressedTool >= 0 || pressedChip) { pressedTool = -1; pressedChip = false; invalidate() }
                    }
                    if (dy > m.dp(80f)) { downY = Float.MAX_VALUE / 2; host.onHideByDrag() }
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                velocity?.addMovement(e)
                if (longFired) {
                    longFired = false
                    if (longTool >= 0) host.onToolbarLongEnd(longTool, false)
                    longTool = -1
                    return true
                }
                if (actionMsg != null) {
                    if (actionRect.contains(e.x, e.y)) { val a = action; clearAction(); a?.invoke() }
                    return true
                }
                if (candidateMode && pressedTool >= 0) {
                    pressedTool = -1
                    host.onToolbar(tools[0])
                } else if (candidateMode) {
                    if (dragging) {
                        velocity?.computeCurrentVelocity(1000)
                        val vx = velocity?.xVelocity ?: 0f
                        scroller.fling(scrollX0.toInt(), 0, -vx.toInt(), 0, 0, maxScroll().toInt(), 0, 0)
                        postInvalidateOnAnimation()
                    } else {
                        val right = width - (if (hasMore) expandW() else 0f)
                        if (e.x >= right && hasMore) host.onExpand()
                        else if (pressedCand >= 0) host.onCandidate(pressedCand)
                    }
                    pressedCand = -1
                } else if (pressedChip) {
                    pressedChip = false
                    if (clipChip != null) host.onClipChip()
                } else if (pressedTool >= 0) {
                    val t = pressedTool
                    pressedTool = -1
                    host.onToolbar(tools[t])
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (longTool >= 0) host.onToolbarLongEnd(longTool, true)
                longTool = -1
                pressedCand = -1; pressedTool = -1; pressedChip = false
                invalidate()
            }
        }
        return true
    }

    companion object {
        private val TOOL_NAMES = arrayOf("工具箱", "切换键盘", "语音输入", "光标与编辑", "剪贴板", "收起键盘", "表情", "设置")
        /** 三格建议条中第 i 名所在的格。 Slot of the i-th suggestion in the strip. */
        private val STRIP_SLOTS = intArrayOf(1, 0, 2)
        private val OUTLINE_ICONS = mapOf(
            ToolIds.MENU to R.drawable.ic_logo, ToolIds.KEYBOARD to R.drawable.ic_keyboard, ToolIds.VOICE to R.drawable.ic_mic,
            ToolIds.CURSOR to R.drawable.ic_cursor, ToolIds.CLIPBOARD to R.drawable.ic_clipboard, ToolIds.HIDE to R.drawable.ic_chevron_down,
            ToolIds.EMOJI to R.drawable.ic_emoji, ToolIds.SETTINGS to R.drawable.ic_settings,
        )
        private val FILLED_ICONS = mapOf(
            ToolIds.MENU to R.drawable.ic_logo_filled, ToolIds.KEYBOARD to R.drawable.ic_keyboard_filled, ToolIds.VOICE to R.drawable.ic_mic_filled,
            ToolIds.CURSOR to R.drawable.ic_cursor_filled, ToolIds.CLIPBOARD to R.drawable.ic_clipboard_filled, ToolIds.HIDE to R.drawable.ic_chevron_down_filled,
            ToolIds.EMOJI to R.drawable.ic_emoji_filled, ToolIds.SETTINGS to R.drawable.ic_settings_filled,
        )
        private const val CHIP = 10
        private const val PREEDIT = 11
        private const val EXPAND = 12
        private const val ACTION_MSG = 13
        private const val ACTION_BUTTON = 14
        private const val CAND_BASE = 100
    }
}
