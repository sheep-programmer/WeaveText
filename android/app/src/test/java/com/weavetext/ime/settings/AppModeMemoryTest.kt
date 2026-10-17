package com.weavetext.ime.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 按应用记忆的存取：切过的还原、没切过的不参与、上限内丢最早的（取值一起丢）、可整体清除。
 * The per-app memory store: a switched app is remembered, apps never switched in stay unknown, entries past the
 * limit fall off along with their value, and everything can be cleared at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-420dpi")
class AppModeMemoryTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val p get() = WeavePrefs.of(app)

    @Before fun clear() { p.edit().clear().commit() }

    @Test fun rememberedChoicesComeBackAndNeverSwitchedAppsStayUnknown() {
        assertTrue("默认打开", WeavePrefs.perAppMemory(p))
        assertNull(WeavePrefs.appMode(p, "com.a"))
        WeavePrefs.rememberAppMode(p, "com.a", false)
        WeavePrefs.rememberAppMode(p, "com.b", true)
        assertEquals(false, WeavePrefs.appMode(p, "com.a"))
        assertEquals(true, WeavePrefs.appMode(p, "com.b"))
        assertNull(WeavePrefs.appMode(p, "com.c"))
        // 再切一次只是改取值，不重复计数。 Switching again only changes the value, it does not double-count.
        WeavePrefs.rememberAppMode(p, "com.a", true)
        assertEquals(true, WeavePrefs.appMode(p, "com.a"))
        assertEquals(2, WeavePrefs.appMemoryCount(p))
        // 空包名不记。 No package name, nothing remembered.
        WeavePrefs.rememberAppMode(p, null, true)
        WeavePrefs.rememberAppMode(p, "", true)
        assertEquals(2, WeavePrefs.appMemoryCount(p))
    }

    @Test fun entriesPastTheLimitGoAwayWithTheirValue() {
        val limit = WeavePrefs.APP_MODE_MAX
        for (i in 0 until limit + 5) WeavePrefs.rememberAppMode(p, "com.app$i", i % 2 == 0)
        assertEquals(limit, WeavePrefs.appMemoryCount(p))
        assertNull("最老的连同取值一起丢掉", WeavePrefs.appMode(p, "com.app0"))
        assertNull(WeavePrefs.appMode(p, "com.app4"))
        assertEquals(true, WeavePrefs.appMode(p, "com.app${limit + 2}"))
        // 名单之外不留取值。 No values are left outside the list.
        assertFalse(p.contains(WeavePrefs.APP_MODE_PREFIX + "com.app0"))
    }

    @Test fun clearingDropsBothTheValuesAndTheList() {
        WeavePrefs.rememberAppMode(p, "com.a", false)
        WeavePrefs.clearAppMemory(p)
        assertEquals(0, WeavePrefs.appMemoryCount(p))
        assertNull(WeavePrefs.appMode(p, "com.a"))
        assertFalse(p.contains(WeavePrefs.APP_MODE_PREFIX + "com.a"))
    }
}
