package com.weavetext.ime.ui.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * 按键底板的统一画法（阴影形式、描边、按下下沉），供键区与各面板共用；只用调用方传入的对象，零分配。
 * Shared key-face painting (shadow form, outline, pressed offset) for the key area and panels; allocation-free.
 */
object KeyPainter {
    /**
     * 画一个键的底板；返回后 [face] 为实际绘制的矩形（按下时下移半 dp），供放置文字。
     * Draws one key face; afterwards [face] holds the drawn rect (shifted by 0.5dp when pressed) for labels.
     */
    fun draw(c: Canvas, rect: RectF, face: RectF, scratch: RectF, fill: Paint, color: Int, pressed: Boolean, radius: Float, p: KbPalette, m: KbMetrics) {
        face.set(rect)
        val opaque = color ushr 24 == 0xFF
        if (pressed) {
            face.offset(0f, m.dp(0.5f))
        } else if (p.shadow == KeyShadow.BAR && opaque) {
            scratch.set(rect); scratch.offset(0f, m.dp(1f))
            fill.color = p.keyShadow
            c.drawRoundRect(scratch, radius, radius, fill)
        }
        fill.color = color
        if (p.shadow == KeyShadow.SOFT && !pressed) {
            fill.setShadowLayer(m.dp(1.5f), 0f, m.dp(1f), p.keyShadow)
            c.drawRoundRect(face, radius, radius, fill)
            fill.clearShadowLayer()
        } else {
            c.drawRoundRect(face, radius, radius, fill)
        }
        if (p.strokeWidth > 0f) {
            val sw = m.dp(p.strokeWidth)
            fill.style = Paint.Style.STROKE
            fill.strokeWidth = sw
            fill.color = p.stroke
            scratch.set(face); scratch.inset(sw / 2, sw / 2)
            c.drawRoundRect(scratch, radius, radius, fill)
            fill.style = Paint.Style.FILL
        }
    }
}
