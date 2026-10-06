package com.weavetext.ime.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.weavetext.ime.plugins.GitHubCredentials
import com.weavetext.ime.plugins.GitHubHttp
import com.weavetext.ime.plugins.GitHubPlugins
import com.weavetext.ime.plugins.GitHubRepository
import com.weavetext.ime.settings.ImeStatus
import com.weavetext.ime.settings.Navigator
import com.weavetext.ime.settings.Route
import com.weavetext.ime.settings.SettingsApp
import com.weavetext.ime.settings.SettingsDeps
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.testing.FakeEngines
import com.weavetext.ime.testing.FakeModels
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-420dpi")
class PluginRepositoryListTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val plugins = ConcurrentHashMap<String, List<String>>()
    private val failures = ConcurrentHashMap<String, Boolean>()
    private lateinit var service: GitHubPlugins

    @Before fun setup() {
        app.getSharedPreferences("plugin_repositories", 0).edit().clear().commit()
        app.getSharedPreferences("github_credentials", 0).edit().clear().commit()
        WeavePrefs.of(app).edit().clear().commit()
        plugins["voice-plugins"] = listOf("streaming-asr", "punctuation", "translation")
        plugins["personal-tools"] = listOf("custom-asr")
        val credentials = GitHubCredentials(app) { SecretKeySpec(ByteArray(32), "AES") }
        service = GitHubPlugins(app, credentials, GitHubHttp(credentials) { url ->
            object : HttpURLConnection(url) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy() = false
                override fun getResponseCode() = if (failures[url.path.split('/').getOrNull(3)] == true) 404 else 200
                override fun getInputStream(): ByteArrayInputStream {
                    val name = url.path.split('/')[3]
                    val body = when {
                        url.path.endsWith("/releases") -> "[]"
                        "/commits/" in url.path -> """{"sha":"${"a".repeat(40)}","commit":{"tree":{"sha":"${"b".repeat(40)}"}}}"""
                        "/git/trees/" in url.path -> {
                            val tree = JSONArray()
                            plugins.getValue(name).forEach { p ->
                                tree.put(JSONObject().put("path", "$p/manifest.yaml").put("type", "blob").put("mode", "100644").put("sha", "c".repeat(40)).put("size", 50))
                            }
                            JSONObject().put("tree", tree).put("truncated", false).toString()
                        }
                        else -> """{"private":${name == "personal-tools"},"default_branch":"main"}"""
                    }
                    return ByteArrayInputStream(body.toByteArray())
                }
            }
        })
        service.save(GitHubRepository.parse("demo/voice-plugins"))
        service.save(GitHubRepository.parse("demo/personal-tools"))
    }

    private fun show(dark: Boolean = false) {
        WeavePrefs.of(app).edit().putString(WeavePrefs.THEME, if (dark) "dark" else "light").commit()
        val deps = SettingsDeps(app, engines = { FakeEngines() }, models = { FakeModels(emptyMap()) },
            status = { ImeStatus(true, true, true) }, pluginRepositories = { service })
        compose.setContent { SettingsApp(deps, Navigator(listOf(Route.Home, Route.Voice, Route.PluginRepositories))) }
        val expectedCounts = plugins.values.map { it.size }.groupingBy { it }.eachCount()
        compose.waitUntil(10_000) {
            expectedCounts.all { (count, repositories) ->
                compose.onAllNodesWithText("$count 项插件").fetchSemanticsNodes().size == repositories
            }
        }
        compose.waitForIdle()
    }

    @Test fun countsAndIndependentExpansionStayInsideTheirRepository() {
        show()
        compose.onNodeWithText("3 项插件").assertExists()
        compose.onNodeWithText("1 项插件").assertExists()
        compose.onNodeWithText("streaming-asr").assertDoesNotExist()
        compose.onNodeWithText("voice-plugins").performClick()
        compose.onNodeWithText("streaming-asr").assertExists()
        compose.onNodeWithText("custom-asr").assertDoesNotExist()
        compose.onNodeWithText("voice-plugins").performClick()
        compose.onNodeWithText("streaming-asr").assertDoesNotExist()
        compose.onNodeWithText("personal-tools").performClick()
        compose.onNodeWithText("custom-asr").assertExists()
        compose.onNodeWithText("3 项插件").assertExists()
    }

    @Test fun emptyRepositoryShowsZeroAndRefreshUpdatesItsCount() {
        plugins["voice-plugins"] = emptyList()
        show()
        compose.onNodeWithText("0 项插件").assertExists()
        compose.onNodeWithText("voice-plugins").performClick()
        compose.onNodeWithText("这个仓库还没有可导入的插件").assertExists()
        plugins["voice-plugins"] = listOf("new-asr")
        compose.onNodeWithText("刷新").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("new-asr").fetchSemanticsNodes().size == 1 }
        compose.waitForIdle()
        compose.onNodeWithText("new-asr").assertExists()
        compose.onNodeWithText("这个仓库还没有可导入的插件").assertDoesNotExist()
        assertEquals(2, compose.onAllNodesWithText("1 项插件").fetchSemanticsNodes().size)
    }

    @Test fun expandedListLight() { show(); compose.onNodeWithText("voice-plugins").performClick(); snapshot("expanded") }
    @Test fun collapsedListDark() { show(true); snapshot("collapsed_dark") }
    private fun snapshot(name: String) {
        compose.waitForIdle()
        val dir = File(System.getProperty("weave.snapshotDir") ?: "build/snapshots")
        compose.onRoot().captureRoboImage(File(dir, "settings_plugin_repositories_$name.png").path)
    }
}
