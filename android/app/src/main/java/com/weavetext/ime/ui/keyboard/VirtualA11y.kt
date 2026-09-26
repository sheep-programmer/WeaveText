package com.weavetext.ime.ui.keyboard

import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider

/**
 * 自绘视图的虚拟无障碍节点：TalkBack 逐项朗读，双击或（可选）触摸探索松手即触发。
 * 只用平台 API，不依赖 androidx.customview。
 * Virtual accessibility nodes for a custom-drawn view: TalkBack reads each item; double-tap or,
 * optionally, lifting the finger during touch exploration activates it. Platform APIs only.
 */
class VirtualA11y(private val view: View, private val src: Source) : AccessibilityNodeProvider() {

    /** 由视图提供的项目。 Items supplied by the host view. */
    interface Source {
        /** 项目 id 列表（绘制顺序即朗读顺序）。 Item ids in reading order. */
        fun a11yIds(): IntArray
        /** 视图坐标下的边界；不可见返回 false。 Bounds in view coordinates; false if hidden. */
        fun a11yBounds(id: Int, out: RectF): Boolean
        fun a11yLabel(id: Int): CharSequence?
        fun a11yClick(id: Int): Boolean
        fun a11yLongClickLabel(id: Int): CharSequence? = null
        fun a11yLongClick(id: Int): Boolean = false
        fun a11yEnabled(id: Int): Boolean = true
        /** 选中/激活态说明（如「已开启」）。 State description. */
        fun a11yState(id: Int): CharSequence? = null
        /** 触摸探索松手时直接触发（按键、候选）。 Activate on lift during touch exploration. */
        fun a11yLiftToActivate(id: Int): Boolean = false
        /** 整个视图的滚动动作（候选条）；返回是否滚动了。 Host-level scroll. */
        fun a11yScroll(forward: Boolean): Boolean = false
        fun a11yCanScroll(forward: Boolean): Boolean = false
    }

    private val am = view.context.getSystemService(AccessibilityManager::class.java)
    private var focused = INVALID
    private var hovered = INVALID
    private val rf = RectF()
    private val r = Rect()
    private val loc = IntArray(2)

    val enabled get() = am?.isEnabled == true

    /** 在视图坐标 (x, y) 处的项目。 Item at a point. */
    fun idAt(x: Float, y: Float): Int {
        for (id in src.a11yIds()) if (src.a11yBounds(id, rf) && rf.contains(x, y)) return id
        return INVALID
    }

