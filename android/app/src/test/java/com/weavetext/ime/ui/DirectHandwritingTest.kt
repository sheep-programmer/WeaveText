package com.weavetext.ime.ui

import android.app.Activity
import android.app.Application
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.ime.InputController
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.style.ToolIds
import com.weavetext.ime.testing.FakeEngine
import com.weavetext.ime.testing.FakeInputConnection
import com.weavetext.ime.ui.keyboard.ImeWindowHost
import com.weavetext.ime.ui.keyboard.Key
import com.weavetext.ime.ui.keyboard.KeyCode
import com.weavetext.ime.ui.keyboard.KeyboardView
import com.weavetext.ime.ui.keyboard.WeaveKeyboard
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

/** Direct handwriting starts from a slide, while an ordinary key tap remains typing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-420dpi")
class DirectHandwritingTest {
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
        WeavePrefs.of(app).edit().clear()
            .putString(WeavePrefs.KEYBOARDS, "pinyin,t9,hand,english")
            .putString(WeavePrefs.ACTIVE_KEYBOARD, "pinyin").commit()
        com.weavetext.ime.ime.ClipHistory.awaitIo()
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
    private fun hold(ms: Long) = ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS)
    private fun dp(value: Float) = value * app.resources.displayMetrics.density
    private fun key(code: Int): Key = kv.keyOf(code)!!

    private fun send(action: Int, x: Float, y: Float, downTime: Long) {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { kv.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    private fun slide(k: Key, vararg offsets: Float) {
        val startX = k.rect.centerX()
        val startY = k.rect.centerY()
        val downTime = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, startX, startY, downTime)
        for (i in offsets.indices step 2) send(MotionEvent.ACTION_MOVE, startX + offsets[i], startY + offsets[i + 1], downTime)
        val last = offsets.size - 2
        send(MotionEvent.ACTION_UP, startX + offsets[last], startY + offsets[last + 1], downTime)
    }

    private fun tapAt(x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, x, y, downTime)
        send(MotionEvent.ACTION_UP, x, y, downTime)
    }

    @Test fun qwertySlideBecomesInkAndAutoCommitReturnsToPinyin() {
        val q = key('q'.code)
        val x = q.rect.centerX()
        val y = q.rect.centerY()
        slide(q, dp(24f), 0f, dp(48f), dp(12f))

        assertEquals("the key-down letter must be rolled back", "", engine.raw.toString())
        assertEquals("", ic.text)
        assertEquals("hand", engine.schema)
        assertNotNull("ink is available over the unchanged QWERTY keys", kv.hand)
        assertNotNull(kv.keyOf('q'.code))
        assertEquals(1, engine.handCalls.size)
        assertArrayEquals(floatArrayOf(x, y, x + dp(24f), y, x + dp(48f), y + dp(12f)),
            engine.handCalls.single().single(), 0.01f)

        hold(900)
        assertEquals("1笔0", ic.text)
        assertEquals("pinyin", engine.schema)
        assertNull("a committed overlay returns to the ordinary keys", kv.hand)
        slide(key('n'.code), 0f, 0f)
        assertEquals("n", engine.raw.toString())
    }

    @Test fun t9SlideUsesTheSameOverlayAndReturnsToT9() {
        kb.chooseKeyboard("t9")
        idle()
        slide(key('2'.code), dp(36f), dp(8f))

        assertEquals("hand", engine.schema)
        assertEquals("", ic.text)
        assertEquals(1, engine.handCalls.size)
        hold(900)
        assertEquals("1笔0", ic.text)
        assertEquals("t9", engine.schema)
        assertNull(kv.hand)
        assertNotNull(kv.side)
    }

    @Test fun strictUpSwipeStillSelectsTheKeyAlternative() {
        slide(key('q'.code), 0f, -dp(55f))

        assertEquals("1", ic.text)
        assertEquals("pinyin", engine.schema)
        assertTrue(engine.handCalls.isEmpty())
        assertNull(kv.hand)
    }

    @Test fun typingAKeyCommitsTheInkAndReturnsToTheKeys() {
        autoCommitOff()
        writeAndSettle()

        slide(key('n'.code), 0f, 0f)

        assertEquals("ink goes out before the letter", "1笔0", ic.text)
        assertEquals("pinyin", engine.schema)
        assertNull(kv.hand)
        assertEquals("n", engine.raw.toString())
    }

    @Test fun spaceCommitsTheInkBeforeInsertingTheSpace() {
        autoCommitOff()
        writeAndSettle()

        slide(key(KeyCode.SPACE), 0f, 0f)

        assertEquals("1笔0 ", ic.text)
        assertEquals("pinyin", engine.schema)
        assertNull(kv.hand)
    }

    @Test fun switchingLanguageCommitsTheInkAndLeavesTheOverlay() {
        autoCommitOff()
        writeAndSettle()

        slide(key(KeyCode.LANG), 0f, 0f)

        assertEquals("1笔0", ic.text)
        assertEquals("english", engine.schema)
        assertFalse(controller.state.chinese)
        assertNull(kv.hand)
    }

    @Test fun tappingTheT9SideListWhileWritingInsertsItsSymbol() {
        autoCommitOff()
        kb.chooseKeyboard("t9")
        idle()
        writeAndSettle(key('2'.code))
        val side = requireNotNull(kv.side)
        val item = side.items.first()
        val x = side.cell.centerX()
        val y = side.cell.top + side.itemHeight / 2

        tapAt(x, y)

        assertEquals("1笔0$item", ic.text)
        assertEquals("t9", engine.schema)
        assertNull(kv.hand)
    }

    @Test fun openingTheEmojiToolbarCommitsTheInkAndLeavesTheOverlay() {
        autoCommitOff()
        writeAndSettle()

        kb.onToolbar(ToolIds.EMOJI)
        idle()

        assertEquals("1笔0", ic.text)
        assertEquals("pinyin", engine.schema)
        assertNull(kv.hand)
    }

    @Test fun anUpSwipeOnAKeyDuringTheOverlayCommitsTheInkThenTypesTheAlternative() {
        autoCommitOff()
        writeAndSettle()

        slide(key('q'.code), 0f, -dp(55f))

        assertEquals("1笔01", ic.text)
        assertEquals("pinyin", engine.schema)
        assertNull(kv.hand)
    }

    @Test fun pickingFromTheExpandedGridAlsoLeavesTheOverlay() {
        autoCommitOff()
        writeAndSettle()

        kb.onExpand()
        idle()
        val grid = requireNotNull(find(kb.view, "CandidateGridPanel\$Grid")).accessibilityNodeProvider!!
        assertTrue(grid.performAction(0, AccessibilityNodeInfo.ACTION_CLICK, null))
        idle()

        assertEquals("1笔0", ic.text)
        assertEquals("pinyin", engine.schema)
        assertNull(kv.hand)
    }

    private fun find(view: android.view.View, suffix: String): android.view.View? {
        if (view.javaClass.name.endsWith(suffix)) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), suffix)?.let { return it }
        return null
    }

    @Test fun movingTheCursorElsewhereEndsTheOverlayWithoutInsertingTheInk() {
        autoCommitOff()
        writeAndSettle()

        // 用户点了文字里的别处：编辑器回报的选区不是我们改的。
        // The user tapped elsewhere in the text: the editor reports a selection we didn't set.
        controller.onSelectionUpdate(0, 0, -1, -1)
        controller.onSelectionUpdate(4, 4, -1, -1)
        kb.onSelectionChanged(4, 4)

        assertEquals("the ink must not land at the moved cursor", "", ic.text)
        assertEquals("pinyin", engine.schema)
        assertNull(kv.hand)
    }

    private fun autoCommitOff() {
        WeavePrefs.of(app).edit().putBoolean(WeavePrefs.HAND_AUTO_COMMIT, false).commit()
        kb.onSharedPreferenceChanged(WeavePrefs.of(app), WeavePrefs.HAND_AUTO_COMMIT)
        idle()
    }

    /** 写一笔并等到识别结果，自动上屏关着时覆盖层仍留着。 Draw and wait for recognition; auto-commit off keeps the overlay. */
    private fun writeAndSettle(k: Key = key('q'.code)) {
        slide(k, dp(36f), dp(8f))
        hold(900)
        assertEquals("hand", engine.schema)
        assertNotNull(kv.hand)
    }

    @Test fun candidateSelectionEndsOverlayWhenAutoCommitIsOff() {
        autoCommitOff()
        slide(key('q'.code), dp(36f), dp(8f))
        hold(900)

        assertEquals("", ic.text)
        assertEquals("hand", engine.schema)
        kb.flushRender()
        kb.onCandidate(0)

        assertEquals("1笔0", ic.text)
        assertEquals("pinyin", engine.schema)
        assertNull(kv.hand)
    }

    @Test fun choosingAnotherKeyboardEndsAnUnfinishedOverlay() {
        slide(key('q'.code), dp(36f), dp(8f))
        assertEquals("hand", engine.schema)

        kb.chooseKeyboard("t9")
        idle()

        assertEquals("t9", engine.schema)
        assertNull(kv.hand)
        assertNotNull(kv.side)
    }

    @Test fun showingASensitiveFieldDropsAnUnfinishedOverlay() {
        slide(key('q'.code), dp(36f), dp(8f))
        assertEquals("hand", engine.schema)

        controller.onFinishInput()
        controller.onStartInput(EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }, false)
        kb.onShown()
        idle()

        assertEquals("english", engine.schema)
        assertNull(kv.hand)
        slide(key('q'.code), dp(36f), dp(8f))
        assertTrue(engine.handCalls.size == 1)
        assertEquals("english", engine.schema)
    }
}
