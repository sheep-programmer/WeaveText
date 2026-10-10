package com.weavetext.ime.ui.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.weavetext.ime.R
import com.weavetext.ime.core.Candidate
import com.weavetext.ime.core.PreeditMark
import com.weavetext.ime.ime.ImeState
import kotlin.math.ceil
import kotlin.math.max

/**
 * 候选展开网格（02 §2.3）：接管顶栏 + 主区域。 Expanded candidate grid over top bar + main area.
 */
class CandidateGridPanel(kb: WeaveKeyboard) : KbPanel(kb) {
    override val full = true
    private val ctx = kb.ctx
    private val header = Header(ctx)
    private val grid = Grid(ctx)
    private val side = PadView(ctx, kb)
    private var showPinyin = false

    override val view: ViewGroup = object : ViewGroup(ctx) {
        override fun onMeasure(ws: Int, hs: Int) {
            val w = MeasureSpec.getSize(ws)
            val h = MeasureSpec.getSize(hs)
            setMeasuredDimension(w, h)
            val m = kb.metrics
            val sideW = m.dp(64f).toInt()
            val top = m.topBar.toInt()
            header.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(top, MeasureSpec.EXACTLY))
            grid.measure(MeasureSpec.makeMeasureSpec(w - sideW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h - top, MeasureSpec.EXACTLY))
            side.measure(MeasureSpec.makeMeasureSpec(sideW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h - top, MeasureSpec.EXACTLY))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val w = r - l
            val h = b - t
            val m = kb.metrics
            val sideW = m.dp(64f).toInt()
            val top = m.topBar.toInt()
            header.layout(0, 0, w, top)
            grid.layout(0, top, w - sideW, h)
            side.layout(w - sideW, top, w, h)
        }
    }.apply {
        addView(header); addView(grid); addView(side)
        setOnTouchListener { _, _ -> true }
        // 空白处的悬停也吃掉，读屏不会摸到下面的键。 Swallow hover over blank parts too, away from the hidden keys.
        setOnHoverListener { _, _ -> true }
    }

    private var cands = ArrayList<Candidate>()
    private var pinyin: List<String> = emptyList()

    init {
        side.onTap = { k ->
            when (k.id) {
                0 -> kb.controller.onBackspace()
                1 -> kb.controller.reset()
                2 -> kb.closePanel()
                3 -> { showPinyin = !showPinyin; k.active = showPinyin; reload() }
            }
        }
        side.keys = buildSide(false)
        side.layouter = { w, h -> layoutSide(w, h) }
        grid.onPressFeedback = { kb.feedback.key(grid) }
        grid.onLongFeedback = { kb.feedback.haptic(grid) }
    }

    private fun buildSide(t9: Boolean): List<PadKey> {
        val list = ArrayList<PadKey>()
        if (t9) list += PadKey(3, "拼音")
        list += PadKey(0, icon = R.drawable.ic_backspace).apply { repeat = true }
        list += PadKey(1, "重输")
        list += PadKey(2, "返回")
        return list
    }

    private fun layoutSide(w: Float, h: Float) {
        val m = kb.metrics
        val n = side.keys.size
        val rh = h / n
        side.keys.forEachIndexed { i, k ->
            k.cell.set(0f, i * rh, w, (i + 1) * rh)
            k.rect.set(k.cell)
            k.rect.inset(m.insetH, m.insetV)
            k.textSize = m.label(15f)
        }
    }

    override fun applyTheme() {
        kb.paintBackground(view)
        header.invalidate(); grid.rebuild(); side.relayout()
    }

    override fun onShow() {
        val t9 = (kb.state.schema == "t9" || kb.state.schema == "t14") && kb.state.chinese
        showPinyin = false
        side.keys = buildSide(t9)
        grid.scrollToTop()
        reload()
        grid.highlight(kb.state.highlightedCandidate, force = true)
    }

    override fun onHide() {
        // 组合删空时网格随即收起：按住的退格不能接着删已上屏的字。 The grid closes as the composition empties:
        // a held backspace must not go on deleting committed text.
        side.cancelPress()
    }

    override fun onState(s: ImeState) {
        if (!s.composing && s.candidates.isEmpty()) { kb.closePanel(); return }
        // 组合串与首页候选都没变（光标回报等引起的刷新）：保留已翻到的位置和加载的页。
        // Same preedit and first page (a refresh from a selection report and the like): keep the scroll and loaded pages.
        if (s.candidateGeneration == shownGeneration && s.preedit == shownPreedit && s.preeditMarks == shownMarks && samePage(s.candidates) &&
            (if (showPinyin) s.pinyinOptions else emptyList()) == pinyin) {
            grid.highlight(s.highlightedCandidate)
            return
        }
        reload()
        grid.highlight(s.highlightedCandidate, force = true)
    }

    private var shownPreedit = ""
    private var shownGeneration = 0L
    private var shownMarks: List<PreeditMark> = emptyList()

    private fun samePage(page: List<Candidate>): Boolean {
        if (page.size > cands.size) return false
        for (i in page.indices) if (page[i] != cands[i]) return false
        return true
    }

    private fun reload() {
        val s = kb.state
        cands = ArrayList(s.candidates)
        pinyin = if (showPinyin) s.pinyinOptions else emptyList()
        shownPreedit = s.preedit
        shownGeneration = s.candidateGeneration
        shownMarks = s.preeditMarks
        header.set(s.preedit, s.preeditMarks)
        grid.rebuild()
        grid.scrollToTop()
        header.invalidate()
    }

    private fun loadMore() {
        val s = kb.state
        if (cands.size >= s.totalCandidates) return
        val more = kb.controller.loadCandidates(cands.size, 30)
        if (more.isEmpty()) return
        cands.addAll(more)
        grid.rebuild()
    }

    @SuppressLint("ViewConstructor")
    private inner class Header(c: Context) : View(c) {
        private var text = ""
        private var style = ByteArray(0)
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).zh()

        fun set(preedit: String, marks: List<PreeditMark>) {
            val (t, st) = styledPreedit(preedit, marks)
            text = t
            style = st
        }

        override fun onDraw(canvas: Canvas) {
            val pal = kb.palette
            val m = kb.metrics
            p.textSize = m.dp(12.5f) * m.candScale
            // 纠错改动的字母标红（去掉的多打字母划掉）。 Corrected letters in red (dropped extras struck through).
            val base = height / 2f - (p.ascent() + p.descent()) / 2
            var x = m.dp(12f)
            var i = 0
            while (i < text.length) {
                val k = style.getOrElse(i) { TopBarView.STYLE_PLAIN }
                var j = i + 1
                while (j < text.length && style.getOrElse(j) { TopBarView.STYLE_PLAIN } == k) j++
                val w = p.measureText(text, i, j)
                p.color = if (k == TopBarView.STYLE_PLAIN) pal.labelSecondary else pal.danger
                canvas.drawText(text, i, j, x, base, p)
                if (k == TopBarView.STYLE_REMOVED) canvas.drawRect(x, base - p.textSize * 0.3f - m.dp(0.6f), x + w, base - p.textSize * 0.3f + m.dp(0.6f), p)
                x += w
                i = j
            }
            val cx = width - m.dp(32f)
            kb.icons.draw(canvas, R.drawable.ic_chevron_up, pal.icon, cx, height / 2f, m.dp(22f))
            p.color = pal.divider
            canvas.drawRect(0f, height - max(1f, m.dp(0.5f)), width.toFloat(), height.toFloat(), p)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN && e.x > width - kb.metrics.dp(64f)) {
                kb.feedback.key(this)
                kb.closePanel()
            }
            return true
        }
    }

    @SuppressLint("ViewConstructor")
    private inner class Grid(c: Context) : ScrollGridView(c) {
        private var highlighted = -1
        private var revealPending = false
        fun highlight(index: Int, force: Boolean = false) {
            if (!force && highlighted == index) return
            highlighted = index
            revealPending = index >= 0
            revealHighlighted()
            invalidate()
            a11yChanged()
        }
        private fun revealHighlighted() {
            val i = highlighted + nPinyin
            if (revealPending && width > 0 && height > 0 && i * 4 + 3 < rects.size) {
                reveal(rects[i * 4 + 1], rects[i * 4 + 3])
                revealPending = false
            }
        }
        // 每项：left, top, right, bottom；前 pinyin.size 项为拼音 chip。 Per item rects.
        private var rects = FloatArray(0)
        private var labels: Array<String> = emptyArray()
        private var nPinyin = 0
        private var rowsBottom = 0f
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).zh().apply { textAlign = Paint.Align.CENTER }
        private val line = Paint()

        fun rebuild() {
            if (width == 0) { post { rebuild() }; return }
            val m = kb.metrics
            text.textSize = m.dp(19f) * m.candScale
            val unit = width / 4f
            val rowH = m.dp(max(52f, 19f * m.candScale + 26f))
            nPinyin = pinyin.size
            val all = pinyin + cands.map { it.text }
            labels = all.toTypedArray()
            rects = FloatArray(all.size * 4)
            var y = 0f
            var i = 0
            // 拼音行：每个 chip 1 单元，一行 4 个。 Pinyin chips: 1 unit each.
            while (i < nPinyin) {
                val rowStart = i
                while (i < nPinyin && i - rowStart < 4) i++
                y += layoutRow(rowStart, i, IntArray(i - rowStart) { 1 }, y, rowH, unit)
            }
            while (i < all.size) {
                val rowStart = i
                var used = 0
                val spans = ArrayList<Int>()
                while (i < all.size) {
                    val cand = cands.getOrNull(i - nPinyin)
                    val wordW = text.measureText(all[i]) + (if(cand?.isCloud==true) m.dp(18f) else 0f)
                    val w = wordW + m.dp(24f)
                    val span = ceil(w / unit).toInt().coerceIn(1, 4)
                    if (used + span > 4) break
                    spans += span
                    used += span
                    i++
                }
                y += layoutRow(rowStart, i, spans.toIntArray(), y, rowH, unit)
            }
            rowsBottom = y
            revealHighlighted()
            invalidate()
            a11yChanged()
        }

        override fun a11yCount() = labels.size
        override fun a11yRect(index: Int, out: android.graphics.RectF) {
            out.set(rects[index * 4], rects[index * 4 + 1], rects[index * 4 + 2], rects[index * 4 + 3])
        }
        override fun a11yLabel(index: Int): CharSequence? = labels.getOrNull(index)?.let {
            val candidate = cands.getOrNull(index - nPinyin)
            if (index < nPinyin) "拼音 $it" else it + (if(candidate?.isCloud==true) "，云端词" else "") +
                (if (index - nPinyin == highlighted) "，已选中，按空格确认" else "")
        }

        private fun layoutRow(from: Int, to: Int, spans: IntArray, y: Float, rowH: Float, unit: Float): Float {
            val used = spans.sum()
            val extra = (4 - used) * unit / spans.size.coerceAtLeast(1)
            var x = 0f
            for (j in from until to) {
                val w = spans[j - from] * unit + extra
                rects[j * 4] = x; rects[j * 4 + 1] = y; rects[j * 4 + 2] = x + w
                x += w
            }
            for (j in from until to) rects[j * 4 + 3] = y + rowH
            return rowH
        }

        override fun contentHeight() = rowsBottom
        override fun hit(x: Float, y: Float): Int {
            for (i in labels.indices) {
                if (x >= rects[i * 4] && x < rects[i * 4 + 2] && y >= rects[i * 4 + 1] && y < rects[i * 4 + 3]) return i
            }
            return -1
        }

        override fun onItemTap(index: Int) {
            if (index < nPinyin) { kb.controller.onPinyinOption(index); return }
            kb.controller.onVisibleCandidate(index - nPinyin, shownGeneration)
        }

        override fun onItemLong(index: Int): Boolean = index >= nPinyin && kb.onCandidateLongVisible(index - nPinyin, shownGeneration)
        override fun a11yLongLabel(index: Int): CharSequence? = if (index >= nPinyin) "查看完整候选与操作" else null

        override fun onScrolledNearEnd() = loadMore()

        override fun drawContent(c: Canvas) {
            val p = kb.palette
            val m = kb.metrics
            val hair = max(1f, m.dp(0.5f))
            text.textSize = m.dp(19f) * m.candScale
            val top = scroll
            val bottom = scroll + height
            for (i in labels.indices) {
                val l = rects[i * 4]; val t = rects[i * 4 + 1]; val r = rects[i * 4 + 2]; val b = rects[i * 4 + 3]
                if (b < top || t > bottom) continue
                if (i == pressed || i == nPinyin + highlighted && highlighted >= 0) {
                    line.color = p.toolbarActive
                    c.drawRect(l, t, r, b, line)
                }
                val isPy = i < nPinyin
                text.color = when {
                    isPy -> p.candidateFirst
                    i == nPinyin + highlighted || highlighted < 0 && i == nPinyin -> p.candidateFirst
                    else -> p.label
                }
                text.textSize = if (isPy) m.dp(15f) else m.dp(19f) * m.candScale
                // 最多占 4 格，更长的候选在格内中间省略。 At most 4 units wide: longer candidates are ellipsized in the middle.
                val cloud=!isPy && cands.getOrNull(i-nPinyin)?.isCloud==true
                val maxW = r - l - m.dp(if(cloud) 34f else 16f)
                val label = labels[i].let { if (maxW > 0f && text.measureText(it) > maxW) TextUtils.ellipsize(it, text, maxW, TextUtils.TruncateAt.MIDDLE).toString() else it }
                val badgeW=if(cloud)m.dp(18f)else 0f
                val center=(l+r)/2-badgeW/2
                val base = (t + b) / 2 - (text.ascent() + text.descent()) / 2
                c.drawText(label, center, base, text)
                if(cloud) kb.icons.draw(c,R.drawable.ic_cloud,0xff2685e7.toInt(),center+text.measureText(label)/2+m.dp(10f),base+(text.ascent()+text.descent())/2,m.dp(14f))
                line.color = p.divider
                c.drawRect(0f, b - hair / 2, width.toFloat(), b + hair / 2, line)
                if (r < width - 1f) c.drawRect(r - hair / 2, t + m.dp(10f), r + hair / 2, b - m.dp(10f), line)
            }
        }
    }
}

