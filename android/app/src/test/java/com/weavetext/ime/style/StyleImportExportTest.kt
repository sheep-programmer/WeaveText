package com.weavetext.ime.style

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.weavetext.ime.settings.WeavePrefs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** .wvskin 导入导出往返、校验与限大小。 .wvskin round trip, validation and size limits. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StyleImportExportTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val prefs get() = WeavePrefs.of(app)
    private lateinit var repo: StyleRepository

    @Before fun setUp() {
        prefs.edit().clear().commit()
        repo = StyleRepository.get(app)
        repo.root.deleteRecursively()
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun png(w: Int = 8, h: Int = 8): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF336699.toInt()) }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun assertRejected(bytes: ByteArray) {
        try { repo.stage(ByteArrayInputStream(bytes)); fail("应拒绝") } catch (e: StyleException) { assertTrue(e.message!!.isNotEmpty()) }
    }

    @Test fun exportImportRoundTrip() {
        val layoutId = repo.layoutIds.last()
        val themeId = repo.themeIds.last()
        currentBackground()
        prefs.edit()
            .putString(WeavePrefs.STYLE_LAYOUT, layoutId).putString(WeavePrefs.STYLE_THEME, themeId)
            .putString(WeavePrefs.STYLE_OVERRIDES, """{"accent":"#E91E63","radius":10,"background":{"type":"image","colors":["#223344"],"image":"bg.png","dim":0.3}}""")
            .commit()
        val before = repo.resolve(app, prefs)
        val out = ByteArrayOutputStream()
        repo.exportCurrent(prefs, "往返", out)

        prefs.edit().clear().commit()
        repo.root.deleteRecursively()
        repo.invalidate()
        val staged = repo.stage(ByteArrayInputStream(out.toByteArray()))
        assertEquals("往返", staged.name)
        assertTrue(staged.hasImage)
        val pack = repo.commit(staged)
        assertFalse(staged.dir.exists())
        repo.apply(prefs, pack)
        val after = repo.resolve(app, prefs)
        assertEquals(before.layout.id, after.layout.id)
        assertEquals(before.theme.id, after.theme.id)
        assertEquals(before.palette.keyAccent, after.palette.keyAccent)
        assertEquals(before.metrics.keyRadius, after.metrics.keyRadius, 0.001f)
        assertNotNull(after.palette.backdrop?.image)
    }

    private fun currentBackground() {
        repo.currentDir.mkdirs()
        File(repo.currentDir, "bg.png").writeBytes(png())
    }

    @Test fun saveApplyDelete() {
        prefs.edit().putString(WeavePrefs.STYLE_LAYOUT, repo.layoutIds.last()).commit()
        val pack = repo.saveCurrent(prefs, "我的")
        assertEquals(1, repo.packs().size)
        prefs.edit().clear().commit()
        repo.apply(prefs, pack)
        assertEquals(repo.layoutIds.last(), WeavePrefs.styleLayout(prefs))
        repo.delete(prefs, pack)
        assertTrue(repo.packs().isEmpty())
    }

    @Test fun packWithOwnLayoutAndTheme() {
        val json = JSONObject(
            """{"version":1,"name":"自带","layout":{"extends":"fresh","geometry":{"radius":14},"qwerty":{"showHints":false}},
            "theme":{"extends":"fresh","light":{"accent":"#00897B"}}}""",
        )
        val pack = repo.commit(repo.stage(ByteArrayInputStream(zip("style.json" to json.toString().toByteArray()))))
        repo.apply(prefs, pack)
        assertEquals(StyleRepository.PACK + pack.id, WeavePrefs.styleLayout(prefs))
        val s = repo.resolve(app, prefs)
        assertEquals("none", s.hint)
        assertEquals(0xFF00897B.toInt(), repo.theme(StyleRepository.PACK + pack.id).light.keyAccent)
        // 删除正在使用的包后退回内置项。 Deleting an in-use pack falls back to its base.
        repo.delete(prefs, pack)
        assertEquals("fresh", WeavePrefs.styleLayout(prefs))
        assertEquals("fresh", WeavePrefs.styleTheme(prefs))
    }

    @Test fun rejectsBadPacks() {
        assertRejected(zip("other.txt" to "x".toByteArray()))
        assertRejected(zip("style.json" to "{not json".toByteArray()))
        assertRejected(zip("style.json" to """{"name":""}""".toByteArray()))
        assertRejected(zip("style.json" to """{"name":"x","version":42}""".toByteArray()))
        assertRejected(zip("style.json" to """{"name":"x","layout":{"extends":"nope"}}""".toByteArray()))
        assertRejected(zip("style.json" to """{"name":"x","layout":{"extends":"fresh","qwerty":{"hint":"sideways"}}}""".toByteArray()))
        // 引用缺失的图片。 Missing image.
        assertRejected(zip("style.json" to """{"name":"x","overrides":{"background":{"type":"image","colors":["#000000"],"image":"bg.png"}}}""".toByteArray()))
        // 不能解码的图片。 Undecodable image.
        assertRejected(
            zip(
                "style.json" to """{"name":"x","overrides":{"background":{"type":"image","colors":["#000000"],"image":"bg.png"}}}""".toByteArray(),
                "bg.png" to ByteArray(64) { 7 },
            ),
        )
        // 超大 style.json。 Oversized style.json.
        assertRejected(zip("style.json" to ("{\"name\":\"x\",\"pad\":\"" + "a".repeat(300 * 1024) + "\"}").toByteArray()))
        assertTrue(File(app.cacheDir, "style-import").listFiles().orEmpty().isEmpty())
    }

    /** 路径穿越与子目录条目被忽略，不会写到临时目录之外。 Path traversal entries are ignored. */
    @Test fun ignoresUnsafeEntries() {
        val staged = repo.stage(
            ByteArrayInputStream(
                zip(
                    "style.json" to """{"name":"安全","unknownField":1}""".toByteArray(),
                    "../evil.png" to png(),
                    "sub/dir.png" to png(),
                ),
            ),
        )
        assertEquals(listOf("style.json"), staged.dir.list()!!.toList())
        assertFalse(File(staged.dir.parentFile, "evil.png").exists())
        repo.discard(staged)
    }
}
