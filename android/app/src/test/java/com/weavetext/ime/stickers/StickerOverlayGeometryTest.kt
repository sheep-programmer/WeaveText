package com.weavetext.ime.stickers

import org.junit.Assert.*
import org.junit.Test

class StickerOverlayGeometryTest {
    @Test fun allFourCornersResizeIndependentlyAndKeepTheOppositeCornerAnchored() {
        val start=StickerOverlayGeometry.Box(200,300,330,360)
        val expected=listOf(
            StickerOverlayGeometry.Box(250,340,280,320),StickerOverlayGeometry.Box(200,340,380,320),
            StickerOverlayGeometry.Box(250,300,280,400),StickerOverlayGeometry.Box(200,300,380,400))
        StickerOverlayGeometry.Corner.entries.forEachIndexed { index,corner ->
            assertEquals(expected[index],StickerOverlayGeometry.resize(start,corner,50,40,0,1000,100,1000,240,190))
        }
    }
    @Test fun resizedBagRemainsAboveKeyboardAndCannotShrinkPastUsableControls() {
        val start=StickerOverlayGeometry.Box(200,300,330,300)
        val large=StickerOverlayGeometry.resize(start,StickerOverlayGeometry.Corner.BOTTOM_RIGHT,900,900,10,900,60,650,240,190)
        assertEquals(900,large.x+large.width);assertEquals(650,large.y+large.height)
        val small=StickerOverlayGeometry.resize(start,StickerOverlayGeometry.Corner.BOTTOM_RIGHT,-900,-900,10,900,60,650,240,190)
        assertEquals(240,small.width);assertEquals(190,small.height)
        assertEquals(200,small.x);assertEquals(300,small.y)
    }
    @Test fun measuredKeyboardTopLeavesGapWithoutAddingStatusBarOffset() {
        val box=StickerOverlayGeometry.place(720,1600,0,0,63,126,981,577,630,619,640,14)
        assertEquals(143,box.x);assertEquals(337,box.y)
        assertEquals(967,box.y+box.height);assertTrue(box.y+box.height<981)
    }
    @Test fun landscapeAndSideNavigationKeepTheWholeWindowWithinUsableArea() {
        val box=StickerOverlayGeometry.place(1600,720,0,126,42,0,400,577,630,1500,900,14)
        assertEquals(897,box.x);assertEquals(56,box.y)
        assertEquals(330,box.height);assertEquals(1474,box.x+box.width)
        assertEquals(386,box.y+box.height)
    }
    @Test fun validPositionIsKeptInsteadOfRaisingTheBagUnnecessarily() {
        val box=StickerOverlayGeometry.place(720,1600,0,0,63,126,981,84,84,619,640,14)
        assertEquals(StickerOverlayGeometry.Box(619,640,84,84),box)
        val small=StickerOverlayGeometry.place(250,400,10,10,30,30,null,577,630,300,500,8)
        assertEquals(230,small.width);assertTrue(small.y>=38 && small.y+small.height<=362)
    }
}
