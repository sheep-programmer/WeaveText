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

    @Test fun resizeFollowsTheCornerDrag() {
        assertEquals(1f, FloatingGeometry.resizeScale(1f, 800, 600, 0f, 0f), 1e-4f)
        // 左上角向左上拖：宽 +10%、高 +10% → 1.1。 Top-left corner dragged up/left by 10% both ways.
        assertEquals(1.1f, FloatingGeometry.resizeScale(1f, 800, 600, -80f, -60f), 1e-4f)
        // 只拖宽度（向右 = 缩小）：取平均，按键保持比例。 Width only (right = smaller): averaged.
        assertEquals(0.9f, FloatingGeometry.resizeScale(1f, 800, 600, 160f, 0f), 1e-4f)
        // 右下角保持不动。 The bottom-right corner stays put.
        assertEquals(100 to 1300, FloatingGeometry.anchorBottomRight(1000, 2000, 900, 700))
    }

    @Test fun gripAndDockStayOutOfTheKeyArea() {
        val d = 2.625f
        val handleH = (22 * d).toInt()
        for (cardW in listOf((220 * d).toInt(), 810, 1026)) {
            val keys = FloatingGeometry.keyArea(cardW, handleH + 700, handleH)
            val grip = FloatingGeometry.gripBox(handleH, d)
            val dock = FloatingGeometry.dockBox(cardW, handleH, d)
            for (b in listOf(grip, dock)) {
                assertTrue("$b overlaps keys $keys", b.bottom <= keys.top)
                assertTrue(b.left >= 0 && b.right <= cardW)
            }
            // 手柄与停靠按钮之间留出拖动区。 Room to drag between the grip and the dock button.
            assertTrue(dock.left - grip.right >= 36 * d)
            // 手柄至少 44dp 宽，便于按住。 The grip is at least 44 dp wide.
            assertTrue(grip.width >= (44 * d).toInt())
        }
    }

    @Test fun scaleStaysWithinLimits() {
        val d = 2.625f
        // 基准卡片 810×700 px。 Base card 810 × 700 px.
        assertEquals(220 * d / 810, FloatingGeometry.clampScale(0.1f, w, h, 810, 700, d), 1e-4f)
        assertEquals(FloatingGeometry.MIN_SCALE, FloatingGeometry.clampScale(0.1f, 2400, h, 1200, 700, d), 1e-4f)
        assertEquals(1.2666f, FloatingGeometry.clampScale(5f, w, h, 810, 700, d), 1e-3f) // 95% 窗宽 / 95% of the width
        assertEquals(0.85f, FloatingGeometry.clampScale(0.85f, w, h, 810, 700, d), 1e-4f)
        // 横屏窗口矮：高度上限起作用。 Short landscape window: the height cap wins.
        assertEquals(1080 * 0.65f / 700, FloatingGeometry.clampScale(5f, 2400, 1080, 1200, 700, d), 1e-3f)
        // 最小 220 dp 宽。 At least 220 dp wide.
        assertEquals(220 * d / 700, FloatingGeometry.clampScale(0.1f, w, h, 700, 500, d), 1e-3f)
    }

    @Test fun storedScaleIsSanitised() {
        assertEquals(1f, FloatingGeometry.decodeScale(null), 0f)
        assertEquals(1f, FloatingGeometry.decodeScale("x"), 0f)
        assertEquals(FloatingGeometry.MAX_SCALE, FloatingGeometry.decodeScale("9"), 0f)
        assertEquals(0.8f, FloatingGeometry.decodeScale("0.800"), 1e-4f)
    }
}
