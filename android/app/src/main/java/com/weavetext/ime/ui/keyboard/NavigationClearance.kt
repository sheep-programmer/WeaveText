package com.weavetext.ime.ui.keyboard

import android.graphics.Rect

/** Only reserve the navigation area which the keyboard's host has not already excluded. */
internal object NavigationClearance {
    data class Edges(val bottom: Int = 0, val left: Int = 0, val right: Int = 0)

    fun legacyInset(current: Int, stable: Int, systemBarSize: Int): Int =
        current.coerceIn(0, (stable.takeIf { it > 0 } ?: systemBarSize).coerceAtLeast(0))

    fun overlap(window: Rect, content: Rect, bars: Edges): Edges = Edges(
        bottom = (content.bottom - (window.bottom - bars.bottom)).coerceIn(0, bars.bottom),
        left = (window.left + bars.left - content.left).coerceIn(0, bars.left),
        right = (content.right - (window.right - bars.right)).coerceIn(0, bars.right),
    )

    /**
     * 这次读数是否把某条边从「留着」变成「一点都不留」。切应用的动画、窗口进出场时系统可能给出这种读数，
     * 认快了键盘就画到虚拟按键下面。
     * Whether the new reading gives up an edge which the current one still reserves — the reading which must not be
     * taken on its own, since an app switch or a window entering/leaving reports zero first.
     */
    fun dropsToZero(current: Edges, next: Edges): Boolean =
        (current.bottom > 0 && next.bottom == 0) ||
            (current.left > 0 && next.left == 0) ||
            (current.right > 0 && next.right == 0)

    /**
     * 还没读到任何边衬时按系统资源先留出导航栏：三键导航的手机上，第一帧就该让开它，
     * 否则键盘先画到虚拟按键下面，等边衬到了才跳上来。手势导航只画手势条，不预留。
     * Before the first reading, reserve the bar from the platform resource: on a phone with the button bar the first
     * frame must already clear it, or the keys are painted under the system buttons until the insets arrive.
     * Gesture navigation draws only the handle, so nothing is reserved.
     */
    fun seed(navigationMode: Int, barHeight: Int): Edges =
        if (navigationMode == 2) Edges() else Edges(bottom = barHeight.coerceAtLeast(0))
}
