package com.weavetext.ime.ui.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
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
import com.weavetext.ime.core.Candidate
import com.weavetext.ime.core.PreeditMark
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
    /** 工具长按；返回 false 表示没有长按功能，这次按下仍按点击处理。 Tool long-press; false = none, the press stays a tap. */
    fun onToolbarLong(index: Int): Boolean
    /** 长按后抬起（顶栏 🎙 按住说话）。 Release after a long-press. */
    fun onToolbarLongEnd(index: Int, cancelled: Boolean) {}
    fun onToolbarLongMove(index: Int, dy: Float) {}
    fun onCandidate(index: Int)
    /** 点按或拖过组合串：把光标放到 preedit 第 [index] 个字符处。 A tap or drag on the preedit: caret to char [index]. */
    fun onPreeditTap(index: Int) {}
    /** 候选长按；返回 false 表示没有长按功能，这次按下仍按点击处理。 Candidate long-press; false = none, still a tap. */
    fun onCandidateLong(index: Int): Boolean
    fun onExpand()
    /** 点联想词右侧的叉：清掉候选栏。 The cross beside predictions: clear the bar. */
    fun onDismissCandidates() {}
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
    private var tools = intArrayOf(ToolIds.MENU, ToolIds.KEYBOARD, ToolIds.VOICE, ToolIds.CURSOR, ToolIds.CLIPBOARD, ToolIds.STICKERS, ToolIds.HIDE)
    private var toolIcons = IntArray(7) { OUTLINE_ICONS.getValue(tools[it]) }
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
    private var marks: List<PreeditMark> = emptyList()
    /** [preeditShown] 每个字符的样式，见 [styledPreedit]。 Per-character style of [preeditShown], see [styledPreedit]. */
    private var preeditStyle = ByteArray(0)
    /** 组合串光标（preedit 下标），null 在末尾。 Preedit caret (index), null at the end. */
    private var caret: Int? = null
    /** 左侧省略时从显示串第几个字符开始（-1 = 没省略）。 Where the left-ellipsized text starts (-1 = not cut). */
    private var preeditCut = -1
    /** 组合串画在哪里（点按定位光标用）。 Where the preedit was drawn, for caret taps. */
    private var preeditLeft = 0f
    private var preeditRight = 0f
    private var pressedPreedit = false
    private var preeditTapAt = -1
    private val markLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val markPath = Path()
    private var english = false
    /**
     * 这一栏是本地候选列表（九键标点、计算结果），不是英文联想词。它借 [english] 换取「不显示组合串、
     * 候选行居中」，但那不等于英文三格建议条：后者只显示三个，本地列表要能滚动看全。
     * This bar holds a local candidate list (9-key punctuation, a calculator result), not English suggestions. It borrows
     * [english] for "no preedit, centred row", but that must not turn it into the three-slot English strip: a local list
     * has to stay scrollable so every entry is reachable.
     */
    private var localTexts = false
    // 缓冲区复用，只增不减；只测量到可见范围再多一屏，滚动时再补。
    // Buffers are reused and only grow; only the visible range plus one screen is measured, the rest on scroll.
    private val texts = ArrayList<String>(64)
    private val comments = ArrayList<String>(64)
    private val pinyins = ArrayList<String>(64)
    private val cloudBadges = ArrayList<Boolean>(64)
    var cloudLoading=false
        set(value) {if(field!=value){field=value;invalidate()}}
    private val cloudSpinner=Paint(Paint.ANTI_ALIAS_FLAG).apply {style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND}
    private var shown = arrayOfNulls<String>(64)
    private var widths = FloatArray(64)
    private var lefts = FloatArray(64)
    /** 已测量的候选数。 Candidates measured so far. */
    private var measured = 0
    private var contentWidth = 0f
    private var total = 0
    private var hasMore = false
    private var scrollX0 = 0f
    private var pressedCand = -1
    private var highlightedCandidate = -1
    private var candidateGeneration = 0L
    private val focusedCandidate get() = highlightedCandidate.takeIf { it in texts.indices } ?: 0
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
    private val text = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).zh()
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).zh()
    private val annotation = Paint(Paint.ANTI_ALIAS_FLAG).zh()
    /** 候选右端渐隐：在图层里擦去文字（DST_OUT），透出真实背景。 Right-edge fade erases the text in a layer, showing the real backdrop. */
    private val fade = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT) }
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
    /** 这次按下只用来收起提示条。 This touch only dismissed the action strip. */
    private var swallowed = false
    private var cursorOn = true
    private var lastInput = 0L
    /** 键盘收起时停掉光标闪烁，别每 530 ms 唤醒主线程。 Stop blinking while hidden instead of waking every 530 ms. */
    fun stopBlink() = removeCallbacks(blink)
    private val blink = object : Runnable {
        override fun run() {
            if (!candidateMode || preedit.isEmpty()) return
            cursorOn = if (SystemClock.uptimeMillis() - lastInput > 5000) true else !cursorOn
            invalidate()
            postDelayed(this, 530)
        }
    }
    private val longPress = Runnable {
        val handled = when {
            pressedCand >= 0 -> host.onCandidateLong(pressedCand)
            pressedTool >= 0 && !candidateMode -> {
                val t = tools[pressedTool]
                longTool = t
                host.onToolbarLong(t).also { if (!it) longTool = -1 }
            }
            else -> false
        }
        // 没有长按功能（普通候选、大多数工具）：保持按下，慢慢按的一下松手时照常生效。
        // Nothing to do on a long press (ordinary candidates, most tools): keep the press so a slow tap still works.
        if (!handled) return@Runnable
        longFired = true
        pressedCand = -1
        pressedTool = -1
        pressedChip = false
        invalidate()
    }

    /** [items]：用户在设置里排好的工具栏（默认取风格自带）。 [items]: the toolbar as arranged in settings. */
    fun applyStyle(s: KeyboardStyle, i: Icons, items: List<String> = s.layout.toolbar.items) {
        palette = s.palette; metrics = s.metrics; icons = i
        layout = s.layout
        val tb = s.layout.toolbar
        val names = items.filter { it in ToolIds.NAMES }
        tools = IntArray(names.size) { ToolIds.NAMES.getValue(names[it]) }
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
    private val strip get() = english && !localTexts && layout.candidates.english == "strip3" && highlightedCandidate < 3
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
     * 设置候选内容（内核候选）。 Set engine candidates.
     * @param english 英文模式（不显示组合串，候选行垂直居中）。
     */
    fun setCandidates(
        preedit: String, items: List<Candidate>, total: Int, english: Boolean, keepScroll: Boolean,
        marks: List<PreeditMark> = emptyList(),
        generation: Long = 0,
        caret: Int? = null,
    ) {
        val marksChanged = marks != this.marks || caret != this.caret
        this.marks = marks
        this.caret = caret
        localTexts = false
        var changed = generation != candidateGeneration || preedit != this.preedit || items.size != texts.size
        candidateGeneration = generation
        if (!changed) for (i in items.indices) if (items[i].text != texts[i] || items[i].comment != comments[i] || items[i].pinyin != pinyins[i] || items[i].isCloud != cloudBadges.getOrElse(i){false}) { changed = true; break }
        texts.clear(); comments.clear(); pinyins.clear(); cloudBadges.clear()
        for (i in items.indices) { texts += items[i].text; comments += items[i].comment;pinyins += items[i].pinyin;cloudBadges += items[i].isCloud }
        apply(preedit, total, english, keepScroll, changed, marksChanged)
    }

    /** 设置纯文字候选（本地标点列表、预览）。 Set plain text candidates (local lists, previews). */
    fun setCandidateTexts(preedit: String, items: List<String>, total: Int, english: Boolean, keepScroll: Boolean) {
        val marksChanged = marks.isNotEmpty() || caret != null
        marks = emptyList()
        caret = null
        localTexts = true
        val changed = preedit != this.preedit || items != texts
        texts.clear(); comments.clear(); pinyins.clear(); cloudBadges.clear()
        for (t in items) { texts += t; comments += "";pinyins += "";cloudBadges += false }
        apply(preedit, total, english, keepScroll, changed, marksChanged)
    }

    private fun apply(preedit: String, total: Int, english: Boolean, keepScroll: Boolean, changed: Boolean, marksChanged: Boolean) {
        // 标记变了也要重排组合串。 Changed marks also re-lay the preedit out.
        val preeditChanged = preedit != this.preedit || marksChanged
        this.preedit = preedit
        this.english = english
        this.total = total
        val wasCandidates = candidateMode
        candidateMode = preedit.isNotEmpty() || texts.isNotEmpty()
        // 按住工具按钮时栏切成了候选（如手写结果晚到）：这次按下作废，否则松手会打开别的工具。
        // The bar switched mode under a pressed tool (e.g. a late handwriting result): drop the press, or the
        // release would open a different tool.
        if (wasCandidates != candidateMode && (pressedTool >= 0 || pressedChip)) {
            removeCallbacks(longPress); pressedTool = -1; pressedChip = false
        }
        // 不浮动时也通知一次（样式从浮动切回行内时收起浮条）。 Notify when inline too, so switching styles drops the chip.
        host.onFloatingPreedit(if (floating && !english && preedit.isNotEmpty()) preedit else null)
        if (changed && !keepScroll) { scrollX0 = 0f; scroller.forceFinished(true) }
        // 按住时候选变了：原位置已是别的词，这次按下作废。 Candidates changed under the finger: that press no longer applies.
        if (changed && pressedCand >= 0) { removeCallbacks(longPress); pressedCand = -1 }
        lastInput = SystemClock.uptimeMillis()
        cursorOn = true
        removeCallbacks(blink)
        if (preedit.isNotEmpty()) postDelayed(blink, 530)
        remeasure(preeditChanged)
        // 保留滚动位置时，候选可能变少了（翻页加载的已丢掉）：夹回范围内，否则栏里一片空白。
        // A kept scroll offset may now be past a shorter list (loaded pages are dropped): clamp it, or the bar goes blank.
        if (scrollX0 > 0f && !strip) scrollX0 = scrollX0.coerceIn(0f, maxScroll())
        invalidate()
        if (changed) a11y.invalidate()
    }

    /** 追加候选（分页）。 Append a page of candidates. */
    fun appendCandidates(items: List<Candidate>) {
        for (c in items) { texts += c.text; comments += c.comment;pinyins += c.pinyin;cloudBadges += c.isCloud }
        hasMore = total > texts.size || measured < texts.size || contentWidth > width - leadW() - expandW()
        ensureMeasured(scrollX0 + width * 2f)
        invalidate()
        a11y.invalidate()
    }

    val loadedCount get() = texts.size

    /** Keep the keyboard-selected word visible, including after loading another candidate page. */
    fun setHighlightedCandidate(index: Int) {
        if (highlightedCandidate == index) return
        highlightedCandidate = index
        remeasure(false)
        if (index in texts.indices && ::metrics.isInitialized && width > 0 && !strip) {
            while (measured <= index && measured < texts.size) ensureMeasured(contentWidth + width)
            scroller.forceFinished(true)
            val available = width - leadW() - (if (sideButton) expandW() else 0f)
            val l = lefts[index]
            val r = l + widths[index]
            scrollX0 = when {
                l < scrollX0 -> l
                r > scrollX0 + available -> r - available
                else -> scrollX0
            }.coerceIn(0f, maxScroll())
        }
        invalidate()
        a11y.invalidate()
    }
    /** 第 [i] 个候选的文字（测试用）。 Candidate text at [i] (for tests). */
    @androidx.annotation.VisibleForTesting
    fun candidateAt(i: Int): String? = texts.getOrNull(i)
    @androidx.annotation.VisibleForTesting
    fun candidatePinyinAt(i: Int): String? = pinyins.getOrNull(i)
    /** 第 [i] 个候选画出来的括号拼音（测试用）。 Bracketed pinyin drawn after candidate [i] (for tests). */
    @androidx.annotation.VisibleForTesting
    fun candidateHintAt(i: Int): String? = if (i in pinyins.indices) hintAt(i) else null
    /** 已测量（可绘制）的候选数。 Candidates measured so far. */
    val measuredCount get() = measured

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

    /** 正在显示的提示条文字。 The message of the action strip being shown. */
    val actionMessage: String? get() = actionMsg

    fun clearAction() {
        if (actionMsg == null) return
        actionMsg = null; actionLabel = null; action = null
        removeCallbacks(actionTimeout)
        invalidate()
        a11y.invalidate()
    }

    /** 候选行的上沿：只有中文且有组合串时才给组合串留一行（联想词、本地列表整行居中）。 Top of the candidate row. */
    /**
     * 中文候选行固定在组合串下面，联想词（没有组合串）也放在同一位置，打字和上屏后词不会上下跳。
     * Chinese candidates sit below the preedit line; predictions (no preedit) keep that same spot, so words don't
     * jump up and down between typing and after a commit.
     */
    private fun rowTop() = if (english || floating) 0f else metrics.dp(18f) * metrics.topScale

    /**
     * 首项是否突出显示：有组合串（它就是空格会上屏的那个）或英文时突出；联想词没有「默认」项，一律平等。
     * Whether the first item stands out: with a preedit (it's what space commits) or in English; predictions have no default.
     */
    private val firstStandsOut get() = highlightedCandidate >= 0 || english || preedit.isNotEmpty()

    /** 只有正在选的首选带拼音，括号标在词后。 Only the top candidate being picked carries its pinyin, in brackets after it. */
    private fun hintAt(i: Int): String? = pinyins[i].takeIf { i == focusedCandidate && !english && preedit.isNotEmpty() && it.isNotEmpty() }?.let { "($it)" }
    private fun expandW() = metrics.dp(44f)

    /** 联想词（没有组合串）右侧用叉代替下拉，点了清空。 Predictions (no preedit) get a cross instead of the dropdown. */
    private val dismissable get() = !strip && !expanded && preedit.isEmpty() && texts.isNotEmpty()
    /** 右端留出按钮位：展开或叉。 Room at the right end for the expand button or the cross. */
    private val sideButton get() = hasMore || dismissable

    private fun remeasure(preeditChanged: Boolean = true) {
        if (!::metrics.isInitialized || width == 0) return
        val m = metrics
        val n = texts.size
        if (shown.size < n) {
            val cap = maxOf(n, shown.size * 2)
            shown = shown.copyOf(cap)
            widths = widths.copyOf(cap)
            lefts = lefts.copyOf(cap)
        }
        if (strip) { remeasureStrip(); return }
        measured = 0
        contentWidth = 0f
        ensureMeasured(scrollX0 + width * 2f)
        if (!preeditChanged) return
        // 组合串左侧省略（只在组合串变化时算）。 Ellipsize the preedit from the left, only when it changed.
        small.textSize = m.dp(12.5f) * m.candScale
        val maxPre = width * 0.6f
        val (disp, style) = styledPreedit(preedit, marks)
        if (small.measureText(disp) > maxPre) {
            // 光标在中间时省略要留住光标所在处。 With the caret inside, keep the spot around it visible.
            val keep = caret?.let { (styledIndex(preedit, marks, it) - 4).coerceAtLeast(0) } ?: disp.length
            var start = 0
            while (start < disp.length && start < keep && small.measureText("…" + disp.substring(start)) > maxPre) start++
            var end = disp.length
            if (small.measureText("…" + disp.substring(start)) > maxPre) {
                while (end > start + 1 && small.measureText("…" + disp.substring(start, end) + "…") > maxPre) end--
            }
            val tail = if (end < disp.length) "…" else ""
            preeditShown = "…" + disp.substring(start, end) + tail
            preeditStyle = byteArrayOf(STYLE_PLAIN) + style.copyOfRange(start, end) + (if (tail.isEmpty()) ByteArray(0) else byteArrayOf(STYLE_PLAIN))
            preeditCut = start
        } else {
            preeditShown = disp
            preeditStyle = style
            preeditCut = -1
        }
    }

    /**
     * 画组合串，纠错处用红色：补上 / 换掉的字母下划线，换回来的两个字母上方一道弧线，去掉的多打字母
     * 划掉。返回画完的右端。
     * Draw the preedit with corrections in red: added / replaced letters underlined, swapped-back pairs with an arc
     * over them, dropped extra letters struck through. Returns the right end.
     */
    private fun drawPreedit(c: Canvas, x: Float, base: Float): Float {
        val p = palette
        val m = metrics
        val s = preeditShown
        val st = preeditStyle
        markLine.color = p.danger
        markLine.strokeWidth = m.dp(1.2f)
        val em = small.textSize
        var cx = x
        var i = 0
        while (i < s.length) {
            val k = st.getOrElse(i) { STYLE_PLAIN }
            var j = i + 1
            while (j < s.length && st.getOrElse(j) { STYLE_PLAIN } == k && k != STYLE_SWAP) j++
            if (k == STYLE_SWAP) j = min(i + 2, s.length)
            val w = small.measureText(s, i, j)
            small.color = if (k == STYLE_PLAIN) p.labelSecondary else p.danger
            c.drawText(s, i, j, cx, base, small)
            when (k) {
                STYLE_FIXED -> c.drawLine(cx + m.dp(0.5f), base + m.dp(2.5f), cx + w - m.dp(0.5f), base + m.dp(2.5f), markLine)
                STYLE_REMOVED -> c.drawLine(cx, base - em * 0.3f, cx + w, base - em * 0.3f, markLine)
                STYLE_SWAP -> {
                    // 两个换回来的字母上方一道弧线，两端下弯。 An arc over the swapped pair, both ends bent down.
                    val l = cx + m.dp(0.5f)
                    val r = cx + w - m.dp(0.5f)
                    val top = base - em * 0.86f
                    markPath.reset()
                    markPath.moveTo(l, top + m.dp(2.5f))
                    markPath.cubicTo(l, top - m.dp(1.5f), r, top - m.dp(1.5f), r, top + m.dp(2.5f))
                    c.drawPath(markPath, markLine)
                }
            }
            cx += w
            i = j
        }
        return cx
    }

    /** 光标在 [preeditShown] 里的下标；在末尾或被省略掉时为 null。 Caret index in [preeditShown]; null at the end or when cut off. */
    private fun caretShownAt(): Int? {
        val c = caret ?: return null
        val d = styledIndex(preedit, marks, c)
        val i = if (preeditCut < 0) d else if (d < preeditCut) return null else d - preeditCut + 1
        return i.coerceAtMost(preeditShown.length)
    }

    /** 横向位置 [x] 换成最近的 preedit 下标交给宿主（同一处不重复通知）。 Nearest preedit index to [x], sent once per spot. */
    private fun tapPreedit(x: Float) {
        val s = preeditShown
        small.textSize = metrics.dp(12.5f) * metrics.candScale
        small.typeface = Typeface.DEFAULT
        var best = 0
        var bestD = Float.MAX_VALUE
        for (i in 0..s.length) {
            val d = abs(preeditLeft + small.measureText(s, 0, i) - x)
            if (d < bestD) { bestD = d; best = i }
        }
        val styled = if (preeditCut < 0) best else (best - 1).coerceAtLeast(0) + preeditCut
        val index = (0..preedit.length).minByOrNull { abs(styledIndex(preedit, marks, it) - styled) } ?: preedit.length
        if (index == preeditTapAt) return
        preeditTapAt = index
        host.onPreeditTap(index)
    }

    /** 测量候选直到内容宽度超过 [x]。 Measure candidates until the content reaches [x]. */
    private fun ensureMeasured(x: Float) {
        if (!::metrics.isInitialized || width == 0 || strip) return
        val m = metrics
        val n = texts.size
        if (shown.size < n) {
            val cap = maxOf(n, shown.size * 2)
            shown = shown.copyOf(cap)
            widths = widths.copyOf(cap)
            lefts = lefts.copyOf(cap)
        }
        if (measured >= n || contentWidth > x) { hasMore = total > n || measured < n || contentWidth > width - leadW() - expandW(); return }
        text.textSize = m.dp(layout.candidates.textSize) * m.candScale
        small.textSize = m.dp(10f) * m.candScale
        annotation.textSize = m.dp(13f) * m.candScale
        val maxItem = (width - expandW()) * 0.7f - m.dp(42f)
        while (measured < n && contentWidth <= x) {
            val i = measured
            text.typeface = if (i == focusedCandidate && firstStandsOut) mediumTf else Typeface.DEFAULT
            val s = texts[i]
            var tw = text.measureText(s)
            val out = if (tw > maxItem) {
                TextUtils.ellipsize(s, text, maxItem, TextUtils.TruncateAt.MIDDLE).toString().also { tw = text.measureText(it) }
            } else s
            var w = tw + m.dp(24f) + if(cloudBadges.getOrElse(i){false}) m.dp(18f) else 0f
            val c = comments[i]
            if (c.isNotEmpty()) w += small.measureText(c) + m.dp(3f)
            hintAt(i)?.let { w += annotation.measureText(it) + m.dp(3f) }
            w = max(w, m.dp(40f))
            shown[i] = out
            lefts[i] = contentWidth
            widths[i] = w
            contentWidth += w
            measured++
        }
        hasMore = total > n || measured < n || contentWidth > width - leadW() - expandW()
    }

    /** 三格：首选居中，第二、三名在左右；不滚动、无展开。 Strip: best in the middle, no scrolling or expand. */
    private fun remeasureStrip() {
        val m = metrics
        val n = min(3, texts.size)
        val slot = width / 3f
        text.textSize = m.dp(layout.candidates.textSize) * m.candScale
        for (i in 0 until n) {
            widths[i] = slot
            lefts[i] = slot * STRIP_SLOTS[i]
            val s = texts[i]
            shown[i] = if (text.measureText(s) > slot - m.dp(16f)) TextUtils.ellipsize(s, text, slot - m.dp(16f), TextUtils.TruncateAt.END).toString() else s
        }
        measured = n
        contentWidth = width.toFloat()
        hasMore = false
        preeditShown = ""
    }

    private fun maxScroll(): Float {
        // 还有没测量的候选时允许继续滚，滚动中再补测。 Unmeasured candidates remain: allow scrolling on, measuring as we go.
        val extra = if (measured < texts.size) width.toFloat() else 0f
        return max(0f, contentWidth + extra - (width - leadW() - (if (sideButton) expandW() else 0f)))
    }

    private fun scrollTo(x: Float) {
        ensureMeasured(x + width * 2f)
        scrollX0 = x.coerceIn(0f, maxScroll())
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX.toFloat())
            checkMore()
            postInvalidateOnAnimation()
        }
    }

    private fun checkMore() {
        if (measured >= texts.size && texts.size < total && scrollX0 > maxScroll() - width * 0.5f) host.onNeedMore()
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
            val end = drawPreedit(c, x, base)
            if(cloudLoading) {
                val cx=width-m.dp(14f);val cy=m.dp(10f)*m.topScale;val radius=m.dp(5f)
                cloudSpinner.color=0xff2685e7.toInt();cloudSpinner.strokeWidth=m.dp(1.5f)
                tmp.set(cx-radius,cy-radius,cx+radius,cy+radius)
                c.drawArc(tmp,(SystemClock.uptimeMillis()%1000)*0.36f,100f,false,cloudSpinner)
                postInvalidateOnAnimation()
            }
            preeditLeft = x
            preeditRight = end
            if (cursorOn) {
                val cx = caretShownAt()?.let { x + small.measureText(preeditShown, 0, it) } ?: (end + m.dp(1f))
                fill.color = p.candidateFirst
                c.drawRect(cx, base - m.dp(10.5f) * m.candScale, cx + m.dp(1f), base + m.dp(1.5f), fill)
            }
        }
        val top = rowTop()
        val rowH = height - top
        val right = width - (if (sideButton) expandW() else 0f)
        // 有更多候选时整行画进图层，右端再擦成透明：渐变、图片背景上也看不出接缝。
        // With more candidates the row goes into a layer whose right end is erased, so no seam shows on gradients or images.
        val layer = if (hasMore) c.saveLayer(lead, top, right, height.toFloat(), null) else -1
        c.save()
        c.clipRect(lead, top, right, height.toFloat())
        text.textSize = m.dp(layout.candidates.textSize) * m.candScale
        small.textSize = m.dp(10f) * m.candScale
        annotation.textSize = m.dp(13f) * m.candScale
        annotation.color = p.labelSecondary
        val base = top + rowH / 2 - (text.ascent() + text.descent()) / 2
        for (i in 0 until measured) {
            val l = lead + lefts[i] - scrollX0
            if (l > right) break
            if (l + widths[i] < lead) continue
            val pill = i == focusedCandidate && p.candidatePill && firstStandsOut
            if (i == pressedCand || i == highlightedCandidate || pill) {
                fill.color = if (pill && i != pressedCand) p.candidatePillColor else p.toolbarActive
                val h = min(m.dp(30f), rowH)
                tmp.set(l + (if (pill) m.dp(4f) else 0f), top + (rowH - h) / 2, l + widths[i] - (if (pill) m.dp(4f) else 0f), top + (rowH + h) / 2)
                c.drawRoundRect(tmp, if (pill) h / 2 else m.dp(8f), if (pill) h / 2 else m.dp(8f), fill)
            }
            if (layout.candidates.dividers && i > 0) {
                fill.color = p.divider
                c.drawRect(l - m.dp(0.5f), top + rowH * 0.3f, l + m.dp(0.5f), top + rowH * 0.7f, fill)
            }
            val first = i == focusedCandidate && firstStandsOut
            text.typeface = if (first) mediumTf else Typeface.DEFAULT
            text.color = if (first) p.candidateFirst else p.label
            val sh = shown[i] ?: continue
            c.drawText(sh, l + m.dp(12f), base, text)
            // 英文：首项就是正在敲的单词，带上光标，每个字母当帧可见。 English: the first item is the word being typed, with a caret.
            if (i == 0 && english && cursorOn && preedit.isNotEmpty() && texts[0] == preedit) {
                val at = caret?.takeIf { sh == preedit && it < sh.length }
                val cx = l + m.dp(12f) + (if (at != null) text.measureText(sh, 0, at) else text.measureText(sh) + m.dp(1f))
                fill.color = p.candidateFirst
                c.drawRect(cx, base + text.ascent() * 0.8f, cx + m.dp(1f), base + m.dp(1.5f), fill)
            }
            val badge=cloudBadges.getOrElse(i){false}
            var end=l+m.dp(12f)+text.measureText(sh)
            hintAt(i)?.let { c.drawText(it, end + m.dp(3f), base, annotation); end += m.dp(3f) + annotation.measureText(it) }
            if(badge) icons.draw(c,R.drawable.ic_cloud,0xff2685e7.toInt(),end+m.dp(10f),base+(text.ascent()+text.descent())/2,m.dp(14f))
            val cm = comments[i]
            if (cm.isNotEmpty()) {
                small.color = p.labelHint
                c.drawText(cm, end + m.dp(if(badge) 21f else 3f), base, small)
            }
        }
        c.restore()
        if (hasMore) {
            // 渐隐遮罩。 Fade.
            val fw = m.dp(16f)
            if (fadeShader == null) {
                fadeShader = LinearGradient(0f, 0f, fw, 0f, 0, 0xFF000000.toInt(), Shader.TileMode.CLAMP)
            }
            fade.shader = fadeShader
            c.save()
            c.translate(right - fw, 0f)
            c.drawRect(0f, top, fw, height.toFloat(), fade)
            c.restore()
            c.restoreToCount(layer)
        }
        if (sideButton) {
            val expandIcon = when {
                dismissable -> R.drawable.ic_close
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
        for (i in 0 until measured) {
            val l = lefts[i]
            if (i == pressedCand || i == highlightedCandidate) {
                fill.color = p.toolbarActive
                val h = min(m.dp(34f), rowH)
                tmp.set(l + m.dp(4f), (rowH - h) / 2, l + widths[i] - m.dp(4f), (rowH + h) / 2)
                c.drawRoundRect(tmp, h / 2, h / 2, fill)
            }
            text.typeface = if (i == focusedCandidate) mediumTf else Typeface.DEFAULT
            text.color = if (i == highlightedCandidate) p.candidateFirst else p.label
            c.drawText(shown[i] ?: continue, l + widths[i] / 2, base, text)
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
        private fun candRight() = width - (if (sideButton) expandW() else 0f)
        private fun toolIndex(id: Int) = tools.indexOf(id)

        override fun a11yIds(): IntArray = when {
            !::metrics.isInitialized -> IntArray(0)
            actionMsg != null -> if (actionLabel != null) intArrayOf(ACTION_MSG, ACTION_BUTTON) else intArrayOf(ACTION_MSG)
            candidateMode -> {
                val lead = if (leadW() > 0f && !strip) intArrayOf(tools[0]) else IntArray(0)
                val first = if (!english && preedit.isNotEmpty() && !floating) intArrayOf(PREEDIT) else IntArray(0)
                lead + first + IntArray(measured) { CAND_BASE + it } + (if (sideButton) intArrayOf(EXPAND) else IntArray(0))
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
                    if (i !in 0 until measured) return false
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
            id == EXPAND -> if (dismissable) "清空候选" else if (expanded) "收起候选" else "展开更多候选"
            id >= CAND_BASE -> texts.getOrNull(id - CAND_BASE)?.let { t ->
                val c = comments.getOrNull(id - CAND_BASE)
                val py = pinyins.getOrNull(id - CAND_BASE)
                t + (if (py.isNullOrEmpty()) "" else "，拼音 $py") + (if (c.isNullOrEmpty()) "" else "，$c") + if(cloudBadges.getOrElse(id-CAND_BASE){false}) "，云端词" else ""
            }
            id == CHIP -> "粘贴最近复制：${clipChip.orEmpty()}"
            else -> TOOL_NAMES.getOrNull(id)
        }

        override fun a11yState(id: Int): CharSequence? = when {
            id >= CAND_BASE && id - CAND_BASE == highlightedCandidate -> "已选中，按空格确认"
            id < CHIP && id == activeTool -> "已打开"
            else -> null
        }

        override fun a11yClick(id: Int): Boolean {
            when {
                id == ACTION_BUTTON -> { val a = action; clearAction(); a?.invoke() }
                id == ACTION_MSG || id == PREEDIT -> return false
                id == EXPAND -> if (dismissable) host.onDismissCandidates() else host.onExpand()
                id >= CAND_BASE -> { if (id - CAND_BASE !in texts.indices) return false; host.onCandidate(id - CAND_BASE) }
                id == CHIP -> host.onClipChip()
                id < CHIP -> host.onToolbar(id)
                else -> return false
            }
            return true
        }

        override fun a11yLongClickLabel(id: Int): CharSequence? = when {
            id == ToolIds.MENU && clipChip == null && !candidateMode -> "打开设置"
            id >= CAND_BASE -> "调整优先级或删除学习记录"
            else -> null
        }

        override fun a11yLongClick(id: Int): Boolean = when {
            id == ToolIds.MENU -> host.onToolbarLong(ToolIds.MENU)
            id >= CAND_BASE -> host.onCandidateLong(id - CAND_BASE)
            else -> false
        }

        override fun a11yLiftToActivate(id: Int) = id >= CAND_BASE

        override fun a11yCanScroll(forward: Boolean) = candidateMode && actionMsg == null &&
            (if (forward) scrollX0 < maxScroll() - 1f || texts.size < total else scrollX0 > 0f)

        override fun a11yScroll(forward: Boolean): Boolean {
            if (!a11yCanScroll(forward)) return false
            val page = candRight() * 0.8f
            scrollTo(scrollX0 + if (forward) page else -page)
            checkMore()
            invalidate()
            a11y.invalidate()
            return true
        }
    }

    // ================================================================ touch

    private fun candAt(x: Float): Int {
        val xx = if (strip) x else x - leadW() + scrollX0
        for (i in 0 until measured) if (xx >= lefts[i] && xx < lefts[i] + widths[i]) return i
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
                swallowed = false
                downScroll = scrollX0
                scroller.forceFinished(true)
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                if (actionMsg != null) {
                    // 点在操作按钮外：收起提示条（不落到被它盖住、看不见的工具上）。
                    // A tap outside the action button dismisses the strip (without hitting the tools hidden under it).
                    if (!actionRect.contains(e.x, e.y)) { clearAction(); swallowed = true }
                    return true
                }
                if (candidateMode) {
                    val right = width - (if (sideButton) expandW() else 0f)
                    if (e.x >= right) return true
                    if (!strip && e.x < leadW()) { pressedTool = 0; invalidate(); return true }
                    // 点在组合串文字上：定位光标（左右拖动跟着移）；组合串行的其余地方按横向位置算候选。
                    // On the preedit text: place the caret (dragging moves it); elsewhere on that line, map by x to a candidate.
                    if (!english && !floating && preedit.isNotEmpty() && e.y < rowTop() &&
                        e.x >= preeditLeft - m.dp(6f) && e.x <= preeditRight + m.dp(16f)) {
                        pressedPreedit = true
                        preeditTapAt = -1
                        return true
                    }
                    pressedCand = candAt(e.x)
                    if (pressedCand >= 0) {
                        host.feedback?.key(this)
                        postDelayed(longPress, 400)
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
                if (swallowed) return true
                velocity?.addMovement(e)
                val dx = e.x - downX
                val dy = e.y - downY
                if (longTool >= 0) { host.onToolbarLongMove(longTool, dy); return true }
                if (pressedPreedit) {
                    if (dragging || abs(dx) > m.dp(8f)) { dragging = true; tapPreedit(e.x) }
                    return true
                }
                if (candidateMode && actionMsg == null && pressedTool < 0) {
                    if (strip) return true
                    if (!dragging && abs(dx) > m.dp(8f)) {
                        dragging = true
                        removeCallbacks(longPress)
                        pressedCand = -1
                    }
                    if (dragging) {
                        scrollTo(downScroll - dx)
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
                if (swallowed) { swallowed = false; return true }
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
                if (pressedPreedit) {
                    pressedPreedit = false
                    if (!dragging) tapPreedit(e.x)
                    dragging = false
                    return true
                }
                if (candidateMode && pressedTool >= 0) {
                    pressedTool = -1
                    host.onToolbar(tools[0])
                } else if (candidateMode) {
                    // 快速横滑的末点可能只在 UP 到达，先更新拖动再判断选词。
                    // A fast slide may deliver its final position only on UP; update the drag before selecting.
                    val dx = e.x - downX
                    if (!strip && abs(dx) > m.dp(8f)) { dragging = true; pressedCand = -1 }
                    if (dragging) {
                        scrollTo(downScroll - dx)
                        checkMore()
                        velocity?.computeCurrentVelocity(1000)
                        val vx = velocity?.xVelocity ?: 0f
                        scroller.fling(scrollX0.toInt(), 0, -vx.toInt(), 0, 0, maxScroll().toInt(), 0, 0)
                        postInvalidateOnAnimation()
                    } else {
                        val right = width - (if (sideButton) expandW() else 0f)
                        if (e.x >= right && dismissable) host.onDismissCandidates()
                        else if (e.x >= right && hasMore) host.onExpand()
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
                pressedPreedit = false
                if (longTool >= 0) host.onToolbarLongEnd(longTool, true)
                longTool = -1
                pressedCand = -1; pressedTool = -1; pressedChip = false
                invalidate()
            }
        }
        return true
    }

    companion object {
        private val TOOL_NAMES = arrayOf("工具箱", "切换键盘", "语音输入", "光标与编辑", "剪贴板", "收起键盘", "表情", "设置", "表情收纳袋", "翻译")
        /** 三格建议条中第 i 名所在的格。 Slot of the i-th suggestion in the strip. */
        private val STRIP_SLOTS = intArrayOf(1, 0, 2)
        private val OUTLINE_ICONS = mapOf(
            ToolIds.MENU to R.drawable.ic_logo, ToolIds.KEYBOARD to R.drawable.ic_keyboard, ToolIds.VOICE to R.drawable.ic_mic,
            ToolIds.CURSOR to R.drawable.ic_cursor, ToolIds.CLIPBOARD to R.drawable.ic_clipboard, ToolIds.HIDE to R.drawable.ic_chevron_down,
            ToolIds.EMOJI to R.drawable.ic_emoji, ToolIds.SETTINGS to R.drawable.ic_settings, ToolIds.STICKERS to R.drawable.ic_sticker_bag,
            ToolIds.TRANSLATE to R.drawable.ic_globe,
        )
        private val FILLED_ICONS = mapOf(
            ToolIds.MENU to R.drawable.ic_logo_filled, ToolIds.KEYBOARD to R.drawable.ic_keyboard_filled, ToolIds.VOICE to R.drawable.ic_mic_filled,
            ToolIds.CURSOR to R.drawable.ic_cursor_filled, ToolIds.CLIPBOARD to R.drawable.ic_clipboard_filled, ToolIds.HIDE to R.drawable.ic_chevron_down_filled,
            ToolIds.EMOJI to R.drawable.ic_emoji_filled, ToolIds.SETTINGS to R.drawable.ic_settings_filled, ToolIds.STICKERS to R.drawable.ic_sticker_bag,
            ToolIds.TRANSLATE to R.drawable.ic_globe,
        )
        private const val CHIP = 10
        private const val PREEDIT = 11
        private const val EXPAND = 12
        private const val ACTION_MSG = 13
        private const val ACTION_BUTTON = 14
        private const val CAND_BASE = 100
        internal const val STYLE_PLAIN: Byte = 0
        internal const val STYLE_FIXED: Byte = 1
        internal const val STYLE_SWAP: Byte = 2
        internal const val STYLE_REMOVED: Byte = 3
    }
}

/**
 * 同 [styledPreedit]：preedit 下标 [index] 在显示串里的位置（插回的多打字母算在光标前）。
 * Where preedit index [index] lands in the [styledPreedit] text (re-inserted extra letters count before it).
 */
internal fun styledIndex(preedit: String, marks: List<PreeditMark>, index: Int): Int =
    index + marks.sumOf { if (it.kind == PreeditMark.Kind.DELETE && it.start in 0..index) it.removed.length else 0 }

/** 字形筛选用的笔画字母（横竖撇点折）。 Stroke letters of the shape filter (h s p n z). */
internal const val STROKE_LETTERS = "hspnz"
private const val STROKE_GLYPHS = "一丨丿丶乛"

/**
 * 组合串末尾的字形码若是笔画键打的（大写 HSPNZ），显示成笔画（shi`HS → shi`一丨）；外接键盘打的小写字母可能是部件
 * 拼音，照原样显示。字数不变，标记与光标的下标照旧。
 * A shape code from the stroke keys (capital HSPNZ) at the end is shown as strokes (shi`HS → shi`一丨); lowercase from a
 * physical keyboard may be component pinyin and stays as typed. The length stays, so mark and caret indices hold.
 */
internal fun shapeShown(preedit: String): String {
    val at = preedit.lastIndexOf('`')
    val code = preedit.substring(at + 1)
    if (at < 0 || code.isEmpty() || code.any { it.lowercaseChar() !in STROKE_LETTERS || !it.isUpperCase() }) return preedit
    return preedit.substring(0, at + 1) + code.map { STROKE_GLYPHS[STROKE_LETTERS.indexOf(it.lowercaseChar())] }.joinToString("")
}

/**
 * 组合串加上纠错标记后的显示文字与每个字符的样式：去掉的多打字母插回原处（划掉显示），补上 / 换掉的字母
 * [TopBarView.STYLE_FIXED]，换回来的两个字母 [TopBarView.STYLE_SWAP]。
 * The preedit with its correction marks as display text plus a style per character: dropped extra letters are put back
 * in place (drawn struck through), added / replaced letters are [TopBarView.STYLE_FIXED], swapped pairs
 * [TopBarView.STYLE_SWAP].
 */
internal fun styledPreedit(raw: String, marks: List<PreeditMark>): Pair<String, ByteArray> {
    val preedit = shapeShown(raw)
    if (marks.isEmpty()) return preedit to ByteArray(preedit.length)
    val style = ByteArray(preedit.length)
    for (mk in marks) {
        val v = when (mk.kind) {
            PreeditMark.Kind.SWAP -> TopBarView.STYLE_SWAP
            PreeditMark.Kind.INSERT, PreeditMark.Kind.REPLACE -> TopBarView.STYLE_FIXED
            PreeditMark.Kind.DELETE -> continue
        }
        for (i in mk.start.coerceAtLeast(0) until mk.end.coerceAtMost(preedit.length)) style[i] = v
    }
    val removed = marks.filter { it.kind == PreeditMark.Kind.DELETE && it.start in 0..preedit.length }.groupBy { it.start }
    val sb = StringBuilder(preedit.length + removed.size)
    val out = ArrayList<Byte>(preedit.length + removed.size)
    for (i in 0..preedit.length) {
        removed[i]?.forEach { d -> for (ch in d.removed) { sb.append(ch); out += TopBarView.STYLE_REMOVED } }
        if (i < preedit.length) { sb.append(preedit[i]); out += style[i] }
    }
    return sb.toString() to out.toByteArray()
}
