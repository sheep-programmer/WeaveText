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
import com.weavetext.ime.ui.keyboard.HandPad
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

/**
 * 手写键盘：按真实的 MotionEvent 序列在书写区回放笔画，检查送给内核的点列、停笔自动上屏、退笔与重写、多指。
 * 时间由 Robolectric 的主线程时钟控制（[hold] 推进）。
 * Handwriting keyboard: replay MotionEvent strokes on the pad and check the point arrays sent to the engine,
 * auto-commit after a pause, undo / clear and multi-touch. Time is Robolectric's main-looper clock ([hold]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-420dpi")
class HandwritingTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var activity: Activity
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
            .putString(WeavePrefs.KEYBOARDS, "hand,pinyin,english").putString(WeavePrefs.ACTIVE_KEYBOARD, "hand").commit()
        // 上一个测试的剪贴板写入可能还在后台排队。 A previous test's clip writes may still be queued.
        com.weavetext.ime.ime.ClipHistory.awaitIo()
        File(app.filesDir, "clipboard").deleteRecursively()
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        build()
    }

    private fun build() {
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
    private val pad: HandPad get() = kv.hand!!

    // ------------------------------------------------------------ event replay

    private val down = LinkedHashMap<Int, Pair<Float, Float>>()
    private var downTime = 0L

    private fun send(action: Int, index: Int) {
        val ids = down.keys.toList()
        val props = Array(ids.size) { i -> MotionEvent.PointerProperties().apply { id = ids[i]; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(ids.size) { i -> MotionEvent.PointerCoords().apply { x = down[ids[i]]!!.first; y = down[ids[i]]!!.second; pressure = 1f; size = 1f } }
        val a = if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_CANCEL) action else action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), a, ids.size, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        kv.dispatchTouchEvent(e)
        e.recycle()
    }

    private fun pressAt(id: Int, x: Float, y: Float) {
        if (down.isEmpty()) downTime = SystemClock.uptimeMillis()
        down[id] = x to y
        send(if (down.size == 1) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN, down.keys.indexOf(id))
    }

    private fun moveTo(id: Int, x: Float, y: Float) {
        down[id] = x to y
        send(MotionEvent.ACTION_MOVE, 0)
    }

    private fun release(id: Int) {
        send(if (down.size == 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP, down.keys.indexOf(id))
        down.remove(id)
    }

    private fun key(code: Int): Key = kv.keyOf(code)!!
    private fun tap(code: Int) { val k = key(code); pressAt(9, k.rect.centerX(), k.rect.centerY()); release(9) }

    /** 在书写区画一笔，点为书写区内的比例坐标；返回期望送给内核的点列（书写区坐标）。 Draw one stroke from pad fractions. */
    private fun stroke(vararg f: Float, id: Int = 0, lift: Boolean = true): FloatArray {
        val r = pad.rect
        val pts = FloatArray(f.size)
        for (i in f.indices step 2) {
            pts[i] = f[i] * r.width()
            pts[i + 1] = f[i + 1] * r.height()
        }
        pressAt(id, r.left + pts[0], r.top + pts[1])
        for (i in 2 until pts.size step 2) moveTo(id, r.left + pts[i], r.top + pts[i + 1])
        if (lift) release(id)
        return pts
    }

    private fun assertStroke(expected: FloatArray, actual: FloatArray) = assertArrayEquals(expected, actual, 0.01f)

    // ------------------------------------------------------------ tests

    @Test fun handSchemeShowsPadAndKeys() {
        assertEquals("hand", engine.schema)
        assertNotNull(kv.hand)
        for (c in intArrayOf(KeyCode.DELETE, KeyCode.HAND_CLEAR, KeyCode.HAND_MODE, KeyCode.ENTER, KeyCode.SYMBOL, KeyCode.NUMBER, ','.code, KeyCode.SPACE, '.'.code, KeyCode.LANG)) {
            assertNotNull("key $c", kv.keyOf(c))
        }
        val r = pad.rect
        assertTrue(r.width() > kv.width * 0.6f && r.height() > kv.height * 0.6f)
        for (k in kv.keys) assertFalse("${k.label} overlaps the pad", android.graphics.RectF.intersects(k.rect, r))
        // The right column now includes the mode key; Enter keeps a dedicated cell below it.
        assertTrue(key(KeyCode.ENTER).rect.top > key(KeyCode.HAND_MODE).rect.bottom)
        val bottom = kv.keys.filter { it.rect.top >= r.bottom && it.code != KeyCode.ENTER }.sortedBy { it.rect.left }.map { it.code }
        assertEquals(listOf(KeyCode.SYMBOL, KeyCode.NUMBER, ','.code, KeyCode.SPACE, '.'.code, KeyCode.LANG), bottom)
    }

    @Test fun manualCommitKeepsInkAcrossLongPausesAndModeSwitches() {
        WeavePrefs.of(app).edit().putBoolean(WeavePrefs.HAND_AUTO_COMMIT, false).commit()
        idle()
        stroke(0.1f, 0.5f, 0.4f, 0.5f)
        hold(10000)
        assertEquals("", ic.text)
        assertEquals(1, pad.strokes.size)
        tap(KeyCode.HAND_MODE)
        idle()
        assertTrue(WeavePrefs.of(app).getBoolean(WeavePrefs.HAND_LINE, false))
        assertEquals("连写", key(KeyCode.HAND_MODE).label)
        assertEquals(1, pad.strokes.size)
        stroke(0.6f, 0.5f, 0.9f, 0.5f)
        hold(10000)
        assertEquals("1笔0", ic.text)
        assertEquals(1, pad.strokes.size)
        tap(KeyCode.SPACE)
        assertEquals("1笔01笔0", ic.text)
        assertTrue(pad.strokes.isEmpty())
    }

    @Test fun switchingOffAutoCommitCancelsAnAlreadyScheduledPause() {
        stroke(0.1f, 0.5f, 0.9f, 0.5f)
        hold(200)
        WeavePrefs.of(app).edit().putBoolean(WeavePrefs.HAND_AUTO_COMMIT, false).commit()
        hold(3000)
        assertEquals("", ic.text)
        assertEquals(1, pad.strokes.size)
        assertTrue(engine.isComposing())
    }

    @Test fun longPressModeRedoesAnUndoneStrokeWithoutChangingMode() {
        WeavePrefs.of(app).edit().putBoolean(WeavePrefs.HAND_AUTO_COMMIT, false).commit()
        stroke(0.1f, 0.5f, 0.9f, 0.5f)
        val second = stroke(0.5f, 0.1f, 0.5f, 0.9f)
        tap(KeyCode.DELETE)
        assertEquals(1, pad.strokes.size)
        assertTrue(pad.canRedo)
        val mode = key(KeyCode.HAND_MODE)
        pressAt(9, mode.rect.centerX(), mode.rect.centerY())
        hold(700)
        release(9)
        assertEquals(2, pad.strokes.size)
        assertStroke(second, pad.strokes.last())
        assertEquals(2, engine.hand.size)
        assertFalse(WeavePrefs.of(app).getBoolean(WeavePrefs.HAND_LINE, false))
    }

    @Test fun noRecognitionResultKeepsTheInkForCorrection() {
        val failing = object : com.weavetext.ime.core.KeyEngine by engine {
            override fun snapshot() = engine.snapshot().copy(candidates = emptyList(), totalCandidates = 0)
        }
        controller.attachEngine(failing)
        controller.setPreferredSchema("hand")
        stroke(0.1f, 0.5f, 0.9f, 0.5f)
        hold(3000)
        assertEquals("", ic.text)
        assertEquals(1, pad.strokes.size)
        assertTrue(kv.handRecognitionFailed)
        tap(KeyCode.HAND_CLEAR)
        assertTrue(pad.strokes.isEmpty())
    }

    @Test fun handInputOncePerStrokeWithPadPoints() {
        val r = pad.rect
        pressAt(0, r.left + 20f, r.top + 30f)
        moveTo(0, r.left + 60f, r.top + 32f)
        moveTo(0, r.left + 120f, r.top + 35f)
        assertEquals("moves never reach the engine", 0, engine.handCalls.size)
        release(0)
        assertEquals(1, engine.handCalls.size)
        assertEquals(1, engine.handCalls[0].size)
        assertStroke(floatArrayOf(20f, 30f, 60f, 32f, 120f, 35f), engine.handCalls[0][0])

        val s2 = stroke(0.5f, 0.1f, 0.5f, 0.5f, 0.5f, 0.9f)
        assertEquals(2, engine.handCalls.size)
        val second = engine.handCalls[1]
        assertEquals("all strokes of the char each time", 2, second.size)
        assertStroke(floatArrayOf(20f, 30f, 60f, 32f, 120f, 35f), second[0])
        assertStroke(s2, second[1])
        assertEquals(2, pad.strokes.size)
        hold(20)
        assertEquals("2笔0", kb.topBar.candidateAt(0))
        assertEquals("", ic.text)
    }

    @Test fun tinyMovesAreDroppedAndTapIsADot() {
        val r = pad.rect
        pressAt(0, r.left + 50f, r.top + 50f)
        moveTo(0, r.left + 50.5f, r.top + 50.5f)
        release(0)
        assertStroke(floatArrayOf(50f, 50f), engine.handCalls.single()[0])
    }

    @Test fun nextStrokeAfterPauseCommitsTopCandidate() {
        stroke(0.1f, 0.5f, 0.9f, 0.5f)
        hold(HandPad.COMMIT_PAUSE_MS - 200)
        val s2 = stroke(0.5f, 0.1f, 0.5f, 0.9f)
        assertEquals("a quick next stroke belongs to the same char", "", ic.text)
        assertEquals(2, engine.handCalls.last().size)
        hold(HandPad.COMMIT_PAUSE_MS + 50)
        val s3 = stroke(0.2f, 0.2f, 0.8f, 0.8f)
        assertEquals("2笔0", ic.text)
        val last = engine.handCalls.last()
        assertEquals("the new char starts from its own first stroke", 1, last.size)
        assertStroke(s3, last[0])
        assertEquals(1, pad.strokes.size)
        assertFalse(s2.contentEquals(last[0]))
    }

    @Test fun idlePenCommitsWithoutAnotherStroke() {
        stroke(0.1f, 0.5f, 0.9f, 0.5f)
        hold(HandPad.COMMIT_PAUSE_MS - 200)
        assertEquals("still inside the pause", "", ic.text)
        stroke(0.5f, 0.1f, 0.5f, 0.9f)
        hold(HandPad.COMMIT_PAUSE_MS - 200)
        assertEquals("the second stroke restarted the wait", "", ic.text)
        hold(400)
        assertEquals("2笔0", ic.text)
        assertEquals("ink is cleared after the auto commit", 0, pad.strokes.size)
    }

    @Test fun ink_in_progress_is_not_committed_by_the_idle_timer() {
        stroke(0.1f, 0.5f, 0.9f, 0.5f)
        hold(HandPad.COMMIT_PAUSE_MS - 100)
        stroke(0.5f, 0.1f, 0.5f, 0.5f, lift = false)
        hold(HandPad.COMMIT_PAUSE_MS + 500)
        assertEquals("", ic.text)
        release(0)
    }

    @Test fun continuousLineConfirmsThePreviousCharacterBeforeTheNextOne() {
        WeavePrefs.of(app).edit()
            .putBoolean(WeavePrefs.HAND_LINE, true)
            .putBoolean(WeavePrefs.HAND_AUTO_COMMIT, false)
            .commit()
        idle()
        stroke(0.1f, 0.5f, 0.4f, 0.5f)
        hold(HandPad.COMMIT_PAUSE_MS + 50)
        assertEquals("manual continuous mode keeps the first char until the next stroke", "", ic.text)
        stroke(0.6f, 0.5f, 0.9f, 0.5f)
        assertEquals("the next char starts only after the first one is finished", "1笔0", ic.text)
        assertEquals("the new char owns the live canvas", 1, pad.strokes.size)
        assertTrue("the old char is removed from the live ink for the fade layer", pad.fadeStart >= 0L || !android.animation.ValueAnimator.areAnimatorsEnabled())
    }

    @Test fun commitKeysClearInk() {
        stroke(0.1f, 0.5f, 0.9f, 0.5f)
        tap(KeyCode.SPACE)
        assertEquals("1笔0", ic.text)
        assertTrue(pad.strokes.isEmpty())
        assertFalse(engine.isComposing())

        stroke(0.1f, 0.5f, 0.9f, 0.5f)
        tap(','.code)
        // 紧跟数字的逗号保持半角，见 InputPathTest.punctuationAfterDigitsStaysAscii。
        // A comma right after a digit stays ASCII; see InputPathTest.punctuationAfterDigitsStaysAscii.
        assertEquals("1笔01笔0,", ic.text)
        assertTrue(pad.strokes.isEmpty())

        stroke(0.1f, 0.5f, 0.9f, 0.5f)
        stroke(0.5f, 0.1f, 0.5f, 0.9f)
        tap(KeyCode.ENTER)
        assertEquals("enter commits the top candidate, no newline", "1笔01笔0,2笔0", ic.text)
        assertTrue(pad.strokes.isEmpty())

        stroke(0.1f, 0.5f, 0.9f, 0.5f)
        hold(20)
        kb.onCandidate(3)
        assertEquals("1笔01笔0,2笔01笔3", ic.text)
        assertTrue(pad.strokes.isEmpty())
    }

    @Test fun switchingTo123CommitsTheHalfWrittenChar() {
        stroke(0.1f, 0.5f, 0.9f, 0.5f)
        stroke(0.5f, 0.1f, 0.5f, 0.9f)
        tap(KeyCode.NUMBER)
        // 回来时书写区是空的，候选也不能还是旧的：这个字先上屏。 The pad comes back empty, so the char is committed first.
        assertEquals("2笔0", ic.text)
        assertFalse(engine.isComposing())
    }

    @Test fun inkStaysOnThePadAfterTheLiftAndSpansTheStroke() {
        stroke(0.1f, 0.5f, 0.3f, 0.5f, 0.6f, 0.5f, 0.9f, 0.5f)
        val b = android.graphics.RectF()
        pad.ink.computeBounds(b, true)
        val r = pad.rect
        // 墨迹是整条填充轮廓，不是只剩落笔的一个点。 The ink is the whole filled outline, not just the pen-down dot.
        assertTrue("ink spans the stroke: $b", b.width() > 0.7f * r.width())
        assertTrue("ink has thickness: $b", b.height() > 0f)
    }

    @Test fun deleteUndoesStrokesThenDeletesText() {
        controller.onText("ab")
        stroke(0.1f, 0.5f, 0.9f, 0.5f)
        stroke(0.5f, 0.1f, 0.5f, 0.9f)
        tap(KeyCode.DELETE)
        assertEquals(1, pad.strokes.size)
        assertEquals(1, engine.hand.size)
        hold(20)
        assertEquals("1笔0", kb.topBar.candidateAt(0))
        tap(KeyCode.DELETE)
        assertTrue(pad.strokes.isEmpty())
        assertFalse(engine.isComposing())
        assertEquals("ab", ic.text)
        tap(KeyCode.DELETE)
        assertEquals("no strokes left: a normal backspace", "a", ic.text)
    }

    @Test fun rewriteAndLongPressDeleteClearStrokes() {
        controller.onText("ab")
        stroke(0.1f, 0.5f, 0.9f, 0.5f)
        stroke(0.5f, 0.1f, 0.5f, 0.9f)
        idle()
        assertFalse(key(KeyCode.HAND_CLEAR).disabled)
        tap(KeyCode.HAND_CLEAR)
        assertTrue(pad.strokes.isEmpty())
        assertFalse(engine.isComposing())
        assertEquals("ab", ic.text)
        hold(20)
        assertTrue("rewrite is disabled with nothing written", key(KeyCode.HAND_CLEAR).disabled)

        stroke(0.1f, 0.5f, 0.9f, 0.5f)
        stroke(0.5f, 0.1f, 0.5f, 0.9f)
        val d = key(KeyCode.DELETE)
        pressAt(9, d.rect.centerX(), d.rect.centerY())
        hold(1500)
        release(9)
        assertTrue(pad.strokes.isEmpty())
        assertFalse(engine.isComposing())
        assertEquals("holding delete clears the strokes and deletes nothing else", "ab", ic.text)
    }

    @Test fun secondFingerIgnoredWhileWriting() {
        val r = pad.rect
        pressAt(0, r.left + 20f, r.top + 20f)
        moveTo(0, r.left + 80f, r.top + 20f)
        val sp = key(KeyCode.SPACE)
        pressAt(1, sp.rect.centerX(), sp.rect.centerY())
        release(1)
        pressAt(2, r.left + 200f, r.top + 100f)
        moveTo(2, r.left + 240f, r.top + 140f)
        release(2)
        assertEquals(0, engine.handCalls.size)
        moveTo(0, r.left + 140f, r.top + 20f)
        release(0)
        assertEquals(1, engine.handCalls.size)
        assertStroke(floatArrayOf(20f, 20f, 80f, 20f, 140f, 20f), engine.handCalls[0][0])
        assertEquals("", ic.text)
    }

    @Test fun cancelledStrokeIsDropped() {
        stroke(0.1f, 0.5f, 0.9f, 0.5f)
        stroke(0.5f, 0.1f, 0.5f, 0.9f, lift = false)
        send(MotionEvent.ACTION_CANCEL, 0)
        down.clear()
        assertEquals(1, engine.handCalls.size)
        assertEquals(1, pad.strokes.size)
    }

    @Test fun langKeySwitchesToEnglishAndBack() {
        stroke(0.1f, 0.5f, 0.9f, 0.5f)
        tap(KeyCode.LANG)
        idle()
        assertEquals("english", engine.schema)
        assertEquals(null, kv.hand)
        tap(KeyCode.LANG)
        idle()
        assertEquals("hand", engine.schema)
        assertNotNull(kv.hand)
        assertTrue(pad.strokes.isEmpty())
    }

    @Test fun floatingKeyboardKeepsThePadUsable() {
        WeavePrefs.of(app).edit().putBoolean(WeavePrefs.FLOATING, true).commit()
        idle()
        assertTrue(kb.floating)
        val r = pad.rect
        assertTrue(r.width() > 0f && r.right <= kv.width)
        val s = stroke(0.2f, 0.3f, 0.8f, 0.3f)
        assertStroke(s, engine.handCalls.single()[0])
    }
}
