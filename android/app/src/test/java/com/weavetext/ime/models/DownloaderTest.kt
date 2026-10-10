package com.weavetext.ime.models

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real HTTP failures on loopback; no Android, Internet or JNI dependencies. */
class DownloaderTest {
    private val payload = ByteArray(256 * 1024) { (it * 31).toByte() }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun withServer(block: (LocalHttpServer, File) -> Unit) {
        val server = LocalHttpServer()
        val dir = Files.createTempDirectory("weave-download-test").toFile()
        try {
            block(server, dir)
            server.checkFailures()
        } finally { server.close(); dir.deleteRecursively() }
    }

    private fun mirror(server: LocalHttpServer, path: String) = Mirror(path, path, "http://127.0.0.1:${server.port}/$path/{url}")
    private fun Exchange.reply(body: ByteArray = payload, code: Int = 200, chunked: Boolean = false) {
        sendResponseHeaders(code, if (chunked) 0 else body.size.toLong())
        responseBody.use { it.write(body) }
    }

    /** java.net is also present on Android's boot classpath; the host-only JDK HTTP module is not. */
    private class LocalHttpServer : Closeable {
        private val listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = listener.localPort
        private val handlers = ConcurrentHashMap<String, (Exchange) -> Unit>()
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val failures = ConcurrentLinkedQueue<Throwable>()
        private val workers = Executors.newCachedThreadPool { r -> Thread(r, "weave-test-http").apply { isDaemon = true } }
        @Volatile private var closed = false
        private val acceptor = Thread({
            while (!closed) {
                try {
                    val socket = listener.accept()
                    sockets += socket
                    workers.execute {
                        try {
                            socket.use {
                                socket.soTimeout = 3_000
                                val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                                val request = input.readLine() ?: throw IOException("missing request")
                                val path = request.split(' ').getOrNull(1)?.substringBefore('?') ?: throw IOException("bad request")
                                val headers = Headers()
                                while (true) {
                                    val line = input.readLine() ?: throw IOException("incomplete request headers")
                                    if (line.isEmpty()) break
                                    headers.add(line.substringBefore(':'), line.substringAfter(':').trim())
                                }
                                val exchange = Exchange(socket, headers)
                                val handler = handlers.entries.filter { path.startsWith(it.key) }.maxByOrNull { it.key.length }?.value
                                if (handler != null) handler(exchange) else exchange.sendResponseHeaders(404, -1)
                            }
                        } catch (error: Throwable) {
                            // Clients deliberately abort connections in the cancellation, size and probe tests.
                            if (error !is IOException && error !is InterruptedException && !closed) failures += error
                        } finally { sockets -= socket }
                    }
                } catch (error: Exception) {
                    if (!closed) failures += error
                }
            }
        }, "weave-test-accept").apply { isDaemon = true; start() }

        fun createContext(prefix: String, handler: (Exchange) -> Unit) { handlers[prefix] = handler }
        fun checkFailures() { failures.peek()?.let { throw AssertionError("HTTP fixture handler failed", it) } }
        override fun close() {
            closed = true
            listener.close()
            sockets.forEach { runCatching { it.close() } }
            workers.shutdownNow()
            acceptor.join(1_000)
            workers.awaitTermination(1, TimeUnit.SECONDS)
        }
    }

    private class Headers {
        private val values = linkedMapOf<String, String>()
        fun add(name: String, value: String) { values[name.lowercase(Locale.ROOT)] = value }
        fun getFirst(name: String): String? = values[name.lowercase(Locale.ROOT)]
        fun lines(): String = values.entries.joinToString("") { (name, value) -> "$name: $value\r\n" }
    }

    private class Exchange(private val socket: Socket, val requestHeaders: Headers) : Closeable {
        val responseHeaders = Headers()
        lateinit var responseBody: OutputStream
            private set

