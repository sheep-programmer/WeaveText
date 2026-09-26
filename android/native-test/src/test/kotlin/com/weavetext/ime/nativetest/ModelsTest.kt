package com.weavetext.ime.nativetest

import com.k2fsa.sherpa.onnx.LibraryUtils
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineZipformerCtcModelConfig
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import com.sun.net.httpserver.HttpServer
import com.weavetext.ime.models.Downloader
import com.weavetext.ime.models.Mirror
import com.weavetext.ime.models.ModelCatalog
import com.weavetext.ime.models.NativeArchive
import com.weavetext.ime.voice.local.OfflineAsr
import com.weavetext.ime.voice.local.StreamingAsr
import com.weavetext.ime.voice.local.TwoPassListener
import com.weavetext.ime.voice.local.TwoPassRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** 模型下载、解压与两遍识别的桌面测试。 Desktop tests for downloads, extraction and two-pass ASR. */
class ModelsTest {
    private val models = File(System.getProperty("weave.models"))
    private val cache = File(System.getProperty("weave.cache"))

    // ------------------------------------------------------------ catalog

    @Test
    fun catalogParses() {
        val cat = ModelCatalog.parse(File("../app/src/main/assets/models/catalog.json").readText())
        assertTrue(cat.mirrors.first().template == "{url}")
        assertTrue(cat.models.count { it.builtin } >= 2)
        assertTrue(cat.models.all { m -> m.archiveSha256.length == 64 && m.files.isNotEmpty() && m.files.all { it.sha256.length == 64 } })
        assertTrue(cat.hfMirrors.isNotEmpty())
    }

    // ------------------------------------------------------------ downloader

    private fun server(payload: ByteArray): Pair<HttpServer, AtomicInteger> {
        val drops = AtomicInteger(1)
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // /good/…：支持 Range；第一次完整请求在一半处断开，模拟网络中断。 Supports Range; first full GET drops halfway.
        s.createContext("/good/") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull() ?: 0L
            val to = range?.substringAfter('-')?.toLongOrNull()?.coerceAtMost(payload.size - 1L) ?: (payload.size - 1L)
            val body = payload.copyOfRange(from.toInt(), to.toInt() + 1)
            if (range != null) ex.responseHeaders.add("Content-Range", "bytes $from-$to/${payload.size}")
            ex.sendResponseHeaders(if (range != null) 206 else 200, body.size.toLong())
            val cut = range == null && drops.getAndDecrement() > 0
            ex.responseBody.use { it.write(if (cut) body.copyOf(body.size / 2) else body) }
        }
        // /bad/…：内容被篡改。 Serves corrupted bytes.
        s.createContext("/bad/") { ex ->
            val body = payload.copyOf().also { it[0] = (it[0] + 1).toByte() }
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        s.start()
        return s to drops
    }

    @Test
    fun downloaderFallsBackResumesAndVerifies() {
        val payload = ByteArray(3_000_000) { (it * 31 + 7).toByte() }
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val (srv, _) = server(payload)
        val port = srv.address.port
        try {
            val mirrors = listOf(
                Mirror("dead", "dead", "http://127.0.0.1:1/{url}"),
                Mirror("bad", "bad", "http://127.0.0.1:$port/bad/{url}"),
                Mirror("good", "good", "http://127.0.0.1:$port/good/{url}"),
            )
            val dest = Files.createTempDirectory("weave-dl").resolve("m.bin").toFile()
            val seen = mutableSetOf<String>()
            // 优先坏镜像：校验失败后应换到好镜像；好镜像第一次在一半断开，应续传完成。
            // Prefer the bad mirror: checksum fails, fall back; the good mirror drops once, then resumes.
            Downloader(mirrors).download("x/model.tar.bz2", sha, dest, preferred = "bad") { seen += it.mirror }
            assertEquals(sha, Downloader.sha256Of(dest))
            assertTrue(seen.contains("good"))
            assertTrue(!File(dest.path + ".part").exists())
            // 取消。 Cancellation.
            val dest2 = File(dest.parentFile, "m2.bin")
            val cancel = AtomicBoolean(true)
            val err = runCatching { Downloader(mirrors).download("x/model.tar.bz2", sha, dest2, cancel) }.exceptionOrNull()
            assertTrue(err is IOException && err.message!!.contains("cancelled"))
        } finally {
            srv.stop(0)
        }
    }

