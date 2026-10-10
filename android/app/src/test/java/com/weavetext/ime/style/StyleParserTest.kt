package com.weavetext.ime.style

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.settings.WeavePrefs
import com.weavetext.ime.ui.keyboard.KeyShadow
import com.weavetext.ime.ui.keyboard.Layouts
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 风格 JSON 解析、校验与合并（预设 + 主题 + 微调）。
 * Style JSON parsing, validation and merging (preset + theme + tweaks).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StyleParserTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var repo: StyleRepository

    @Before fun setUp() {
        WeavePrefs.of(app).edit().clear().commit()
        repo = StyleRepository.get(app)
    }

    private fun layout(extra: String) = StyleParser.layout(JSONObject("""{"id":"t",$extra}"""))

    private fun assertRejects(what: String, block: () -> Unit) {
        try { block(); fail("应拒绝：$what") } catch (e: StyleException) { assertTrue(e.message!!.isNotEmpty()) }
    }

    @Test fun builtInsParse() {
        assertEquals(setOf("fresh", "classic"), repo.layoutIds.toSet())
        assertEquals(setOf("fresh", "ink", "mint", "dusk", "dynamic"), repo.themeIds.toSet())
        for (id in repo.layoutIds) {
            val l = repo.layout(id)
            assertEquals(id, l.id)
            assertTrue("$id 的默认主题 ${l.theme} 不存在", l.theme in repo.themeIds)
        }
        for (id in repo.themeIds) assertEquals(id, repo.theme(id).id)
    }

    @Test fun builtInNamesAreUnique() {
        assertEquals(repo.layoutIds.size, repo.layoutIds.map { repo.layout(it).name }.toSet().size)
        assertEquals(repo.themeIds.size, repo.themeIds.map { repo.theme(it).name }.toSet().size)
    }

    /** 默认布局文件与代码内置默认一致，现有外观由它产生。 The default layout file equals the in-code default. */
    @Test fun defaultLayoutMatchesBuiltIn() {
        val l = repo.layout(StyleRepository.DEFAULT_LAYOUT)
        val d = Layouts.DEFAULT
        assertEquals(d.qwerty.rows.toString(), l.qwerty.rows.toString())
        assertEquals(d.t9.right.toString(), l.t9.right.toString())
        assertEquals(d.t9.bottom.toString(), l.t9.bottom.toString())
        assertEquals(d.toolbar.items, l.toolbar.items)
        assertEquals(d.geometry.gapV, l.geometry.gapV)
        assertEquals(d.geometry.radius, l.geometry.radius)
        assertEquals(d.labels.symbol, l.labels.symbol)
        assertEquals(d.popup.bubble, l.popup.bubble)
    }

    @Test fun defaultThemeTokens() {
        val t = repo.theme(StyleRepository.DEFAULT_THEME)
        assertEquals(0xFFE8EBF0.toInt(), t.light.background)
        assertEquals(0xFFFFFFFF.toInt(), t.light.key)
        assertEquals(0x2E1E283C, t.light.popupShadow)
        assertEquals(0xFF121315.toInt(), t.dark.background)
        assertEquals(0x7A000000, t.dark.scrim)
        assertEquals(KeyShadow.BAR, t.light.shadow)
    }

    @Test fun deepMergeReplacesArraysAndMergesObjects() {
        val base = JSONObject("""{"a":{"x":1,"y":2},"list":[1,2,3],"keep":true}""")
        val over = JSONObject("""{"a":{"y":5,"z":6},"list":[9]}""")
        val m = StyleParser.merge(base, over)
        assertEquals(1, m.getJSONObject("a").getInt("x"))
        assertEquals(5, m.getJSONObject("a").getInt("y"))
        assertEquals(6, m.getJSONObject("a").getInt("z"))
        assertEquals(1, m.getJSONArray("list").length())
        assertTrue(m.getBoolean("keep"))
        // 不修改输入。 Inputs untouched.
        assertEquals(2, base.getJSONObject("a").getInt("y"))
    }

    @Test fun unknownFieldsIgnored() {
        val l = layout(""""future":{"x":1},"qwerty":{"showHints":false,"sparkle":true}""")
        assertFalse(l.qwerty.showHints)
    }

    @Test fun rejectsInvalidLayouts() {
        assertRejects("枚举") { layout(""""qwerty":{"hint":"left"}""") }
        assertRejects("范围") { layout(""""geometry":{"gapH":40}""") }
        assertRejects("类型") { layout(""""geometry":{"gapH":"wide"}""") }
        assertRejects("缺字母") { layout(""""qwerty":{"rows":[["qwertyuiop"],["asdfghjkl"],["zxcvbn"],["space","delete","enter"]]}""") }
        assertRejects("重复字母") { layout(""""qwerty":{"rows":[["qwertyuiop"],["asdfghjkl"],["zxcvbnmm"],["space","delete","enter"]]}""") }
        assertRejects("未知键") { layout(""""qwerty":{"rows":[["qwertyuiop"],["asdfghjkl"],["zxcvbnm"],["space","delete","enter","rocket"]]}""") }
        assertRejects("缺空格") { layout(""""qwerty":{"rows":[["qwertyuiop"],["asdfghjkl"],["zxcvbnm","delete"],["enter"]]}""") }
        assertRejects("九键跨行") { layout(""""t9":{"right":["delete","enter"]}""") }
        assertRejects("工具栏") { layout(""""toolbar":{"items":["voice","hide","cursor"]}""") }
        assertRejects("id") { StyleParser.layout(JSONObject("""{"id":"Bad Id"}""")) }
        assertRejects("版本") { StyleParser.layout(JSONObject("""{"id":"t","version":99}""")) }
    }

    private fun install(key: String) {
        val store = com.weavetext.ime.extensions.ExtensionStore(app)
        store.install(store.items.first { it.key == key })
    }

    @Test fun numberRowIsOptional() {
        install("layout:numrow")
        val l = repo.layout("numrow")
        assertEquals(5, l.qwerty.rows.size)
        val keys = Layouts.qwerty(english = false, l.qwerty, l.labels)
        assertEquals("1234567890", keys.filter { it.row == 0 }.joinToString("") { it.label })
        assertEquals(listOf("0", "⁰", "₀", "⓪"), keys.first { it.code == '0'.code }.longPress)
        assertRejects("数字行缺数字") { layout(""""qwerty":{"rows":[["123456789"],["qwertyuiop"],["asdfghjkl"],["zxcvbnm"],["space","delete","enter"]]}""") }
        assertRejects("数字不在首行") { layout(""""qwerty":{"rows":[["qwertyuiop"],["1234567890"],["asdfghjkl"],["zxcvbnm"],["space","delete","enter"]]}""") }
        assertRejects("6 行") { layout(""""qwerty":{"rows":[["1234567890"],["qwertyuiop"],["asdfghjkl"],["zxcvbnm"],["space","delete","enter"],["space"]]}""") }
    }

    @Test fun customRowsBuildKeys() {
        val l = layout(""""qwerty":{"rows":[["qwertyuiop"],["asdfghjkl"],["shift:1.5","zxcvbnm","delete:1.5"],["number:1.5","emoji","space:5","period","enter:1.5"]],"letterCase":"lower"}""")
        val keys = Layouts.qwerty(english = false, l.qwerty, l.labels)
        assertEquals(26 + 2 + 5, keys.size)
        assertEquals("q", keys.first().label)
        assertTrue(keys.any { it.code == com.weavetext.ime.ui.keyboard.KeyCode.EMOJI })
        assertFalse(keys.any { it.code == com.weavetext.ime.ui.keyboard.KeyCode.SYMBOL })
    }

    @Test fun themeDerivesMissingColours() {
        val t = StyleParser.theme(
            JSONObject(
                """{"id":"mini","light":{"background":"#F0F0F0","key":"#FFFFFF","label":"#202020","accent":"#1565C0"},
                "dark":{"background":{"type":"gradient","colors":["#101820","#203040"]},"key":"#303840","label":"#F0F0F0","accent":"#90CAF9"}}""",
            ),
        )
        assertEquals(0xFF1565C0.toInt(), t.light.candidateFirst)
        assertNotEquals(t.light.key, t.light.keyPressed)
        assertEquals("gradient", t.darkBackground.type)
        assertEquals(0xFF111111.toInt(), t.dark.onAccent)
        assertRejects("颜色") { StyleParser.theme(JSONObject("""{"id":"x","light":{"background":"blue","key":"#FFF","label":"#000","accent":"#00F"},"dark":{}}""")) }
        assertRejects("缺 dark") { StyleParser.theme(JSONObject("""{"id":"x","light":{"background":"#FFFFFF","key":"#FFFFFF","label":"#000000","accent":"#0000FF"}}""")) }
    }

    @Test fun overridesMergeIntoStyle() {
        val base = repo.resolve(app, "fresh", "auto", StyleOverrides.NONE, dark = false, level = 2)
        val o = StyleParser.overrides(JSONObject("""{"accent":"#E91E63","radius":12,"gap":1.5,"hints":false,"shadow":false,"textScale":1.1}"""))
        val s = repo.resolve(app, "fresh", "auto", o, dark = false, level = 2)
        assertEquals(0xFFE91E63.toInt(), s.palette.keyAccent)
        assertEquals(KeyShadow.NONE, s.palette.shadow)
        assertEquals("none", s.hint)
        assertTrue(s.metrics.keyRadius > base.metrics.keyRadius)
        assertTrue(s.metrics.insetH > base.metrics.insetH)
        assertTrue(s.metrics.labelScale > base.metrics.labelScale)
        // 强调色文字在背景上仍 ≥ 4.5:1。 Accent text stays readable.
        assertTrue(Contrast.ratio(s.palette.candidateFirst, s.palette.background) >= 4.5)
        // 往返。 Round trip.
        val again = StyleParser.overrides(o.toJson())
        assertEquals(o.toJson().toString(), again.toJson().toString())
        assertRejects("微调范围") { StyleParser.overrides(JSONObject("""{"radius":99}""")) }
    }

    @Test fun themeFollowsLayoutUnlessChosen() {
        for (id in repo.layoutIds) {
            val s = repo.resolve(app, id, StyleRepository.AUTO, StyleOverrides.NONE, dark = true, level = 2)
            assertEquals(repo.layout(id).theme, s.theme.id)
            assertTrue(s.dark)
        }
        val chosen = repo.themeIds.last()
        assertEquals(chosen, repo.resolve(app, "fresh", chosen, StyleOverrides.NONE, dark = false, level = 2).theme.id)
    }

    @Test fun unknownIdsFallBackToDefault() {
        val s = repo.resolve(app, "missing", "missing-too", StyleOverrides.NONE, dark = false, level = 2)
        assertEquals(StyleRepository.DEFAULT_LAYOUT, s.layout.id)
    }

    @Test fun prefsDriveResolution() {
        val p = WeavePrefs.of(app)
        val other = repo.layoutIds.last()
        p.edit().putString(WeavePrefs.STYLE_LAYOUT, other).putString(WeavePrefs.STYLE_OVERRIDES, "{\"hints\":false}").commit()
        val s = repo.resolve(app, p)
        assertEquals(other, s.layout.id)
        assertEquals("none", s.hint)
        // 损坏的微调 JSON 不影响键盘。 A corrupt tweak JSON is ignored.
        p.edit().putString(WeavePrefs.STYLE_OVERRIDES, "{oops").commit()
        assertTrue(repo.overrides(p).isEmpty)
    }

    @Test fun rowsRoundTripThroughJson() {
        val l = repo.layout(repo.layoutIds.last())
        val rows = JSONArray(l.qwerty.rows.map { r -> JSONArray(r.map { it.toString() }) })
        val again = layout(""""qwerty":{"rows":$rows}""")
        assertEquals(l.qwerty.rows.toString(), again.qwerty.rows.toString())
    }

    /** 符号面板结构由布局选择，默认底行分类。 The symbol panel structure is chosen per layout; bottom tabs by default. */
    @Test fun symbolPanelStructure() {
        install("layout:bright")
        assertEquals("bottom", layout(""""name":"t"""").symbols.categories)
        assertEquals("side", layout(""""symbols":{"categories":"side"}""").symbols.categories)
        assertRejects("符号面板结构") { layout(""""symbols":{"categories":"top"}""") }
        val side = repo.layoutIds.filter { repo.layout(it).symbols.categories == "side" }
        assertEquals(listOf("classic", "bright"), side)
    }

    @Test fun everyBuiltInToolbarHasTheCursorAndNoStickerBagByDefault() {
        for (id in repo.layoutIds) {
            val items = WeavePrefs.toolbarItems(WeavePrefs.of(app), repo.layout(id).toolbar.items)
            assertTrue("$id: $items", "cursor" in items)
            assertFalse("$id: $items", "stickers" in items)
        }
    }
}
