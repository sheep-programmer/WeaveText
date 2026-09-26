package com.weavetext.ime.ui

import com.weavetext.ime.ui.keyboard.FloatingGeometry
import com.weavetext.ime.ui.keyboard.FloatingGeometry.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 悬浮键盘几何：宽度、放置、夹紧、比例往返、insets。 Floating keyboard geometry. */
class FloatingGeometryTest {
    private val w = 1080
    private val h = 2400
    private val minTop = 63
    private val maxBottom = h - 63

    @Test fun cardWidth() {
        assertEquals(810, FloatingGeometry.cardWidth(w, landscape = false, density = 2.625f))
        // 横屏：一半宽，但不超过 480 dp。 Landscape: half width, capped at 480 dp.
        assertEquals(1200, FloatingGeometry.cardWidth(2400, landscape = true, density = 2.5f))
        assertEquals(1000, FloatingGeometry.cardWidth(2000, landscape = true, density = 3f))
    }

    @Test fun defaultPlacementIsCentredAtTheBottom() {
        val (fx, fy) = FloatingGeometry.decode(null)
        val b = FloatingGeometry.place(fx, fy, w, 810, 700, minTop, maxBottom)
        assertEquals(135, b.left)
        assertEquals(maxBottom, b.bottom)
        assertEquals(810, b.width)
    }

    @Test fun clampKeepsCardOnScreen() {
        val b = FloatingGeometry.clamp(-500, -500, w, 810, 700, minTop, maxBottom)
        assertEquals(Box(0, minTop, 810, minTop + 700), b)
        val c = FloatingGeometry.clamp(5000, 5000, w, 810, 700, minTop, maxBottom)
        assertEquals(w, c.right)
        assertEquals(maxBottom, c.bottom)
    }

    @Test fun fractionsRoundTripAndSurviveRotation() {
        val card = Box(200, 900, 1010, 1600)
        val f = FloatingGeometry.fractions(card, w, minTop, maxBottom)
        val back = FloatingGeometry.place(f.first, f.second, w, 810, 700, minTop, maxBottom)
        assertEquals(card, back)
        assertEquals(f, FloatingGeometry.decode(FloatingGeometry.encode(f)).let { (x, y) ->
            // 编码保留 4 位小数。 Encoding keeps 4 decimals.
            assertEquals(f.first, x, 1e-4f); assertEquals(f.second, y, 1e-4f); f
        })
        // 换到更小的窗口仍在屏幕内。 A smaller window still keeps the card on screen.
        val small = FloatingGeometry.place(f.first, f.second, 720, 540, 500, minTop, 1500)
        assertTrue(small.left >= 0 && small.right <= 720 && small.top >= minTop && small.bottom <= 1500)
    }

    @Test fun insetsKeepAppFullHeight() {
        val card = Box(135, 1200, 945, 1900)
        val i = FloatingGeometry.insets(h, card)
        assertEquals(h, i.contentTop)
        assertEquals(h, i.visibleTop)
        assertEquals(card, i.touchable)
    }

    @Test fun decodeRejectsGarbage() {
        assertEquals(FloatingGeometry.DEFAULT, FloatingGeometry.decode("abc"))
        assertEquals(FloatingGeometry.DEFAULT, FloatingGeometry.decode("0.3"))
        assertEquals(1f to 0f, FloatingGeometry.decode("7,-2"))
    }
}
