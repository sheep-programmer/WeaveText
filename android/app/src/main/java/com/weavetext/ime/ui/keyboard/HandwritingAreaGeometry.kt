package com.weavetext.ime.ui.keyboard

enum class HandwritingAreaMode(val key: String, val label: String) {
    KEYBOARD("keyboard", "键盘高度"),
    HALF("half", "半屏"),
    FULL("full", "全屏");

    companion object {
        fun from(key: String?): HandwritingAreaMode = entries.firstOrNull { it.key == key } ?: KEYBOARD
    }
}

/** Sizes the whole handwriting keyboard, including candidates/controls, inside the granted IME viewport. */
internal object HandwritingAreaGeometry {
    fun boardHeight(mode: HandwritingAreaMode, normal: Int, usableWindow: Int, parentCapacity: Int): Int {
        if (mode == HandwritingAreaMode.KEYBOARD) return normal
        val available = minOf(usableWindow, parentCapacity).coerceAtLeast(0)
        // A short landscape/split viewport is still a hard bound, even when the usual keyboard is taller.
        if (available < normal) return available
        val requested = if (mode == HandwritingAreaMode.HALF) usableWindow / 2 else usableWindow
        return requested.coerceIn(normal, available)
    }
}
