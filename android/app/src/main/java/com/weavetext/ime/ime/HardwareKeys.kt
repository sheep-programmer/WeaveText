package com.weavetext.ime.ime

import android.view.KeyEvent

/**
 * 实体键盘的按键：字母、数字、标点、空格、回车、退格经过内核；组合中 1–9 选候选、Esc 取消、方向键先上屏原始字母；
 * Ctrl+空格切换中/英。其余组合键（Ctrl/Alt/Meta 快捷键等）交还给 App。
 * Physical-keyboard keys: letters, digits, punctuation, space, enter and backspace go through the engine;
 * while composing 1–9 pick a candidate, Esc cancels and arrows commit the raw letters first; Ctrl+Space
 * toggles Chinese/English. Other shortcuts (Ctrl/Alt/Meta) go back to the app.
 */
class HardwareKeys(private val controller: InputController) {
    /** 按下时被我们处理的键，对应的抬起也吞掉。 Keys we consumed on DOWN; their UP is consumed too. */
    private val consumed = HashSet<Int>()

    fun onKeyDown(keyCode: Int, e: KeyEvent): Boolean {
        val handled = handle(keyCode, e)
        if (handled) consumed += keyCode else consumed -= keyCode
        return handled
    }

    fun onKeyUp(keyCode: Int, @Suppress("UNUSED_PARAMETER") e: KeyEvent): Boolean = consumed.remove(keyCode)

    private fun handle(keyCode: Int, e: KeyEvent): Boolean {
        val s = controller.state
        if (!s.engineReady) return false
        val composing = s.composing
        if (keyCode == KeyEvent.KEYCODE_SPACE && e.isCtrlPressed && !e.isAltPressed && !e.isMetaPressed) {
            if (e.repeatCount == 0) controller.toggleChinese()
            return true
        }
        // 快捷键交给 App（组合中先上屏原始字母）。 Shortcuts belong to the app (flush the raw letters first).
        if (e.isCtrlPressed || e.isAltPressed || e.isMetaPressed) {
            if (composing && !KeyEvent.isModifierKey(keyCode)) controller.commitRaw()
            return false
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> { controller.onBackspace(); return true }
            KeyEvent.KEYCODE_SPACE -> { controller.onSpace(); return true }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // 不在组合中时回车交给 App（多行换行、表单提交等按它自己的规则）。 Not composing: the app handles Enter.
                if (!composing) return false
                controller.onEnter(); return true
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                if (!composing) return false
                controller.reset(); return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_TAB -> {
                if (composing) controller.commitRaw()
                return false
            }
        }
        if (composing && keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 && !e.isShiftPressed) {
            val i = keyCode - KeyEvent.KEYCODE_1
            if (i < s.candidates.size) controller.onCandidate(i)
            return true
        }
        val ch = e.getUnicodeChar(e.metaState)
        if (ch == 0 || ch and android.view.KeyCharacterMap.COMBINING_ACCENT != 0 || Character.isISOControl(ch)) return false
        when {
            // 中文模式下的大写字母直接上屏。 Uppercase letters commit as-is in Chinese mode.
            s.chinese && ch in 'A'.code..'Z'.code -> controller.onText(ch.toChar().toString())
            else -> controller.onChar(ch)
        }
        return true
    }
}