    /** 由宿主视图的 dispatchHoverEvent 调用。 Call from the host's dispatchHoverEvent. */
    fun onHover(e: MotionEvent): Boolean {
        if (am?.isEnabled != true || !am.isTouchExplorationEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                val id = idAt(e.x, e.y)
                setHovered(id)
                return id != INVALID
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                val id = hovered
                setHovered(INVALID)
                // 手指在视图内抬起（而非滑出视图）才算松手。 A lift inside the view, not a slide out.
                val inside = e.x >= 0 && e.y >= 0 && e.x < view.width && e.y < view.height
                if (id != INVALID && inside && idAt(e.x, e.y) == id && src.a11yLiftToActivate(id)) {
                    if (src.a11yClick(id)) send(id, AccessibilityEvent.TYPE_VIEW_CLICKED)
                }
                return id != INVALID
            }
        }
        return false
    }

    /** 内容变化（布局、标签）后调用。 Call after items or labels change. */
    fun invalidate() {
        if (am?.isEnabled != true) return
        val parent = view.parent ?: return
        @Suppress("DEPRECATION")
        val ev = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        ev.contentChangeTypes = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE
        view.onInitializeAccessibilityEvent(ev)
        parent.requestSendAccessibilityEvent(view, ev)
    }

    private fun setHovered(id: Int) {
        if (id == hovered) return
        val old = hovered
        hovered = id
        if (old != INVALID) send(old, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
        if (id != INVALID) send(id, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
    }

    private fun send(id: Int, type: Int) {
        if (am?.isEnabled != true) return
        val parent = view.parent ?: return
        @Suppress("DEPRECATION")
        val ev = AccessibilityEvent.obtain(type)
        ev.packageName = view.context.packageName
        ev.className = BUTTON
        ev.setSource(view, id)
        ev.isEnabled = true
        src.a11yLabel(id)?.let { ev.contentDescription = it }
        parent.requestSendAccessibilityEvent(view, ev)
    }

    override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
        if (virtualViewId == HOST_VIEW_ID) {
            @Suppress("DEPRECATION")
            val info = AccessibilityNodeInfo.obtain(view)
            view.onInitializeAccessibilityNodeInfo(info)
            for (id in src.a11yIds()) if (src.a11yBounds(id, rf)) info.addChild(view, id)
            if (src.a11yCanScroll(true)) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            if (src.a11yCanScroll(false)) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
            if (src.a11yCanScroll(true) || src.a11yCanScroll(false)) info.isScrollable = true
            return info
        }
        if (!src.a11yBounds(virtualViewId, rf)) return null
        @Suppress("DEPRECATION")
        val info = AccessibilityNodeInfo.obtain(view, virtualViewId)
        info.packageName = view.context.packageName
        info.className = BUTTON
        info.setParent(view)
        info.contentDescription = src.a11yLabel(virtualViewId)
        src.a11yState(virtualViewId)?.let { if (android.os.Build.VERSION.SDK_INT >= 30) info.stateDescription = it }
        rf.round(r)
        r.intersect(0, 0, view.width, view.height)
        @Suppress("DEPRECATION")
        info.setBoundsInParent(r)
        view.getLocationOnScreen(loc)
        r.offset(loc[0], loc[1])
        info.setBoundsInScreen(r)
        info.isEnabled = src.a11yEnabled(virtualViewId)
        info.isVisibleToUser = true
        info.isClickable = true
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        src.a11yLongClickLabel(virtualViewId)?.let {
            info.isLongClickable = true
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, it))
        }
        if (focused == virtualViewId) {
            info.isAccessibilityFocused = true
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
        } else {
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS)
        }
        return info
    }

    override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
        if (virtualViewId == HOST_VIEW_ID) {
            return when (action) {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> src.a11yScroll(true)
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> src.a11yScroll(false)
                else -> view.performAccessibilityAction(action, arguments)
            }
        }
        return when (action) {
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                if (focused == virtualViewId) return false
                if (focused != INVALID) send(focused, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                focused = virtualViewId
                view.invalidate()
                send(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                true
            }
            AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                if (focused != virtualViewId) return false
                focused = INVALID
                view.invalidate()
                send(virtualViewId, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                true
            }
            AccessibilityNodeInfo.ACTION_CLICK -> src.a11yClick(virtualViewId).also {
                if (it) send(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
            }
            AccessibilityNodeInfo.ACTION_LONG_CLICK -> src.a11yLongClick(virtualViewId).also {
                if (it) send(virtualViewId, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED)
            }
            else -> false
        }
    }

    companion object {
        const val INVALID = Int.MIN_VALUE
        private const val BUTTON = "android.widget.Button"

        /** 标点、符号的中文读法。 Spoken names for punctuation. */
        private val SYMBOL_NAMES = mapOf(
            "，" to "逗号", "。" to "句号", "、" to "顿号", "；" to "分号", "：" to "冒号", "？" to "问号", "！" to "感叹号",
            "…" to "省略号", "～" to "波浪号", "“" to "左引号", "”" to "右引号", "（" to "左括号", "）" to "右括号",
            "," to "英文逗号", "." to "点", ";" to "英文分号", ":" to "英文冒号", "?" to "英文问号", "!" to "英文感叹号",
            "'" to "单引号", "\"" to "双引号", "(" to "左括号", ")" to "右括号", "-" to "减号", "_" to "下划线",
            "+" to "加号", "*" to "星号", "/" to "斜杠", "%" to "百分号", "=" to "等号", "@" to "艾特", "#" to "井号",
            "~" to "波浪号", "·" to "间隔号", " " to "空格",
        )

        /** 单个符号的朗读文本（未知符号原样返回）。 Spoken form of a symbol. */
        fun speak(s: String): String = SYMBOL_NAMES[s] ?: s
    }
}
