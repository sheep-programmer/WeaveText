package com.weavetext.ime.style

import com.weavetext.ime.ui.keyboard.KbPalette
import kotlin.math.pow

/**
 * WCAG 2.x 对比度。键盘文字 ≥ 4.5:1，副文字与图标 ≥ 3:1（05 §6）。
 * WCAG 2.x contrast. Key text ≥ 4.5:1, secondary text and icons ≥ 3:1 (05 §6).
 */
object Contrast {
    private fun channel(v: Int): Double {
        val c = v / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    fun luminance(c: Int): Double =
        0.2126 * channel((c shr 16) and 0xFF) + 0.7152 * channel((c shr 8) and 0xFF) + 0.0722 * channel(c and 0xFF)

    /** 前景（可半透明，先叠到背景上）对背景的对比度。 Contrast of [fg] (composited over [bg]) against [bg]. */
    fun ratio(fg: Int, bg: Int): Double {
        val f = over(fg, bg)
        val a = luminance(f) + 0.05
        val b = luminance(bg or 0xFF000000.toInt()) + 0.05
        return if (a > b) a / b else b / a
    }

    /** 把 [fg] 按 alpha 叠到不透明的 [bg] 上。 Composite [fg] over opaque [bg]. */
    fun over(fg: Int, bg: Int): Int {
        val a = (fg ushr 24) / 255f
        if (a >= 1f) return fg
        return StyleParser.mix(bg or 0xFF000000.toInt(), fg or 0xFF000000.toInt(), a)
    }

    /**
     * 强调色上的文字色：白色够 4.5:1 用白色，否则取白色与近黑中对比度更高的一个。
     * Text colour on an accent: white when it reaches 4.5:1, else whichever of white and near-black contrasts more.
     */
    fun onColor(accent: Int): Int {
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF111111.toInt()
        val w = ratio(white, accent)
        return if (w >= 4.5 || w >= ratio(black, accent)) white else black
    }

    /** 一条对比度检查。 One contrast check. */
    class Check(val what: String, val ratio: Double, val min: Double) {
        val ok get() = ratio + 1e-6 >= min
        override fun toString() = "$what %.2f:1 (≥ %.1f)".format(ratio, min)
    }

    /**
     * 对一版配色做全部检查；[backgrounds] 为背景的所有色（渐变各色）。
     * All checks for one palette; [backgrounds] lists every background colour (gradient stops).
     */
    fun checks(p: KbPalette, backgrounds: IntArray = intArrayOf(p.background)): List<Check> {
        val out = ArrayList<Check>()
        for ((i, bg) in backgrounds.withIndex()) {
            val tag = if (backgrounds.size > 1) "@bg$i" else ""
            // 半透明按键先叠到背景上再算。 Translucent key faces are composited over the background first.
            val key = over(p.key, bg)
            val func = over(p.keyFunc, bg)
            out += Check("label/key$tag", ratio(p.label, key), 4.5)
            out += Check("label/keyFunc$tag", ratio(p.label, func), 4.5)
            out += Check("icon/keyFunc$tag", ratio(p.icon, func), 3.0)
            out += Check("label/keyPressed$tag", ratio(p.label, over(p.keyPressed, bg)), 3.0)
            out += Check("hint/key$tag", ratio(p.labelHint, key), 3.0)
            out += Check("secondary/key$tag", ratio(p.labelSecondary, key), 3.0)
            out += Check("label/bg$tag", ratio(p.label, bg), 4.5)
            out += Check("candidate/bg$tag", ratio(p.candidateFirst, if (p.candidatePill) over(p.candidatePillColor, bg) else bg), 4.5)
            out += Check("secondary/bg$tag", ratio(p.labelSecondary, bg), 3.0)
            out += Check("icon/bg$tag", ratio(p.icon, bg), 3.0)
            // 半透明气泡叠在键盘背景上显示（PopupOverlay.plate）。 Translucent popups are laid over the backdrop.
            val popup = over(p.popup, bg)
            out += Check("label/popup$tag", ratio(p.label, popup), 4.5)
            out += Check("onAccent/popupSelected$tag", ratio(p.onAccent, over(p.popupSelected, popup)), 4.5)
        }
        out += Check("onAccent/accent", ratio(p.onAccent, p.keyAccent), 4.5)
        // 红底上的文字（「松手清空」红键、出错提示条、语音出错）：按 onDanger 检查，与正文同标准。
        // Text on a danger plate (the red hold-to-clear key, error strips, voice errors): checked via onDanger.
        out += Check("onDanger/danger", ratio(p.onDanger, p.danger), 4.5)
        out += Check("accent/accentSoft (active)", ratio(p.keyAccent, p.accentSoft), 3.0)
        return out
    }
}
