package com.weavetext.ime.ui

import com.weavetext.ime.ui.keyboard.FloatingGeometry
import com.weavetext.ime.ui.keyboard.FloatingGeometry.Box
import com.weavetext.ime.ui.keyboard.FloatingResizeAccessibility
import com.weavetext.ime.ui.keyboard.FloatingResizeController
import com.weavetext.ime.ui.keyboard.FloatingResizeCorner
import com.weavetext.ime.ui.keyboard.FloatingResizePolicy
import org.junit.Assert.*
import org.junit.Test

/** Geometry/gesture tests only: these do not measure on-device usability or recognition accuracy. */
class FloatingResizeTest {
    private val bounds = Box(20, 50, 1020, 1550)
    private val start = Box(220, 400, 820, 800)

    private fun resize(
        corner: FloatingResizeCorner, dx: Float, dy: Float, box: Box = start, area: Box = bounds,
        fixed: Int = 0, allowSlideAtEdge: Boolean = false,
    ) = FloatingGeometry.resizeFromCorner(box, 1f, corner, dx, dy, area, 1f, fixed, allowSlideAtEdge)

    private fun assertAnchored(corner: FloatingResizeCorner, before: Box, after: Box, area: Box = bounds) {
        assertEquals(corner.name, FloatingGeometry.oppositeAnchor(before, corner), FloatingGeometry.oppositeAnchor(after, corner))
        assertTrue("$corner: $after outside $area", FloatingGeometry.fits(after, area))
    }

    @Test fun allFourCornersGrowAndShrinkWithTheirOwnSigns() {
        for (corner in FloatingResizeCorner.entries) {
            val grow = resize(corner, corner.horizontalSign * 60f, corner.verticalSign * 40f)
            assertEquals(1.1f, grow.scale, 1e-5f)
            assertEquals(660, grow.box.width)
            assertEquals(440, grow.box.height)
            assertAnchored(corner, start, grow.box)
            val shrink = resize(corner, corner.horizontalSign * -60f, corner.verticalSign * -40f)
            assertEquals(0.9f, shrink.scale, 1e-5f)
            assertEquals(540, shrink.box.width)
            assertEquals(360, shrink.box.height)
            assertAnchored(corner, start, shrink.box)
        }
    }

    @Test fun zeroDeltaDoesNotMoveAnyCornerOrChangeItsSize() {
        for (corner in FloatingResizeCorner.entries) {
            val result = resize(corner, 0f, 0f)
            assertEquals(start, result.box)
            assertEquals(1f, result.scale, 0f)
        }
    }

    @Test fun horizontalAndVerticalOnlyDragsPreserveAspect() {
        for (corner in FloatingResizeCorner.entries) {
            val horizontal = resize(corner, corner.horizontalSign * 120f, 0f)
            val vertical = resize(corner, 0f, corner.verticalSign * 80f)
            assertEquals(horizontal.box, vertical.box)
            assertEquals(1.1f, horizontal.scale, 1e-5f)
        }
    }

    @Test fun hugeOutwardDragsAtEveryScreenEdgeKeepTheOppositeCornerFixed() {
        val cards = listOf(
            Box(20, 50, 620, 450), Box(420, 50, 1020, 450),
            Box(20, 1150, 620, 1550), Box(420, 1150, 1020, 1550), start,
        )
        for (card in cards) for (corner in FloatingResizeCorner.entries) {
            val result = resize(corner, corner.horizontalSign * 10000f, corner.verticalSign * 10000f, card)
            assertAnchored(corner, card, result.box)
            assertTrue(result.box.width >= card.width)
            assertTrue(result.box.height >= card.height)
        }
    }

    @Test fun integerRoundedMeasuredSizesAreBoundedBeforeTheyAreAnchored() {
        for (corner in FloatingResizeCorner.entries) {
            val result = FloatingGeometry.anchorOpposite(start, corner, 10000, 10000, bounds)
            assertAnchored(corner, start, result)
            assertTrue(result.width > 0 && result.height > 0)
            val rounded = FloatingGeometry.anchorOpposite(start, corner, 661, 431, bounds)
            assertAnchored(corner, start, rounded)
            assertEquals(661, rounded.width)
            assertEquals(431, rounded.height)
        }
    }

