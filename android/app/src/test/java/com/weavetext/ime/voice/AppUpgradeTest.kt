package com.weavetext.ime.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppUpgradeTest {
    @Test fun ordersReleasesAndPrereleasesSemantically() {
        assertTrue(AppUpgrade.compareVersions("v0.1.0-beta.17", "0.1.0-beta.16") > 0)
        assertTrue(AppUpgrade.compareVersions("0.1.0-beta.17", "0.1.0-beta.1") > 0)
        assertTrue(AppUpgrade.compareVersions("0.1.0-beta.10", "0.1.0-beta.9") > 0)
        assertTrue(AppUpgrade.compareVersions("0.1.0", "0.1.0-beta.18") > 0)
        assertTrue(AppUpgrade.compareVersions("0.1.0-rc.1", "0.1.0-beta.18") > 0)
        assertTrue(AppUpgrade.compareVersions("0.2.0", "0.1.9") > 0)
        assertTrue(AppUpgrade.compareVersions("0.1.10", "0.2.0") < 0)
        assertEquals(0, AppUpgrade.compareVersions("v0.1.0-beta.18", "0.1.0-beta.18"))
        assertTrue(AppUpgrade.compareVersions("0.1.0-beta.17", "0.1.0-beta.18") < 0)
    }
}
