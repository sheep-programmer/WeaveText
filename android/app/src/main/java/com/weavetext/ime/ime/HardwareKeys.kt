package com.weavetext.ime.ime

import android.view.KeyEvent

/**
 * 实体键盘：上下 / Tab 选候选，PageUp / PageDown 每次移动九项，空格确认；未选候选时回车原样上屏。
 * Ctrl+空格切换中/英。其余组合键（Ctrl/Alt/Meta 快捷键等）交还给 App。
 * Physical-keyboard keys: letters, digits, punctuation, space, enter and backspace go through the engine;
 * arrows/Tab navigate candidates, PageUp/PageDown move nine items, Space confirms; Ctrl+Space
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
        // 内核未就绪时也经过控制器：拼音字母先记下，就绪后再重放。 Even before the engine is ready: letters are kept and replayed.
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
            // 实体键盘上两次空格就是两个空格，不改成句号。 Two spaces on a physical keyboard stay two spaces.
            KeyEvent.KEYCODE_SPACE -> {
                val visible = controller.visibleCandidates
                if (visible?.highlightedCandidate?.let { it >= 0 } == true && visible.candidateGeneration != s.candidateGeneration) return true
                if (!composing && s.highlightedCandidate >= 0) {
                    controller.onCandidate(s.highlightedCandidate)
                    if (!s.chinese) controller.onSpace(periodShortcut = false)
                } else controller.onSpace(periodShortcut = false)
                return true
            }
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                val visible = controller.visibleCandidates
                if (visible?.highlightedCandidate?.let { it >= 0 } == true && visible.candidateGeneration != s.candidateGeneration) return true
                // 不在组合中时回车交给 App（多行换行、表单提交等按它自己的规则）。 Not composing: the app handles Enter.
                if (s.highlightedCandidate >= 0) { controller.onCandidate(s.highlightedCandidate); return true }
                if (!composing) {
                    if (s.candidates.isNotEmpty()) controller.dismissPredictions()
                    return false
                }
                controller.onEnter(); return true
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                if (!composing && s.candidates.isEmpty()) return false
                controller.reset(); return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN -> {
                // Idle predictions must not take Tab focus traversal or page scrolling from the app.
                if (!composing && s.highlightedCandidate < 0 && keyCode in intArrayOf(
                        KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN)) return false
                val delta = when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> -1
                    KeyEvent.KEYCODE_PAGE_UP -> -9
                    KeyEvent.KEYCODE_PAGE_DOWN -> 9
                    KeyEvent.KEYCODE_TAB -> if (e.isShiftPressed) -1 else 1
                    else -> 1
                }
                if ((!e.isShiftPressed || keyCode == KeyEvent.KEYCODE_TAB) && controller.moveCandidate(delta)) return true
                if (composing) controller.commitRaw()
                return false
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END -> {
                if (composing) controller.commitRaw()
                return false
            }
        }
        // 拼音 v 模式（v12*3）里数字是输入的一部分，不选候选。 In the pinyin v mode digits are input, not candidate picks.
        if ((composing || s.highlightedCandidate >= 0) && keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 && !e.isShiftPressed && s.candidates.isNotEmpty() && !vMode(s)) {
            val i = s.highlightedCandidate.coerceAtLeast(0) / 9 * 9 + keyCode - KeyEvent.KEYCODE_1
            val visible = controller.visibleCandidates
            if (visible != null) controller.onVisibleCandidate(i, visible.candidateGeneration)
            else if (i < maxOf(s.candidates.size, s.totalCandidates)) controller.onCandidate(i)
            return true
        }
        val ch = e.getUnicodeChar(e.metaState)
        if (ch == 0 || ch and android.view.KeyCharacterMap.COMBINING_ACCENT != 0 || Character.isISOControl(ch)) return false
        when {
            // 中文模式下的大写字母直接上屏（组合中先上屏首选）。 Uppercase letters commit as-is in Chinese mode (after the top candidate).
            s.chinese && ch in 'A'.code..'Z'.code -> controller.onText(ch.toChar().toString())
            else -> controller.onChar(ch)
        }
        return true
    }

    private fun vMode(s: ImeState): Boolean =
        s.chinese && s.schema == "pinyin" && s.preedit.startsWith("v") && s.preedit.drop(1).all { it.isDigit() || it in V_CHARS }

    private companion object {
        /** v 模式里数字以外可输入的字符（与内核一致）。 Non-digit chars of the v mode, as in the engine. */
        const val V_CHARS = ".+-*/()%^"
    }
}
