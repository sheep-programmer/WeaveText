package com.weavetext.ime.ui

import android.app.Activity
import android.app.Application
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.weavetext.ime.ime.InputController
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.testing.FakeEngine
import com.weavetext.ime.testing.FakeInputConnection
import com.weavetext.ime.ui.keyboard.ExpressionCatalog
import com.weavetext.ime.ui.keyboard.ImeWindowHost
import com.weavetext.ime.ui.keyboard.Key
import com.weavetext.ime.ui.keyboard.KeyCode
import com.weavetext.ime.ui.keyboard.ScrollGridView
import com.weavetext.ime.ui.keyboard.SymbolData
import com.weavetext.ime.ui.keyboard.SymbolPanel
import com.weavetext.ime.ui.keyboard.WeaveKeyboard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * 表情搜索在面板里的完整链路：键盘字母进查询（不上屏）、结果网格、回车选择、退格与返回退出。
 * The whole search path inside the panel: keyboard letters go to the query (never the editor), the grid shows hits,
 * Enter picks the first, and backspace or Back leaves the search.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-420dpi")
class ExpressionSearchPanelTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var kb: WeaveKeyboard
    private lateinit var controller: InputController
    private lateinit var ic: FakeInputConnection
    private val dir = File("/tmp/weavetext-expression-android-ui")

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
        kb.onShown()
        idle()
    }

    @After fun tearDown() = kb.dispose()

    private fun idle() = ShadowLooper.idleMainLooper()
    private fun text(s: String) { for (c in s) kb.onKey(Key(c.code)) }
    private fun key(code: Int) = kb.onKey(Key(code))
    private fun panel() = kb.panelNamed("symbol") as SymbolPanel
    private fun catalog() = ExpressionCatalog.load(app)!!

    private fun grid(v: View): ScrollGridView? {
        if (v.javaClass.name.endsWith("SymbolPanel\$Grid")) return v as ScrollGridView
        if (v is ViewGroup) for (i in 0 until v.childCount) grid(v.getChildAt(i))?.let { return it }
        return null
    }

    @Test fun lettersSearchByNameWithoutReachingTheEditor() {
        kb.showPanel("symbol"); val p = panel(); p.selectEmoji(); idle()
        assertTrue("the emoji tab can be searched", p.searchOpen)
        text("weixiao"); idle()
        assertEquals("nothing may be committed while searching", "", ic.text)
        assertEquals("", controller.state.preedit)
        assertEquals("微笑", catalog().name(p.searchResults().first()))
        // 结果里有「羞涩微笑」，但以关键词开头的「微笑」排第一。 微笑 leads because its name starts with the query.
        assertTrue(p.searchResults().mapNotNull { catalog().name(it) }.contains("羞涩微笑"))
        // 读屏读到「名字 · 拼音」，不用打开表情就能核对。 The a11y label carries the pinyin.
        val node = grid(kb.view)!!.accessibilityNodeProvider.createAccessibilityNodeInfo(0)!!
        assertTrue(node.contentDescription.toString().contains("wei xiao"))
        capture("search_weixiao")
    }

    @Test fun englishAndKaomojiSearchUseTheSameBox() {
        kb.showPanel("symbol"); val p = panel(); p.selectEmoji(); idle()
        text("dog"); idle()
        assertTrue(p.searchResults().mapNotNull { catalog().name(it) }.any { it.contains("狗") })
        key(KeyCode.DELETE); key(KeyCode.DELETE); key(KeyCode.DELETE); idle()
        // 表情里也按拼音搜：gou → 狗。 Pinyin works on emoji as well: gou finds the dog.
        text("gou"); idle()
        assertTrue(p.searchResults().mapNotNull { catalog().name(it) }.any { it.contains("狗") })
        // 换分类会清空查询；颜文字分类里搜的是颜文字，不是表情。 Switching tabs clears the query; kaomoji search kaomoji.
        p.selectTab(SymbolData.TAB_KAOMOJI); idle()
        assertTrue(p.searchResults().isEmpty())
        text("kaixin"); idle()
        assertTrue(p.searchResults().isNotEmpty())
        // 结果全是颜文字（表情的「开心笑」在同一关键词下不会混进来）。 Every hit is a kaomoji.
        val subjects = p.searchResults().filter { catalog().kaomoji.any { k -> k.text == it } }
        assertEquals(p.searchResults().size, subjects.size)
    }

    @Test fun enterCommitsTheFirstHitAndSpaceOnlyEndsThePinyin() {
        kb.showPanel("symbol"); val p = panel(); p.selectEmoji(); idle()
        text("weixiao"); idle()
        key(KeyCode.ENTER); idle()
        assertEquals("回车直接取第一个结果", "微笑", catalog().name(ic.text))
        // 空格只把拼音确定下来（下划线消失），不上屏。 Space only settles the pinyin.
        ic.editable!!.clear()
        repeat(7) { key(KeyCode.DELETE) }
        text("gou"); idle()
        key(KeyCode.SPACE); idle()
        assertEquals("空格不上屏", "", ic.text)
        assertTrue(p.searchResults().isNotEmpty())
    }

    @Test fun backspaceAndBackLeaveTheSearchBeforeThePanel() {
        kb.showPanel("symbol"); val p = panel(); p.selectEmoji(); idle()
        text("gou"); idle()
        assertTrue(p.searchResults().isNotEmpty())
        repeat(3) { key(KeyCode.DELETE) }; idle()
        assertTrue("退格删空即退出搜索", p.searchResults().isEmpty())
        assertNotNull("面板还开着", kb.panel)
        text("gou"); idle()
        assertTrue(p.searchResults().isNotEmpty())
        assertTrue("返回键先退搜索", kb.handleBack())
        assertTrue(p.searchResults().isEmpty())
        assertNotNull("面板还开着", kb.panel)
        // 面板的返回由面板自己的底行处理（这里只验证搜索先退出）。 Closing the panel itself is the bottom row's Back.
        assertNotNull(kb.panel)
    }

    @Test fun leavingOrSwitchingClearsTheQueryOnTheRow() {
        kb.showPanel("symbol"); val p = panel(); p.selectEmoji(); idle()
        text("weixiao"); idle()
        assertEquals("weixiao", p.searchTextForTest())
        // 收起搜索：网格回到全部表情，行上不能还挂着旧词（看着像还在筛选）。
        // Leave the search: the grid shows everything again and the row must not keep the old query.
        assertTrue(kb.handleBack()); idle()
        assertTrue(p.searchResults().isEmpty())
        assertEquals("退出搜索后行上不应留着旧词", "", p.searchTextForTest())
        text("gou"); idle()
        assertEquals("gou", p.searchTextForTest())
        // 换分类同样清空查询与行上的字。 Switching tabs clears both too.
        p.selectTab(SymbolData.TAB_KAOMOJI); idle()
        assertEquals("", p.searchTextForTest())
    }

    @Test fun spaceAndPunctuationWithAnEmptyQueryNeverReachTheEditor() {
        kb.showPanel("symbol"); val p = panel(); p.selectEmoji(); idle()
        // 查询空着时按空格：面板吃掉，正文里不能出现空格（也不触发双空格变句号）。
        // Space on an empty query is eaten by the panel: no space in the document, no double-space period.
        key(KeyCode.SPACE); key(KeyCode.SPACE); idle()
        assertEquals("空格不能落进正文", "", ic.text)
        // 逗号在查询空着时同样归搜索框；它进的是查询（标点可以搜索），正文不受影响。
        // A comma on an empty query belongs to the box as well; it goes into the query, not the document.
        key(','.code); idle()
        assertEquals("，不能落进正文", "", ic.text)
        assertEquals(",", p.searchTextForTest().trim())
        // 标点在查询里起作用之后，字母照常接上。 Letters still append after it.
        text("abc"); idle()
        assertTrue(p.searchTextForTest().contains(","))
    }

    @Test fun swipeUpDuringASearchRewritesTheQueryInsteadOfTheDocument() {
        // 先上屏一个字，再打开搜索：上滑改写不能把它当成「刚输出的字母」撤掉。
        // Commit a character, then open the search: the swipe must not undo it as the char just emitted.
        controller.onText("好"); idle()
        kb.showPanel("symbol"); val p = panel(); p.selectEmoji(); idle()
        assertTrue("先有正文", ic.text.isNotEmpty())
        val committed = ic.text
        kb.onKey(Key('w'.code)); idle()
        assertEquals("w", p.searchTextForTest())
        kb.onKeyReplace(Key('w'.code), "1"); idle()
        assertEquals("上滑不能删掉已经上屏的字", committed, ic.text)
        assertFalse("按下的字母先撤掉，不留 w1", p.searchTextForTest().contains("w1"))
        assertEquals("改写后的字符进了查询", "1", p.searchTextForTest())
    }

    @Test fun physicalKeyboardTypesIntoTheSearchBox() {
        kb.showPanel("symbol"); val p = panel(); p.selectEmoji(); idle()
        val down = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_W)
        assertTrue("实体键盘的字母进查询", kb.hardwareToPanel(android.view.KeyEvent.KEYCODE_W, down))
        idle()
        assertEquals("w", p.searchTextForTest())
        assertEquals("正文不受影响", "", ic.text)
        // 方向键与快捷键不抢：照旧交给内核与 App。 Arrows and shortcuts keep their usual routing.
        val left = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT)
        assertFalse(kb.hardwareToPanel(android.view.KeyEvent.KEYCODE_DPAD_LEFT, left))
    }

    @Test fun symbolCategoriesCannotBeSearchedAndHideNothingElse() {
        kb.showPanel("symbol"); val p = panel()
        val cats = SymbolData.categories(emptyList(), catalog())
        val punctuation = cats.indexOfFirst { !it.emoji && !it.kaomoji }
        p.selectTab(punctuation); idle()
        assertFalse(p.searchOpen)
        // 标点分类里打字照常进编辑器（中文模式下先去内核组合）。 Letters reach the editor on punctuation tabs.
        key('a'.code); idle()
        assertEquals("a", controller.state.preedit)
    }

    private fun capture(name: String) {
        idle()
        kb.view.captureRoboImage(File(dir, "$name.png").path)
    }
}
