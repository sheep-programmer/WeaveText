package com.weavetext.ime.settings

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.ui.keyboard.HandInkAppearance
import com.weavetext.ime.ui.keyboard.HandInkPrefs
import com.weavetext.ime.ui.keyboard.HandInkStyle
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HandInkPreferencesTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("hand-ink-tests", Context.MODE_PRIVATE)

    @Before fun setup() { prefs.edit().clear().commit() }

    @Test fun defaultsAndUnknownOrCorruptValuesFallBackWithoutRewritingPrefs() {
        assertEquals(HandInkAppearance(), HandInkPrefs.read(prefs))
        prefs.edit().putString(HandInkPrefs.STYLE, "future-nib").putFloat(HandInkPrefs.WIDTH_DP, Float.NaN)
            .putString(HandInkPrefs.COLOR, "#GG1234").commit()
        assertEquals(HandInkAppearance(), HandInkPrefs.read(prefs))
        assertEquals("future-nib", prefs.getString(HandInkPrefs.STYLE, null))
        prefs.edit().putInt(HandInkPrefs.STYLE, 1).putString(HandInkPrefs.WIDTH_DP, "wide")
            .putBoolean(HandInkPrefs.COLOR, true).commit()
        assertEquals(HandInkAppearance(), HandInkPrefs.read(prefs))
    }

    @Test fun colorsAcceptOnlyThemeOrSixDigitRgbAndInvalidWritesKeepTheLastColor() {
        for (color in listOf("#000000", "#FFFFFF", "#00a1b2", " #3366cc ")) {
            assertTrue(color, HandInkPrefs.setColor(prefs, color))
            assertEquals(color.trim().uppercase(), HandInkPrefs.read(prefs).color)
        }
        for (color in listOf("", "#FFF", "#12345", "#1234567", "#FF123456", "123456", "#12GG56", "red")) {
            assertFalse(color, HandInkPrefs.setColor(prefs, color))
            assertNull(HandInkPrefs.parseColor(color))
            assertEquals("#3366CC", HandInkPrefs.read(prefs).color)
        }
        assertTrue(HandInkPrefs.setColor(prefs, "theme"))
        assertEquals(0xFF112233.toInt(), HandInkPrefs.resolveColor("theme", 0xFF112233.toInt()))
        assertEquals(0xFF00A1B2.toInt(), HandInkPrefs.resolveColor("#00a1b2", 0))
    }

    @Test fun toolsAndClampedWidthsRoundTripAndDoNotTouchUnrelatedKeys() {
        prefs.edit().putString("unrelated", "keep").commit()
        for (tool in HandInkStyle.entries) {
            HandInkPrefs.setStyle(prefs, tool)
            assertEquals(tool, HandInkPrefs.read(prefs).style)
        }
        for ((input, expected) in listOf(-1f to 2f, 2f to 2f, 6.5f to 6.5f, 12f to 12f, 100f to 12f,
            Float.NaN to 6f, Float.POSITIVE_INFINITY to 6f)) {
            HandInkPrefs.setWidthDp(prefs, input)
            assertEquals(expected, HandInkPrefs.read(prefs).widthDp, 0f)
        }
        assertEquals("keep", prefs.getString("unrelated", null))
    }
}
