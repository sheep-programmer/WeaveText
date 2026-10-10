package com.weavetext.ime.settings

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.weavetext.ime.ui.keyboard.HandInkPrefs
import com.weavetext.ime.ui.keyboard.HandInkStyle
import com.weavetext.ime.ui.keyboard.HandwritingAreaMode
import org.junit.Assert.*
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
class HandwritingAppearanceSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = app.getSharedPreferences("hand-ink-compose-tests", Context.MODE_PRIVATE)

    @Before fun setup() {
        prefs.edit().clear().commit()
        compose.setContent {
            WeaveSettingsTheme(false) {
                Column(Modifier.verticalScroll(rememberScrollState())) { HandwritingAppearanceSettings(prefs) }
            }
        }
        compose.waitForIdle()
    }

    private fun previewState(value: String) {
        compose.onNodeWithTag("handwriting_ink_preview")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value))
    }

    private fun previewPixels(): IntArray {
        val node = compose.onNodeWithTag("handwriting_ink_preview").performScrollTo()
        compose.waitForIdle()
        // Roborazzi's native draw capture works in Robolectric, whose PixelCopy has no real window.
        val file = File.createTempFile("hand-ink-preview-", ".png")
        try {
            node.captureRoboImage(file.path)
            val png = file.readBytes()
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(png, 0, png.size)) { "Preview capture must produce a PNG" }
            return IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                bitmap.recycle()
            }
        } finally {
            file.delete()
        }
    }

    @Test fun areaChoicesPersistAndExternalChangesRefreshTheirSelection() {
        compose.onNodeWithTag("handwriting_area_keyboard").assertIsSelected()
        for (mode in listOf(HandwritingAreaMode.HALF, HandwritingAreaMode.FULL, HandwritingAreaMode.KEYBOARD)) {
            compose.onNodeWithTag("handwriting_area_${mode.key}").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(mode, WeavePrefs.handAreaMode(prefs))
            compose.onNodeWithTag("handwriting_area_${mode.key}").assertIsSelected()
        }
        compose.runOnIdle { prefs.edit().putString(WeavePrefs.HAND_AREA_MODE, "full").apply() }
        compose.waitForIdle()
        compose.onNodeWithTag("handwriting_area_full").assertIsSelected()
    }

    @Test fun eachToolSelectsAndChangesTheRealPreviewImmediately() {
        compose.onNodeWithText("毛笔").assertIsSelected()
        previewState("毛笔 · 6 dp · 跟随主题")
        var previous = previewPixels()
        for (tool in listOf(HandInkStyle.BALLPOINT, HandInkStyle.PENCIL, HandInkStyle.HIGHLIGHTER, HandInkStyle.BRUSH)) {
            compose.onNodeWithText(tool.label).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithText(tool.label).assertIsSelected()
            previewState("${tool.label} · 6 dp · 跟随主题")
            val changed = previewPixels()
            assertFalse("real outline must refresh for $tool", previous.contentEquals(changed))
            previous = changed
        }
    }

    @Test fun sliderUpdatesWidthAndActualInkBeforeLeavingThePage() {
        val before = previewPixels()
        compose.onNodeWithTag("handwriting_ink_width").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(12f)) }
        compose.waitForIdle()
        assertEquals(12f, HandInkPrefs.read(prefs).widthDp, 0f)
        previewState("毛笔 · 12 dp · 跟随主题")
        assertFalse(before.contentEquals(previewPixels()))
    }

    @Test fun presetAndCustomColorsApplyImmediatelyAndInvalidInputKeepsTheLastColor() {
        val original = previewPixels()
        compose.onNodeWithText("蓝色").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("蓝色").assertIsSelected()
        previewState("毛笔 · 6 dp · 蓝色")
        val blue = previewPixels()
        assertFalse(original.contentEquals(blue))
        compose.onNodeWithTag("handwriting_ink_custom_color").performScrollTo().performTextReplacement("#00a1b2")
        compose.waitForIdle()
        assertEquals("#00A1B2", HandInkPrefs.read(prefs).color)
        previewState("毛笔 · 6 dp · #00A1B2")
        assertFalse(blue.contentEquals(previewPixels()))
        compose.onNodeWithTag("handwriting_ink_custom_color").performScrollTo().performTextReplacement("#GG1234")
        compose.waitForIdle()
        compose.onNodeWithText("请输入 #RRGGBB 格式（6 位十六进制）").assertExists()
        assertEquals("#00A1B2", HandInkPrefs.read(prefs).color)
        previewState("毛笔 · 6 dp · #00A1B2")
    }

    @Test fun externalPreferenceChangesRefreshBothTheControlsAndTheChildPreview() {
        val before = previewPixels()
        compose.runOnIdle {
            prefs.edit().putString(HandInkPrefs.STYLE, "pencil").putFloat(HandInkPrefs.WIDTH_DP, 2f)
                .putString(HandInkPrefs.COLOR, "#DC2626").apply()
        }
        compose.waitForIdle()
        compose.onNodeWithText("铅笔").assertIsSelected()
        compose.onNodeWithText("红色").assertIsSelected()
        previewState("铅笔 · 2 dp · 红色")
        assertFalse(before.contentEquals(previewPixels()))
    }
}
