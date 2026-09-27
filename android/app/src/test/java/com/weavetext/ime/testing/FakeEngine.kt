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

    private fun composes(c: Char) = c in 'a'..'z' || (schema == "english" && c in 'A'..'Z') ||
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
        inputs++
        raw.append(c)
        return true
    }
    /** 带邻键的按键：(字母, 邻键, 贴近度)。 Keys fed with a neighbour: (letter, neighbour, closeness). */
    val nearCalls = ArrayList<Triple<Char, Char, Float>>()
    override fun inputKey(codePoint: Int, near: Int, closeness: Float): Boolean {
        if (!inputChar(codePoint)) return false
        nearCalls += Triple(codePoint.toChar(), near.toChar(), closeness)
        return true
    }
    override fun backspace(): Boolean {
        if (handing) { hand.removeAt(hand.size - 1); return true }
        if (raw.isEmpty()) return false
        raw.setLength(raw.length - 1)
        return true
    }
    override fun select(index: Int): Boolean {
        if (raw.isEmpty() && !handing) return false
        pending.append(cand(index).text)
        raw.clear()
        hand.clear()
        return true
    }
    override fun selectPinyin(index: Int) = false
    override fun forget(index: Int) = false
    override fun commitFirst() { if (raw.isNotEmpty() || handing) select(0) }
    override fun commitRaw() { pending.append(raw); raw.clear(); hand.clear() }
    override fun clear() { raw.clear(); hand.clear() }
    override fun flush() {}
    override fun isComposing() = raw.isNotEmpty() || handing
    override fun setLearning(on: Boolean) {}
    override fun setContext(prevWord: String?) {}
    override fun snapshot(): EngineSnapshot {
        snapshots++
        val commit = pending.toString()
        pending = StringBuilder()
        val composing = isComposing()
        if (handing) return EngineSnapshot(commit, "", true, List(12) { cand(it) }, 12, emptyList(), schema)
        return EngineSnapshot(commit, raw.toString(), composing, if (composing) List(60) { cand(it) } else emptyList(), if (composing) 800 else 0, emptyList(), schema)
    }
    override fun candidates(offset: Int, limit: Int): List<Candidate> =
        if (handing) (offset until minOf(12, offset + limit)).map { cand(it) }
        else if (raw.isEmpty()) emptyList() else (offset until minOf(800, offset + limit)).map { cand(it) }
}

/**
 * 记录上屏结果的编辑器连接（方向键移动光标、删除键删字）。
 * An editor connection that keeps the committed text (arrows move the cursor, DEL deletes).
 */
open class FakeInputConnection(view: View) : BaseInputConnection(view, true) {
    val text: String get() = editable!!.toString()
    var cursorMoves = 0

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val e = editable!!
        val sel = android.text.Selection.getSelectionEnd(e).coerceAtLeast(0)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> if (sel > 0) e.delete(sel - 1, sel)
            KeyEvent.KEYCODE_DPAD_LEFT -> { cursorMoves--; android.text.Selection.setSelection(e, (sel - 1).coerceAtLeast(0)) }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { cursorMoves++; android.text.Selection.setSelection(e, (sel + 1).coerceAtMost(e.length)) }
            KeyEvent.KEYCODE_ENTER -> commitText("\n", 1)
        }
        return true
    }
}
