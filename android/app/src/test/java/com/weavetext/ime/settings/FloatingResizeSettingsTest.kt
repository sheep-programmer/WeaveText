package com.weavetext.ime.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.ui.keyboard.FloatingResizeCorner
import com.weavetext.ime.ui.keyboard.FloatingResizePolicy
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
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-port-420dpi")
class FloatingResizeSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val prefs get() = ApplicationProvider.getApplicationContext<Application>()
        .getSharedPreferences("floating_resize_test", Application.MODE_PRIVATE)

    @Before fun setUp() { prefs.edit().clear().commit() }

    @Test fun defaultIsAllCornersAndDoesNotWriteOrChangeExistingSettings() {
        prefs.edit().putBoolean("floating", true).putString("float_pos_port", "0.3,0.6")
            .putString("float_size_land", "0.850").commit()
        val before = prefs.all.toMap()
        assertEquals(FloatingResizeSettings.DEFAULT_ALL, FloatingResizeSettings.cornerMask(prefs))
        assertEquals(before, prefs.all)
        assertFalse(prefs.contains(FloatingResizeSettings.KEY_CORNERS))
    }

    @Test fun allFifteenNonemptyCornerSetsPersistAndCanBeReadFromAnotherPreferencesHandle() {
        for (mask in 1..15) {
            FloatingResizeSettings.writeMask(prefs, mask)
            val reopened = ApplicationProvider.getApplicationContext<Application>()
                .getSharedPreferences("floating_resize_test", Application.MODE_PRIVATE)
            assertEquals(mask, FloatingResizeSettings.cornerMask(reopened))
            assertEquals(FloatingResizePolicy.fromMask(mask), FloatingResizeSettings.read(reopened))
            assertEquals(FloatingResizeCorner.entries.filter { mask and it.bit != 0 }.map { it.id }.toSet(),
                reopened.getStringSet(FloatingResizeSettings.KEY_CORNERS, null))
        }
    }

    @Test fun asyncPreferenceWritesAreSerializedToTheSharedPreferencesFile() {
        FloatingResizeSettings.writeMask(prefs, 6)
        // An empty synchronous edit waits for all preceding apply() disk writes on this store.
        assertTrue(prefs.edit().commit())
        val app = ApplicationProvider.getApplicationContext<Application>()
        val file = File(app.applicationInfo.dataDir, "shared_prefs/floating_resize_test.xml")
        assertTrue("The helper's preference update must reach disk", file.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val sets = doc.getElementsByTagName("set")
        val values = mutableSetOf<String>()
        for (index in 0 until sets.length) {
            val node = sets.item(index)
            if (node.attributes.getNamedItem("name").nodeValue != FloatingResizeSettings.KEY_CORNERS) continue
            for (child in 0 until node.childNodes.length) {
                val value = node.childNodes.item(child)
                if (value.nodeName == "string") values += value.textContent
            }
        }
        assertEquals(setOf("top_right", "bottom_left"), values)
    }

    @Test fun emptyUnknownAndWrongTypeStoredValuesRecoverToAllCorners() {
        prefs.edit().putStringSet(FloatingResizeSettings.KEY_CORNERS, emptySet()).commit()
        assertEquals(15, FloatingResizeSettings.cornerMask(prefs))
        prefs.edit().putStringSet(FloatingResizeSettings.KEY_CORNERS, setOf("unknown")).commit()
        assertEquals(15, FloatingResizeSettings.cornerMask(prefs))
        prefs.edit().putString(FloatingResizeSettings.KEY_CORNERS, "top_left").commit()
        assertEquals(15, FloatingResizeSettings.cornerMask(prefs))
        prefs.edit().putStringSet(FloatingResizeSettings.KEY_CORNERS, setOf("unknown", "bottom_left")).commit()
        assertEquals(4, FloatingResizeSettings.cornerMask(prefs))
    }

    @Test fun writesAndResetOnlyChangeTheHelpersOwnKey() {
        prefs.edit().putBoolean("floating", true).putString("float_pos_land", "0.2,0.8")
            .putString("float_size_port", "1.100").putInt("vibration", 3).commit()
        val before = prefs.all.toMap()
        FloatingResizeSettings.write(prefs, setOf(FloatingResizeCorner.TOP_RIGHT, FloatingResizeCorner.BOTTOM_LEFT))
        assertEquals(6, FloatingResizeSettings.cornerMask(prefs))
        assertEquals(before, prefs.all.filterKeys { it != FloatingResizeSettings.KEY_CORNERS })
        FloatingResizeSettings.reset(prefs)
        assertEquals(before, prefs.all)
        assertEquals(15, FloatingResizeSettings.cornerMask(prefs))
    }

    @Test fun emptySelectionIsRejectedWithoutChangingStoredData() {
        FloatingResizeSettings.writeMask(prefs, 8)
        try {
            FloatingResizeSettings.write(prefs, emptySet())
            throw AssertionError("An empty selection was accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals(8, FloatingResizeSettings.cornerMask(prefs))
    }

    @Test fun independentComposeCardShowsDefaultsAndPersistsChangesImmediately() {
        compose.setContent { MaterialTheme { FloatingResizeSettingsCard(prefs) } }
        for (corner in FloatingResizeCorner.entries) compose.onNodeWithText(corner.label).assertIsOn()
        compose.onNodeWithText("左上角").performClick().assertIsOff()
        assertEquals(14, FloatingResizeSettings.cornerMask(prefs))
        compose.onNodeWithText("左上角").performClick().assertIsOn()
        assertEquals(15, FloatingResizeSettings.cornerMask(prefs))
    }

    @Test fun composeCardKeepsTheLastCornerEnabledAndReflectsExternalWrites() {
        FloatingResizeSettings.writeMask(prefs, 1)
        compose.setContent { MaterialTheme { FloatingResizeSettingsCard(prefs) } }
        compose.onNodeWithText("左上角").assertIsOn().assertIsNotEnabled()
        compose.onNodeWithText("右下角").performClick().assertIsOn()
        compose.onNodeWithText("左上角").performClick().assertIsOff()
        assertEquals(8, FloatingResizeSettings.cornerMask(prefs))
        compose.onNodeWithText("右下角").assertIsNotEnabled()
        compose.runOnIdle { FloatingResizeSettings.writeMask(prefs, 6) }
        compose.onNodeWithText("右上角").assertIsOn()
        compose.onNodeWithText("左下角").assertIsOn()
        compose.onNodeWithText("右下角").assertIsOff()
    }
}
