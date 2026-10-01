package com.weavetext.ime.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.weavetext.ime.models.ModelState
import com.weavetext.ime.models.Progress
import com.weavetext.ime.settings.ImeStatus
import com.weavetext.ime.settings.Navigator
import com.weavetext.ime.settings.Route
import com.weavetext.ime.settings.SettingsApp
import com.weavetext.ime.settings.SettingsDeps
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.settings.isValidMirrorTemplate
import com.weavetext.ime.testing.FakeEngines
import com.weavetext.ime.testing.FakeModels
import com.weavetext.ime.testing.FakeUserDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 离线模型管理页的截图与交互测试（假模型仓库，不联网）。输出 src/test/snapshots/models_*.png。
 * Screenshots and interaction tests of the model management page, backed by a fake repository.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-420dpi")
class ModelsScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val dir = File(System.getProperty("weave.snapshotDir") ?: "build/snapshots")

    @Before fun setUp() {
        WeavePrefs.of(app).edit().clear().commit()
    }

    private fun open(models: FakeModels, dark: Boolean = false, routes: List<Route> = listOf(Route.Home, Route.Voice, Route.Models)) {
        WeavePrefs.of(app).edit().putString(WeavePrefs.THEME, if (dark) "dark" else "light").commit()
        val deps = SettingsDeps(
            app, engines = { FakeEngines() }, models = { models }, dictionary = FakeUserDictionary(),
            status = { ImeStatus(enabled = true, isDefault = true, micGranted = true) }, versionName = "0.1.0", versionCode = 1,
        )
        compose.setContent { SettingsApp(deps, Navigator(routes)) }
        compose.waitForIdle()
    }

    private fun capture(name: String) = compose.onRoot().captureRoboImage(File(dir, "models_$name.png").path)

    private val mixed get() = mapOf("punc-ct" to ModelState.Installed)
    private val allInstalled get() = FakeModels.CATALOG.models.filter { !it.builtin }.associate { it.id to ModelState.Installed }

    // 整页（加高视口以包含底部「下载设置」）。 Whole page in a tall viewport, including download settings.
    @Config(qualifiers = "w411dp-h1900dp-port-420dpi")
    @Test fun page() { open(FakeModels(mixed)); capture("page") }

    /** 轻量版：顶部是识别运行库，原内置模型改为可下载。 Lite: runtime first, formerly built-in models downloadable. */
    @Config(qualifiers = "w411dp-h2200dp-port-420dpi")
    @Test fun pageLite() {
        open(FakeModels(mapOf("asr-runtime" to ModelState.Installed, "asr-stream-small" to ModelState.Installed), catalog = FakeModels.LITE))
        capture("page_lite")
    }

    @Config(qualifiers = "w411dp-h1900dp-port-420dpi")
    @Test fun pageDark() { open(FakeModels(mixed), dark = true); capture("page_dark") }

    @Test fun downloading() {
        open(
            FakeModels(
                mapOf(
                    "asr-stream-large" to ModelState.Downloading(Progress(54_400_000, 127_965_713, 3_200_000, "ghfast.top")),
                    "asr-sensevoice" to ModelState.Waiting,
                    "asr-paraformer" to ModelState.Extracting,
                ),
            ),
        )
        capture("downloading")
    }

    @Test fun failed() {
        open(FakeModels(mapOf("asr-stream-large" to ModelState.Failed("所有下载源均不可用，请检查网络后重试"), "asr-sensevoice" to ModelState.Failed("当前为移动网络，已按设置暂停下载"))))
        capture("failed")
    }

    @Config(qualifiers = "w411dp-h1900dp-port-420dpi")
    @Test fun allInstalled() { open(FakeModels(allInstalled), dark = true); capture("all_installed_dark") }

    /** 仅 Wi-Fi + 计流量：点下载先确认，同意后以 allowMetered = true 下载。 Metered confirmation flow. */
    @Test fun meteredConfirm() {
        val models = FakeModels(metered = true)
        open(models)
        compose.onAllNodesWithText("安装")[0].performClick()
        compose.waitForIdle()
        assertTrue(models.downloads.isEmpty())
        captureScreenRoboImage(File(dir, "models_metered_confirm.png").path)
        compose.onAllNodesWithText("下载").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        compose.waitForIdle()
        assertEquals(listOf("vad-silero" to true, "asr-stream-small" to true), models.downloads)
        assertEquals(ModelState.Waiting, models.state("asr-stream-small"))
    }

    /** 不计流量时直接下载，不弹框。 Unmetered: download right away. */
    @Test fun unmeteredDownloadsDirectly() {
        val models = FakeModels(metered = false)
        open(models)
        compose.onAllNodesWithText("安装")[0].performClick()
        compose.waitForIdle()
        assertEquals(listOf("vad-silero" to false, "asr-stream-small" to false), models.downloads)
    }

    /** 已安装的一项可单独卸载，需确认。 Uninstalling one installed item asks first. */
    @Test fun deleteConfirm() {
        val models = FakeModels(mapOf("asr-stream-large" to ModelState.Installed))
        open(models)
        compose.onNodeWithText("卸载").performScrollTo().performClick()
        compose.waitForIdle()
        captureScreenRoboImage(File(dir, "models_delete_confirm.png").path)
        compose.onAllNodesWithText("卸载").let { it[it.fetchSemanticsNodes().size - 1] }.performClick()
        compose.waitForIdle()
        assertEquals(ModelState.NotInstalled, models.state("asr-stream-large"))
    }

    @Config(qualifiers = "w411dp-h1900dp-port-420dpi")
    @Test fun sourceDialog() {
        val models = FakeModels(mixed).apply { mirrorPreference = "hfmirror" }
        open(models)
        compose.onNodeWithText("下载源").performScrollTo().performClick()
        compose.waitForIdle()
        captureScreenRoboImage(File(dir, "models_source_dialog.png").path)
        compose.onNodeWithText("gh-proxy.com").performClick()
        compose.waitForIdle()
        assertEquals("ghproxy", models.mirrorPreference)
    }

    @Test fun mirrorTemplate() {
        assertTrue(isValidMirrorTemplate("https://example.com/{url}"))
        assertFalse(isValidMirrorTemplate("https://example.com/"))
        assertFalse(isValidMirrorTemplate("example.com/{url}"))
    }

    @Test fun voiceListCard() {
        open(FakeModels(mixed), routes = listOf(Route.Home, Route.Voice))
        compose.onNodeWithText("已安装 1 项 · 点此完成离线语音下载").assertExists()
    }
}