    @Test fun anOutwardDragAtTheActiveCornerBoundaryDoesNotRelocateTheCard() {
        val area = Box(0, 0, 600, 400)
        for (corner in FloatingResizeCorner.entries) {
            assertEquals(area, resize(corner, corner.horizontalSign * 300f, corner.verticalSign * 200f, area, area).box)
        }
    }

    @Test fun strictRemainsTheDefaultForBottomAlignedOutwardDrags() {
        val card = Box(220, 1150, 820, bounds.bottom)
        for (corner in listOf(FloatingResizeCorner.BOTTOM_LEFT, FloatingResizeCorner.BOTTOM_RIGHT)) {
            val strict = resize(corner, corner.horizontalSign * 60f, 40f, card)
            assertEquals(card, strict.box)
            assertEquals(1f, strict.scale, 0f)
            val controller = FloatingResizeController()
            assertTrue(controller.begin(corner, card, 1f, bounds, 1f, 0f, 0f))
            assertEquals(strict, controller.update(corner.horizontalSign * 60f, 40f, bounds))
        }
    }

    @Test fun bottomAlignedCornersGrowBySlidingUpOnlyWhenOptedIn() {
        val card = Box(220, 1150, 820, bounds.bottom)
        for (corner in listOf(FloatingResizeCorner.BOTTOM_LEFT, FloatingResizeCorner.BOTTOM_RIGHT)) {
            val result = resize(corner, corner.horizontalSign * 60f, 40f, card, allowSlideAtEdge = true)
            assertEquals(1.1f, result.scale, 1e-5f)
            assertEquals(660, result.box.width)
            assertEquals(440, result.box.height)
            assertEquals(bounds.bottom, result.box.bottom)
            assertEquals(card.top - 40, result.box.top) // Exactly the overflow, no extra translation.
            assertEquals(FloatingGeometry.oppositeAnchor(card, corner).first,
                FloatingGeometry.oppositeAnchor(result.box, corner).first)
            assertTrue(FloatingGeometry.fits(result.box, bounds))
        }
    }

    @Test fun slideModeKeepsTheOppositeAnchorWheneverThereIsRoom() {
        for (corner in FloatingResizeCorner.entries) {
            val dx = corner.horizontalSign * 60f
            val dy = corner.verticalSign * 40f
            val strict = resize(corner, dx, dy)
            val sliding = resize(corner, dx, dy, allowSlideAtEdge = true)
            assertEquals(strict, sliding)
            assertAnchored(corner, start, sliding.box)
        }
    }

    @Test fun allFourCornersCanGrowAtTheirCorrespondingWindowEdgesByMinimalTranslation() {
        for (corner in FloatingResizeCorner.entries) {
            val left = if (corner.horizontalSign < 0) bounds.left else bounds.right - 600
            val top = if (corner.verticalSign < 0) bounds.top else bounds.bottom - 400
            val card = Box(left, top, left + 600, top + 400)
            val result = resize(corner, corner.horizontalSign * 60f, corner.verticalSign * 40f,
                card, allowSlideAtEdge = true)
            val expectedLeft = if (corner.horizontalSign < 0) bounds.left else bounds.right - 660
            val expectedTop = if (corner.verticalSign < 0) bounds.top else bounds.bottom - 440
            assertEquals(Box(expectedLeft, expectedTop, expectedLeft + 660, expectedTop + 440), result.box)
            assertEquals(1.1f, result.scale, 1e-5f)
            assertTrue(FloatingGeometry.fits(result.box, bounds))
        }
    }

