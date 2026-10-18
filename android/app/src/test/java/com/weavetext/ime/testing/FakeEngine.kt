package com.weavetext.ime.testing

import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import com.weavetext.ime.core.Candidate
import com.weavetext.ime.core.EngineSnapshot
import com.weavetext.ime.core.KeyEngine

/**
 * 可预测的假内核：字母（及 14 键键码、分隔符）进入组合串，候选为「组合串 + 序号」，每次最多返回 60 个、总数报 800。
 * 手写方案记录每次收到的笔画，候选为「笔画数 + 序号」（如 "2笔0"）。
 * A predictable fake engine: letters (and 14-key codes, separators) compose; candidates are "preedit + n",
 * at most 60 per snapshot with 800 reported in total. Handwriting records every stroke list it gets and offers
 * "<strokes>笔<n>" candidates.
 */
class FakeEngine : KeyEngine {
    val raw = StringBuilder()
    private var pending = StringBuilder()
    var schema = "pinyin"
    var inputs = 0
    var snapshots = 0
    /** 每次 handInput 收到的笔画（复制）。 Copies of every handInput call. */
    val handCalls = mutableListOf<List<FloatArray>>()
    val hand = ArrayList<FloatArray>()
    private val handing get() = schema == "hand" && hand.isNotEmpty()
    /** 打开后像真内核一样在上屏后给出联想词（P1、P2、P3）。 When on, offers predictions after a commit like the real engine. */
    var predicts = false
    var predictions: List<String> = emptyList()
    private fun predict() { predictions = if (predicts) listOf("P1", "P2", "P3") else emptyList() }

    /** 最近一次 setLearning。 The last setLearning value. */
    var learningValue = true
    /** 打开后支持组合串光标（按 raw 下标），与真内核一样在末尾时为 null。 Opt-in preedit caret over raw; null at the end. */
    var caretEditing = false
    var caret: Int? = null
    /** 以词取字的调用：(序号, 是否末字)。 selectChar calls: (index, last). */
    val charPicks = ArrayList<Pair<Int, Boolean>>()
    override fun moveCaret(delta: Int): Boolean {
        if (!caretEditing || raw.isEmpty()) return false
        val to = ((caret ?: raw.length) + delta).coerceIn(0, raw.length)
        caret = to.takeIf { it < raw.length }
        return true
    }
    override fun setCaret(index: Int): Boolean {
        if (!caretEditing || raw.isEmpty()) return false
        caret = index.takeIf { it < raw.length }
        return true
    }
    override fun deleteForward(): Boolean {
        val at = caret ?: return raw.isNotEmpty()
        raw.deleteCharAt(at)
        if (at >= raw.length) caret = null
        return true
    }
    /** 假内核按每两个字母一个音节。 The fake treats every two letters as a syllable. */
    override fun moveCaretSyllable(delta: Int): Boolean {
        if (!caretEditing || raw.isEmpty()) return false
        val at = caret ?: raw.length
        val to = if (delta < 0) (at - 1).coerceAtLeast(0) / 2 * 2 else minOf(raw.length, at / 2 * 2 + 2)
        caret = to.takeIf { it < raw.length }
        return true
    }
    override fun deleteSyllable(forward: Boolean): Boolean {
        if (!caretEditing || raw.isEmpty()) return false
        val at = caret ?: raw.length
        val (from, to) = if (forward) at to minOf(raw.length, at / 2 * 2 + 2) else (at - 1).coerceAtLeast(0) / 2 * 2 to at
        raw.delete(from, to)
        caret = from.takeIf { it < raw.length }
        return true
    }
    override fun selectChar(index: Int, last: Boolean): Boolean {
        if (raw.isEmpty()) return false
        charPicks += index to last
        val t = cand(index).text
        pending.append(if (last) t.last() else t.first())
        raw.clear(); caret = null
        return true
    }
    private fun composes(c: Char) = c in 'a'..'z' || (schema == "english" && c in 'A'..'Z') ||
        // 与真内核一样：v 之后的数字进入 v 模式。 Like the real engine: digits after "v" stay in the v mode.
        (schema == "pinyin" && c.isDigit() && raw.startsWith("v") && raw.drop(1).all { it.isDigit() }) ||
        (schema == "t14" && (c in 'A'..'N' || c == '\'' || c == '1'))
    /** 英文方案与真实内核一样把原样输入放在首位。 English puts the typed word first, like the real engine. */
    private fun cand(i: Int) = if (handing) Candidate("${hand.size}笔$i", "", false) else Candidate(if (i == 0) (if (schema == "english") "$raw" else "【$raw】") else "$raw$i", "", false)

