package com.weavetext.ime.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 繁体地区字形：默认标准，点击按 标准 → 台湾 → 香港 轮换。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TraditionalRegionTest {
    private val prefs get() = WeavePrefs.of(ApplicationProvider.getApplicationContext<Application>())

    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun defaultsToStandardAndCyclesStandardTaiwanHongKong() {
        assertEquals("standard", WeavePrefs.traditionalRegion(prefs))
        assertEquals("taiwan", WeavePrefs.nextTraditionalRegion("standard"))
        assertEquals("hongkong", WeavePrefs.nextTraditionalRegion("taiwan"))
        assertEquals("standard", WeavePrefs.nextTraditionalRegion("hongkong"))
        assertEquals("standard", WeavePrefs.nextTraditionalRegion("unknown"))
    }

    @Test fun savedRegionIsReadBack() {
        prefs.edit().putString(WeavePrefs.TRADITIONAL_REGION, "taiwan").commit()
        assertEquals("taiwan", WeavePrefs.traditionalRegion(prefs))
    }
}