    @Test fun slideModeDoesNotJumpAtDownAndStillAnchorsInwardDrags() {
        val card = Box(220, 1150, 820, bounds.bottom)
        for (corner in FloatingResizeCorner.entries) {
            val down = resize(corner, 0f, 0f, card, fixed = 96, allowSlideAtEdge = true)
            assertEquals(card, down.box)
            assertEquals(1f, down.scale, 0f)
            val shrink = resize(corner, corner.horizontalSign * -60f, corner.verticalSign * -30.4f,
                card, fixed = 96, allowSlideAtEdge = true)
            assertEquals(0.9f, shrink.scale, 1e-5f)
            assertEquals(540, shrink.box.width)
            assertEquals(370, shrink.box.height)
            assertAnchored(corner, card, shrink.box)
        }
    }

    @Test fun slidingStillRespectsNominalWholeWindowWidthAndScaleCaps() {
        val area = Box(20, 50, 620, 1550)
        val card = Box(120, 1402, 620, 1550) // 500 x (48 fixed + 100 scalable).
        val result = resize(FloatingResizeCorner.BOTTOM_RIGHT, 10000f, 10000f, card, area,
            fixed = 48, allowSlideAtEdge = true)
        assertEquals(1.14f, result.scale, 1e-5f) // 95% of the whole 600px window, not the anchor's 500px.
        assertEquals(570, result.box.width)
        assertEquals(162, result.box.height)
        assertTrue(result.scale <= FloatingGeometry.MAX_SCALE)
        assertTrue(FloatingGeometry.fits(result.box, area))
        assertEquals(area.right, result.box.right)
        assertEquals(area.bottom, result.box.bottom)
    }

    @Test fun slidingStillRespectsTheWholeShortLandscapeHeightIncludingFixedBars() {
        val area = Box(30, 24, 1230, 324)
        val card = Box(930, 144, 1230, 324)
        val result = resize(FloatingResizeCorner.BOTTOM_RIGHT, 10000f, 10000f, card, area,
            fixed = 48, allowSlideAtEdge = true)
        assertEquals((195f - 48) / (180 - 48), result.scale, 1e-5f)
        assertEquals(195, result.box.height)
        assertEquals(area.bottom, result.box.bottom)
        assertTrue(FloatingGeometry.fits(result.box, area))
    }

    @Test fun measuredSlideUsesWholeBoundsRatherThanClippingToTheInitialAnchorSpace() {
        val card = Box(220, 1150, 820, bounds.bottom)
        val corner = FloatingResizeCorner.BOTTOM_RIGHT
        val strict = FloatingGeometry.anchorOpposite(card, corner, 661, 441, bounds)
        assertEquals(400, strict.height)
        val sliding = FloatingGeometry.anchorOpposite(card, corner, 661, 441, bounds, allowSlideAtEdge = true)
        assertEquals(Box(220, 1109, 881, 1550), sliding)
        assertEquals(bounds, FloatingGeometry.anchorOpposite(card, corner, 10000, 10000, bounds, allowSlideAtEdge = true))

        val controller = FloatingResizeController()
        controller.begin(corner, card, 1f, bounds, 1f, 0f, 0f, allowSlideAtEdge = true)
        controller.update(60f, 40f, bounds)
        val measured = controller.anchorMeasured(661, 441, bounds)!!
        assertEquals(sliding, measured.box)
        assertEquals(1.1f, measured.scale, 1e-5f)
        assertEquals(measured, controller.anchorMeasured(661, 441, bounds))
        assertEquals(measured, controller.finish())
    }

    @Test fun slidingControllerUsesTheOriginalDownSnapshotAcrossReversalsAndCancel() {
        val card = Box(220, 1150, 820, bounds.bottom)
        val controller = FloatingResizeController()
        controller.begin(FloatingResizeCorner.BOTTOM_RIGHT, card, 1f, bounds, 1f, 10f, 20f, allowSlideAtEdge = true)
        assertEquals(card, controller.current?.box)
        val first = controller.update(70f, 60f, bounds)!!
        assertTrue(first.box.top < card.top)
        controller.anchorMeasured(661, 441, bounds)
        assertEquals(first, controller.update(70f, 60f, bounds))
        assertEquals(card, controller.update(10f, 20f, bounds)?.box)
        assertEquals(first, controller.update(70f, 60f, bounds))
        assertEquals(card, controller.startBox)
        assertEquals(card, controller.cancel()?.box)
        assertFalse(controller.active)
        assertNull(controller.finish())
    }

