package com.weavetext.ime.ui.keyboard

import kotlin.math.min

/**
 * 误触过滤：手掌、搁着的手或拇指根压在屏幕上时，接触面积远大于这位用户平时的指尖，这样的按下不输出字符、
 * 也不开始书写。基线取最近几次正常按下的中位数，被拒绝的接触不计入，所以持续的误压不会抬高基线。
 * 没有接触面积数据（部分设备恒为 0）时一律放行。
 * Accidental-touch guard: a palm, a resting hand or the heel of a thumb covers far more area than this user's usual
 * fingertip, so such a press neither types nor starts ink. The baseline is the median of recent accepted presses, and
 * rejected contacts are not recorded, so sustained palm contact cannot raise the baseline. Unknown sizes (some devices
 * always report 0) are accepted.
 */
internal class ContactFilter(
    private val ratio: Float = 2.2f,
    private val minSamples: Int = 8,
    private val window: Int = 24,
) {
    private val recent = FloatArray(window)
    private var count = 0
    private var next = 0

    /** True for a contact that is a palm by size. Accepted contacts feed the baseline. */
    fun isPalm(touchMajor: Float): Boolean {
        if (!touchMajor.isFinite() || touchMajor <= 0f) return false
        val n = min(count, window)
        if (n >= minSamples) {
            val sorted = recent.copyOf(n).also { it.sort() }
            if (touchMajor > sorted[n / 2] * ratio) return true
        }
        recent[next] = touchMajor
        next = (next + 1) % window
        count++
        return false
    }
}
