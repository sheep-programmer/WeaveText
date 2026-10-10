package com.weavetext.ime.ui.keyboard

import android.view.KeyEvent
import com.weavetext.ime.R

/**
 * 光标编辑面板（02 §9）：4 × 3，方向键在中间排成十字，两侧只留最常用的复制、粘贴、全选、开头、末尾。
 * Cursor edit panel: 4 × 3 with the arrows as a cross in the middle and only the common actions around them.
 */
class CursorPanel(kb: WeaveKeyboard) : KbPanel(kb) {
    override val toolIndex = 3
    private val pad = PadView(kb.ctx, kb)
    override val view = pad
    /** 扩选模式。 Selection mode. */
    var selecting = false
        private set

    private val keys = listOf(
        PadKey(COPY, "复制"),
        PadKey(UP, icon = R.drawable.ic_chevron_up, style = KeyStyle.CHAR).apply { repeat = true },
        PadKey(PASTE, "粘贴"),
        PadKey(BACKSPACE, icon = R.drawable.ic_backspace).apply { repeat = true },
        PadKey(LEFT, icon = R.drawable.ic_chevron_left, style = KeyStyle.CHAR).apply { repeat = true },
        PadKey(SELECT, "选择", style = KeyStyle.CHAR),
        PadKey(RIGHT, icon = R.drawable.ic_chevron_right, style = KeyStyle.CHAR).apply { repeat = true },
        PadKey(SELECT_ALL, "全选"),
        PadKey(HOME, "开头"),
        PadKey(DOWN, icon = R.drawable.ic_chevron_down, style = KeyStyle.CHAR).apply { repeat = true },
        PadKey(END, "末尾"),
        PadKey(BACK, "返回", style = KeyStyle.ACCENT),
    )

    init {
        pad.keys = keys
        pad.layouter = { w, h ->
            val m = kb.metrics
            val spec = keys.mapIndexed { i, k -> intArrayOf(k.id, i % 4, i / 4, 1, 1) }
            PadView.grid(keys, spec, WEIGHTS, ROWS, w, h, m)
            for (k in keys) { k.textSize = m.label(16f); k.large = true; k.iconSize = m.icon(24f) }
        }
        pad.onTap = { onKey(it.id) }
    }

    override fun applyTheme() = pad.relayout()

    override fun onShow() {
        selecting = false
        refreshSelection(kb.controller.hasSelection())
    }

    /** 选区变化（onUpdateSelection）。 Selection changed. */
    fun onSelection(has: Boolean) = refreshSelection(has)

    private fun refreshSelection(has: Boolean) {
        keys.first { it.id == SELECT }.active = selecting
        keys.first { it.id == COPY }.disabled = !has
        pad.invalidate()
        if(has) kb.topBar.showAction("已选中文字", "重新选词", 30000) {
            if(kb.controller.reselect()) kb.closePanel()
            else kb.topBar.showAction("请选择一个可读取的中文词或英文词",null,2500,null)
        }
    }

    private fun onKey(id: Int) {
        val c = kb.controller
        when (id) {
            COPY -> c.contextMenuAction(android.R.id.copy)
            PASTE -> c.contextMenuAction(android.R.id.paste)
            SELECT_ALL -> c.contextMenuAction(android.R.id.selectAll)
            BACKSPACE -> c.onBackspace()
            UP -> c.cursorArrow(KeyEvent.KEYCODE_DPAD_UP, selecting)
            DOWN -> c.cursorArrow(KeyEvent.KEYCODE_DPAD_DOWN, selecting)
            LEFT -> c.cursorArrow(KeyEvent.KEYCODE_DPAD_LEFT, selecting)
            RIGHT -> c.cursorArrow(KeyEvent.KEYCODE_DPAD_RIGHT, selecting)
            HOME -> c.cursorToEdge(end = false, select = selecting)
            END -> c.cursorToEdge(end = true, select = selecting)
            SELECT -> { selecting = !selecting; refreshSelection(c.hasSelection()) }
            BACK -> kb.closePanel()
        }
        if (id != SELECT && id != BACK) refreshSelection(c.hasSelection())
    }

    companion object {
        private val WEIGHTS = floatArrayOf(1f, 1f, 1f, 1f)
        private const val ROWS = 3
        const val COPY = 1; const val UP = 2; const val PASTE = 3; const val BACKSPACE = 4
        const val LEFT = 6; const val SELECT = 7; const val RIGHT = 8
        const val SELECT_ALL = 11; const val DOWN = 12; const val BACK = 14
        const val HOME = 5; const val END = 10
    }
}
