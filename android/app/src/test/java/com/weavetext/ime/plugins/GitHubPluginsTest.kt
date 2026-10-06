package com.weavetext.ime.plugins

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GitHubPluginsTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val token = "github_pat_" + "a".repeat(70)
    private lateinit var credentials: GitHubCredentials
    private lateinit var service: GitHubPlugins
    private val requests = mutableListOf<Response>()
    private val fixtures = mutableMapOf<String, Triple<Int, ByteArray, Map<String, String>>>()
    private val commit = "a".repeat(40)
    private val tree = "b".repeat(40)

    private class Response(url: URL, private val fixture: Triple<Int, ByteArray, Map<String, String>>) : HttpURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = fixture.first
        override fun getInputStream() = ByteArrayInputStream(fixture.second)
        override fun getHeaderField(name: String) = fixture.third[name]
    }
    private fun respond(url: String, body: String, status: Int = 200, headers: Map<String, String> = emptyMap()) {
        fixtures[url] = Triple(status, body.toByteArray(), headers)
    }
    private fun sha(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${bytes.size}\u0000".toByteArray())
        return digest.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    @Before fun setup() {
        app.getSharedPreferences("github_credentials", 0).edit().clear().commit()
        app.getSharedPreferences("plugin_repositories", 0).edit().clear().commit()
        File(app.cacheDir, "repository-import").deleteRecursively()
        credentials = GitHubCredentials(app) { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
        service = GitHubPlugins(app, credentials, GitHubHttp(credentials) { url ->
            Response(url, fixtures[url.toString()] ?: error("Unexpected request ${url.host}${url.path}")).also { requests += it }
        }, inspectArchive = { file -> ZipFile(file).use { it.getEntry("manifest.yaml") != null && it.getEntry("main.lua") != null } })
    }

    @Test fun tokenIsEncryptedAndLogoutClearsAuthorizationAcrossInstances() {
        respond("https://api.github.com/user", """{"login":"tester"}""")
        assertEquals("tester", service.login(token))
        assertEquals(token, credentials.token())
        val prefs = app.getSharedPreferences("github_credentials", 0)
        assertFalse(prefs.getString("token", "")!!.contains(token))
        assertFalse(prefs.all.toString().contains(token))
        assertEquals("Bearer $token", requests.single().getRequestProperty("Authorization"))
        val oldEpoch = credentials.epoch
        GitHubCredentials(app) { SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }.clear()
        assertTrue(credentials.epoch > oldEpoch)
        assertNull(credentials.token())
        assertNull(credentials.login)
    }

    @Test fun releaseRedirectNeverReceivesTheApiCredential() {
        credentials.save("tester", token)
        respond("https://api.github.com/asset", "", 302, mapOf("Location" to "https://release-assets.githubusercontent.com/file"))
        respond("https://release-assets.githubusercontent.com/file", "package")
        val http = GitHubHttp(credentials) { url -> Response(url, fixtures.getValue(url.toString())).also { requests += it } }
        assertEquals("package", http.read("/asset", "application/octet-stream") { String(it.readBytes()) })
        assertEquals("Bearer $token", requests[0].getRequestProperty("Authorization"))
        assertNull(requests[1].getRequestProperty("Authorization"))
        assertFalse(GitHubHttp.allowed(URL("https://api.github.com.evil.example/file")))
        assertFalse(GitHubHttp.allowed(URL("http://api.github.com/file")))
        assertFalse(GitHubHttp.allowed(URL("https://user:secret@api.github.com/file")))
    }

    @Test fun privateSourceIsFetchedAtImmutableBlobVersionsAndPackaged() {
        credentials.save("tester", token)
        val manifest = "id: org.example.test\nname: Test\ntype: speech\nentry: main.lua\n".toByteArray()
        val lua = "return {}".toByteArray()
        sourceFixtures(listOf("manifest.yaml" to manifest, "main.lua" to lua))
        val repo = GitHubRepository.parse("tester/plugins")
        val catalog = service.catalog(repo)
        assertTrue(catalog.isPrivate)
        assertEquals(commit, catalog.commit)
        assertEquals(GitHubRepositorySummary(1, true), service.summary(repo))
        val file = service.download(catalog, catalog.plugins.single())
        ZipFile(file).use { z ->
            assertArrayEquals(manifest, z.getInputStream(z.getEntry("manifest.yaml")).readBytes())
            assertArrayEquals(lua, z.getInputStream(z.getEntry("main.lua")).readBytes())
        }
        assertTrue(requests.all { it.getRequestProperty("Authorization") == "Bearer $token" })
        assertTrue(requests.any { it.url.path.endsWith("/git/blobs/${sha(manifest)}") })
        service.save(repo)
        assertEquals(listOf(repo), service.saved())
        assertFalse(app.getSharedPreferences("plugin_repositories", 0).all.toString().contains(token))
        file.delete()
        service.remove(repo)
        assertNull(service.summary(repo))
        assertTrue(service.saved().isEmpty())
    }

    @Test fun corruptBlobNeverLeavesAnImportablePackage() {
        sourceFixtures(listOf("manifest.yaml" to "id: test\ntype: speech".toByteArray()))
        val catalog = service.catalog(GitHubRepository.parse("tester/plugins"))
        val source = catalog.plugins.single() as GitHubPlugin.Source
        val blob = source.files.single()
        fixtures["https://api.github.com/repos/tester/plugins/git/blobs/${blob.sha}"] = Triple(200, ByteArray(blob.size.toInt()), emptyMap())
        assertTrue(runCatching { service.download(catalog, source) }.isFailure)
        assertTrue(File(app.cacheDir, "repository-import").listFiles().orEmpty().isEmpty())
        assertTrue(requests.all { it.getRequestProperty("Authorization") == null })
    }

    @Test fun unsafeRepositoryInputsAndPathsAreRejected() {
        for (input in listOf("https://github.com.evil.example/tester/plugins", "https://secret@github.com/tester/plugins", "https://github.com/tester/../plugins")) {
            assertTrue(input, runCatching { GitHubRepository.parse(input) }.isFailure)
        }
        assertTrue(runCatching { GitHubRepository.parse("tester/plugins", path = "../secret") }.isFailure)
        assertFalse(GitHubRepository.safePath("plugins/../../secret"))
        assertFalse(GitHubRepository.safePath("/manifest.yaml"))
        assertEquals("feature/custom", GitHubRepository.parse("https://github.com/tester/plugins", "feature/custom").ref)
        assertEquals("asr", GitHubRepository.parse("https://github.com/tester/plugins/tree/feature/custom/asr", "feature/custom").path)
    }

    @Test fun aRepositoryWithNoPluginsHasAZeroCountAndCanStillBeSaved() {
        sourceFixtures(emptyList())
        val repo = GitHubRepository.parse("tester/plugins")
        val catalog = service.catalog(repo)
        assertTrue(catalog.plugins.isEmpty())
        service.save(repo)
        assertEquals(0, service.summary(repo)!!.pluginCount)
        assertEquals(listOf(repo), service.saved())
    }

    @Test fun aCompletePrivateRepositoryAddressStillUsesOnlyTheAuthenticatedApi() {
        credentials.save("tester", token)
        sourceFixtures(listOf("manifest.yaml" to "id: org.example.test\ntype: speech\nentry: main.lua".toByteArray(), "main.lua" to "return {}".toByteArray()))
        val repo = GitHubRepository.parse("https://www.github.com/tester/plugins.git?tab=readme#overview")
        val catalog = service.catalog(repo)
        assertTrue(catalog.isPrivate)
        assertEquals(1, catalog.plugins.size)
        service.save(repo)
        assertEquals(listOf(GitHubRepository("tester", "plugins")), service.saved())
        assertTrue(requests.all { it.url.host == "api.github.com" && it.getRequestProperty("Authorization") == "Bearer $token" })
    }

    @Test fun aCompletePackageAddressReadsTheNamedBlobAndPreservesItsPlusCharacter() {
        credentials.save("tester", token)
        val bytes = archive()
        sourceFixtures(listOf("voice+one.custom" to bytes))
        val entry = JSONObject().put("path", "voice+one.custom").put("sha", sha(bytes)).put("type", "blob").put("mode", "100644").put("size", bytes.size)
        respond("https://api.github.com/repos/tester/plugins/git/trees/$tree", JSONObject().put("tree", JSONArray().put(entry)).toString())
        val catalog = service.catalog(GitHubRepository.parse("https://github.com/tester/plugins/blob/main/voice+one.custom?raw=true"))
        val plugin = catalog.plugins.single() as GitHubPlugin.Package
        assertEquals("voice+one.custom", plugin.blob.path)
        val file = service.download(catalog, plugin)
        assertArrayEquals(bytes, file.readBytes())
        assertTrue(requests.all { it.getRequestProperty("Authorization") == "Bearer $token" })
        file.delete()
    }

    @Test fun pluginCountsFollowArchiveContentsInsteadOfExtensions() {
        sourceFixtures(listOf("voice.zip" to archive(), "voice.custom" to archive(), "voice" to archive(),
            "fake.xipk" to "ordinary text".toByteArray(), "unrelated.zip" to archive(valid = false)))
        val catalog = service.catalog(GitHubRepository.parse("tester/plugins"))
        assertEquals(setOf("voice.zip", "voice.custom", "voice"), catalog.plugins.map { it.label }.toSet())
        assertEquals(3, service.summary(catalog.repository)!!.pluginCount)
        assertTrue(File(app.cacheDir, "repository-import").listFiles().orEmpty().isEmpty())
    }

    @Test fun releaseAssetsAreInspectedEvenWithAnUnfamiliarExtension() {
        sourceFixtures(emptyList())
        val bytes = archive()
        respond("https://api.github.com/repos/tester/plugins/releases?per_page=20",
            """[{"tag_name":"v1","assets":[{"id":42,"name":"voice.bundle","size":${bytes.size}}]}]""")
        fixtures["https://api.github.com/repos/tester/plugins/releases/assets/42"] = Triple(200, bytes, emptyMap())
        val catalog = service.catalog(GitHubRepository.parse("tester/plugins"))
        assertEquals("voice.bundle · v1", catalog.plugins.single().label)
        val file = service.download(catalog, catalog.plugins.single())
        assertArrayEquals(bytes, file.readBytes()); file.delete()
    }

    private fun archive(valid: Boolean = true): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            for ((name, content) in if (valid) listOf("manifest.yaml" to "id: org.example.test\ntype: speech\nentry: main.lua\n", "main.lua" to "return {}")
                else listOf("README.md" to "not a plugin")) {
                zip.putNextEntry(ZipEntry(name));zip.write(content.toByteArray());zip.closeEntry()
            }
        }
    }.toByteArray()

    @Test fun aManifestAddressDownloadsItsSiblingEntrypointFromThePrivateDirectory() {
        credentials.save("tester", token)
        val manifest = "id: org.example.test\ntype: speech\nentry: main.lua".toByteArray()
        val lua = "return {}".toByteArray()
        sourceFixtures(listOf("voice/asr/manifest.yaml" to manifest, "voice/asr/main.lua" to lua))
        val voiceTree = "c".repeat(40)
        val asrTree = "d".repeat(40)
        fun directory(name: String, sha: String) = JSONObject().put("tree", JSONArray().put(
            JSONObject().put("path", name).put("type", "tree").put("sha", sha))).toString()
        respond("https://api.github.com/repos/tester/plugins/git/trees/$tree", directory("voice", voiceTree))
        respond("https://api.github.com/repos/tester/plugins/git/trees/$voiceTree", directory("asr", asrTree))
        val entries = JSONArray()
        for ((name, bytes) in listOf("manifest.yaml" to manifest, "main.lua" to lua)) {
            entries.put(JSONObject().put("path", name).put("type", "blob").put("sha", sha(bytes)).put("mode", "100644").put("size", bytes.size))
        }
        respond("https://api.github.com/repos/tester/plugins/git/trees/$asrTree?recursive=1", JSONObject().put("truncated", false).put("tree", entries).toString())
        val catalog = service.catalog(GitHubRepository.parse("https://github.com/tester/plugins/blob/main/voice/asr/manifest.yaml#L1"))
        val plugin = catalog.plugins.single() as GitHubPlugin.Source
        assertEquals("voice/asr", plugin.prefix)
        val file = service.download(catalog, plugin)
        ZipFile(file).use { z ->
            assertArrayEquals(manifest, z.getInputStream(z.getEntry("manifest.yaml")).readBytes())
            assertArrayEquals(lua, z.getInputStream(z.getEntry("main.lua")).readBytes())
        }
        assertTrue(requests.all { it.getRequestProperty("Authorization") == "Bearer $token" })
        file.delete()
    }

    private fun sourceFixtures(files: List<Pair<String, ByteArray>>) {
        respond("https://api.github.com/repos/tester/plugins", """{"private":true,"default_branch":"main"}""")
        respond("https://api.github.com/repos/tester/plugins/commits/main", """{"sha":"$commit","commit":{"tree":{"sha":"$tree"}}}""")
        respond("https://api.github.com/repos/tester/plugins/releases?per_page=20", "[]")
        val entries = JSONArray()
        files.forEach { (path, bytes) ->
            entries.put(JSONObject().put("path", path).put("sha", sha(bytes)).put("type", "blob").put("mode", "100644").put("size", bytes.size))
            fixtures["https://api.github.com/repos/tester/plugins/git/blobs/${sha(bytes)}"] = Triple(200, bytes, emptyMap())
        }
        respond("https://api.github.com/repos/tester/plugins/git/trees/$tree?recursive=1", JSONObject().put("truncated", false).put("tree", entries).toString())
    }
}
