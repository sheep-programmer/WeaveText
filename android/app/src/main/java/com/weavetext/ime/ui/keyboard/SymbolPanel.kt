package com.weavetext.ime.ui.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import com.weavetext.ime.R
import com.weavetext.ime.settings.WeavePrefs
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 符号面板（02 §8）。两种结构共用同一份分类数据（[SymbolData]），由布局风格 `symbols.categories` 选择：
 * `bottom`：上方纵向滚动网格，底行「返回 | 分类 tab | 锁定 | ⌫」；
 * `side`：左侧纵向分类列表，右侧按页翻动的网格，底行「返回 | 页码 | 锁定 | ⌫」。
 * Symbol panel. Two structures share one data model, chosen by the layout's `symbols.categories`:
 * `bottom` — scrolling grid with category tabs in the bottom row; `side` — categories in a left column, a paged grid,
 * and a page indicator in the bottom row.
 */
class SymbolPanel(kb: WeaveKeyboard) : KbPanel(kb) {
    private val grid = Grid(kb.ctx)
    private val side = SideList(kb.ctx)
    private val bottomRow = BottomRow(kb.ctx)
    private val body = LinearLayout(kb.ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(side, LinearLayout.LayoutParams(-2, -1))
        addView(grid, LinearLayout.LayoutParams(0, -1, 1f))
    }
    override val view = LinearLayout(kb.ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(bottomRow, LinearLayout.LayoutParams(-1, kb.metrics.rowPitch.toInt()))
    }

    /** 左侧分类 + 翻页网格。 Side categories with a paged grid. */
    private var sideMode = false

    private var cats = SymbolData.categories(recent())
    private var tab = 0
    private val tmp = RectF()

    private fun recent(): List<String> =
        kb.prefs.getString(WeavePrefs.SYMBOL_RECENT, "").orEmpty().split('\u0001').filter { it.isNotEmpty() }

    private fun remember(s: String) {
        val list = (listOf(s) + recent().filter { it != s }).take(24)
        kb.prefs.edit().putString(WeavePrefs.SYMBOL_RECENT, list.joinToString("\u0001")).apply()
    }

    private val locked get() = kb.prefs.getBoolean(WeavePrefs.SYMBOL_LOCK, false)
    private val skinTone get() = kb.prefs.getInt(WeavePrefs.EMOJI_SKIN, 0)

    override fun applyTheme() {
        sideMode = kb.style.layout.symbols.categories == "side"
        (bottomRow.layoutParams as LinearLayout.LayoutParams).height = kb.metrics.rowPitch.toInt()
        bottomRow.requestLayout()
        side.visibility = if (sideMode) View.VISIBLE else View.GONE
        grid.scrollToTop()
        grid.rebuild(); side.invalidate(); bottomRow.invalidate()
    }

    /** 左列宽度与底行「返回」键对齐。 The side column lines up with the Back key below. */
    private fun sideWidth(total: Int): Int {
        val m = kb.metrics
        return (m.padH + (total - 2 * m.padH) / 10f * 1.3f).toInt()
    }

    override fun onShow() {
        cats = SymbolData.categories(recent())
        if (tab != SymbolData.TAB_EMOJI) tab = 0
        grid.rebuild()
        grid.scrollToTop()
        side.ensureVisible()
        bottomRow.ensureTabVisible()
        bottomRow.invalidate()
    }

    override fun onHide() { tab = 0 }

    /** 定位到「表情」Tab（符长按、工具箱「表情」）。 Jump to the emoji tab. */
    fun selectEmoji() = selectTab(SymbolData.TAB_EMOJI)

    fun selectTab(i: Int) {
        tab = i
        grid.rebuild()
        grid.scrollToTop()
        side.ensureVisible()
        side.invalidate()
        bottomRow.ensureTabVisible()
        bottomRow.invalidate()
    }

    private fun output(s: String, single: Boolean) {
        val close = if (single) null else SymbolData.PAIRS[s]
        if (close != null) kb.controller.onPairedText(s, close) else kb.controller.onText(s)
        if (tab != SymbolData.TAB_COMMON) remember(s)
        val stay = locked || tab == SymbolData.TAB_EMOJI || tab == SymbolData.TAB_KAOMOJI
        if (!stay) kb.closePanel()
    }

