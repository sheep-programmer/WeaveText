package com.weavetext.ime.ui

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.compose.runtime.mutableIntStateOf
import com.github.takahirom.roborazzi.captureRoboImage
import com.weavetext.ime.settings.ImeStatus
import com.weavetext.ime.settings.Navigator
import com.weavetext.ime.settings.Route
import com.weavetext.ime.settings.SettingsApp
import com.weavetext.ime.settings.SettingsDeps
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.testing.FakeEngines
import com.weavetext.ime.testing.FakeModels
import com.weavetext.ime.voice.VoiceEngines
import com.weavetext.ime.voice.ConfigField
import androidx.compose.ui.semantics.SemanticsProperties
import org.junit.Assert.assertEquals
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
class VoicePluginListTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val engines = FakeEngines()
    private val nav = Navigator(listOf(Route.Home, Route.Voice))
    private val statusVersion = mutableIntStateOf(0)
    private val cloud = FakeEngines.SAMPLE[1]
    private val second = FakeEngines.SAMPLE[2]
    private val third = FakeEngines.SAMPLE[3]

    @Before fun setup() { WeavePrefs.of(app).edit().clear().commit() }

    private fun show(dark: Boolean = false, backend: VoiceEngines = engines, restoration: StateRestorationTester? = null) {
        WeavePrefs.of(app).edit().putString(WeavePrefs.THEME, if (dark) "dark" else "light").commit()
        val deps = SettingsDeps(app, engines = { backend }, models = { FakeModels() }, status = { ImeStatus(true, true, true) })
        if (restoration == null) compose.setContent { SettingsApp(deps, nav, statusVersion.intValue) }
        else restoration.setContent { SettingsApp(deps, nav, statusVersion.intValue) }
        compose.waitForIdle()
    }

    private fun pluginAction(id: String, label: String) =
        compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("voice_plugin_$id")))

    private fun combinedCheckbox(id: String) =
        compose.onNode(isToggleable() and hasAnyAncestor(hasTestTag("voice_combination_$id")))

    @Test fun independentExpansionDoesNotChangeTheActiveEngine() {
        show()
        compose.onNodeWithText("已安装 · 3 个语音插件").assertExists()
        compose.onNodeWithText(cloud.description).assertDoesNotExist()
        compose.onNodeWithText(cloud.name).performClick()
        compose.onNodeWithText(cloud.description).assertExists()
        compose.onNodeWithText(second.description).assertDoesNotExist()
        compose.onNodeWithText(second.name).performScrollTo().performClick()
        compose.onNodeWithText(second.description).assertExists()
        compose.onNodeWithText(cloud.description).assertExists()
        compose.onNodeWithText(cloud.name).performScrollTo().performClick()
        compose.onNodeWithText(cloud.description).assertDoesNotExist()
        compose.onNodeWithText(second.description).assertExists()
        assertEquals("weave.local", engines.activeId)
    }

    @Test fun explicitActivationRefreshesTheHeaderImmediately() {
        show()
        compose.onNodeWithText(second.name).performClick()
        assertEquals("weave.local", engines.activeId)
        pluginAction(second.id, "设为当前引擎").performScrollTo().performClick()
        assertEquals(second.id, engines.activeId)
        compose.onNodeWithText(second.name).performScrollTo().performClick()
        pluginAction(second.id, "当前引擎").assertExists()
        pluginAction(second.id, "设为当前引擎").assertDoesNotExist()
    }

    @Test fun requiredConfigurationBlocksActivationAndReturningFromConfigurationRefreshesReadiness() {
        show()
        compose.onNodeWithText(cloud.name).performClick()
        pluginAction(cloud.id, "待配置").assertExists()
        pluginAction(cloud.id, "设为当前引擎").assertIsNotEnabled()
        compose.onNodeWithText("请先填写：API Key").assertExists()
        pluginAction(cloud.id, "配置与详情").performScrollTo().performClick()
        compose.onNodeWithText("设为当前引擎").assertIsNotEnabled()
        val token = "test-private-value-never-in-list"
        // Simulate configuration saved by the detail page before returning to the list.
        compose.runOnIdle { engines.setConfig(cloud.id, "api_key", token); nav.pop() }
        compose.waitForIdle()
        compose.onNodeWithText(cloud.description).assertExists()
        pluginAction(cloud.id, "设为当前引擎").assertIsEnabled()
        pluginAction(cloud.id, "已配置").assertExists()
        compose.onNodeWithText(token, substring = true).assertDoesNotExist()
    }

    @Test fun pluginReportedUnconfiguredCannotBeActivatedFromListOrDetail() {
        show()
        compose.onNodeWithText(third.name).performClick()
        pluginAction(third.id, "待配置").assertExists()
        pluginAction(third.id, "设为当前引擎").assertIsNotEnabled()
        pluginAction(third.id, "配置与详情").performScrollTo().performClick()
        compose.onNodeWithText("设为当前引擎").assertIsNotEnabled()
        assertEquals("weave.local", engines.activeId)
    }

    @Test fun combiningStopsAtThreeAndAllowsRemovingAnExistingSelection() {
        engines.setConfig(cloud.id, "api_key", "configured")
        engines.plugins = engines.plugins.map { if (it.id == third.id) it.copy(configured = true) else it }
        show()
        compose.onNodeWithText("多引擎识别").performScrollTo().performClick()
        combinedCheckbox(cloud.id).performScrollTo().performClick()
        combinedCheckbox(second.id).performScrollTo().performClick()
        assertEquals(setOf("weave.local", cloud.id, second.id), engines.selection().map { it.id }.toSet())
        combinedCheckbox(third.id).assertIsNotEnabled()
        combinedCheckbox(cloud.id).assertIsEnabled().assertIsOn()
        combinedCheckbox(second.id).performScrollTo().performClick()
        combinedCheckbox(third.id).assertIsEnabled().performScrollTo().performClick()
        combinedCheckbox(second.id).assertIsOff().assertIsNotEnabled()
        compose.onNodeWithText("已选 3 个 · 最多同时使用 3 个").assertExists()
        assertEquals(setOf("weave.local", cloud.id, third.id), engines.selection().map { it.id }.toSet())
        compose.onNodeWithText("多引擎识别").performScrollTo().performClick()
        pluginAction(cloud.id, "同时使用").assertExists()
        pluginAction(third.id, "同时使用").assertExists()
    }

    @Test fun combinationKeepsUnconfiguredAndExclusiveEnginesDisabled() {
        val backend = object : VoiceEngines by engines {
            override fun canCombine(id: String) = id != second.id
        }
        show(backend = backend)
        compose.onNodeWithText("多引擎识别").performScrollTo().performClick()
        combinedCheckbox(cloud.id).assertIsNotEnabled()
        combinedCheckbox(second.id).assertIsNotEnabled()
        combinedCheckbox(third.id).assertIsNotEnabled()
        assertEquals(emptySet<String>(), engines.extraIds)
    }

    @Test fun expansionSurvivesSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        show(restoration = restoration)
        compose.onNodeWithText(second.name).performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(second.description).assertExists()
        assertEquals("weave.local", engines.activeId)
    }

    @Test fun searchMatchesNamesIdsAndDescriptionsAndCanBeCleared() {
        show()
        compose.onNodeWithContentDescription("搜索语音插件").performClick()
        val search = compose.onNodeWithTag("voice_plugin_search")
        search.performTextReplacement("  WEBSOCKET  ")
        compose.onNodeWithText(cloud.name).assertExists()
        compose.onNodeWithText(second.name).assertDoesNotExist()
        compose.onNodeWithText("显示 1 / 3 个插件").assertExists()
        search.performTextReplacement(second.id.uppercase())
        compose.onNodeWithText(second.name).assertExists()
        compose.onNodeWithText(cloud.name).assertDoesNotExist()
        search.performTextReplacement("没有这个插件")
        compose.onNodeWithText("没有找到匹配的插件").assertExists()
        compose.onNodeWithText("清除筛选").performScrollTo().performClick()
        compose.onNodeWithText("显示 3 / 3 个插件").assertExists()
        compose.onNodeWithContentDescription("关闭插件筛选").performClick()
        compose.onNodeWithTag("voice_plugin_search").assertDoesNotExist()
        compose.onNodeWithText(cloud.name).assertExists()
    }

    @Test fun readinessAndEnabledFiltersFollowEngineChanges() {
        engines.activeId = second.id
        show()
        compose.onNodeWithContentDescription("搜索语音插件").performClick()
        compose.onNodeWithTag("voice_filter_Enabled").performClick()
        compose.onNodeWithText(second.name).assertExists()
        compose.onNodeWithText(cloud.name).assertDoesNotExist()
        compose.onNodeWithTag("voice_filter_NeedsConfig").performClick()
        compose.onNodeWithText(cloud.name).assertExists()
        compose.onNodeWithText(third.name).assertExists()
        compose.onNodeWithText(second.name).assertDoesNotExist()
        compose.runOnIdle { engines.setConfig(cloud.id, "api_key", "configured"); statusVersion.intValue++ }
        compose.onNodeWithText(cloud.name).assertDoesNotExist()
        compose.onNodeWithText("显示 1 / 3 个插件").assertExists()
    }

    @Test fun returningFromConfigurationKeepsTheSearchFilterAndExpandedCard() {
        engines.activeId = second.id
        show()
        compose.onNodeWithContentDescription("搜索语音插件").performClick()
        compose.onNodeWithTag("voice_plugin_search").performTextReplacement(second.id)
        compose.onNodeWithTag("voice_filter_Enabled").performClick()
        compose.onNodeWithText(second.name).performScrollTo().performClick()
        pluginAction(second.id, "配置与详情").performScrollTo().performClick()
        compose.runOnIdle { nav.pop() }
        compose.onNodeWithText(second.description).assertExists()
        compose.onNodeWithTag("voice_filter_Enabled").assertIsSelected()
        compose.onNodeWithText("显示 1 / 3 个插件").assertExists()
        compose.onNodeWithText(cloud.name).assertDoesNotExist()
        compose.onNodeWithTag("voice_plugin_search").assertExists()
    }

    @Test fun selectionRefreshesOnResumeWithoutResettingExpandedCards() {
        show()
        compose.onNodeWithText(second.name).performClick()
        compose.runOnIdle { engines.activeId = second.id; statusVersion.intValue++ }
        pluginAction(second.id, "设为当前引擎").assertDoesNotExist()
        compose.onNodeWithText(second.description).assertExists()
        assertEquals(second.id, engines.activeId)
    }

    @Test fun returningFromConfigurationKeepsTheScrollPositionInALongList() {
        engines.plugins = engines.plugins.take(1) + (1..9).map { n -> second.copy(id = "org.example.asr.$n", name = "识别插件 $n") }
        show()
        val last = engines.plugins.last()
        compose.onNodeWithText(last.name).performScrollTo().performClick()
        pluginAction(last.id, "配置与详情").performScrollTo()
        fun scrollPosition() = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        val before = scrollPosition()
        org.junit.Assert.assertTrue(before > 0)
        pluginAction(last.id, "配置与详情").performClick()
        compose.runOnIdle { nav.pop() }
        compose.waitForIdle()
        assertEquals(before, scrollPosition(), 1f)
        compose.onNodeWithText(last.description).assertExists()
    }

    @Test fun tappingShortHelpTextEditsTheSetting() {
        nav.push(Route.VoiceDetail(cloud.id))
        show()
        compose.onNodeWithText("识别结果自动添加标点").performScrollTo().performClick()
        assertEquals("false", engines.getConfig(cloud.id, "auto_punct"))
    }

    @Test fun longHelpHasAnExplicitExpandButtonWithoutOpeningTheEditor() {
        val field = ConfigField("note", "服务说明", "text", helpText = "这里是一段比较长的配置说明，用于确认截断后的展开和收起操作。".repeat(12))
        engines.plugins = listOf(engines.plugins.first(), second.copy(configSchema = listOf(field)))
        nav.push(Route.VoiceDetail(second.id))
        show()
        compose.onNodeWithText("展开说明").performScrollTo().performClick()
        compose.onNodeWithText("收起说明").assertExists()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText("收起说明").performScrollTo().performClick()
        compose.onNodeWithText("展开说明").assertExists()
        assertEquals(null, engines.getConfig(second.id, "note"))
    }

    @Test fun emptyInstalledListShowsImportGuidance() {
        engines.plugins = engines.plugins.take(1)
        show()
        compose.onNodeWithText("已安装 · 0 个语音插件").assertExists()
        compose.onNodeWithText("还没有安装语音插件").assertExists()
        compose.onNodeWithText("导入本地插件").assertExists()
        compose.onNodeWithContentDescription("搜索语音插件").assertDoesNotExist()
    }

    @Test fun filteredDark() {
        engines.activeId = second.id
        show(dark = true)
        compose.onNodeWithContentDescription("搜索语音插件").performClick()
        compose.onNodeWithTag("voice_filter_NeedsConfig").performClick()
        snapshot("filtered_dark")
    }

    @Test fun expandedLight() {
        engines.activeId = second.id
        show()
        compose.onNodeWithText(second.name).performClick()
        snapshot("expanded")
    }

    @Test fun collapsedDark() { show(dark = true); snapshot("collapsed_dark") }

    @Config(qualifiers = "w320dp-h1000dp-port-xhdpi")
    @Test fun expandedNarrowLargeFont() {
        org.robolectric.RuntimeEnvironment.setFontScale(1.3f)
        show()
        compose.onNodeWithText(cloud.name).performClick()
        snapshot("expanded_narrow_font13")
    }

    private fun snapshot(name: String) {
        compose.waitForIdle()
        val dir = File(System.getProperty("weave.snapshotDir") ?: "build/snapshots")
        compose.onRoot().captureRoboImage(File(dir, "settings_voice_plugins_$name.png").path)
    }
}
