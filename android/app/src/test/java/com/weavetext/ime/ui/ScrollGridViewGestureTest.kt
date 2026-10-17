package com.weavetext.ime.ui

import android.app.Activity
import android.app.Application
import android.graphics.Canvas
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.ui.keyboard.ScrollGridView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * 纵向网格的手势：纵向拖动滚动、轻点命中、横向位移不该让这一次点击白白丢掉。
 * Vertical grid gestures: a vertical drag scrolls, a tap hits an item, and a sideways slip must not silently throw the
 * tap away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-420dpi")
class ScrollGridViewGestureTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var grid: Probe

    /** 10 个等高条目，可滚动；记录点选、长按与滚动位置。 Ten equal rows, scrollable; records taps and the scroll offset. */
    private class Probe(activity: Activity) : ScrollGridView(activity) {
        val taps = mutableListOf<Int>()
        var longs = 0
        override fun contentHeight() = 1000f
        override fun hit(x: Float, y: Float): Int = (y / 100f).toInt().takeIf { it in 0..9 } ?: -1
        override fun onItemTap(index: Int) { taps += index }
        override fun onItemLong(index: Int): Boolean { longs++; return true }
        override fun drawContent(c: Canvas) {}
        fun offset() = scroll
    }

    @Before fun setUp() {
        android.provider.Settings.Global.putFloat(app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        grid = Probe(activity)
        // 挂到窗口上：长按走 View.postDelayed，未 attach 的视图会把它推迟到 attach 之后。
        // Attached to a window: the long press uses View.postDelayed, which an unattached view defers until attach.
        val frame = android.widget.FrameLayout(activity)
        frame.addView(grid, android.widget.FrameLayout.LayoutParams(400, 300))
        activity.setContentView(frame, android.view.ViewGroup.LayoutParams(-1, -1))
        idle()
        grid.layout(0, 0, 400, 300)
    }

    private fun send(action: Int, x: Float, y: Float, t: Long): Boolean {
        val e = MotionEvent.obtain(t, t, action, x, y, 0)
        return try { grid.dispatchTouchEvent(e) } finally { e.recycle() }
    }

    private fun idle() = ShadowLooper.idleMainLooper()

    @Test fun aTapHitsTheItemUnderTheFinger() {
        val t = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, 100f, 150f, t)
        send(MotionEvent.ACTION_UP, 100f, 150f, t + 30)
        idle()
        assertEquals(listOf(1), grid.taps)
    }

    @Test fun aVerticalDragScrollsWithoutPickingAnything() {
        val t = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, 100f, 250f, t)
        send(MotionEvent.ACTION_MOVE, 100f, 150f, t + 20)
        send(MotionEvent.ACTION_MOVE, 100f, 50f, t + 40)
        send(MotionEvent.ACTION_UP, 100f, 50f, t + 60)
        idle()
        assertTrue("纵向拖动应当滚动，实际 ${grid.offset()}", grid.offset() > 0f)
        assertTrue("拖动不该点选，实际 ${grid.taps}", grid.taps.isEmpty())
    }

    @Test fun aSidewaysSlipStillPicksTheItemUnderTheFinger() {
        // 这些网格只有纵向滚动，横向位移不改变任何东西；可它现在会把按下项丢掉，抬手时既不滚动也不点选，
        // 于是斜着轻点就没反应。 These grids only scroll vertically, so a sideways slip changes nothing — yet it
        // currently drops the pressed item, and the lift neither scrolls nor picks, so a slanted tap does nothing.
        val t = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, 100f, 150f, t)
        send(MotionEvent.ACTION_MOVE, 140f, 150f, t + 20)
        send(MotionEvent.ACTION_UP, 140f, 150f, t + 40)
        idle()
        assertEquals("斜着轻点仍应点选", listOf(1), grid.taps)
        assertEquals("横向位移不该滚动", 0f, grid.offset())
    }

    @Test fun aLongPressFiresOnThePressedItem() {
        val t = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, 100f, 150f, t)
        idle()
        ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals(1, grid.longs)
        send(MotionEvent.ACTION_UP, 100f, 150f, t + 520)
        idle()
        assertTrue("长按成立后抬手不再点选，实际 ${grid.taps}", grid.taps.isEmpty())
    }
}
