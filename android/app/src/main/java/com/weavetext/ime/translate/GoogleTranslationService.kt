package com.weavetext.ime.translate

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.content.SharedPreferences
import com.weavetext.ime.settings.TranslationSettings
import com.weavetext.translation.contract.ITranslationCallback
import com.weavetext.translation.contract.ITranslationPlugin
import com.weavetext.translation.contract.TranslationPluginContract as Contract
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Lightweight facade. Google SDK and language data live exclusively in the companion APK. */
class GoogleTranslationService(ctx: Context, private val prefs: SharedPreferences) : TranslationProgressService {
    private val context = ctx.applicationContext
    override fun translate(request: TranslationRequest, onProgress: (TranslationProgress) -> Unit,
                           callback: (TranslationResult) -> Unit): TranslationCall {
        request.validationError()?.let { callback(TranslationResult.Failure(it)); return TranslationCall {} }
        val call = PluginCall(request, onProgress, callback)
        TranslationExecutors.shared.execute { call.start() }
        return call
    }

    private inner class PluginCall(private val request: TranslationRequest,
                                   private val onProgress: (TranslationProgress) -> Unit,
                                   private val callback: (TranslationResult) -> Unit) : TranslationCall {
        private val main = Handler(Looper.getMainLooper())
        private val finished = AtomicBoolean(false)
        private val cancelled = AtomicBoolean(false)
        private val id = UUID.randomUUID().toString()
        @Volatile private var remote: ITranslationPlugin? = null
        private var bound = false
        private val timeout = Runnable { fail("离线翻译插件响应超时，请重试或打开插件管理") }
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (finished.get()) { cleanup(); return }
                val plugin = ITranslationPlugin.Stub.asInterface(binder)
                remote = plugin
                TranslationExecutors.shared.execute {
                    try {
                        if (finished.get()) return@execute
                        require(plugin.protocolVersion() == Contract.VERSION)
                        if (TranslationSettings.validatedProtocol(prefs) != TranslationProtocol.GOOGLE_DEVICE) {
                            fail("翻译方式已改变，请重新发起翻译"); return@execute
                        }
                        plugin.request(id, JSONObject().put("op", "translate").put("source", request.sourceLanguage)
                            .put("target", request.targetLanguage).put("text", request.text).toString(), sink)
                        if (finished.get()) runCatching { plugin.cancel(id) }
                    } catch (_: Exception) { fail("无法连接离线翻译插件，请检查插件版本") }
                }
            }
            override fun onServiceDisconnected(name: ComponentName) { fail("离线翻译插件已断开，请重试") }
            override fun onBindingDied(name: ComponentName) { fail("离线翻译插件已更新或卸载，请重新打开") }
            override fun onNullBinding(name: ComponentName) { fail("离线翻译插件不可用") }
        }
        private val sink = object : ITranslationCallback.Stub() {
            override fun onEvent(requestId: String?, json: String?) {
                if (requestId != id || finished.get()) return
                if (json == null || json.length > Contract.MAX_JSON_CHARS) { fail("插件返回内容无效"); return }
                val event = runCatching { JSONObject(json) }.getOrNull() ?: run { fail("插件返回格式无效"); return }
                main.post {
                    if (finished.get()) return@post
                    if (runCatching { TranslationSettings.validatedProtocol(prefs) }.getOrNull() != TranslationProtocol.GOOGLE_DEVICE) {
                        fail("翻译方式已改变，请重新发起翻译"); return@post
                    }
                    when (event.optString("type")) {
                        "progress" -> onProgress(TranslationProgress(when (event.optString("stage")) {
                            "identifying" -> TranslationStage.IDENTIFYING_LANGUAGE
                            "preparing" -> TranslationStage.PREPARING_MODEL
                            else -> TranslationStage.TRANSLATING
                        }, event.optString("message").take(200)))
                        "result" -> {
                            val text = event.optString("text")
                            if (text.isBlank() || text.length > Contract.MAX_TEXT_CHARS) fail("插件未返回有效译文")
                            else complete(TranslationResult.Success(text))
                        }
                        "error" -> {
                            val message = if (event.optString("code") == "MODEL_MISSING")
                                "缺少离线语言包，请打开离线翻译插件下载所需语言"
                            else event.optString("message").take(200).ifBlank { "离线翻译插件暂时不可用" }
                            fail(message)
                        }
                    }
                }
            }
        }

        fun start() {
            if (finished.get()) return
            val status = OfflineTranslationPlugin.status(context)
            if (status.state != OfflineTranslationPlugin.State.READY) { fail(status.message); return }
            main.post {
                if (finished.get()) return@post
                try {
                    bound = context.bindService(Intent().setComponent(ComponentName(Contract.PLUGIN_PACKAGE, Contract.PLUGIN_SERVICE)),
                        connection, Context.BIND_AUTO_CREATE)
                    if (!bound) fail("无法启动离线翻译插件") else main.postDelayed(timeout, 60_000)
                } catch (_: Exception) { fail("离线翻译插件连接被拒绝，请安装配套插件") }
            }
        }
        private fun fail(message: String) = complete(TranslationResult.Failure(TranslationError(TranslationFailure.NOT_CONFIGURED, message)))
        private fun complete(result: TranslationResult) {
            if (!finished.compareAndSet(false, true)) return
            if (result is TranslationResult.Failure) remote?.let { plugin ->
                runCatching { TranslationExecutors.shared.execute { runCatching { plugin.cancel(id) } } }
            }
            main.post { cleanup(); if (!cancelled.get()) callback(result) }
        }
        override fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return
            finished.set(true)
            val plugin = remote
            if (plugin != null) TranslationExecutors.shared.execute { runCatching { plugin.cancel(id) } }
            main.post { cleanup() }
        }
        private fun cleanup() {
            main.removeCallbacks(timeout)
            if (bound) { bound = false; runCatching { context.unbindService(connection) } }
            remote = null
        }
    }
}
