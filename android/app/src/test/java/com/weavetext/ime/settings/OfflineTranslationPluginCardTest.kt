package com.weavetext.ime.settings

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.translate.TranslationProtocol
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.ConcurrentLinkedQueue

/** 独立 Compose 设置回归：不调用真实安装器、IPC 或 SDK。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OfflineTranslationPluginCardTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("translation-plugin-ui-test", 0)
    private val owner = TestLifecycleOwner()
    private val picker = TestPickerRegistry()
    private val registryOwner = object : ActivityResultRegistryOwner { override val activityResultRegistry = picker }
    private val actions = FakePluginActions()

    private class TestLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private class TestPickerRegistry : ActivityResultRegistry() {
        var requestCode = -1
        var mimeTypes = emptyList<String>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
            options: ActivityOptionsCompat?) {
            check(contract is ActivityResultContracts.OpenDocument)
            this.requestCode = requestCode
            mimeTypes = (input as Array<*>).map { it.toString() }
        }
        fun choose(uri: Uri?) { check(dispatchResult(requestCode, uri)) }
    }

    private class FakePluginActions : OfflineTranslationPluginActions {
        var current = OfflinePluginStatus(OfflinePluginState.MISSING)
        var statusReads = 0
        var managers = 0
        var uninstallers = 0
        var managerAvailable = true
        var uninstallAvailable = true
        var installResult = Result.success(Unit)
        val imports = ConcurrentLinkedQueue<Pair<Uri, Boolean>>()
        override fun status(ctx: Context): OfflinePluginStatus { statusReads++; return current }
        override fun openManager(ctx: Context): Boolean { managers++; return managerAvailable }
        override fun requestUninstall(ctx: Context): Boolean { uninstallers++; return uninstallAvailable }
        override fun installFromUri(ctx: Context, uri: Uri): Result<Unit> {
            imports += uri to (Looper.myLooper() != Looper.getMainLooper())
            return installResult
        }
    }

    @Before fun setUp() { prefs.edit().clear().commit() }

    private fun open() {
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalActivityResultRegistryOwner provides registryOwner,
                LocalNav provides Navigator(listOf(Route.Home, Route.Translation))) {
                WeaveSettingsTheme(false) { TranslationSettingsContent(prefs, actions) }
            }
        }
        compose.waitForIdle()
    }
    private fun click(tag: String) = compose.onNodeWithTag(tag).performScrollTo().performClick()
    private fun resume() {
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
    }
    private fun awaitMessage(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun defaultShowsOfficialWebAndARealMissingPluginInsteadOfAnInstalledToggle() {
        open()
        compose.onNodeWithTag("translation_protocol_google_web").assertIsSelected()
        compose.onNodeWithText("未安装 · 请先安装插件").assertExists()
        compose.onNodeWithTag("offline_translation_plugin_enabled").assertIsNotEnabled().assertIsOff()
        compose.onNodeWithTag("offline_translation_plugin_install").assertIsEnabled()
        compose.onNodeWithTag("offline_translation_plugin_manage").assertIsNotEnabled()
        compose.onNodeWithTag("offline_translation_plugin_uninstall").assertIsNotEnabled()
        compose.onNodeWithTag("translation_custom_endpoint").assertDoesNotExist()
        compose.onNodeWithTag("translation_custom_api_key").assertDoesNotExist()
        compose.onNodeWithText("允许移动网络下载语言包").assertDoesNotExist()
        assertFalse(TranslationSettings.onlineEnabled(prefs))
        assertTrue(actions.imports.isEmpty())
        assertFalse(prefs.contains(TranslationSettings.MODEL_METERED_ALLOWED))
    }

    @Test fun incompatibleAndUntrustedPluginsShowTheirVersionsButCannotBeEnabled() {
        actions.current = OfflinePluginStatus(OfflinePluginState.INCOMPATIBLE, "0.4", "插件协议版本不兼容")
        open()
        compose.onNodeWithText("版本：0.4").assertExists()
        compose.onNodeWithText("已安装 · 不兼容，请更新插件").assertExists()
        compose.onNodeWithTag("offline_translation_plugin_enabled").assertIsNotEnabled()
        compose.onNodeWithTag("offline_translation_plugin_manage").assertIsNotEnabled()
        compose.onNodeWithTag("offline_translation_plugin_uninstall").assertIsEnabled()
        compose.runOnIdle { actions.current = OfflinePluginStatus(OfflinePluginState.UNTRUSTED, "0.5", "请安装配套签名的插件") }
        resume()
        compose.onNodeWithText("版本：0.5").assertExists()
        compose.onNodeWithText("已安装 · 签名不可信，无法启用").assertExists()
        compose.onNodeWithTag("offline_translation_plugin_enabled").assertIsNotEnabled()
        assertEquals(TranslationProtocol.GOOGLE_WEB, TranslationSettings.protocol(prefs))
    }

    @Test fun installedReadyPluginIsAnExplicitChoiceAndTurningItOffSelectsWeb() {
        actions.current = OfflinePluginStatus(OfflinePluginState.READY, "1.2")
        open()
        compose.onNodeWithText("已安装 · 兼容").assertExists()
        compose.onNodeWithTag("offline_translation_plugin_enabled").assertIsOff()
        click("offline_translation_plugin_enabled")
        assertEquals(TranslationProtocol.GOOGLE_DEVICE, TranslationSettings.protocol(prefs))
        compose.onNodeWithTag("offline_translation_plugin_enabled").assertIsOn()
        click("offline_translation_plugin_enabled")
        assertEquals(TranslationProtocol.GOOGLE_WEB, TranslationSettings.protocol(prefs))
        compose.onNodeWithTag("translation_protocol_google_web").assertIsSelected()
        assertTrue(actions.imports.isEmpty())
        assertEquals(0, actions.managers)
    }

    @Test fun pickerAcceptsApkAndOctetStreamAndDelegatesContentOnIoWithoutPretendingInstallationFinished() {
        open()
        click("offline_translation_plugin_install")
        assertEquals(listOf("application/vnd.android.package-archive", "application/octet-stream"), picker.mimeTypes)
        val uri = Uri.parse("content://test-plugin/provider/file-without-apk-extension")
        compose.runOnIdle { picker.choose(uri) }
        awaitMessage("请在系统页面授权并完成安装；返回后会刷新插件状态。")
        val imported = actions.imports.peek()
        assertEquals(uri, imported.first)
        assertTrue("复制和校验不能阻塞主 UI", imported.second)
        compose.onNodeWithText("未安装 · 请先安装插件").assertExists()
        compose.onNodeWithTag("offline_translation_plugin_enabled").assertIsNotEnabled()

        val reads = actions.statusReads
        compose.runOnIdle { actions.current = OfflinePluginStatus(OfflinePluginState.READY, "1.3") }
        resume()
        assertTrue(actions.statusReads > reads)
        compose.onNodeWithText("版本：1.3").assertExists()
        compose.onNodeWithTag("offline_translation_plugin_enabled").assertIsEnabled().assertIsOff()
    }

    @Test fun pickerCancellationDoesNotInstallOrChangeProvider() {
        open()
        click("offline_translation_plugin_install")
        compose.runOnIdle { picker.choose(null) }
        compose.waitForIdle()
        assertTrue(actions.imports.isEmpty())
        assertEquals(TranslationProtocol.GOOGLE_WEB, TranslationSettings.protocol(prefs))
    }

    @Test fun validationFailureShowsTheRealReasonAndDoesNotEnablePlugin() {
        actions.installResult = Result.failure(IllegalArgumentException("插件签名与输入法不匹配"))
        open()
        click("offline_translation_plugin_install")
        compose.runOnIdle { picker.choose(Uri.parse("content://test-plugin/wrong-signature")) }
        awaitMessage("插件签名与输入法不匹配")
        assertEquals(TranslationProtocol.GOOGLE_WEB, TranslationSettings.protocol(prefs))
        compose.onNodeWithTag("offline_translation_plugin_enabled").assertIsNotEnabled()
    }

    @Test fun managementAndUninstallDelegateToPluginAndUninstallReturnRefreshesSelection() {
        actions.current = OfflinePluginStatus(OfflinePluginState.READY, "1.2")
        open()
        click("offline_translation_plugin_enabled")
        click("offline_translation_plugin_manage")
        assertEquals(1, actions.managers)
        click("offline_translation_plugin_uninstall")
        assertEquals(1, actions.uninstallers)
        assertEquals(TranslationProtocol.GOOGLE_DEVICE, TranslationSettings.protocol(prefs))
        compose.runOnIdle { actions.current = OfflinePluginStatus(OfflinePluginState.MISSING) }
        resume()
        assertEquals(TranslationProtocol.GOOGLE_WEB, TranslationSettings.protocol(prefs))
        compose.onNodeWithTag("offline_translation_plugin_enabled").assertIsOff().assertIsNotEnabled()
        compose.onNodeWithTag("translation_protocol_google_web").assertIsSelected()
        compose.onNodeWithText("离线插件不可用，已改用 Google 官方网页；请先安装兼容且可信的插件。").assertExists()
    }

    @Test fun aReadyDisplayIsRecheckedBeforeEnablingWhenPackageStateChanges() {
        actions.current = OfflinePluginStatus(OfflinePluginState.READY, "1.2")
        open()
        compose.runOnIdle { actions.current = OfflinePluginStatus(OfflinePluginState.UNTRUSTED, "1.2") }
        click("offline_translation_plugin_enabled")
        assertEquals(TranslationProtocol.GOOGLE_WEB, TranslationSettings.protocol(prefs))
        compose.onNodeWithTag("offline_translation_plugin_enabled").assertIsNotEnabled()
    }

    @Test fun customConfigurationRemainsAdvancedAndSeparatelyOptedIn() {
        open()
        click("translation_advanced_toggle")
        click("translation_protocol_libretranslate")
        compose.onNodeWithTag("translation_custom_endpoint").assertExists()
        compose.onNodeWithTag("translation_custom_api_key").assertExists()
        assertEquals(TranslationProtocol.LIBRE_TRANSLATE, TranslationSettings.protocol(prefs))
        assertFalse(TranslationSettings.onlineEnabled(prefs))
    }

    @Test fun lifecycleObserverIsRemovedWhenTheCardLeavesComposition() {
        val visible = mutableStateOf(true)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalActivityResultRegistryOwner provides registryOwner) {
                WeaveSettingsTheme(false) { if (visible.value) OfflineTranslationPluginCard(prefs, actions) }
            }
        }
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        val reads = actions.statusReads
        resume()
        assertEquals(reads, actions.statusReads)
    }
}