/**
 * 键盘选择浮层（02 §2.4）：覆盖主区域。 Layout picker over the main area.
 */
class PickerPanel(kb: WeaveKeyboard) : KbPanel(kb) {
    override val toolIndex = 1
    override val view = PickerView(kb.ctx)

    override fun applyTheme() { kb.paintBackground(view); view.invalidate() }
    override fun onShow() = view.invalidate()

    @SuppressLint("ViewConstructor")
    inner class PickerView(c: Context) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).zh()
        private val rect = android.graphics.RectF()
        private var pressed = -1
        private val medium = if (android.os.Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 500, false) else Typeface.DEFAULT_BOLD

        private fun items() = com.weavetext.ime.settings.WeavePrefs.keyboards(kb.prefs)

        /** 字号 [size]，放不下 [maxW] 时缩小。 Text size [size], shrunk to fit [maxW]. */
        private fun fit(s: String, size: Float, maxW: Float) {
            p.textSize = size
            val w = p.measureText(s)
            if (w > maxW && w > 0f) p.textSize = size * maxW / w
        }

        private fun cell(i: Int, out: android.graphics.RectF) {
            val m = kb.metrics
            val gap = m.dp(8f)
            val cw = m.dp(76f).coerceAtMost((width - m.dp(24f) - 3 * gap) / 4)
            // 行高按可用高度收：横屏键区矮，两行也要放得下。 Row height shrinks to fit: two rows fit even in landscape.
            val rows = (items().size + 3) / 4
            val ch = if (rows <= 1) m.dp(72f) else ((height - m.dp(44f) - m.dp(8f) - (rows - 1) * gap) / rows).coerceIn(m.dp(40f), m.dp(72f))
            val totalW = 4 * cw + 3 * gap
            val left0 = (width - totalW) / 2
            val col = i % 4
            val row = i / 4
            val top = m.dp(44f) + row * (ch + gap)
            out.set(left0 + col * (cw + gap), top, left0 + col * (cw + gap) + cw, top + ch)
        }

        private fun sub(k: String): String = when (k) {
            "pinyin" -> "全拼"
            "t9", "t14" -> "拼音"
            "hand" -> "单字"
            "shuangpin" -> com.weavetext.ime.settings.WeavePrefs.SHUANGPIN_SCHEMES.firstOrNull { it.first == com.weavetext.ime.settings.WeavePrefs.shuangpinScheme(kb.prefs) }?.second ?: ""
            "wubi86" -> "86"
            else -> "English"
        }

        private fun title(k: String): String = when (k) {
            "pinyin" -> "26键"; "t9" -> "九键"; "t14" -> "14键"; "hand" -> "手写"; "shuangpin" -> "双拼"; "wubi86" -> "五笔"; else -> "英文"
        }

        override fun onDraw(c: Canvas) {
            val pal = kb.palette
            val m = kb.metrics
            p.textSize = m.dp(15f); p.typeface = medium; p.color = pal.label; p.textAlign = Paint.Align.LEFT
            c.drawText("选择键盘", m.dp(16f), m.dp(22f) - (p.ascent() + p.descent()) / 2, p)
            p.textAlign = Paint.Align.RIGHT; p.color = pal.candidateFirst
            c.drawText("完成", width - m.dp(16f), m.dp(22f) - (p.ascent() + p.descent()) / 2, p)
            val cur = kb.currentKeyboardKey()
            val list = items()
            p.textAlign = Paint.Align.CENTER
            for (i in list.indices) {
                cell(i, rect)
                val sel = list[i] == cur
                p.style = Paint.Style.FILL
                p.color = if (sel) pal.accentSoft else if (i == pressed) pal.keyPressed else pal.key
                c.drawRoundRect(rect, m.dp(12f), m.dp(12f), p)
                if (sel) {
                    p.style = Paint.Style.STROKE; p.strokeWidth = m.dp(1.5f); p.color = pal.keyAccent
                    c.drawRoundRect(rect, m.dp(12f), m.dp(12f), p)
                    p.style = Paint.Style.FILL
                }
                // 格子够高：图标、标题、说明三行；矮格子（横屏）省掉图标，两行文字居中。
                // Tall cells: icon, title, subtitle; short cells (landscape) drop the icon and centre the two lines.
                val tall = rect.height() >= m.dp(64f)
                val titleY = if (tall) rect.top + rect.height() * 46f / 72f else rect.centerY() - m.dp(3f)
                val subY = if (tall) rect.top + rect.height() * 62f / 72f else rect.centerY() + m.dp(12f)
                if (tall) kb.icons.draw(c, R.drawable.ic_keyboard, if (sel) pal.keyAccent else pal.icon, rect.centerX(), rect.top + rect.height() * 20f / 72f, m.dp(20f))
                p.typeface = medium; p.color = if (sel) pal.keyAccent else pal.label
                val t = title(list[i])
                fit(t, m.panel(13f), rect.width() - m.dp(8f))
                c.drawText(t, rect.centerX(), titleY, p)
                p.typeface = Typeface.DEFAULT; p.color = if (sel) pal.keyAccent else pal.labelSecondary
                val st = sub(list[i]) + if (sel) " ✓" else ""
                fit(st, m.panel(11f), rect.width() - m.dp(8f))
                c.drawText(st, rect.centerX(), subY, p)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val list = items()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressed = -1
                    for (i in list.indices) { cell(i, rect); if (rect.contains(e.x, e.y)) pressed = i }
                    if (pressed >= 0 || (e.y < kb.metrics.dp(44f) && e.x > width - kb.metrics.dp(80f))) kb.feedback.key(this)
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    val i = pressed
                    pressed = -1
                    if (i >= 0) { kb.chooseKeyboard(list[i]); kb.closePanel() }
                    else if (e.y < kb.metrics.dp(44f) && e.x > width - kb.metrics.dp(80f)) kb.closePanel()
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
            }
            return true
        }
    }
}
