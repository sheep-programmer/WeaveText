package com.weavetext.ime.ui

import android.app.Activity
import android.app.Application
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.ime.InputController
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.testing.FakeEngine
import com.weavetext.ime.testing.FakeInputConnection
import com.weavetext.ime.ui.keyboard.ImeWindowHost
import com.weavetext.ime.ui.keyboard.Key
import com.weavetext.ime.ui.keyboard.KeyCode
import com.weavetext.ime.ui.keyboard.KeyboardView
import com.weavetext.ime.ui.keyboard.WeaveKeyboard
import com.weavetext.ime.ui.keyboard.clampLeft
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
 * 打字手感回归：按真实的多指 MotionEvent 序列回放，检查输出顺序、丢键、空格横滑、长按与帧合并渲染。
 * Typing-feel regressions: replay real multi-pointer MotionEvent sequences and check output order,
 * dropped keys, space-bar slides, long presses and frame-coalesced rendering.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-420dpi")
class TypingFeelTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var kb: WeaveKeyboard
    private lateinit var controller: InputController
    private lateinit var engine: FakeEngine
    private lateinit var ic: FakeInputConnection
    private lateinit var kv: KeyboardView
    private val menuActions = mutableListOf<Int>()

    private val host = object : ImeWindowHost {
        override fun hideKeyboard() {}
        override val window: android.view.Window? get() = null
    }

    @Before fun setUp() {
        android.provider.Settings.Global.putFloat(app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        // 本类按手势导航的手机写（底部没有三键导航栏）：键盘不会预先留出底边，需要留白的用例自己发边衬。
        // This class is written for a phone without the three-button bar: nothing is reserved up front, and the cases
        // which need clearance dispatch it themselves.
        android.provider.Settings.Secure.putInt(app.contentResolver, "navigation_mode", 2)
        WeavePrefs.of(app).edit().clear().commit()
        // 上一个测试的剪贴板写入可能还在后台排队。 A previous test's clip writes may still be queued.
        com.weavetext.ime.ime.ClipHistory.awaitIo()
        File(app.filesDir, "clipboard").deleteRecursively()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val frame = FrameLayout(activity)
        ic = object : FakeInputConnection(frame) {
            override fun performContextMenuAction(id: Int): Boolean { menuActions += id; return true }
        }
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
    /** 让复核用的延迟任务跑完（复核按间隔算，时间要往前走）。 Let the delayed confirmation run; it is measured in time. */
    private fun settle() = ShadowLooper.idleMainLooper(250, java.util.concurrent.TimeUnit.MILLISECONDS)
    private fun hold(ms: Long) = ShadowLooper.idleMainLooper(ms, java.util.concurrent.TimeUnit.MILLISECONDS)

    // ------------------------------------------------------------ event replay

    /** 当前按着的手指：id → 坐标。 Fingers currently down: id → position. */
    private val down = LinkedHashMap<Int, Pair<Float, Float>>()
    private var downTime = 0L

    private fun key(c: Char) = kv.keyOf(c.code)!!
    private fun key(code: Int) = kv.keyOf(code)!!

    private fun send(action: Int, index: Int) {
        val ids = down.keys.toList()
        val props = Array(ids.size) { i -> MotionEvent.PointerProperties().apply { id = ids[i]; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(ids.size) { i -> MotionEvent.PointerCoords().apply { x = down[ids[i]]!!.first; y = down[ids[i]]!!.second; pressure = 1f; size = 1f } }
        val a = if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_CANCEL) action else action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), a, ids.size, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        kv.dispatchTouchEvent(e)
        e.recycle()
    }

    private fun press(id: Int, k: Key, dx: Float = 0f, dy: Float = 0f) {
        if (down.isEmpty()) downTime = SystemClock.uptimeMillis()
        down[id] = (k.rect.centerX() + dx) to (k.rect.centerY() + dy)
        send(if (down.size == 1) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN, down.keys.indexOf(id))
    }

    private fun move(id: Int, dx: Float, dy: Float) {
        val (x, y) = down[id]!!
        down[id] = (x + dx) to (y + dy)
        send(MotionEvent.ACTION_MOVE, 0)
    }

    private fun release(id: Int, dx: Float = 0f, dy: Float = 0f) {
        val (x, y) = down[id]!!
        down[id] = (x + dx) to (y + dy)
        send(if (down.size == 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP, down.keys.indexOf(id))
        down.remove(id)
    }

    private fun cancelAll() {
        send(MotionEvent.ACTION_CANCEL, 0)
        down.clear()
    }

    private fun tap(c: Char) { press(9, key(c)); release(9) }
    private fun dp(v: Float) = v * app.resources.displayMetrics.density

    // ------------------------------------------------------------ multi-touch rollover

    /** 导航栏边衬：底部与横屏侧边都要让开；视图重建后也能拿到。 Navigation bar insets, bottom and sides. */
    @Test fun anAutoCapitalFromEnglishDoesNotCarryIntoChinese() {
        controller.onStartInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES }, false)
        kb.onShown()
        idle()
        val lang = kv.keyOf(com.weavetext.ime.ui.keyboard.KeyCode.LANG)!!
        press(9, lang); release(9)
        assertFalse("switched to English", controller.state.chinese)
        press(9, kv.keyOf(com.weavetext.ime.ui.keyboard.KeyCode.LANG)!!); release(9)
        assertTrue("back to Chinese", controller.state.chinese)
        tap('n')
        // 拼音 n 进组合串，不是直接上屏一个大写 N。 Pinyin n composes; no capital N is committed.
        assertEquals("", ic.text)
        assertTrue(engine.isComposing())
    }

    private fun englishField(text: String = "", caps: Boolean = true) {
        if (text.isNotEmpty()) ic.commitText(text, 1)
        controller.onStartInput(EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or if (caps) InputType.TYPE_TEXT_FLAG_CAP_SENTENCES else 0
            initialSelStart = text.length; initialSelEnd = text.length
        }, false)
        controller.toggleChinese()
        kb.onShown(); idle()
    }

    private fun tapShift() { press(9, key(KeyCode.SHIFT)); release(9) }

    private fun select(start: Int, end: Int = start) {
        ic.setSelection(start, end)
        controller.onSelectionUpdate(start, end, -1, -1)
        kb.onSelectionChanged(start, end)
        kb.flushRender()
    }

    @Test fun tappingShiftAfterAQuicklyTypedCapitalDoesNotLockEnglishCaps() {
        englishField(caps = false)
        tapShift(); tap('a'); tapShift(); tap('b'); tap('c')
        kb.onKey(key(KeyCode.SPACE))
        assertEquals("ABc ", ic.text)
        assertEquals(com.weavetext.ime.R.drawable.ic_shift, key(KeyCode.SHIFT).icon)
    }

    @Test fun toolbarSwitchToChineseClearsAutomaticEnglishCaps() {
        kb.prefs.edit().putString(WeavePrefs.KEYBOARDS, "pinyin").commit()
        englishField()
        assertEquals(com.weavetext.ime.R.drawable.ic_shift_filled, key(KeyCode.SHIFT).icon)
        kb.onToolbar(1)
        tap('n')
        assertTrue(controller.state.chinese)
        assertEquals("n", controller.state.preedit)
        assertEquals("", ic.text)
    }

    @Test fun emojiToolbarHighlightFollowsOpeningClosingAndPanelSwitches() {
        kb.prefs.edit().putString(WeavePrefs.TOOLBAR_ITEMS, "emoji,clipboard,hide").commit()
        idle()
        fun clickTool(id: Int) {
            val node = kb.topBar.accessibilityNodeProvider!!.createAccessibilityNodeInfo(id)!!
            val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
            val origin = IntArray(2).also(kb.topBar::getLocationOnScreen)
            val x = bounds.exactCenterX() - origin[0]
            val y = bounds.exactCenterY() - origin[1]
            val start = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(start, start, action, x, y, 0)
                try { assertTrue(kb.topBar.dispatchTouchEvent(event)) } finally { event.recycle() }
            }
            idle()
        }
        fun emojiState() = kb.topBar.accessibilityNodeProvider!!
            .createAccessibilityNodeInfo(com.weavetext.ime.style.ToolIds.EMOJI)!!.stateDescription

        clickTool(com.weavetext.ime.style.ToolIds.EMOJI)
        assertTrue(kb.panel is com.weavetext.ime.ui.keyboard.SymbolPanel)
        assertEquals("已打开", emojiState())
        assertEquals(com.weavetext.ime.style.ToolIds.EMOJI, kb.topBar.activeTool)
        clickTool(com.weavetext.ime.style.ToolIds.EMOJI)
        assertEquals(null, kb.panel)
        assertEquals(null, emojiState())
        clickTool(com.weavetext.ime.style.ToolIds.EMOJI)
        clickTool(com.weavetext.ime.style.ToolIds.CLIPBOARD)
        assertEquals(com.weavetext.ime.style.ToolIds.CLIPBOARD, kb.topBar.activeTool)
        assertEquals(null, emojiState())
        clickTool(com.weavetext.ime.style.ToolIds.EMOJI)
        assertEquals("已打开", emojiState())
    }

    @Test fun hardwareSwitchToChineseClearsAutomaticEnglishCaps() {
        englishField()
        val hardware = com.weavetext.ime.ime.HardwareKeys(controller)
        val code = android.view.KeyEvent.KEYCODE_SPACE
        assertTrue(hardware.onKeyDown(code, android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN,
            code, 0, android.view.KeyEvent.META_CTRL_ON)))
        tap('n')
        assertEquals("n", controller.state.preedit)
        assertEquals("", ic.text)
    }

    @Test fun automaticCapsFollowTheCursorFromTheMiddleToTheStartAndBack() {
        englishField("hello")
        select(0)
        assertEquals(com.weavetext.ime.R.drawable.ic_shift_filled, key(KeyCode.SHIFT).icon)
        select(3)
        assertEquals(com.weavetext.ime.R.drawable.ic_shift, key(KeyCode.SHIFT).icon)
        tap('x'); kb.onKey(key(KeyCode.SPACE))
        assertEquals("helx lo", ic.text)
    }

    @Test fun cursorUpdatesKeepManualCapsAndDoNotUndoAnExplicitLowercaseChoice() {
        englishField()
        tapShift()
        select(0)
        assertEquals(com.weavetext.ime.R.drawable.ic_shift, key(KeyCode.SHIFT).icon)
        tap('a'); kb.onKey(key(KeyCode.SPACE))
        assertEquals("a ", ic.text)
        select(1)
        tapShift()
        select(2)
        assertEquals(com.weavetext.ime.R.drawable.ic_shift_filled, key(KeyCode.SHIFT).icon)
    }

    @Test fun aSwipeToADigitRestoresTheOneShotCapitalForTheNextLetter() {
        englishField(caps = false)
        tapShift()
        press(9, key('q'))
        release(9, dy = -dp(55f))
        tap('a'); kb.onKey(key(KeyCode.SPACE))
        assertEquals("1A ", ic.text)
    }

    @Test fun cancellingACapitalDoesNotSpendTheOneShotShift() {
        englishField(caps = false)
        tapShift()
        press(9, key('q')); cancelAll()
        tap('a'); kb.onKey(key(KeyCode.SPACE))
        assertEquals("A ", ic.text)
    }

    @Test fun punctuationAlternativesDoNotSpendTheOneShotShift() {
        englishField(caps = false)
        tapShift()
        kb.onKeyText(key(','), ";")
        tap('a'); kb.onKey(key(KeyCode.SPACE))
        assertEquals(";A ", ic.text)
    }

    @Test fun shuangpinShiftCanSendACapitalInsideTheComposition() {
        kb.prefs.edit().putString(WeavePrefs.KEYBOARDS, "shuangpin,english")
            .putString(WeavePrefs.ACTIVE_KEYBOARD, "shuangpin").commit()
        idle()
        tap('n'); tap('i')
        tapShift()
        assertEquals(com.weavetext.ime.R.drawable.ic_shift_filled, key(KeyCode.SHIFT).icon)
        assertEquals("A", key('a').label)
        tap('a')
        assertEquals("niA", controller.state.preedit)
        assertEquals("", ic.text)
    }

    @Test fun keepsClearOfTheNavigationBar() {
        val insets = android.view.WindowInsets.Builder()
            .setInsets(android.view.WindowInsets.Type.navigationBars(), android.graphics.Insets.of(0, 0, 0, 126))
            .build()
        kb.view.dispatchApplyWindowInsets(insets)
        idle()
        assertEquals(126, kb.navInset)
        val side = android.view.WindowInsets.Builder()
            .setInsets(android.view.WindowInsets.Type.navigationBars(), android.graphics.Insets.of(0, 0, 90, 0))
            .build()
        kb.view.dispatchApplyWindowInsets(side)
        // 底部那条归零要隔一会儿复核同一份读数才认（切应用时会先报一次 0）。
        // Giving the bottom strip up waits for the confirming re-read.
        settle()
        assertEquals(0, kb.navInset)
        // 键区整体在导航栏左边。 The keys end left of the side bar.
        val loc = IntArray(2)
        kv.getLocationInWindow(loc)
        val root = IntArray(2)
        kb.view.getLocationInWindow(root)
        assertTrue("kv=${loc[0]}+${kv.width} root=${root[0]}+${kb.view.width}", loc[0] - root[0] + kv.width <= kb.view.width - 90)
    }

    @Test fun floatingKeyboardCannotBePlacedOverSideNavigation() {
        val inset = android.view.WindowInsets.Builder()
            .setInsets(android.view.WindowInsets.Type.navigationBars(), android.graphics.Insets.of(90, 0, 126, 0))
            .build()
        kb.view.dispatchApplyWindowInsets(inset)
        WeavePrefs.of(app).edit().putString(WeavePrefs.FLOAT_POS_PORT, "1,1").commit()
        kb.toggleFloating()
        idle()
        val card = kb.board.parent as android.view.View
        assertTrue("left edge must stay clear", card.translationX >= 90)
        assertTrue("right edge must stay clear", card.translationX + card.width <= kb.view.width - 126)
    }

    @Test fun floatingChromeKeepsThinReservedGuttersWhenCornerPolicyChanges() {
        kb.toggleFloating()
        idle()
        val card = kb.board.parent as android.view.View
        assertEquals(kb.metrics.dp(28f + 14f), (card.height - kb.board.height).toFloat(), 2f)
        com.weavetext.ime.settings.FloatingResizeSettings.write(WeavePrefs.of(app),
            setOf(com.weavetext.ime.ui.keyboard.FloatingResizeCorner.TOP_LEFT, com.weavetext.ime.ui.keyboard.FloatingResizeCorner.TOP_RIGHT))
        idle()
        // The new frame reserves the bottom gutter even when bottom corners are disabled.
        assertEquals(kb.metrics.dp(28f + 14f), (card.height - kb.board.height).toFloat(), 2f)
    }

    @Test fun floatingKeyboardFitsAConstrainedWindowWithoutShrinkingForever() {
        kb.toggleFloating()
        idle()
        val width = app.resources.displayMetrics.widthPixels
        val height = (300f * app.resources.displayMetrics.density).toInt()
        repeat(3) {
            kb.view.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY))
            kb.view.layout(0, 0, width, height)
        }
        val card = kb.board.parent as android.view.View
        assertTrue("card must keep readable keys", card.width >= 220 * app.resources.displayMetrics.density - 2)
        assertTrue("card bottom must fit the usable window", card.translationY + card.height <= height - kb.navInset - kb.metrics.dp(8f) + 2)
        assertTrue("card must stay below the upper edge", card.translationY >= kb.metrics.dp(24f) - 1)
    }

    @Test fun borderTapsCarryTheNeighbourToTheEngine() {
        val z = key('z')
        val x = key('x')
        val border = (z.rect.right + x.rect.left) / 2f
        // 正中：没有邻键。 Dead centre: no neighbour.
        tap('g')
        assertTrue(engine.nearCalls.isEmpty())
        // 贴着 z/x 交界、落在 x 一侧。 Just on x's side of the z/x border.
        press(9, x, dx = border + dp(1f) - x.rect.centerX())
        release(9)
        val (c, n, closeness) = engine.nearCalls.single()
        assertEquals('x', c)
        assertEquals('z', n)
        assertTrue(closeness > 0.8f)
        // 靠上沿：邻键是上一行的字母。 Near the top edge: the neighbour is in the row above.
        val g = key('g')
        press(9, g, dy = -(g.rect.height() / 2f + dp(3f)))
        release(9)
        assertTrue(engine.nearCalls.last().second in "ty")
        assertEquals("gxg", engine.raw.toString())
    }

    @Test fun rolloverKeepsPressOrder() {
        press(0, key('n'))
        press(1, key('i'))
        release(0)
        press(0, key('h'))
        release(1)
        release(0)
        assertEquals("nih", engine.raw.toString())
    }

    @Test fun secondFingerTypesWhileFirstDragsTheSpaceBar() {
        ic.commitText("hello", 1)
        val space = key(KeyCode.SPACE)
        press(0, space)
        move(0, -dp(10f), 0f)
        hold(30)
        move(0, -dp(30f), 0f)
        assertTrue("cursor moved", ic.cursorMoves < 0)
        press(1, key('b'))
        release(1)
        release(0)
        assertEquals("b", engine.raw.toString())
        assertFalse("the drag moved the cursor, so no space", ic.text.contains(' '))
    }

    @Test fun longPressingShiftLocksCapitalsInEnglishButNotIntoChinese() {
        controller.toggleChinese(); idle()
        press(0, key(KeyCode.SHIFT))
        hold(KeyboardView.LONG_PRESS_MS + 20)
        release(0)
        assertEquals(com.weavetext.ime.R.drawable.ic_shift_lock, key(KeyCode.SHIFT).icon)
        tap('o'); tap('k')
        assertEquals("OK", engine.raw.toString())
        // 切到中文：锁定不带过去，字母照常打拼音。 Switching to Chinese drops the lock; letters are pinyin again.
        controller.toggleChinese(); idle()
        tap('n')
        assertEquals("n", engine.raw.toString())
        // 中文里长按 Shift 不锁定，松手仍是一次大写。 In Chinese a long press does not lock; it is one capital.
        controller.reset(); idle()
        press(0, key(KeyCode.SHIFT))
        hold(KeyboardView.LONG_PRESS_MS + 20)
        release(0)
        tap('a'); tap('b')
        assertTrue(ic.text, ic.text.endsWith("A"))
        assertEquals("b", engine.raw.toString())
    }

    @Test fun theCursorPanelCutsTheSelection() {
        ic.commitText("hello", 1)
        ic.setSelection(0, 5)
        kb.showPanel("cursor"); idle()
        fun pads(view: android.view.View): List<android.view.View> = when {
            view.javaClass.name.endsWith(".PadView") -> listOf(view)
            view is ViewGroup -> (0 until view.childCount).flatMap { pads(view.getChildAt(it)) }
            else -> emptyList()
        }
        val pad = pads(kb.view).first { it.visibility == android.view.View.VISIBLE }
        val provider = pad.accessibilityNodeProvider!!
        // 键位按面板自己的顺序给出，不假定固定 15 个。 The pad reports its own keys, so no fixed count is assumed.
        var n = 0
        while (provider.createAccessibilityNodeInfo(n) != null) n++
        val labels = (0 until n).map { provider.createAccessibilityNodeInfo(it)?.contentDescription?.toString() }
        val cut = labels.indexOf("剪切")
        assertTrue(labels.toString(), cut >= 0 && "复制" in labels && "粘贴" in labels && "全选" in labels)
        // 撤销 / 重做：多次撤销交给编辑器，网页字段与终端里退回 Ctrl+Z / Ctrl+Shift+Z。 Undo/redo reach the editor.
        val undo = labels.indexOf("撤销")
        val redo = labels.indexOf("重做")
        assertTrue(labels.toString(), undo >= 0 && redo >= 0)
        assertTrue(provider.performAction(undo, android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null))
        assertTrue(provider.performAction(redo, android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(listOf(android.R.id.undo, android.R.id.redo), menuActions)
        assertEquals("已选中文字", kb.topBar.actionMessage)
        assertTrue(provider.performAction(cut, android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(listOf(android.R.id.undo, android.R.id.redo, android.R.id.cut), menuActions)
        // 选区没了，提示条随之收起。 With the selection gone, its strip goes too.
        ic.setSelection(0, 0)
        (kb.panel as com.weavetext.ime.ui.keyboard.CursorPanel).onSelection(false)
        assertEquals(null, kb.topBar.actionMessage)
    }

    @Test fun secondFingerTypesWhileFirstHoldsTheLongPressPopup() {
        press(0, key('q'))
        hold(KeyboardView.LONG_PRESS_MS + 20)
        assertTrue(kb.overlay!!.altShown)
        press(1, key('w'))
        assertFalse("a new key closes the popup", kb.overlay!!.altShown)
        release(0)
        release(1)
        assertEquals("qw", engine.raw.toString())
    }

    @Test fun secondFingerTypesWhileFirstClearsWithDelete() {
        val del = key(KeyCode.DELETE)
        press(0, del)
        move(0, -key('q').cell.width() * 2f, 0f)
        press(1, key('a'))
        release(1)
        assertEquals("a", engine.raw.toString())
        cancelAll()
    }

    @Test fun pendingFunctionKeyFiresBeforeTheNextFinger() {
        ic.commitText("x", 1)
        press(0, key(KeyCode.SPACE))
        press(1, key('a'))
        release(0)
        release(1)
        // 空格先于 a：空格已上屏，a 在组合中。 Space went first: committed, then a composes.
        assertEquals("x ", ic.text)
        assertEquals("a", engine.raw.toString())
    }

    @Test fun systemCancelTakesBackTheEmittedChar() {
        // 边缘返回手势等截走手指：按下即输出的字撤回。 The system took the finger (edge back gesture): the char is taken back.
        tap('n')
        press(0, key('z'))
        assertEquals("nz", engine.raw.toString())
        cancelAll()
        assertEquals("n", engine.raw.toString())
    }

    @Test fun internalResetKeepsTheEmittedChar() {
        // 面板切换等内部复位不撤回。 Internal resets (panel switches) keep it.
        press(0, key('z'))
        kv.cancelTouch()
        down.clear()
        assertEquals("z", engine.raw.toString())
    }

    @Test fun deleteRepeatRestoresAutoCapsAfterClearingTheComposition() {
        englishField()
        tap('a')
        assertEquals("A", engine.raw.toString())
        press(0, key(KeyCode.DELETE))
        hold(410)
        release(0)
        assertEquals("", engine.raw.toString())
        tap('b')
        kb.onKey(key(KeyCode.SPACE))
        assertEquals("B ", ic.text)
    }

    @Test fun deleteRepeatKeepsLowercaseInsideAComposition() {
        englishField()
        tap('a'); tap('b')
        press(0, key(KeyCode.DELETE))
        hold(410)
        release(0)
        assertEquals("A", engine.raw.toString())
        tap('c')
        kb.onKey(key(KeyCode.SPACE))
        assertEquals("Ac ", ic.text)
    }

    @Test fun deleteRepeatPreservesManuallyLockedCaps() {
        englishField(caps = false)
        tapShift(); tapShift()
        tap('a')
        press(0, key(KeyCode.DELETE))
        hold(410)
        release(0)
        tap('b'); tap('c')
        kb.onKey(key(KeyCode.SPACE))
        assertEquals("BC ", ic.text)
    }

    @Test fun deleteRepeatSurvivesThumbDrift() {
        ic.commitText("abcdefghijklmnopqrstuvwxyz", 1)
        press(0, key(KeyCode.DELETE))
        hold(400 + 50 * 3 + 10)
        val after = ic.text.length
        assertTrue("repeating, ${ic.text}", after < 26)
        // 连发中手指漂移 12 dp：继续删。 A 12 dp drift while repeating keeps deleting.
        move(0, dp(6f), dp(-10f))
        hold(50 * 4 + 10)
        assertTrue("still repeating: ${ic.text}", ic.text.length < after)
        release(0)
    }

    @Test fun ordinaryCandidateTapCommitsBeforeTheLongPressThreshold() {
        tap('n'); tap('i')
        hold(20)
        val bar = kb.topBar
        val y = bar.height - dp(8f)
        val x = dp(60f)
        val t0 = SystemClock.uptimeMillis()
        bar.dispatchTouchEvent(MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, x, y, 0))
        hold(200)
        bar.dispatchTouchEvent(MotionEvent.obtain(t0, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0))
        assertEquals("【ni】", ic.text)
    }

    @Test fun candidateTapWithSmallReleaseJitterStillCommits() {
        tap('n'); tap('i'); hold(20)
        val bar = kb.topBar
        val y = bar.height - dp(8f)
        val start = SystemClock.uptimeMillis()
        MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, dp(60f), y, 0).let {
            bar.dispatchTouchEvent(it); it.recycle()
        }
        hold(100)
        MotionEvent.obtain(start, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, dp(63f), y, 0).let {
            bar.dispatchTouchEvent(it); it.recycle()
        }
        assertEquals("【ni】", ic.text)
        assertEquals("", engine.raw.toString())
    }

    @Test fun candidateSlideToTheRightWithoutAMoveEventDoesNotCommit() {
        tap('n'); tap('i'); hold(20)
        val bar = kb.topBar
        val y = bar.height - dp(8f)
        val start = SystemClock.uptimeMillis()
        MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, dp(60f), y, 0).let {
            bar.dispatchTouchEvent(it); it.recycle()
        }
        hold(100)
        MotionEvent.obtain(start, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, dp(140f), y, 0).let {
            bar.dispatchTouchEvent(it); it.recycle()
        }
        assertEquals("a slide must leave the editor unchanged", "", ic.text)
        assertEquals("ni", engine.raw.toString())
    }

    @Test fun candidateSlideToTheLeftWithoutAMoveEventDoesNotCommit() {
        tap('n'); tap('i'); hold(20)
        val bar = kb.topBar
        val y = bar.height - dp(8f)
        val start = SystemClock.uptimeMillis()
        MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, dp(140f), y, 0).let {
            bar.dispatchTouchEvent(it); it.recycle()
        }
        hold(100)
        MotionEvent.obtain(start, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, dp(60f), y, 0).let {
            bar.dispatchTouchEvent(it); it.recycle()
        }
        assertEquals("a slide must leave the editor unchanged", "", ic.text)
        assertEquals("ni", engine.raw.toString())
    }

    @Test fun candidateSlideWhoseMoveStayedWithinSlopDoesNotCommitOnUp() {
        tap('n'); tap('i'); hold(20)
        val bar = kb.topBar
        val y = bar.height - dp(8f)
        val start = SystemClock.uptimeMillis()
        MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, dp(60f), y, 0).let {
            bar.dispatchTouchEvent(it); it.recycle()
        }
        hold(50)
        MotionEvent.obtain(start, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, dp(64f), y, 0).let {
            bar.dispatchTouchEvent(it); it.recycle()
        }
        hold(50)
        MotionEvent.obtain(start, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, dp(140f), y, 0).let {
            bar.dispatchTouchEvent(it); it.recycle()
        }
        assertEquals("", ic.text)
        assertEquals("ni", engine.raw.toString())
    }

    @Test fun longPressOnAnOrdinaryCandidateOpensActionsWithoutCommitting() {
        tap('n');tap('i');hold(20)
        val bar=kb.topBar
        val y=bar.height-dp(8f);val x=dp(60f)
        val start=SystemClock.uptimeMillis()
        bar.dispatchTouchEvent(MotionEvent.obtain(start,start,MotionEvent.ACTION_DOWN,x,y,0))
        hold(600)
        bar.dispatchTouchEvent(MotionEvent.obtain(start,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,x,y,0))
        assertEquals("",ic.text)
        assertEquals("ni",engine.raw.toString())
    }

    // ------------------------------------------------------------ space bar

    @Test fun spaceHoldSurvivesThumbDriftAndFinishesRecognizedTextOnRelease() {
        org.robolectric.Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val previousEngines = com.weavetext.ime.ui.VoiceAccess.enginesProvider
        val previousRecognizer = com.weavetext.ime.ui.VoiceAccess.recognizerProvider
        val previousEnsure = com.weavetext.ime.voice.VoiceAutoDownload.ensureOverride
        var preparations = 0
        val engines = com.weavetext.ime.testing.FakeEngines()
        val rec = com.weavetext.ime.testing.ScriptedRecognizer(engines)
        com.weavetext.ime.ui.VoiceAccess.enginesProvider = { engines }
        com.weavetext.ime.ui.VoiceAccess.recognizerProvider = { rec }
        com.weavetext.ime.voice.VoiceAutoDownload.ensureOverride = { preparations++; false }
        try {
            press(0, key(KeyCode.SPACE))
            move(0, dp(10f), dp(6f))
            hold(550)
            assertTrue("long press starts microphone despite thumb drift", rec.isRunning)
            assertEquals("空格按住绕过面板，也必须检查一次模型补装", 1, preparations)
            rec.listener!!.onReady(false)
            rec.listener!!.onLevel(0.5f)
            rec.listener!!.onPartial("hello下午见")
            idle()
            assertTrue("waveform receives microphone levels", kb.voiceSession.level > 0f)
            release(0)
            assertEquals(1, rec.stops)
            rec.listener!!.onFinal("hello下午见")
            rec.endAll()
            assertEquals("hello下午见", ic.text)
            assertEquals(com.weavetext.ime.ui.keyboard.VoiceSession.State.IDLE, kb.voiceSession.state)
        } finally {
            com.weavetext.ime.ui.VoiceAccess.enginesProvider = previousEngines
            com.weavetext.ime.ui.VoiceAccess.recognizerProvider = previousRecognizer
            com.weavetext.ime.voice.VoiceAutoDownload.ensureOverride = previousEnsure
        }
    }

    @Test fun shortSpaceSlideStillTypesASpace() {
        ic.commitText("a", 1)
        press(0, key(KeyCode.SPACE))
        move(0, dp(22f), 0f) // 超过 18dp 进入光标模式，但不到一格。 Past the 18dp slop, less than one step.
        release(0)
        assertEquals("a ", ic.text)
        assertEquals(0, ic.cursorMoves)
    }

    @Test fun outAndBackSpaceCursorGestureDoesNotInsertASpaceOnRelease() {
        ic.commitText("hello", 1)
        press(0, key(KeyCode.SPACE))
        move(0, -dp(42f), 0f)
        assertEquals(-2, ic.cursorMoves)
        assertEquals(3, android.text.Selection.getSelectionEnd(ic.editable))
        hold(100)
        move(0, dp(24f), 0f)
        assertEquals(0, ic.cursorMoves)
        assertEquals(5, android.text.Selection.getSelectionEnd(ic.editable))
        release(0)
        assertEquals("cursor motion must not turn into a space when its steps cancel", "hello", ic.text)
        press(0, key(KeyCode.SPACE)); release(0)
        assertEquals("the next tap must still insert a space", "hello ", ic.text)
    }

    @Test fun outAndBackSpaceCursorGestureDoesNotInsertASpaceWhenAnotherFingerLands() {
        ic.commitText("hello", 1)
        press(0, key(KeyCode.SPACE))
        move(0, -dp(42f), 0f)
        assertEquals(-2, ic.cursorMoves)
        hold(100)
        move(0, dp(24f), 0f)
        assertEquals(0, ic.cursorMoves)
        assertEquals(5, android.text.Selection.getSelectionEnd(ic.editable))
        press(1, key('b'))
        assertEquals("settling a completed cursor excursion must not insert a space", "hello", ic.text)
        assertEquals("b", engine.raw.toString())
        release(1); release(0)
        assertEquals("hello", ic.text)
        assertEquals("b", engine.raw.toString())
    }

    @Test fun aShortSpaceSlideStillTypesASpaceBeforeTheNextFinger() {
        ic.commitText("hello", 1)
        press(0, key(KeyCode.SPACE))
        move(0, dp(22f), 0f)
        assertEquals(0, ic.cursorMoves)
        press(1, key('b'))
        assertEquals("hello ", ic.text)
        assertEquals("b", engine.raw.toString())
        release(1); release(0)
        assertEquals("hello ", ic.text)
    }

    @Test fun spaceSlideWhileComposingCommitsTheCandidate() {
        tap('n'); tap('i')
        press(0, key(KeyCode.SPACE))
        move(0, dp(60f), 0f)
        release(0)
        assertEquals("【ni】", ic.text)
        assertEquals(0, ic.cursorMoves)
    }

    // ------------------------------------------------------------ long press

    @Test fun longPressThresholdIsNotTooShort() {
        press(0, key('q'))
        hold(320)
        assertFalse(kb.overlay!!.altShown)
        hold(KeyboardView.LONG_PRESS_MS - 320 + 10)
        assertTrue(kb.overlay!!.altShown)
        release(0)
    }

    @Test fun liftingInPlaceAfterLongPressKeepsTheLetter() {
        press(0, key('q'))
        hold(KeyboardView.LONG_PRESS_MS + 20)
        assertTrue(kb.overlay!!.altShown)
        release(0)
        assertEquals("q", engine.raw.toString())
        assertEquals("", ic.text)
    }

    @Test fun movingOntoAnAlternativeReplacesTheLetter() {
        val q = key('q')
        press(0, q)
        hold(KeyboardView.LONG_PRESS_MS + 20)
        // 浮层首格（"1"）在键的正上方。 The first cell ("1") sits right above the key.
        move(0, 0f, -(q.rect.height() / 2 + dp(6f) + maxOf(q.rect.height(), dp(44f)) / 2 + dp(4f)))
        release(0)
        assertEquals("", engine.raw.toString())
        assertEquals("1", ic.text)
    }

    @Test fun swipeUpReplacesTheLetter() {
        tap('n')
        val q = key('q')
        press(0, q)
        move(0, 0f, -q.rect.height() * 0.8f)
        release(0)
        // 组合中上滑：先上屏首选，再输出上滑字符（与抬手才输出时一致）。 Same result as commit-on-release.
        assertEquals("【n】1", ic.text)
        assertEquals("", engine.raw.toString())
    }

    @Test fun aSwipeWhoseLastPositionArrivesOnlyOnUpReplacesTheLetter() {
        tap('n')
        val q = key('q')
        press(0, q)
        release(0, dy = -q.rect.height() * 0.8f)
        assertEquals("【n】1", ic.text)
        assertEquals("", engine.raw.toString())
    }

    @Test fun pointerUpProcessesOnlyTheLiftingFingersFinalPosition() {
        press(8, key('n'))
        val q = key('q')
        press(3, q)
        // q is pointer index 1. n was already settled when q landed and must not emit again.
        release(3, dy = -q.rect.height() * 0.8f)
        release(8)
        assertEquals("【n】1", ic.text)
        assertEquals("", engine.raw.toString())
    }

    @Test fun aShortUpwardReleaseKeepsTheLetter() {
        val q = key('q')
        val min = maxOf(dp(KeyboardView.SWIPE_MIN_DP), kb.metrics.keyHeight * KeyboardView.SWIPE_MIN_KEY)
        press(0, q)
        release(0, dy = -(min - dp(2f)))
        assertEquals("q", engine.raw.toString())
        assertEquals("", ic.text)
    }

    @Test fun aSpaceSlideEndingWithoutAMoveEventMovesTheCursor() {
        ic.commitText("hello", 1)
        press(0, key(KeyCode.SPACE))
        release(0, dx = -dp(60f))
        assertTrue("the final release position must move the cursor", ic.cursorMoves < 0)
        assertEquals("hello", ic.text)
    }

    @Test fun aLongPressLiftOnAnAlternativeUsesTheFinalReleasePosition() {
        val q = key('q')
        press(0, q)
        hold(KeyboardView.LONG_PRESS_MS + 20)
        release(0, dy = -(q.rect.height() / 2 + dp(6f) + maxOf(q.rect.height(), dp(44f)) / 2 + dp(4f)))
        assertEquals("1", ic.text)
        assertEquals("", engine.raw.toString())
    }

    @Test fun shortOrSlantedUpwardDragsKeepTheLetter() {
        val q = key('q')
        val min = maxOf(dp(KeyboardView.SWIPE_MIN_DP), kb.metrics.keyHeight * KeyboardView.SWIPE_MIN_KEY)
        // 不够长。 Too short.
        press(0, q); move(0, 0f, -(min - dp(2f))); release(0)
        // 够长但斜着（|dy| ≤ 1.5 |dx|）。 Long enough but slanted.
        press(0, key('w')); move(0, min * 0.8f, -min * 1.1f); release(0)
        assertEquals("qw", engine.raw.toString())
        assertEquals("", ic.text)
        // 明显朝上且够长：上滑字符。 Clearly vertical and long: the swipe-up char.
        press(0, key('e')); move(0, min * 0.3f, -(min + dp(2f))); release(0)
        assertEquals("【qw】3", ic.text)
    }

    @Test fun englishLettersShowOnTheNextFrame() {
        controller.toggleChinese()
        hold(20)
        for (c in "hel") tap(c)
        hold(20)
        // 组合串不写入编辑器，但每个字母当帧出现在候选栏首位。 Not in the editor, but in the bar within a frame.
        assertEquals("", ic.text)
        assertEquals("hel", kb.state.preedit)
        assertEquals("hel", kb.topBar.candidateAt(0))
        tap('p')
        hold(20)
        assertEquals("help", kb.topBar.candidateAt(0))
    }

    @Test fun nineKeyLongPressOnWideDigitKeysStaysOnScreen() {
        // 竖屏九键的 7（PQRS）、9（WXYZ）各有五个候选，按键又宽，一行按原宽放不下。
        // Portrait 9-key 7 (PQRS) and 9 (WXYZ) have five items each on wide keys: a row at key width doesn't fit.
        WeavePrefs.of(app).edit().putString(WeavePrefs.KEYBOARDS, "t9,english").putString(WeavePrefs.ACTIVE_KEYBOARD, "t9").commit()
        idle()
        val ov = kb.overlay!!
        for (c in "79") {
            press(0, key(c))
            hold(KeyboardView.LONG_PRESS_MS + 20)
            assertTrue("popup for $c", ov.altShown)
            val box = ov.altBounds
            assertTrue("box $box within ${ov.width}", box.left >= 0f && box.right <= ov.width)
            release(0)
        }
    }

    @Test fun popupClampNeverThrows() {
        // 放得下：夹在边距内；放不下（范围倒置）：居中。 Fits: clamped to the margins; too wide (inverted range): centred.
        assertEquals(4f, clampLeft(-10f, 50f, 100f, 4f), 0f)
        assertEquals(46f, clampLeft(90f, 50f, 100f, 4f), 0f)
        assertEquals(-5f, clampLeft(30f, 110f, 100f, 4f), 0f)
        assertEquals(-25f, clampLeft(0f, 50f, 0f, 4f), 0f)
    }

    // ------------------------------------------------------------ 14-key

    @Test fun fourteenKeySendsGroupCodesAndSeparator() {
        WeavePrefs.of(app).edit().putString(WeavePrefs.KEYBOARDS, "t14,english").putString(WeavePrefs.ACTIVE_KEYBOARD, "t14").commit()
        idle()
        assertEquals("t14", engine.schema)
        // n→bn(M) i→ui(D) h→gh(H) a→as(F) o→op(E)
        for (c in "MDHFE") { press(9, key(c)); release(9) }
        idle()
        press(9, key(KeyCode.T9_ONE)); release(9)
        assertEquals("MDHFE'", engine.raw.toString())
        assertEquals(14, kv.keys.count { it.code in 'A'.code..'N'.code })
        assertEquals("q，w", kv.describe(key('A')))
    }

    // ------------------------------------------------------------ rendering

    @Test fun charKeysEmitOnDown() {
        press(0, key('k'))
        assertEquals("k", engine.raw.toString())
        release(0)
        assertEquals("k", engine.raw.toString())
    }

    @Test fun burstOfKeysRendersOncePerFrame() {
        val before = kb.renderCount
        val word = "zhongwenshurufa"
        for (c in word) tap(c)
        assertEquals(word, engine.raw.toString())
        assertEquals("no render inside touch dispatch", before, kb.renderCount)
        hold(20)
        assertEquals("one render for the whole burst", before + 1, kb.renderCount)
        assertEquals(word, kb.state.preedit)
        // 60 个候选里只测量可见范围（再多一屏）。 Only the visible range (plus a screen) of the 60 is measured.
        assertTrue("measured ${kb.topBar.measuredCount}", kb.topBar.measuredCount in 1 until 60)
    }

    @Test fun editorActionChangesPreservePagedCandidatesUntilTheNextInput() {
        tap('n')
        kb.flushRender()
        val firstPage = kb.topBar.loadedCount
        kb.onNeedMore()
        val loaded = kb.topBar.loadedCount
        assertTrue("a second page was loaded", loaded > firstPage)
        val lastCandidate = kb.topBar.candidateAt(loaded - 1)
        val measured = kb.topBar.measuredCount

        controller.previewState(controller.state.copy(enterAction = com.weavetext.ime.ime.EnterAction.SEND))
        kb.flushRender()
        assertEquals("an editor action must not discard the second page", loaded, kb.topBar.loadedCount)
        assertEquals(lastCandidate, kb.topBar.candidateAt(loaded - 1))
        assertEquals(measured, kb.topBar.measuredCount)

        tap('i')
        kb.flushRender()
        assertEquals("【ni】", kb.topBar.candidateAt(0))
        assertEquals("new input replaces the old pages", firstPage, kb.topBar.loadedCount)
    }

    @Test fun candidateMetadataUpdatesStillReachTheBar() {
        tap('n')
        kb.flushRender()
        val s = controller.state
        val changed = s.candidates.mapIndexed { i, c -> if (i == 0) c.copy(pinyin = "n", isCloud = true) else c }
        controller.previewState(s.copy(candidates = changed))
        kb.flushRender()
        assertEquals("n", kb.topBar.candidatePinyinAt(0))
        assertEquals("(n)", kb.topBar.candidateHintAt(0))
    }

    @Test fun engineCandidatesReplaceLocalCandidatesEvenWhenTheEnginePayloadIsUnchanged() {
        tap('n')
        kb.flushRender()
        controller.onCalc!!(listOf("640"))
        assertEquals("640", kb.topBar.candidateAt(0))
        controller.previewState(controller.state.copy(enterAction = com.weavetext.ime.ime.EnterAction.SEND))
        kb.flushRender()
        assertEquals("【n】", kb.topBar.candidateAt(0))
    }

    @Test fun keyLatencyGuard() {
        // 200 次按键 + 退格：每键只有一次内核输入，快照次数与按键同阶，且每帧最多渲染一次。
        // 200 keys + backspaces: one engine input per key, snapshots on the order of keys, at most one render per frame.
        val before = kb.renderCount
        val snapsBefore = engine.snapshots
        var frames = 0
        repeat(40) {
            for (c in "shuru") tap(c)
            repeat(5) { press(9, key(KeyCode.DELETE)); release(9) }
            hold(17)
            frames++
        }
        assertEquals(200, engine.inputs)
        assertTrue("renders ${kb.renderCount - before} > frames $frames", kb.renderCount - before <= frames)
        assertTrue("snapshots ${engine.snapshots - snapsBefore}", engine.snapshots - snapsBefore <= 400)
    }
}
