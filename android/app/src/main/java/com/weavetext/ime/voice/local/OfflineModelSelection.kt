package com.weavetext.ime.voice.local

import android.content.Context
import com.weavetext.ime.models.ModelKind
import com.weavetext.ime.models.ModelRepository
import com.weavetext.ime.models.ModelSpec
import com.weavetext.ime.models.isReady
import org.json.JSONArray

/** The ordered, installed models used for one shared microphone capture. */
internal class OfflineModelSelection(ctx: Context, private val models: ModelRepository) {
    private val prefs = ctx.getSharedPreferences(LocalAsrChoice.PREFS, Context.MODE_PRIVATE)

    fun available(): List<ModelSpec> = models.catalog.models.filter {
        (it.kind == ModelKind.ASR_STREAMING || it.kind == ModelKind.ASR_OFFLINE) && models.state(it.id).isReady
    }

    fun ids(): List<String> {
        val available = available()
        val saved = prefs.getString(KEY, null)?.let { raw -> runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getString(it) }
        }.getOrDefault(emptyList()) }
        saved?.filter { id -> available.any { it.id == id } }?.distinct()?.take(MAX_MODELS)?.takeIf { it.isNotEmpty() }?.let { return it }
        val old = prefs.getString(LocalAsrChoice.KEY_FINAL, null)
        val best = available.firstOrNull { it.id == old } ?: available.firstOrNull { supportsMixed(it) } ?: available.firstOrNull()
        return listOfNotNull(best?.id)
    }

    fun select(ids: List<String>) {
        val installed = available().map { it.id }.toSet()
        val valid = ids.filter { it in installed }.distinct().take(MAX_MODELS)
        if (valid.isNotEmpty()) prefs.edit().putString(KEY, JSONArray(valid).toString()).apply()
    }

    /** false when a fourth model was requested or the last model would be deselected. */
    fun toggle(id: String): Boolean {
        if (available().none { it.id == id }) return false
        val selected = ids()
        if (id in selected && selected.size == 1) return false
        if (id !in selected && selected.size >= MAX_MODELS) return false
        select(if (id in selected) selected - id else selected + id)
        return true
    }

    fun inUse(model: ModelSpec) = model.id in ids()
    fun use(model: ModelSpec) = select(listOf(model.id))

    companion object {
        const val MAX_MODELS = 3
        private const val KEY = "selected_models"
        fun supportsMixed(model: ModelSpec) = model.arch in setOf("sense-voice", "wenet-ctc", "zipformer-transducer")
        fun language(model: ModelSpec) = if (supportsMixed(model)) "中英混说" else "中文"
    }
}
