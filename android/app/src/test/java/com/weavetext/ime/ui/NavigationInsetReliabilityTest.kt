package com.weavetext.ime.ui

import android.app.Activity
import android.graphics.Insets
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.ime.InputController
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.ui.keyboard.ImeWindowHost
import com.weavetext.ime.ui.keyboard.WeaveKeyboard
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Window insets, consumed child dispatches, and layout-only navigation changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-420dpi")
class NavigationInsetReliabilityTest {
    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()
    private lateinit var kb: WeaveKeyboard
    private lateinit var controller: InputController
    private lateinit var activity: Activity
    private var useHostWindow = false
    private val host = object : ImeWindowHost {
        override fun hideKeyboard() {}
        override val window: android.view.Window? get() = if (useHostWindow) activity.window else null
    }

    @Before fun setUp() {
        WeavePrefs.of(app).edit().clear().commit()
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        controller = InputController { null }
        kb = WeaveKeyboard(activity, controller, host)
        val frame = FrameLayout(activity)
        frame.addView(kb.view, FrameLayout.LayoutParams(-1, -2))
        activity.setContentView(frame, ViewGroup.LayoutParams(-1, -1))
        kb.onShown()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @After fun tearDown() { kb.dispose() }

    private fun navBar(bottom: Int) = WindowInsets.Builder()
        .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, bottom))
        .setInsetsIgnoringVisibility(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, bottom))
        .setVisible(WindowInsets.Type.navigationBars(), true)
        .build()

    private fun setWindowInsets(insets: WindowInsets) {
        useHostWindow = true
        val viewRoot = ReflectionHelpers.callInstanceMethod<Any>(activity.window.decorView, "getViewRootImpl")
        ReflectionHelpers.setField(viewRoot, "mLastWindowInsets", insets)
        assertEquals(insets, activity.window.decorView.rootWindowInsets)
    }

    private fun placeRootAtScreenBottom() {
        val bounds = activity.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val location = IntArray(2)
        kb.view.getLocationOnScreen(location)
        kb.view.translationY += bounds.bottom - location[1] - kb.view.height
    }

    @Test fun aConsumedChildCannotEraseTheWindowNavigationBar() {
        setWindowInsets(navBar(126))
        placeRootAtScreenBottom()
        kb.view.dispatchApplyWindowInsets(WindowInsets.CONSUMED)
        assertEquals(126, kb.navInset)
    }

    @Test fun visibleNavigationFallsBackToItsReportedStableSize() {
        setWindowInsets(WindowInsets.Builder(navBar(126))
            .setInsets(WindowInsets.Type.navigationBars(), Insets.NONE).build())
        placeRootAtScreenBottom()
        kb.view.dispatchApplyWindowInsets(WindowInsets.CONSUMED)
        assertEquals(126, kb.navInset)
    }

    @Test fun hiddenNavigationDoesNotKeepTheStableBarPadding() {
        setWindowInsets(navBar(126))
        placeRootAtScreenBottom()
        kb.view.dispatchApplyWindowInsets(WindowInsets.CONSUMED)
        assertEquals(126, kb.navInset)
        setWindowInsets(WindowInsets.Builder(navBar(126))
            .setInsets(WindowInsets.Type.navigationBars(), Insets.NONE)
            .setVisible(WindowInsets.Type.navigationBars(), false).build())
        kb.view.dispatchApplyWindowInsets(WindowInsets.CONSUMED)
        assertEquals(0, kb.navInset)
        setWindowInsets(navBar(48))
        kb.view.dispatchApplyWindowInsets(WindowInsets.CONSUMED)
        assertEquals(48, kb.navInset)
    }

    @Test fun aParentThatAlreadyAvoidsNavigationDoesNotGetDoublePadding() {
        setWindowInsets(navBar(126))
        placeRootAtScreenBottom()
        kb.view.translationY -= 126
        kb.view.dispatchApplyWindowInsets(WindowInsets.CONSUMED)
        assertEquals(0, kb.navInset)
    }

    @Test fun aConsumedSignalBeforeTheHostIsReadyPreservesKnownInsets() {
        kb.view.dispatchApplyWindowInsets(navBar(126))
        kb.view.dispatchApplyWindowInsets(WindowInsets.CONSUMED)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(126, kb.navInset)
    }

    @Test fun parentLayoutRefreshesWindowInsetsWithoutAnotherChildDispatch() {
        setWindowInsets(navBar(0))
        kb.view.dispatchApplyWindowInsets(WindowInsets.CONSUMED)
        assertEquals(0, kb.navInset)
        setWindowInsets(navBar(126))
        placeRootAtScreenBottom()
        kb.view.viewTreeObserver.dispatchOnGlobalLayout()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(126, kb.navInset)
    }

    @Test fun aDispatchedNavigationBarIsHonouredEvenWhenHostWindowIsMissing() {
        // host.window=null 时就代表「拿不到窗口级 insets」——只有 dispatch 进来的那份。
        // stubbed host.window: the only source of truth is the dispatched signal.
        kb.view.dispatchApplyWindowInsets(navBar(126))
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(126, kb.navInset)
    }

    @Test fun repeatedZeroInsetsDontDrownALaterRealOne() {
        // 重现真实顺序：先 0（被消耗）再 126（重发）。键盘必须接到 126。
        kb.view.dispatchApplyWindowInsets(navBar(0))
        kb.view.dispatchApplyWindowInsets(navBar(126))
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(126, kb.navInset)
    }

    @Test fun repeatedOnShownReadsTheCurrentWindowInsets() {
        setWindowInsets(navBar(126))
        placeRootAtScreenBottom()
        kb.onShown()
        assertEquals(126, kb.navInset)
        setWindowInsets(navBar(48))
        kb.onShown(true)
        assertEquals(48, kb.navInset)
    }

    @Test fun layoutIsRepeatedAfterNavInsetChanges() {
        // 0 -> 126 -> 48 每一步都会 applyGeometry，高度跟着变。
        // Each change re-lays out the board with the new bottom padding.
        for ((inset, want) in listOf(0 to 0, 126 to 126, 48 to 48, 0 to 0)) {
            kb.view.dispatchApplyWindowInsets(navBar(inset))
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("inset" + inset, want, kb.navInset)
        }
    }

    @Test fun theSystemImeButtonRowUnderGestureNavigationIsKeptClear() {
        // 手势条只有 63，系统画的「收起 / 切换输入法」那排按钮以标题栏边衬报 126：按 126 留。
        // The gesture handle is 63 but the system's hide / switch-keyboard row reports 126 as a caption bar.
        setWindowInsets(WindowInsets.Builder(navBar(63))
            .setInsets(WindowInsets.Type.captionBar(), Insets.of(0, 0, 0, 126)).build())
        placeRootAtScreenBottom()
        kb.view.dispatchApplyWindowInsets(WindowInsets.CONSUMED)
        assertEquals(126, kb.navInset)
    }

    @Test fun aZeroReadDuringAnAppSwitchIsCorrectedOnceTheWindowShows() {
        setWindowInsets(navBar(0))
        kb.view.dispatchApplyWindowInsets(WindowInsets.CONSUMED)
        assertEquals(0, kb.navInset)
        kb.onWindowShown()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        // 动画结束后导航栏才落定，没有新的边衬分发：靠延迟补测读到。 The bar settles later with no new dispatch.
        setWindowInsets(navBar(126))
        placeRootAtScreenBottom()
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400))
        assertEquals(126, kb.navInset)
    }
}
