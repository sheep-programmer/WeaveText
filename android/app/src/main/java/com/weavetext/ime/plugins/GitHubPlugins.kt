package com.weavetext.ime.plugins

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.weavetext.ime.voice.NativePluginHost

/** Read-only GitHub repository import. Downloads become plugin ZIP archives for the existing preview. */
class GitHubPlugins(ctx: Context, val credentials: GitHubCredentials = GitHubCredentials(ctx),
    private val http: GitHubHttp = GitHubHttp(credentials),
    private val inspectArchive: (File) -> Boolean = { file ->
        val result = JSONObject(NativePluginHost.nativeInspect(file.absolutePath) ?: "{}")
        result.has("plugin") && !result.has("error")
    }) {
    private val prefs = ctx.getSharedPreferences("plugin_repositories", Context.MODE_PRIVATE)
    private val cache = File(ctx.cacheDir, "repository-import").apply { mkdirs() }

    fun login(input: String): String {
        val token = input.trim()
        require(token.length in 20..255 && token.all { it.isLetterOrDigit() && it.code < 128 || it == '_' }) { "请粘贴有效的 GitHub 访问令牌" }
        val user = http.read("/user", tokenOverride = token) { JSONObject(text(it, JSON_LIMIT)) }.getString("login")
        require(user.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}"))) { "GitHub 账号信息无效" }
        credentials.save(user, token)
        return user
    }
    fun logout() = credentials.clear()

    fun saved(): List<GitHubRepository> = runCatching {
        val a = JSONArray(prefs.getString("repositories", "[]"))
        (0 until a.length()).mapNotNull { i -> runCatching {
            val o = a.getJSONObject(i)
            GitHubRepository.parse(o.getString("repository"), o.optString("ref"), o.optString("path"))
        }.getOrNull() }
    }.getOrDefault(emptyList())

    fun save(repo: GitHubRepository) {
        val list = (saved().filter { it.key != repo.key } + repo).takeLast(50)
        persist(list)
    }
    fun remove(repo: GitHubRepository) {
        persist(saved().filter { it.key != repo.key })
        prefs.edit().remove("summary:${repo.key}").apply()
    }
    fun summary(repo: GitHubRepository): GitHubRepositorySummary? = runCatching {
        val value = prefs.getString("summary:${repo.key}", null) ?: return null
        val o = JSONObject(value)
        GitHubRepositorySummary(o.getInt("count").coerceAtLeast(0), o.getBoolean("private"))
    }.getOrNull()
    private fun persist(repos: List<GitHubRepository>) {
        val a = JSONArray()
        repos.forEach { a.put(JSONObject().put("repository", it.fullName).put("ref", it.ref).put("path", it.path)) }
        check(prefs.edit().putString("repositories", a.toString()).commit()) { "无法保存仓库" }
    }

    private fun json(path: String) = http.read(path) { JSONObject(text(it, JSON_LIMIT)) }
    private fun array(path: String) = http.read(path) { JSONArray(text(it, JSON_LIMIT)) }

    fun catalog(repo: GitHubRepository): GitHubCatalog {
        val epoch = credentials.epoch
        val metadata = json(repo.apiPath)
        val branch = repo.ref.ifEmpty { metadata.getString("default_branch") }
        val commit = json("${repo.apiPath}/commits/${GitHubRepository.component(branch)}")
        val commitSha = commit.getString("sha")
        val treeSha = commit.getJSONObject("commit").getJSONObject("tree").getString("sha")
        require(GitHubRepository.sha(commitSha) && GitHubRepository.sha(treeSha)) { "GitHub 返回的版本信息无效" }
        val files = files(repo, treeSha)
        // Read the ZIP signature, then validate manifest/entry contents. Names are never the gate.
        val packageCandidates = files.filter { it.size in 4..ENTRY_LIMIT && it.mode in listOf("100644", "100755") }
        require(packageCandidates.size <= 512) { "仓库文件较多，请指定插件所在目录后读取" }
        val packages = packageCandidates.filter { candidate(repo, it) }.map { GitHubPlugin.Package(it) }
        val sources = files.filter { it.path == "manifest.yaml" || it.path.endsWith("/manifest.yaml") }.map { manifest ->
            val prefix = manifest.path.substringBeforeLast('/', "")
            val children = files.filter { if (prefix.isEmpty()) true else it.path.startsWith("$prefix/") }
            GitHubPlugin.Source(prefix, children)
        }
        val releases = array("${repo.apiPath}/releases?per_page=20")
        val assets = buildList {
            for (i in 0 until releases.length()) {
                val release = releases.getJSONObject(i)
                if (release.optBoolean("draft")) continue
                val a = release.optJSONArray("assets") ?: continue
                for (j in 0 until a.length()) {
                    val asset = a.getJSONObject(j)
                    val name = asset.optString("name")
                    val size = asset.optLong("size", -1)
                    if (size in 4..ENTRY_LIMIT) {
                        val plugin = GitHubPlugin.Release(asset.getLong("id"), name, release.optString("tag_name"), size,
                            asset.optString("digest").takeIf { it.startsWith("sha256:") })
                        if (candidate(repo, plugin)) add(plugin)
                    }
                }
            }
        }
        val all = assets + packages + sources
        val result = GitHubCatalog(repo, metadata.optBoolean("private"), commitSha, all)
        check(epoch == credentials.epoch) { "GitHub 账号已变更，请重新读取仓库" }
        prefs.edit().putString("summary:${repo.key}", JSONObject().put("count", all.size).put("private", result.isPrivate).toString()).apply()
        return result
    }

    private fun files(repo: GitHubRepository, rootSha: String): List<GitHubBlob> {
        var sha = rootSha
        if (repo.path.isNotEmpty()) {
            val segments = repo.path.split('/')
            for ((i, name) in segments.withIndex()) {
                val tree = json("${repo.apiPath}/git/trees/$sha").getJSONArray("tree")
                val entry = (0 until tree.length()).map { tree.getJSONObject(it) }.firstOrNull { it.getString("path") == name }
                    ?: error("找不到指定插件目录或文件")
                sha = entry.getString("sha")
                require(GitHubRepository.sha(sha)) { "GitHub 文件信息无效" }
                if (entry.getString("type") == "blob") {
                    require(i == segments.lastIndex) { "插件目录无效" }
                    return listOf(blob(entry, repo.path))
                }
                require(entry.getString("type") == "tree") { "不支持子模块，请选择实际插件目录" }
            }
        }
        val tree = json("${repo.apiPath}/git/trees/$sha?recursive=1")
        require(!tree.optBoolean("truncated")) { "仓库目录过大，请指定较小的插件目录" }
        val a = tree.getJSONArray("tree")
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.getString("type") == "blob" }
            .map { e -> blob(e, listOf(repo.path, e.getString("path")).filter(String::isNotEmpty).joinToString("/")) }
    }

    private fun blob(o: JSONObject, path: String): GitHubBlob {
        require(GitHubRepository.safePath(path) && GitHubRepository.sha(o.getString("sha"))) { "GitHub 文件路径无效" }
        return GitHubBlob(path, o.getString("sha"), o.getLong("size"), o.getString("mode"))
    }

    fun download(catalog: GitHubCatalog, plugin: GitHubPlugin, progress: (String) -> Unit = {}): File {
        val file = File.createTempFile("github-plugin-", ".archive", cache)
        try {
            when (plugin) {
                is GitHubPlugin.Release -> {
                    require(plugin.id > 0 && plugin.size in 1..ENTRY_LIMIT)
                    val digest = MessageDigest.getInstance("SHA-256")
                    http.read("${catalog.repository.apiPath}/releases/assets/${plugin.id}", "application/octet-stream") { input ->
                        file.outputStream().use { output -> copy(input, output, plugin.size, digest) }
                    }
                    plugin.digest?.let { require(it == "sha256:${hex(digest.digest())}") { "插件下载校验失败，请重新获取仓库" } }
                }
                is GitHubPlugin.Package -> file.outputStream().use { fetch(catalog.repository, plugin.blob, it) }
                is GitHubPlugin.Source -> {
                    require(plugin.files.size in 1..4096 && plugin.files.sumOf { it.size } <= TOTAL_LIMIT) { "插件文件过多或体积过大" }
                    ZipOutputStream(file.outputStream()).use { zip ->
                        plugin.files.forEachIndexed { i, blob ->
                            val name = if (plugin.prefix.isEmpty()) blob.path else blob.path.removePrefix("${plugin.prefix}/")
                            require(GitHubRepository.safePath(name)) { "插件文件路径无效" }
                            progress("正在下载插件文件 ${i + 1}/${plugin.files.size}")
                            zip.putNextEntry(ZipEntry(name))
                            fetch(catalog.repository, blob, zip)
                            zip.closeEntry()
                        }
                    }
                }
            }
            return file
        } catch (e: Throwable) { file.delete(); throw e }
    }

    private fun fetch(repo: GitHubRepository, blob: GitHubBlob, output: OutputStream) {
        require(blob.mode in listOf("100644", "100755") && blob.size in 0..ENTRY_LIMIT) { "不支持符号链接，或插件文件体积超过限制" }
        val digest = MessageDigest.getInstance(if (blob.sha.length == 64) "SHA-256" else "SHA-1")
        digest.update("blob ${blob.size}\u0000".toByteArray())
        http.read("${repo.apiPath}/git/blobs/${blob.sha}", "application/vnd.github.raw+json") { input -> copy(input, output, blob.size, digest) }
        require(hex(digest.digest()).equals(blob.sha, true)) { "插件文件校验失败，请重新获取仓库" }
    }

    private fun candidate(repo: GitHubRepository, blob: GitHubBlob): Boolean = probe(
        "${repo.apiPath}/git/blobs/${blob.sha}", "application/vnd.github.raw+json", blob.size,
        blob.sha, gitBlob = true,
    )
    private fun candidate(repo: GitHubRepository, release: GitHubPlugin.Release): Boolean = probe(
        "${repo.apiPath}/releases/assets/${release.id}", "application/octet-stream", release.size,
        release.digest?.removePrefix("sha256:"), gitBlob = false,
    )
    private fun probe(path: String, accept: String, size: Long, expectedHash: String?, gitBlob: Boolean): Boolean {
        return http.read(path, accept) { input ->
            val prefix = ByteArray(4)
            var got = 0
            while (got < 4) { val n = input.read(prefix, got, 4 - got); if (n < 0) break; got += n }
            if (got != 4 || !prefix.contentEquals(byteArrayOf(80, 75, 3, 4))) return@read false
            val file = File.createTempFile("plugin-probe-", ".archive", cache)
            try {
                val digest = MessageDigest.getInstance(if (gitBlob && expectedHash?.length == 40) "SHA-1" else "SHA-256")
                if (gitBlob) digest.update("blob $size\u0000".toByteArray())
                file.outputStream().use { output ->
                    output.write(prefix); digest.update(prefix)
                    copy(input, output, size - 4, digest)
                }
                if (expectedHash != null) require(hex(digest.digest()).equals(expectedHash, true)) { "插件文件校验失败，请刷新仓库" }
                runCatching { inspectArchive(file) }.getOrDefault(false)
            } finally { file.delete() }
        }
    }

    companion object {
        const val ENTRY_LIMIT = 64L * 1024 * 1024
        const val TOTAL_LIMIT = 256L * 1024 * 1024
        private const val JSON_LIMIT = 8L * 1024 * 1024
        private fun text(input: InputStream, limit: Long): String {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(32 * 1024)
            var size = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                size += n
                require(size <= limit) { "GitHub 返回内容过大" }
                out.write(buffer, 0, n)
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }
        private fun copy(input: InputStream, output: OutputStream, expected: Long, digest: MessageDigest): Long {
            var count = 0L
            val bytes = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(bytes)
                if (n < 0) break
                count += n
                require(count <= expected) { "插件文件大小与仓库信息不一致" }
                digest.update(bytes, 0, n); output.write(bytes, 0, n)
            }
            require(count == expected) { "插件下载未完成，请重试" }
            return count
        }
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
