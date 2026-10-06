package com.weavetext.ime.translate

import com.weavetext.ime.settings.TranslationSettings
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** All service instances share two workers and at most eight waiting requests. */
internal object TranslationExecutors {
    val shared = ThreadPoolExecutor(
        2, 2, 30L, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(8),
        { runnable -> Thread(runnable, "weave-translation").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    ).apply { allowCoreThreadTimeOut(true) }
    val deadlines = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "weave-translation-timeout").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
}

/** Text selected by the user and sent to a translation provider. */
data class TranslationRequest(
    val sourceLanguage: String,
    val targetLanguage: String,
    val text: String,
) {
    fun validationError(): TranslationError? = when {
        text.isBlank() -> TranslationError(TranslationFailure.INVALID_REQUEST, "请先选择文字或手动粘贴待翻译内容")
        text.length > MAX_TEXT_CHARS -> TranslationError(TranslationFailure.INVALID_REQUEST, "文字太长，请分段翻译")
        sourceLanguage.isBlank() -> TranslationError(TranslationFailure.INVALID_REQUEST, "请选择源语言")
        targetLanguage.isBlank() -> TranslationError(TranslationFailure.INVALID_REQUEST, "请选择目标语言")
        targetLanguage == TranslationLanguages.AUTO -> TranslationError(TranslationFailure.INVALID_REQUEST, "目标语言不能为自动识别")
        else -> null
    }

    override fun toString() = "TranslationRequest(source=$sourceLanguage, target=$targetLanguage, characters=${text.length})"

    companion object {
        /** Keep a single request bounded even when an editor returns a very large selection. */
        const val MAX_TEXT_CHARS = 32_000
    }
}

/** Provider result. Providers may be local, plugin-backed, or HTTP-backed. */
sealed interface TranslationResult {
    data class Success(val text: String) : TranslationResult
    data class Failure(val error: TranslationError) : TranslationResult
}

enum class TranslationFailure {
    NOT_ENABLED,
    NOT_CONFIGURED,
    INVALID_REQUEST,
    TIMEOUT,
    NETWORK,
    BAD_RESPONSE,
    CANCELLED,
    PRIVACY_BLOCKED,
    BUSY,
    AUTHENTICATION,
    RATE_LIMITED,
    SERVICE_ERROR,
    LANGUAGE_UNDETERMINED,
    UNSUPPORTED_LANGUAGE,
    MODEL_PREPARATION_FAILED,
    IDENTIFICATION_FAILED,
    DEVICE_TRANSLATION_FAILED,
}

data class TranslationError(
    val kind: TranslationFailure,
    override val message: String,
    val reason: Throwable? = null,
) : Exception(message, reason)

/** A cancellable provider operation. Cancellation suppresses the callback. */
fun interface TranslationCall {
    fun cancel()
}

/** Translation provider boundary used by the panel and by future local/plugin providers. */
interface TranslationService {
    fun translate(request: TranslationRequest, callback: (TranslationResult) -> Unit): TranslationCall
}

enum class TranslationStage { IDENTIFYING_LANGUAGE, PREPARING_MODEL, TRANSLATING }
data class TranslationProgress(val stage: TranslationStage, val message: String)

/** Optional progress contract. UI callers marshal callbacks and reject stale session generations. */
interface TranslationProgressService : TranslationService {
    fun translate(
        request: TranslationRequest,
        onProgress: (TranslationProgress) -> Unit,
        callback: (TranslationResult) -> Unit,
    ): TranslationCall

    override fun translate(request: TranslationRequest, callback: (TranslationResult) -> Unit): TranslationCall =
        translate(request, {}, callback)
}

/** Optional injection/test hook. Google translation uses SDK language packs downloaded on demand. */
fun interface LocalTranslationEngine {
    fun translate(request: TranslationRequest): String?
}

