package com.weavetext.ime.models

import org.json.JSONObject

/** 模型种类。 Model kind. */
enum class ModelKind(val key: String) {
    ASR_STREAMING("asr-streaming"),
    ASR_OFFLINE("asr-offline"),
    PUNCTUATION("punctuation"),
    /** 识别运行库（轻量版按需下载）。 Speech runtime libraries, downloaded on demand by the lite build. */
    ASR_RUNTIME("asr-runtime");

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

/** 模型里的一个文件；名字可带一层目录（如 `arm64-v8a/libfoo.so`）。 One file; the name may carry a directory. */
data class ModelFile(val name: String, val size: Long, val sha256: String)

/** 一个压缩包下载源。 One archive source. */
data class ModelArchive(val url: String, val sha256: String, val size: Long)

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
    /** 压缩包来源，按顺序尝试（前一个 404 或失败再试下一个）。 Archive sources, tried in order. */
    val archives: List<ModelArchive>,
    val files: List<ModelFile>,
    val installedSize: Long,
    val bench: Bench?,
    /** HuggingFace 仓库（可逐个文件下载，经 hf-mirror 加速）；没有则只能下载压缩包。
     *  HuggingFace repo for per-file downloads; null means archive only. */
    val hfRepo: String?,
    /** 只适用于该 ABI（运行库）；null 表示与架构无关。 Only for this ABI (runtime); null means any. */
    val abi: String? = null,
    /** 轻量版里显示的描述（内置模型在轻量版需要下载）。 Description shown in the lite build. */
    val liteDescription: String? = null,
) {
    fun fileNames() = files.map { it.name }

    /** 首选压缩包。 The preferred archive. */
    val archiveUrl get() = archives.first().url
    val archiveSha256 get() = archives.first().sha256
    /** 首选压缩包的大小（界面显示的下载量）。 Size of the preferred archive, as shown in the UI. */
    val archiveSize get() = archives.first().size
}

/** 模型目录（随 APK 分发的 assets/models/catalog.json）。 The catalog shipped in assets. */
data class ModelCatalog(val mirrors: List<Mirror>, val hfMirrors: List<Mirror>, val models: List<ModelSpec>) {
    fun find(id: String) = models.firstOrNull { it.id == id }

    /**
     * 按本次构建调整：运行库随包时去掉「识别运行库」条目；不随包（轻量版）时没有任何内置模型，
     * 目录里标为内置的模型改为可下载，并换用轻量版的描述；运行库只保留与 [abi] 相符的。
     * Adjust for this build: with a bundled runtime the runtime entry is dropped; without it (lite) nothing is
     * built in, so built-in models become downloads with their lite description, and runtimes must match [abi].
     */
    fun forBuild(bundledRuntime: Boolean, abi: String? = null): ModelCatalog = copy(
        models = models.mapNotNull { m ->
            when {
                m.kind == ModelKind.ASR_RUNTIME -> m.takeIf { !bundledRuntime && (m.abi == null || abi == null || m.abi == abi) }
                bundledRuntime -> m
                else -> m.copy(builtin = false, description = m.liteDescription ?: m.description)
            }
        },
    )

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
                    // 新格式 `archives: [...]`（按顺序回退），也兼容旧的单个 `archive`。 `archives` list or legacy `archive`.
                    val archives = (m.optJSONArray("archives")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
                        ?: listOf(m.getJSONObject("archive")))
                        .map { ModelArchive(it.getString("url"), it.getString("sha256"), it.optLong("size")) }
                    require(archives.isNotEmpty()) { "${m.optString("id")}: no archive" }
                    val b = m.optJSONObject("bench")
                    ModelSpec(
                        id = m.getString("id"),
                        kind = kind,
                        arch = m.getString("arch"),
                        name = m.getString("name"),
                        description = m.optString("description"),
                        license = m.optString("license"),
                        builtin = m.optBoolean("builtin"),
                        archives = archives,
                        files = m.getJSONArray("files").let { f ->
                            (0 until f.length()).map { i ->
                                val x = f.getJSONObject(i)
                                ModelFile(x.getString("name"), x.optLong("size"), x.getString("sha256"))
                            }
                        },
                        installedSize = m.optLong("installedSize"),
                        bench = b?.let { Bench(it.optDouble("cerClean"), it.optDouble("cerNoisy"), it.optDouble("rtf")) },
                        hfRepo = m.optString("hf").ifEmpty { null },
                        abi = m.optString("abi").ifEmpty { null },
                        liteDescription = m.optString("descriptionLite").ifEmpty { null },
                    )
                }
            }
            return ModelCatalog(mirrors, hfMirrors, models)
        }
    }
}
