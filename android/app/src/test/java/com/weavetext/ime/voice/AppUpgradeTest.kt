package com.weavetext.ime.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppUpgradeTest {
    @Test fun comparesReleaseVersionsWithoutTheVPrefixOrPrereleaseSuffix() {
        assertEquals(0, AppUpgrade.compareVersions("v0.1.0-beta.17", "0.1.0-beta.16"))
        assertEquals(1, AppUpgrade.compareVersions("0.2.0", "0.1.9"))
        assertEquals(-1, AppUpgrade.compareVersions("0.1.10", "0.2.0"))
        assertEquals(0, AppUpgrade.compareVersions("0.1.0-beta.17", "0.1.0-beta.1"))
    }
}
