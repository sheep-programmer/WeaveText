package com.weavetext.ime.models

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

enum class DownloadPhase { CONNECTING, DOWNLOADING, VERIFYING }

/** Unknown total is 0; known totals prefer the catalog's expected metadata. */
data class Progress(
    val downloaded: Long,
    val total: Long,
    val bytesPerSecond: Long,
    val mirror: String,
    val phase: DownloadPhase = DownloadPhase.DOWNLOADING,
)

/** Multi-mirror downloads with resume and SHA-256 verification; Android-free for JVM tests. */
class Downloader(
    private val mirrors: List<Mirror>,
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 20_000,
    private val probeTimeoutMs: Long = 6_000,
) {
    /** All probes share one deadline. Timed-out/cancelled connections are closed. */
    fun probeAll(url: String, cancel: AtomicBoolean = AtomicBoolean(false)): List<Pair<Mirror, Long?>> {
        checkCancelled(cancel)
        if (mirrors.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(mirrors.size) { r -> Thread(r, "weave-probe").apply { isDaemon = true } }
        val futures = mirrors.map { m -> pool.submit(Callable { probe(m.apply(url), cancel) }) }
        try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(probeTimeoutMs)
            while (futures.any { !it.isDone } && System.nanoTime() < deadline) {
                checkCancelled(cancel)
                Thread.sleep(25)
            }
            checkCancelled(cancel)
            return mirrors.zip(futures).map { (m, f) ->
                m to if (f.isDone && !f.isCancelled) runCatching { f.get() }.getOrNull() else null
            }.sortedBy { it.second ?: Long.MAX_VALUE }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("cancelled")
        } finally {
            futures.forEach { it.cancel(true) }
            pool.shutdownNow()
        }
    }

    fun rankMirrors(url: String, cancel: AtomicBoolean = AtomicBoolean(false)): List<Mirror> =
        if (mirrors.size <= 1) mirrors else probeAll(url, cancel).map { it.first }

    private fun probe(src: String, cancel: AtomicBoolean): Long? = try {
        val t0 = System.nanoTime()
        val c = open(src, 0)
        c.setRequestProperty("Range", "bytes=0-65535")
        RequestGuard(c, cancel, deadlineMs = probeTimeoutMs).use { guard ->
            val code = c.responseCode
            if (code != 200 && code != 206) null else {
                c.inputStream.use { input ->
                    val buf = ByteArray(16 * 1024)
                    var n = 0
                    while (n < 65536) {
                        guard.check()
                        val r = input.read(buf, 0, minOf(buf.size, 65536 - n))
                        if (r < 0) break
                        n += r
                        guard.activity()
                    }
                }
                (System.nanoTime() - t0) / 1_000_000
            }
        }
    } catch (_: Exception) { null }

    private fun open(src: String, from: Long): HttpURLConnection {
        val c = try {
            URI(src).toURL().openConnection() as? HttpURLConnection ?: throw IOException("not an HTTP URL")
        } catch (e: Exception) {
            throw IOException("invalid download URL", e)
        }
        c.connectTimeout = connectTimeoutMs
        c.readTimeout = readTimeoutMs
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "WeaveText-ModelDownloader")
        // Android's transparent gzip would invalidate byte offsets and lengths.
        c.setRequestProperty("Accept-Encoding", "identity")
        if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
        return c
    }

    /** Bounded, cancellable metadata reads, sharing HTTP cleanup with downloads. */
    fun readText(
        url: String,
        cancel: AtomicBoolean = AtomicBoolean(false),
        maxBytes: Int = 1024 * 1024,
        accept: String = "application/vnd.github+json",
    ): String = request(cancel) { scope -> readTextBlocking(url, cancel, maxBytes, accept, scope) }

    private fun readTextBlocking(url: String, cancel: AtomicBoolean, maxBytes: Int, accept: String, scope: RequestScope): String {
        checkCancelled(cancel)
        val c = open(url, 0)
        c.setRequestProperty("Accept", accept)
        RequestGuard(c, cancel, onTimeout = { scope.timeout() }).use { guard ->
            try {
                if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
                guard.activity()
                val out = ByteArrayOutputStream()
                c.inputStream.use { input ->
                    val buf = ByteArray(8192)
                    while (true) {
                        guard.check()
                        val n = input.read(buf)
                        if (n < 0) break
                        guard.activity()
                        if (out.size() + n > maxBytes) throw IOException("metadata too large")
                        out.write(buf, 0, n)
                    }
                }
                guard.check()
                return out.toString(Charsets.UTF_8.name())
            } catch (e: IOException) {
                guard.check()
                throw e
            }
        }
    }

    /** Existing parameters/callback stay compatible; all completion paths verify SHA. */
    fun download(
        url: String,
        sha256: String,
        dest: File,
        cancel: AtomicBoolean = AtomicBoolean(false),
        preferred: String? = null,
        ranked: List<Mirror>? = null,
        expectedSize: Long = 0,
        onProgress: (Progress) -> Unit = {},
    ): File {
        checkCancelled(cancel)
        if (!SHA256.matches(sha256)) throw IOException("invalid sha256")
        val digest = sha256.lowercase()
        fun verify(file: File, mirror: String): Boolean {
            checkCancelled(cancel)
            onProgress(Progress(file.length(), expectedSize.takeIf { it > 0 } ?: file.length(), 0, mirror, DownloadPhase.VERIFYING))
            return (expectedSize <= 0 || file.length() == expectedSize) && sha256Of(file, cancel) == digest
        }
        if (dest.isFile && verify(dest, "本机")) return dest
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        if (expectedSize > 0 && part.isFile && part.length() == expectedSize) {
            if (verify(part, "本机")) return promote(part, dest, cancel)
            part.delete()
        }
        onProgress(Progress(part.length(), expectedSize, 0, "", DownloadPhase.CONNECTING))
        // A user-selected source should connect immediately. Auto mode still ranks all sources.
        var order = ranked ?: if (preferred != null && mirrors.any { it.id == preferred }) mirrors else rankMirrors(url, cancel)
        if (preferred != null) order = order.sortedBy { if (it.id == preferred) 0 else 1 }
        val errors = mutableListOf<String>()
        for (m in order) {
            checkCancelled(cancel)
            try {
                var retriedFromZero = false
                while (true) {
                    val hadPartial = part.length() > 0
                    fetchWithResume(m, url, part, cancel, expectedSize, onProgress)
                    if (verify(part, m.name)) break
                    part.delete()
                    // The retained prefix may belong to a corrupt previous mirror. Give this
                    // mirror one clean attempt before blaming it for a mismatched resumed file.
                    if (!hadPartial || retriedFromZero) throw IOException("sha256 mismatch from ${m.name}")
                    retriedFromZero = true
                }
                return promote(part, dest, cancel)
            } catch (e: IOException) {
                checkCancelled(cancel)
                errors += "${m.name}: ${e.message}"
            }
        }
        throw IOException("all mirrors failed:\n" + errors.joinToString("\n"))
    }

    private fun promote(part: File, dest: File, cancel: AtomicBoolean): File {
        checkCancelled(cancel)
        if (dest.exists() && !dest.delete()) throw IOException("cannot replace destination")
        if (!part.renameTo(dest)) throw IOException("rename failed")
        return dest
    }

    /** Resume a progressing EOF on the same mirror; a stall switches mirrors immediately. */
    private fun fetchWithResume(
        m: Mirror, url: String, part: File, cancel: AtomicBoolean, expectedSize: Long, onProgress: (Progress) -> Unit,
    ) {
        var retries = 0
        var restarted = false
        while (true) {
            checkCancelled(cancel)
            val before = part.length()
            try {
                fetch(m, url, part, cancel, expectedSize, onProgress)
                return
            } catch (e: IOException) {
                checkCancelled(cancel)
                if (e is RestartFromZero && !restarted) { restarted = true; continue }
                if (e is SocketTimeoutException || part.length() <= before || ++retries > RESUME_RETRIES) throw e
            }
        }
    }

    private fun fetch(
        m: Mirror, url: String, part: File, cancel: AtomicBoolean, expectedSize: Long, onProgress: (Progress) -> Unit,
    ) = request(cancel) { scope -> fetchBlocking(m, url, part, cancel, expectedSize, onProgress, scope) }

    private fun fetchBlocking(
        m: Mirror, url: String, part: File, cancel: AtomicBoolean, expectedSize: Long, onProgress: (Progress) -> Unit, scope: RequestScope,
    ) {
        var have = part.length()
        if (expectedSize > 0 && have > expectedSize) { scope.mutate { part.delete() }; have = 0 }
        val transfer = TransferProgress(Progress(have, expectedSize, 0, m.name, DownloadPhase.CONNECTING)) { p -> scope.report { onProgress(p) } }
        transfer.emit(force = true)
        val c = open(m.apply(url), have)
        RequestGuard(c, cancel, heartbeat = { transfer.emit() }, onTimeout = { scope.timeout() }).use { guard ->
            try {
                val code = c.responseCode
                guard.activity()
                if (code == 416) {
                    val serverSize = UNSATISFIED_RANGE.matchEntire(c.getHeaderField("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull()
                    if (have > 0 && serverSize == have && (expectedSize <= 0 || have == expectedSize)) return
                    // An incomplete/stale part must not turn 416 into successful completion.
                    scope.mutate { part.delete() }
                    throw RestartFromZero()
                }
                if (code != 200 && code != 206) throw IOException("HTTP $code")
                if (!c.contentEncoding.isNullOrBlank() && !c.contentEncoding.equals("identity", true)) throw IOException("unexpected Content-Encoding")
                val resumed = code == 206
                val length = c.contentLengthLong
                val range = if (resumed) parseRange(c.getHeaderField("Content-Range")) else null
                if (resumed && (range == null || range.start != have || (length >= 0 && length != range.end - range.start + 1))) {
                    scope.mutate { part.delete() }
                    throw IOException("bad Content-Range from ${m.name}")
                }
                val announced = if (resumed) range!!.total else length
                if (expectedSize > 0 && announced > 0 && announced != expectedSize) throw IOException("${m.name} announced $announced bytes, expected $expectedSize")
                if (expectedSize > 0 && range != null && range.end >= expectedSize) throw IOException("bad Content-Range from ${m.name}")
                // A chunked resumed response must not use have + 0 as its total.
                val total = if (expectedSize > 0) expectedSize else announced.coerceAtLeast(0)
                val offset = if (resumed) have else 0L
                transfer.begin(offset, total)
                RandomAccessFile(part, "rw").use { raf ->
                    scope.mutate { if (resumed) raf.seek(have) else raf.setLength(0) }
                    var done = offset
                    c.inputStream.use { input ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            guard.check()
                            val n = input.read(buf)
                            if (n < 0) break
                            guard.check()
                            guard.activity()
                            val limit = if (expectedSize > 0) expectedSize else total
                            if ((limit > 0 && n > limit - done) || (range != null && n > range.end + 1 - done)) {
                                scope.mutate { raf.setLength(0) }
                                throw IOException("${m.name} sent more than the expected ${limit.takeIf { it > 0 } ?: range?.total} bytes")
                            }
                            scope.mutate { raf.write(buf, 0, n) }
                            done += n
                            transfer.advance(done, n)
                        }
                    }
                    guard.check()
                    transfer.emit(force = true)
                    if (range != null && done < range.end + 1) throw IOException("connection closed early ($done/${range.end + 1})")
                    if (total > 0 && done < total) throw IOException("connection closed early ($done/$total)")
                }
            } catch (e: IOException) {
                guard.check()
                throw e
            } finally {
                transfer.stop()
            }
        }
    }

    private class RestartFromZero : IOException("Range not satisfiable; restarting")
    private data class ByteRange(val start: Long, val end: Long, val total: Long)

    /** Some HttpURLConnection implementations lock disconnect behind a read. Cancellation must
     * not wait for that cleanup. Stop file mutations/callbacks before returning to the caller;
     * the guarded daemon request finishes socket cleanup under the configured timeouts. */
    private class RequestScope(private val cancel: AtomicBoolean) {
        private var stopped = false
        @Volatile private var timedOut = false
        fun timeout() { timedOut = true }
        fun check() {
            checkCancelled(cancel)
            if (timedOut) throw SocketTimeoutException("download stalled")
        }
        @Synchronized fun <T> mutate(block: () -> T): T {
            check()
            if (stopped) throw InterruptedIOException("cancelled")
            return block()
        }
        @Synchronized fun report(block: () -> Unit) { if (!stopped && !timedOut && !cancel.get()) block() }
        @Synchronized fun stop() { stopped = true }
    }

    private fun <T> request(cancel: AtomicBoolean, block: (RequestScope) -> T): T {
        checkCancelled(cancel)
        val scope = RequestScope(cancel)
        val task = NETWORK_IO.submit(Callable { block(scope) })
        try {
            while (true) {
                scope.check()
                try { return task.get(100, TimeUnit.MILLISECONDS) }
                catch (_: TimeoutException) { /* poll cancellation independently of blocking socket cleanup */ }
                catch (e: ExecutionException) {
                    val cause = e.cause
                    when (cause) {
                        is IOException -> throw cause
                        is RuntimeException -> throw cause
                        else -> throw IOException("HTTP request failed", cause)
                    }
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("cancelled")
        } finally {
            scope.stop()
            if (!task.isDone) task.cancel(true)
        }
    }

    /** Heartbeats clear stale speed during blocked reads; callback ordering is serialized. */
    private class TransferProgress(private var p: Progress, private val callback: (Progress) -> Unit) {
        private var stopped = false
        private var last = System.nanoTime()
        private var bytes = 0L
        @Synchronized fun begin(done: Long, total: Long) {
            p = p.copy(downloaded = done, total = total, phase = DownloadPhase.DOWNLOADING)
            emit(force = true)
        }
        @Synchronized fun advance(done: Long, count: Int) { p = p.copy(downloaded = done); bytes += count; emit() }
        @Synchronized fun emit(force: Boolean = false) {
            if (stopped) return
            val now = System.nanoTime()
            if (!force && now - last < 500_000_000) return
            val speed = if (p.phase == DownloadPhase.DOWNLOADING && now > last) bytes * 1_000_000_000 / (now - last) else 0
            p = p.copy(bytesPerSecond = speed)
            bytes = 0
            last = now
            callback(p)
        }
        @Synchronized fun stop() { stopped = true }
    }

    /** AtomicBoolean cannot wake a blocked socket: actively disconnect on cancel/timeout. */
    private inner class RequestGuard(
        private val connection: HttpURLConnection,
        private val cancel: AtomicBoolean,
        deadlineMs: Long = 0,
        private val heartbeat: () -> Unit = {},
        private val onTimeout: () -> Unit = {},
    ) : Closeable {
        private val owner = Thread.currentThread()
        @Volatile private var closed = false
        @Volatile private var timedOut = false
        @Volatile private var lastActivity = System.nanoTime()
        @Volatile private var connected = false
        private val deadline = if (deadlineMs > 0) lastActivity + TimeUnit.MILLISECONDS.toNanos(deadlineMs) else Long.MAX_VALUE
        private val watcher = Thread({
            try {
                while (!closed) {
                    val idleMs = if (connected) readTimeoutMs.toLong() else connectTimeoutMs.toLong() + readTimeoutMs
                    val now = System.nanoTime()
                    timedOut = now >= deadline || (idleMs > 0 && now - lastActivity >= TimeUnit.MILLISECONDS.toNanos(idleMs))
                    if (timedOut) onTimeout()
                    if (cancel.get() || owner.isInterrupted || timedOut) { connection.disconnect(); break }
                    heartbeat()
                    Thread.sleep(100)
                }
            } catch (_: InterruptedException) { /* close */ }
        }, "weave-http-watch").apply { isDaemon = true; start() }

        fun activity() { connected = true; lastActivity = System.nanoTime() }
        fun check() {
            checkCancelled(cancel)
            if (timedOut) throw SocketTimeoutException("download stalled")
        }
        override fun close() {
            closed = true
            watcher.interrupt()
            connection.disconnect()
        }
    }

    companion object {
        private val NETWORK_IO = Executors.newCachedThreadPool { r -> Thread(r, "weave-http").apply { isDaemon = true } }
        private const val RESUME_RETRIES = 5
        private val SHA256 = Regex("[0-9a-fA-F]{64}")
        private val CONTENT_RANGE = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
        private val UNSATISFIED_RANGE = Regex("bytes\\s+\\*/(\\d+)", RegexOption.IGNORE_CASE)

        private fun parseRange(header: String?): ByteRange? {
            val match = CONTENT_RANGE.matchEntire(header.orEmpty().trim()) ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val end = match.groupValues[2].toLongOrNull() ?: return null
            val total = match.groupValues[3].let { if (it == "*") 0 else it.toLongOrNull() ?: return null }
            return ByteRange(start, end, total).takeIf { end >= start && end < Long.MAX_VALUE && (total == 0L || end < total) }
        }

        internal fun checkCancelled(cancel: AtomicBoolean) {
            if (cancel.get() || Thread.currentThread().isInterrupted) throw InterruptedIOException("cancelled")
        }

        fun sha256Of(f: File, cancel: AtomicBoolean = AtomicBoolean(false)): String {
            checkCancelled(cancel)
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    checkCancelled(cancel)
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            checkCancelled(cancel)
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
