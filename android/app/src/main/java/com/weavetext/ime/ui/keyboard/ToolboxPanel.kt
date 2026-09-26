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
import com.weavetext.ime.settings.WeavePrefs

/**
 * 工具箱面板（02 §11）：4 列 × 3 行卡片；开关类开启时染强调色。
 * Toolbox: 4 × 3 cards; toggles tint when on.
 */
class ToolboxPanel(kb: WeaveKeyboard) : KbPanel(kb), PrefAware {
    override val toolIndex = 0
    override val view = Grid(kb.ctx)

    private class Item(val id: Int, val icon: Int, val text: String?, val label: String)

    private val items = listOf(
        Item(SCHEMES, R.drawable.ic_keyboard, null, "输入方案"),
        Item(HEIGHT, R.drawable.ic_resize, null, "键盘调节"),
        Item(DARK, R.drawable.ic_moon, null, "深色模式"),
        Item(TRAD, 0, "繁", "繁体输出"),
        Item(EMOJI, R.drawable.ic_emoji, null, "表情"),
        Item(PHRASES, R.drawable.ic_quick_phrase, null, "常用语"),
        Item(ONE_HAND, R.drawable.ic_one_hand, null, "单手模式"),
        Item(ENGINES, R.drawable.ic_waveform, null, "语音引擎"),
        Item(CURSOR, R.drawable.ic_cursor, null, "光标编辑"),
        Item(CLIPBOARD, R.drawable.ic_clipboard, null, "剪贴板"),
        Item(SETTINGS, R.drawable.ic_settings, null, "设置"),
        Item(HELP, R.drawable.ic_info, null, "帮助"),
    )

    override fun applyTheme() = view.invalidate()
    override fun onShow() = view.invalidate()
    override fun onPref(key: String?) = view.invalidate()

    private fun isOn(id: Int): Boolean = when (id) {
        DARK -> WeavePrefs.theme(kb.prefs) != "system"
        TRAD -> WeavePrefs.traditional(kb.prefs)
        ONE_HAND -> WeavePrefs.oneHand(kb.prefs) != 0
        else -> false
    }

    private fun label(it: Item): String = when (it.id) {
        DARK -> when (WeavePrefs.theme(kb.prefs)) { "dark" -> "深色：开"; "light" -> "深色：关"; else -> it.label }
        ONE_HAND -> when (WeavePrefs.oneHand(kb.prefs)) { 1 -> "单手：靠左"; 2 -> "单手：靠右"; else -> it.label }
        else -> it.label
    }

    private fun onItem(id: Int) {
        val p = kb.prefs
        when (id) {
            SCHEMES -> kb.showPanel("picker")
            HEIGHT -> kb.showPanel("height")
            DARK -> {
                // 跟随系统 → 深色 → 浅色 → 跟随系统。 system → dark → light → system.
                val next = when (WeavePrefs.theme(p)) { "system" -> "dark"; "dark" -> "light"; else -> "system" }
                p.edit().putString(WeavePrefs.THEME, next).apply()
            }
            TRAD -> p.edit().putBoolean(WeavePrefs.TRADITIONAL, !WeavePrefs.traditional(p)).apply()
            EMOJI -> { kb.showPanel("symbol"); (kb.panelNamed("symbol") as? SymbolPanel)?.selectEmoji() }
            PHRASES -> kb.showPanel("phrases")
            ONE_HAND -> p.edit().putInt(WeavePrefs.ONE_HAND, (WeavePrefs.oneHand(p) + 1) % 3).apply()
            ENGINES -> kb.showEngineSheet()
            CURSOR -> kb.showPanel("cursor")
            CLIPBOARD -> kb.showPanel("clipboard")
            SETTINGS -> kb.openSettings(null)
            HELP -> kb.openSettings("about/help")
        }
        view.invalidate()
    }

    @SuppressLint("ViewConstructor")
    inner class Grid(c: Context) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val rect = RectF()
        private var pressed = -1

        private fun cell(i: Int, out: RectF) {
            val m = kb.metrics
            val pad = m.dp(12f)
            val gap = m.dp(8f)
            val cw = (width - 2 * pad - 3 * gap) / 4
            val ch = (height - 2 * pad - 2 * gap) / 3
            val col = i % 4
            val row = i / 4
            out.set(pad + col * (cw + gap), pad + row * (ch + gap), pad + col * (cw + gap) + cw, pad + row * (ch + gap) + ch)
        }

        override fun onDraw(c: Canvas) {
            val pal = kb.palette
            val m = kb.metrics
            for ((i, it) in items.withIndex()) {
                cell(i, rect)
                val on = isOn(it.id)
                p.color = when { i == pressed -> pal.keyPressed; on -> pal.accentSoft; else -> pal.card }
                c.drawRoundRect(rect, m.dp(12f), m.dp(12f), p)
                val fg = if (on) pal.keyAccent else pal.icon
                val iconCy = rect.top + rect.height() * 0.40f
                if (it.icon != 0) {
                    kb.icons.draw(c, it.icon, fg, rect.centerX(), iconCy, m.dp(26f))
                } else {
                    p.typeface = Typeface.DEFAULT
                    p.textSize = m.dp(22f)
                    p.color = fg
                    c.drawText(it.text!!, rect.centerX(), iconCy - (p.ascent() + p.descent()) / 2, p)
                }
                p.typeface = Typeface.DEFAULT
                p.textSize = m.dp(12f)
                p.color = if (on) pal.keyAccent else pal.labelSecondary
                c.drawText(label(it), rect.centerX(), iconCy + m.dp(13f) + m.dp(6f) - p.ascent() * 0.8f, p)
            }
        }

