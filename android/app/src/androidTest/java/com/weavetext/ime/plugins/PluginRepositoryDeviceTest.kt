package com.weavetext.ime.plugins

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weavetext.ime.voice.NativePlugins
import com.weavetext.ime.voice.NativeSpeechCallback
import com.weavetext.ime.voice.VoiceBackend
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PluginRepositoryDeviceTest {
    @Test fun privateRepositoryPackageInstallsAndRunsInTheRealNativeHost() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(app.cacheDir, "github-plugin-test-${UUID.randomUUID()}").apply { mkdirs() }
        val namespace = UUID.randomUUID().toString()
        val ctx = object : ContextWrapper(app) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("$namespace-$name", mode)
        }
        val manifest = "id: org.weavetext.github.test\nname: 仓库测试\ntype: speech\nversion: 1.0\nentry: main.lua\n".toByteArray()
        val lua = """
            local p = {}
            function p.start() return true end
            function p.processAudioChunk(pcm) end
            function p.stop()
              host.asr.emitFinal(host.config.get('github_token') or '仓库插件运行成功')
              host.asr.emitEnd()
            end
            function p.cancel() end
            return p
        """.trimIndent().toByteArray()
        fun sha(bytes: ByteArray): String {
            val d = MessageDigest.getInstance("SHA-1"); d.update("blob ${bytes.size}\u0000".toByteArray())
            return d.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        val tree = "b".repeat(40); val commit = "a".repeat(40)
        val entries = JSONArray()
        val bodies = mutableMapOf<String, ByteArray>()
        for ((path, bytes) in listOf("manifest.yaml" to manifest, "main.lua" to lua)) {
            entries.put(JSONObject().put("path", path).put("type", "blob").put("mode", "100644").put("size", bytes.size).put("sha", sha(bytes)))
            bodies["/repos/tester/plugins/git/blobs/${sha(bytes)}"] = bytes
        }
        bodies["/user"] = """{"login":"tester"}""".toByteArray()
        bodies["/repos/tester/plugins"] = """{"private":true,"default_branch":"main"}""".toByteArray()
        bodies["/repos/tester/plugins/commits/main"] = """{"sha":"$commit","commit":{"tree":{"sha":"$tree"}}}""".toByteArray()
        bodies["/repos/tester/plugins/git/trees/$tree"] = JSONObject().put("tree", entries).put("truncated", false).toString().toByteArray()
        bodies["/repos/tester/plugins/releases"] = "[]".toByteArray()
        val fakeToken = "github_pat_" + "t".repeat(70)
        val credentials = GitHubCredentials(ctx)
        val http = GitHubHttp(credentials) { url -> object : HttpURLConnection(url) {
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun getResponseCode(): Int {
                assertEquals("Bearer $fakeToken", getRequestProperty("Authorization"))
                return 200
            }
            override fun getInputStream() = ByteArrayInputStream(bodies[url.path] ?: error("Unexpected API request"))
        } }
        val repo = GitHubPlugins(ctx, credentials, http)
        val native = NativePlugins(ctx)
        try {
            assertEquals("tester", repo.login(fakeToken))
            assertEquals(fakeToken, credentials.token())
            assertFalse(ctx.getSharedPreferences("github_credentials", Context.MODE_PRIVATE).all.toString().contains(fakeToken))
            val catalog = repo.catalog(GitHubRepository.parse("tester/plugins"))
            assertTrue(catalog.isPrivate)
            val packageFile = repo.download(catalog, catalog.plugins.single())
            val (engines, _) = VoiceBackend.create(ctx)
            assertEquals("org.weavetext.github.test", engines.inspect(packageFile.path).getOrThrow().id)
            val plugin = engines.install(packageFile.path).getOrThrow()
            // The real native host inspects contents even when the SAF file name is unrelated.
            for (name in listOf("voice.zip", "voice.custom", "voice")) {
                val archive = File(root, name)
                ZipOutputStream(archive.outputStream()).use { zip ->
                    for ((path, bytes) in listOf("manifest.yaml" to manifest, "main.lua" to lua)) {
                        zip.putNextEntry(ZipEntry(path));zip.write(bytes);zip.closeEntry()
                    }
                }
                assertEquals(plugin.id, engines.inspect(archive.path).getOrThrow().id)
                assertEquals(plugin.id, engines.install(archive.path).getOrThrow().id)
            }
            val fake = File(root, "fake.xipk").apply { writeText("ordinary text") }
            assertTrue(engines.inspect(fake.path).isFailure)
            engines.activeId = plugin.id
            assertEquals(plugin.id, engines.selection().single().id)
            assertNull(engines.getConfig(plugin.id, "github_token"))
            val done = CountDownLatch(1)
            var result = ""; var error: String? = null
            val session = native.session(plugin.id, object : NativeSpeechCallback {
                override fun onPartial(text: String) {}
                override fun onFinal(text: String) { result = text }
                override fun onReplace(old: String, new: String) {}
                override fun onError(message: String) { error = message }
                override fun onEnd() { done.countDown() }
                override fun onLog(level: Int, message: String) {}
            })
            session.feed(ByteArray(1280), 1280); session.stop()
            assertTrue("Native plugin did not finish", done.await(15, TimeUnit.SECONDS))
            assertNull(error)
            assertEquals("仓库插件运行成功", result)
            session.cancel()
            engines.uninstall(plugin.id).getOrThrow()
            repo.logout()
            assertNull(credentials.token())
        } finally {
            credentials.clear()
            root.deleteRecursively()
        }
    }
}
