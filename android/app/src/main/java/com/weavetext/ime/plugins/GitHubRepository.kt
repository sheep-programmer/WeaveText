package com.weavetext.ime.plugins

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

data class GitHubRepository(val owner: String, val name: String, val ref: String = "", val path: String = "") {
    val fullName get() = "$owner/$name"
    val key get() = "$fullName@$ref:$path"
    val apiPath get() = "/repos/${component(owner)}/${component(name)}"

    companion object {
        fun parse(input: String, ref: String = "", path: String = ""): GitHubRepository {
            val value = input.trim()
            val address = if (value.matches(Regex("(?i)^(?:www\\.)?github\\.com(?::443)?/.*"))) "https://$value" else value
            val parts = if (address.contains("://")) {
                val uri = runCatching { URI(address) }.getOrNull() ?: error("仓库地址无效")
                require(uri.scheme.equals("https", true) && (uri.host.equals("github.com", true) || uri.host.equals("www.github.com", true)) &&
                    uri.userInfo == null && uri.port in listOf(-1, 443)) { "请输入 GitHub 的 HTTPS 仓库地址" }
                // URL paths use literal '+', unlike form data decoded by URLDecoder.
                uri.rawPath.orEmpty().trim('/').split('/').map { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
            } else value.trim('/').split('/')
            require(parts.size >= 2 && parts[0].matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}"))) { "请输入 owner/repository 或 GitHub 仓库链接" }
            val name = parts[1].let { if (it.endsWith(".git", true)) it.dropLast(4) else it }
            require(name.matches(Regex("[A-Za-z0-9_.-]{1,100}")) && name !in listOf(".", "..")) { "仓库名称无效" }
            val linkedRef = if (parts.size > 3 && parts[2] in listOf("tree", "blob")) parts[3] else ""
            val linkedPath = if (linkedRef.isNotEmpty()) {
                val tail = parts.drop(3).joinToString("/")
                val chosen = ref.trim()
                if (chosen.isNotEmpty() && (tail == chosen || tail.startsWith("$chosen/"))) tail.removePrefix(chosen).trimStart('/')
                else parts.drop(4).joinToString("/")
            } else ""
            require(parts.size == 2 || linkedRef.isNotEmpty()) { "请输入仓库链接，或指定分支及插件目录" }
            val branch = ref.trim().ifEmpty { linkedRef }
            val linkedDirectory = if (parts.getOrNull(2) == "blob" && linkedPath.substringAfterLast('/') == "manifest.yaml") {
                linkedPath.substringBeforeLast('/', "")
            } else linkedPath
            val dir = path.trim().trim('/').ifEmpty { linkedDirectory }
            require(branch.length <= 200 && branch.none(Char::isISOControl)) { "分支或标签无效" }
            require(dir.isEmpty() || safePath(dir)) { "插件目录无效" }
            return GitHubRepository(parts[0], name, branch, dir)
        }

        fun component(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        fun safePath(path: String) = path.isNotEmpty() && !path.startsWith('/') && '\\' !in path && path.none(Char::isISOControl) &&
            path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
        fun sha(value: String) = value.length in listOf(40, 64) && value.all { it in "0123456789abcdefABCDEF" }
    }
}

data class GitHubBlob(val path: String, val sha: String, val size: Long, val mode: String)
sealed interface GitHubPlugin {
    val label: String
    data class Source(val prefix: String, val files: List<GitHubBlob>) : GitHubPlugin {
        override val label get() = if (prefix.isEmpty()) "仓库根目录插件" else prefix
    }
    data class Package(val blob: GitHubBlob) : GitHubPlugin { override val label get() = blob.path }
    data class Release(val id: Long, val name: String, val tag: String, val size: Long, val digest: String?) : GitHubPlugin {
        override val label get() = "$name · $tag"
    }
}

data class GitHubCatalog(val repository: GitHubRepository, val isPrivate: Boolean, val commit: String, val plugins: List<GitHubPlugin>)

/** Last known count for the collapsed repository list; no credentials or plugin source are cached here. */
data class GitHubRepositorySummary(val pluginCount: Int, val isPrivate: Boolean)
