package com.weavetext.ime.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weavetext.ime.plugins.GitHubCredentials
import com.weavetext.ime.plugins.GitHubHttp
import com.weavetext.ime.plugins.GitHubPlugins
import com.weavetext.ime.plugins.GitHubRepository
import com.weavetext.ime.settings.ImeStatus
import com.weavetext.ime.settings.Navigator
import com.weavetext.ime.settings.Route
import com.weavetext.ime.debug.SettingsTestActivity
import com.weavetext.ime.settings.SettingsApp
import com.weavetext.ime.settings.SettingsDeps
import com.weavetext.ime.voice.VoiceEngines
import com.weavetext.ime.voice.VoicePlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.util.UUID

/** Real Android dialog roots and paste actions, using a controlled GitHub API and isolated preferences. */
@RunWith(AndroidJUnit4::class)
class RepositoryAddressDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation

    private fun nodes(): List<AccessibilityNodeInfo> {
        fun walk(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null || !node.refresh()) emptyList()
            else listOf(node) + (0 until node.childCount).flatMap { walk(node.getChild(it)) }
        return automation.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.flatMap { walk(it.root) }
    }

    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 10_000
        do {
            nodes().firstOrNull(predicate)?.let { return it }
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        automation.takeScreenshot()?.let { bmp ->
            File(instrumentation.targetContext.cacheDir, "repository-address-test.png").outputStream().use {
                bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        error("Expected repository control did not appear; text=" + nodes().mapNotNull { it.text?.toString()?.takeIf(String::isNotEmpty) })
    }

    private fun text(label: String) = awaitNode { it.text?.contains(label) == true }
    private fun clickText(label: String) {
        val node = awaitNode { it.text?.toString() == label && it.isVisibleToUser }
        val bounds = Rect().also(node::getBoundsInScreen)
        check(!bounds.isEmpty) { "Repository control has no bounds: $label" }
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        instrumentation.waitForIdleSync()
    }

    private fun paste(index: Int, value: String) {
        val editors = nodes().filter { it.isEditable }.sortedBy { node -> Rect().also(node::getBoundsInScreen).top }
        check(index in editors.indices) { "Repository input field is missing" }
        assertTrue(editors[index].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
        instrumentation.waitForIdleSync()
    }

    @Test fun completeAddressesCanBePreviewedCorrectedAndSaved() {
        val app = instrumentation.targetContext
        val namespace = "repository-url-${UUID.randomUUID()}"
        val root = File(app.cacheDir, namespace).apply { mkdirs() }
        val prefsNames = mutableSetOf<String>()
        val ctx = object : ContextWrapper(app) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("$namespace-$name", mode).also { prefsNames += "$namespace-$name" }
        }
        val credentials = GitHubCredentials(ctx)
        val service = GitHubPlugins(ctx, credentials, GitHubHttp(credentials) { url ->
            object : HttpURLConnection(url) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy() = false
                override fun getResponseCode() = 200
                override fun getInputStream() = ByteArrayInputStream(when {
                    "/commits/" in url.path -> """{"sha":"${"a".repeat(40)}","commit":{"tree":{"sha":"${"b".repeat(40)}"}}}"""
                    "/git/trees/" in url.path -> """{"tree":[],"truncated":false}"""
                    url.path.endsWith("/releases") -> "[]"
                    else -> """{"private":false,"default_branch":"main"}"""
                }.toByteArray())
            }
        })
        val local = VoicePlugin("weave.local", "离线语音", "", "", null, emptyList())
        val engines = object : VoiceEngines {
            override var activeId: String? = local.id
            override fun list() = listOf(local)
            override fun getConfig(id: String, key: String): String? = null
            override fun setConfig(id: String, key: String, value: String) {}
            override fun install(xipkPath: String) = Result.failure<VoicePlugin>(UnsupportedOperationException())
            override fun uninstall(id: String) = Result.failure<Unit>(UnsupportedOperationException())
        }
        val saved = automation.serviceInfo
        val flags = saved.flags
        automation.serviceInfo = saved.apply { this.flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        try {
            ActivityScenario.launch(SettingsTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val deps = SettingsDeps(activity, prefs = ctx.getSharedPreferences("weave", Context.MODE_PRIVATE),
                        engines = { engines }, pluginRepositories = { service }, status = { ImeStatus(true, true, true) })
                    activity.setContent { SettingsApp(deps, Navigator(listOf(Route.Home, Route.Voice, Route.PluginRepositories))) }
                }
                clickText("添加仓库")
                awaitNode { it.isEditable }
                paste(0, "https://github.com.evil.example/demo/plugins")
                clickText("加入")
                text("请输入 GitHub 的 HTTPS 仓库地址")
                text("加入 GitHub 仓库")
                assertTrue(service.saved().isEmpty())
                paste(0, "https://www.github.com/demo/plugins.git?tab=readme#overview")
                text("仓库：demo/plugins")
                text("分支：默认分支 · 目录：仓库根目录")
                clickText("加入")
                assertEquals(listOf(GitHubRepository("demo", "plugins")), service.saved())
                val closedBy = SystemClock.uptimeMillis() + 10_000
                while (nodes().any { it.text?.toString() == "加入 GitHub 仓库" }) {
                    check(SystemClock.uptimeMillis() < closedBy) { "Repository dialog did not close" }
                    SystemClock.sleep(100)
                }
                awaitNode { it.text?.toString() == "plugins" }
                clickText("添加仓库")
                awaitNode { it.isEditable }
                paste(0, "https://github.com/demo/plugins/tree/main/voice")
                text("分支：main · 目录：voice")
                paste(1, "release/custom")
                text("分支：release/custom · 目录：voice")
                clickText("取消")
                assertEquals(1, service.saved().size)
            }
        } finally {
            prefsNames.forEach(app::deleteSharedPreferences)
            root.deleteRecursively()
            saved.flags = flags
            automation.serviceInfo = saved
        }
    }
}