    @Test fun slidingControllerStillEndsTheSessionWhenUsableBoundsChange() {
        val card = Box(220, 1150, 820, bounds.bottom)
        val controller = FloatingResizeController()
        controller.begin(FloatingResizeCorner.BOTTOM_RIGHT, card, 1f, bounds, 1f, 0f, 0f, allowSlideAtEdge = true)
        controller.update(60f, 40f, bounds)
        assertNull(controller.anchorMeasured(660, 440, Box(0, 0, 1500, 700)))
        assertFalse(controller.active)
        assertNull(controller.finish())
    }

    @Test fun minimumWidthYieldsToTheStartAnchorInTinyWindows() {
        val area = Box(3, 20, 123, 110)
        val card = Box(13, 30, 113, 90)
        for (corner in FloatingResizeCorner.entries) {
            val result = resize(corner, corner.horizontalSign * -10000f, corner.verticalSign * -10000f, card, area)
            assertAnchored(corner, card, result.box, area)
            assertEquals(card, result.box) // Cannot force an existing sub-220dp card to grow on DOWN.
        }
    }

    @Test fun landscapeHeightCapsGrowthWithoutMovingAnAnchor() {
        val area = Box(30, 24, 1230, 324)
        val card = Box(200, 80, 500, 180)
        for (corner in FloatingResizeCorner.entries) {
            val result = resize(corner, corner.horizontalSign * 10000f, corner.verticalSign * 10000f, card, area)
            assertAnchored(corner, card, result.box, area)
            assertEquals(130, result.box.height) // MAX_SCALE=1.3 is tighter than the 195px height cap.
        }
        val high = Box(200, 100, 800, 280)
        val capped = resize(FloatingResizeCorner.TOP_LEFT, -10000f, -10000f, high, area)
        assertTrue(capped.box.height <= 195)
        assertAnchored(FloatingResizeCorner.TOP_LEFT, high, capped.box, area)
    }

    @Test fun unscaledControlBarsAreNotIncludedInTheDragHeightRatio() {
        for (corner in FloatingResizeCorner.entries) {
            val result = resize(corner, corner.horizontalSign * 60f, corner.verticalSign * 30.4f, fixed = 96)
            assertEquals(1.1f, result.scale, 1e-5f)
            assertEquals(660, result.box.width)
            assertEquals(430, result.box.height) // 96 + round(304 * 1.1), rather than 400 * 1.1.
            assertAnchored(corner, start, result.box)
        }
    }

    @Test fun legacySizeAboveNominalCapsDoesNotJumpWhenTheGestureBegins() {
        val area = Box(0, 0, 300, 200)
        for (corner in FloatingResizeCorner.entries) {
            val result = FloatingGeometry.resizeFromCorner(area, 0.4f, corner, 0f, 0f, area, 3f)
            assertEquals(area, result.box)
            assertEquals(0.4f, result.scale, 0f)
        }
    }

    @Test fun fourCornerTargetsAndCloseAreSeparateFromTheBoard() {
        val d = 2.5f
        val cardW = 550
        val cardH = 900
        val barH = 120
        val board = Box(0, barH, cardW, cardH - barH)
        val close = FloatingGeometry.closeBox(cardW, barH, d)
        assertEquals(120, close.width)
        assertEquals(120, close.height)
        for (corner in FloatingResizeCorner.entries) {
            val grip = FloatingGeometry.gripBox(corner, cardW, cardH, barH, d)
            assertEquals(120, grip.width)
            assertEquals(120, grip.height)
            assertFalse(overlaps(grip, board))
            assertFalse(overlaps(grip, close))
            assertTrue(FloatingGeometry.fits(grip, Box(0, 0, cardW, cardH)))
        }
        assertFalse(overlaps(close, board))
        assertEquals("关闭悬浮键盘，停靠到底部", FloatingResizeAccessibility.CLOSE_DESCRIPTION)
        assertEquals(4, FloatingResizeCorner.entries.map { it.contentDescription }.toSet().size)
    }