    // ------------------------------------------------------------------ grid

    @SuppressLint("ViewConstructor")
    private inner class Grid(c: Context) : ScrollGridView(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private var items: List<String> = emptyList()
        private var cols = 6
        private var cellH = 0f
        /** 翻页模式每页行数（0 = 自由滚动）。 Rows per page in paged mode (0 = free scrolling). */
        private var rowsPerPage = 0
        private var altFor = -1
        private var shownPage = -1

        fun rebuild() {
            val cat = cats[tab]
            items = if (tab == SymbolData.TAB_EMOJI) cat.items.map { SymbolData.withTone(it, skinTone) } else cat.items
            // 左列占去一格多，多列分类少排一列。 The side column takes a column's worth of width.
            cols = if (sideMode && cat.columns > 2) cat.columns - 1 else cat.columns
            layoutRows()
            invalidate()
            a11yChanged()
        }

        private fun layoutRows() {
            val m = kb.metrics
            val base = m.rowPitch - m.dp(4f)
            if (sideMode && height > 0) {
                // 整页放整行，行高均分余量。 Whole rows per page; the spare height is shared.
                rowsPerPage = max(1, ((height - m.dp(4f)) / base).toInt())
                cellH = (height - m.dp(4f)) / rowsPerPage
            } else {
                rowsPerPage = 0
                cellH = base
            }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { layoutRows(); scrollToTop() }

        private val padL get() = if (sideMode) 0f else kb.metrics.padH

        private fun cellW() = (width - padL - kb.metrics.padH) / cols

        private val perPage get() = rowsPerPage * cols

        /** 页数（翻页模式）。 Page count in paged mode. */
        val pages get() = if (rowsPerPage == 0) 1 else max(1, (items.size + perPage - 1) / perPage)

        override fun pageHeight() = if (rowsPerPage > 0) height.toFloat() else 0f

        override fun contentHeight() =
            if (rowsPerPage > 0) pages * height.toFloat() else ceil(items.size / cols.toFloat()) * cellH + kb.metrics.dp(4f)

        override fun onScrollMoved() {
            if (rowsPerPage > 0 && page != shownPage) { shownPage = page; bottomRow.invalidate() }
        }

        override fun hit(x: Float, y: Float): Int {
            val col = ((x - padL) / cellW()).toInt()
            if (col !in 0 until cols || x < padL) return -1
            val i = if (rowsPerPage > 0) {
                val pg = (y / height).toInt()
                val row = ((y - pg * height - kb.metrics.dp(2f)) / cellH).toInt()
                if (row !in 0 until rowsPerPage) return -1
                pg * perPage + row * cols + col
            } else {
                val row = ((y - kb.metrics.dp(2f)) / cellH).toInt()
                if (row < 0) return -1
                row * cols + col
            }
            return if (i < items.size) i else -1
        }

        private fun cellRect(i: Int, out: RectF) {
            val m = kb.metrics
            val w = cellW()
            val l = padL + (i % cols) * w
            val t = if (rowsPerPage > 0) i / perPage * height + m.dp(2f) + (i % perPage) / cols * cellH
            else m.dp(2f) + (i / cols) * cellH
            out.set(l, t, l + w, t + cellH)
        }

        override fun onItemTap(index: Int) {
            val s = items.getOrNull(index) ?: return
            output(s, single = false)
        }

        override fun a11yCount() = items.size
        override fun a11yRect(index: Int, out: RectF) = cellRect(index, out)
        override fun a11yLabel(index: Int): CharSequence? = items.getOrNull(index)?.let { VirtualA11y.speak(it) }

        override fun onItemLong(index: Int): Boolean {
            val s = items.getOrNull(index) ?: return false
            val base = cats[tab].items[index]
            if (tab == SymbolData.TAB_EMOJI && base in SymbolData.SKIN_TONE_BASE) {
                val ov = kb.overlay ?: return false
                cellRect(index, tmp)
                tmp.offset(0f, -scroll)
                ov.map(this, tmp, tmp)
                altFor = index
                ov.showAlternatives(tmp, SymbolData.SKIN_TONES.indices.map { SymbolData.withTone(base, it) }, skinTone)
                return true
            }
            if (SymbolData.PAIRS.containsKey(s)) {
                // 长按成对符号只输入单个。 Long-press a paired symbol inputs it alone.
                output(s, single = true)
                return true
            }
            return false
        }

        override fun onLongMove(x: Float, y: Float) {
            val ov = kb.overlay ?: return
            if (altFor >= 0 && ov.moveAlternatives(ov.mapX(this, x), ov.mapY(this, y))) kb.feedback.haptic(this)
        }

        override fun onLongUp(cancel: Boolean) {
            val ov = kb.overlay ?: return
            if (altFor < 0) return
            val sel = ov.altSelected
            val base = cats[tab].items[altFor]
            ov.hideAlternatives()
            altFor = -1
            if (cancel || sel < 0) return
            // 记住肤色选择。 Remember the chosen tone.
            kb.prefs.edit().putInt(WeavePrefs.EMOJI_SKIN, sel).apply()
            rebuild()
            output(SymbolData.withTone(base, sel), single = true)
        }

        override fun drawContent(c: Canvas) {
            val pal = kb.palette
            val m = kb.metrics
            val emoji = tab == SymbolData.TAB_EMOJI
            val kao = tab == SymbolData.TAB_KAOMOJI
            p.typeface = Typeface.DEFAULT
            for (i in items.indices) {
                cellRect(i, tmp)
                if (tmp.bottom < scroll || tmp.top > scroll + height) continue
                if (i == pressed) {
                    p.color = pal.toolbarActive
                    tmp.inset(m.dp(3f), m.dp(3f))
                    c.drawRoundRect(tmp, m.dp(8f), m.dp(8f), p)
                    cellRect(i, tmp)
                }
                p.color = pal.label
                p.textSize = when { emoji -> m.dp(26f); kao -> m.dp(16f); else -> m.dp(20f) }
                val s = items[i]
                val w = p.measureText(s)
                val maxW = tmp.width() - m.dp(8f)
                if (w > maxW) p.textSize *= maxW / w
                c.drawText(s, tmp.centerX(), tmp.centerY() - (p.ascent() + p.descent()) / 2, p)
            }
        }
    }

    // ------------------------------------------------------------------ side categories

    /**
     * 左侧纵向分类列表（`side`）。每项高 = 列表高 / 5.5，露出半项提示可滚动；选中项按 `symbols.indicator`
     * 画左侧竖条或胶囊底。
     * Left category column: items are a 5.5th of the height so a half item hints at scrolling; the selected item gets a
     * left bar or a pill per `symbols.indicator`.
     */
    @SuppressLint("ViewConstructor")
    private inner class SideList(c: Context) : ScrollGridView(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val medium = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 500, false) else Typeface.DEFAULT_BOLD
        private val r = RectF()

        private fun itemH() = max(kb.metrics.dp(34f), height / 5.5f)

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // 包裹内容时拿到的是整行宽度。 With wrap_content the spec carries the full row width.
            val w = sideWidth(MeasureSpec.getSize(widthMeasureSpec))
            setMeasuredDimension(w, MeasureSpec.getSize(heightMeasureSpec))
        }

