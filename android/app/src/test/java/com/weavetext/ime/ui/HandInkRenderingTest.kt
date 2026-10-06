package com.weavetext.ime.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
class HandInkRenderingTest {
    private val trace = floatArrayOf(30f, 65f, 45f, 65f, 65f, 58f, 85f, 65f, 105f, 62f, 135f, 65f)

    private fun stroke(pad: HandPad, pressure: Float = 1f): FloatArray {
        pad.begin(trace[0], trace[1], 0L, pressure)
        for (i in 2 until trace.size step 2) pad.add(trace[i], trace[i + 1], i * 4L, pressure)
        return pad.end(60L)!!
    }

    private fun pixels(pad: HandPad, shapeOnly: Boolean = false, theme: Int = Color.BLACK): IntArray {
        val bitmap = Bitmap.createBitmap(220, 140, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (shapeOnly) { paint.color = Color.BLACK; canvas.drawPath(pad.ink, paint) }
        else pad.drawInk(canvas, paint, theme)
        return IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            bitmap.recycle()
        }
    }

    private fun coverage(pixels: IntArray): Long = pixels.sumOf { Color.alpha(it).toLong() }

    @Test fun fourToolsProduceDifferentOutlinesFromTheSameRecognitionSamples() {
        val pad = HandPad { 60L }
        val geometry = stroke(pad)
        val outlines = HandInkStyle.entries.map { style ->
            pad.style = style
            assertSame(geometry, pad.strokes.single())
            assertArrayEquals(trace, pad.strokes.single(), 0f)
            pixels(pad, shapeOnly = true)
        }
        for (i in outlines.indices) for (j in 0 until i) {
            assertFalse("tools ${HandInkStyle.entries[i]} and ${HandInkStyle.entries[j]} need different shapes",
                outlines[i].contentEquals(outlines[j]))
        }
    }

    @Test fun widthLimitsActuallyChangeExistingInkForEveryTool() {
        val pad = HandPad { 60L }
        val geometry = stroke(pad)
        for (style in HandInkStyle.entries) {
            pad.configure(HandInkAppearance(style, 2f))
            val thin = coverage(pixels(pad, shapeOnly = true))
            pad.widthDp = 12f
            val thick = coverage(pixels(pad, shapeOnly = true))
            assertTrue("$style: 12dp ink should be substantially wider", thick > thin * 2)
            assertSame(geometry, pad.strokes.single())
            assertArrayEquals(trace, geometry, 0f)
        }
    }

    @Test fun brushTailAndSmoothOutlineNeverEnterRecognition() {
        val pad = HandPad { 60L }
        val geometry = stroke(pad)
        val bounds = RectF()
        pad.ink.computeBounds(bounds, true)
        assertTrue("fast brush lift has a display-only tail", bounds.right > trace[trace.size - 2])
        assertArrayEquals(trace, geometry, 0f)
        pad.taper = false
        val untapered = pixels(pad, shapeOnly = true)
        pad.taper = true
        assertFalse(untapered.contentEquals(pixels(pad, shapeOnly = true)))
        assertSame(geometry, pad.strokes.single())
    }

    @Test fun stylusPressureChangesInkAndSurvivesRepeatedToolSwitches() {
        val light = HandPad { 60L }
        val heavy = HandPad { 60L }
        stroke(light, 0.2f)
        stroke(heavy, 1.8f)
        val original = pixels(heavy)
        assertTrue(coverage(original) > coverage(pixels(light)) * 1.5)
        assertArrayEquals(light.strokes.single(), heavy.strokes.single(), 0f)
        for (style in HandInkStyle.entries) heavy.style = style
        heavy.style = HandInkStyle.BRUSH
        assertArrayEquals("pressure samples survive restyling", original, pixels(heavy))
        assertEquals(1, heavy.strokes.size)
    }

    @Test fun omittedFingerPressureMatchesExplicitNeutralPressure() {
        val finger = HandPad { 60L }
        val neutral = HandPad { 60L }
        finger.begin(trace[0], trace[1], 0L)
        for (i in 2 until trace.size step 2) finger.add(trace[i], trace[i + 1], i * 4L)
        finger.end(60L)
        stroke(neutral, 1f)
        for (style in HandInkStyle.entries) {
            finger.style = style
            neutral.style = style
            assertArrayEquals(pixels(finger), pixels(neutral))
            assertArrayEquals(finger.strokes.single(), neutral.strokes.single(), 0f)
        }
    }

