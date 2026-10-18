package com.weavetext.ime.ui

import android.graphics.Rect
import com.weavetext.ime.ui.keyboard.NavigationClearance
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NavigationClearanceTest {
    private val screen = Rect(0, 0, 1080, 2400)

    @Test fun legacyInsetsCannotLiftTheKeyboardByTheImeHeight() {
        assertEquals(126, NavigationClearance.legacyInset(800, 126, 126))
        assertEquals(126, NavigationClearance.legacyInset(800, 0, 126))
        assertEquals(48, NavigationClearance.legacyInset(48, 126, 126))
        assertEquals(0, NavigationClearance.legacyInset(0, 126, 126))
    }

    @Test fun reservesThreeButtonsOnlyWhenTheyOverlap() {
        val bars = NavigationClearance.Edges(bottom = 126)
        assertEquals(bars, NavigationClearance.overlap(screen, Rect(0, 1600, 1080, 2400), bars))
        assertEquals(NavigationClearance.Edges(), NavigationClearance.overlap(screen, Rect(0, 1600, 1080, 2274), bars))
        assertEquals(NavigationClearance.Edges(bottom = 26), NavigationClearance.overlap(screen, Rect(0, 1600, 1080, 2300), bars))
    }

    @Test fun gesturesAndHiddenNavigationDoNotAddExcessHeight() {
        assertEquals(NavigationClearance.Edges(bottom = 48), NavigationClearance.overlap(screen, screen, NavigationClearance.Edges(bottom = 48)))
        assertEquals(NavigationClearance.Edges(), NavigationClearance.overlap(screen, screen, NavigationClearance.Edges()))
    }

    @Test fun landscapeAndOffsetWindowAvoidTheSideBarOnce() {
        val window = Rect(200, 100, 2600, 1180)
        val bars = NavigationClearance.Edges(right = 126)
        assertEquals(bars, NavigationClearance.overlap(window, window, bars))
        assertEquals(NavigationClearance.Edges(), NavigationClearance.overlap(window, Rect(200, 700, 2474, 1180), bars))
        val left = NavigationClearance.Edges(left = 90)
        assertEquals(left, NavigationClearance.overlap(window, window, left))
        assertEquals(NavigationClearance.Edges(), NavigationClearance.overlap(window, Rect(290, 700, 2600, 1180), left))
    }

    /** 三键导航的手机上先按资源留出底边；手势导航只画手势条，不预留下来。 */
    @Test fun theBarIsSeededFromThePlatformUnlessNavigationIsGestural() {
        assertEquals(NavigationClearance.Edges(bottom = 126), NavigationClearance.seed(0, 126))
        assertEquals(NavigationClearance.Edges(bottom = 126), NavigationClearance.seed(1, 126))
        assertEquals(NavigationClearance.Edges(), NavigationClearance.seed(2, 126))
        assertEquals(NavigationClearance.Edges(), NavigationClearance.seed(0, 0))
    }

    @Test fun onlyGivingAnEdgeUpEntirelyNeedsASecondReading() {
        val bottom = NavigationClearance.Edges(bottom = 126)
        val side = NavigationClearance.Edges(right = 90)
        assertEquals(true, NavigationClearance.dropsToZero(bottom, NavigationClearance.Edges()))
        assertEquals(true, NavigationClearance.dropsToZero(bottom, NavigationClearance.Edges(right = 90)))
        assertEquals(true, NavigationClearance.dropsToZero(side, NavigationClearance.Edges()))
        // 变大、变小但不归零、横向挪动都不必等第二次。 Growing, shrinking without reaching zero, and sideways moves
        // all apply at once.
        assertEquals(false, NavigationClearance.dropsToZero(bottom, NavigationClearance.Edges(bottom = 48)))
        assertEquals(false, NavigationClearance.dropsToZero(bottom, NavigationClearance.Edges(bottom = 144)))
        assertEquals(false, NavigationClearance.dropsToZero(side, NavigationClearance.Edges(right = 120)))
        assertEquals(false, NavigationClearance.dropsToZero(NavigationClearance.Edges(), NavigationClearance.Edges()))
    }
}
