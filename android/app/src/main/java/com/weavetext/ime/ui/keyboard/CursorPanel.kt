package com.weavetext.ime.ui.keyboard

import android.view.KeyEvent
import com.weavetext.ime.R

/**
 * 光标编辑面板（02 §9）：5 列 × 4 行，与键盘行数一致，列权重 1 | 1.25 | 1.25 | 1.25 | 1。
 * 方向键在中间围成十字；左上 Tab、右上删除，底行是撤销、重做、按词移动与回车，返回在第 3 行右端。
 * 每格只放一个键——此前方向键跨两行，和底行的撤销 / 重做落在同一格，点哪一半都会触发另一个键。
 * 撤销 / 重做交给编辑器（`performContextMenuAction`），在网页字段与终端里退回 Ctrl+Z / Ctrl+Shift+Z，可连续撤销多步。
 * Cursor edit panel, 5 × 4 like the keyboard rows, column weights 1 | 1.25 | 1.25 | 1.25 | 1: arrows as a cross in the
 * middle, Tab and Delete at the top corners, undo / redo / word movement / Enter on the bottom row and Back at the right
 * end of the third row. One key per cell — the arrows used to span two rows and share cells with undo / redo, so either
 * half of such a cell fired the other key. Undo and redo go to the editor (`performContextMenuAction`, falling back to
 * Ctrl+Z / Ctrl+Shift+Z in web fields and terminals), so they repeat for as many steps as the editor keeps.
 */
class CursorPanel(kb: WeaveKeyboard) : KbPanel(kb) {
    override val toolIndex = 3
    private val pad = PadView(kb.ctx, kb)
    override val view = pad
    /** 扩选模式。 Selection mode. */
    var selecting = false
        private set

    private val keys = listOf(
        PadKey(TAB, "Tab"),
        PadKey(COPY, "复制"),
        PadKey(UP, icon = R.drawable.ic_chevron_up, style = KeyStyle.CHAR).apply { repeat = true },
        PadKey(PASTE, "粘贴"),
        PadKey(BACKSPACE, icon = R.drawable.ic_backspace).apply { repeat = true },
        PadKey(HOME, "开头"),
        PadKey(LEFT, icon = R.drawable.ic_chevron_left, style = KeyStyle.CHAR).apply { repeat = true },
        PadKey(SELECT, "选择", style = KeyStyle.CHAR),
        PadKey(RIGHT, icon = R.drawable.ic_chevron_right, style = KeyStyle.CHAR).apply { repeat = true },
        PadKey(FORWARD, "Del").apply { description = "向后删除" },
        PadKey(END, "末尾"),
        PadKey(SELECT_ALL, "全选"),
        PadKey(DOWN, icon = R.drawable.ic_chevron_down, style = KeyStyle.CHAR).apply { repeat = true },
        PadKey(CUT, "剪切"),
        PadKey(BACK, "返回", style = KeyStyle.ACCENT),
        PadKey(UNDO, icon = R.drawable.ic_undo).apply { description = "撤销"; repeat = true },
        PadKey(REDO, icon = R.drawable.ic_redo).apply { description = "重做"; repeat = true },
        PadKey(WORD_LEFT, "词 ←").apply { repeat = true },
        PadKey(WORD_RIGHT, "词 →").apply { repeat = true },
        PadKey(ENTER, "⏎", style = KeyStyle.ACCENT),
    )

