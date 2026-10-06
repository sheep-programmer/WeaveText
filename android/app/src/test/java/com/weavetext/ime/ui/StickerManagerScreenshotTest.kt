package com.weavetext.ime.ui

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.weavetext.ime.settings.WeaveSettingsTheme
import com.weavetext.ime.stickers.StickerManagerScreen
import com.weavetext.ime.stickers.StickerRepository
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import org.junit.Assert.assertEquals
import java.io.File

/**
 * 管理页截图：单独一个类，不和键盘截图共用 Activity（共用时 Compose 画不出来，截到一片空白）。
 * Manager screenshots in their own class: sharing the keyboard tests' activity left the Compose capture blank.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-420dpi")
class StickerManagerScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val dir = File(System.getProperty("weave.snapshotDir") ?: "build/snapshots")

    @Before fun samples() {
        StickerRepository.io.submit {}.get(); StickerRepository.reset(); File(app.filesDir, "stickers").deleteRecursively()
        val root = generateSequence(File(checkNotNull(System.getProperty("user.dir")))) { it.parentFile }.first { File(it, "tests/fixtures/stickers").isDirectory }
        val store = StickerRepository.get(app).store
        for ((name, title) in listOf("sample.png" to "开心", "animated.gif" to "晚安", "animated.webp" to "收到")) {
            val (item, _) = store.import(File(root, "tests/fixtures/stickers/$name").inputStream(), title)
            store.edit(item.id, title, "日常", listOf("常用"), name == "sample.png")
        }
    }

    private fun shot(name: String, dark: Boolean, before: () -> Unit = {}, act: () -> Unit = {}) {
        before()
        compose.setContent {
            WeaveSettingsTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { StickerManagerScreen(onBack = {}) }
            }
        }
        // 缩略图在后台解码：等它们出来再截。 Thumbnails decode in the background; wait for them.
        val end = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < end) { Thread.sleep(50); compose.waitForIdle() }
        act()
        val settle = System.nanoTime() + 600_000_000L
        while (System.nanoTime() < settle) { Thread.sleep(50); compose.waitForIdle() }
        compose.onRoot().captureRoboImage(File(dir, "$name.png").path)
    }

    private fun emptyStore() {
        StickerRepository.io.submit {}.get(); StickerRepository.reset(); File(app.filesDir, "stickers").deleteRecursively()
    }

    private fun selectOne() {
        compose.onNodeWithContentDescription("更多").performClick()
        compose.onNodeWithText("整理").performClick()
        compose.onNodeWithText("开心").performClick()
    }

    @Test fun singleDeleteConfirmationCanBeCancelledBeforeAnyOriginalIsRemoved() {
        shot("sticker_manager_delete_confirmation_light", false, act = {
            compose.onNodeWithText("开心").performTouchInput { longClick() }
            compose.onNodeWithText("删除").performClick()
            compose.onNodeWithText("删除这张表情？").assertExists()
            assertEquals(3, StickerRepository.get(app).store.list().size)
        })
        compose.onNodeWithText("取消").performClick(); compose.waitForIdle()
        assertEquals(3, StickerRepository.get(app).store.list().size)
    }
    @Test fun batchDeleteAlsoWaitsForConfirmation() {
        shot("sticker_manager_batch_delete_confirmation_dark", true, act = {
            selectOne()
            compose.onNodeWithText("晚安").performClick()
            compose.onNodeWithText("删除").performClick()
            compose.onNodeWithText("删除 2 张表情？").assertExists()
            assertEquals(3, StickerRepository.get(app).store.list().size)
        })
        compose.onNode(hasText("删除") and hasAnyAncestor(isDialog())).performClick()
        StickerRepository.io.submit {}.get(); compose.waitForIdle()
        assertEquals(1, StickerRepository.get(app).store.list().size)
    }

    @Test fun managerLight() = shot("sticker_manager_light", false)
    @Test fun managerDark() = shot("sticker_manager_dark", true)
    @Test fun managerSelecting() = shot("sticker_manager_selecting_light", false, act = ::selectOne)
    @Test fun managerSelectingDark() = shot("sticker_manager_selecting_dark", true, act = ::selectOne)
    @Test fun managerEmpty() = shot("sticker_manager_empty_light", false, before = ::emptyStore)
    @Test fun managerEmptyDark() = shot("sticker_manager_empty_dark", true, before = ::emptyStore)
}