    @Test
    fun fetcherPrefersWorkingRouteAndVerifiesFiles() {
        val a = ByteArray(200_000) { (it % 251).toByte() }
        val b = "tokens".toByteArray()
        fun sha(x: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(x).joinToString("") { "%02x".format(it) }
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/hf/") { ex ->
            val body = when {
                ex.requestURI.path.endsWith("/model.int8.onnx") -> a
                ex.requestURI.path.endsWith("/tokens.txt") -> b
                else -> ByteArray(0)
            }
            ex.sendResponseHeaders(if (body.isEmpty()) 404 else 200, if (body.isEmpty()) -1 else body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        srv.start()
        try {
            val port = srv.address.port
            val spec = com.weavetext.ime.models.ModelSpec(
                id = "t", kind = com.weavetext.ime.models.ModelKind.ASR_OFFLINE, arch = "zipformer-ctc", name = "t",
                description = "", license = "", builtin = false,
                archiveUrl = "http://127.0.0.1:1/none.tar.bz2", archiveSha256 = "0".repeat(64), archiveSize = 0,
                files = listOf(
                    com.weavetext.ime.models.ModelFile("model.int8.onnx", a.size.toLong(), sha(a)),
                    com.weavetext.ime.models.ModelFile("tokens.txt", b.size.toLong(), sha(b)),
                ),
                installedSize = (a.size + b.size).toLong(), bench = null, hfRepo = "org/repo",
            )
            val work = Files.createTempDirectory("weave-fetch").toFile()
            val fetcher = com.weavetext.ime.models.ModelFetcher(
                githubMirrors = listOf(Mirror("dead", "dead", "{url}")),
                hfMirrors = listOf(Mirror("broken", "broken", "http://127.0.0.1:1/{url}"), Mirror("local", "local", "http://127.0.0.1:$port/hf/{url}")),
                workDir = work,
            ) { _, _, _ -> "should not extract" }
            val dest = File(work, "staging")
            var last: com.weavetext.ime.models.Progress? = null
            fetcher.fetch(spec, dest) { last = it }
            assertEquals(sha(a), Downloader.sha256Of(File(dest, "model.int8.onnx")))
            assertEquals("tokens", File(dest, "tokens.txt").readText())
            assertEquals(spec.installedSize, last!!.total)
            // 目录里的哈希不对 → 两条路线都失败并报错。 Wrong catalog hash → both routes fail.
            val bad = spec.copy(files = listOf(spec.files[0].copy(sha256 = "1".repeat(64))))
            val err = runCatching { fetcher.fetch(bad, File(work, "s2")) }.exceptionOrNull()
            assertTrue(err is IOException)
        } finally {
            srv.stop(0)
        }
    }

    /** 可选：真实网络上分别走两条路线下载内置流式模型（WEAVE_NET_TEST=1 时运行）。 Real-network check. */
    @Test
    fun realNetworkBothRoutes() {
        assumeTrue(System.getenv("WEAVE_NET_TEST") == "1")
        val cat = ModelCatalog.parse(File("../app/src/main/assets/models/catalog.json").readText())
        val spec = cat.find("asr-stream-small")!!
        val extract = { a: File, d: File, keep: List<String> -> NativeArchive.nativeExtractTarBz2(a.path, d.path, keep.joinToString("\n")) }
        for ((label, gh, hf) in listOf(
            Triple("hf-only", emptyList<Mirror>(), cat.hfMirrors),
            Triple("github-only", cat.mirrors, emptyList<Mirror>()),
        )) {
            val work = Files.createTempDirectory("weave-net").toFile()
            val t0 = System.nanoTime()
            var via = ""
            com.weavetext.ime.models.ModelFetcher(gh, hf, work, extract).fetch(spec.copy(hfRepo = if (hf.isEmpty()) null else spec.hfRepo), File(work, "m")) { via = it.mirror }
            println("$label via $via in ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
    }

    @Test
    fun downloaderRejectsOversizeAndBadRanges() {
        val payload = ByteArray(1_000_000) { (it * 7).toByte() }
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // 没有 Content-Length、一直多发数据。 No Content-Length, keeps sending too much.
        srv.createContext("/big/") { ex ->
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.use { o -> repeat(3) { o.write(payload) } }
        }
        // 续传时返回错误的起点。 Answers a resume with the wrong start offset.
        srv.createContext("/badrange/") { ex ->
            if (ex.requestHeaders.getFirst("Range") != null) {
                ex.responseHeaders.add("Content-Range", "bytes 0-${payload.size - 1}/${payload.size}")
                ex.sendResponseHeaders(206, payload.size.toLong())
            } else {
                ex.sendResponseHeaders(200, payload.size.toLong())
            }
            ex.responseBody.use { it.write(payload) }
        }
        srv.start()
        try {
            val port = srv.address.port
            val dir = Files.createTempDirectory("weave-dl2").toFile()
            val big = runCatching {
                Downloader(listOf(Mirror("big", "big", "http://127.0.0.1:$port/big/{url}")))
                    .download("f.bin", sha, File(dir, "a.bin"), expectedSize = payload.size.toLong())
            }.exceptionOrNull()
            assertTrue(big?.message.orEmpty(), big is IOException && big.message!!.contains("more than the expected"))
            assertTrue(File(dir, "a.bin.part").let { !it.exists() || it.length() <= payload.size })
            // 已有一半 .part：坏 range 镜像的内容被丢弃，改从正常镜像完整下载。 Half-done part + bad range.
            val dest = File(dir, "b.bin")
            File(dest.path + ".part").writeBytes(payload.copyOf(payload.size / 2))
            val (good, _) = server(payload)
            try {
                Downloader(listOf(
                    Mirror("badrange", "badrange", "http://127.0.0.1:$port/badrange/{url}"),
                    Mirror("good", "good", "http://127.0.0.1:${good.address.port}/good/{url}"),
                )).download("x/f.bin", sha, dest, preferred = "badrange", expectedSize = payload.size.toLong())
                assertEquals(sha, Downloader.sha256Of(dest))
            } finally {
                good.stop(0)
            }
        } finally {
            srv.stop(0)
        }
    }

    // ------------------------------------------------------------ extraction

    @Test
    fun extractsRealModelArchive() {
        val archive = File(cache, "sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01.tar.bz2")
        assumeTrue(archive.isFile)
        val dest = Files.createTempDirectory("weave-x").toFile()
        val t0 = System.nanoTime()
        val err = NativeArchive.nativeExtractTarBz2(archive.path, dest.path, "model.int8.onnx\ntokens.txt")
        println("extract ${archive.length() / 1_000_000} MB in ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertEquals(null, err)
        assertEquals(File(models, "asr-stream-small/model.int8.onnx").length(), File(dest, "model.int8.onnx").length())
        assertTrue(dest.list()!!.sorted() == listOf("model.int8.onnx", "tokens.txt"))
    }

    // ------------------------------------------------------------ two-pass ASR

    private fun wav(f: File): FloatArray {
        val b = f.readBytes()
        var off = 12
        while (!(b[off] == 'd'.code.toByte() && b[off + 1] == 'a'.code.toByte() && b[off + 2] == 't'.code.toByte())) {
            off += 8 + ByteBuffer.wrap(b, off + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        }
        val n = ByteBuffer.wrap(b, off + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int / 2
        val bb = ByteBuffer.wrap(b, off + 8, n * 2).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(n) { bb.short / 32768f }
    }

    @Test
    fun twoPassProducesFinalsPerUtterance() {
        val a = File("/tmp/asrbench/w4.wav")
        val b = File("/tmp/asrbench/w6.wav")
        assumeTrue(a.isFile && b.isFile && File(models, "asr-final-small/model.int8.onnx").isFile)
        LibraryUtils.load()
        val sDir = File(models, "asr-stream-small").path
        val online = OnlineRecognizer(
            OnlineRecognizerConfig.builder()
                .setOnlineModelConfig(
                    OnlineModelConfig.builder()
                        .setZipformer2Ctc(OnlineZipformer2CtcModelConfig.builder().setModel("$sDir/model.int8.onnx").build())
                        .setTokens("$sDir/tokens.txt").setNumThreads(2).build(),
                )
                .setEnableEndpoint(true)
                .setEndpointConfig(
                    EndpointConfig.builder()
                        .setRule1(EndpointRule.builder().setMustContainNonSilence(false).setMinTrailingSilence(2.4f).setMinUtteranceLength(0f).build())
                        .setRule2(EndpointRule.builder().setMustContainNonSilence(true).setMinTrailingSilence(0.8f).setMinUtteranceLength(0f).build())
                        .setRule3(EndpointRule.builder().setMustContainNonSilence(false).setMinTrailingSilence(0f).setMinUtteranceLength(20f).build())
                        .build(),
                ).build(),
        )
        val fDir = File(models, "asr-final-small").path
        val offlineRec = OfflineRecognizer(
            OfflineRecognizerConfig.builder().setOfflineModelConfig(
                OfflineModelConfig.builder()
                    .setZipformerCtc(OfflineZipformerCtcModelConfig.builder().setModel("$fDir/model.int8.onnx").build())
                    .setTokens("$fDir/tokens.txt").setNumThreads(2).build(),
            ).build(),
        )
        var stream = online.createStream()
        val streaming = object : StreamingAsr {
            override fun accept(samples: FloatArray) {
                stream.acceptWaveform(samples, 16000)
                while (online.isReady(stream)) online.decode(stream)
            }
            override fun text(): String = online.getResult(stream).text
            override fun isEndpoint(): Boolean = online.isEndpoint(stream)
            override fun reset() = online.reset(stream)
            override fun finish() {
                stream.acceptWaveform(FloatArray(8000), 16000)
                while (online.isReady(stream)) online.decode(stream)
            }
            override fun release() { stream.release(); online.release() }
        }
        val offline = object : OfflineAsr {
            override fun decode(samples: FloatArray): String {
                val s = offlineRec.createStream()
                s.acceptWaveform(samples, 16000)
                offlineRec.decode(s)
                return offlineRec.getResult(s).text.also { s.release() }
            }
            override fun release() = offlineRec.release()
        }
        val partials = mutableListOf<String>()
        val finals = mutableListOf<String>()
        val r = TwoPassRecognizer(streaming, offline, null, object : TwoPassListener {
            override fun onPartial(text: String) { partials += text }
            override fun onFinal(text: String) { finals += text }
        })
        // 句子 A + 1.5 秒静音 + 句子 B，按 40 ms 一块送入。 A + 1.5 s silence + B in 40 ms chunks.
        val audio = FloatArray(4800) + wav(a) + FloatArray(24000) + wav(b)
        audio.toList().chunked(640).forEach { r.feed(it.toFloatArray()) }
        r.finish()
        r.release()
        val refs = File("/tmp/asrbench/ref.txt").readLines()
        println("partials=${partials.size} finals=$finals")
        assertEquals(2, finals.size)
        assertEquals(refs[3], finals[0])
        assertEquals(refs[5], finals[1])
        assertTrue(partials.isNotEmpty())
    }
}