    @Test fun restylingDuringDrawingKeepsFinishedAndActiveStrokesAndUndoWorks() {
        val pad = HandPad { 100L }
        val first = stroke(pad, 0.4f)
        pad.begin(30f, 90f, 70L, 0.3f)
        pad.add(65f, 90f, 80L, 1.5f)
        pad.configure(HandInkAppearance(HandInkStyle.HIGHLIGHTER, 12f, "#123456"), 2f)
        assertTrue(pad.drawing)
        assertSame(first, pad.strokes.single())
        pad.add(130f, 95f, 90L, 0.6f)
        assertArrayEquals(floatArrayOf(30f, 90f, 65f, 90f, 130f, 95f), pad.end(100L), 0f)
        assertTrue(pad.undo())
        assertSame(first, pad.strokes.single())
        val beforeCancel = pixels(pad)
        pad.begin(20f, 110f, 105L, 2f)
        pad.add(50f, 110f, 110L, 2f)
        pad.cancel()
        assertArrayEquals(beforeCancel, pixels(pad))
    }

    @Test fun colorAndThemeChangesRedrawExistingInkWithToolAndFadeAlpha() {
        val pad = HandPad { 60L }
        val geometry = stroke(pad)
        val black = pixels(pad)
        val blue = pixels(pad, theme = Color.BLUE)
        assertFalse(black.contentEquals(blue))
        pad.color = "#ff3366"
        val custom = pixels(pad, theme = Color.BLUE)
        assertFalse(custom.contentEquals(blue))
        assertEquals(0xFFFF3366.toInt(), pad.inkColor(Color.GREEN))
        pad.style = HandInkStyle.HIGHLIGHTER
        val full = pad.inkColor(Color.GREEN)
        assertTrue(Color.alpha(full) in 1..254)
        assertEquals(0xFF3366, full and 0xFFFFFF)
        assertTrue(Color.alpha(pad.inkColor(Color.GREEN, 0.5f)) < Color.alpha(full))
        pad.color = "theme"
        assertEquals(Color.GREEN and 0xFFFFFF, pad.inkColor(Color.GREEN) and 0xFFFFFF)
        assertSame(geometry, pad.strokes.single())
    }

    @Test fun clearDuringAStrokeRetainsOnlyTheNextCharactersGeometry() {
        val pad = HandPad { 90L }
        stroke(pad)
        pad.begin(40f, 100f, 70L, 0.2f)
        pad.add(80f, 100f, 80L, 1.8f)
        pad.clear(fade = true)
        pad.style = HandInkStyle.PENCIL
        assertTrue(pad.drawing)
        assertTrue(pad.strokes.isEmpty())
        assertEquals(-1f, pad.fadeProgress(), 0f)
        assertArrayEquals(floatArrayOf(40f, 100f, 80f, 100f), pad.end(90L), 0f)
    }

    @Test fun fadingInkRestylesAndExpiresWithoutReappearingInRecognition() {
        var now = 60L
        val pad = HandPad { now }
        stroke(pad)
        pad.clear(fade = true)
        assertTrue(pad.strokes.isEmpty())
        assertFalse(pad.fading.isEmpty)
        val old = RectF().also { pad.fading.computeBounds(it, true) }
        pad.configure(HandInkAppearance(HandInkStyle.BALLPOINT, 12f, "#336699"))
        val changed = RectF().also { pad.fading.computeBounds(it, true) }
        assertNotEquals(old, changed)
        now += HandPad.FADE_MS
        assertEquals(-1f, pad.fadeProgress(), 0f)
        assertTrue(pad.fading.isEmpty)
        assertTrue(pad.ink.isEmpty)
    }

    @Test fun longStrokeBufferGrowthAndTinyMovesDoNotChangeAcceptedGeometry() {
        val pad = HandPad { 3000L }
        pad.begin(20f, 30f, 0L, Float.NaN)
        for (i in 1..600) {
            pad.add(20f + i * 3f, 30f, i * 4L, if (i % 2 == 0) 0.2f else 1.8f)
            pad.add(20.1f + i * 3f, 30.1f, i * 4L + 1, 1f) // Below recognition's fixed distance filter.
            if (i == 300) pad.style = HandInkStyle.PENCIL
        }
        val geometry = pad.end(3000L)!!
        assertEquals(601 * 2, geometry.size)
        for (i in 0..600) {
            assertEquals(20f + i * 3f, geometry[i * 2], 0f)
            assertEquals(30f, geometry[i * 2 + 1], 0f)
        }
        pad.style = HandInkStyle.BRUSH
        assertSame(geometry, pad.strokes.single())
        assertFalse(pad.ink.isEmpty)
    }
}
