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

    private val host = object : ImeWindowHost {
        override fun hideKeyboard() {}
        override val window: android.view.Window? get() = null
    }

    @Before fun setUp() {
        android.provider.Settings.Global.putFloat(app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        WeavePrefs.of(app).edit().clear().commit()
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

    private fun release(id: Int) {
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

    @Test fun cancelKeepsAnEmittedChar() {
        press(0, key('z'))
        cancelAll()
        assertEquals("z", engine.raw.toString())
    }

    // ------------------------------------------------------------ space bar

    @Test fun shortSpaceSlideStillTypesASpace() {
        ic.commitText("a", 1)
        press(0, key(KeyCode.SPACE))
        move(0, dp(10f), 0f) // 超过 8dp 进入光标模式，但不到一格。 Past slop, less than one step.
        release(0)
        assertEquals("a ", ic.text)
        assertEquals(0, ic.cursorMoves)
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
