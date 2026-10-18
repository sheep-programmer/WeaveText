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

/**
 * 面板里的紧凑字母键盘：面板盖住主键盘（`keyboardView` 被设为 INVISIBLE）时，搜索框仍需能打字，而输入法窗口
 * 拿不到焦点、不能放 EditText，所以由面板自带一排字母键。三行 QWERTY，末行末尾是退格（长按连删）。
 * 只出现在搜索行展开时：[SymbolPanel] 在表情 / 颜文字分类里常驻，[ClipboardPanel] 只在点开放大镜后展开。
 * A compact letter pad inside a panel. While a panel is open the main keyboard is INVISIBLE, yet the search box still
 * needs letters — and an IME window cannot host a focused EditText. Three QWERTY rows with Backspace (hold to repeat)
 * at the end of the last one. It only shows while a search box is open.
 */
@SuppressLint("ViewConstructor")
class PanelKeys(ctx: Context, private val kb: WeaveKeyboard) : View(ctx) {
    /** 字母按下。 A letter was pressed. */
    var onLetter: ((Char) -> Unit)? = null
    /** 退格；删空后由面板决定是否收起搜索。 Backspace; the panel decides whether an empty query closes the search. */
    var onBackspace: (() -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).zh().apply { textAlign = Paint.Align.CENTER }
    private val tmp = RectF()
    private val shadow = RectF()
    private val cell = RectF()
    private var down: Cell? = null
    private var repeated = false
    private val medium = if (android.os.Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 500, false) else Typeface.DEFAULT_BOLD

    /** 一个键位；[span] 以槽为单位，退格比字母宽。 One key; [span] counts slots, so Backspace is wider. */
    private class Cell(val ch: Char, val row: Int, val start: Float, val span: Float) {
        val backspace get() = ch == BACKSPACE
    }

    private val cells: List<Cell> = buildList {
        ROWS.forEachIndexed { row, letters ->
            val span = letters.length + if (row == ROWS.lastIndex) BACKSPACE_SPAN else 0f
            var x = (COLUMNS - span) / 2f
            for (ch in letters) {
                add(Cell(ch, row, x, 1f))
                x += 1f
            }
            if (row == ROWS.lastIndex) add(Cell(BACKSPACE, row, x, BACKSPACE_SPAN))
        }
    }

    private val repeatTask = object : Runnable {
        override fun run() {
            if (down?.backspace != true) return
            repeated = true
            onBackspace?.invoke()
            postDelayed(this, 60)
        }
    }

    /** 键高：按键盘行高收一点，面板里还要留给候选网格。 Key height, a little shorter than a keyboard row. */
    private fun keyH() = (kb.metrics.rowPitch * 0.62f).coerceIn(kb.metrics.dp(32f), kb.metrics.dp(46f))

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (keyH() * ROWS.size).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    private fun cellRect(c: Cell, out: RectF) {
        val m = kb.metrics
        val slot = width / COLUMNS
        val top = paddingTop.toFloat()
        val h = (height - paddingTop - paddingBottom) / ROWS.size.toFloat()
        out.set(c.start * slot, top + c.row * h, (c.start + c.span) * slot, top + (c.row + 1) * h)
        out.inset(m.insetH, m.insetV)
    }

    private fun hitAt(x: Float, y: Float): Cell? {
        for (c in cells) {
            cellRect(c, cell)
            if (cell.contains(x, y)) return c
        }
        return null
    }

    override fun onDraw(c: Canvas) {
        val pal = kb.palette
        val m = kb.metrics
        for (k in cells) {
            cellRect(k, cell)
            val pressed = k === down
            val bg = when {
                k.backspace -> if (pressed) pal.keyFuncPressed else pal.keyFunc
                pressed -> pal.keyPressed
                else -> pal.key
            }
            KeyPainter.draw(c, cell, tmp, shadow, fill, bg, pressed, m.keyRadius, pal, m)
            if (k.backspace) {
                kb.icons.draw(c, R.drawable.ic_backspace, pal.icon, cell.centerX(), cell.centerY(), m.icon(22f))
            } else {
                text.typeface = medium
                text.textSize = minOf(m.label(16f), cell.height() * 0.62f)
                text.color = pal.label
                val label = k.ch.uppercaseChar().toString()
                val w = text.measureText(label)
                val maxW = cell.width() - m.dp(6f)
                if (w > maxW && w > 0) text.textSize *= maxW / w
                c.drawText(label, cell.centerX(), cell.centerY() - (text.ascent() + text.descent()) / 2, text)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val c = hitAt(e.x, e.y) ?: return true
                down = c
                repeated = false
                kb.feedback.key(this)
                if (c.backspace) postDelayed(repeatTask, 400)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val c = down ?: return true
                // 滑出键位就取消，和键盘按键一致。 Leaving the key cancels it, like the main keyboard.
                if (hitAt(e.x, e.y) !== c) { removeCallbacks(repeatTask); down = null; invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(repeatTask)
                val c = down
                down = null
                // 长按已经连发过：松手不再补一下。 After auto-repeat the release adds nothing.
                if (c != null && !repeated && hitAt(e.x, e.y) === c) fire(c)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(repeatTask); down = null; invalidate() }
        }
        return true
    }

    private fun fire(c: Cell) {
        if (c.backspace) onBackspace?.invoke() else onLetter?.invoke(c.ch)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) { removeCallbacks(repeatTask); if (down != null) { down = null; invalidate() } }
    }

    private val a11y = VirtualA11y(this, object : VirtualA11y.Source {
        override fun a11yIds() = IntArray(cells.size) { it }
        override fun a11yBounds(id: Int, out: RectF): Boolean {
            val c = cells.getOrNull(id) ?: return false
            cellRect(c, out)
            return !out.isEmpty
        }
        override fun a11yLabel(id: Int): CharSequence? {
            val c = cells.getOrNull(id) ?: return null
            return if (c.backspace) "删除" else c.ch.toString()
        }
        override fun a11yClick(id: Int): Boolean {
            val c = cells.getOrNull(id) ?: return false
            fire(c)
            return true
        }
    })

    override fun getAccessibilityNodeProvider(): android.view.accessibility.AccessibilityNodeProvider = a11y
    override fun dispatchHoverEvent(event: MotionEvent): Boolean = a11y.onHover(event) || super.dispatchHoverEvent(event)

    companion object {
        private val ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        /** 每行槽位。 Slots per row. */
        private const val COLUMNS = 10f
        private const val BACKSPACE_SPAN = 1.4f
        /** 退格格用的占位字符（不是可打的字母）。 The placeholder for the Backspace cell. */
        private const val BACKSPACE = ' '
    }
}
