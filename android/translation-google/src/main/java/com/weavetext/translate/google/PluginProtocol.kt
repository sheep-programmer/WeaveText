package com.weavetext.translate.google

import org.json.JSONArray
import org.json.JSONObject
import com.weavetext.translation.contract.TranslationPluginContract

internal class PluginFailure(val code: String, override val message: String) : Exception(message)
internal fun interface PluginCall { fun cancel() }
internal fun interface PluginTask<T> { fun onComplete(callback: (Result<T>) -> Unit) }

internal sealed interface PluginRequest {
    class Translate(val source: String, val target: String, val text: String) : PluginRequest {
        override fun toString() = "Translate(source=$source,target=$target,characters=${text.length})"
    }
    data object Languages : PluginRequest
    data class Download(val language: String, val wifiOnly: Boolean) : PluginRequest
    data class Delete(val language: String) : PluginRequest
}

internal data class PluginLanguage(val code: String, val label: String, val installed: Boolean, val builtin: Boolean)

internal class PluginEvent private constructor(private val json: JSONObject, val terminal: Boolean) {
    fun encode(): String = json.toString()
    companion object {
        fun progress(stage: String, message: String) = PluginEvent(JSONObject().put("type", "progress")
            .put("stage", stage).put("message", message), false)
        fun result(text: String, detected: String?) = PluginEvent(JSONObject().put("type", "result")
            .put("text", text).put("detected_language", detected ?: JSONObject.NULL), true)
        fun languages(items: List<PluginLanguage>): PluginEvent {
            val array = JSONArray()
            items.forEach { array.put(JSONObject().put("code", it.code).put("label", it.label)
                .put("installed", it.installed).put("builtin", it.builtin)) }
            return PluginEvent(JSONObject().put("type", "languages").put("languages", array), true)
        }
        fun downloaded(language: String) = PluginEvent(JSONObject().put("type", "downloaded").put("language", language), true)
        fun deleted(language: String) = PluginEvent(JSONObject().put("type", "deleted").put("language", language), true)
        fun error(code: String, message: String) = PluginEvent(JSONObject().put("type", "error")
            .put("code", code).put("message", message), true)
    }
}

internal object PluginProtocol {
    const val VERSION = TranslationPluginContract.VERSION
    const val MAX_INPUT_CHARS = TranslationPluginContract.MAX_TEXT_CHARS
    const val MAX_JSON_BYTES = TranslationPluginContract.MAX_JSON_CHARS
    const val MAX_EVENT_BYTES = 256 * 1024
    const val MAX_REQUEST_ID_CHARS = 128

    fun validRequestId(id: String) = id.isNotBlank() && id.length <= MAX_REQUEST_ID_CHARS && id.none { it.isISOControl() }

    fun parse(json: String): PluginRequest {
        if (json.length > MAX_JSON_BYTES || json.toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES) {
            throw PluginFailure("REQUEST_TOO_LARGE", "请求过大，请分段翻译")
        }
        val root = try { JSONObject(json) } catch (_: Exception) {
            throw PluginFailure("INVALID_JSON", "请求不是有效的 JSON 对象")
        }
        fun string(key: String, default: String? = null): String = (root.opt(key) as? String ?: default)
            ?.takeIf { it.isNotBlank() } ?: throw PluginFailure("INVALID_REQUEST", "请求缺少有效字段：$key")
        return when (string("op")) {
            "translate" -> {
                val text = string("text")
                if (text.length > MAX_INPUT_CHARS) throw PluginFailure("INPUT_TOO_LARGE", "源文超过 32000 字符，请分段翻译")
                PluginRequest.Translate(string("source", "auto"), string("target"), text)
            }
            "languages" -> PluginRequest.Languages
            "download" -> {
                if (root.has("wifiOnly") && root.opt("wifiOnly") !is Boolean) {
                    throw PluginFailure("INVALID_REQUEST", "wifiOnly 必须为布尔值")
                }
                PluginRequest.Download(string("language"), root.optBoolean("wifiOnly", true))
            }
            "delete" -> PluginRequest.Delete(string("language"))
            else -> throw PluginFailure("UNSUPPORTED_OPERATION", "不支持此翻译插件操作")
        }
    }
}
