package com.weavetext.ime.ui.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.view.MotionEvent
import android.view.View
import com.weavetext.ime.R
import kotlin.math.max
import kotlin.math.min

/**
 * 面板顶部的搜索行：放大镜、查询文字（含占位提示）、清除与收起。只负责显示与两个按钮，查询状态由面板持有。
 * 输入法窗口拿不到输入焦点，面板里不能放会抢焦点的 EditText（会顶起另一个输入法、挡住键盘），所以查询
 * 来自键盘本身：中文模式按字母键加拼音，英文模式按字母。按键由 [SymbolPanel] 等面板转交。
 * A search row for panels: magnifier, query (or placeholder), clear and close. It only draws and owns two buttons;
 * the query itself belongs to the panel. An IME window never takes input focus, so an EditText inside a panel would
 * raise a second IME over this one; the query comes from the keyboard itself instead (pinyin letters in Chinese,
 * plain letters in English), and panels forward those keys here.
 */
@SuppressLint("ViewConstructor")
class SearchRow(ctx: Context, private val kb: WeaveKeyboard, placeholder: String) : View(ctx) {
    var placeholder: String = placeholder
        set(v) { field = v; invalidate() }
    var onClose: (() -> Unit)? = null
    /** 清除按钮（面板清空自己的查询）。 The clear button (the panel clears its own query). */
    var onClear: (() -> Unit)? = null
    /** 点搜索区本身：面板据此把字母键盘展开（面板盖住主键盘时没有别的地方能打字）。 Tapping the field itself lets the
     * panel raise its own letter pad, since it covers the main keyboard. */
    var onOpen: (() -> Unit)? = null

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).zh()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val search = RectF()
    private val clear = RectF()
    private val close = RectF()
    /** 可点的输入区：放大镜到清除键之间。 The tappable field between the magnifier and the clear button. */
    private val field = RectF()
    private var pressed = NONE
    /** 已确定的查询。 The committed query. */
    private var query = ""
    /** 组合中的拼音（中文模式按下字母时），画在查询之后加下划线。 The pinyin being composed, underlined after the query. */
    private var composing = ""

    /** 查询与组合中的拼音。 The query plus the pinyin being composed. */
    val text get() = query + composing
    val active get() = text.isNotEmpty()

    /** 面板推送当前查询与组合串（不回调，避免来回通知）。 The panel pushes the query and composition (no callbacks). */
    fun show(query: String, composing: String) {
        if (query == this.query && composing == this.composing) return
        this.query = query.take(MAX_QUERY)
        this.composing = composing
        invalidate()
    }

    private fun geometry() {
        val m = kb.metrics
        val pad = m.dp(8f)
        search.set(pad, m.dp(2f), pad + m.dp(30f), height - m.dp(2f))
        close.set(width - pad - m.dp(34f), m.dp(2f), width - pad, height - m.dp(2f))
        clear.set(close.left - m.dp(34f), m.dp(2f), close.left, height - m.dp(2f))
        field.set(search.left, m.dp(2f), clear.left, height - m.dp(2f))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = geometry()

    override fun onDraw(c: Canvas) {
        val pal = kb.palette
        val m = kb.metrics
        geometry()
        val cy = height / 2f
        p.color = pal.labelSecondary
        kb.icons.draw(c, R.drawable.ic_search, if (active) pal.keyAccent else pal.labelSecondary, search.centerX(), cy, m.dp(18f))
        val textLeft = search.right + m.dp(4f)
        val textRight = clear.left - m.dp(6f)
        p.typeface = Typeface.DEFAULT
        p.textSize = m.panel(14f)
        p.textAlign = Paint.Align.LEFT
        val base = cy - (p.ascent() + p.descent()) / 2
        if (!active) {
            p.color = pal.labelHint
            clipText(placeholder, textLeft, textRight, c, base)
        } else {
            // 查询滚动到尾部：总是露出刚打的字。 The query scrolls to its end: the newest letters stay visible.
            val shown = text
            p.color = pal.label
            val w = p.measureText(shown)
            val avail = (textRight - textLeft).coerceAtLeast(1f)
            val sx = max(0f, w - avail)
            c.save()
            c.clipRect(textLeft, 0f, textRight, height.toFloat())
            c.drawText(shown, textLeft - sx, base, p)
            if (composing.isNotEmpty()) {
                // 组合中的拼音加下划线，和候选栏一致。 The composed pinyin is underlined, like in the candidate bar.
                val start = max(textLeft, textLeft - sx + p.measureText(query))
                val end = min(textRight, textLeft - sx + w)
                if (end > start) c.drawRect(start, base + m.dp(2f), end, base + m.dp(3f), fill.also { it.color = pal.keyAccent })
            }
            c.restore()
        }
        if (active) {
            if (pressed == CLEAR) { p.color = pal.toolbarActive; c.drawRoundRect(clear, m.dp(9f), m.dp(9f), p) }
            kb.icons.draw(c, R.drawable.ic_close, pal.icon, clear.centerX(), cy, m.dp(16f))
        }
        if (pressed == CLOSE) { p.color = pal.toolbarActive; c.drawRoundRect(close, m.dp(9f), m.dp(9f), p) }
        kb.icons.draw(c, R.drawable.ic_chevron_down, pal.icon, close.centerX(), cy, m.dp(18f))
        p.color = pal.divider
        c.drawRect(0f, height - max(1f, m.dp(0.5f)), width.toFloat(), height.toFloat(), p)
    }

    private fun clipText(text: String, left: Float, right: Float, c: Canvas, base: Float) {
        c.save()
        c.clipRect(left, 0f, right, height.toFloat())
        c.drawText(text.ellipsize(p, right - left), left, base, p)
        c.restore()
    }

    private fun String.ellipsize(paint: Paint, width: Float): String {
        if (paint.measureText(this) <= width) return this
        var end = length
        while (end > 1 && paint.measureText(substring(0, end) + "…") > width) end--
        return substring(0, end) + "…"
    }

    private fun hitAt(x: Float, y: Float): Int = when {
        close.contains(x, y) -> CLOSE
        active && clear.contains(x, y) -> CLEAR
        field.contains(x, y) -> FIELD
        else -> NONE
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hitAt(e.x, e.y)
                if (pressed != NONE) kb.feedback.key(this)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val i = pressed
                pressed = NONE
                if (i != NONE && i == hitAt(e.x, e.y)) when (i) {
                    CLEAR -> onClear?.invoke()
                    CLOSE -> onClose?.invoke()
                    FIELD -> onOpen?.invoke()
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = NONE; invalidate() }
        }
        return true
    }

    private val a11y = VirtualA11y(this, object : VirtualA11y.Source {
        override fun a11yIds() = intArrayOf(FIELD) + intArrayOf(CLEAR, CLOSE).filter { it != CLEAR || active }.toIntArray()
        override fun a11yBounds(id: Int, out: RectF): Boolean {
            geometry()
            out.set(when (id) { FIELD -> field; CLEAR -> clear; else -> close })
            return !out.isEmpty
        }
        override fun a11yLabel(id: Int) = when (id) {
            FIELD -> if (active) "搜索：$text" else placeholder
            CLEAR -> "清除搜索"
            else -> "退出搜索"
        }
        override fun a11yClick(id: Int): Boolean {
            when (id) {
                FIELD -> onOpen?.invoke()
                CLEAR -> onClear?.invoke()
                else -> onClose?.invoke()
            }
            return true
        }
    })

    override fun getAccessibilityNodeProvider(): android.view.accessibility.AccessibilityNodeProvider = a11y
    override fun dispatchHoverEvent(event: MotionEvent): Boolean = a11y.onHover(event) || super.dispatchHoverEvent(event)

    companion object {
        private const val NONE = -1
        private const val FIELD = 0
        private const val CLEAR = 1
        private const val CLOSE = 2
        /** 查询上限（字符）。 Max query length in chars. */
        private const val MAX_QUERY = 48
    }
}
