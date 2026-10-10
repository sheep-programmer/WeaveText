package com.weavetext.ime.extensions

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.style.StyleRepository
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExtensionStoreTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = WeavePrefs.of(app)
    private val store get() = ExtensionStore(app)
    @Before fun reset() {
        prefs.edit().clear().commit()
        File(app.filesDir, "extensions").deleteRecursively()
    }
    @Test fun disabledToolsAndSchemesPersistWithoutChangingOtherChoices() {
        prefs.edit().putString(WeavePrefs.KEYBOARDS, "hand,wubi86,pinyin,english").putString(WeavePrefs.ACTIVE_KEYBOARD, "hand").commit()
        Extensions.setEnabled(prefs, "scheme:hand", false)
        Extensions.setEnabled(prefs, "scheme:wubi86", false)
        Extensions.setEnabled(prefs, "feature:voice", false)
        assertEquals(listOf("pinyin", "english"), WeavePrefs.keyboards(prefs))
        assertEquals("pinyin", WeavePrefs.activeKeyboard(prefs))
        assertEquals(listOf("menu", "clipboard", "hide"), WeavePrefs.toolbarItems(prefs, listOf("menu", "voice", "clipboard", "hide")))
        assertTrue(Extensions.feature(prefs, "translate"))
        assertFalse(Extensions.feature(WeavePrefs.of(app), "voice"))
        Extensions.setEnabled(prefs, "scheme:hand", true)
        assertEquals("hand", WeavePrefs.activeKeyboard(prefs))
    }
    @Test fun everyBundledPackageInstallsAndLayoutsKeepTheirTheme() {
        val s = store
        assertEquals(Extensions.defaults, s.items.filter { it.builtin }.map { it.key }.toSet())
        for (item in s.items.filter { it.source == "bundled" }) s.install(item)
        val repo = StyleRepository.get(app)
        assertEquals(7, repo.layoutIds.size)
        assertEquals(16, repo.themeIds.size)
        for (id in repo.layoutIds) {
            assertEquals(id, repo.layout(id).id)
            assertTrue(repo.layout(id).theme in repo.themeIds)
        }
    }
    @Test fun uninstallingCurrentThemeFallsBackAndInvalidatesTheResolvedStyle() {
        val s = store
        val item = s.items.first { it.key == "theme:sakura" }
        s.install(item)
        prefs.edit().putString(WeavePrefs.STYLE_THEME, "sakura").commit()
        val repo = StyleRepository.get(app)
        assertEquals("sakura", repo.resolve(app, prefs).theme.id)
        s.uninstall(item)
        assertEquals("fresh", repo.resolve(app, prefs).theme.id)
        assertFalse("sakura" in repo.themeIds)
    }
    @Test fun upgradingKeepsTheSelectedLayoutAndItsTheme() {
        prefs.edit().putString(WeavePrefs.STYLE_LAYOUT, "bright").commit()
        store.migrateSelected()
        val repo = StyleRepository.get(app)
        assertEquals("bright", repo.resolve(app, prefs).layout.id)
        assertEquals("azure", repo.resolve(app, prefs).theme.id)
    }
    @Test fun upgradingClassicKeepsItsOldAutomaticOrangeTheme() {
        prefs.edit().putString(WeavePrefs.STYLE_LAYOUT, "classic").commit()
        store.migrateSelected()
        assertEquals("amber", WeavePrefs.styleTheme(prefs))
        assertEquals("amber", StyleRepository.get(app).resolve(app, prefs).theme.id)
    }
    @Test fun importingCustomThemeSurvivesReloadAndRejectsTraversalOrBuiltins() {
        val json = app.assets.open("styles/theme-fresh.json").bufferedReader().use { it.readText() }
        val custom = json.replace("\"id\": \"fresh\"", "\"id\": \"custom\"")
        val item = store.importData(custom.toByteArray())
        assertEquals("theme:custom", item.key)
        assertTrue(store.installed(item))
        assertTrue(store.items.any { it.key == item.key })
        assertThrows(IllegalArgumentException::class.java) { store.importData(json.toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { store.importData(custom.replace("\"custom\"", "\"../escape\"").toByteArray()) }
    }
    @Test fun oversizedStreamsFailBeforeParsingOrSavingAnything() {
        assertThrows(IllegalArgumentException::class.java) {
            store.importData(java.io.ByteArrayInputStream(ByteArray(256 * 1024 + 1)))
        }
        assertFalse(File(store.root, "theme").exists())
    }
    @Test fun moduleOffAlsoGatesNetworkPreferencesAndCannotRemoveBaseData() {
        prefs.edit().putBoolean(WeavePrefs.CLOUD_WORDS, true).putBoolean(WeavePrefs.LINK_ENABLED, true).commit()
        Extensions.setEnabled(prefs, "feature:link", false)
        Extensions.setEnabled(prefs, "feature:cloudwords", false)
        assertFalse(WeavePrefs.linkEnabled(prefs)); assertFalse(WeavePrefs.cloudWords(prefs))
        Extensions.setEnabled(prefs, "feature:link", true)
        assertTrue(WeavePrefs.linkEnabled(prefs))
        assertThrows(IllegalArgumentException::class.java) { store.uninstall(store.items.first { it.key == "theme:fresh" }) }
    }
}
