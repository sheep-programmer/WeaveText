package com.weavetext.ime.ui.keyboard

/** Stable ids are stored by FloatingResizeSettings; labels also identify each accessibility node. */
enum class FloatingResizeCorner(val id: String, val label: String, val bit: Int, val horizontalSign: Int, val verticalSign: Int) {
    TOP_LEFT("top_left", "左上角", 1, -1, -1),
    TOP_RIGHT("top_right", "右上角", 2, 1, -1),
    BOTTOM_LEFT("bottom_left", "左下角", 4, -1, 1),
    BOTTOM_RIGHT("bottom_right", "右下角", 8, 1, 1);

    val contentDescription get() = "从${label}调整悬浮键盘大小"
}

/** No location restriction; every corner is enabled by default. All 15 nonempty sets are valid. */
class FloatingResizePolicy(corners: Set<FloatingResizeCorner> = FloatingResizeCorner.entries.toSet()) {
    val corners: Set<FloatingResizeCorner> = corners.toSet()
    val cornerMask get() = corners.fold(0) { mask, corner -> mask or corner.bit }

    init { require(this.corners.isNotEmpty()) { "Keep at least one floating resize corner enabled" } }

    fun allows(corner: FloatingResizeCorner) = corner in corners

    /** The last enabled corner cannot be disabled. Used by the independent settings component. */
    fun withCorner(corner: FloatingResizeCorner, enabled: Boolean): FloatingResizePolicy {
        val next = if (enabled) corners + corner else corners - corner
        return if (next.isEmpty()) this else FloatingResizePolicy(next)
    }

    override fun equals(other: Any?) = other is FloatingResizePolicy && corners == other.corners
    override fun hashCode() = corners.hashCode()
    override fun toString() = "FloatingResizePolicy($corners)"

    companion object {
        const val DEFAULT_ALL = 15
        /** Unknown bits are ignored; an empty set is rejected, as with the Set constructor. */
        fun fromMask(mask: Int) = FloatingResizePolicy(FloatingResizeCorner.entries.filter { mask and it.bit != 0 }.toSet())
    }
}

object FloatingResizeAccessibility {
    const val CLOSE_DESCRIPTION = "关闭悬浮键盘，停靠到底部"
}
