package com.weavetext.ime.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.weavetext.ime.ui.keyboard.HandInkAppearance
import com.weavetext.ime.ui.keyboard.HandInkStyle
import com.weavetext.ime.ui.keyboard.HandPad
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HandInkRedoTest {
    private fun stroke(pad: HandPad, y: Float): FloatArray {
        pad.begin(20f, y, 0L, pressure = 0.2f)
        pad.add(50f, y + 5f, 10L, pressure = 1.8f)
        pad.add(80f, y, 20L, pressure = 0.6f)
        return pad.end(25L)!!
    }

    private fun pixels(pad: HandPad): IntArray {
        val bitmap = Bitmap.createBitmap(160, 140, Bitmap.Config.ARGB_8888)
        pad.drawInk(Canvas(bitmap), Paint(Paint.ANTI_ALIAS_FLAG), Color.BLACK)
        return IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            bitmap.recycle()
        }
    }

    @Test fun undoAndRedoRestoreStrokeOrderOriginalArraysAndPressureInk() {
        val pad = HandPad { 25L }
        val first = stroke(pad, 30f)
        val second = stroke(pad, 80f)
        val original = pixels(pad)
        assertFalse(pad.canRedo)
        assertFalse(pad.redo())
        assertTrue(pad.undo())
        assertTrue(pad.undo())
        assertTrue(pad.strokes.isEmpty())
        assertTrue(pad.canRedo)
        assertFalse(pad.undo())
        assertTrue(pad.redo())
        assertSame(first, pad.strokes.single())
        assertArrayEquals(floatArrayOf(20f, 30f, 50f, 35f, 80f, 30f), pad.strokes.single(), 0f)
        assertTrue(pad.canRedo)
        assertTrue(pad.redo())
        assertSame(first, pad.strokes[0])
        assertSame(second, pad.strokes[1])
        assertFalse(pad.canRedo)
        assertFalse(pad.redo())
        assertArrayEquals("display samples, including pressure, must survive", original, pixels(pad))
    }

    @Test fun restylingAndChangingColorOrWidthKeepsRedoAndUsesTheCurrentAppearance() {
        val pad = HandPad { 25L }
        val expected = HandPad { 25L }
        val geometry = stroke(pad, 60f)
        stroke(expected, 60f)
        assertTrue(pad.undo())
        for (tool in HandInkStyle.entries) {
            val appearance = HandInkAppearance(tool, 12f, "#00A1B2")
            pad.configure(appearance, 2f)
            expected.configure(appearance, 2f)
            assertTrue("appearance must not erase redo for $tool", pad.canRedo)
            assertTrue(pad.redo())
            assertSame(geometry, pad.strokes.single())
            assertArrayEquals("redo should render the original pressure samples using $tool", pixels(expected), pixels(pad))
            assertTrue(pad.undo())
        }
    }

    @Test fun canceledStrokeAndUnsuccessfulEndPreserveRedoWhileLiveDrawingCannotRedo() {
        val pad = HandPad { 25L }
        val original = stroke(pad, 50f)
        assertTrue(pad.undo())
        assertNull(pad.end())
        assertTrue(pad.canRedo)
        pad.begin(20f, 90f, 30L, pressure = 2f)
        pad.add(50f, 90f, 40L, pressure = 0.2f)
        assertFalse(pad.canRedo)
        assertFalse(pad.redo())
        assertTrue(pad.strokes.isEmpty())
        pad.cancel()
        assertTrue(pad.canRedo)
        assertTrue(pad.redo())
        assertSame(original, pad.strokes.single())
    }

    @Test fun onlyASuccessfullyEndedNewStrokeDiscardsTheRedoBranch() {
        val pad = HandPad { 60L }
        stroke(pad, 40f)
        assertTrue(pad.undo())
        pad.begin(20f, 90f, 30L)
        pad.cancel()
        assertTrue(pad.canRedo)
        pad.begin(20f, 90f, 40L)
        pad.add(50f, 95f, 50L)
        val replacement = pad.end(60L)!!
        assertFalse(pad.canRedo)
        assertFalse(pad.redo())
        assertSame(replacement, pad.strokes.single())
        assertArrayEquals(floatArrayOf(20f, 90f, 50f, 95f), replacement, 0f)
    }

    @Test fun clearDiscardsRedoEvenWhenEveryStrokeHasAlreadyBeenUndone() {
        for (fade in listOf(false, true)) {
            val pad = HandPad { 25L }
            stroke(pad, 50f)
            assertTrue(pad.undo())
            assertTrue(pad.canRedo)
            assertTrue(pad.strokes.isEmpty())
            pad.clear(fade)
            assertFalse(pad.canRedo)
            assertFalse(pad.redo())
            assertTrue(pad.ink.isEmpty)
        }
    }

    @Test fun clearKeepsTheLiveStrokeButDropsBothFinishedInkAndRedo() {
        val pad = HandPad { 60L }
        stroke(pad, 30f)
        stroke(pad, 60f)
        pad.undo()
        pad.begin(20f, 90f, 40L)
        pad.add(80f, 90f, 50L)
        pad.clear(fade = true)
        assertTrue(pad.drawing)
        assertTrue(pad.strokes.isEmpty())
        assertArrayEquals(floatArrayOf(20f, 90f, 80f, 90f), pad.end(60L), 0f)
        assertFalse(pad.canRedo)
        assertFalse(pad.redo())
    }

    @Test fun resetDiscardsRedoAndAnyLiveStroke() {
        val pad = HandPad { 25L }
        stroke(pad, 50f)
        pad.undo()
        pad.begin(20f, 90f, 30L)
        pad.reset()
        assertFalse(pad.drawing)
        assertFalse(pad.canRedo)
        assertFalse(pad.redo())
        assertTrue(pad.strokes.isEmpty())
        assertTrue(pad.ink.isEmpty)
        assertTrue(pad.fading.isEmpty)
    }

    @Test fun redoHistoryIsBoundedTo128StrokesWithoutReorderingOrCopyingGeometry() {
        val pad = HandPad { 25L }
        val originals = Array(130) { i ->
            pad.begin(20f + i, 60f, 0L)
            pad.end(25L)!!
        }
        repeat(130) { assertTrue(pad.undo()) }
        repeat(128) { i ->
            assertTrue(pad.redo())
            assertSame(originals[i], pad.strokes.last())
        }
        assertFalse(pad.canRedo)
        assertFalse(pad.redo())
        assertEquals(128, pad.strokes.size)
    }
}
