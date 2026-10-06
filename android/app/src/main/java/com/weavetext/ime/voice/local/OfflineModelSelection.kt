package com.weavetext.ime.voice.local

import android.content.Context
import com.weavetext.ime.models.ModelKind
import com.weavetext.ime.models.ModelRepository
import com.weavetext.ime.models.ModelSpec
import com.weavetext.ime.models.isReady
import org.json.JSONArray
import com.weavetext.ime.voice.VoiceLanguage

/** The ordered, installed models used for one shared microphone capture. */
internal class OfflineModelSelection(ctx: Context, private val models: ModelRepository) {
    private val prefs = ctx.getSharedPreferences(LocalAsrChoice.PREFS, Context.MODE_PRIVATE)
    var mode: VoiceLanguage
        get() = VoiceLanguage.of(prefs.getString("language", null))
        set(value) { prefs.edit().putString("language", value.key).apply() }
    private val selectionKey get() = if (mode == VoiceLanguage.MIXED) KEY else "${KEY}_${mode.key}"

    fun available(): List<ModelSpec> = models.catalog.models.filter {
        (it.kind == ModelKind.ASR_STREAMING || it.kind == ModelKind.ASR_OFFLINE) && models.state(it.id).isReady &&
            when (mode) {
                // 双语实时模型没有语言约束，英文档位只能使用能真正设置 en 的模型。
                // Bilingual streaming models cannot constrain the language; English requires an actual en setting.
                VoiceLanguage.MIXED, VoiceLanguage.CHINESE -> true
                VoiceLanguage.ENGLISH -> supportsEnglishMode(it)
            }
    }

    /**
     * 当前档位下「首选」该用哪个模型：混说与英文档位必须落到认识英文的模型，否则会出现「装了双语模型却仍只出中文」。
     * The model the current mode should actually use. Mixed/English must land on an English-capable model, otherwise a
     * bilingual setup silently keeps recognising Chinese-only.
     */
    fun primaryOk(): Boolean {
        val ids = ids()
        return ids.isNotEmpty() && when (mode) {
            VoiceLanguage.MIXED -> ids.any { id -> models.catalog.find(id)?.let { supportsEnglish(it) } == true }
            VoiceLanguage.ENGLISH -> ids.any { id -> models.catalog.find(id)?.let { supportsEnglishMode(it) } == true }
            VoiceLanguage.CHINESE -> true
        }
    }

    fun ids(): List<String> {
        val available = available()
        val saved = prefs.getString(selectionKey, null)?.let { raw -> runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getString(it) }
        }.getOrDefault(emptyList()) }
        saved?.filter { id -> available.any { it.id == id } }?.distinct()?.take(MAX_MODELS)
            // 混说档位下，曾经单独选过「只会中文」的模型不该把整个档位锁死：装了双语模型就换成它。
            // In mixed mode a previously pinned Chinese-only model must not lock the whole mode: prefer bilingual.
            ?.let { list -> if (mode == VoiceLanguage.MIXED && list.none { id -> catalogModel(id)?.let { supportsEnglish(it) } == true } &&
                available.any { supportsEnglish(it) }) null else list }
            ?.takeIf { it.isNotEmpty() }?.let { list ->
                return if (mode == VoiceLanguage.MIXED) list.sortedBy { id -> !supportsEnglish(catalogModel(id)!!) } else list
            }
        val old = if (mode == VoiceLanguage.MIXED) prefs.getString(LocalAsrChoice.KEY_FINAL, null) else null
        val preferred = when (mode) {
            VoiceLanguage.MIXED -> listOf("asr-sensevoice", "asr-stream-mixed-high", "asr-stream-mixed-medium")
            VoiceLanguage.CHINESE -> listOf("asr-sensevoice", "asr-stream-small", "asr-final-small")
            VoiceLanguage.ENGLISH -> listOf("asr-whisper-small", "asr-whisper-base", "asr-sensevoice")
        }
        val legacy = available.firstOrNull { it.id == old && (mode != VoiceLanguage.MIXED || supportsMixed(it)) }
        val best = legacy ?: preferred.firstNotNullOfOrNull { id -> available.firstOrNull { it.id == id } }
            ?: available.firstOrNull { supportsEnglish(it) } ?: available.firstOrNull { supportsMixed(it) } ?: available.firstOrNull()
        return listOfNotNull(best?.id)
    }

    /** 单个双语实时模型用已装的 SenseVoice 补终稿，降低把英文猜成中文的概率。 */
    fun finalCompanion(id: String): String? {
        val spec = catalogModel(id) ?: return null
        val companion = when {
            mode == VoiceLanguage.MIXED && supportsMixed(spec) -> "asr-sensevoice"
            mode == VoiceLanguage.CHINESE && id == "asr-stream-small" -> "asr-final-small"
            else -> null
        }
        return companion?.takeIf { ids() == listOf(id) && spec.kind == ModelKind.ASR_STREAMING && models.state(it).isReady }
    }

    private fun catalogModel(id: String): ModelSpec? = models.catalog.find(id)

    fun select(ids: List<String>) {
        val installed = available().map { it.id }.toSet()
        val valid = ids.filter { it in installed }.distinct().take(MAX_MODELS)
        if (valid.isNotEmpty()) prefs.edit().putString(selectionKey, JSONArray(valid).toString()).apply()
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
    fun use(model: ModelSpec) {
        if (available().none { it.id == model.id }) mode = if (supportsMixed(model)) VoiceLanguage.MIXED else VoiceLanguage.CHINESE
        select(listOf(model.id))
    }

    companion object {
        const val MAX_MODELS = 3
        private const val KEY = "selected_models"
        /** 模型认识英文（含中文专用 CTC 之外的多语种／双语架构）。 Model knows English. */
        fun supportsEnglish(model: ModelSpec) = model.arch in setOf("sense-voice", "wenet-ctc", "zipformer-transducer", "whisper", "dolphin")
        fun supportsMixed(model: ModelSpec) = supportsEnglish(model)
        fun supportsEnglishMode(model: ModelSpec) = model.arch in setOf("sense-voice", "whisper")
        fun language(model: ModelSpec) = if (model.arch == "whisper") "多语种 · 中文/英文" else if (supportsMixed(model)) "中英混说" else "中文"
    }
}
