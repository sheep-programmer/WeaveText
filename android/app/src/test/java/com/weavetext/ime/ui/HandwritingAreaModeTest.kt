package com.weavetext.ime.ui

import android.app.Activity
import android.app.Application
import android.graphics.Insets
import android.graphics.Rect
import android.graphics.RectF
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.core.graphics.Insets as CompatInsets
import androidx.core.view.WindowInsetsCompat
import com.weavetext.ime.ime.InputController
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.testing.FakeEngine
import com.weavetext.ime.testing.FakeInputConnection
import com.weavetext.ime.ui.keyboard.HandwritingAreaMode
import com.weavetext.ime.ui.keyboard.ImeWindowHost
import com.weavetext.ime.ui.keyboard.Key
import com.weavetext.ime.ui.keyboard.KeyCode
import com.weavetext.ime.ui.keyboard.WeaveKeyboard
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-420dpi")
class HandwritingAreaModeTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var activity: Activity
    private lateinit var frame: FrameLayout
    private lateinit var kb: WeaveKeyboard
    private lateinit var controller: InputController
    private lateinit var engine: FakeEngine
    private lateinit var ic: FakeInputConnection
    private var windowHeight = 0
    private var windowWidth = 0
    private lateinit var windowInsets: WindowInsets

    @Before fun setup() {
        android.provider.Settings.Global.putFloat(app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        WeavePrefs.of(app).edit().clear().putString(WeavePrefs.KEYBOARDS, "hand,pinyin,english")
            .putString(WeavePrefs.ACTIVE_KEYBOARD, "hand").putBoolean(WeavePrefs.HAND_AUTO_COMMIT, false).commit()
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        frame = FrameLayout(activity)
        ic = FakeInputConnection(frame)
        controller = InputController { ic }
        engine = FakeEngine()
        kb = WeaveKeyboard(activity, controller, object : ImeWindowHost {
            override fun hideKeyboard() {}
            override val window: android.view.Window? = null
        })
        frame.addView(kb.view, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        activity.setContentView(frame, ViewGroup.LayoutParams(-1, -1))
        val bounds = if (android.os.Build.VERSION.SDK_INT >= 30)
            activity.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        else Rect(0, 0, activity.resources.displayMetrics.widthPixels, activity.resources.displayMetrics.heightPixels)
        windowHeight = bounds.height()
        windowWidth = bounds.width()
        controller.attachEngine(engine)
        controller.onStartInput(textField(), false)
        kb.onShown()
        setWindowInsets(bars())
        layout()
    }

    @After fun teardown() { kb.dispose() }

    private fun textField() = EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }

    private fun bars(top: Int = 72, cutout: Int = 96, nav: Int = 120, buttons: Int = 160): WindowInsets {
        if (android.os.Build.VERSION.SDK_INT < 30) {
            // Native Builder/type APIs are unavailable on API 26/28; the compat builder merges these
            // status/navigation types into the legacy platform insets without a hidden Rect constructor.
            // Its empty builder starts from CONSUMED on these SDKs; seed the actual unconsumed window.
            val source = WindowInsetsCompat.toWindowInsetsCompat(requireNotNull(activity.window.decorView.rootWindowInsets))
            return requireNotNull(WindowInsetsCompat.Builder(source)
                .setInsets(WindowInsetsCompat.Type.statusBars(), CompatInsets.of(0, top, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), CompatInsets.of(32, 0, 48, nav))
                .build().toWindowInsets())
        }
        return WindowInsets.Builder()
            .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, top, 0, 0))
            .setInsets(WindowInsets.Type.displayCutout(), Insets.of(0, cutout, 0, 0))
            .setInsets(WindowInsets.Type.navigationBars(), Insets.of(32, 0, 48, nav))
            .setInsets(WindowInsets.Type.captionBar(), Insets.of(0, 0, 0, buttons))
            .setVisible(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars(), true)
            .build()
    }

    private fun layout(capacity: Int = windowHeight, width: Int = windowWidth) {
        ShadowLooper.idleMainLooper()
        kb.flushRender()
        // Activity traversal supplies its own (empty) insets for this hostless IME fixture. Deliver the
        // modeled window's insets after that traversal, before measuring the granted IME viewport.
        kb.view.dispatchApplyWindowInsets(windowInsets)
        repeat(3) {
            frame.forceLayout()
            kb.view.forceLayout()
            frame.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(capacity, View.MeasureSpec.EXACTLY))
            frame.layout(0, 0, width, capacity)
        }
    }

    private fun setWindowInsets(insets: WindowInsets) {
        windowInsets = insets
        kb.view.dispatchApplyWindowInsets(insets)
    }

    private fun mode(mode: HandwritingAreaMode) {
        kb.prefs.edit().putString(WeavePrefs.HAND_AREA_MODE, mode.key).commit()
        layout()
    }

    private fun normalHeight() = (kb.metrics.bubbleSpace + kb.metrics.kbHeight).toInt() + kb.navInset

    private fun assertNormal() {
        assertEquals(normalHeight(), kb.view.height)
        assertEquals(kb.metrics.mainHeight.toInt(), kb.keyboardView.height)
        assertEquals(0, kb.keyboardView.handAreaHeight)
    }

    /** Mirrors an IME view rebuild on rotation: retain the controller/editor/mode preference, replace the UI. */
    private fun recreateKeyboard(qualifiers: String) {
        kb.dispose()
        activity.finish()
        RuntimeEnvironment.setQualifiers(qualifiers)
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        frame = FrameLayout(activity)
        kb = WeaveKeyboard(activity, controller, object : ImeWindowHost {
            override fun hideKeyboard() {}
            override val window: android.view.Window? = null
        })
        frame.addView(kb.view, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        activity.setContentView(frame, ViewGroup.LayoutParams(-1, -1))
        val bounds = activity.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        windowHeight = bounds.height()
        windowWidth = bounds.width()
        kb.onShown(restarting = true)
        setWindowInsets(bars())
        layout()
    }

    @Test fun standardHalfAndFullChangeThePadAndPreserveSystemBarAndControlClearance() {
        assertEquals(HandwritingAreaMode.KEYBOARD, WeavePrefs.handAreaMode(kb.prefs))
        assertNormal()
        val standardPad = kb.keyboardView.hand!!.rect.height()
        val bottomControlHeight = kb.keyboardView.keyOf(KeyCode.SPACE)!!.rect.height()
        val usable = windowHeight - 96 - 160
        for (area in listOf(HandwritingAreaMode.HALF, HandwritingAreaMode.FULL)) {
            mode(area)
            val expected = if (area == HandwritingAreaMode.HALF) maxOf(kb.metrics.kbHeight.toInt(), usable / 2) else usable
            assertEquals(expected + 160, kb.view.height)
            assertEquals(expected, kb.board.height - kb.board.paddingBottom)
            assertTrue(kb.view.top >= 96)
            assertEquals(32, kb.board.paddingLeft)
            assertEquals(48, kb.board.paddingRight)
            val pad = kb.keyboardView.hand!!.rect
            assertTrue(pad.height() >= standardPad - 1f)
            assertEquals(bottomControlHeight, kb.keyboardView.keyOf(KeyCode.SPACE)!!.rect.height(), 1f)
            for (key in kb.keyboardView.keys) {
                assertFalse("${key.label} overlaps the writing pad", RectF.intersects(pad, key.rect))
                assertTrue("${key.label} stays inside the body", key.cell.bottom <= kb.keyboardView.height + 1f)
            }
            val insets = InputMethodService.Insets()
            kb.computeInsets(insets)
            val loc = IntArray(2)
            kb.board.getLocationInWindow(loc)
            assertEquals(loc[1], insets.contentTopInsets)
            assertEquals(loc[1], insets.visibleTopInsets)
            assertEquals(loc[1] + expected, insets.touchableRegion.bounds.bottom)
            assertEquals(loc[0] + 32, insets.touchableRegion.bounds.left)
            assertEquals(loc[0] + kb.board.width - 48, insets.touchableRegion.bounds.right)
        }
        mode(HandwritingAreaMode.KEYBOARD)
        assertNormal()
    }

    @Test fun changingSchemeAndUsingTheNumberKeyboardRestoresNormalHeight() {
        mode(HandwritingAreaMode.FULL)
        controller.setPreferredSchema("pinyin")
        layout()
        assertNormal()
        assertNull(kb.keyboardView.hand)
        controller.setPreferredSchema("hand")
        layout()
        assertTrue(kb.view.height > normalHeight())
        kb.onKey(Key(KeyCode.NUMBER))
        layout()
        assertNormal()
        assertNull(kb.keyboardView.hand)
        kb.onKey(Key(KeyCode.BACK))
        layout()
        assertNotNull(kb.keyboardView.hand)
        assertTrue(kb.view.height > normalHeight())
        kb.onKey(Key(KeyCode.NUMBER))
        layout()
        assertNormal()
        assertTrue("system Back escapes the number keyboard", kb.handleBack())
        layout()
        assertNotNull(kb.keyboardView.hand)
        assertTrue(kb.view.height > normalHeight())
        assertEquals(HandwritingAreaMode.FULL, WeavePrefs.handAreaMode(kb.prefs))
    }

    @Test fun theLanguageKeyEscapesFullHeightToEnglishAndRestoresHandwritingOnReturn() {
        mode(HandwritingAreaMode.FULL)
        kb.onKey(kb.keyboardView.keyOf(KeyCode.LANG)!!)
        layout()
        assertFalse(controller.state.chinese)
        assertEquals("english", engine.schema)
        assertNull(kb.keyboardView.hand)
        assertNormal()
        kb.onKey(kb.keyboardView.keyOf(KeyCode.LANG)!!)
        layout()
        assertTrue(controller.state.chinese)
        assertEquals("hand", engine.schema)
        assertNotNull(kb.keyboardView.hand)
        assertEquals(windowHeight - 96, kb.view.height)
        assertEquals(HandwritingAreaMode.FULL, WeavePrefs.handAreaMode(kb.prefs))
        assertEquals("", ic.text)
    }

    @Test fun rotationRecreatesFullHeightUsingTheNewViewportAndPreservesAnEnglishEscape() {
        mode(HandwritingAreaMode.FULL)
        val portraitHeight = kb.view.height
        recreateKeyboard("w914dp-h411dp-land-420dpi")
        assertTrue(kb.metrics.landscape)
        assertEquals(windowHeight - 96, kb.view.height)
        assertNotEquals("rotation must discard the portrait height", portraitHeight, kb.view.height)
        for (key in kb.keyboardView.keys) assertTrue(key.cell.bottom <= kb.keyboardView.height + 1f)

        kb.onKey(kb.keyboardView.keyOf(KeyCode.LANG)!!)
        layout()
        assertNormal()
        recreateKeyboard("w411dp-h914dp-port-420dpi")
        assertFalse(kb.metrics.landscape)
        assertFalse(controller.state.chinese)
        assertNull(kb.keyboardView.hand)
        assertNormal()
        kb.onKey(kb.keyboardView.keyOf(KeyCode.LANG)!!)
        layout()
        assertEquals(windowHeight - 96, kb.view.height)
        assertNotNull(kb.keyboardView.hand)
        assertEquals(HandwritingAreaMode.FULL, WeavePrefs.handAreaMode(kb.prefs))
    }

    @Test fun privateAndPasswordFieldsCannotExpandAndReturningToOrdinaryTextRestoresTheChoice() {
        mode(HandwritingAreaMode.FULL)
        controller.onStartInput(textField().apply { imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING }, false)
        layout()
        assertTrue(controller.state.privateField)
        assertNormal()
        assertFalse(engine.learningValue)
        controller.onStartInput(textField().apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }, false)
        layout()
        assertTrue(controller.state.passwordField)
        assertNormal()
        assertNull(kb.keyboardView.hand)
        controller.onStartInput(textField(), false)
        layout()
        assertTrue(kb.view.height > normalHeight())
        assertTrue(engine.learningValue)
    }

    @Test fun floatingAndHardwareKeyboardsIgnoreTheExpansionWithoutOverwritingItsPreference() {
        mode(HandwritingAreaMode.FULL)
        kb.prefs.edit().putBoolean(WeavePrefs.FLOATING, true).commit()
        layout()
        assertTrue(kb.floating)
        assertEquals(0, kb.keyboardView.handAreaHeight)
        assertEquals(kb.metrics.mainHeight.toInt(), kb.keyboardView.height)
        kb.prefs.edit().putBoolean(WeavePrefs.FLOATING, false).commit()
        layout()
        assertTrue(kb.view.height > normalHeight())
        kb.setHardwareMode(true)
        layout()
        assertNormal()
        assertSame(kb.candidatesView, kb.topBar.parent)
        kb.setHardwareMode(false)
        layout()
        assertTrue(kb.view.height > normalHeight())
        assertEquals(HandwritingAreaMode.FULL, WeavePrefs.handAreaMode(kb.prefs))
    }

    @Test fun smallerGrantedViewportsClampTheBodyAndTheirBottomControls() {
        mode(HandwritingAreaMode.FULL)
        val shortViewport = kb.metrics.kbHeight.toInt() - kb.metrics.rowPitch.toInt() + kb.navInset
        layout(shortViewport)
        assertEquals(shortViewport, kb.view.height)
        for (key in kb.keyboardView.keys) assertTrue(key.cell.bottom <= kb.keyboardView.height + 1f)
        assertTrue(kb.keyboardView.hand!!.rect.bottom <= kb.keyboardView.height)
        layout()
        assertTrue(kb.view.height > shortViewport)
    }

    @Test fun systemBarChangesResizeTheAreaWithoutKeepingOldClearance() {
        mode(HandwritingAreaMode.FULL)
        setWindowInsets(bars(top = 144, cutout = 0, nav = 200, buttons = 240))
        layout()
        assertEquals(windowHeight - 144, kb.view.height)
        assertEquals(240, kb.board.paddingBottom)
        setWindowInsets(bars(top = 0, cutout = 0, nav = 0, buttons = 0))
        layout()
        assertEquals(windowHeight, kb.view.height)
        assertEquals(0, kb.board.paddingBottom)
    }

    @Test fun aVisibleStatusBarsTransientZeroReadUsesItsStableClearance() {
        mode(HandwritingAreaMode.FULL)
        setWindowInsets(WindowInsets.Builder(bars(cutout = 0))
            .setInsets(WindowInsets.Type.statusBars(), Insets.NONE)
            .setInsetsIgnoringVisibility(WindowInsets.Type.statusBars(), Insets.of(0, 144, 0, 0)).build())
        layout()
        assertEquals(windowHeight - 144, kb.view.height)
        assertTrue(kb.view.top >= 144)
    }

    @Test fun aNarrowParentAndLandscapeCutoutsKeepTheWritingAreaInsideTheVisibleWidth() {
        mode(HandwritingAreaMode.FULL)
        setWindowInsets(WindowInsets.Builder(bars())
            .setInsets(WindowInsets.Type.displayCutout(), Insets.of(112, 96, 84, 220)).build())
        layout(width = windowWidth / 2)
        assertEquals(112, kb.board.paddingLeft)
        assertEquals(84, kb.board.paddingRight)
        assertEquals(220, kb.board.paddingBottom)
        assertTrue(kb.keyboardView.width <= kb.board.width - 112 - 84)
        assertTrue(kb.keyboardView.hand!!.rect.right <= kb.keyboardView.width)
    }

    @Test @Config(qualifiers = "w914dp-h411dp-land-420dpi")
    fun landscapeUsesTheCurrentWindowAndKeepsTheControlsInsideIt() {
        assertTrue(kb.metrics.landscape)
        for (area in listOf(HandwritingAreaMode.HALF, HandwritingAreaMode.FULL)) {
            mode(area)
            assertTrue(kb.view.height <= windowHeight - 96)
            for (key in kb.keyboardView.keys) assertTrue(key.cell.bottom <= kb.keyboardView.height + 1f)
        }
    }

    @Test @Config(sdk = [26, 28])
    fun legacyAndroidUsesSupportedInsetsAndKeepsTheExpandedBodyInsideTheWindow() {
        for (area in listOf(HandwritingAreaMode.HALF, HandwritingAreaMode.FULL)) {
            mode(area)
            @Suppress("DEPRECATION")
            val detail = "sdk=${android.os.Build.VERSION.SDK_INT} area=$area height=${kb.view.height} window=$windowHeight " +
                "top=${windowInsets.systemWindowInsetTop} stableTop=${windowInsets.stableInsetTop} consumed=${windowInsets.isConsumed} " +
                "rootConsumed=${activity.window.decorView.rootWindowInsets?.isConsumed}"
            assertTrue(detail, kb.view.height <= windowHeight - 72)
            assertNotNull(kb.keyboardView.hand)
            for (key in kb.keyboardView.keys) assertTrue(key.cell.bottom <= kb.keyboardView.height + 1f)
        }
    }

    @Test fun openingAnotherPanelUsesNormalGeometryAndClosingItRestoresHandwriting() {
        mode(HandwritingAreaMode.FULL)
        kb.showPanel("picker")
        layout()
        assertNotNull(kb.panel)
        assertNormal()
        assertTrue("system Back closes the panel", kb.handleBack())
        layout()
        assertTrue(kb.view.height > normalHeight())
        assertEquals(HandwritingAreaMode.FULL, WeavePrefs.handAreaMode(kb.prefs))
    }

    @Test fun areaChangesPreserveCompletedInkWithoutCommittingIt() {
        val pad = kb.keyboardView.hand!!
        val time = SystemClock.uptimeMillis()
        for ((action, x, y) in listOf(Triple(MotionEvent.ACTION_DOWN, 20f, 30f), Triple(MotionEvent.ACTION_UP, 120f, 35f))) {
            val event = MotionEvent.obtain(time, time, action, pad.rect.left + x, pad.rect.top + y, 0)
            try { kb.keyboardView.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        val geometry = pad.strokes.single().copyOf()
        for (area in listOf(HandwritingAreaMode.HALF, HandwritingAreaMode.FULL, HandwritingAreaMode.KEYBOARD)) {
            mode(area)
            assertSame(pad, kb.keyboardView.hand)
            assertArrayEquals(geometry, pad.strokes.single(), 0f)
            assertTrue(controller.state.composing)
            assertEquals("", ic.text)
        }
        assertEquals("resizing does not resubmit recognition", 1, engine.handCalls.size)
    }
}
