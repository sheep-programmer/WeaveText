package com.weavetext.ime.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weavetext.ime.debug.SmokeActivity
import com.weavetext.ime.ime.ImeState
import com.weavetext.ime.ime.InputController
import com.weavetext.ime.settings.FloatingResizeSettings
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.ui.keyboard.FloatingResizeAccessibility
import com.weavetext.ime.ui.keyboard.FloatingResizeCorner
import com.weavetext.ime.ui.keyboard.ImeWindowHost
import com.weavetext.ime.ui.keyboard.WeaveKeyboard
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Real Android WeaveKeyboard/KeyboardView layout and ViewGroup touch dispatch in an Activity.
 * Every event enters kb.view, never a grip directly. Screen coordinates become rawX/rawY while
 * local coordinates follow the root/child transforms as the card resizes between MOVE events.
 * This tests view routing, geometry and editor writes, not system IME-window insets or recognition.
 * An ASCII field uses InputController's direct input path, requiring no recognition engine/models.
 * A real 'q' key tap first proves that accidental touches could write to the real EditText.
 */
@RunWith(AndroidJUnit4::class)
class FloatingResizeDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun defaultFourCornersResizeWithTheOppositeCornerFixedWithoutTyping() {
        // A fresh card per corner avoids reaching MIN_SCALE cumulatively and hiding a broken grip.
        for (corner in FloatingResizeCorner.entries) withKeyboard { f ->
            f.onMain {
                assertFalse(f.prefs.contains(FloatingResizeSettings.KEY_CORNERS))
                assertEquals(FloatingResizeCorner.entries.toSet(), f.visibleCorners())
                f.assertControlRegions()
            }
            val before = f.onMain { screenBounds(f.card) }
            dragCorner(f, corner, requireHandled = true) { current -> assertAnchor(corner, before, current) }
            f.onMain {
                val after = screenBounds(f.card)
                assertTrue("$corner did not shrink width: $before -> $after", after.width() < before.width())
                assertTrue("$corner did not shrink height: $before -> $after", after.height() < before.height())
                assertAnchor(corner, before, after)
                assertTrue(f.keyboard.floating)
                assertTrue("UP must persist the new size", f.prefs.getString(f.sizeKey(), null) != INITIAL_SCALE)
                f.assertInsideRoot()
                f.assertNoInput()
            }
        }
    }

    @Test fun selectingOnlyBottomRightLiveDisablesOtherCornerGesturesWithoutTyping() = withKeyboard { f ->
        f.onMain { FloatingResizeSettings.write(f.prefs, setOf(FloatingResizeCorner.BOTTOM_RIGHT)) }
        f.awaitLayout()
        f.onMain {
            assertEquals(8, FloatingResizeSettings.cornerMask(f.prefs))
            assertEquals(setOf(FloatingResizeCorner.BOTTOM_RIGHT), f.visibleCorners())
            f.assertControlRegions()
        }
        for (corner in FloatingResizeCorner.entries.filter { it != FloatingResizeCorner.BOTTOM_RIGHT }) {
            val before = f.onMain { screenBounds(f.card) }
            val scale = f.onMain { f.prefs.getString(f.sizeKey(), null) }
            // GONE grips have no touch target: exercise their reserved bar position via the root.
            // The top drag bar may move the card here; it must not resize or type through the bar.
            dragCorner(f, corner, requireHandled = false)
            f.onMain {
                val after = screenBounds(f.card)
                assertEquals("Disabled $corner resized width", before.width(), after.width())
                assertEquals("Disabled $corner resized height", before.height(), after.height())
                assertEquals("Disabled $corner saved a size", scale, f.prefs.getString(f.sizeKey(), null))
                assertTrue("Disabled $corner unexpectedly docked the card", f.keyboard.floating)
                f.assertInsideRoot()
                f.assertNoInput()
            }
        }
        val before = f.onMain { screenBounds(f.card) }
        dragCorner(f, FloatingResizeCorner.BOTTOM_RIGHT, requireHandled = true) { current ->
            assertAnchor(FloatingResizeCorner.BOTTOM_RIGHT, before, current)
        }
        f.onMain {
            assertTrue(screenBounds(f.card).width() < before.width())
            assertTrue(screenBounds(f.card).height() < before.height())
            assertEquals(8, FloatingResizeSettings.cornerMask(f.prefs))
            f.assertNoInput()
        }
    }

    @Test fun bottomAlignedRightCornerGrowsVisiblyBySlidingUpWithoutTyping() = withKeyboard(bottomAligned = true) { f ->
        val before = f.onMain {
            // Omit both position keys: exercise the actual DEFAULT (fy=1), not a centred fixture.
            assertFalse(f.prefs.contains(WeavePrefs.FLOAT_POS_PORT))
            assertFalse(f.prefs.contains(WeavePrefs.FLOAT_POS_LAND))
            f.assertControlRegions()
            val card = screenBounds(f.card)
            val usableBottom = screenBounds(f.keyboard.view).bottom - f.keyboard.navInset - f.keyboard.metrics.dp(8f).toInt()
            assertNear("Card must start at the usable bottom edge", usableBottom, card.bottom)
            card
        }
        dragCorner(f, FloatingResizeCorner.BOTTOM_RIGHT, requireHandled = true, outward = true) { current ->
            assertNear("Outward resize should slide up along the bottom edge", before.bottom, current.bottom)
        }
        f.onMain {
            val after = screenBounds(f.card)
            assertTrue("Bottom-right grip must visibly grow width: $before -> $after", after.width() > before.width())
            assertTrue("Bottom-right grip must visibly grow height: $before -> $after", after.height() > before.height())
            assertTrue("Growth at the bottom edge must slide the card upward", after.top < before.top)
            assertNear("The enlarged card must stay at the usable bottom", before.bottom, after.bottom)
            assertTrue("UP must persist the expanded size", f.prefs.getString(f.sizeKey(), null) != INITIAL_SCALE)
            assertTrue(f.keyboard.floating)
            f.assertInsideRoot()
            f.assertNoInput()
        }
    }

    @Test fun closeXTapDocksTheKeyboardWithoutTyping() = withKeyboard { f ->
        val point = f.onMain {
            f.assertControlRegions()
            val close = f.find(FloatingResizeAccessibility.CLOSE_DESCRIPTION)
                ?: error("Missing independent close X view")
            assertTrue(close.isShown && close.isClickable)
            centre(screenBounds(close))
        }
        val downTime = SystemClock.uptimeMillis()
        f.emit(MotionEvent.ACTION_DOWN, point, downTime, requireHandled = true)
        f.emit(MotionEvent.ACTION_UP, point, downTime, requireHandled = true)
        f.awaitLayout()
        f.onMain {
            assertFalse("X must turn off floating mode", f.keyboard.floating)
            assertFalse(f.prefs.getBoolean(WeavePrefs.FLOATING, true))
            assertEquals(emptySet<FloatingResizeCorner>(), f.visibleCorners())
            assertFalse(f.find(FloatingResizeAccessibility.CLOSE_DESCRIPTION)?.isShown == true)
            assertTrue("The docked KeyboardView must remain visible", f.keyboard.keyboardView.isShown)
            assertTrue(f.keyboard.keyboardView.width > 0 && f.keyboard.keyboardView.height > 0)
            assertEquals("Docked board should fill its card", f.card.width, f.keyboard.board.width)
            f.assertNoInput()
        }
    }

    @Test fun cancellingEachCornerRestoresTheStartBoxAndNeverSavesTheDrag() {
        for (corner in FloatingResizeCorner.entries) withKeyboard { f ->
            val before = f.onMain { screenBounds(f.card) }
            val prefsBefore = f.onMain { f.prefs.all.toMap() }
            var resized = false
            dragCorner(f, corner, requireHandled = true, endAction = MotionEvent.ACTION_CANCEL) { current ->
                assertAnchor(corner, before, current)
                if (current.width() != before.width() || current.height() != before.height()) resized = true
                f.onMain { assertEquals("MOVE must not persist geometry", prefsBefore, f.prefs.all) }
            }
            f.onMain {
                assertTrue("$corner never resized before cancellation", resized)
                val restored = screenBounds(f.card)
                assertEquals(before.width(), restored.width())
                assertEquals(before.height(), restored.height())
                assertNear("Canceled $corner left", before.left, restored.left)
                assertNear("Canceled $corner top", before.top, restored.top)
                assertEquals("CANCEL must not save size or position", prefsBefore, f.prefs.all)
                f.assertNoInput()
            }
        }
    }

    private fun dragCorner(
        f: Fixture, corner: FloatingResizeCorner, requireHandled: Boolean,
        endAction: Int = MotionEvent.ACTION_UP, outward: Boolean = false, onMove: (Rect) -> Unit = {},
    ) {
        val before = f.onMain { screenBounds(f.card) }
        val origin = f.onMain {
            val grip = f.find(corner.contentDescription)
            if (grip != null && grip.isShown && grip.width > 0 && grip.height > 0) centre(screenBounds(grip))
            else {
                val inset = f.keyboard.metrics.dp(24f)
                PointF(if (corner.horizontalSign < 0) before.left + inset else before.right - inset,
                    if (corner.verticalSign < 0) before.top + inset else before.bottom - inset)
            }
        }
        val direction = if (outward) 1 else -1
        val dx = direction * corner.horizontalSign * max(before.width() * 0.10f, f.onMain { f.keyboard.metrics.dp(12f) })
        val heightDelta = max(before.height() * 0.10f, f.onMain { f.keyboard.metrics.dp(12f) })
        // Keep an outward bottom pointer within the display by using the reserved 48dp bar's room.
        val dy = direction * corner.verticalSign * if (outward) min(heightDelta, f.onMain { f.keyboard.metrics.dp(16f) }) else heightDelta
        val downTime = SystemClock.uptimeMillis()
        try {
            f.emit(MotionEvent.ACTION_DOWN, origin, downTime, requireHandled)
            if (requireHandled) f.onMain {
                val down = screenBounds(f.card)
                assertEquals("DOWN must not jump or change dimensions", before, down)
            }
            for (step in 1..6) {
                val point = PointF(origin.x + dx * step / 6, origin.y + dy * step / 6)
                f.emit(MotionEvent.ACTION_MOVE, point, downTime, requireHandled)
                f.awaitLayout() // Observe real remeasurement between raw-coordinate MOVE samples.
                f.onMain { f.assertInsideRoot(); f.assertNoInput() }
                onMove(f.onMain { screenBounds(f.card) })
            }
            f.emit(endAction, PointF(origin.x + dx, origin.y + dy), downTime, requireHandled)
        } catch (failure: Throwable) {
            // Release captured pointers even if an intermediate invariant failed.
            runCatching { f.emit(MotionEvent.ACTION_CANCEL, origin, downTime, requireHandled = false) }
                .onFailure { failure.addSuppressed(it) }
            throw failure
        }
        f.awaitLayout()
    }

    private fun withKeyboard(bottomAligned: Boolean = false, test: (Fixture) -> Unit) {
        val app = instrumentation.targetContext
        val namespace = "floating-resize-device-${UUID.randomUUID()}"
        val storage = File(app.cacheDir, namespace).apply { mkdirs() }
        val prefsNames = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        try {
            ActivityScenario.launch(SmokeActivity::class.java).use { scenario ->
                var fixture: Fixture? = null
                try {
                    scenario.onActivity { activity ->
                        activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                        val editor = activity.findViewById<EditText>(android.R.id.edit)
                        editor.showSoftInputOnFocus = false
                        activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(editor.windowToken, 0)
                        val ctx = object : ContextWrapper(activity) {
                            // WeavePrefs.of uses applicationContext: preserve isolation through it too.
                            override fun getApplicationContext(): Context = this
                            override fun getFilesDir() = File(storage, "files").apply { mkdirs() }
                            override fun getCacheDir() = File(storage, "cache").apply { mkdirs() }
                            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                                val isolatedName = "$namespace-$name"
                                prefsNames += isolatedName
                                return app.getSharedPreferences(isolatedName, mode)
                            }
                        }
                        val prefs = WeavePrefs.of(ctx)
                        assertTrue(prefs.edit().putBoolean(WeavePrefs.FLOATING, true)
                            .apply {
                                if (!bottomAligned) {
                                    putString(WeavePrefs.FLOAT_POS_PORT, "0.5,0.5")
                                    putString(WeavePrefs.FLOAT_POS_LAND, "0.5,0.5")
                                }
                            }
                            .putString(WeavePrefs.FLOAT_SIZE_PORT, INITIAL_SCALE).putString(WeavePrefs.FLOAT_SIZE_LAND, INITIAL_SCALE)
                            .putBoolean(WeavePrefs.KEY_PREVIEW, false).putBoolean(WeavePrefs.CLIPBOARD_RECORD, false).commit())
                        editor.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        editor.imeOptions = EditorInfo.IME_FLAG_FORCE_ASCII or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                        editor.setText(SEED); editor.setSelection(SEED.length)
                        val info = EditorInfo()
                        val connection = RecordingConnection(requireNotNull(editor.onCreateInputConnection(info)))
                        val controller = InputController { connection }
                        controller.onStartInput(info, false)
                        val keyboard = WeaveKeyboard(ctx, controller, object : ImeWindowHost {
                            override fun hideKeyboard() {}
                            override val window get() = activity.window
                        })
                        fixture = Fixture(scenario, keyboard, controller, editor, info, connection, prefs)
                        (editor.parent as? ViewGroup)?.removeView(editor)
                        activity.setContentView(FrameLayout(activity).apply {
                            addView(editor, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
                            addView(keyboard.view, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
                        })
                        editor.requestFocus()
                        keyboard.onShown()
                        keyboard.flushRender()
                    }
                    val f = requireNotNull(fixture)
                    f.awaitLayout()
                    f.proveInputPath()
                    test(f)
                } finally {
                    scenario.onActivity {
                        fixture?.let { f ->
                            f.keyboard.onHidden()
                            f.keyboard.dispose()
                            (f.keyboard.view.parent as? ViewGroup)?.removeView(f.keyboard.view)
                            f.controller.onFinishInput()
                        }
                    }
                }
            }
        } finally {
            synchronized(prefsNames) { prefsNames.forEach { app.deleteSharedPreferences(it) } }
            storage.deleteRecursively()
        }
    }

    private inner class Fixture(
        val scenario: ActivityScenario<SmokeActivity>, val keyboard: WeaveKeyboard,
        val controller: InputController, val editor: EditText, val info: EditorInfo,
        val connection: RecordingConnection, val prefs: SharedPreferences,
    ) {
        val card get() = keyboard.board.parent as ViewGroup
        private var idleState: ImeState = controller.state

        fun <T> onMain(action: () -> T): T {
            var result: T? = null
            scenario.onActivity { result = action() }
            @Suppress("UNCHECKED_CAST")
            return result as T
        }

        fun find(description: String): View? {
            fun walk(view: View): View? {
                if (view.contentDescription?.toString() == description) return view
                if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))?.let { return it }
                return null
            }
            return walk(keyboard.view)
        }

        fun visibleCorners() = FloatingResizeCorner.entries.filter { find(it.contentDescription)?.isShown == true }.toSet()
        fun sizeKey() = if (keyboard.metrics.landscape) WeavePrefs.FLOAT_SIZE_LAND else WeavePrefs.FLOAT_SIZE_PORT

        fun awaitLayout() {
            val deadline = SystemClock.uptimeMillis() + 5_000
            var previous: Rect? = null
            while (SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                val current = onMain {
                    keyboard.flushRender()
                    if (keyboard.view.isLaidOut && !keyboard.view.isLayoutRequested && !card.isLayoutRequested &&
                        !keyboard.board.isLayoutRequested && keyboard.keyboardView.width > 0 && keyboard.keyboardView.height > 0)
                        screenBounds(card) else null
                }
                if (current != null && current == previous) return
                previous = current
                SystemClock.sleep(32) // Instrumentation thread only; allow a framework layout/frame.
            }
            error("Keyboard layout did not settle; last card=$previous")
        }

        fun emit(action: Int, point: PointF, downTime: Long, requireHandled: Boolean) = onMain {
            val rootPosition = IntArray(2).also(keyboard.view::getLocationOnScreen)
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            // offsetLocation preserves rawX/rawY; ViewGroup then transforms x/y for each child.
            event.offsetLocation(-rootPosition[0].toFloat(), -rootPosition[1].toFloat())
            try {
                assertEquals("Event must preserve screen rawX", point.x, event.rawX, 0.01f)
                assertEquals("Event must preserve screen rawY", point.y, event.rawY, 0.01f)
                val handled = keyboard.view.dispatchTouchEvent(event)
                if (requireHandled) assertTrue("Root did not handle action=$action at $point", handled)
            } finally { event.recycle() }
        }

        fun proveInputPath() {
            val point = onMain {
                assertFalse("Use a direct ASCII input field", controller.state.chinese)
                val key = requireNotNull(keyboard.keyboardView.keyOf('q'.code))
                val bounds = screenBounds(keyboard.keyboardView)
                PointF(bounds.left + key.cell.centerX(), bounds.top + key.cell.centerY())
            }
            val time = SystemClock.uptimeMillis()
            emit(MotionEvent.ACTION_DOWN, point, time, true)
            emit(MotionEvent.ACTION_UP, point, time, true)
            awaitLayout()
            onMain {
                assertEquals("Real key input must work before checking for accidental input", "${SEED}q", editor.text.toString().lowercase())
                assertTrue("InputConnection must have observed the key commit", connection.writes.isNotEmpty())
                editor.setText(SEED); editor.setSelection(SEED.length)
                info.initialSelStart = SEED.length; info.initialSelEnd = SEED.length
                controller.onStartInput(info, false)
                keyboard.flushRender()
                idleState = controller.state
                connection.writes.clear()
            }
            awaitLayout()
        }

        fun assertNoInput() {
            assertEquals("Control touch changed editor text", SEED, editor.text.toString())
            assertEquals("Control touch moved editor selection", SEED.length, editor.selectionStart)
            assertEquals("Control touch moved editor selection", SEED.length, editor.selectionEnd)
            assertTrue("Control touch wrote through InputConnection: ${connection.writes}", connection.writes.isEmpty())
            assertEquals("Control touch changed input state", idleState, controller.state)
            assertNull("Control touch opened a keyboard panel", keyboard.panel)
        }

        fun assertInsideRoot() {
            val root = screenBounds(keyboard.view)
            val bounds = screenBounds(card)
            assertTrue("Card escaped root: card=$bounds root=$root", root.contains(bounds))
        }

        fun assertControlRegions() {
            assertInsideRoot()
            val board = screenBounds(keyboard.board)
            val close = screenBounds(requireNotNull(find(FloatingResizeAccessibility.CLOSE_DESCRIPTION)))
            val minimum = keyboard.metrics.dp(48f) - 1f
            assertTrue(close.width() >= minimum && close.height() >= minimum)
            assertFalse("Close X covers candidate/key board", Rect.intersects(close, board))
            for (corner in visibleCorners()) {
                val grip = screenBounds(requireNotNull(find(corner.contentDescription)))
                assertTrue("$corner target is smaller than 48dp: $grip", grip.width() >= minimum && grip.height() >= minimum)
                assertFalse("$corner covers candidate/key board", Rect.intersects(grip, board))
                assertFalse("$corner overlaps close X", Rect.intersects(grip, close))
            }
        }
    }

    private class RecordingConnection(target: InputConnection) : InputConnectionWrapper(target, false) {
        val writes = mutableListOf<String>()
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            writes += "commit:$text"
            return super.commitText(text, newCursorPosition)
        }
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            writes += "compose:$text"
            return super.setComposingText(text, newCursorPosition)
        }
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            writes += "key:${event.keyCode}"
            return super.sendKeyEvent(event)
        }
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            writes += "delete:$beforeLength,$afterLength"
            return super.deleteSurroundingText(beforeLength, afterLength)
        }
    }

    private fun screenBounds(view: View): Rect {
        val position = IntArray(2).also(view::getLocationOnScreen)
        return Rect(position[0], position[1], position[0] + view.width, position[1] + view.height)
    }
    private fun centre(rect: Rect) = PointF(rect.exactCenterX(), rect.exactCenterY())
    private fun assertAnchor(corner: FloatingResizeCorner, before: Rect, after: Rect) {
        assertNear("$corner opposite X", if (corner.horizontalSign < 0) before.right else before.left,
            if (corner.horizontalSign < 0) after.right else after.left)
        assertNear("$corner opposite Y", if (corner.verticalSign < 0) before.bottom else before.top,
            if (corner.verticalSign < 0) after.bottom else after.top)
    }
    private fun assertNear(message: String, expected: Int, actual: Int) =
        assertTrue("$message: expected=$expected actual=$actual", abs(expected - actual) <= 2)

    companion object {
        private const val SEED = "weave:"
        private const val INITIAL_SCALE = "1.100"
    }
}