        private fun hitAt(x: Float, y: Float): Int {
            for (i in items.indices) { cell(i, rect); if (rect.contains(x, y)) return i }
            return -1
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { pressed = hitAt(e.x, e.y); if (pressed >= 0) kb.feedback.key(this); invalidate() }
                MotionEvent.ACTION_MOVE -> if (pressed >= 0 && hitAt(e.x, e.y) != pressed) { pressed = -1; invalidate() }
                MotionEvent.ACTION_UP -> { val i = pressed; pressed = -1; invalidate(); if (i >= 0) onItem(items[i].id) }
                MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
            }
            return true
        }
    }

    companion object {
        const val SCHEMES = 0; const val HEIGHT = 1; const val DARK = 2; const val TRAD = 3
        const val EMOJI = 4; const val PHRASES = 5; const val ONE_HAND = 6; const val ENGINES = 7
        const val CURSOR = 8; const val CLIPBOARD = 9; const val SETTINGS = 10; const val HELP = 11
    }
}

/**
 * 键盘调节浮层（02 §11 #2）：5 档键高分段条，键盘本身即实时预览；与设置「键盘高度」共用一个值。
 * Height adjust overlay: five-step segmented bar; the keyboard itself is the live preview.
 */
class HeightPanel(kb: WeaveKeyboard) : KbPanel(kb), PrefAware {
    override val toolIndex = 0
    override val view = HeightView(kb.ctx)

    override fun applyTheme() = view.invalidate()
    override fun onPref(key: String?) = view.invalidate()

    @SuppressLint("ViewConstructor")
    inner class HeightView(c: Context) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val medium = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 500, false) else Typeface.DEFAULT_BOLD
        private var pressed = -2

        private fun seg(i: Int, out: RectF) {
            val m = kb.metrics
            val pad = m.dp(16f)
            val w = (width - 2 * pad) / 5
            val top = m.dp(64f)
            out.set(pad + i * w, top, pad + (i + 1) * w, top + m.dp(44f))
        }

        override fun onDraw(c: Canvas) {
            val pal = kb.palette
            val m = kb.metrics
            p.typeface = medium; p.textSize = m.dp(15f); p.color = pal.label; p.textAlign = Paint.Align.LEFT
            val ty = m.dp(22f) - (p.ascent() + p.descent()) / 2
            c.drawText("键盘高度", m.dp(16f), ty, p)
            p.textAlign = Paint.Align.RIGHT; p.color = pal.candidateFirst
            c.drawText("完成", width - m.dp(16f), ty, p)
            // 分段条底 / track
            seg(0, rect)
            val left = rect.left
            seg(4, rect)
            rect.left = left
            p.color = pal.keyFunc
            c.drawRoundRect(rect, m.dp(10f), m.dp(10f), p)
            val level = WeavePrefs.heightLevel(kb.prefs)
            p.textAlign = Paint.Align.CENTER
            for (i in 0..4) {
                seg(i, rect)
                if (i == level) {
                    rect.inset(m.dp(3f), m.dp(3f))
                    p.color = pal.key
                    c.drawRoundRect(rect, m.dp(8f), m.dp(8f), p)
                    seg(i, rect)
                }
                p.typeface = if (i == level) medium else Typeface.DEFAULT
                p.textSize = m.dp(14f)
                p.color = if (i == level) pal.keyAccent else pal.labelSecondary
                c.drawText(NAMES[i], rect.centerX(), rect.centerY() - (p.ascent() + p.descent()) / 2, p)
            }
            p.typeface = Typeface.DEFAULT; p.textSize = m.dp(12f); p.color = pal.labelSecondary
            c.drawText("拖动选择档位，键盘高度立即变化；也可在设置 App「外观与手感」中调整", width / 2f, rect.bottom + m.dp(32f), p)
        }

        private fun hitAt(x: Float, y: Float): Int {
            val m = kb.metrics
            if (y < m.dp(44f) && x > width - m.dp(80f)) return -1
            for (i in 0..4) { seg(i, rect); rect.inset(0f, -m.dp(12f)); if (rect.contains(x, y)) return i }
            return -2
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val i = hitAt(e.x, e.y)
                    if (i >= 0 && i != WeavePrefs.heightLevel(kb.prefs)) {
                        kb.feedback.haptic(this)
                        kb.prefs.edit().putInt(WeavePrefs.HEIGHT_LEVEL, i).apply()
                    }
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) pressed = i
                }
                MotionEvent.ACTION_UP -> { if (pressed == -1 && hitAt(e.x, e.y) == -1) kb.closePanel(); pressed = -2 }
            }
            return true
        }
    }

    companion object {
        val NAMES = arrayOf("紧凑", "较矮", "适中", "较高", "高")
    }
}
