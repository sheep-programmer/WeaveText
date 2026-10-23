package com.weavetext.ime.ui

import android.app.Activity
import android.app.Application
import android.graphics.PointF
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
import com.weavetext.ime.testing.FakeInputConnection
import com.weavetext.ime.ui.keyboard.FloatingResizeCorner
import com.weavetext.ime.ui.keyboard.ImeWindowHost
import com.weavetext.ime.ui.keyboard.WeaveKeyboard
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** Replay root touch dispatch so a control gesture cannot silently become a key gesture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-420dpi")
class FloatingChromeTest {
    private lateinit var keyboard: WeaveKeyboard
    private lateinit var controller: InputController
    private lateinit var connection: FakeInputConnection
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = WeavePrefs.of(app)
    private val corners get() = FloatingResizeCorner.entries.map { corner ->
        descendants().first { it.contentDescription == corner.contentDescription }
    }
    private val handle get() = descendants().first { it.contentDescription?.startsWith("拖动悬浮键盘") == true }
    private val card get() = keyboard.board.parent as View

    @Before fun setUp() {
        android.provider.Settings.Secure.putInt(app.contentResolver, "navigation_mode", 2)
        prefs.edit().clear().putBoolean(WeavePrefs.FLOATING, true)
            .putString(WeavePrefs.FLOAT_POS_PORT, "0.5,0.5")
            .putString(WeavePrefs.FLOAT_SIZE_PORT, "1.100").commit()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val frame = FrameLayout(activity)
        connection = FakeInputConnection(frame)
        controller = InputController { connection }
        controller.onStartInput(EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }, false)
        keyboard = WeaveKeyboard(activity, controller, object : ImeWindowHost {
            override fun hideKeyboard() {}
            override val window: android.view.Window? get() = null
        })
        frame.addView(keyboard.view, FrameLayout.LayoutParams(-1, -1))
        activity.setContentView(frame)
        layout()
    }

    @After fun tearDown() { keyboard.dispose(); controller.onFinishInput() }

    @Test fun ordinaryFloatingKeyboardHasNoResizeTargets() {
        // A permanently visible grip would steal control touches before resize mode is requested.
        assertTrue(corners.all { it.visibility != View.VISIBLE })
        assertTrue(handle.isShown)
    }

    @Test fun singleHandleTapsToggleResizeTargetsWithoutDockingOrSavingGeometry() {
        // A missing toggle or the former double-tap-to-dock branch breaks the second assertion.
        val before = prefs.all.toMap()
        tapHandle()
        assertTrue(corners.all { it.isShown })
        val edges = descendants().filter { it.contentDescription?.startsWith("从") == true &&
            it.contentDescription?.contains("边调整悬浮键盘大小") == true }.toList()
        assertEquals(4, edges.size)
        assertTrue(edges.all { it.isShown })
        tapHandle()
        assertTrue(corners.all { it.visibility != View.VISIBLE })
        assertTrue(edges.all { it.visibility != View.VISIBLE })
        assertTrue(keyboard.floating)
        assertEquals(before, prefs.all)
        assertEquals("", connection.text.toString())
    }

    @Test fun handleDragMovesWithoutOpeningResizeModeAndCancelRestoresPosition() {
        // Saving a canceled drag, or treating a drag's UP as a tap, violates this gesture contract.
        val before = PointF(card.translationX, card.translationY)
        val settings = prefs.all.toMap()
        val origin = centre(handle)
        val time = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, origin, time)
        send(MotionEvent.ACTION_MOVE, PointF(origin.x + 40, origin.y - 60), time)
        assertTrue(card.translationY < before.y)
        send(MotionEvent.ACTION_CANCEL, PointF(origin.x + 40, origin.y - 60), time)
        layout()
        assertEquals(before.x, card.translationX, 1f)
        assertEquals(before.y, card.translationY, 1f)
        assertEquals(settings, prefs.all)
        assertTrue(corners.all { it.visibility != View.VISIBLE })
        assertEquals("", connection.text.toString())
    }

    @Test fun handleUsesTheFinalUpPositionWhenNoMoveSampleArrives() {
        val before = PointF(card.translationX, card.translationY)
        val point = centre(handle)
        val time = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, point, time)
        send(MotionEvent.ACTION_UP, PointF(point.x + 40f, point.y - 60f), time)
        layout()
        assertEquals(before.x + 40f, card.translationX, 1f)
        assertEquals(before.y - 60f, card.translationY, 1f)
        assertTrue(corners.all { it.visibility != View.VISIBLE })
    }

    @Test fun tappingResizeFrameWithMinorJitterDoesNotSaveGeometry() {
        tapHandle()
        val point = centre(corners.last())
        val settings = prefs.all.toMap()
        val time = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, point, time)
        send(MotionEvent.ACTION_MOVE, PointF(point.x + 2f, point.y + 2f), time)
        send(MotionEvent.ACTION_UP, PointF(point.x + 2f, point.y + 2f), time)
        layout()
        assertEquals(settings, prefs.all)
        assertEquals("", connection.text.toString())
    }

    @Test fun resizeUsesTheFinalUpPositionWhenNoMoveSampleArrives() {
        tapHandle()
        val grip = corners.first { it.contentDescription == FloatingResizeCorner.BOTTOM_RIGHT.contentDescription }
        val point = centre(grip)
        val before = card.width
        val time = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, point, time)
        send(MotionEvent.ACTION_UP, PointF(point.x - 60f, point.y - 60f), time)
        layout()
        assertTrue("Final UP must resize without requiring MOVE", card.width < before)
        assertTrue(prefs.getString(WeavePrefs.FLOAT_SIZE_PORT, null) != "1.100")
        assertEquals("", connection.text.toString())
    }

    @Test fun eachEdgeShrinksUsingOnlyMotionAcrossThatEdgeWithoutTyping() {
        tapHandle()
        for (name in listOf("左边", "上边", "右边", "下边")) {
            val target = descendants().first { it.contentDescription == "从${name}调整悬浮键盘大小" }
            val origin = centre(target).apply { if (name == "上边") x -= target.width * 0.3f }
            val before = card.width
            val delta = when (name) {
                "左边" -> PointF(60f, 0f)
                "右边" -> PointF(-60f, 0f)
                "上边" -> PointF(0f, 60f)
                else -> PointF(0f, -60f)
            }
            val point = PointF(origin.x + delta.x, origin.y + delta.y)
            val time = SystemClock.uptimeMillis()
            send(MotionEvent.ACTION_DOWN, origin, time)
            send(MotionEvent.ACTION_MOVE, point, time)
            layout()
            assertTrue("$name should shrink the keyboard", card.width < before)
            send(MotionEvent.ACTION_CANCEL, point, time)
            layout()
            assertEquals("$name cancellation at viewport=${keyboard.view.width}x${keyboard.view.height}, layout=${card.layoutParams.width}, metrics=${keyboard.metrics.mainHeight}, scale=${prefs.getString(WeavePrefs.FLOAT_SIZE_PORT, null)}", before, card.width)
        }
        assertEquals("", connection.text.toString())
    }

    @Test fun aKeyTapStillTypesWhileResizeModeIsVisible() {
        tapHandle()
        val key = requireNotNull(keyboard.keyboardView.keyOf('q'.code))
        val position = IntArray(2).also(keyboard.keyboardView::getLocationOnScreen)
        val point = PointF(position[0] + key.cell.centerX(), position[1] + key.cell.centerY())
        val time = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, point, time)
        send(MotionEvent.ACTION_UP, point, time)
        assertEquals("q", connection.text.lowercase())
    }

    @Test fun resizeTargetsStayOutsideCandidatesAndKeys() {
        tapHandle()
        val board = android.graphics.Rect().also { keyboard.board.getGlobalVisibleRect(it) }
        val targets = descendants().filter { it.isShown && it.contentDescription?.startsWith("从") == true }.toList()
        assertEquals(8, targets.size)
        for (target in targets) {
            val rect = android.graphics.Rect().also { target.getGlobalVisibleRect(it) }
            assertFalse("${target.contentDescription} overlaps the board", android.graphics.Rect.intersects(board, rect))
        }
    }

    private fun tapHandle() {
        val point = centre(handle)
        val time = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, point, time)
        send(MotionEvent.ACTION_UP, point, time)
        layout()
    }

    private fun centre(view: View): PointF {
        val position = IntArray(2).also(view::getLocationOnScreen)
        return PointF(position[0] + view.width / 2f, position[1] + view.height / 2f)
    }

    private fun send(action: Int, point: PointF, time: Long) {
        val rootPosition = IntArray(2).also(keyboard.view::getLocationOnScreen)
        val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
        event.offsetLocation(-rootPosition[0].toFloat(), -rootPosition[1].toFloat())
        try { assertTrue(keyboard.view.dispatchTouchEvent(event)) } finally { event.recycle() }
    }

    private fun layout() {
        // Let the Activity own the viewport. Manually remeasuring only its child at another size
        // races the next framework traversal and leaves DOWN with mismatched metrics/measured box.
        ShadowLooper.idleMainLooper()
        assertTrue(keyboard.view.isLaidOut)
        assertTrue(keyboard.keyboardView.width > 0 && keyboard.keyboardView.height > 0)
    }

    private fun descendants(view: View = keyboard.view): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
