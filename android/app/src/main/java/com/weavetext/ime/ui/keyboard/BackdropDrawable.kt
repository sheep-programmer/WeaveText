package com.weavetext.ime.ui.keyboard

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 键盘背景：渐变或图片（居中裁切）+ 压暗。着色器与矩阵只在尺寸变化时重建，绘制不分配。
 * Keyboard backdrop: gradient or centre-cropped image plus dim; shaders rebuilt only on bounds change.
 */
class BackdropDrawable(private val b: KbBackdrop) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = (((b.dim * 255).toInt().coerceIn(0, 255)) shl 24) }
    private val matrix = Matrix()

    override fun onBoundsChange(r: Rect) {
        val img = b.image
        if (img != null) {
            val s = max(r.width() / img.width.toFloat(), r.height() / img.height.toFloat())
            matrix.setScale(s, s)
            matrix.postTranslate(r.left + (r.width() - img.width * s) / 2, r.top + (r.height() - img.height * s) / 2)
            paint.shader = android.graphics.BitmapShader(img, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
        } else {
            val a = Math.toRadians(b.angle.toDouble())
            val cx = r.exactCenterX(); val cy = r.exactCenterY()
            val dx = (cos(a) * r.width() / 2).toFloat(); val dy = (sin(a) * r.height() / 2).toFloat()
            val colors = if (b.colors.size == 1) intArrayOf(b.colors[0], b.colors[0]) else b.colors
            paint.shader = LinearGradient(cx - dx, cy - dy, cx + dx, cy + dy, colors, null, Shader.TileMode.CLAMP)
        }
    }

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds, paint)
        if (b.dim > 0f) canvas.drawRect(bounds, dimPaint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}
