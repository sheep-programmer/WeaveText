package com.weavetext.ime.voice.local

import android.content.Context
import com.weavetext.ime.models.ModelKind
import com.weavetext.ime.models.ModelRepository
import com.weavetext.ime.models.ModelSpec
import com.weavetext.ime.models.isReady

/**
 * 本地识别用哪个实时模型、哪个终稿模型（语音包页面与识别引擎共用）。新装好的识别模型直接用上：下载它就是为了用它。
 * Which streaming and final models on-device recognition uses, shared by the voice-pack page and the engine. A newly
 * installed model is adopted right away — it was downloaded to be used.
 */
internal class LocalAsrChoice(ctx: Context, private val models: ModelRepository) {
    private val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun streamingModels(): List<ModelSpec> = available(ModelKind.ASR_STREAMING)
    fun offlineModels(): List<ModelSpec> = available(ModelKind.ASR_OFFLINE)
    private fun available(kind: ModelKind) = models.catalog.models.filter { it.kind == kind && models.state(it.id).isReady }

    /** 当前实时模型；没有可用的返回 null。 The streaming model in use, or null. */
    fun streamId(): String? {
        sync()
        val saved = prefs.getString(KEY_STREAM, null)
        if (saved == NONE && offlineModels().isNotEmpty()) return null
        val list = streamingModels()
        return list.firstOrNull { it.id == saved }?.id ?: list.firstOrNull()?.id
    }

    /** 当前终稿模型；选了「不使用」或没有可用的返回 null。 The final model in use; null for none. */
    fun finalId(): String? {
        sync()
        val saved = prefs.getString(KEY_FINAL, null)
        // Never leave an offline-only setup without its sole recognizer.
        if (saved == NONE && streamId() != null) return null
        val list = offlineModels()
        return list.firstOrNull { it.id == saved }?.id ?: list.firstOrNull()?.id
    }

    fun setStream(id: String?) = prefs.edit().putString(KEY_STREAM, id ?: NONE).apply()

    /** null 表示不使用终稿模型。 null turns the final pass off. */
    fun setFinal(id: String?) = prefs.edit().putString(KEY_FINAL, id ?: NONE).apply()

    /** 是否正在使用这个模型。 Whether this model is in use. */
    fun inUse(m: ModelSpec): Boolean = when (m.kind) {
        ModelKind.ASR_STREAMING -> streamId() == m.id
        ModelKind.ASR_OFFLINE -> finalId() == m.id
        else -> false
    }

    /** 设为使用中。 Put this model to use. */
    fun use(m: ModelSpec) {
        when (m.kind) {
            ModelKind.ASR_STREAMING -> setStream(m.id)
            ModelKind.ASR_OFFLINE -> setFinal(m.id)
            else -> {}
        }
    }

    /**
     * 与上次看到的已装模型比较，新出现的识别模型设为使用中。第一次运行只记下现状。
     * Compare with the installed models seen last time and adopt new ones. The first run only records the state.
     */
    @Synchronized
    fun sync() {
        val now = (streamingModels() + offlineModels())
        val ids = now.map { it.id }.toSet()
        val known = prefs.getStringSet(KEY_KNOWN, null)
        if (known == ids) return
        val e = prefs.edit().putStringSet(KEY_KNOWN, ids)
        if (known != null) {
            for (m in now) if (m.id !in known) when (m.kind) {
                ModelKind.ASR_STREAMING -> e.putString(KEY_STREAM, m.id)
                ModelKind.ASR_OFFLINE -> e.putString(KEY_FINAL, m.id)
                else -> {}
            }
        }
        e.apply()
    }

    companion object {
        const val PREFS = "weave_local_asr"
        const val KEY_STREAM = "stream_model"
        const val KEY_FINAL = "final_model"
        private const val KEY_KNOWN = "known_models"
        private const val NONE = "none"
    }
}
