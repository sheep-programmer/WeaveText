package com.weavetext.ime.ui.keyboard

import android.view.KeyEvent
import com.weavetext.ime.R

/**
 * 光标编辑面板（02 §9）：5 × 3。左边三列是方向键（上方是开头、末尾，中间是选择），右边两列是复制、剪切、
 * 粘贴与删除、全选、返回；底行两端是撤销与重做。撤销 / 重做交给编辑器（`performContextMenuAction`），
 * 在网页字段与终端里退回 Ctrl+Z / Ctrl+Shift+Z，因此能连续撤销多步。
 * Cursor edit panel, 5 × 3: arrows on the left three columns (start/end above them, select in the middle), copy, cut,
 * paste, delete, select all and back on the right two, and undo / redo at the ends of the bottom row. Undo and redo
 * go to the editor (`performContextMenuAction`, falling back to Ctrl+Z / Ctrl+Shift+Z in web fields and terminals),
 * so they repeat for as many steps as the editor keeps.
 */
class CursorPanel(kb: WeaveKeyboard) : KbPanel(kb) {
    override val toolIndex = 3
    private val pad = PadView(kb.ctx, kb)
    override val view = pad
    /** 扩选模式。 Selection mode. */
    var selecting = false
        private set

    private val keys = listOf(
        PadKey(HOME, "开头"),
        PadKey(UP, icon = R.drawable.ic_chevron_up, style = KeyStyle.CHAR).apply { repeat = true },
        PadKey(END, "末尾"),
        PadKey(COPY, "复制"),
        PadKey(BACKSPACE, icon = R.drawable.ic_backspace).apply { repeat = true },
        PadKey(LEFT, icon = R.drawable.ic_chevron_left, style = KeyStyle.CHAR).apply { repeat = true },
        PadKey(SELECT, "选择", style = KeyStyle.CHAR),
        PadKey(RIGHT, icon = R.drawable.ic_chevron_right, style = KeyStyle.CHAR).apply { repeat = true },
        PadKey(CUT, "剪切"),
        PadKey(SELECT_ALL, "全选"),
        PadKey(DOWN, icon = R.drawable.ic_chevron_down, style = KeyStyle.CHAR).apply { repeat = true },
        PadKey(PASTE, "粘贴"),
        PadKey(BACK, "返回", style = KeyStyle.ACCENT),
        PadKey(UNDO, icon = R.drawable.ic_undo).apply { description = "撤销"; repeat = true },
        PadKey(REDO, icon = R.drawable.ic_redo).apply { description = "重做"; repeat = true },
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
            COPY -> c.contextMenuAction(android.R.id.copy)
            CUT -> c.contextMenuAction(android.R.id.cut)
            PASTE -> c.contextMenuAction(android.R.id.paste)
            SELECT_ALL -> c.contextMenuAction(android.R.id.selectAll)
            BACKSPACE -> c.onBackspace()
            UP -> c.cursorArrow(KeyEvent.KEYCODE_DPAD_UP, selecting)
            DOWN -> c.cursorArrow(KeyEvent.KEYCODE_DPAD_DOWN, selecting)
            LEFT -> c.cursorArrow(KeyEvent.KEYCODE_DPAD_LEFT, selecting)
            RIGHT -> c.cursorArrow(KeyEvent.KEYCODE_DPAD_RIGHT, selecting)
            HOME -> c.cursorToEdge(end = false, select = selecting)
            END -> c.cursorToEdge(end = true, select = selecting)
            UNDO -> c.undoRedo(redo = false)
            REDO -> c.undoRedo(redo = true)
            SELECT -> { selecting = !selecting; refreshSelection(c.hasSelection()) }
            BACK -> kb.closePanel()
        }
        if (id != SELECT && id != BACK && id != UNDO && id != REDO) refreshSelection(c.hasSelection())
    }

    companion object {
        private const val SELECTED = "已选中文字"
        private val WEIGHTS = floatArrayOf(1f, 1f, 1f, 1f, 1f)
        private const val ROWS = 3
        const val COPY = 1; const val UP = 2; const val PASTE = 3; const val BACKSPACE = 4
        const val LEFT = 6; const val SELECT = 7; const val RIGHT = 8; const val CUT = 9
        const val SELECT_ALL = 11; const val DOWN = 12; const val BACK = 14
        const val HOME = 5; const val END = 10
        const val UNDO = 15; const val REDO = 16
        /** (键, 列, 行, 跨列, 跨行)。 (key, column, row, column span, row span). */
        private val SPEC = listOf(
            intArrayOf(HOME, 0, 0, 1, 1), intArrayOf(UP, 1, 0, 1, 1), intArrayOf(END, 2, 0, 1, 1),
            intArrayOf(COPY, 3, 0, 1, 1), intArrayOf(BACKSPACE, 4, 0, 1, 1),
            intArrayOf(LEFT, 0, 1, 1, 2), intArrayOf(SELECT, 1, 1, 1, 1), intArrayOf(RIGHT, 2, 1, 1, 2),
            intArrayOf(CUT, 3, 1, 1, 1), intArrayOf(SELECT_ALL, 4, 1, 1, 1),
            // 底行：撤销、下移、重做、粘贴、返回。 Bottom row: undo, down, redo, paste, back.
            intArrayOf(UNDO, 0, 2, 1, 1), intArrayOf(DOWN, 1, 2, 1, 1), intArrayOf(REDO, 2, 2, 1, 1),
            intArrayOf(PASTE, 3, 2, 1, 1), intArrayOf(BACK, 4, 2, 1, 1),
        )
    }
}
