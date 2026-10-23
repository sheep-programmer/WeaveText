package com.weavetext.ime.ime

/**
 * 英文 Shift 三态（02 §4）：关 → 单次 → 关；400ms 内双击或长按 → 锁定；锁定再单击 → 关。
 * 单次态输入一个字母后回到关；句首自动大写进入单次态。纯逻辑，便于单元测试。
 * English Shift tri-state (02 §4): a double tap within 400 ms or a long press locks. Pure logic for unit tests.
 */
class ShiftState(private val doubleTapMs: Long = 400) {
    var value = OFF
        private set
    private var lastTap = Long.MIN_VALUE / 2
    /** 当前的单次态是自动大写给的（不是用户按的）。 The current ONCE came from auto-capitalisation, not the user. */
    private var auto = false
    /** 最近一次按下字母消耗的单次态；撤回同一按键时恢复。 The ONCE consumed by the last letter, for reverting that key. */
    private var consumedAuto: Boolean? = null

    val upper get() = value != OFF

    /**
     * 点击 Shift。 Tap on Shift.
     * @param allowLock 是否允许双击锁定（中文模式下的临时大写不锁定）。
     * @return 是否变化。
     */
    fun tap(now: Long, allowLock: Boolean = true): Boolean {
        val next = when {
            value == LOCK -> OFF
            allowLock && now - lastTap < doubleTapMs -> LOCK
            value == ONCE -> OFF
            else -> ONCE
        }
        lastTap = if (next == LOCK) Long.MIN_VALUE / 2 else now
        auto = false
        consumedAuto = null
        return set(next)
    }

    /** 长按 Shift：直接锁定大写。 Long press on Shift: lock capitals. */
    fun lock(): Boolean { lastTap = Long.MIN_VALUE / 2; auto = false; consumedAuto = null; return set(LOCK) }

    /** 输出一个字母后调用；单次态回到关，也结束双击窗口。 A letter consumes ONCE and ends the double-tap window. */
    fun consume(): Boolean {
        lastTap = Long.MIN_VALUE / 2
        consumedAuto = if (value == ONCE) auto else null
        auto = false
        return if (value == ONCE) set(OFF) else false
    }

    /** 同一字符已成功撤回：恢复它消耗的单次大写，不恢复双击窗口。 Restore a reverted letter's ONCE, without rearming double tap. */
    fun restoreConsumed(): Boolean {
        val wasAuto = consumedAuto ?: return false
        consumedAuto = null
        if (value != OFF) return false
        auto = wasAuto
        return set(ONCE)
    }

    /**
     * 句首自动大写：需要时从关进入单次态；自动给的单次态在不再需要时（如退格删掉了句号后的空格）收回。
     * Auto-capitalise at sentence start: OFF → ONCE when needed; an auto ONCE is taken back once it no longer
     * applies (e.g. the space after a period was deleted).
     */
    fun autoCap(active: Boolean): Boolean = when {
        active && value == OFF -> { auto = true; set(ONCE) }
        !active && auto && value == ONCE -> { auto = false; set(OFF) }
        else -> false
    }

    fun reset(): Boolean { lastTap = Long.MIN_VALUE / 2; auto = false; consumedAuto = null; return set(OFF) }

    private fun set(v: Int): Boolean {
        if (v == value) return false
        value = v
        return true
    }

    companion object {
        const val OFF = 0
        const val ONCE = 1
        const val LOCK = 2
    }
}