    @Test fun closeAndCornerColumnsAreDisjointAtTheMinimumSuggestedWidth() {
        val close = FloatingGeometry.closeBox(144, 48, 1f)
        assertEquals(Box(48, 0, 96, 48), close)
        for (corner in FloatingResizeCorner.entries) {
            assertFalse(overlaps(close, FloatingGeometry.gripBox(corner, 144, 200, 48, 1f)))
        }
    }

    @Test fun closeTargetRemainsVisibleInItsSeparateRowOnTinyCards() {
        for (width in listOf(1, 30, 48, 100, 143)) {
            val close = FloatingGeometry.closeBox(width, 48, 1f)
            assertEquals(minOf(width, 48), close.width)
            assertEquals(48, close.height)
            assertTrue(FloatingGeometry.fits(close, Box(0, 0, width, 48)))
        }
    }

    @Test fun hitTestingOnlyClaimsEnabledCornersAndNeverClaimsTheBoardCentre() {
        val policy = FloatingResizePolicy(setOf(FloatingResizeCorner.BOTTOM_RIGHT))
        assertNull(FloatingGeometry.hitCorner(1f, 1f, 300, 200, 1f, policy))
        assertEquals(FloatingResizeCorner.BOTTOM_RIGHT, FloatingGeometry.hitCorner(299f, 199f, 300, 200, 1f, policy))
        assertNull(FloatingGeometry.hitCorner(150f, 100f, 300, 200, 1f))
        assertNull(FloatingGeometry.hitCorner(300f, 200f, 300, 200, 1f))
        assertNull(FloatingGeometry.hitCorner(Float.NaN, 1f, 300, 200, 1f))
    }

    @Test fun tinyTargetsUseTheNearestEnabledCornerAndStayInsideTheCard() {
        for ((x, y, corner) in listOf(
            Triple(1f, 1f, FloatingResizeCorner.TOP_LEFT), Triple(29f, 1f, FloatingResizeCorner.TOP_RIGHT),
            Triple(1f, 19f, FloatingResizeCorner.BOTTOM_LEFT), Triple(29f, 19f, FloatingResizeCorner.BOTTOM_RIGHT),
        )) assertEquals(corner, FloatingGeometry.hitCorner(x, y, 30, 20, 1f))
        FloatingGeometry.cornerTargets(30, 20, 1f).values.forEach {
            assertTrue(FloatingGeometry.fits(it, Box(0, 0, 30, 20)))
        }
        assertNull(FloatingGeometry.hitCorner(0f, 0f, 0, 0, 1f))
    }

    @Test fun bitMasksCoverEveryNonemptySubset() {
        assertEquals(15, FloatingResizePolicy.DEFAULT_ALL)
        assertEquals(15, FloatingResizePolicy().cornerMask)
        for (mask in 1..15) {
            val policy = FloatingResizePolicy.fromMask(mask)
            assertEquals(mask, policy.cornerMask)
            for (corner in FloatingResizeCorner.entries) assertEquals(mask and corner.bit != 0, policy.allows(corner))
        }
        assertEquals(1, FloatingResizePolicy.fromMask(17).cornerMask)
    }

    @Test(expected = IllegalArgumentException::class) fun emptyPolicyIsRejected() { FloatingResizePolicy(emptySet()) }
    @Test(expected = IllegalArgumentException::class) fun zeroMaskIsRejected() { FloatingResizePolicy.fromMask(0) }

    @Test fun policiesCopyTheInputAndDoNotDisableTheLastCorner() {
        val source = mutableSetOf(FloatingResizeCorner.TOP_LEFT)
        val policy = FloatingResizePolicy(source)
        source.clear()
        assertEquals(1, policy.cornerMask)
        assertEquals(policy, policy.withCorner(FloatingResizeCorner.TOP_LEFT, false))
        assertEquals(9, policy.withCorner(FloatingResizeCorner.BOTTOM_RIGHT, true).cornerMask)
    }