    override fun setSchema(key: String): Boolean { schema = key; raw.clear(); hand.clear(); return true }
    override fun handInput(strokes: List<FloatArray>): Boolean {
        if (schema != "hand") return false
        handCalls += strokes.map { it.copyOf() }
        hand.clear()
        hand.addAll(strokes.map { it.copyOf() })
        return true
    }
    override fun setOption(key: String, value: Boolean) = true
    override fun inputChar(codePoint: Int): Boolean {
        val c = codePoint.toChar()
        if (!composes(c)) return false
        predictions = emptyList()
        inputs++
        val at = caret
        if (at != null) { raw.insert(at, c); caret = at + 1 } else raw.append(c)
        return true
    }
    /** 只认 a*b 形式的假计算。 A fake calculator that only knows a*b. */
    override fun evaluate(expr: String): String? =
        expr.split('*').takeIf { it.size == 2 }?.let { (a, b) -> (a.toLongOrNull() ?: return null) * (b.toLongOrNull() ?: return null) }?.toString()


    /** 带邻键的按键：(字母, 邻键, 贴近度)。 Keys fed with a neighbour: (letter, neighbour, closeness). */
    val nearCalls = ArrayList<Triple<Char, Char, Float>>()
    override fun inputKey(codePoint: Int, near: Int, closeness: Float): Boolean {
        if (!inputChar(codePoint)) return false
        nearCalls += Triple(codePoint.toChar(), near.toChar(), closeness)
        return true
    }
    override fun backspace(): Boolean {
        if (handing) { hand.removeAt(hand.size - 1); return true }
        if (raw.isEmpty()) { predictions = emptyList(); return false }
        val at = caret
        if (at != null) { if (at > 0) { raw.deleteCharAt(at - 1); caret = at - 1 } } else raw.setLength(raw.length - 1)
        return true
    }
    override fun select(index: Int): Boolean {
        if (raw.isEmpty() && !handing) {
            val p = predictions.getOrNull(index) ?: return false
            pending.append(p)
            predict()
            return true
        }
        pending.append(cand(index).text)
        raw.clear()
        caret = null
        hand.clear()
        predict()
        return true
    }
    override fun selectPinyin(index: Int) = false
    override fun forget(index: Int) = false
    override fun commitFirst() { if (raw.isNotEmpty() || handing) select(0); predictions = emptyList() }
    override fun commitRaw() { pending.append(raw); raw.clear(); caret = null; hand.clear(); predictions = emptyList() }
    override fun clear() { raw.clear(); caret = null; hand.clear(); predictions = emptyList() }
    override fun flush() {}
    override fun isComposing() = raw.isNotEmpty() || handing
    override fun setLearning(on: Boolean) { learningValue = on }
    override fun setContext(prevWord: String?) {}
    override fun snapshot(): EngineSnapshot {
        snapshots++
        val commit = pending.toString()
        pending = StringBuilder()
        val composing = isComposing()
        if (handing) return EngineSnapshot(commit, "", true, List(12) { cand(it) }, 12, emptyList(), schema)
        if (!composing && predictions.isNotEmpty()) {
            return EngineSnapshot(commit, "", false, predictions.map { Candidate(it, "", false) }, predictions.size, emptyList(), schema)
        }
        return EngineSnapshot(commit, raw.toString(), composing, if (composing) List(60) { cand(it) } else emptyList(), if (composing) 800 else 0, emptyList(), schema, caret = caret)
    }
    override fun candidates(offset: Int, limit: Int): List<Candidate> {
        return when {
            !isComposing() && predictions.isNotEmpty() -> predictions.drop(offset).take(limit).map { Candidate(it, "", false) }
            handing -> (offset until minOf(12, offset + limit)).map { cand(it) }
            raw.isEmpty() -> emptyList()
            else -> (offset until minOf(800, offset + limit)).map { cand(it) }
        }
    }
}

/**
 * 记录上屏结果的编辑器连接（方向键移动光标、删除键像文本框一样删掉一个字形簇或选区）。
 * An editor connection that keeps the committed text (arrows move the cursor; DEL deletes the selection or one
 * grapheme cluster, like a text view).
 */
open class FakeInputConnection(view: View) : BaseInputConnection(view, true) {
    val text: String get() = editable!!.toString()
    /** 方向键移动的步数（左负右正）。 Arrow-key steps (left negative). */
    var cursorMoves = 0
    /** setSelection 调用次数。 Number of setSelection calls. */
    var selectionSets = 0
    /** 收到的按键（按下）。 Key events received (downs). */
    val keyDowns = mutableListOf<KeyEvent>()

    override fun setSelection(start: Int, end: Int): Boolean { selectionSets++; return super.setSelection(start, end) }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true
        keyDowns += event
        val e = editable!!
        val sel = android.text.Selection.getSelectionEnd(e).coerceAtLeast(0)
        val selStart = android.text.Selection.getSelectionStart(e).coerceAtLeast(0)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> when {
                selStart != sel -> e.delete(minOf(selStart, sel), maxOf(selStart, sel))
                sel > 0 -> {
                    val it = android.icu.text.BreakIterator.getCharacterInstance()
                    it.setText(e.toString())
                    e.delete(it.preceding(sel).coerceAtLeast(0), sel)
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> { cursorMoves--; android.text.Selection.setSelection(e, (sel - 1).coerceAtLeast(0)) }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { cursorMoves++; android.text.Selection.setSelection(e, (sel + 1).coerceAtMost(e.length)) }
            KeyEvent.KEYCODE_ENTER -> commitText("\n", 1)
        }
        return true
    }
}
