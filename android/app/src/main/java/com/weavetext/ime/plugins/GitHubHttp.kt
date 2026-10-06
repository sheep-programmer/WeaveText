package com.weavetext.ime.plugins

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Credentials are sent only to the official API and are never forwarded to a download redirect. */
class GitHubHttp(
    private val credentials: GitHubCredentials,
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    fun <T> read(path: String, accept: String = "application/vnd.github+json", tokenOverride: String? = null, block: (InputStream) -> T): T {
        require(path.startsWith('/') && !path.startsWith("//") && path.none(Char::isISOControl))
        var url = URL("https://api.github.com$path")
        val token = tokenOverride ?: credentials.token()
        val epoch = credentials.epoch
        repeat(6) {
            check(credentials.epoch == epoch) { "GitHub 账号已变更，请重新操作" }
            require(allowed(url)) { "GitHub 下载地址不受支持" }
            val connection = connect(url)
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("User-Agent", "WeaveText-plugin-repositories")
                connection.setRequestProperty("Accept", accept)
                if (url.host == "api.github.com") {
                    connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                    if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
                }
                when (connection.responseCode) {
                    200 -> {
                        val input = object : java.io.FilterInputStream(connection.inputStream) {
                            private fun current() { check(credentials.epoch == epoch && !Thread.currentThread().isInterrupted) { "GitHub 账号已变更或下载已取消" } }
                            override fun read(): Int { current(); return super.read() }
                            override fun read(b: ByteArray, off: Int, len: Int): Int { current(); return super.read(b, off, len) }
                        }
                        return input.use(block)
                    }
                    301, 302, 303, 307, 308 -> {
                        url = URL(url, connection.getHeaderField("Location") ?: error("GitHub 下载跳转缺少地址"))
                    }
                    401 -> error("GitHub 令牌无效或已过期，请重新登录")
                    403, 429 -> error(if (connection.getHeaderField("X-RateLimit-Remaining") == "0" || connection.responseCode == 429) "GitHub 请求过于频繁，请稍后重试" else "当前令牌没有读取权限，请检查所选仓库、Contents 只读权限及组织授权")
                    404 -> error("找不到仓库或文件；私有仓库请先登录并确认令牌已授权该仓库")
                    else -> error("GitHub 请求失败（${connection.responseCode}），请稍后重试")
                }
            } catch (e: java.io.IOException) {
                throw IllegalStateException("连接 GitHub 失败，请检查网络后重试")
            } finally { connection.disconnect() }
        }
        error("GitHub 下载跳转次数过多")
    }

    companion object {
        fun allowed(url: URL): Boolean = url.protocol == "https" && url.userInfo == null && (url.port == -1 || url.port == 443) &&
            (url.host == "api.github.com" || url.host == "github.com" || url.host == "codeload.github.com" ||
                url.host == "objects.githubusercontent.com" || url.host == "release-assets.githubusercontent.com")
    }
}
