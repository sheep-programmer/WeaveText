package com.weavetext.ime.ui

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
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
import com.weavetext.ime.style.KeyboardStyle
import com.weavetext.ime.style.StyleParser
import com.weavetext.ime.testing.FakeEngine
import com.weavetext.ime.testing.FakeInputConnection
import com.weavetext.ime.ui.keyboard.ClipboardPanel
import com.weavetext.ime.ui.keyboard.ImeWindowHost
import com.weavetext.ime.ui.keyboard.Key
import com.weavetext.ime.ui.keyboard.KeyCode
import com.weavetext.ime.ui.keyboard.KeyboardView
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
 * 候选栏与剪贴板面板：本地候选列表不被英文三格样式截断；卡片与表头图标的动作和显示对得上。
 * Candidate bar and clipboard panel: a local candidate list survives the three-slot English style; a card and the
 * header icons act as they look.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-420dpi")
class ClipboardPanelTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var kb: WeaveKeyboard
    private lateinit var controller: InputController
    private lateinit var engine: FakeEngine
    private lateinit var ic: FakeInputConnection
    private lateinit var kv: KeyboardView

    private val host = object : ImeWindowHost {
        override fun hideKeyboard() {}
        override val window: android.view.Window? get() = null
    }

    @Before fun setUp() {
        android.provider.Settings.Global.putFloat(app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        WeavePrefs.of(app).edit().clear().commit()
        com.weavetext.ime.ime.ClipHistory.awaitIo()
        com.weavetext.ime.ime.ClipHistory.resetShared()
        File(app.filesDir, "clipboard").deleteRecursively()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val frame = FrameLayout(activity)
        ic = FakeInputConnection(frame)
        controller = InputController { ic }
        engine = FakeEngine()
        kb = WeaveKeyboard(activity, controller, host)
        frame.addView(kb.view, FrameLayout.LayoutParams(-1, -2))
        activity.setContentView(frame, ViewGroup.LayoutParams(-1, -1))
        controller.attachEngine(engine)
        controller.onStartInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, false)
        kb.onShown()
        idle()
        kv = kb.keyboardView
    }

    @After fun tearDown() { kb.dispose() }

    private fun idle() = ShadowLooper.idleMainLooper()
    private fun m() = kb.metrics
    private fun copy(text: String) {
        app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("t", text))
        idle()
    }

    private fun tapKey(code: Int) {
        val k: Key = kv.keyOf(code)!!
        val t = SystemClock.uptimeMillis()
        for (a in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val e = MotionEvent.obtain(t, t, a, k.rect.centerX(), k.rect.centerY(), 0)
            try { kv.dispatchTouchEvent(e) } finally { e.recycle() }
        }
        idle()
    }

    private fun tapView(v: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        for (a in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val e = MotionEvent.obtain(t, t, a, x, y, 0)
            try { v.dispatchTouchEvent(e) } finally { e.recycle() }
        }
        idle()
    }

    /** 画一遍：面板的命中矩形都在绘制时写入。 Draw once: the panels write their hit rects while drawing. */
    private fun draw(v: View) {
        val bmp = android.graphics.Bitmap.createBitmap(
            v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        bmp.recycle()
        idle()
    }

    /** 某个虚拟节点的可视位置（视图坐标）。 On-screen bounds of a virtual a11y node, in view coordinates. */
    private fun a11yRect(v: View, index: Int): Rect? {
        val p = v.accessibilityNodeProvider ?: return null
        val info = p.createAccessibilityNodeInfo(index) ?: return null
        val r = Rect()
        info.getBoundsInScreen(r)
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return Rect(r.left - loc[0], r.top - loc[1], r.right - loc[0], r.bottom - loc[1])
    }

    private fun openPanel(name: String): ClipboardPanel {
        kb.showPanel(name)
        idle()
        return kb.panel as ClipboardPanel
    }

    @Test fun nineKeyPunctuationIsNotCutToThreeSlots() {
        WeavePrefs.of(app).edit().putString(WeavePrefs.KEYBOARDS, "t9").putString(WeavePrefs.ACTIVE_KEYBOARD, "t9").commit()
        idle()
        // 「英文三格」样式只描述英文联想词。九键的标点列表借同一个居中行来展示，但不该被砍成三格。
        // The three-slot style describes English suggestions. The 9-key punctuation list borrows the same centred row
        // for display but must not be cut down to three slots.
        val base = kb.style
        val layout = StyleParser.layout(org.json.JSONObject("{\"id\":\"t\",\"candidates\":{\"english\":\"strip3\"}}"))
        kb.topBar.applyStyle(KeyboardStyle(layout, base.theme, base.dark, base.overrides, base.palette, base.metrics), kb.icons, emptyList())
        idle()
        tapKey(KeyCode.T9_ONE)
        assertTrue("本地标点列表要能滚动看全", kb.topBar.measuredCount > 3)
        assertTrue("第四项之后仍然存在", kb.topBar.candidateAt(3) != null)
    }

    @Test fun theCurrentClipboardCardIsTappableNotAnActionRow() {
        // 关掉记录（这个键一出现就算问过了），面板只剩当前剪贴板这一条。
        // Recording off (the key merely existing counts as asked); the panel then holds only the current clipboard.
        WeavePrefs.of(app).edit().putBoolean(WeavePrefs.CLIPBOARD_RECORD, false).commit()
        copy("只有当前这一条")
        val panel = openPanel("clipboard")
        // 面板从上到下是「表头 | 搜索行 | 卡片列表」。 The panel is header | search row | cards.
        val grid = panel.view.getChildAt(panel.view.childCount - 1)
        // 动作行是绘制时叠上去的，且命中矩形也在那时写入：先画一遍，才能看出它到底有没有盖在卡片上。
        // The action row is drawn over the card and its hit rects are written then: draw once to see whether it is
        // really there.
        draw(grid)
        val r = a11yRect(grid, 0)!!
        val w = (r.width() - m().dp(16f)) / 3
        // 卡片左三分之一：那里不该有「固定」按钮——当前剪贴板这一条不是历史项，固定与删除都无从下手。
        // Left third of the card: it must not carry a "pin" button — the current-clipboard entry is not a history entry,
        // so pinning and deleting it have nothing to act on.
        tapView(grid, r.left + m().dp(8f) + w / 2f, r.centerY().toFloat())
        assertEquals("点卡片应该上屏", "只有当前这一条", ic.text)
    }

    @Test fun thePhraseHeaderPlusAddsTheClipboardText() {
        WeavePrefs.of(app).edit().putBoolean(WeavePrefs.CLIPBOARD_RECORD, true).commit()
        copy("要点")
        val panel = openPanel("phrases")
        val header = panel.view.getChildAt(0)
        // 表头图标从右往左排，动作按同一次序分派：最右一格必须是「+」，点它添加常用语，而不是多选的铅笔。
        // Header icons run right to left and the actions follow the same order: the rightmost cell has to be the "+",
        // which adds a phrase, not the multi-select pencil.
        val icons = panel.headerIconsForTest()
        assertEquals("最右一格是加号", com.weavetext.ime.R.drawable.ic_plus, icons[0])
        assertEquals("其次是多选的铅笔", com.weavetext.ime.R.drawable.ic_edit, icons[1])
        // 命中矩形在绘制时写入，先画一遍再点。
        // Hit rects are written while drawing; draw once before tapping.
        draw(header)
        val before = kb.clipboard.phrases.list(System.currentTimeMillis()).map { it.text }
        tapView(header, header.width - m().dp(8f) - m().dp(18f), header.height / 2f)
        val after = kb.clipboard.phrases.list(System.currentTimeMillis()).map { it.text }
        assertEquals("右端加号应该新增一条常用语", before.size + 1, after.size)
        assertTrue("新增的是剪贴板内容", after.contains("要点"))
    }

    /** 打开搜索：点表头最左一格的放大镜（图标从右往左，搜索排在最后）。 Open the search from the leftmost icon. */
    private fun openSearch(panel: ClipboardPanel): View {
        val header = panel.view.getChildAt(0)
        draw(header)
        val icons = panel.headerIconsForTest()
        tapView(header, header.width - m().dp(8f) - m().dp(40f) * (icons.size - 1) - m().dp(18f), header.height / 2f)
        assertEquals("放大镜要打开搜索行", icons.last(), com.weavetext.ime.R.drawable.ic_search)
        return panel.view.getChildAt(1)
    }

    @Test fun searchingFiltersTheClipboardAndNeverReachesTheEditor() {
        WeavePrefs.of(app).edit().putBoolean(WeavePrefs.CLIPBOARD_RECORD, true).commit()
        copy("https://example.com/a")
        copy("订单号 88123")
        copy("https://example.com/b")
        val panel = openPanel("clipboard")
        assertFalse("搜索行默认收着", panel.searchOpen)
        openSearch(panel)
        assertTrue("放大镜打开搜索", panel.searchOpen)
        // 字母进查询，不上屏；列表按字面过滤。 Letters filter the list and never reach the editor.
        for (c in "example") kb.onKey(Key(c.code))
        idle()
        assertEquals("查询不进编辑器", "", ic.text)
        assertEquals(2, panel.searchResultsForTest().size)
        // 删空之后再按一次退格收起搜索行，那一下不会删到编辑器里的文字。
        // Deleting the query down to nothing and pressing backspace once more closes the row, without deleting text.
        repeat(8) { kb.onKey(Key(KeyCode.DELETE)) }
        idle()
        assertFalse("删空后再退格即收搜索行", panel.searchOpen)
        assertEquals(3, panel.searchResultsForTest().size)
        assertEquals("那一下不能删编辑器里的字", "", ic.text)
        openSearch(panel)
        for (c in "88123".toCharArray()) kb.onKey(Key(c.code))
        idle()
        assertEquals(1, panel.searchResultsForTest().size)
        // 回车取第一个结果上屏。 Enter inserts the first hit.
        kb.onKey(Key(KeyCode.ENTER)); idle()
        assertEquals("订单号 88123", ic.text)
        repeat(8) { kb.onKey(Key(KeyCode.DELETE)) }
        idle()
        assertTrue("删空即退出搜索", panel.searchResultsForTest().size == 3)
        assertTrue("搜索行收回去", panel.view.getChildAt(1).height == 0)
    }

    @Test fun pinyinFindsChineseClipsThroughTheEngine() {
        WeavePrefs.of(app).edit().putBoolean(WeavePrefs.CLIPBOARD_RECORD, true).commit()
        copy("他微笑着说好")
        copy("明天见")
        engine.readings = mapOf("weixiao" to listOf("微笑"))
        val panel = openPanel("clipboard")
        openSearch(panel)
        for (c in "weixiao") kb.onKey(Key(c.code))
        idle()
        // 搜索框只能收字母：内核把 weixiao 读成「微笑」之后才匹配得到中文内容。
        // The box only takes letters, so the engine's reading is what finds the Chinese clip.
        assertEquals(1, panel.searchResultsForTest().size)
        assertEquals("他微笑着说好", panel.searchResultsForTest().first().text)
        assertEquals("查询不进编辑器", "", ic.text)
    }

    @Test fun spaceAndPunctuationStayOutOfTheEditor() {
        WeavePrefs.of(app).edit().putBoolean(WeavePrefs.CLIPBOARD_RECORD, true).commit()
        copy("他说「好」")
        copy("a,b")
        val panel = openPanel("clipboard")
        openSearch(panel)
        // 查询空着时：空格与标点都归搜索框，正文一个字也不动。
        // Empty query: space and punctuation belong to the search box, and the document stays untouched.
        kb.onKey(Key(KeyCode.SPACE)); kb.onKey(Key(KeyCode.SPACE)); kb.onKey(Key(','.code))
        idle()
        assertEquals("空格与标点不能落进正文", "", ic.text)
        // 逗号进了查询：只留下含逗号的那条。 The comma went into the query: only the clip with a comma matches.
        assertEquals("标点进查询", 1, panel.searchResultsForTest().size)
        assertEquals("a,b", panel.searchResultsForTest().first().text)
        // 长按 / 上滑送来的引号同样进查询（走字符输出那条路）。 A quote from long-press goes in too (the text path).
        kb.onKeyText(Key('w'.code), "「"); idle()
        assertEquals("引号也进查询", 0, panel.searchResultsForTest().size)
        assertEquals("", ic.text)
    }

    @Test fun leavingSearchWithBackKeepsThePanelOpen() {
        WeavePrefs.of(app).edit().putBoolean(WeavePrefs.CLIPBOARD_RECORD, true).commit()
        copy("订单号 88123")
        val panel = openPanel("clipboard")
        openSearch(panel)
        for (c in "88123" .toCharArray()) kb.onKey(Key(c.code))
        idle()
        assertTrue(panel.searchResultsForTest().isNotEmpty())
        assertTrue("返回键先退搜索", kb.handleBack())
        assertEquals("面板还开着", panel, kb.panel)
        assertFalse("搜索行收回去", panel.searchOpen)
        assertEquals("搜索退出后列表回到全部", 1, panel.searchResultsForTest().size)
    }
}