/** Synchronous adapter for a local model; it never creates a network request. */
class LocalTranslationService(private val engine: LocalTranslationEngine) : TranslationService {
    override fun translate(request: TranslationRequest, callback: (TranslationResult) -> Unit): TranslationCall {
        request.validationError()?.let { callback(TranslationResult.Failure(it)); return TranslationCall {} }
        val result = runCatching { engine.translate(request)?.takeIf { it.isNotBlank() } }
            .fold(
                onSuccess = { text ->
                    if (text == null) TranslationResult.Failure(TranslationError(TranslationFailure.BAD_RESPONSE, "离线翻译没有返回结果"))
                    else TranslationResult.Success(text)
                },
                onFailure = { TranslationResult.Failure(TranslationError(TranslationFailure.BAD_RESPONSE, "离线翻译失败", it)) },
            )
        callback(result)
        return TranslationCall {}
    }
}

/** Editor operations kept separate so plugin/local providers never gain editor access. */
interface TranslationEditor {
    fun selectedText(): String?
    fun replaceSelection(text: String): Boolean
    fun insert(text: String): Boolean
}

/** No network request is made when translation is disabled or not configured. */
class DisabledTranslationService(
    private val failure: TranslationError = TranslationError(
        TranslationFailure.NOT_ENABLED,
        "在线翻译未开启；请先在设置中配置服务并明确开启",
    ),
) : TranslationService {
    override fun translate(request: TranslationRequest, callback: (TranslationResult) -> Unit): TranslationCall {
        request.validationError()?.let { callback(TranslationResult.Failure(it)); return TranslationCall {} }
        callback(TranslationResult.Failure(failure))
        return TranslationCall {}
    }
}

data class TranslationLanguage(val code: String, val label: String)

object TranslationLanguages {
    const val AUTO = "auto"

    val all = listOf(
        TranslationLanguage(AUTO, "自动识别"),
        TranslationLanguage("zh", "中文"),
        TranslationLanguage("en", "英语"),
        TranslationLanguage("ja", "日语"),
        TranslationLanguage("ko", "韩语"),
        TranslationLanguage("fr", "法语"),
        TranslationLanguage("de", "德语"),
        TranslationLanguage("es", "西班牙语"),
        TranslationLanguage("ru", "俄语"),
    )

    fun label(code: String): String = all.firstOrNull { it.code == code }?.label ?: code
}

enum class TranslationProtocol(val id: String, val label: String) {
    GOOGLE_WEB("google_web", "Google Translate 网页（无需下载）"),
    GOOGLE_DEVICE("google_device", "Google 离线翻译插件"),
    LIBRE_TRANSLATE("libretranslate", "LibreTranslate"),
    GENERIC("generic", "Generic JSON"),
}

/** Never include endpoint, headers or API key in diagnostic string representations. */
class HttpTranslationConfig(
    val endpoint: String,
    val timeoutMs: Int = 8_000,
    headers: Map<String, String> = emptyMap(),
    val protocol: TranslationProtocol = TranslationProtocol.LIBRE_TRANSLATE,
    val apiKey: String = "",
) {
    val headers: Map<String, String> = headers.toMap()

    fun endpointUrl(): URL {
        require(protocol != TranslationProtocol.GOOGLE_DEVICE && protocol != TranslationProtocol.GOOGLE_WEB) { "设备端翻译不使用 HTTP 地址" }
        require(endpoint.length <= 2_000) { "翻译服务地址过长" }
        require(apiKey.length <= MAX_API_KEY_CHARS && apiKey.none { it.isISOControl() }) { "API key 格式无效" }
        val uri = try { URI(endpoint.trim()) } catch (_: Exception) {
            throw IllegalArgumentException("翻译服务地址格式无效")
        }
        require(uri.scheme == "http" || uri.scheme == "https") { "服务地址必须使用 http 或 https" }
        require(uri.userInfo == null) { "服务地址不能包含用户信息" }
        require(uri.host != null && uri.host.isNotBlank()) { "服务地址缺少主机名" }
        require(uri.fragment == null) { "服务地址不能包含片段" }
        require(uri.rawQuery == null) { "服务地址不能带查询参数；请在 API key 栏填写凭据" }
        require(uri.port == -1 || uri.port in 1..65535) { "服务端口无效" }
        require(apiKey.isBlank() || !endpoint.contains(apiKey) && !uri.path.orEmpty().contains(apiKey)) {
            "API key 只能放在请求体中，不能放在服务地址中"
        }
        return uri.toURL()
    }

    val boundedTimeoutMs: Int get() = timeoutMs.coerceIn(500, 30_000)

    override fun toString() = "HttpTranslationConfig(protocol=${protocol.id}, timeoutMs=$boundedTimeoutMs)"

    companion object {
        const val MAX_API_KEY_CHARS = 1_024
    }
}

