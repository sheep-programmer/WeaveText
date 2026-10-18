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
import org.junit.Assert.assertTrue
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
        // 这些用例按三键导航的手机写：底边 126，第一帧就按系统资源留出来。
        // These cases are written for a phone with the three-button bar: 126 at the bottom, reserved from the first
        // frame out of the platform resource.
        android.provider.Settings.Secure.putInt(app.contentResolver, "navigation_mode", 0)
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

    private fun idle() = Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

    /** 让复核用的延迟任务跑完（时间也要往前走，复核是按间隔算的）。 Let the delayed re-read run; the confirmation is
     * measured in time, so the clock has to move. */
    private fun settle() = Shadows.shadowOf(android.os.Looper.getMainLooper())
        .idleFor(java.time.Duration.ofMillis(250))

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
        val hidden = WindowInsets.Builder(navBar(126))
            .setInsets(WindowInsets.Type.navigationBars(), Insets.NONE)
            .setVisible(WindowInsets.Type.navigationBars(), false).build()
        setWindowInsets(hidden)
        kb.view.dispatchApplyWindowInsets(WindowInsets.CONSUMED)
        assertEquals("归零的那一下先不当真", 126, kb.navInset)
        // 过一会儿复核还是 0：导航栏真的收起了，让开。
        settle()
        assertEquals(0, kb.navInset)
        setWindowInsets(navBar(48))
        // 真机上输入法窗口随内容收缩，底部始终贴在屏幕底边；这里跟着摆回去，重叠量才是导航栏那一份。
        // On a device the IME window follows its content and stays glued to the bottom; put it back so the overlap
        // measures the bar again.
        placeRootAtScreenBottom()
        kb.view.dispatchApplyWindowInsets(WindowInsets.CONSUMED)
        assertEquals(48, kb.navInset)
    }

    /**
     * 用户报的那一种：切应用时拿着过场动画里的 0 去重排，键盘底部就滑到系统虚拟按键下面，一直等到再点一次键才回来。
     * The reported defect: an app switch re-lays the keyboard out from the transitional zero, so the bottom row slides
     * under the system buttons and only a later re-show brings it back.
     */
    @Test fun aTransitionalZeroDoesNotPutTheBottomRowUnderTheSystemButtons() {
        setWindowInsets(navBar(126))
        placeRootAtScreenBottom()
        kb.view.dispatchApplyWindowInsets(WindowInsets.CONSUMED)
        assertEquals(126, kb.navInset)
        setWindowInsets(navBar(0))
        placeRootAtScreenBottom()
        kb.view.dispatchApplyWindowInsets(WindowInsets.CONSUMED)
        assertEquals("过场中的 0 不能收走留白", 126, kb.navInset)
        // 动画走完，导航栏又报回来：一次都没有收起过留白。
        // The animation ends and the bar is reported again: the strip was never given up.
        setWindowInsets(navBar(126))
        placeRootAtScreenBottom()
        kb.view.dispatchApplyWindowInsets(WindowInsets.CONSUMED)
        idle()
        assertEquals("真实的 126 回来后一次都没有闪", 126, kb.navInset)
    }

    @Test fun aZeroWhichIsReallyGoneIsHonouredAfterTheConfirmingRead() {
        setWindowInsets(navBar(126))
        placeRootAtScreenBottom()
        kb.view.dispatchApplyWindowInsets(WindowInsets.CONSUMED)
        assertEquals(126, kb.navInset)
        // 沉浸式应用：导航栏真的收起了，复核之后照样让开。
        // An immersive app: the bar really is gone, and the confirming re-read gives the strip up.
        setWindowInsets(navBar(0))
        placeRootAtScreenBottom()
        kb.view.dispatchApplyWindowInsets(WindowInsets.CONSUMED)
        assertEquals(126, kb.navInset)
        settle()
        assertEquals("确认之后收掉留白", 0, kb.navInset)
    }

    /**
     * 边衬一直不来时，第一帧就按系统资源留住导航栏：用户看到的第一眼不能是压在虚拟按键下面的键盘。
     * With no reading at all yet, the first frame reserves the bar from the platform resource: what the user sees
     * first must never be a keyboard under the system buttons. Robolectric dispatches a zero on attach, which the
     * keyboard then holds exactly as it holds a mid-transition zero on a device.
     */
    @Test fun theBarIsReservedFromThePlatformUntilAReadingIsConfirmed() {
        val want = app.resources.getDimensionPixelSize(
            app.resources.getIdentifier("navigation_bar_height", "dimen", "android"))
        assertTrue("这台设备应当有导航栏高度资源", want > 0)
        assertEquals(want, kb.navInset)
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
        settle()
        assertEquals(0, kb.navInset)
        setWindowInsets(navBar(126))
        placeRootAtScreenBottom()
        kb.view.viewTreeObserver.dispatchOnGlobalLayout()
        idle()
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
        // 0 -> 126 -> 48 -> 0 每一步都会 applyGeometry，高度跟着变；把底部那条整个让回去要等复核。
        // Each change re-lays out the board with the new bottom padding; giving the bottom strip up entirely waits
        // for the confirming re-read.
        kb.view.dispatchApplyWindowInsets(navBar(0))
        settle()
        assertEquals("inset0", 0, kb.navInset)
        kb.view.dispatchApplyWindowInsets(navBar(126))
        idle()
        assertEquals("inset126", 126, kb.navInset)
        kb.view.dispatchApplyWindowInsets(navBar(48))
        idle()
        assertEquals("inset48", 48, kb.navInset)
        kb.view.dispatchApplyWindowInsets(navBar(0))
        settle()
        assertEquals("inset0 再来一次", 0, kb.navInset)
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
        // 切应用时先报到的是 0，而且连着两次：这时只能当真，留白先收掉。
        setWindowInsets(navBar(0))
        kb.view.dispatchApplyWindowInsets(WindowInsets.CONSUMED)
        kb.view.dispatchApplyWindowInsets(WindowInsets.CONSUMED)
        assertEquals(0, kb.navInset)
        // 动画结束后导航栏才落定，而且没有新的边衬分发：窗口显示时重读一次窗口边衬。
        // The bar settles after the animation with no new dispatch: showing the window reads the window insets again.
        setWindowInsets(navBar(126))
        placeRootAtScreenBottom()
        kb.onWindowShown()
        assertEquals(126, kb.navInset)
        idle()
        assertEquals(126, kb.navInset)
    }
}