    @Test fun controllerUsesTheDownSnapshotAcrossRepeatedMovesAndPointerReversals() {
        for (corner in FloatingResizeCorner.entries) {
            val c = FloatingResizeController()
            assertTrue(c.begin(corner, start, 1f, bounds, 1f, 100f, 200f))
            assertEquals(start, c.startBox)
            val x = 100f + corner.horizontalSign * 60
            val y = 200f + corner.verticalSign * 40
            val first = c.update(x, y, bounds)
            assertEquals(first, c.update(x, y, bounds))
            assertEquals(first, c.update(x, y, bounds))
            assertEquals(start, c.update(100f, 200f, bounds)?.box)
            c.update(x, y, bounds)
            assertEquals(first, c.finish())
            assertFalse(c.active)
            assertNull(c.startBox)
            assertNull(c.finish())
        }
    }

    @Test fun controllerOverscrollRemainsBoundedAndReturningToDownRestoresTheStart() {
        val c = FloatingResizeController()
        c.begin(FloatingResizeCorner.TOP_LEFT, start, 1f, bounds, 1f, 10f, 10f)
        val edge = c.update(-10000f, -10000f, bounds)!!
        assertAnchored(FloatingResizeCorner.TOP_LEFT, start, edge.box)
        assertEquals(edge, c.update(-20000f, -20000f, bounds))
        assertEquals(start, c.update(10f, 10f, bounds)?.box)
    }

    @Test fun controllerAnchorsMeasuredSizesAndStillUsesTheOriginalDownSnapshot() {
        for (corner in FloatingResizeCorner.entries) {
            val c = FloatingResizeController()
            c.begin(corner, start, 1f, bounds, 1f, 0f, 0f)
            c.update(corner.horizontalSign * 60f, corner.verticalSign * 40f, bounds)
            val measured = c.anchorMeasured(661, 441, bounds)!!
            assertAnchored(corner, start, measured.box)
            assertEquals(1.1f, measured.scale, 1e-5f)
            assertEquals(measured, c.anchorMeasured(0, 0, bounds))
            assertEquals(start, c.update(0f, 0f, bounds)?.box)
            assertEquals(start, c.startBox)
        }
    }

    @Test fun controllerRejectsDisabledCornersAndUnmeasuredOrUnfittedCards() {
        val c = FloatingResizeController()
        val policy = FloatingResizePolicy(setOf(FloatingResizeCorner.BOTTOM_RIGHT))
        assertFalse(c.begin(FloatingResizeCorner.TOP_LEFT, start, 1f, bounds, 1f, 0f, 0f, policy))
        assertFalse(c.begin(FloatingResizeCorner.TOP_LEFT, Box(0, 0, 0, 0), 1f, bounds, 1f, 0f, 0f))
        assertFalse(c.begin(FloatingResizeCorner.TOP_LEFT, Box(0, 0, 2000, 2000), 1f, bounds, 1f, 0f, 0f))
        assertFalse(c.begin(FloatingResizeCorner.TOP_LEFT, start, Float.NaN, bounds, 1f, 0f, 0f))
        assertFalse(c.active)
    }

    @Test fun boundsChangeEndsTheSessionBeforeApplyingAnOldAnchor() {
        val c = FloatingResizeController()
        c.begin(FloatingResizeCorner.TOP_LEFT, start, 1f, bounds, 1f, 0f, 0f)
        assertNull(c.update(-100f, -100f, Box(0, 0, 1500, 700)))
        assertFalse(c.active)
        assertNull(c.finish())
    }

    @Test fun cancelRestoresTheStartAndInvalidPointerSamplesAreIgnored() {
        val c = FloatingResizeController()
        c.begin(FloatingResizeCorner.TOP_LEFT, start, 1f, bounds, 1f, 0f, 0f)
        val moved = c.update(-60f, -40f, bounds)
        assertEquals(moved, c.update(Float.POSITIVE_INFINITY, Float.NaN, bounds))
        assertEquals(start, c.cancel()?.box)
        assertFalse(c.active)
        assertNull(c.finish())
    }

    private fun overlaps(a: Box, b: Box) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
}
