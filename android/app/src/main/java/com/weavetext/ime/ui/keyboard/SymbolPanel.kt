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

/**
 * 符号面板（02 §8）：上方纵向滚动网格，底行「返回 | 分类 tab | 锁定 | ⌫」。
 * Symbol panel: scrolling grid on top, bottom row with Back, category tabs, lock and backspace.
 */
class SymbolPanel(kb: WeaveKeyboard) : KbPanel(kb) {
    private val grid = Grid(kb.ctx)
    private val bottomRow = BottomRow(kb.ctx)
    override val view = LinearLayout(kb.ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(bottomRow, LinearLayout.LayoutParams(-1, kb.metrics.rowPitch.toInt()))
    }

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
        (bottomRow.layoutParams as LinearLayout.LayoutParams).height = kb.metrics.rowPitch.toInt()
        bottomRow.requestLayout()
        grid.rebuild(); bottomRow.invalidate()
    }

    override fun onShow() {
        cats = SymbolData.categories(recent())
        if (tab != SymbolData.TAB_EMOJI) tab = 0
        grid.rebuild()
        grid.scrollToTop()
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
        private var altFor = -1

        fun rebuild() {
            val cat = cats[tab]
            items = if (tab == SymbolData.TAB_EMOJI) cat.items.map { SymbolData.withTone(it, skinTone) } else cat.items
            cols = cat.columns
            cellH = kb.metrics.rowPitch - kb.metrics.dp(4f)
            invalidate()
            a11yChanged()
        }

        private fun cellW() = (width - 2 * kb.metrics.padH) / cols

        override fun contentHeight() = ceil(items.size / cols.toFloat()) * cellH + kb.metrics.dp(4f)

        override fun hit(x: Float, y: Float): Int {
            val col = ((x - kb.metrics.padH) / cellW()).toInt()
            val row = ((y - kb.metrics.dp(2f)) / cellH).toInt()
            if (col !in 0 until cols || row < 0) return -1
            val i = row * cols + col
            return if (i < items.size) i else -1
        }

        private fun cellRect(i: Int, out: RectF) {
            val m = kb.metrics
            val w = cellW()
            val l = m.padH + (i % cols) * w
            val t = m.dp(2f) + (i / cols) * cellH
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
            kb.icons.draw(c, if (lk) R.drawable.ic_lock else R.drawable.ic_lock_open, if (lk) pal.keyAccent else pal.icon, r.centerX(), r.centerY(), m.dp(22f))
            drawKey(c, del, pressed == DEL, false)
            kb.icons.draw(c, R.drawable.ic_backspace, pal.icon, r.centerX(), r.centerY(), m.icon(22f))
            // tabs
            val pill = kb.style.layout.symbols.indicator == "pill"
            c.save()
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
                    val top = cy + (text.descent() - text.ascent()) / 2 + m.dp(6f)
                    r.set(cx - m.dp(12f), top, cx + m.dp(12f), top + m.dp(3f))
                    c.drawRoundRect(r, m.dp(1.5f), m.dp(1.5f), fill)
                }
            }
            drawScrollHints(c)
            c.restore()
        }

        /** 分类可横向滚动时两端 20dp 渐隐 + 12dp 箭头；滑到头隐藏对应一侧。 Edge fades and chevrons. */
        private fun drawScrollHints(c: Canvas) {
            val pal = kb.palette
            val m = kb.metrics
            val fw = m.dp(20f)
            val bg = pal.background
            if (fadeColor != bg || fadeW != fw) {
                fadeColor = bg; fadeW = fw
                // 外侧 40% 不透明，箭头落在实色上。 The outer 40% is solid so the chevron sits on plain background.
                val clear = bg and 0x00ffffff
                fadeL = LinearGradient(0f, 0f, fw, 0f, intArrayOf(bg, bg, clear), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
                fadeR = LinearGradient(0f, 0f, fw, 0f, intArrayOf(clear, bg, bg), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            }
            val cy = height / 2f
            if (tabScroll > 1f) {
                c.save(); c.translate(tabs.left, 0f)
                fadePaint.shader = fadeL; c.drawRect(0f, 0f, fw, height.toFloat(), fadePaint)
                c.restore()
                kb.icons.draw(c, R.drawable.ic_chevron_left, pal.labelHint, tabs.left + m.dp(5f), cy, m.dp(12f))
            }
            if (tabScroll < maxTabScroll() - 1f) {
                c.save(); c.translate(tabs.right - fw, 0f)
                fadePaint.shader = fadeR; c.drawRect(0f, 0f, fw, height.toFloat(), fadePaint)
                c.restore()
                kb.icons.draw(c, R.drawable.ic_chevron_right, pal.labelHint, tabs.right - m.dp(5f), cy, m.dp(12f))
            }
        }

        private val fadePaint = Paint()
        private var fadeL: LinearGradient? = null
        private var fadeR: LinearGradient? = null
        private var fadeColor = 0
        private var fadeW = 0f

        // 无障碍：返回 / 分类 / 锁定 / 删除。 Accessibility: back, categories, lock, delete.
        private val a11y = VirtualA11y(this, object : VirtualA11y.Source {
            override fun a11yIds() = intArrayOf(BACK) + IntArray(cats.size) { it } + intArrayOf(LOCK, DEL)
            override fun a11yBounds(id: Int, out: RectF): Boolean {
                if (width == 0) return false
                geometry()
                when (id) {
                    BACK -> out.set(back); LOCK -> out.set(lock); DEL -> out.set(del)
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
                MotionEvent.ACTION_MOVE -> if (pressed >= 0 || dragging) {
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
    }
}
