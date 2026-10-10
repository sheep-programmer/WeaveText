package com.weavetext.ime.ui

import com.weavetext.ime.ui.keyboard.HandwritingAreaGeometry
import com.weavetext.ime.ui.keyboard.HandwritingAreaMode
import org.junit.Assert.assertEquals
import org.junit.Test

class HandwritingAreaGeometryTest {
    @Test fun halfAndFullUseTheUsableWindowRatherThanTheDisplayOrAnExtractEditor() {
        assertEquals(360, HandwritingAreaGeometry.boardHeight(HandwritingAreaMode.KEYBOARD, 360, 1000, 1100))
        assertEquals(500, HandwritingAreaGeometry.boardHeight(HandwritingAreaMode.HALF, 360, 1000, 1100))
        assertEquals(1000, HandwritingAreaGeometry.boardHeight(HandwritingAreaMode.FULL, 360, 1000, 1100))
    }

    @Test fun aParentThatAlreadyAvoidsBarsOrHasASmallerViewportIsAHardCap() {
        assertEquals(700, HandwritingAreaGeometry.boardHeight(HandwritingAreaMode.FULL, 360, 1000, 700))
        assertEquals(450, HandwritingAreaGeometry.boardHeight(HandwritingAreaMode.HALF, 360, 1000, 450))
        assertEquals(200, HandwritingAreaGeometry.boardHeight(HandwritingAreaMode.FULL, 360, 1000, 200))
        assertEquals(0, HandwritingAreaGeometry.boardHeight(HandwritingAreaMode.FULL, 360, 1000, 0))
    }

    @Test fun halfScreenKeepsOrdinaryControlsWhenThereIsRoomAndUnknownModesAreStandard() {
        assertEquals(360, HandwritingAreaGeometry.boardHeight(HandwritingAreaMode.HALF, 360, 600, 600))
        assertEquals(HandwritingAreaMode.KEYBOARD, HandwritingAreaMode.from(null))
        assertEquals(HandwritingAreaMode.KEYBOARD, HandwritingAreaMode.from("unsupported"))
        for (mode in HandwritingAreaMode.entries) assertEquals(mode, HandwritingAreaMode.from(mode.key))
    }
}