        override fun contentHeight() = cats.size * itemH()

        override fun hit(x: Float, y: Float): Int = (y / itemH()).toInt().takeIf { it in cats.indices && y >= 0 } ?: -1

        override fun onItemTap(index: Int) { if (index != tab) selectTab(index) }

        override fun a11yCount() = cats.size
        override fun a11yRect(index: Int, out: RectF) { out.set(0f, index * itemH(), width.toFloat(), (index + 1) * itemH()) }
        override fun a11yLabel(index: Int): CharSequence? = cats.getOrNull(index)?.let { if (index == tab) "${it.name}，已选中" else it.name }

        /** 让选中分类露在可见范围内。 Keep the selected category in view. */
        fun ensureVisible() {
            if (height == 0) return
            val top = tab * itemH()
            val target = when {
                top < scroll -> top
                top + itemH() > scroll + height -> top + itemH() - height
                else -> scroll
            }.coerceIn(0f, maxScroll())
            if (target != scroll) { scroll = target; invalidate() }
            a11yChanged()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { edgeFade = null; ensureVisible() }

        private val edgePaint = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT) }
        private var edgeFade: LinearGradient? = null

        /** 还能滚动的一端擦成渐隐，截断的分类名不会露出半截。 Fade out the scrollable ends so no half label shows. */
        override fun onDraw(canvas: Canvas) {
            val top = scroll > 1f
            val bottom = scroll < maxScroll() - 1f
            if (!top && !bottom) { super.onDraw(canvas); return }
            val fh = kb.metrics.dp(18f)
            val on = 0xFF000000.toInt()
            val g = edgeFade ?: LinearGradient(0f, 0f, 0f, height.toFloat(), intArrayOf(on, 0, 0, on),
                floatArrayOf(0f, fh / height, 1f - fh / height, 1f), Shader.TileMode.CLAMP).also { edgeFade = it }
            val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            super.onDraw(canvas)
            edgePaint.shader = g
            if (top) canvas.drawRect(0f, 0f, width.toFloat(), fh, edgePaint)
            if (bottom) canvas.drawRect(0f, height - fh, width.toFloat(), height.toFloat(), edgePaint)
            canvas.restoreToCount(layer)
        }

        override fun drawContent(c: Canvas) {
            val pal = kb.palette
            val m = kb.metrics
            val h = itemH()
            val pill = kb.style.layout.symbols.indicator == "pill"
            for (i in cats.indices) {
                val top = i * h
                if (top + h < scroll || top > scroll + height) continue
                r.set(m.padH + m.dp(2f), top + m.dp(3f), width - m.dp(4f), top + h - m.dp(3f))
                val sel = i == tab
                if (i == pressed || (sel && pill)) {
                    p.color = if (sel && pill) pal.accentSoft else pal.toolbarActive
                    c.drawRoundRect(r, m.dp(8f), m.dp(8f), p)
                }
                if (sel && !pill) {
                    // 左侧竖条 3×16dp。 Left bar, 3 × 16dp.
                    p.color = pal.keyAccent
                    val cy = top + h / 2
                    c.drawRoundRect(m.padH, cy - m.dp(8f), m.padH + m.dp(3f), cy + m.dp(8f), m.dp(1.5f), m.dp(1.5f), p)
                }
                p.typeface = if (sel) medium else Typeface.DEFAULT
                p.textSize = m.dp(14f)
                p.color = if (sel) pal.label else pal.labelSecondary
                val name = cats[i].name
                val maxW = r.width() - m.dp(6f)
                val w = p.measureText(name)
                if (w > maxW) p.textSize *= maxW / w
                c.drawText(name, r.centerX(), top + h / 2 - (p.ascent() + p.descent()) / 2, p)
            }
            // 与网格之间的细分隔线。 Hairline between the column and the grid.
            p.color = pal.divider
            c.drawRect(width - m.dp(0.5f), scroll + m.dp(8f), width.toFloat(), scroll + height - m.dp(8f), p)
        }
    }

    // ------------------------------------------------------------------ bottom row

    @SuppressLint("ViewConstructor")
    private inner class BottomRow(c: Context) : View(c) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val medium = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 500, false) else Typeface.DEFAULT_BOLD
        private val back = RectF()
        private val lock = RectF()
        private val del = RectF()
        private val tabs = RectF()
        private val r = RectF()
        private val scratch = RectF()
        private val scratch2 = RectF()
        private var tabX = FloatArray(0)
        private var tabW = FloatArray(0)
        private var tabScroll = 0f
        private var pressed = NONE
        private var downX = 0f
        private var downScroll = 0f
        private var dragging = false
        private var repeatCount = 0
        private val repeat = object : Runnable {
            override fun run() {
                repeatCount++
                kb.controller.onBackspace()
                postDelayed(this, 50)
            }
        }

        private fun geometry() {
            val m = kb.metrics
            val unit = (width - 2 * m.padH) / 10f
            back.set(m.padH, 0f, m.padH + unit * 1.3f, height.toFloat())
            del.set(width - m.padH - unit * 1.3f, 0f, width - m.padH, height.toFloat())
            lock.set(del.left - unit, 0f, del.left, height.toFloat())
            tabs.set(back.right, 0f, lock.left, height.toFloat())
            text.textSize = m.dp(14f)
            tabX = FloatArray(cats.size); tabW = FloatArray(cats.size)
            var x = 0f
            for (i in cats.indices) {
                text.typeface = if (i == tab) medium else Typeface.DEFAULT
                val w = text.measureText(cats[i].name) + m.dp(20f)
                tabX[i] = x; tabW[i] = w; x += w
            }
        }

        private fun maxTabScroll() = max(0f, (tabX.lastOrNull() ?: 0f) + (tabW.lastOrNull() ?: 0f) - tabs.width())

        fun ensureTabVisible() {
            if (width == 0) return
            geometry()
            val l = tabX[tab]; val rr = l + tabW[tab]
            if (l < tabScroll) tabScroll = l
            if (rr > tabScroll + tabs.width()) tabScroll = rr - tabs.width()
            tabScroll = tabScroll.coerceIn(0f, maxTabScroll())
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = ensureTabVisible()

        private fun drawKey(c: Canvas, cell: RectF, isPressed: Boolean, active: Boolean) {
            val pal = kb.palette
            val m = kb.metrics
            scratch.set(cell); scratch.inset(m.insetH, m.insetV)
            val color = when { active -> pal.accentSoft; isPressed -> pal.keyFuncPressed; else -> pal.keyFunc }
            KeyPainter.draw(c, scratch, r, scratch2, fill, color, isPressed, m.keyRadius, pal, m)
        }

        override fun onDraw(c: Canvas) {
            val pal = kb.palette
            val m = kb.metrics
            geometry()
            drawKey(c, back, pressed == BACK, false)
            text.typeface = medium; text.textSize = m.label(16f); text.color = pal.label
            text.measureText("返回").let { w -> val maxW = r.width() - m.dp(8f); if (w > maxW) text.textSize *= maxW / w }
            c.drawText("返回", r.centerX(), r.centerY() - (text.ascent() + text.descent()) / 2, text)
            val lk = locked
            drawKey(c, lock, pressed == LOCK, lk)
            kb.icons.draw(c, if (lk) R.drawable.ic_lock else R.drawable.ic_lock_open, if (lk) pal.keyAccent else pal.icon, r.centerX(), r.centerY(), m.icon(22f))
            drawKey(c, del, pressed == DEL, false)
            kb.icons.draw(c, R.drawable.ic_backspace, pal.icon, r.centerX(), r.centerY(), m.icon(22f))
            if (sideMode) { drawPages(c); return }
            // tabs
            val pill = kb.style.layout.symbols.indicator == "pill"
            // 分类画进图层，两端擦成透明（透出真实背景）。 Tabs go into a layer whose ends are erased to the real backdrop.
            val layer = c.saveLayer(tabs, null)
            c.clipRect(tabs)
            val cy = height / 2f
            for (i in cats.indices) {
                val l = tabs.left + tabX[i] - tabScroll
                val cx = l + tabW[i] / 2
                text.typeface = if (i == tab) medium else Typeface.DEFAULT
                text.textSize = m.dp(14f)
                text.color = if (i == tab) pal.label else pal.labelSecondary
                if (i == tab && pill) {
                    // 胶囊指示：选中分类垫一层浅强调色。 Pill indicator behind the selected category.
                    fill.color = pal.accentSoft
                    val hh = (text.descent() - text.ascent()) / 2 + m.dp(6f)
                    r.set(l + m.dp(4f), cy - hh, l + tabW[i] - m.dp(4f), cy + hh)
                    c.drawRoundRect(r, hh, hh, fill)
                }
                c.drawText(cats[i].name, cx, cy - (text.ascent() + text.descent()) / 2, text)
                if (i == tab && !pill) {
                    // 下划线 24×3dp，距文字 6dp。 Active underline 24 × 3dp, 6dp below the label.
                    fill.color = pal.keyAccent
                    // 矮键盘上也离底边至少 3dp。 Keep ≥ 3dp above the bottom edge on short keyboards.
                    val top = min(cy + (text.descent() - text.ascent()) / 2 + m.dp(6f), height - m.dp(6f))
                    r.set(cx - m.dp(12f), top, cx + m.dp(12f), top + m.dp(3f))
                    c.drawRoundRect(r, m.dp(1.5f), m.dp(1.5f), fill)
                }
            }
            drawScrollHints(c, layer)
        }

        /** 翻页模式：中部画页码点（页数多时改为「3 / 14」）。 Paged mode: page dots, or "3 / 14" when there are many. */
        private fun drawPages(c: Canvas) {
            val pal = kb.palette
            val m = kb.metrics
            val n = grid.pages
            if (n <= 1) return
            val cur = grid.page.coerceIn(0, n - 1)
            val cy = height / 2f
            val step = m.dp(14f)
            if (n * step > tabs.width() - m.dp(16f) || n > MAX_DOTS) {
                text.typeface = Typeface.DEFAULT; text.textSize = m.dp(14f); text.color = pal.labelSecondary
                c.drawText("${cur + 1} / $n", tabs.centerX(), cy - (text.ascent() + text.descent()) / 2, text)
                return
            }
            var x = tabs.centerX() - (n - 1) * step / 2
            for (i in 0 until n) {
                fill.color = if (i == cur) pal.keyAccent else pal.labelDisabled
                c.drawCircle(x, cy, m.dp(if (i == cur) 3.5f else 3f), fill)
                x += step
            }
        }

        /** 分类可横向滚动时两端 20dp 渐隐 + 12dp 箭头；滑到头隐藏对应一侧。 Edge fades and chevrons. */
        private fun drawScrollHints(c: Canvas, layer: Int) {
            val pal = kb.palette
            val m = kb.metrics
            val fw = m.dp(20f)
            if (fadeW != fw) {
                fadeW = fw
                // 外侧 40% 完全擦掉，箭头落在背景上。 The outer 40% is fully erased so the chevron sits on the backdrop.
                val on = 0xFF000000.toInt()
                fadeL = LinearGradient(0f, 0f, fw, 0f, intArrayOf(on, on, 0), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
                fadeR = LinearGradient(0f, 0f, fw, 0f, intArrayOf(0, on, on), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            }
            val cy = height / 2f
            val left = tabScroll > 1f
            val right = tabScroll < maxTabScroll() - 1f
            if (left) {
                c.save(); c.translate(tabs.left, 0f)
                fadePaint.shader = fadeL; c.drawRect(0f, 0f, fw, height.toFloat(), fadePaint)
                c.restore()
            }
            if (right) {
                c.save(); c.translate(tabs.right - fw, 0f)
                fadePaint.shader = fadeR; c.drawRect(0f, 0f, fw, height.toFloat(), fadePaint)
                c.restore()
            }
            c.restoreToCount(layer)
            if (left) kb.icons.draw(c, R.drawable.ic_chevron_left, pal.labelHint, tabs.left + m.dp(5f), cy, m.dp(12f))
            if (right) kb.icons.draw(c, R.drawable.ic_chevron_right, pal.labelHint, tabs.right - m.dp(5f), cy, m.dp(12f))
        }

        private val fadePaint = Paint().apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT) }
        private var fadeL: LinearGradient? = null
        private var fadeR: LinearGradient? = null
        private var fadeW = 0f

        // 无障碍：返回 / 分类 / 锁定 / 删除。 Accessibility: back, categories, lock, delete.
        private val a11y = VirtualA11y(this, object : VirtualA11y.Source {
            override fun a11yIds() = if (sideMode) intArrayOf(BACK, PAGE, LOCK, DEL) else intArrayOf(BACK) + IntArray(cats.size) { it } + intArrayOf(LOCK, DEL)
            override fun a11yBounds(id: Int, out: RectF): Boolean {
                if (width == 0) return false
                geometry()
                when (id) {
                    BACK -> out.set(back); LOCK -> out.set(lock); DEL -> out.set(del)
                    PAGE -> { if (grid.pages <= 1) return false; out.set(tabs) }
                    else -> {
                        if (id !in tabX.indices) return false
                        val l = tabs.left + tabX[id] - tabScroll
                        out.set(max(l, tabs.left), 0f, minOf(l + tabW[id], tabs.right), height.toFloat())
                    }
                }
                return out.width() > 1f
            }
            override fun a11yLabel(id: Int): CharSequence? = when (id) {
                BACK -> "返回"; LOCK -> "锁定符号面板"; DEL -> "删除"
                PAGE -> "第 ${grid.page + 1} 页，共 ${grid.pages} 页，点按翻到下一页"
                else -> cats.getOrNull(id)?.name
            }
            override fun a11yState(id: Int): CharSequence? = when {
                id == LOCK -> if (locked) "已开启" else "关闭"
                id == tab -> "已选中"
                else -> null
            }
            override fun a11yClick(id: Int): Boolean {
                when (id) {
                    BACK -> kb.closePanel()
                    LOCK -> kb.prefs.edit().putBoolean(WeavePrefs.SYMBOL_LOCK, !locked).apply()
                    DEL -> kb.controller.onBackspace()
                    PAGE -> grid.scrollToPage((grid.page + 1) % grid.pages)
                    else -> { if (id !in cats.indices) return false; selectTab(id); ensureTabVisible() }
                }
                invalidate()
                return true
            }
        })

        override fun getAccessibilityNodeProvider(): android.view.accessibility.AccessibilityNodeProvider = a11y
        override fun dispatchHoverEvent(event: MotionEvent): Boolean = a11y.onHover(event) || super.dispatchHoverEvent(event)

        private fun hitAt(x: Float, y: Float): Int = when {
            back.contains(x, y) -> BACK
            lock.contains(x, y) -> LOCK
            del.contains(x, y) -> DEL
            sideMode -> if (tabs.contains(x, y) && grid.pages > 1) PAGE else NONE
            tabs.contains(x, y) -> {
                val xx = x - tabs.left + tabScroll
                var t = NONE
                for (i in tabX.indices) if (xx >= tabX[i] && xx < tabX[i] + tabW[i]) t = i
                t
            }
            else -> NONE
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressed = hitAt(e.x, e.y)
                    downX = e.x; downScroll = tabScroll; dragging = false; repeatCount = 0
                    if (pressed != NONE) kb.feedback.key(this)
                    if (pressed == DEL) postDelayed(repeat, 400)
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> if (!sideMode && (pressed >= 0 || dragging)) {
                    val dx = e.x - downX
                    if (!dragging && abs(dx) > kb.metrics.dp(8f)) { dragging = true; pressed = NONE }
                    if (dragging) { tabScroll = (downScroll - dx).coerceIn(0f, maxTabScroll()); invalidate() }
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(repeat)
                    val p = pressed
                    pressed = NONE
                    when {
                        dragging -> {}
                        p == BACK -> kb.closePanel()
                        p == LOCK -> kb.prefs.edit().putBoolean(WeavePrefs.SYMBOL_LOCK, !locked).apply()
                        p == DEL -> if (repeatCount == 0) kb.controller.onBackspace()
                        // 点页码左半翻上一页、右半翻下一页。 Tap the left / right half of the indicator to turn pages.
                        p == PAGE -> grid.scrollToPage(grid.page + if (e.x < tabs.centerX()) -1 else 1)
                        p >= 0 -> selectTab(p)
                    }
                    dragging = false
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> { removeCallbacks(repeat); pressed = NONE; dragging = false; invalidate() }
            }
            return true
        }
    }

    companion object {
        private const val NONE = -1
        private const val BACK = -2
        private const val LOCK = -3
        private const val DEL = -4
        private const val PAGE = -5
        private const val MAX_DOTS = 12
    }
}