/**
 * LibreTranslate: POST q/source/target/format=text/optional api_key; read translatedText.
 * Generic: POST source_language/target_language/text; read a supported string result.
 * Callbacks run on callbackExecutor (worker thread by default); UI callers must marshal to their UI thread.
 */
class HttpTranslationService(
    private val config: HttpTranslationConfig,
    private val executor: ExecutorService = TranslationExecutors.shared,
    private val callbackExecutor: Executor = Executor { it.run() },
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val deadlineExecutor: ScheduledExecutorService = TranslationExecutors.deadlines,
    private val accessAllowed: () -> Boolean = { true },
) : TranslationService {
    override fun translate(request: TranslationRequest, callback: (TranslationResult) -> Unit): TranslationCall {
        val call = HttpCall(callback)
        request.validationError()?.let { call.finish(TranslationResult.Failure(it)); return call }
        val task = FutureTask<Unit>({
            var connection: HttpURLConnection? = null
            val result = try {
                call.checkActive()
                checkAccess()
                val url = config.endpointUrl()
                val body = encode(request).toByteArray(Charsets.UTF_8)
                call.checkActive()
                val conn = openConnection(url)
                connection = conn
                call.attach(conn)
                conn.apply {
                    requestMethod = "POST"
                    connectTimeout = config.boundedTimeoutMs
                    readTimeout = config.boundedTimeoutMs
                    doOutput = true
                    useCaches = false
                    // A redirect could disclose source text/key to an unconfigured destination.
                    instanceFollowRedirects = false
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    config.headers.forEach { (key, value) -> setRequestProperty(key, value) }
                    setFixedLengthStreamingMode(body.size)
                }
                call.checkActive()
                checkAccess()
                conn.outputStream.use { output ->
                    call.checkActive()
                    output.write(body)
                }
                call.checkActive()
                val status = conn.responseCode
                if (status !in 200..299) throw statusError(status)
                if (conn.contentLengthLong > MAX_RESPONSE_BYTES) throw tooLarge()
                val response = conn.inputStream.use { readBounded(it, call) }
                TranslationResult.Success(parseResponse(response))
            } catch (error: Exception) {
                TranslationResult.Failure(error.toTranslationError())
            } finally {
                // Release resources before delivering a result, including when client callbacks throw.
                runCatching { connection?.errorStream?.close() }
                call.release(connection)
            }
            call.finish(result)
        }, Unit)
        call.bind(task)
        try {
            // The deadline includes waiting in the bounded worker queue and request writes.
            call.bindDeadline(deadlineExecutor.schedule({
                call.finish(TranslationResult.Failure(timeoutError()), interrupt = true)
            }, config.boundedTimeoutMs.toLong(), TimeUnit.MILLISECONDS))
            call.enqueue(task)
        } catch (_: RejectedExecutionException) {
            call.finish(TranslationResult.Failure(TranslationError(TranslationFailure.BUSY, "翻译请求较多，请稍后重试")), interrupt = true)
        }
        return call
    }

    private fun encode(request: TranslationRequest): String {
        val json = when (config.protocol) {
            TranslationProtocol.GOOGLE_DEVICE, TranslationProtocol.GOOGLE_WEB -> error("Device and web translation do not use custom HTTP")
            TranslationProtocol.LIBRE_TRANSLATE -> JSONObject()
                .put("q", request.text).put("source", request.sourceLanguage)
                .put("target", request.targetLanguage).put("format", "text")
                .also { if (config.apiKey.isNotBlank()) it.put("api_key", config.apiKey) }
            TranslationProtocol.GENERIC -> JSONObject()
                .put("source_language", request.sourceLanguage)
                .put("target_language", request.targetLanguage).put("text", request.text)
        }
        return json.toString()
    }

    private fun readBounded(input: InputStream, call: HttpCall): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (true) {
            call.checkActive()
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_BYTES - output.size() + 1))
            if (count < 0) break
            if (output.size() + count > MAX_RESPONSE_BYTES) throw tooLarge()
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun parseResponse(body: String): String {
        val root = JSONObject(body)
        // Never expose the service's error text: it may echo the API key or source text.
        if (root.has("error")) throw TranslationError(TranslationFailure.SERVICE_ERROR, "翻译服务返回错误，请检查语言及服务配置")
        val values = when (config.protocol) {
            TranslationProtocol.GOOGLE_DEVICE, TranslationProtocol.GOOGLE_WEB -> error("Device and web translation do not use custom HTTP")
            TranslationProtocol.LIBRE_TRANSLATE -> sequenceOf(root.opt("translatedText"))
            TranslationProtocol.GENERIC -> sequenceOf(
                root.opt("translation"), root.opt("translatedText"), root.opt("text"),
                root.optJSONObject("data")?.opt("translation"),
                root.optJSONArray("translations")?.optJSONObject(0)?.opt("text"),
            )
        }
        val text = values.filterIsInstance<String>().firstOrNull { it.isNotBlank() }
            ?: throw TranslationError(TranslationFailure.BAD_RESPONSE, "翻译服务没有返回有效的文字结果，请检查服务协议")
        if (text.length > MAX_RESULT_CHARS) throw tooLarge()
        return text
    }

    private fun checkAccess() {
        if (!runCatching { accessAllowed() }.getOrDefault(false)) throw TranslationError(TranslationFailure.NOT_ENABLED, "在线翻译已关闭或配置已变更，请确认设置后重试")
    }

    private fun Throwable.toTranslationError(): TranslationError = when (this) {
        is TranslationError -> this
        is InterruptedException -> TranslationError(TranslationFailure.CANCELLED, "翻译已取消")
        is java.net.SocketTimeoutException -> timeoutError()
        is IllegalArgumentException -> TranslationError(TranslationFailure.NOT_CONFIGURED, "翻译服务配置无效，请检查地址、协议和 API key")
        is org.json.JSONException -> TranslationError(TranslationFailure.BAD_RESPONSE, "翻译服务返回格式无法识别，请检查服务协议")
        // Do not retain raw exception messages/causes in client-visible failures.
        else -> TranslationError(TranslationFailure.NETWORK, "无法连接翻译服务，请检查网络、HTTPS 证书或服务地址")
    }

    private fun statusError(status: Int): TranslationError = when (status) {
        400, 422 -> TranslationError(TranslationFailure.INVALID_REQUEST, "服务拒绝翻译请求，请检查语言支持或文字长度（HTTP $status）")
        401, 403 -> TranslationError(TranslationFailure.AUTHENTICATION, "服务拒绝访问，请检查 API key 或服务权限（HTTP $status）")
        404 -> TranslationError(TranslationFailure.NOT_CONFIGURED, "翻译接口不存在，请填写完整接口地址（HTTP 404）")
        429 -> TranslationError(TranslationFailure.RATE_LIMITED, "翻译服务请求额度已用完，请稍后重试（HTTP 429）")
        in 300..399 -> TranslationError(TranslationFailure.SERVICE_ERROR, "翻译服务要求重定向，请填写最终接口地址（HTTP $status）")
        else -> TranslationError(TranslationFailure.SERVICE_ERROR, "翻译服务暂时不可用，请稍后重试（HTTP $status）")
    }

    private fun timeoutError() = TranslationError(TranslationFailure.TIMEOUT, "翻译服务超时，请稍后重试")
    private fun tooLarge() = TranslationError(TranslationFailure.BAD_RESPONSE, "翻译服务返回内容过大，请分段翻译")

    /** Serializes cancellation, connection publication and callback start without an unsynchronized connection variable. */
    private inner class HttpCall(private val callback: (TranslationResult) -> Unit) : TranslationCall {
        private val lock = Any()
        private var cancelled = false
        private var terminal = false
        private var delivered = false
        private var connection: HttpURLConnection? = null
        private var task: FutureTask<Unit>? = null
        private var deadline: ScheduledFuture<*>? = null

        fun checkActive() = synchronized(lock) {
            if (cancelled || terminal || Thread.currentThread().isInterrupted) throw InterruptedException()
        }

        fun bind(value: FutureTask<Unit>) = synchronized(lock) { task = value }

        fun enqueue(value: FutureTask<Unit>) = synchronized(lock) {
            if (!cancelled && !terminal) executor.execute(value)
        }

        fun bindDeadline(value: ScheduledFuture<*>) = synchronized(lock) {
            if (cancelled || terminal) value.cancel(false) else deadline = value
        }

        fun attach(value: HttpURLConnection) {
            synchronized(lock) {
                if (!cancelled && !terminal) { connection = value; return }
            }
            runCatching { value.disconnect() }
            throw InterruptedException()
        }

        fun release(value: HttpURLConnection?) {
            if (value == null) return
            val owns = synchronized(lock) {
                if (connection === value) { connection = null; true } else false
            }
            if (owns) runCatching { value.disconnect() }
        }

        fun finish(result: TranslationResult, interrupt: Boolean = false) {
            val toClose: HttpURLConnection?
            synchronized(lock) {
                if (cancelled || terminal) return
                terminal = true
                deadline?.cancel(false)
                deadline = null
                toClose = connection
                connection = null
                if (interrupt) stopTask()
            }
            runCatching { toClose?.disconnect() }
            callbackExecutor.execute {
                synchronized(lock) {
                    if (cancelled || delivered) return@execute
                    delivered = true
                    val currentResult = if (runCatching { accessAllowed() }.getOrDefault(false)) result else TranslationResult.Failure(
                        TranslationError(TranslationFailure.NOT_ENABLED, "在线翻译已关闭或配置已变更，请确认设置后重试"),
                    )
                    callback(currentResult)
                }
            }
        }

        override fun cancel() {
            val toClose: HttpURLConnection?
            synchronized(lock) {
                if (cancelled) return
                cancelled = true
                deadline?.cancel(false)
                deadline = null
                stopTask()
                toClose = connection
                connection = null
            }
            runCatching { toClose?.disconnect() }
        }

        private fun stopTask() {
            task?.let {
                it.cancel(true)
                (executor as? ThreadPoolExecutor)?.remove(it)
            }
        }
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 256 * 1_024
        const val MAX_RESULT_CHARS = 32_000
    }
}

