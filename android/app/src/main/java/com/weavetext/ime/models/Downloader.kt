package com.weavetext.ime.models

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 下载进度。 Download progress. */
data class Progress(
    val downloaded: Long,
    val total: Long,
    /** 最近约 1 秒的速度（字节/秒）。 Recent speed, bytes per second. */
    val bytesPerSecond: Long,
    /** 当前使用的镜像名称。 Mirror currently in use. */
    val mirror: String,
)

/**
 * 多镜像下载：先并发探测各镜像（取前 64 KB 计时），按快慢排序；下载支持断点续传，
 * 某个镜像中途失败会带着已下载部分换下一个镜像继续；完成后校验 SHA-256。
 *
 * Multi-mirror download: probe all mirrors concurrently (timing the first 64 KB), order them by speed,
 * download with resume, move on to the next mirror on failure while keeping the partial file, and
 * verify SHA-256 at the end. Android-free so it can be tested on the desktop JVM.
 */
class Downloader(
    private val mirrors: List<Mirror>,
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 20_000,
    private val probeTimeoutMs: Long = 6_000,
) {
    /** 并发探测每个镜像取前 64 KB 的耗时（毫秒），失败为 null；按快慢排序。 Probe all mirrors, fastest first. */
    fun probeAll(url: String): List<Pair<Mirror, Long?>> {
        if (mirrors.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(mirrors.size) { r -> Thread(r, "weave-probe").apply { isDaemon = true } }
        try {
            val futures = mirrors.map { m -> m to pool.submit(Callable { probe(m.apply(url)) }) }
            return futures
                .map { (m, f) -> m to runCatching { f.get(probeTimeoutMs, TimeUnit.MILLISECONDS) }.getOrNull() }
                .sortedBy { it.second ?: Long.MAX_VALUE }
        } finally {
            pool.shutdownNow()
        }
    }

    /** 按探测速度排好序的镜像（失败的排在最后）。 Mirrors ordered by probe speed, failures last. */
    fun rankMirrors(url: String): List<Mirror> =
        if (mirrors.size <= 1) mirrors else probeAll(url).map { it.first }

    /** 请求前 64 KB，返回耗时（毫秒），失败返回 null。 Time to fetch the first 64 KB, null on failure. */
    private fun probe(src: String): Long? = try {
        val t0 = System.nanoTime()
        val c = open(src, 0)
        c.setRequestProperty("Range", "bytes=0-65535")
        val code = c.responseCode
        if (code != 200 && code != 206) null else {
            c.inputStream.use { i ->
                val buf = ByteArray(16 * 1024)
                var n = 0
                while (n < 65536) {
                    val r = i.read(buf)
                    if (r < 0) break
                    n += r
                }
            }
            (System.nanoTime() - t0) / 1_000_000
        }
    } catch (_: Exception) {
        null
    }

    private fun open(src: String, from: Long): HttpURLConnection {
        val c = URI(src).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = connectTimeoutMs
        c.readTimeout = readTimeoutMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "WeaveText-ModelDownloader")
        if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
        return c
    }

    /**
     * 下载 [url] 到 [dest]（经 `dest.part` 续传），校验 [sha256]。
     * @param preferred 优先尝试的镜像 id（用户在设置里指定时）。 Mirror to try first.
     * @throws IOException 所有镜像都失败或校验失败。 All mirrors failed or checksum mismatch.
     */
    fun download(
        url: String,
        sha256: String,
        dest: File,
        cancel: AtomicBoolean = AtomicBoolean(false),
        preferred: String? = null,
        /** 已经探测好的顺序（省去重复测速）。 Pre-computed mirror order. */
        ranked: List<Mirror>? = null,
        /** 目录声明的大小；超出即中止，防止恶意镜像写满存储。0 表示未知。 Expected size; excess aborts. */
        expectedSize: Long = 0,
        onProgress: (Progress) -> Unit = {},
    ): File {
        if (dest.isFile && sha256Of(dest) == sha256) return dest
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        var order = ranked ?: rankMirrors(url)
        if (preferred != null) order = order.sortedBy { if (it.id == preferred) 0 else 1 }
        val errors = mutableListOf<String>()
        for (m in order) {
            if (cancel.get()) throw IOException("cancelled")
            try {
                fetchWithResume(m, url, part, cancel, expectedSize, onProgress)
                val got = sha256Of(part)
                if (got != sha256) {
                    // 校验失败说明这个镜像给的内容不对：丢弃后换下一个。 Bad content: drop and try the next.
                    part.delete()
                    throw IOException("sha256 mismatch from ${m.name}")
                }
                if (dest.exists()) dest.delete()
                if (!part.renameTo(dest)) throw IOException("rename failed")
                return dest
            } catch (e: IOException) {
                if (cancel.get()) throw IOException("cancelled")
                errors += "${m.name}: ${e.message}"
            }
        }
        throw IOException("all mirrors failed:\n" + errors.joinToString("\n"))
    }

    /** 同一镜像中途断开但有进展时，续传重试（最多 [RESUME_RETRIES] 次）。 Resume on the same mirror while it makes progress. */
    private fun fetchWithResume(
        m: Mirror, url: String, part: File, cancel: AtomicBoolean, expectedSize: Long, onProgress: (Progress) -> Unit,
    ) {
        var retries = 0
        while (true) {
            val before = if (part.isFile) part.length() else 0L
            try {
                fetch(m, url, part, cancel, expectedSize, onProgress)
                return
            } catch (e: IOException) {
                val progressed = part.isFile && part.length() > before
                if (cancel.get() || !progressed || ++retries > RESUME_RETRIES) throw e
            }
        }
    }

    private fun fetch(
        m: Mirror, url: String, part: File, cancel: AtomicBoolean, expectedSize: Long, onProgress: (Progress) -> Unit,
    ) {
        var have = if (part.isFile) part.length() else 0L
        if (expectedSize > 0 && have > expectedSize) {
            part.delete()
            have = 0
        }
        val c = open(m.apply(url), have)
        val code = c.responseCode
        if (code != 200 && code != 206) throw IOException("HTTP $code")
        var resumed = code == 206 && have > 0
        if (resumed) {
            // 续传必须从我们请求的偏移开始，否则丢弃已下载部分。 The range must start at our offset.
            val start = CONTENT_RANGE.find(c.getHeaderField("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull()
            if (start != have) {
                c.disconnect()
                part.delete()
                throw IOException("bad Content-Range from ${m.name}")
            }
        }
        if (code == 206 && have == 0L) resumed = false
        val total = if (resumed) have + c.contentLengthLong.coerceAtLeast(0) else c.contentLengthLong
        if (expectedSize > 0 && total > expectedSize) {
            c.disconnect()
            throw IOException("${m.name} announced $total bytes, expected $expectedSize")
        }
        RandomAccessFile(part, "rw").use { raf ->
            if (resumed) raf.seek(have) else raf.setLength(0)
            var done = if (resumed) have else 0L
            var windowStart = System.nanoTime()
            var windowBytes = 0L
            var speed = 0L
            c.inputStream.use { i ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    if (cancel.get()) throw IOException("cancelled")
                    val n = i.read(buf)
                    if (n < 0) break
                    if (expectedSize > 0 && done + n > expectedSize) {
                        raf.setLength(0)
                        throw IOException("${m.name} sent more than the expected $expectedSize bytes")
                    }
                    raf.write(buf, 0, n)
                    done += n
                    windowBytes += n
                    val now = System.nanoTime()
                    if (now - windowStart > 500_000_000) {
                        speed = windowBytes * 1_000_000_000 / (now - windowStart)
                        windowStart = now
                        windowBytes = 0
                        onProgress(Progress(done, total.takeIf { it > 0 } ?: expectedSize, speed, m.name))
                    }
                }
            }
            onProgress(Progress(done, total.takeIf { it > 0 } ?: expectedSize, speed, m.name))
            val want = if (total > 0) total else expectedSize
            if (want > 0 && done < want) throw IOException("connection closed early ($done/$want)")
        }
    }

    companion object {
        private const val RESUME_RETRIES = 5
        private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-""")

        fun sha256Of(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { i ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
