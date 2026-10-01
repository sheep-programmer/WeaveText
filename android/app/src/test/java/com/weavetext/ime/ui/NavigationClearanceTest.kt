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
}