/** Factory used by the keyboard. It is deliberately opt-in: absent settings never create a network client. */
object TranslationServices {
    /** Main integration entry. Creating a device service does not download models or allocate SDK clients. */
    fun from(
        ctx: android.content.Context,
        prefs: android.content.SharedPreferences,
    ): TranslationService = when (selectedProtocol(prefs)) {
        TranslationProtocol.GOOGLE_DEVICE -> GoogleTranslationService(ctx, prefs)
        else -> from(prefs)
    }

    /** Compatibility entry for custom HTTP services. Device translation needs an application Context. */
    fun from(prefs: android.content.SharedPreferences): TranslationService {
        val protocol = selectedProtocol(prefs) ?: return invalidConfiguration()
        if (protocol == TranslationProtocol.GOOGLE_WEB) return DisabledTranslationService(TranslationError(TranslationFailure.NOT_CONFIGURED, "请使用 Google Translate 官方网页入口"))
        if (protocol == TranslationProtocol.GOOGLE_DEVICE) {
            return DisabledTranslationService(TranslationError(
                TranslationFailure.NOT_CONFIGURED, "Google 设备端翻译需要应用上下文，请使用带 Context 的服务入口",
            ))
        }
        if (!TranslationSettings.onlineEnabled(prefs)) return DisabledTranslationService()
        if (TranslationSettings.endpoint(prefs).isBlank()) {
            return DisabledTranslationService(TranslationError(
                TranslationFailure.NOT_CONFIGURED, "尚未配置自定义翻译服务地址，请到设置中填写",
            ))
        }
        return runCatching {
            val config = TranslationSettings.httpConfig(prefs).also { it.endpointUrl() }
            HttpTranslationService(config, accessAllowed = {
                TranslationSettings.onlineEnabled(prefs) && TranslationSettings.matches(prefs, config)
            })
        }.getOrElse { invalidConfiguration() }
    }

    private fun selectedProtocol(prefs: android.content.SharedPreferences): TranslationProtocol? =
        runCatching { TranslationSettings.validatedProtocol(prefs) }.getOrNull()

    private fun invalidConfiguration() = DisabledTranslationService(TranslationError(
        TranslationFailure.NOT_CONFIGURED, "翻译服务配置无效，请检查协议、地址和 API key",
    ))
}