        fun sendResponseHeaders(code: Int, length: Long) {
            val output = socket.getOutputStream()
            val framing = if (length == 0L) "Transfer-Encoding: chunked\r\n" else "Content-Length: ${length.coerceAtLeast(0)}\r\n"
            output.write(("HTTP/1.1 $code Test\r\nConnection: close\r\n" + framing + responseHeaders.lines() + "\r\n").toByteArray(Charsets.US_ASCII))
            output.flush()
            responseBody = if (length == 0L) ChunkedBody(output) else output
        }

        override fun close() { socket.close() }
    }

    /** Encode real HTTP chunks so unknown Content-Length and resumed range cases remain realistic. */
    private class ChunkedBody(private val output: OutputStream) : OutputStream() {
        override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (length == 0) return
            output.write((length.toString(16) + "\r\n").toByteArray(Charsets.US_ASCII))
            output.write(bytes, offset, length)
            output.write("\r\n".toByteArray(Charsets.US_ASCII))
        }
        override fun flush() { output.flush() }
        override fun close() {
            output.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.flush()
        }
    }

    @Test fun resumedChunkedResponseUsesRangeTotalOrExpectedMetadata() = withServer { server, dir ->
        for (unknownRangeSize in listOf(false, true)) {
            val path = "chunked-$unknownRangeSize"
            server.createContext("/$path/") { ex ->
                assertEquals("identity", ex.requestHeaders.getFirst("Accept-Encoding"))
                val from = ex.requestHeaders.getFirst("Range")!!.removePrefix("bytes=").substringBefore('-').toInt()
                ex.responseHeaders.add("Content-Range", "bytes $from-${payload.lastIndex}/${if (unknownRangeSize) "*" else payload.size}")
                ex.reply(payload.copyOfRange(from, payload.size), 206, chunked = true)
            }
            val dest = File(dir, path)
            File(dest.path + ".part").writeBytes(payload.copyOf(payload.size / 2))
            val progress = mutableListOf<Progress>()
            val m = mirror(server, path)
            Downloader(listOf(m)).download("file", sha(payload), dest, ranked = listOf(m), expectedSize = if (unknownRangeSize) payload.size.toLong() else 0) { progress += it }
            assertArrayEquals(payload, dest.readBytes())
            assertTrue(progress.any { it.phase == DownloadPhase.CONNECTING })
            assertTrue(progress.any { it.phase == DownloadPhase.VERIFYING })
            assertTrue(progress.filter { it.phase == DownloadPhase.DOWNLOADING }.all { it.total == payload.size.toLong() })
            assertEquals(payload.size.toLong(), progress.last().downloaded)
        }
    }

    @Test fun unknownChunkedSizeStaysUnknownInsteadOfReportingAFalse100Percent() = withServer { server, dir ->
        server.createContext("/unknown/") { ex ->
            val from = ex.requestHeaders.getFirst("Range")!!.removePrefix("bytes=").substringBefore('-').toInt()
            ex.responseHeaders.add("Content-Range", "bytes $from-${payload.lastIndex}/*")
            ex.reply(payload.copyOfRange(from, payload.size), 206, chunked = true)
        }
        val dest = File(dir, "file")
        File(dest.path + ".part").writeBytes(payload.copyOf(1024))
        val progress = mutableListOf<Progress>()
        val m = mirror(server, "unknown")
        Downloader(listOf(m)).download("file", sha(payload), dest, ranked = listOf(m)) { progress += it }
        assertTrue(progress.filter { it.phase == DownloadPhase.DOWNLOADING }.all { it.total == 0L })
        assertArrayEquals(payload, dest.readBytes())
    }

    @Test fun ignoredRangeRestartsAtZeroWithoutAppendingTheFullResponse() = withServer { server, dir ->
        server.createContext("/ignores/") { it.reply() }
        val dest = File(dir, "file")
        File(dest.path + ".part").writeBytes(payload.copyOf(1024))
        val m = mirror(server, "ignores")
        Downloader(listOf(m)).download("file", sha(payload), dest, ranked = listOf(m), expectedSize = payload.size.toLong())
        assertArrayEquals(payload, dest.readBytes())
    }

    @Test fun malformedRangeEvenOnInitial206FallsBackAndVerifies() = withServer { server, dir ->
        val badRequests = AtomicInteger()
        server.createContext("/bad/") { ex ->
            badRequests.incrementAndGet()
            ex.responseHeaders.add("Content-Range", "bytes 1-${payload.size}/${payload.size + 1}")
            ex.reply(code = 206)
        }
        server.createContext("/good/") { it.reply() }
        val mirrors = listOf(mirror(server, "bad"), mirror(server, "good"))
        val dest = File(dir, "file")
        Downloader(mirrors).download("file", sha(payload), dest, ranked = mirrors)
        assertEquals(1, badRequests.get())
        assertArrayEquals(payload, dest.readBytes())
    }

    @Test fun stalePartAnd416RestartTheOnlyMirrorRatherThanFailingEveryRetry() = withServer { server, dir ->
        val requests = AtomicInteger()
        server.createContext("/range/") { ex ->
            requests.incrementAndGet()
            if (ex.requestHeaders.getFirst("Range") != null) {
                ex.sendResponseHeaders(416, -1)
                ex.close()
            } else ex.reply()
        }
        val dest = File(dir, "file")
        File(dest.path + ".part").writeBytes(payload.copyOf(1024))
        val m = mirror(server, "range")
        Downloader(listOf(m)).download("file", sha(payload), dest, ranked = listOf(m))
        assertEquals(2, requests.get())
        assertArrayEquals(payload, dest.readBytes())
    }

    @Test fun corruptedMirrorAndMalformedCustomUrlDoNotBlockVerifiedFallback() = withServer { server, dir ->
        server.createContext("/corrupt/") { it.reply(payload.copyOf().also { b -> b[0] = 1 }) }
        server.createContext("/good/") { it.reply() }
        val mirrors = listOf(Mirror("invalid", "invalid", "http://[invalid/{url}"), mirror(server, "corrupt"), mirror(server, "good"))
        val dest = File(dir, "file")
        Downloader(mirrors).download("file", sha(payload), dest, ranked = mirrors, expectedSize = payload.size.toLong())
        assertArrayEquals(payload, dest.readBytes())
    }

    @Test fun corruptRetainedPrefixDoesNotCauseTheOnlyHealthyMirrorToBeRejected() = withServer { server, dir ->
        val requests = AtomicInteger()
        server.createContext("/good/") { ex ->
            requests.incrementAndGet()
            val from = ex.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
            if (from > 0) ex.responseHeaders.add("Content-Range", "bytes $from-${payload.lastIndex}/${payload.size}")
            ex.reply(payload.copyOfRange(from, payload.size), if (from > 0) 206 else 200)
        }
        val dest = File(dir, "file")
        File(dest.path + ".part").writeBytes(ByteArray(1024) { 1 })
        val m = mirror(server, "good")
        Downloader(listOf(m)).download("file", sha(payload), dest, ranked = listOf(m), expectedSize = payload.size.toLong())
        assertEquals(2, requests.get())
        assertArrayEquals(payload, dest.readBytes())
    }

    @Test fun oversizeChunkedResponseIsDiscardedBeforeFallback() = withServer { server, dir ->
        server.createContext("/big/") { ex -> runCatching { ex.reply(payload + payload, chunked = true) } }
        server.createContext("/good/") { it.reply() }
        val mirrors = listOf(mirror(server, "big"), mirror(server, "good"))
        val dest = File(dir, "file")
        Downloader(mirrors).download("file", sha(payload), dest, ranked = mirrors, expectedSize = payload.size.toLong())
        assertArrayEquals(payload, dest.readBytes())
    }

    @Test fun cancellationDuringBlockedBodyOrMetadataReadIsPromptAndKeepsPartial() = withServer { server, dir ->
        for (metadata in listOf(false, true)) {
            val entered = CountDownLatch(1)
            val received = CountDownLatch(1)
            val release = CountDownLatch(1)
            val path = "blocked-$metadata"
            server.createContext("/$path/") { ex ->
                runCatching {
                    ex.sendResponseHeaders(200, payload.size.toLong())
                    ex.responseBody.write(payload, 0, 1024)
                    ex.responseBody.flush()
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
                ex.close()
            }
            val cancel = AtomicBoolean(false)
            val worker = Executors.newSingleThreadExecutor()
            val dest = File(dir, path)
            val m = mirror(server, path)
            val result = worker.submit<Throwable?> {
                runCatching {
                    val dl = Downloader(listOf(m), readTimeoutMs = 10_000)
                    if (metadata) dl.readText(m.apply("file"), cancel)
                    else dl.download("file", sha(payload), dest, cancel, ranked = listOf(m)) { p ->
                        if (p.downloaded > 0 && p.phase == DownloadPhase.DOWNLOADING) received.countDown()
                    }
                }.exceptionOrNull()
            }
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                if (!metadata) assertTrue(received.await(2, TimeUnit.SECONDS))
                cancel.set(true)
                val error = result.get(2, TimeUnit.SECONDS)
                assertTrue("$error", error is IOException && error.message == "cancelled")
                assertFalse(dest.exists())
                if (!metadata) assertTrue(File(dest.path + ".part").length() in 1..1024)
            } finally { release.countDown(); worker.shutdownNow() }
        }
    }

    @Test fun stallAfterMakingProgressMovesToNextMirrorWithoutFiveTimeoutRetries() = withServer { server, dir ->
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        server.createContext("/stall/") { ex ->
            requests.incrementAndGet()
            runCatching {
                ex.sendResponseHeaders(200, payload.size.toLong())
                ex.responseBody.write(payload, 0, 1024)
                ex.responseBody.flush()
                release.await(3, TimeUnit.SECONDS)
            }
            ex.close()
        }
        server.createContext("/good/") { ex ->
            val from = ex.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
            if (from > 0) ex.responseHeaders.add("Content-Range", "bytes $from-${payload.lastIndex}/${payload.size}")
            ex.reply(payload.copyOfRange(from, payload.size), if (from > 0) 206 else 200)
        }
        try {
            val mirrors = listOf(mirror(server, "stall"), mirror(server, "good"))
            val dest = File(dir, "file")
            Downloader(mirrors, readTimeoutMs = 200).download("file", sha(payload), dest, ranked = mirrors, expectedSize = payload.size.toLong())
            assertEquals(1, requests.get())
            assertArrayEquals(payload, dest.readBytes())
        } finally { release.countDown() }
    }

    @Test fun probingUsesOneDeadlineAndPrecancelledDownloadsDoNotTouchCache() = withServer { server, dir ->
        val release = CountDownLatch(1)
        server.createContext("/slow/") { ex -> release.await(3, TimeUnit.SECONDS); ex.close() }
        try {
            val mirrors = (1..4).map { mirror(server, "slow").copy(id = "$it") }
            val start = System.nanoTime()
            val ranked = Downloader(mirrors, probeTimeoutMs = 200).probeAll("file")
            assertTrue("shared probe deadline", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 700)
            assertTrue(ranked.all { it.second == null })
            val dest = File(dir, "file").apply { writeBytes(payload) }
            val error = runCatching { Downloader(mirrors).download("file", sha(payload), dest, AtomicBoolean(true)) }.exceptionOrNull()
            assertTrue(error is IOException && error.message == "cancelled")
            assertArrayEquals(payload, dest.readBytes())
        } finally { release.countDown() }
    }

    @Test fun selectedSourceDownloadsWithoutWaitingForOrDuplicatingMirrorProbes() = withServer { server, dir ->
        val requests = AtomicInteger()
        val unwanted = AtomicInteger()
        server.createContext("/good/") { requests.incrementAndGet(); it.reply() }
        server.createContext("/other/") { unwanted.incrementAndGet(); it.sendResponseHeaders(404, -1); it.close() }
        val mirrors = listOf(mirror(server, "other"), mirror(server, "good"))
        val dest = File(dir, "file")
        Downloader(mirrors).download("file", sha(payload), dest, preferred = "good", expectedSize = payload.size.toLong())
        assertEquals(1, requests.get())
        assertEquals(0, unwanted.get())
        assertArrayEquals(payload, dest.readBytes())
    }
}
