package com.weavetext.ime.nativetest

import com.weavetext.ime.core.NativeEngine
import com.weavetext.ime.voice.NativePluginHost
import com.weavetext.ime.voice.NativeSpeechCallback
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 通过 JNI 调用真实内核与插件宿主。 Drives the real engine and plugin host through JNI. */
class JniTest {
    private val data = System.getProperty("weave.data")

    private fun engine(): NativeEngine {
        val user = Files.createTempDirectory("weave-user").toFile()
        return NativeEngine.create(data, user.absolutePath) ?: error("engine create failed")
    }

    private fun NativeEngine.type(s: String) = s.forEach { inputChar(it.code) }

    @Test
    fun pinyinSentenceAndCommit() {
        engine().use { e ->
            assertTrue(e.setSchema("pinyin"))
            e.type("woshizhongguoren")
            val snap = e.snapshot()
            assertTrue(snap.composing)
            assertEquals("我是中国人", snap.candidates.first().text)
            assertEquals("wo'shi'zhong'guo'ren", snap.preedit)
            assertTrue(e.select(0))
            val after = e.snapshot()
            assertEquals("我是中国人", after.commit)
            assertTrue(!after.composing)
            assertEquals("", e.snapshot().commit) // commit 读取后清空 / drained
        }
    }

    /** 按在 z/x 交界、落到 x 上：带邻键信息时仍得到「中国」，没有时不纠正。 A border tap still spells 中国. */
    @Test
    fun borderTapIsCorrected() {
        engine().use { e ->
            assertTrue(e.setSchema("pinyin"))
            assertTrue(e.inputKey('x'.code, 'z'.code, 0.9f))
            e.type("hongguo")
            assertEquals("中国", e.snapshot().candidates.first().text)
            assertEquals("zhong'guo", e.snapshot().preedit)
            // 退格后邻键记录同步退掉。 Backspace drops the neighbour record too.
            repeat(7) { e.backspace() }
            e.type("i")
            assertNotEquals("zi", e.snapshot().preedit)
            e.clear()
            e.type("xhongguo")
            assertNotEquals("中国", e.snapshot().candidates.first().text)
        }
    }

    /**
     * 与安卓端相同的加载方式：分块压缩文件拼进一个「APK」里，按偏移读取；结果须与原始文件完全一致。
     * The Android path: packed files concatenated into one "APK" and read by offset; results must
     * match the raw files exactly.
     */
    @Test
    fun packedSourcesInsideOneFile() {
        val keys = listOf("pinyin", "wubi86", "english", "grammar", "st_phrases", "st_characters", "emoji")
        val apk = File.createTempFile("weave-apk", ".bin").apply { deleteOnExit() }
        val spec = apk.outputStream().use { out ->
            out.write(ByteArray(12345)) // 模拟 APK 里资源之前的内容 / bytes before the assets
            var off = 12345L
            keys.joinToString(";") { k ->
                val bytes = File(data, "$k.wvz").readBytes()
                out.write(bytes)
                "$k=${apk.absolutePath}@$off+${bytes.size}".also { off += bytes.size }
            }
        }
        val user = Files.createTempDirectory("weave-user").toFile()
        val packed = NativeEngine.createFromSpec(spec, user.absolutePath, 4 * 1024) ?: error("create failed")
        packed.use { p ->
            engine().use { raw ->
                for ((schema, keysTyped) in listOf("pinyin" to "woshizhongguoren", "pinyin" to "jintiantianqibucuo", "t9" to "94664486736", "wubi86" to "wqvb", "english" to "hel", "pinyin" to "kaixin")) {
                    for (e in listOf(p, raw)) { e.clear(); assertTrue(e.setSchema(schema)); e.type(keysTyped) }
                    assertEquals(schema + keysTyped, raw.candidates(0, 30), p.candidates(0, 30))
                }
                for (e in listOf(p, raw)) { e.clear(); e.setSchema("pinyin"); e.setOption("output.traditional", true); e.type("toufa") }
                assertEquals(raw.candidates(0, 5), p.candidates(0, 5))
                assertEquals("頭髮", p.candidates(0, 1).first().text)
                p.trim()
                p.clear(); p.setOption("output.traditional", false); p.type("woshizhongguoren")
                assertEquals("我是中国人", p.snapshot().candidates.first().text)
            }
        }
    }

    @Test
    fun schemasThroughJni() {
        engine().use { e ->
            assertTrue(e.setSchema("t9"))
            e.type("94664486736")
            assertEquals("中国人", e.snapshot().candidates.first().text)
            assertTrue(e.snapshot().pinyinOptions.contains("zhong"))
            e.clear()
            assertTrue(e.setSchema("shuangpin:xiaohe"))
            e.type("vsgo")
            assertEquals("中国", e.snapshot().candidates.first().text)
            e.clear()
            assertTrue(e.setSchema("wubi86"))
            e.type("ggll")
            assertEquals("一", e.snapshot().commit) // 四码唯一自动上屏 / auto-commit
            assertTrue(!e.setSchema("nonexistent"))
        }
    }

