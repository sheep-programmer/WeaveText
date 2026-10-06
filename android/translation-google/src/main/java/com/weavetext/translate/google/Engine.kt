package com.weavetext.translate.google

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Self-contained provider engine: it imports no IME classes and downloads only for an explicit download op. */
internal class Engine(
    private val backend: PluginBackend = MlKitBackend(),
    private val worker: ExecutorService = ThreadPoolExecutor(2, 2, 30L, TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(16), { task -> Thread(task, "translation-plugin").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) },
) : AutoCloseable {
    private val lock = Any()
    private val calls = mutableSetOf<Operation>()
    private var closed = false

    fun request(json: String, listener: (PluginEvent) -> Unit): PluginCall {
        val call = Operation(listener)
        synchronized(lock) {
            if (closed) { listener(PluginEvent.error("PLUGIN_STOPPED", "翻译插件已停止，请重新连接")); return PluginCall {} }
            if (calls.size >= 16) { listener(PluginEvent.error("BUSY", "翻译插件请求较多，请稍后重试")); return PluginCall {} }
            calls += call
        }
        try { call.enqueue(FutureTask<Unit>({ call.start(json) }, Unit)) }
        catch (_: RejectedExecutionException) { call.finish(PluginEvent.error("BUSY", "翻译插件请求较多，请稍后重试")) }
        return call
    }

    override fun close() {
        val pending = synchronized(lock) { closed = true; calls.toList() }
        pending.forEach { it.cancel() }
        worker.shutdownNow()
    }

    private enum class Phase { NEW, IDENTIFYING, RESOLVED, CHECKING, READY, TRANSLATING, MANAGING, FINISHED, CANCELLED }

    private inner class Operation(private val listener: (PluginEvent) -> Unit) : PluginCall {
        private val callLock = Any()
        private var phase = Phase.NEW
        private var identifier: PluginIdentifier? = null
        private var translator: PluginTranslator? = null
        private var task: FutureTask<Unit>? = null

        fun enqueue(value: FutureTask<Unit>) = synchronized(callLock) {
            task = value
            if (phase == Phase.NEW) worker.execute(value)
        }
        private fun active(expected: Phase) = synchronized(callLock) { phase == expected }
        private fun move(expected: Phase, next: Phase) = synchronized(callLock) {
            if (phase != expected) false else { phase = next; true }
        }
        private fun code(tag: String): String = backend.languageCode(tag)
            ?: throw PluginFailure("UNSUPPORTED_LANGUAGE", "插件不支持此语言，请重新选择")

        fun start(json: String) {
            if (!active(Phase.NEW)) return
            try {
                when (val request = PluginProtocol.parse(json)) {
                    is PluginRequest.Translate -> {
                        val target = code(request.target)
                        if (request.source == "auto") identify(request, target)
                        else checkModels(request, code(request.source), target, Phase.NEW)
                    }
                    PluginRequest.Languages -> listLanguages()
                    is PluginRequest.Download -> download(code(request.language), request.wifiOnly)
                    is PluginRequest.Delete -> delete(code(request.language))
                }
            } catch (failure: PluginFailure) { finish(PluginEvent.error(failure.code, failure.message)) }
            catch (_: Exception) { finish(PluginEvent.error("PLUGIN_ERROR", "设备端翻译插件无法处理请求，请重试")) }
        }

        private fun identify(request: PluginRequest.Translate, target: String) {
            if (!move(Phase.NEW, Phase.IDENTIFYING)) return
            progress("IDENTIFYING_LANGUAGE", "正在设备上识别源语言…")
            val client = try { backend.identifier() } catch (_: Exception) {
                finish(PluginEvent.error("IDENTIFICATION_FAILED", "无法启动本地语言识别，请手动选择源语言")); return
            }
            val attached = synchronized(callLock) {
                if (phase == Phase.IDENTIFYING) { identifier = client; true } else false
            }
            if (!attached) { runCatching { client.close() }; return }
            runTask(Phase.IDENTIFYING, "IDENTIFICATION_FAILED", "无法识别源语言，请手动选择", { client.identify(request.text) }) { result ->
                if (!move(Phase.IDENTIFYING, Phase.RESOLVED)) return@runTask
                val old = synchronized(callLock) { identifier.also { identifier = null } }
                runCatching { old?.close() }
                val tag = result.getOrNull()
                if (result.isFailure) finish(PluginEvent.error("IDENTIFICATION_FAILED", "无法识别源语言，请手动选择"))
                else if (tag.isNullOrBlank() || tag == "und") finish(PluginEvent.error("LANGUAGE_UNDETERMINED", "无法确定源语言，请手动选择"))
                else checkModels(request, code(tag), target, Phase.RESOLVED)
            }
        }

        private fun checkModels(request: PluginRequest.Translate, source: String, target: String, expected: Phase) {
            if (!active(expected)) return
            val detected = if (request.source == "auto") source else null
            if (source == target) { finish(PluginEvent.result(request.text, detected)); return }
            if (!move(expected, Phase.CHECKING)) return
            progress("CHECKING_MODELS", "正在检查已安装语言包；翻译不会自动下载…")
            runTask(Phase.CHECKING, "MODEL_QUERY_FAILED", "无法读取已安装语言包，请打开插件管理页", { backend.installedLanguages() }) { result ->
                if (!move(Phase.CHECKING, Phase.READY)) return@runTask
                val installed = result.getOrNull()
                if (installed == null) { finish(PluginEvent.error("MODEL_QUERY_FAILED", "无法读取已安装语言包，请打开插件管理页")); return@runTask }
                val missing = setOf(source, target).filter { it != "en" && it !in installed }
                if (missing.isNotEmpty()) {
                    finish(PluginEvent.error("MODEL_MISSING", "缺少语言包：${missing.joinToString { languageLabel(it) + " ($it)" }}。请打开 Google 翻译插件的语言包管理页安装；翻译不会自动下载。"))
                } else translate(request.text, source, target, detected)
            }
        }

        private fun translate(text: String, source: String, target: String, detected: String?) {
            if (!move(Phase.READY, Phase.TRANSLATING)) return
            progress("TRANSLATING", "正在设备上翻译…")
            val client = try { backend.translator(source, target) } catch (_: Exception) {
                finish(PluginEvent.error("TRANSLATION_FAILED", "无法启动设备端翻译，请检查语言包")); return
            }
            val attached = synchronized(callLock) {
                if (phase == Phase.TRANSLATING) { translator = client; true } else false
            }
            if (!attached) { runCatching { client.close() }; return }
            runTask(Phase.TRANSLATING, "TRANSLATION_FAILED", "设备端翻译失败，请检查语言包", { client.translate(text) }) { result ->
                val output = result.getOrNull()
                finish(when {
                    result.isFailure -> PluginEvent.error("TRANSLATION_FAILED", "设备端翻译失败，请检查语言包或重新安装")
                    output.isNullOrBlank() -> PluginEvent.error("INVALID_RESULT", "设备端翻译未返回有效译文")
                    output.length > PluginProtocol.MAX_INPUT_CHARS -> PluginEvent.error("RESULT_TOO_LARGE", "译文过长，请分段翻译")
                    else -> PluginEvent.result(output, detected)
                })
            }
        }

        private fun listLanguages() {
            if (!move(Phase.NEW, Phase.MANAGING)) return
            runTask(Phase.MANAGING, "MODEL_QUERY_FAILED", "无法读取已安装语言包", { backend.installedLanguages() }) { result ->
                val installed = result.getOrNull()
                if (installed == null) finish(PluginEvent.error("MODEL_QUERY_FAILED", "无法读取已安装语言包"))
                else finish(PluginEvent.languages(backend.supportedLanguages().distinct().sorted().map {
                    PluginLanguage(it, languageLabel(it), it == "en" || it in installed, it == "en")
                }))
            }
        }

        private fun download(language: String, wifiOnly: Boolean) {
            if (!move(Phase.NEW, Phase.MANAGING)) return
            if (language == "en") { finish(PluginEvent.downloaded(language)); return }
            progress("DOWNLOADING_MODEL", if (wifiOnly) "正在下载 ${languageLabel(language)} 语言包（仅 Wi-Fi）…" else "正在下载 ${languageLabel(language)} 语言包（允许移动网络）…")
            runTask(Phase.MANAGING, "DOWNLOAD_FAILED", "语言包下载失败，请检查网络及下载条件", { backend.download(language, wifiOnly) }) {
                finish(if (it.isSuccess) PluginEvent.downloaded(language) else PluginEvent.error("DOWNLOAD_FAILED", "语言包下载失败，请检查网络及下载条件"))
            }
        }

        private fun delete(language: String) {
            if (!move(Phase.NEW, Phase.MANAGING)) return
            if (language == "en") { finish(PluginEvent.error("BUILTIN_MODEL", "English 是 SDK 内置语言，不能删除")); return }
            progress("DELETING_MODEL", "正在删除 ${languageLabel(language)} 语言包…")
            runTask(Phase.MANAGING, "DELETE_FAILED", "语言包删除失败，请重试", { backend.delete(language) }) {
                finish(if (it.isSuccess) PluginEvent.deleted(language) else PluginEvent.error("DELETE_FAILED", "语言包删除失败，请重试"))
            }
        }

        private fun <T> runTask(expected: Phase, code: String, message: String,
                                operation: () -> PluginTask<T>, complete: (Result<T>) -> Unit) {
            try {
                val sdkTask = synchronized(callLock) { if (phase != expected) return; operation() }
                sdkTask.onComplete { result ->
                    if (active(expected)) {
                        try { complete(result) }
                        catch (failure: PluginFailure) { finish(PluginEvent.error(failure.code, failure.message)) }
                        catch (_: Exception) { finish(PluginEvent.error(code, message)) }
                    }
                }
            } catch (_: Exception) { finish(PluginEvent.error(code, message)) }
        }

        private fun progress(stage: String, message: String) = synchronized(callLock) {
            if (phase != Phase.CANCELLED && phase != Phase.FINISHED) listener(PluginEvent.progress(stage, message))
        }

        fun finish(event: PluginEvent) {
            val resources: Pair<PluginIdentifier?, PluginTranslator?>
            synchronized(callLock) {
                if (phase == Phase.CANCELLED || phase == Phase.FINISHED) return
                phase = Phase.FINISHED
                resources = takeResources()
            }
            closeResources(resources)
            synchronized(lock) { calls.remove(this) }
            synchronized(callLock) { if (phase != Phase.CANCELLED) listener(event) }
        }

        override fun cancel() {
            val resources: Pair<PluginIdentifier?, PluginTranslator?>
            synchronized(callLock) {
                if (phase == Phase.CANCELLED) return
                phase = Phase.CANCELLED
                task?.let { it.cancel(true); (worker as? ThreadPoolExecutor)?.remove(it) }
                resources = takeResources()
            }
            closeResources(resources)
            synchronized(lock) { calls.remove(this) }
        }

        private fun takeResources() = (identifier to translator).also { identifier = null; translator = null }
        private fun closeResources(resources: Pair<PluginIdentifier?, PluginTranslator?>) {
            runCatching { resources.first?.close() }; runCatching { resources.second?.close() }
        }
    }
}
