package com.weavetext.ime.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.weavetext.ime.settings.ImeStatus
import com.weavetext.ime.settings.Navigator
import com.weavetext.ime.settings.Route
import com.weavetext.ime.settings.SettingsApp
import com.weavetext.ime.settings.SettingsDeps
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.settings.ImportPreview
import com.weavetext.ime.settings.StyleImportContent
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
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

/**
 * 设置 App 各页的 JVM 截图（Compose + Robolectric 原生渲染），输出 src/test/snapshots/settings_*.png。
 * JVM screenshots of the settings pages with fake engines, dictionary and IME status.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-420dpi")
class SettingsScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val dir = File(System.getProperty("weave.snapshotDir") ?: "build/snapshots")
    private val engines = FakeEngines()
    private val models = FakeModels(mapOf("punc-ct" to com.weavetext.ime.models.ModelState.Installed))

    @Before fun setUp() {
        WeavePrefs.of(app).edit().clear()
            .putString(WeavePrefs.KEYBOARDS, "pinyin,shuangpin,english")
            .putStringSet(WeavePrefs.FUZZY, setOf("z_zh", "c_ch", "an_ang"))
            .commit()
    }

    private fun show(
        name: String, vararg routes: Route, dark: Boolean = false,
        status: ImeStatus = ImeStatus(enabled = true, isDefault = true, micGranted = true),
    ) {
        WeavePrefs.of(app).edit().putString(WeavePrefs.THEME, if (dark) "dark" else "system").commit()
        val deps = SettingsDeps(
            app, engines = { engines }, models = { models }, dictionary = FakeUserDictionary(), status = { status },
            versionName = "0.1.0", versionCode = 1,
        )
        compose.setContent { SettingsApp(deps, Navigator(routes.toList())) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(dir, "settings_$name.png").path)
    }

    @Test fun onboarding() = show("onboarding", Route.Onboarding, status = ImeStatus(enabled = true, isDefault = false, micGranted = false))
    @Test fun onboardingDark() = show("onboarding_dark", Route.Onboarding, dark = true, status = ImeStatus(enabled = false, isDefault = false, micGranted = false))
    @Test fun home() = show("home", Route.Home)
    @Test fun homeDark() = show("home_dark", Route.Home, dark = true)
    @Test fun homeNotDefault() = show("home_not_default", Route.Home, status = ImeStatus(enabled = true, isDefault = false, micGranted = true))
    @Test fun schemes() = show("schemes", Route.Home, Route.Schemes)
    @Test fun schemesDark() = show("schemes_dark", Route.Home, Route.Schemes, dark = true)
    @Test fun fuzzy() = show("fuzzy", Route.Home, Route.Schemes, Route.Fuzzy)
    @Test fun voiceList() = show("voice_list", Route.Home, Route.Voice)
    @Test fun voiceListDark() = show("voice_list_dark", Route.Home, Route.Voice, dark = true)
    /** 「同时使用」（整页）：系统识别只能单独使用。 Use-together card (whole page); the platform engine is single-only. */
    @Config(qualifiers = "w411dp-h1600dp-port-420dpi")
    @Test fun voiceListCombine() {
        val sys = com.weavetext.ime.voice.VoicePlugin("weave.system", "系统语音识别", "使用手机自带的语音识别服务。", "", null, emptyList())
        engines.plugins = listOf(LOCAL, sys) + FakeEngines.SAMPLE.drop(1)
        engines.activeId = LOCAL.id
        engines.extraIds = setOf("org.example.asr.cloud", "org.example.asr.b")
        show("voice_list_combine", Route.Home, Route.Voice)
    }
    @Test fun voiceListEmpty() {
        engines.plugins = emptyList()
        show("voice_list_empty", Route.Home, Route.Voice)
    }
    @Test fun voiceDetailForm() = show("voice_detail", Route.Home, Route.Voice, Route.VoiceDetail("org.example.asr.cloud"))
    @Test fun voiceDetailFormDark() = show("voice_detail_dark", Route.Home, Route.Voice, Route.VoiceDetail("org.example.asr.cloud"), dark = true)
    /** 不联网的插件：网络访问卡片里没有「发送到上述地址」。 Offline plugin: no "sent to the hosts above". */
    @Test fun voiceDetailOffline() = show("voice_detail_offline", Route.Home, Route.Voice, Route.VoiceDetail("org.example.asr.b"))
    @Test fun voiceDetailUnrestricted() = show("voice_detail_unrestricted", Route.Home, Route.Voice, Route.VoiceDetail("org.example.asr.c"))
    /** 本地离线识别详情：底部「管理离线模型」入口，没有「删除插件」。 Local engine detail. */
    @Test fun voiceDetailLocal() {
        engines.plugins = listOf(LOCAL) + FakeEngines.SAMPLE
        show("voice_detail_local", Route.Home, Route.Voice, Route.VoiceDetail(LOCAL.id))
    }
    @Test fun importPreview() = preview("import_preview", FakeEngines.SAMPLE[1])
    @Test fun importPreviewOffline() = preview("import_preview_offline", FakeEngines.SAMPLE[2], dark = true)

    /** 导入确认弹层的内容（直接渲染，不经过系统文件选择器）。 Import confirmation content, rendered directly. */
    private fun preview(name: String, p: com.weavetext.ime.voice.VoicePlugin, dark: Boolean = false) {
        compose.setContent {
            com.weavetext.ime.settings.WeaveSettingsTheme(dark) {
                androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow) {
                    androidx.compose.foundation.layout.Column(
                        androidx.compose.ui.Modifier.padding(horizontal = 24.dp).padding(top = 24.dp, bottom = 32.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                    ) { ImportPreview(p, onCancel = {}, onInstall = {}) }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(dir, "settings_$name.png").path)
    }

    // 小屏 / 小平板 / 横屏 / 大字号（ui-polish §5、§7）。 Narrow, tablet, landscape and large-font cases.
    @Config(qualifiers = "w320dp-h640dp-port-xhdpi") @Test fun homeNarrow() = show("w320_home", Route.Home)
    @Config(qualifiers = "w320dp-h640dp-port-xhdpi") @Test fun schemesNarrow() = show("w320_schemes", Route.Home, Route.Schemes)
    @Config(qualifiers = "w600dp-h960dp-port-xhdpi") @Test fun homeTablet() = show("w600_home", Route.Home)
    @Config(qualifiers = "w600dp-h960dp-port-xhdpi") @Test fun schemesTablet() = show("w600_schemes", Route.Home, Route.Schemes)
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi") @Test fun homeLand() = show("land_home", Route.Home)
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi") @Test fun voiceListLand() = show("land_voice_list", Route.Home, Route.Voice)
    @Test fun homeFont13() { org.robolectric.RuntimeEnvironment.setFontScale(1.3f); show("font13_home", Route.Home) }
    @Test fun schemesFont13() { org.robolectric.RuntimeEnvironment.setFontScale(1.3f); show("font13_schemes", Route.Home, Route.Schemes) }
    @Test fun voiceListFont13() { org.robolectric.RuntimeEnvironment.setFontScale(1.3f); show("font13_voice_list", Route.Home, Route.Voice) }

    @Test fun styles() = show("styles", Route.Home, Route.Look, Route.Styles)
    @Test fun stylesDark() {
        WeavePrefs.of(app).edit().putString(WeavePrefs.STYLE_LAYOUT, "round").putString(WeavePrefs.STYLE_THEME, "dusk").commit()
        show("styles_dark", Route.Home, Route.Look, Route.Styles, dark = true)
    }
    /** 整页（高屏）：布局网格、主题网格与我的风格。 Whole page on a tall screen: both grids and saved styles. */
    @Config(qualifiers = "w411dp-h2400dp-port-420dpi")
    @Test fun stylesFullPage() {
        val repo = com.weavetext.ime.style.StyleRepository.get(app)
        repo.root.deleteRecursively()
        WeavePrefs.of(app).edit().putString(WeavePrefs.STYLE_THEME, "sea").commit()
        repo.saveCurrent(WeavePrefs.of(app), "海边")
        show("styles_full", Route.Home, Route.Look, Route.Styles)
        repo.root.deleteRecursively()
    }
    @Test fun styleTweak() {
        WeavePrefs.of(app).edit().putString(WeavePrefs.STYLE_OVERRIDES, "{\"accent\":\"#B8185A\",\"radius\":10,\"hints\":false}").commit()
        show("style_tweak", Route.Home, Route.Look, Route.Styles, Route.StyleTweak)
    }
    @Test fun styleImport() = styleImport("style_import", dark = false)
    @Test fun styleImportDark() = styleImport("style_import_dark", dark = true)

    /** .wvskin 导入确认（直接渲染弹层内容）。 .wvskin import confirmation, rendered directly. */
    private fun styleImport(name: String, dark: Boolean) {
        val repo = com.weavetext.ime.style.StyleRepository.get(app)
        val json = """{"version":1,"name":"晚霞","layout":{"extends":"plain"},"theme":{"extends":"dusk"},"overrides":{"radius":9}}"""
        val bytes = java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { z -> z.putNextEntry(java.util.zip.ZipEntry("style.json")); z.write(json.toByteArray()); z.closeEntry() }
        }.toByteArray()
        val staged = repo.stage(java.io.ByteArrayInputStream(bytes))
        val style = repo.preview(app, staged, dark, 2)
        compose.setContent {
            com.weavetext.ime.settings.WeaveSettingsTheme(dark) {
                androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow) {
                    androidx.compose.foundation.layout.Column(
                        androidx.compose.ui.Modifier.padding(horizontal = 24.dp).padding(top = 24.dp, bottom = 32.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                    ) { StyleImportContent(staged, style, onCancel = {}, onConfirm = {}) }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(dir, "settings_$name.png").path)
        repo.discard(staged)
    }

    @Test fun look() = show("look", Route.Home, Route.Look)
    @Test fun lookDark() = show("look_dark", Route.Home, Route.Look, dark = true)
    /** 选了自有按键音：出现音量滑块。 An own key-sound style shows the volume slider. */
    @Test fun lookKeySound() {
        WeavePrefs.of(app).edit().putString(WeavePrefs.SOUND_STYLE, "wood").putInt(WeavePrefs.SOUND_VOLUME, 60)
            .putInt(WeavePrefs.VIBRATION, 3).commit()
        show("look_key_sound", Route.Home, Route.Look)
    }
    @Test fun dictionary() = show("dictionary", Route.Home, Route.Dictionary)
    @Test fun userWords() = show("user_words", Route.Home, Route.Dictionary, Route.UserWords)
    @Test fun about() = show("about", Route.Home, Route.About)
    @Test fun licenses() = show("licenses", Route.Home, Route.About, Route.Licenses)

    private companion object {
        val LOCAL = com.weavetext.ime.voice.VoicePlugin(
            "weave.local", "本地离线识别", "识别在手机上完成，无需联网，语音不离开设备。", "", null,
            listOf(
                com.weavetext.ime.voice.ConfigField("streaming", "实时模型", "select", "模型", listOf("实时识别 · 小"), "实时识别 · 小", "说话时实时显示文字"),
                com.weavetext.ime.voice.ConfigField("final", "终稿模型", "select", "模型", listOf("终稿识别 · 小", "不使用"), "终稿识别 · 小", "每句说完后用它再识别一遍，更准"),
                com.weavetext.ime.voice.ConfigField("punct", "智能标点", "switch", "模型", defaultValue = "true", helpText = "为识别结果补全标点"),
            ),
        )
    }
}
