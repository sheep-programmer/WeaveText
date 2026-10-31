package com.weavetext.ime.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 一条快捷短语：输入码、文字与权重（权重越大，同一码下越靠前）。 A quick phrase: code, text and weight. */
data class QuickPhrase(val code: String, val text: String, val weight: Int)

/**
 * 快捷短语的设置页封装：把增删改查与导入导出翻译成引擎命令（`features` 的 JSON 操作）。
 * 引擎的回复由它自己校验，这里只读回结果，不重复规则。
 *
 * Settings-side wrapper for quick phrases: each action becomes an engine command. The engine validates; this only
 * reads the reply back.
 */
class QuickPhrases(private val call: (String) -> String) {
    data class ImportResult(val added: Int, val skipped: Int)

    /** 全部短语，按输入码分组、权重从高到低。 Every phrase, as the engine lists them. */
    fun list(): List<QuickPhrase> {
        val items = reply(JSONObject().put("op", "snippets")).optJSONArray("items") ?: return emptyList()
        return items.objects().map { QuickPhrase(it.getString("code"), it.getString("text"), it.optInt("weight", DEFAULT_WEIGHT)) }
    }

    /** 成功返回 null，否则返回引擎给出的原因。 Null on success, otherwise the engine's reason. */
    fun add(code: String, text: String, weight: Int?): String? {
        val command = JSONObject().put("op", "addPhrase").put("code", code).put("text", text)
        weight?.let { command.put("weight", it) }
        return failureReason(command)
    }

    fun remove(code: String, text: String): Boolean =
        reply(JSONObject().put("op", "removePhrase").put("code", code).put("text", text)).optBoolean("ok")

    fun setWeight(code: String, text: String, weight: Int): String? =
        failureReason(JSONObject().put("op", "setPhraseWeight").put("code", code).put("text", text).put("weight", weight))

    fun import(text: String): ImportResult {
        val r = reply(JSONObject().put("op", "importPhrases").put("text", text))
        return ImportResult(r.optInt("added"), r.optInt("skipped"))
    }

    fun export(): String = reply(JSONObject().put("op", "exportPhrases")).optString("text")

    private fun failureReason(command: JSONObject): String? {
        val r = reply(command)
        return if (r.optBoolean("ok")) null else r.optString("error").ifEmpty { "无法保存，请检查输入码与内容" }
    }

    private fun reply(command: JSONObject): JSONObject =
        runCatching { JSONObject(call(command.toString())) }.getOrDefault(JSONObject())

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    companion object {
        private const val DEFAULT_WEIGHT = 10

        /** 设置页用的实例：经由与输入法共用的引擎后台获取。 Shared engine, fetched off the main thread. */
        fun of(ctx: Context): QuickPhrases = QuickPhrases { command ->
            EngineHolder.getBlocking(ctx)?.features(command) ?: "{}"
        }
    }
}
