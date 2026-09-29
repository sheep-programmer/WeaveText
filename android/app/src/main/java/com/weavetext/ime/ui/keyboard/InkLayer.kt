package com.weavetext.ime.ui.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View

/**
 * 手写墨迹层：与键盘视图同在一个容器、盖在其上的透明视图，只画书写区的墨迹，自己不接触摸与无障碍事件
 * （都落到下面的键盘视图）。它有独立的显示列表，写字时每次移动只重画这一层。
 * The handwriting ink layer: a transparent view over the keyboard view in the same container. It draws only the
 * pad's ink and takes no touch or accessibility events (they reach the keyboard view below). It has its own display
 * list, so each move while writing redraws just this layer.
 */
class InkLayer(ctx: Context, private val kv: KeyboardView) : View(ctx) {
    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        // 键盘视图被面板盖住（隐藏）时墨迹也不画。 No ink while the keyboard view is hidden under a panel.
        if (kv.visibility != VISIBLE) return
        kv.drawInk(canvas, kv.x - x, kv.y - y)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = false

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = false
}