    @Test
    fun userDictionaryRoundTrip() {
        engine().use { e ->
            e.setSchema("pinyin")
            // 分两段选词 → 造出新词。 Two-step selection creates a phrase.
            e.type("zhiwen")
            val zhi = e.snapshot().candidates.indexOfFirst { it.text == "织" }
            assertTrue("织 should be listed", zhi >= 0)
            e.select(zhi)
            val wen = e.snapshot().candidates.indexOfFirst { it.text == "文" }
            e.select(wen)
            assertEquals("织文", e.snapshot().commit)
            val words = e.userWords()
            assertTrue(words.any { it.text == "织文" && it.pinyin == "zhi wen" })
            val exported = e.exportUserWords()
            assertTrue(exported.contains("织文\tzhi wen"))
            assertTrue(e.clearUserWords())
            assertEquals(0, e.userWordCount())
            assertTrue(e.importUserWords(exported) >= 1)
            assertTrue(e.userWords("zhiwen").any { it.text == "织文" })
            assertTrue(e.deleteUserWord("zhi wen", "织文"))
        }
    }

    @Test
    fun optionsAndConversion() {
        engine().use { e ->
            e.setSchema("pinyin")
            assertTrue(e.setOption("output.traditional", true))
            e.type("toufa")
            assertEquals("頭髮", e.snapshot().candidates.first().text)
            e.clear()
            assertTrue(e.setOption("output.traditional", false))
            e.type("kaixin")
            assertTrue(e.snapshot().candidates.take(3).any { it.text == "😄" })
            assertTrue(!e.setOption("no.such.option", true))
        }
    }

    private class Recorder : NativeSpeechCallback {
        val events = mutableListOf<String>()
        val ended = CountDownLatch(1)
        override fun onPartial(text: String) { synchronized(events) { events += "partial:$text" } }
        override fun onFinal(text: String) { synchronized(events) { events += "final:$text" } }
        override fun onReplace(old: String, new: String) { synchronized(events) { events += "replace:$old>$new" } }
        override fun onError(message: String) { synchronized(events) { events += "error:$message" } }
        override fun onEnd() { synchronized(events) { events += "end" }; ended.countDown() }
        override fun onLog(level: Int, message: String) {}
    }

    private fun host(): Long {
        val root = Files.createTempDirectory("weave-plugins").toFile()
        val plugins = File(root, "plugins").apply { mkdirs() }
        val echo = File(plugins, "echo").apply { mkdirs() }
        for (n in listOf("manifest.yaml", "main.lua")) {
            javaClass.getResourceAsStream("/echo/$n")!!.use { i -> File(echo, n).outputStream().use { i.copyTo(it) } }
        }
        val h = NativePluginHost.nativeCreate(plugins.absolutePath, File(root, "config").absolutePath)
        assertNotEquals(0L, h)
        return h
    }

    @Test
    fun pluginHostEchoSession() {
        val h = host()
        val list = JSONArray(NativePluginHost.nativeScan(h))
        assertEquals(1, list.length())
        val p = list.getJSONObject(0)
        assertEquals("org.example.echo", p.getString("id"))
        assertEquals("prefix", p.getJSONArray("configSchema").getJSONObject(0).getString("key"))
        assertEquals("echo", NativePluginHost.nativeGetConfig(h, "org.example.echo", "prefix"))
        NativePluginHost.nativeSetConfig(h, "org.example.echo", "prefix", "回声")
        assertTrue(NativePluginHost.nativeIsConfigured(h, "org.example.echo"))

        val rec = Recorder()
        val s = NativePluginHost.nativeStartSpeech(h, "org.example.echo", rec)
        assertNotEquals(0L, s)
        val chunk = ByteArray(1280)
        repeat(60) { NativePluginHost.nativeFeed(s, chunk, chunk.size) } // 2.4 s
        NativePluginHost.nativeStop(s)
        assertTrue(rec.ended.await(10, TimeUnit.SECONDS))
        NativePluginHost.nativeRelease(s)
        synchronized(rec.events) {
            assertTrue(rec.events.toString(), rec.events.contains("partial:回声 2 s"))
            assertTrue(rec.events.toString(), rec.events.contains("final:回声 2.4 s"))
            assertEquals("end", rec.events.last())
        }
        // 不存在的插件：返回 0 且同步回调 error + end。 Unknown plugin: 0, error + end.
        val bad = Recorder()
        assertEquals(0L, NativePluginHost.nativeStartSpeech(h, "no.such.plugin", bad))
        assertTrue(bad.ended.await(1, TimeUnit.SECONDS))
    }

    /** 可选：用真实插件目录 + 16k wav 跑一次（通过环境变量提供，仓库内不含）。 */
    @Test
    fun externalPluginOptional() {
        val dir = System.getProperty("weave.pluginDir")
        val wav = System.getProperty("weave.wav")
        assumeTrue(dir != null && wav != null)
        val root = Files.createTempDirectory("weave-ext").toFile()
        val plugins = File(root, "plugins").apply { mkdirs() }
        File(dir!!).copyRecursively(File(plugins, File(dir).name))
        val h = NativePluginHost.nativeCreate(plugins.absolutePath, File(root, "config").absolutePath)
        val id = JSONArray(NativePluginHost.nativeScan(h)).getJSONObject(0).getString("id")
        val rec = Recorder()
        val s = NativePluginHost.nativeStartSpeech(h, id, rec)
        val pcm = File(wav!!).readBytes().drop(44).toByteArray()
        pcm.toList().chunked(1280).forEach { c -> NativePluginHost.nativeFeed(s, c.toByteArray(), c.size); Thread.sleep(40) }
        NativePluginHost.nativeStop(s)
        assertTrue(rec.ended.await(20, TimeUnit.SECONDS))
        println("external plugin events: ${rec.events}")
        assertTrue(rec.events.any { it.startsWith("final:") })
        NativePluginHost.nativeRelease(s)
    }
}
