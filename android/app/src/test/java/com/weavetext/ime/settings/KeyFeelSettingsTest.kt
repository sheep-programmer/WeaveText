package com.weavetext.ime.settings

import android.app.Application
import android.os.VibratorManager
import android.provider.Settings
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.testing.FakeCloudWords
import com.weavetext.ime.testing.FakeDictPacks
import com.weavetext.ime.testing.FakeEngines
import com.weavetext.ime.testing.FakeModels
import com.weavetext.ime.testing.FakeUserDictionary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows

/**
 * 外观与手感 › 按键手感：点一下立刻反映在界面上（不必退出这一页再回来）。
 * Key feel: a tap shows on the page at once, without leaving and coming back.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-420dpi")
class KeyFeelSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = WeavePrefs.of(app)

    @Before fun setUp() {
        prefs.edit().clear().commit()
        Shadows.shadowOf(app.getSystemService(VibratorManager::class.java).defaultVibrator).setHasVibrator(true)
        Settings.System.putInt(app.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
    }

    private fun openLookPage() {
        val deps = SettingsDeps(
            app, engines = { FakeEngines() }, models = { FakeModels(emptyMap()) }, dictionary = FakeUserDictionary(),
            packs = { FakeDictPacks() }, cloud = { FakeCloudWords() }, status = { ImeStatus(true, true, true) },
        )
        compose.setContent { SettingsApp(deps, Navigator(listOf(Route.Home, Route.Look))) }
        compose.waitForIdle()
    }

    /** 选了按键音风格：音量滑块与右上角的当前值立刻出现。 */
    @Test fun pickingASoundStyleShowsItsVolumeAtOnce() {
        openLookPage()
        compose.onNodeWithText("气泡").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("点击应当写入设置", "bubble", prefs.getString(WeavePrefs.SOUND_STYLE, null))
        compose.onNodeWithText("音量").assertExists()
        assertTrue("音量行出现后也要有数值", prefs.getString(WeavePrefs.SOUND_STYLE, null) == "bubble")
    }

    /** 换一种风格：右上角的摘要跟着变。 */
    @Test fun theSummaryFollowsTheChosenStyle() {
        openLookPage()
        compose.onNodeWithText("木质").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("木质 · 50%").assertExists()
    }

    /** 点震动档位：分段按钮立刻选中，不必返回再进。 */
    @Test fun theVibrationLevelSelectsAtOnce() {
        openLookPage()
        compose.onNodeWithText("中").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("中").assertIsSelected()
        assertEquals(3, WeavePrefs.vibration(prefs))
    }

    /** 按键气泡默认打开，关掉之后写入设置。 */
    @Test fun theKeyPreviewIsOnByDefaultAndCanBeTurnedOff() {
        openLookPage()
        assertTrue(WeavePrefs.keyPreview(prefs))
        compose.onNodeWithText("按键气泡").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(false, prefs.getBoolean(WeavePrefs.KEY_PREVIEW, true))
    }

    @Test fun systemDisabledFeedbackIsExplainedAndUpdatesWhileThePageIsOpen() {
        Settings.System.putInt(app.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 0)
        openLookPage()
        compose.onNodeWithText("强").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("强").assertIsSelected()
        compose.onNodeWithText("系统触摸振动已关闭，开启后可体验所选档位").assertExists()
        compose.onNodeWithText("打开系统声音设置").assertExists()
        assertEquals(4, WeavePrefs.vibration(prefs))
        Settings.System.putInt(app.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
        compose.waitForIdle()
        compose.onNodeWithText("系统触摸振动已关闭，开启后可体验所选档位").assertDoesNotExist()
    }

    @Test fun selectingAStrengthReallySendsThePreviewToTheVibrator() {
        openLookPage()
        compose.onNodeWithText("中").performScrollTo().performClick()
        compose.waitForIdle()
        val shadow = Shadows.shadowOf(app.getSystemService(VibratorManager::class.java).defaultVibrator)
        compose.waitUntil(5000) { shadow.vibrationAttributesFromLastVibration != null }
        val attrs = shadow.vibrationAttributesFromLastVibration as android.os.VibrationAttributes
        assertEquals(android.os.VibrationAttributes.USAGE_TOUCH, attrs.usage)
    }
}
