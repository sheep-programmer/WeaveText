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
}
