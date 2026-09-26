package com.weavetext.ime.models

import org.json.JSONObject

/** 模型种类。 Model kind. */
enum class ModelKind(val key: String) {
    ASR_STREAMING("asr-streaming"),
    ASR_OFFLINE("asr-offline"),
    PUNCTUATION("punctuation");

    companion object {
        fun of(key: String) = entries.firstOrNull { it.key == key }
    }
}

/** 下载镜像：`template` 里的 `{url}` 会被替换为原始地址。 A mirror; `{url}` is replaced by the source URL. */
data class Mirror(val id: String, val name: String, val template: String) {
    fun apply(url: String): String = template.replace("{url}", url)
}

/** 公开评测数据（织文自测，TTS 合成语音，仅作相对比较）。 Our benchmark numbers (relative only). */
data class Bench(val cerClean: Double, val cerNoisy: Double, val rtf: Double)

/** 模型里的一个文件。 One file of a model. */
data class ModelFile(val name: String, val size: Long, val sha256: String)

/** 目录中的一个模型。 One catalog entry. */
data class ModelSpec(
    val id: String,
    val kind: ModelKind,
    /** 模型结构，决定用哪种识别器加载。 Architecture, selects the recognizer type. */
    val arch: String,
    val name: String,
    val description: String,
    val license: String,
    val builtin: Boolean,
    val archiveUrl: String,
    val archiveSha256: String,
    val archiveSize: Long,
    val files: List<ModelFile>,
    val installedSize: Long,
    val bench: Bench?,
    /** HuggingFace 仓库（可逐个文件下载，经 hf-mirror 加速）；没有则只能下载压缩包。
     *  HuggingFace repo for per-file downloads; null means archive only. */
    val hfRepo: String?,
) {
    fun fileNames() = files.map { it.name }
}

/** 模型目录（随 APK 分发的 assets/models/catalog.json）。 The catalog shipped in assets. */
data class ModelCatalog(val mirrors: List<Mirror>, val hfMirrors: List<Mirror>, val models: List<ModelSpec>) {
    fun find(id: String) = models.firstOrNull { it.id == id }

    companion object {
        fun parse(json: String): ModelCatalog {
            val o = JSONObject(json)
            fun mirrorList(key: String) = o.optJSONArray(key)?.let { a ->
                (0 until a.length()).map { i ->
                    val m = a.getJSONObject(i)
                    Mirror(m.getString("id"), m.getString("name"), m.getString("template"))
                }
            }.orEmpty()
            val mirrors = mirrorList("mirrors")
            val hfMirrors = mirrorList("hfMirrors")
            val models = o.getJSONArray("models").let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val m = a.getJSONObject(i)
                    val kind = ModelKind.of(m.getString("kind")) ?: return@mapNotNull null
                    val ar = m.getJSONObject("archive")
                    val b = m.optJSONObject("bench")
                    ModelSpec(
                        id = m.getString("id"),
                        kind = kind,
                        arch = m.getString("arch"),
                        name = m.getString("name"),
                        description = m.optString("description"),
                        license = m.optString("license"),
                        builtin = m.optBoolean("builtin"),
                        archiveUrl = ar.getString("url"),
                        archiveSha256 = ar.getString("sha256"),
                        archiveSize = ar.optLong("size"),
                        files = m.getJSONArray("files").let { f ->
                            (0 until f.length()).map { i ->
                                val x = f.getJSONObject(i)
                                ModelFile(x.getString("name"), x.optLong("size"), x.getString("sha256"))
                            }
                        },
                        installedSize = m.optLong("installedSize"),
                        bench = b?.let { Bench(it.optDouble("cerClean"), it.optDouble("cerNoisy"), it.optDouble("rtf")) },
                        hfRepo = m.optString("hf").ifEmpty { null },
                    )
                }
            }
            return ModelCatalog(mirrors, hfMirrors, models)
        }
    }
}
