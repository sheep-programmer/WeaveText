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

    @Test fun pauseCommitsOnlyForSingleCharAndAutoCommitOverlay() {
        assertTrue(HandPad.pauseCommits(lineMode = false, overlayMode = false, autoCommit = true))
        assertFalse(HandPad.pauseCommits(lineMode = false, overlayMode = false, autoCommit = false))
        assertFalse("连写只按候选确认", HandPad.pauseCommits(lineMode = true, overlayMode = false, autoCommit = true))
        assertTrue("叠写开了自动上屏就按停顿上屏", HandPad.pauseCommits(lineMode = true, overlayMode = true, autoCommit = true))
        assertFalse("叠写关闭自动上屏时等用户确认", HandPad.pauseCommits(lineMode = true, overlayMode = true, autoCommit = false))
    }

    @Test fun retiredCharacterFadesOnItsOwnClockAndLeavesNoRecognitionInk() {
        stroke(); stroke()
        pad.retire(fade = true)
        assertTrue(pad.strokes.isEmpty())
        assertTrue(pad.groupEnds.isEmpty())
        assertEquals(listOf(1f), pad.ghostAlphas(now))
        assertEquals(listOf(0.5f), pad.ghostAlphas(now + 750))
        assertTrue(pad.ghostAlphas(now + 1500).isEmpty())
    }

    @Test fun secondCharacterOverlapsTheFadingFirstAndEachFadesAtItsOwnTime() {
        stroke()
        pad.retire(fade = true)
        val firstAt = now
        now += 700
        stroke()
        pad.retire(fade = true)
        // 第二字刚上屏：第一字已经淡到一半，第二字从满不透明开始。 The second char starts opaque while the first is half gone.
        // 第一字的年龄是 now 减去它上屏的时刻（落笔会推进时钟，不能写死 700）。
        // The first char's age is now minus its commit time; the stroke advances the clock, so 700 is not a constant.
        assertEquals(listOf(1f - (now - firstAt) / 1500f, 1f), pad.ghostAlphas(now))
        assertEquals(2, pad.ghostAlphas(now).size)
        assertTrue(firstAt < now)
        assertEquals(1, pad.ghostAlphas(firstAt + 1500).size)
    }

    @Test fun nextCharacterAfterRetireStartsAtGroupZeroAndNoFadeAddsNoGhost() {
        stroke()
        pad.retire(fade = true)
        stroke()
        assertArrayEquals(intArrayOf(1), pad.groupEnds)
        pad.retire(fade = false)
        assertEquals("without animations the ink is cleared, not turned into a ghost", 1, pad.ghostAlphas(now).size)
        pad.reset()
        assertTrue(pad.ghostAlphas(now).isEmpty())
    }
}
