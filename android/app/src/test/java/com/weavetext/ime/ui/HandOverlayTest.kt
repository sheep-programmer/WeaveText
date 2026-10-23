package com.weavetext.ime.ui

import com.weavetext.ime.ui.keyboard.HandPad
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HandOverlayTest {
    private var now = 1000L
    private val pad = HandPad { now }.apply { overlayMode = true; characterPauseMs = 800 }
    private fun stroke(x: Float = 10f) {
        pad.begin(x, 10f, now)
        pad.add(x + 50f, 60f, now + 10)
        pad.end(now + 20)
        now += 30
    }

    @Test fun onlyCompletedStrokesCreateBoundariesAndRedoRestoresThem() {
        stroke(); stroke()
        assertArrayEquals(intArrayOf(2), pad.groupEnds)
        now += 800
        stroke()
        assertArrayEquals(intArrayOf(2, 3), pad.groupEnds)
        assertTrue(pad.undo())
        assertArrayEquals(intArrayOf(2), pad.groupEnds)
        assertTrue(pad.redo())
        assertArrayEquals(intArrayOf(2, 3), pad.groupEnds)
        assertTrue(pad.undo())
        stroke()
        assertArrayEquals("new ink after undo joins the remaining character", intArrayOf(3), pad.groupEnds)
        assertFalse(pad.canRedo)
    }

    @Test fun cancelPreservesTheBoundaryWhenRetryingTheFirstStrokeOfANewCharacter() {
        stroke()
        now += 800
        pad.begin(20f, 20f, now)
        pad.cancel(now)
        now += 50
        stroke()
        assertArrayEquals("retry keeps the boundary established before cancellation", intArrayOf(1, 2), pad.groupEnds)
    }

    @Test fun clearingDuringALiveStrokeRebasesTheRetainedCharacter() {
        repeat(3) { stroke(); now += 800 }
        pad.begin(10f, 10f, now)
        pad.clear(false)
        pad.end(now + 20)
        assertArrayEquals(intArrayOf(1), pad.groupEnds)
        now += 1000
        stroke()
        assertArrayEquals(intArrayOf(1, 2), pad.groupEnds)
        assertFalse(pad.overlayLimitReached)
    }

    @Test fun fifthCharacterIsRejectedWithoutChangingRetainedInk() {
        repeat(4) { stroke(); now += 800 }
        val ink = pad.strokes.toList()
        stroke()
        assertTrue(pad.overlayLimitReached)
        assertArrayEquals(intArrayOf(1, 2, 3, 4), pad.groupEnds)
        assertEquals(4, pad.strokes.size)
        ink.forEachIndexed { i, points -> assertSame(points, pad.strokes[i]) }
        assertFalse(pad.drawing)
        pad.clear(false)
        stroke()
        assertArrayEquals(intArrayOf(1), pad.groupEnds)
        assertFalse(pad.overlayLimitReached)
    }

    @Test fun characterStrokeLimitKeepsValidInkAndAllowsTheNextCharacter() {
        repeat(64) { stroke() }
        stroke()
        assertTrue(pad.overlayLimitReached)
        assertArrayEquals(intArrayOf(64), pad.groupEnds)
        now += 800
        stroke()
        assertArrayEquals(intArrayOf(64, 65), pad.groupEnds)
        assertFalse(pad.overlayLimitReached)
    }

    @Test fun switchingModesKeepsInkButDiscardsUnverifiableOldBoundaries() {
        stroke(); now += 800; stroke()
        assertArrayEquals(intArrayOf(1, 2), pad.groupEnds)
        pad.overlayMode = false
        assertTrue(pad.groupEnds.isEmpty())
        pad.overlayMode = true
        assertArrayEquals(intArrayOf(2), pad.groupEnds)
        pad.reset()
        assertTrue(pad.groupEnds.isEmpty())
        stroke()
        assertArrayEquals(intArrayOf(1), pad.groupEnds)
    }
}
