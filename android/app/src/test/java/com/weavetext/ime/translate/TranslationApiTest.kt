package com.weavetext.ime.translate

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranslationApiTest {
    private val request = TranslationRequest("auto", "en", "你好\n\"世界\" 🌏")
    private val executors = mutableListOf<ExecutorService>()
    private val apiKey = "private-key-for-translation-tests"

    @After fun shutdown() {
        executors.forEach { it.shutdownNow() }
        executors.forEach { assertTrue(it.awaitTermination(3, TimeUnit.SECONDS)) }
    }

    @Test fun localEngineIsAnExplicitInjectionAndHasNoEndpoint() {
        var received: TranslationRequest? = null
        val service = LocalTranslationService { received = it; "本地译文" }
        assertEquals(TranslationResult.Success("本地译文"), await(service))
        assertEquals(request, received)
    }

    @Test fun libreTranslateUsesOfficialJsonWithApiKeyInBodyOnly() {
        val connection = FakeConnection(response = "{\"translatedText\":\"Hello 🌏\",\"detectedLanguage\":{\"language\":\"zh\"}}")
        val config = HttpTranslationConfig(connection.url.toString(), apiKey = apiKey)
        assertEquals(TranslationResult.Success("Hello 🌏"), await(service(connection, config)))
        val body = JSONObject(connection.body())
        assertEquals(5, body.length())
        assertEquals(request.text, body.getString("q"))
        assertEquals("auto", body.getString("source"))
        assertEquals("en", body.getString("target"))
        assertEquals("text", body.getString("format"))
        assertEquals(apiKey, body.getString("api_key"))
        assertEquals("POST", connection.requestMethod)
        assertEquals("application/json; charset=utf-8", connection.getRequestProperty("Content-Type"))
        assertEquals("identity", connection.getRequestProperty("Accept-Encoding"))
        assertFalse(connection.url.toString().contains(apiKey))
        assertFalse(connection.requestProperties.toString().contains(apiKey))
        assertFalse(config.toString().contains(apiKey))
        assertTrue(connection.outputClosed.get())
        assertTrue(connection.inputClosed.get())
        assertEquals(1, connection.disconnects.get())
    }

    @Test fun selfHostedLibreTranslateOmitsEmptyKeyAndDoesNotChangeWhitespace() {
        val connection = FakeConnection(response = "{\"translatedText\":\"  Hello\\n\"}")
        assertEquals(TranslationResult.Success("  Hello\n"), await(service(connection)))
        assertEquals(4, JSONObject(connection.body()).length())
        assertFalse(JSONObject(connection.body()).has("api_key"))
    }

    @Test fun genericProtocolRemainsAvailableAndNeverSendsLibreTranslateKey() {
        val responses = listOf(
            "{\"translation\":\"Hello\"}", "{\"translatedText\":\"Hello\"}", "{\"text\":\"Hello\"}",
            "{\"data\":{\"translation\":\"Hello\"}}", "{\"translations\":[{\"text\":\"Hello\"}]}",
        )
        for (response in responses) {
            val connection = FakeConnection(response = response)
            val config = HttpTranslationConfig(connection.url.toString(), protocol = TranslationProtocol.GENERIC, apiKey = apiKey)
            assertEquals(TranslationResult.Success("Hello"), await(service(connection, config)))
            val body = JSONObject(connection.body())
            assertEquals(3, body.length())
            assertEquals("auto", body.getString("source_language"))
            assertEquals("en", body.getString("target_language"))
            assertEquals(request.text, body.getString("text"))
            assertFalse(connection.body().contains(apiKey))
        }
    }

    @Test fun invalidOrMismatchedJsonIsNotCoercedToTranslatedText() {
        for (response in listOf("not JSON", "{}", "{\"translation\":\"wrong protocol\"}",
            "{\"translatedText\":123}", "{\"translatedText\":null}", "{\"translatedText\":[\"batch\"]}", "{\"translatedText\":\" \"}")) {
            val connection = FakeConnection(response = response)
            assertEquals(TranslationFailure.BAD_RESPONSE, failure(await(service(connection))).kind)
            assertTrue(connection.inputClosed.get())
            assertEquals(1, connection.disconnects.get())
        }
    }

    @Test fun responseSizeIsBoundedWithAndWithoutContentLength() {
        for (declared in listOf(-1L, HttpTranslationService.MAX_RESPONSE_BYTES.toLong() + 1)) {
            val connection = FakeConnection(response = "x".repeat(HttpTranslationService.MAX_RESPONSE_BYTES + 1), declaredLength = declared)
            assertEquals(TranslationFailure.BAD_RESPONSE, failure(await(service(connection))).kind)
            if (declared == -1L) assertTrue(connection.inputClosed.get())
            assertEquals(1, connection.disconnects.get())
        }
        val connection = FakeConnection(response = JSONObject().put("translatedText", "x".repeat(HttpTranslationService.MAX_RESULT_CHARS + 1)).toString())
        assertEquals(TranslationFailure.BAD_RESPONSE, failure(await(service(connection))).kind)
    }

    @Test fun httpFailuresHaveSpecificMessagesAndNeverEchoKeyOrResponseBody() {
        val expected = mapOf(400 to TranslationFailure.INVALID_REQUEST, 401 to TranslationFailure.AUTHENTICATION,
            403 to TranslationFailure.AUTHENTICATION, 404 to TranslationFailure.NOT_CONFIGURED,
            429 to TranslationFailure.RATE_LIMITED, 503 to TranslationFailure.SERVICE_ERROR, 307 to TranslationFailure.SERVICE_ERROR)
        for ((status, kind) in expected) {
            val connection = FakeConnection(status = status, response = "{\"error\":\"$apiKey\"}")
            val error = failure(await(service(connection, HttpTranslationConfig(connection.url.toString(), apiKey = apiKey))))
            assertEquals(kind, error.kind)
            assertFalse(error.message.contains(apiKey))
            assertFalse(error.toString().contains(apiKey))
            assertNull(error.cause)
            assertFalse(connection.instanceFollowRedirects)
            assertTrue(connection.errorClosed.get())
            assertEquals(1, connection.disconnects.get())
        }
        val connection = FakeConnection(response = "{\"error\":\"$apiKey\",\"translatedText\":\"must not succeed\"}")
        assertEquals(TranslationFailure.SERVICE_ERROR, failure(await(service(connection))).kind)
    }

    @Test fun rawExceptionDoesNotLeakCredentialsAndTimeoutIsDistinct() {
        val failureConnection = FakeConnection(inputFailure = IOException(apiKey))
        val error = failure(await(service(failureConnection)))
        assertEquals(TranslationFailure.NETWORK, error.kind)
        assertFalse(error.toString().contains(apiKey))
        assertNull(error.cause)
        val timeoutConnection = FakeConnection(inputFailure = SocketTimeoutException(apiKey))
        assertEquals(TranslationFailure.TIMEOUT, failure(await(service(timeoutConnection))).kind)
    }

    @Test fun deadlineCoversStalledRequestWriteAndReleasesConnection() {
        val writing = CountDownLatch(1)
        val connection = object : FakeConnection() {
            private val released = CountDownLatch(1)
            override fun getOutputStream(): OutputStream = object : OutputStream() {
                override fun write(value: Int) {
                    writing.countDown()
                    try { released.await(2, TimeUnit.SECONDS) } catch (_: InterruptedException) { }
                }
                override fun write(bytes: ByteArray, offset: Int, length: Int) = write(0)
                override fun close() { outputClosed.set(true) }
            }
            override fun disconnect() { super.disconnect(); released.countDown() }
        }
        val service = service(connection, HttpTranslationConfig(connection.url.toString(), timeoutMs = 500))
        val outcome = LinkedBlockingQueue<TranslationResult>()
        service.translate(request) { outcome.add(it) }
        assertTrue(writing.await(2, TimeUnit.SECONDS))
        assertEquals(TranslationFailure.TIMEOUT, failure(outcome.poll(3, TimeUnit.SECONDS)!!).kind)
        assertEquals(1, connection.disconnects.get())
        assertTrue(outcome.isEmpty())
    }

    @Test fun cancellingQueuedRequestRemovesItWithoutOpeningConnection() {
        val executor = boundedWorker()
        val blocker = blockWorker(executor)
        val opened = AtomicInteger()
        val callbacks = AtomicInteger()
        try {
            val service = HttpTranslationService(HttpTranslationConfig("https://example.test/translate"), executor = executor,
                openConnection = { opened.incrementAndGet(); FakeConnection() })
            val call = service.translate(request) { callbacks.incrementAndGet() }
            assertEquals(1, executor.queue.size)
            call.cancel()
            call.cancel()
            assertTrue(executor.queue.isEmpty())
            assertEquals(0, opened.get())
            assertEquals(0, callbacks.get())
        } finally { blocker.countDown() }
    }

    @Test fun saturationReturnsBusyInsteadOfRunningNetworkOnCallingThread() {
        val executor = boundedWorker()
        val blocker = blockWorker(executor)
        val opened = AtomicInteger()
        val service = HttpTranslationService(HttpTranslationConfig("https://example.test/translate"), executor = executor,
            openConnection = { opened.incrementAndGet(); FakeConnection() })
        val first = service.translate(request) { }
        try {
            assertEquals(TranslationFailure.BUSY, failure(await(service)).kind)
            assertEquals(0, opened.get())
        } finally { first.cancel(); blocker.countDown() }
        assertEquals(2, TranslationExecutors.shared.maximumPoolSize)
        assertEquals(8, TranslationExecutors.shared.queue.size + TranslationExecutors.shared.queue.remainingCapacity())
    }

    @Test fun cancellingWhileConnectionIsBeingCreatedDisconnectsItBeforeUpload() {
        val opening = CountDownLatch(1)
        val release = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val connection = object : FakeConnection() {
            override fun disconnect() { super.disconnect(); disconnected.countDown() }
        }
        val callbacks = AtomicInteger()
        val worker = worker()
        val service = HttpTranslationService(HttpTranslationConfig(connection.url.toString()), executor = worker, openConnection = {
            opening.countDown()
            // Simulate a constructor which doesn't respond to interruption.
            while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
            connection
        })
        val call = service.translate(request) { callbacks.incrementAndGet() }
        try {
            assertTrue(opening.await(2, TimeUnit.SECONDS))
            call.cancel()
        } finally { release.countDown() }
        assertTrue(disconnected.await(2, TimeUnit.SECONDS))
        worker.submit(Runnable {}).get(2, TimeUnit.SECONDS)
        assertEquals("", connection.body())
        assertEquals(0, callbacks.get())
        assertEquals(1, connection.disconnects.get())
    }

    @Test fun cancellingAfterResponseSuppressesQueuedCallbackAndAlreadyClosedResources() {
        for (protocol in listOf(TranslationProtocol.LIBRE_TRANSLATE, TranslationProtocol.GENERIC)) {
            val queued = LinkedBlockingQueue<Runnable>()
            val connection = FakeConnection()
            val callbacks = AtomicInteger()
            val config = HttpTranslationConfig(connection.url.toString(), protocol = protocol)
            val service = HttpTranslationService(config, executor = worker(),
                callbackExecutor = Executor { queued.add(it) }, openConnection = { connection })
            val call = service.translate(request.copy(sourceLanguage = "zh")) { callbacks.incrementAndGet() }
            val callback = queued.poll(2, TimeUnit.SECONDS)!!
            assertTrue(connection.inputClosed.get())
            assertEquals(1, connection.disconnects.get())
            call.cancel()
            callback.run()
            assertEquals(0, callbacks.get())
        }
    }

    @Test fun changingPermissionWhileRequestIsQueuedPreventsUpload() {
        val executor = boundedWorker()
        val blocker = blockWorker(executor)
        val allowed = AtomicBoolean(true)
        val opened = AtomicInteger()
        val outcome = LinkedBlockingQueue<TranslationResult>()
        val service = HttpTranslationService(HttpTranslationConfig("https://example.test/translate"), executor = executor,
            accessAllowed = { allowed.get() }, openConnection = { opened.incrementAndGet(); FakeConnection() })
        service.translate(request) { outcome.add(it) }
        allowed.set(false)
        blocker.countDown()
        assertEquals(TranslationFailure.NOT_ENABLED, failure(outcome.poll(2, TimeUnit.SECONDS)!!).kind)
        assertEquals(0, opened.get())
    }

    @Test fun callbackFailureCannotLeakConnectionOrStrandWorker() {
        val queued = LinkedBlockingQueue<Runnable>()
        val connection = FakeConnection()
        HttpTranslationService(HttpTranslationConfig(connection.url.toString()), executor = worker(),
            callbackExecutor = Executor { queued.add(it) }, openConnection = { connection })
            .translate(request) { throw IllegalStateException("client callback") }
        val callback = queued.poll(2, TimeUnit.SECONDS)!!
        assertTrue(runCatching { callback.run() }.isFailure)
        assertTrue(connection.inputClosed.get())
        assertEquals(1, connection.disconnects.get())
    }

    @Test fun invalidRequestOrCredentialBearingEndpointNeverOpensConnection() {
        val opened = AtomicInteger()
        for (endpoint in listOf("file:///tmp/test", "https://user:secret@example.test/translate",
            "https://example.test/translate?api_key=$apiKey", "https://example.test/translate#$apiKey", "https://example.test/$apiKey")) {
            val config = HttpTranslationConfig(endpoint, apiKey = apiKey)
            val service = HttpTranslationService(config, executor = worker(), openConnection = { opened.incrementAndGet(); FakeConnection() })
            val error = failure(await(service))
            assertEquals(TranslationFailure.NOT_CONFIGURED, error.kind)
            assertFalse(error.toString().contains(apiKey))
        }
        val service = HttpTranslationService(HttpTranslationConfig("https://example.test/translate"), executor = worker(),
            openConnection = { opened.incrementAndGet(); FakeConnection() })
        assertEquals(TranslationFailure.INVALID_REQUEST, failure(await(service, request.copy(targetLanguage = "auto"))).kind)
        assertEquals(TranslationFailure.INVALID_REQUEST, failure(await(service, request.copy(text = "x".repeat(TranslationRequest.MAX_TEXT_CHARS + 1)))).kind)
        assertEquals(0, opened.get())
    }

    private fun worker(): ExecutorService = Executors.newSingleThreadExecutor().also { executors += it }
    private fun boundedWorker() = ThreadPoolExecutor(1, 1, 1L, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(1)).also { executors += it }
    private fun blockWorker(executor: ExecutorService): CountDownLatch {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        executor.execute { entered.countDown(); release.await() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        return release
    }
    private fun service(connection: FakeConnection, config: HttpTranslationConfig = HttpTranslationConfig(connection.url.toString())) =
        HttpTranslationService(config, executor = worker(), openConnection = { connection })

    private fun await(service: TranslationService, request: TranslationRequest = this.request): TranslationResult {
        val outcome = LinkedBlockingQueue<TranslationResult>()
        service.translate(request) { outcome.add(it) }
        return outcome.poll(3, TimeUnit.SECONDS) ?: error("translation callback timed out")
    }
    private fun failure(result: TranslationResult): TranslationError {
        assertTrue("expected failure, got $result", result is TranslationResult.Failure)
        return (result as TranslationResult.Failure).error
    }

    private open class FakeConnection(
        private val response: String = "{\"translatedText\":\"Hello\"}",
        private val status: Int = 200,
        private val inputFailure: Exception? = null,
        private val declaredLength: Long = -1,
    ) : HttpURLConnection(URL("https://example.test/translate")) {
        private val output = ByteArrayOutputStream()
        val outputClosed = AtomicBoolean()
        val inputClosed = AtomicBoolean()
        val errorClosed = AtomicBoolean()
        val disconnects = AtomicInteger()
        override fun connect() = Unit
        override fun disconnect() { disconnects.incrementAndGet() }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getContentLengthLong() = declaredLength
        override fun getInputStream(): InputStream {
            inputFailure?.let { throw it }
            return object : ByteArrayInputStream(response.toByteArray(Charsets.UTF_8)) {
                override fun close() { inputClosed.set(true); super.close() }
            }
        }
        override fun getErrorStream(): InputStream? = if (status in 200..299) null else object : ByteArrayInputStream(response.toByteArray()) {
            override fun close() { errorClosed.set(true); super.close() }
        }
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(value: Int) { output.write(value) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) { output.write(bytes, offset, length) }
            override fun close() { outputClosed.set(true) }
        }
        fun body(): String = output.toString(Charsets.UTF_8.name())
    }
}
