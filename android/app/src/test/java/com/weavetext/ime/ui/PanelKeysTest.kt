package com.weavetext.ime.ui

import android.app.Activity
import android.app.Application
import android.graphics.Rect
import android.os.SystemClock
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.ime.InputController
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.testing.FakeEngine
import com.weavetext.ime.testing.FakeInputConnection
import com.weavetext.ime.ui.keyboard.CursorPanel
import com.weavetext.ime.ui.keyboard.ExpressionCatalog
import com.weavetext.ime.ui.keyboard.ImeWindowHost
import com.weavetext.ime.ui.keyboard.PadKey
import com.weavetext.ime.ui.keyboard.PadView
import com.weavetext.ime.ui.keyboard.PanelKeys
import com.weavetext.ime.ui.keyboard.SymbolPanel
import com.weavetext.ime.ui.keyboard.WeaveKeyboard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * 面板自己的字母键盘：点面板上的字母确实能搜索，因为面板盖住主键盘时它才是唯一的输入。
 * The letter pad inside a panel: tapping letters there really searches, since it is the only input while the panel
 * covers the main keyboard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-420dpi")
class PanelKeysTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var kb: WeaveKeyboard
    private lateinit var controller: InputController
    private lateinit var ic: FakeInputConnection

    private val host = object : ImeWindowHost {
        override fun hideKeyboard() {}
        override val window: android.view.Window? get() = null
    }

    @Before fun setUp() {
        android.provider.Settings.Global.putFloat(app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        WeavePrefs.of(app).edit().clear().commit()
        com.weavetext.ime.ime.ClipHistory.awaitIo()
        File(app.filesDir, "clipboard").deleteRecursively()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val frame = FrameLayout(activity)
        ic = FakeInputConnection(frame)
        controller = InputController { ic }
        kb = WeaveKeyboard(activity, controller, host)
        frame.addView(kb.view, FrameLayout.LayoutParams(-1, -2))
        activity.setContentView(frame, ViewGroup.LayoutParams(-1, -1))
        controller.attachEngine(FakeEngine())
        controller.onStartInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, false)
        kb.onShown(); idle()
    }

    @After fun tearDown() = kb.dispose()

    private fun idle() = ShadowLooper.idleMainLooper()

    private fun find(v: View, cls: Class<*>): View? {
        if (cls.isInstance(v)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), cls)?.let { return it }
        return null
    }

    /** 在主线程里把一个视图量好、摆好，等同于真正上屏后的布局。 Lay a view out the way it is once on screen. */
    private fun layout(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    private fun tapAt(v: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        v.dispatchTouchEvent(MotionEvent.obtain(t, t + 16, MotionEvent.ACTION_UP, x, y, 0))
        idle()
    }

    private fun tapLetter(pad: PanelKeys, ch: Char) {
        val w = pad.width
        val h = pad.height
        val slot = w / 10f
        val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val row = rows.indexOfFirst { it.indexOf(ch) >= 0 }
        assertTrue("字母 $ch 在前三行", row >= 0)
        val letters = rows[row]
        val span = letters.length + if (row == 2) 1.4f else 0f
        val start = (10f - span) / 2f
        val idx = letters.indexOf(ch)
        val x = (start + idx + 0.5f) * slot
        val y = (row + 0.5f) * (h / 3f)
        tapAt(pad, x, y)
    }

    @Test fun theLeftAndRightArrowsDoNotOverlapTheBottomRow() {
        kb.showPanel("cursor"); idle()
        val pad = find(kb.panel!!.view, PadView::class.java) as PadView
        layout(pad, 1080, kb.metrics.mainHeight.toInt())
        val left = pad.keys.first { it.id == CursorPanel.LEFT }
        val undo = pad.keys.first { it.id == CursorPanel.UNDO }
        val right = pad.keys.first { it.id == CursorPanel.RIGHT }
        val redo = pad.keys.first { it.id == CursorPanel.REDO }
        // 四个键各占一格，互不重叠：这正是上次错位的来源（左右键跨两行压住了底行）。
        // Each of the four owns one cell, and none overlaps: the overlap is exactly what drew them in the gap.
        assertFalse("左移不该压住撤销", Rect.intersects(left.cell.let { Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) },
            undo.cell.let { Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) }))
        assertFalse("右移不该压住重做", Rect.intersects(right.cell.let { Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) },
            redo.cell.let { Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) }))
        // 每一行的键高度一致，没有跨行键（面板 5 列 × 4 行）。 Every key is one row tall (5 columns × 4 rows).
        for (k: PadKey in pad.keys) assertEquals(1, ((k.cell.bottom - k.cell.top) / (kb.metrics.mainHeight / 4f)).toInt())
        // 所有键两两不重叠：不只左右键，整块面板都不该有两键争同一格。
        // No two keys share a cell — not just the arrows; the whole pad is checked.
        for (i in pad.keys.indices) for (j in i + 1 until pad.keys.size) {
            val a = pad.keys[i].cell; val b = pad.keys[j].cell
            assertFalse("${pad.keys[i].id} 与 ${pad.keys[j].id} 重叠",
                Rect.intersects(Rect(a.left.toInt(), a.top.toInt(), a.right.toInt(), a.bottom.toInt()),
                    Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())))
        }
        // 左键在自己格子的正中：点它中间真的会按到左移。 The left arrow sits at its own centre, so tapping it moves left.
        val cx = (left.cell.left + left.cell.right) / 2f
        val cy = (left.cell.top + left.cell.bottom) / 2f
        assertTrue(left.cell.contains(cx, cy))
    }

    /** 点一下搜索行，字母键才铺开。 Tap the search row to unfold the letter pad. */
    private fun openPad(p: SymbolPanel): PanelKeys {
        val row = find(p.view, com.weavetext.ime.ui.keyboard.SearchRow::class.java)!!
        layout(row, 1080, 100)
        tapAt(row, 200f, 50f)
        val pad = find(p.view, PanelKeys::class.java) as PanelKeys
        layout(pad, 1080, 200)
        return pad
    }

    @Test fun tappingTheSearchRowUnfoldsTheLetterPad() {
        kb.showPanel("symbol")
        val p = kb.panelNamed("symbol") as SymbolPanel
        p.selectEmoji(); idle()
        val pad = find(p.view, PanelKeys::class.java) as PanelKeys
        assertEquals("没点之前字母键收着，网格占满高度", View.GONE, pad.visibility)
        openPad(p)
        assertEquals("点搜索行后字母键出现", View.VISIBLE, pad.visibility)
        assertTrue("字母键有高度", pad.height > 0)
    }

    @Test fun tappingLettersOnThePanelSearchesEmoji() {
        kb.showPanel("symbol")
        val p = kb.panelNamed("symbol") as SymbolPanel
        p.selectEmoji(); idle()
        assertTrue("表情分类可以搜索", p.searchOpen)
        val pad = openPad(p)
        for (ch in "weixiao") tapLetter(pad, ch)
        val names = p.searchResults().mapNotNull { ExpressionCatalog.load(app)?.name(it) }
        assertTrue("点字母应当搜到「微笑」，实际：$names", names.any { it.contains("微笑") })
        assertEquals("搜索不该往编辑框写任何字", "", ic.text)
    }

    @Test fun thePanelPadBackspaceClearsTheQuery() {
        kb.showPanel("symbol")
        val p = kb.panelNamed("symbol") as SymbolPanel
        p.selectEmoji(); idle()
        val pad = openPad(p)
        for (ch in "dog") tapLetter(pad, ch)
        assertTrue(p.searchResults().isNotEmpty())
        // 退格键在第三行末尾。 The backspace sits at the end of the last row.
        val slot = pad.width / 10f
        val letters = "zxcvbnm"
        val start = (10f - (letters.length + 1.4f)) / 2f
        val x = (start + letters.length + 0.7f) * slot
        val y = 2.5f * (pad.height / 3f)
        tapAt(pad, x, y)
        tapAt(pad, x, y)
        tapAt(pad, x, y)
        assertTrue("退格三次后查询清空", !p.searchOpen || p.searchResults().isEmpty())
    }
}