    init {
        pad.keys = keys
        pad.layouter = { w, h ->
            val m = kb.metrics
            PadView.grid(keys, SPEC, WEIGHTS, ROWS, w, h, m)
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
        keys.first { it.id == CUT }.disabled = !has
        pad.invalidate()
        if(has) kb.topBar.showAction(SELECTED, "重新选词", 30000) {
            if(kb.controller.reselect()) kb.closePanel()
            else kb.topBar.showAction("请选择一个可读取的中文词或英文词",null,2500,null)
        }
        // 剪切、删除后选区没了：「已选中文字」随之收起。 The selection is gone (cut, deleted): drop its strip.
        else if (kb.topBar.actionMessage == SELECTED) kb.topBar.clearAction()
    }

    private fun onKey(id: Int) {
        val c = kb.controller
        when (id) {
            TAB -> c.onText("\t")
            COPY -> c.contextMenuAction(android.R.id.copy)
            CUT -> c.contextMenuAction(android.R.id.cut)
            PASTE -> c.contextMenuAction(android.R.id.paste)
            SELECT_ALL -> c.contextMenuAction(android.R.id.selectAll)
            BACKSPACE -> c.onBackspace()
            FORWARD -> c.deleteForward()
            UP -> c.cursorArrow(KeyEvent.KEYCODE_DPAD_UP, selecting)
            DOWN -> c.cursorArrow(KeyEvent.KEYCODE_DPAD_DOWN, selecting)
            LEFT -> c.cursorArrow(KeyEvent.KEYCODE_DPAD_LEFT, selecting)
            RIGHT -> c.cursorArrow(KeyEvent.KEYCODE_DPAD_RIGHT, selecting)
            HOME -> c.cursorToEdge(end = false, select = selecting)
            END -> c.cursorToEdge(end = true, select = selecting)
            WORD_LEFT -> c.cursorWord(right = false, select = selecting)
            WORD_RIGHT -> c.cursorWord(right = true, select = selecting)
            UNDO -> c.undoRedo(redo = false)
            REDO -> c.undoRedo(redo = true)
            ENTER -> c.onEnter()
            SELECT -> { selecting = !selecting; refreshSelection(c.hasSelection()) }
            BACK -> kb.closePanel()
        }
        if (id != SELECT && id != BACK && id != UNDO && id != REDO && id != ENTER) refreshSelection(c.hasSelection())
    }

    companion object {
        private const val SELECTED = "已选中文字"
        private val WEIGHTS = floatArrayOf(1f, 1.25f, 1.25f, 1.25f, 1f)
        private const val ROWS = 4
        const val TAB = 1; const val COPY = 2; const val UP = 3; const val PASTE = 4; const val BACKSPACE = 5
        const val HOME = 6; const val LEFT = 7; const val SELECT = 8; const val RIGHT = 9; const val FORWARD = 10
        const val END = 11; const val SELECT_ALL = 12; const val DOWN = 13; const val CUT = 14; const val BACK = 15
        const val UNDO = 16; const val REDO = 17; const val WORD_LEFT = 18; const val WORD_RIGHT = 19
        const val ENTER = 20
        /** (键, 列, 行, 跨列, 跨行)。 (key, column, row, column span, row span). */
        private val SPEC = listOf(
            intArrayOf(TAB, 0, 0, 1, 1), intArrayOf(COPY, 1, 0, 1, 1), intArrayOf(UP, 2, 0, 1, 1),
            // 每格一个键：没有跨行键，底行不会和方向键叠在一起。 One key per cell: nothing spans rows, so the bottom row cannot collide.
            intArrayOf(PASTE, 3, 0, 1, 1), intArrayOf(BACKSPACE, 4, 0, 1, 1),
            intArrayOf(HOME, 0, 1, 1, 1), intArrayOf(LEFT, 1, 1, 1, 1), intArrayOf(SELECT, 2, 1, 1, 1),
            intArrayOf(RIGHT, 3, 1, 1, 1), intArrayOf(FORWARD, 4, 1, 1, 1),
            intArrayOf(END, 0, 2, 1, 1), intArrayOf(SELECT_ALL, 1, 2, 1, 1), intArrayOf(DOWN, 2, 2, 1, 1),
            intArrayOf(CUT, 3, 2, 1, 1), intArrayOf(BACK, 4, 2, 1, 1),
            intArrayOf(UNDO, 0, 3, 1, 1), intArrayOf(REDO, 1, 3, 1, 1),
            intArrayOf(WORD_LEFT, 2, 3, 1, 1), intArrayOf(WORD_RIGHT, 3, 3, 1, 1), intArrayOf(ENTER, 4, 3, 1, 1),
        )
    }
}
