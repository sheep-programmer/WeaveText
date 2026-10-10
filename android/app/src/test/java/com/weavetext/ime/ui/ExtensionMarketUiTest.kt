package com.weavetext.ime.ui

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.weavetext.ime.extensions.Extensions
import com.weavetext.ime.settings.*
import com.weavetext.ime.testing.FakeEngines
import com.weavetext.ime.testing.FakeModels
import com.weavetext.ime.testing.FakeUserDictionary
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-420dpi")
class ExtensionMarketUiTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = WeavePrefs.of(app)
    @Before fun reset() { prefs.edit().clear().commit(); File(app.filesDir,"extensions").deleteRecursively() }
    private fun show(route: Route, dark: Boolean = false) {
        prefs.edit().putString(WeavePrefs.THEME,if(dark) "dark" else "light").commit()
        val deps=SettingsDeps(app,engines={FakeEngines()},models={FakeModels()},dictionary=FakeUserDictionary(),status={ImeStatus(true,true,true)})
        compose.setContent {SettingsApp(deps,Navigator(listOf(Route.Home,route)))}
        compose.waitForIdle()
    }
    private fun snap(name:String) {
        compose.onRoot().captureRoboImage(File(System.getProperty("weave.snapshotDir"),"settings_extensions_$name.png").path)
    }
    @Test fun homeLight() {show(Route.Home);compose.onNodeWithText("插件市场").assertExists();snap("home_light")}
    @Test fun homeDark() {show(Route.Home,true);snap("home_dark")}
    @Test fun marketLight() {show(Route.Market());snap("market_light")}
    @Test fun marketDark() {show(Route.Market(),true);snap("market_dark")}
    @Test fun installedThemeFilterAndRemovalUpdateWithoutReopening() {
        show(Route.Market("theme"))
        compose.onNodeWithText("搜索功能、主题或布局",substring=true).assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("樱粉")
        compose.onNodeWithText("安装").performClick()
        compose.onNodeWithText("使用").performClick()
        compose.onNodeWithText("使用中").assertExists()
        compose.onNodeWithText("卸载").performClick()
        compose.onNodeWithText("安装").assertExists()
    }
    @Test fun turningVoiceOffImmediatelyRemovesItsHomeEntry() {
        show(Route.Market("feature"))
        compose.onNodeWithContentDescription("启用语音输入").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("语音输入").assertDoesNotExist()
        compose.onNodeWithText("输入方案").assertExists()
    }
    @Test fun disabledDeepLinkOffersMarketInsteadOfStartingVoice() {
        Extensions.setEnabled(prefs,"feature:voice",false)
        show(Route.Voice)
        compose.onNodeWithText("去插件市场启用").assertExists()
    }
}
