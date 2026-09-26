package com.weavetext.ime.ui.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import com.weavetext.ime.style.KeyboardStyle

/**
 * 设置页用的真实渲染预览：顶栏（组合串 + 候选或工具栏）+ 26 键，不可交互。
 * Real-render preview for the settings app: top bar (candidates or toolbar) plus the QWERTY keys; not interactive.
 */
@SuppressLint("ViewConstructor")
class StylePreviewView(ctx: Context) : ViewGroup(ctx) {
    private val icons = Icons(ctx)
    private val topBar = TopBarView(ctx, object : TopBarHost {
        override val feedback: Feedback? = null
        override fun onToolbar(index: Int) {}
        override fun onToolbarLong(index: Int) {}
        override fun onCandidate(index: Int) {}
        override fun onCandidateLong(index: Int) {}
        override fun onExpand() {}
        override fun onNeedMore() {}
        override fun onClipChip() {}
        override fun onHideByDrag() {}
    })
    private val keys = KeyboardView(ctx, null)
    private var style: KeyboardStyle? = null

    init {
        addView(topBar)
        addView(keys)
        // 预览只看不点。 Preview only.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    /** 绑定风格；[composing] 时顶栏显示示例候选，否则显示工具栏。 Bind a style; composing shows sample candidates. */
    fun bind(s: KeyboardStyle, composing: Boolean) {
        if (s === style && composing == (topBar.candidateMode)) return
        style = s
        icons.clear()
        val b = s.palette.backdrop
        if (b == null) setBackgroundColor(s.palette.background) else background = BackdropDrawable(b)
        topBar.applyStyle(s, icons)
        keys.applyStyle(s, icons)
        keys.chinese = true
        keys.setQwerty(Layouts.qwerty(english = false, s.layout.qwerty, s.layout.labels))
        if (composing) topBar.setCandidateTexts("ni'hao", SAMPLE, 40, english = false, keepScroll = false)
        else topBar.setCandidateTexts("", emptyList(), 0, english = false, keepScroll = false)
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val m = style?.metrics
        val h = m?.kbHeight?.toInt() ?: 0
        if (m != null) {
            topBar.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(m.topBar.toInt(), MeasureSpec.EXACTLY))
            keys.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(m.mainHeight.toInt(), MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val m = style?.metrics ?: return
        topBar.layout(0, 0, r - l, m.topBar.toInt())
        val top = (m.topBar + m.padTop).toInt()
        keys.layout(0, top, r - l, top + m.mainHeight.toInt())
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?) = false

    companion object {
        private val SAMPLE = listOf("你好", "拟好", "你", "尼", "泥", "呢", "倪", "妮")
    }
}
