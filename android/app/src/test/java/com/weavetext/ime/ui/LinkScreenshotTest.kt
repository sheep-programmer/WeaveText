package com.weavetext.ime.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.weavetext.ime.link.LinkNearby
import com.weavetext.ime.link.LinkPeer
import com.weavetext.ime.link.LinkTransfer
import com.weavetext.ime.link.LinkUiState
import com.weavetext.ime.link.PendingPair
import com.weavetext.ime.settings.ImeStatus
import com.weavetext.ime.settings.Navigator
import com.weavetext.ime.settings.Route
import com.weavetext.ime.settings.SettingsApp
import com.weavetext.ime.settings.SettingsDeps
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.testing.FakeEngines
import com.weavetext.ime.testing.FakeLink
import com.weavetext.ime.testing.FakeModels
import com.weavetext.ime.testing.FakeUserDictionary
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.junit.Assert.assertEquals
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 「互联」设置页截图。 Screenshots of the link settings page. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-420dpi")
class LinkScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val dir = File(System.getProperty("weave.snapshotDir") ?: "build/snapshots")

    @Before fun setUp() { WeavePrefs.of(app).edit().clear().commit() }

    private fun show(name: String, state: LinkUiState, dark: Boolean = false, dialog: Boolean = false) {
        WeavePrefs.of(app).edit().putString(WeavePrefs.THEME, if (dark) "dark" else "light").commit()
        val link = FakeLink(state)
        val deps = SettingsDeps(
            app, engines = { FakeEngines() }, models = { FakeModels(emptyMap()) }, dictionary = FakeUserDictionary(),
            status = { ImeStatus(enabled = true, isDefault = true, micGranted = true) }, link = { link }, versionName = "0.1.0", versionCode = 1,
        )
        if (dialog) compose.mainClock.autoAdvance = false
        compose.setContent { SettingsApp(deps, Navigator(listOf(Route.Home, Route.Link))) }
        if (dialog) {
            compose.mainClock.advanceTimeBy(2000)
            captureScreenRoboImage(File(dir, "settings_$name.png").path)
        } else {
            compose.waitForIdle()
            compose.onRoot().captureRoboImage(File(dir, "settings_$name.png").path)
        }
    }

    private val on = LinkUiState(
        enabled = true, running = true, name = "Pixel 9", fingerprint = "AB12-CD34-EF56-7890",
        trusted = listOf(
            LinkPeer("m1", "工作用的 MacBook", "mac", connected = true, nearby = true),
            LinkPeer("m2", "家里的 iMac", "mac", connected = false, nearby = false),
        ),
        nearby = listOf(LinkNearby("m3", "会议室 Mac mini", "mac", listOf("192.168.1.20:47811"))),
        transfers = listOf(
            LinkTransfer("t1", "旅行照片.jpg", incoming = false, peer = "m1", done = 3_400_000, size = 8_200_000, state = LinkTransfer.State.RUNNING),
            LinkTransfer("t2", "季度报告.pdf", incoming = true, peer = "m1", done = 1_200_000, size = 1_200_000, state = LinkTransfer.State.DONE),
        ),
    )

    @Test fun linkOff() = show("link_off", LinkUiState(name = "Pixel 9"))
    @Test fun linkOn() = show("link_on", on)
    @Test fun linkOnDark() = show("link_on_dark", on, dark = true)
    @Test fun linkSearching() = show("link_searching", on.copy(trusted = emptyList(), nearby = emptyList(), transfers = emptyList()))
    @Test fun linkPairFromQr() = show("link_pair_qr", on.copy(pendingPair = PendingPair("会议室 Mac mini", listOf("192.168.1.20:47811"), "482913")), dialog = true)

    @Test fun scannerIsAvailableBeforeEnablingLinkAndOpensTheBuiltInCamera() {
        val deps = SettingsDeps(app, status = { ImeStatus(true, true, true) }, link = { FakeLink() })
        compose.setContent { SettingsApp(deps, Navigator(listOf(Route.Home, Route.Link))) }
        compose.onNodeWithText("扫描二维码").performClick()
        compose.runOnIdle {
            assertEquals("com.weavetext.ime.link.LinkScanActivity", Shadows.shadowOf(app).nextStartedActivity.component!!.className)
        }
    }
}
