package com.weavetext.ime.voice

import android.content.Context
import com.weavetext.ime.models.AsrRuntime
import com.weavetext.ime.models.ModelManager
import com.weavetext.ime.models.VoicePack
import com.weavetext.ime.models.isReady
import java.util.concurrent.CopyOnWriteArrayList

/** 首次点语音时自动准备当前档位需要的离线模型（默认中英混合），不要求用户先打开语音包页面。 */
object VoiceAutoDownload {
    sealed interface State {
        data object Idle : State
        data object Downloading : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    /**
     * 每个档位的默认模型：混说／英文用 SenseVoice（整句识别，离线基准的中英混说错误率约 4%，双语实时模型约 22%），
     * 中文用中文实时模型。
     * Default per mode: SenseVoice for mixed/English (whole-utterance; ~4% error on mixed speech in the offline
     * benchmark versus ~22% for the bilingual streaming model), the Chinese streaming model for Chinese.
     */
    private val defaults = mapOf(
        VoiceLanguage.MIXED to listOf("asr-sensevoice"),
        VoiceLanguage.ENGLISH to listOf("asr-sensevoice"),
        VoiceLanguage.CHINESE to listOf("asr-stream-small"),
    )

    @Volatile var state: State = State.Idle
        private set
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
    private fun changed() = listeners.forEach { it() }

    /**
     * 保证当前语言档位有一组可用的已装模型；缺模型时开始下载。返回 true 表示已开始（或正在进行）下载。
     * Make sure the current language mode has a usable installed set, downloading when it does not. Returns true when
     * a download was started or is already running.
     */
    fun ensure(ctx: Context): Boolean {
        val repo = ModelManager.get(ctx)
        if (!AsrRuntime.ready(repo)) {
            // 运行库没有（轻量版）：下载运行库 + 当前档位默认模型。
            return download(ctx, repo, language(ctx))
        }
        if (usable(ctx, repo)) { state = State.Ready; upgradeInBackground(ctx, repo); return false }
        if (state == State.Downloading) return true
        return download(ctx, repo, language(ctx))
    }

    /**
     * 已经装了流式双语模型的老用户：在后台补装更准的 SenseVoice 并切换过去，只做一次，不打断当前使用，
     * 并遵守「仅 Wi-Fi 下载」设置。之后用户自己改回流式模型就不再替他换。
     * Existing users on a streaming bilingual model: fetch the more accurate SenseVoice in the background and switch to
     * it, once, without interrupting use and honouring the Wi-Fi-only setting. A later manual choice is left alone.
     */
    private fun upgradeInBackground(ctx: Context, repo: com.weavetext.ime.models.ModelRepository) {
        if (upgrading) return
        val prefs = ctx.getSharedPreferences("voice_auto", Context.MODE_PRIVATE)
        val engines = runCatching { VoiceHub.engines(ctx) }.getOrNull() ?: return
        val mode = engines.language
        if (mode == VoiceLanguage.CHINESE) return
        val upgradeKey = "${UPGRADED}_${mode.key}"
        if (prefs.getBoolean(upgradeKey, false)) return
        val selected = engines.selection().map { it.id }
        if (selected.isEmpty() || !selected.all { it in STREAMING_BILINGUAL }) return
        fun switchOver() {
            val now = engines.selection().map { it.id }
            // 下载期间用户自己换过模型就不动。 Leave it if the user picked something else meanwhile.
            if (now.isNotEmpty() && now.all { it in STREAMING_BILINGUAL } && engines.language == mode) {
                engines.setSelection(listOf(BEST))
            }
            if (engines.language == mode) prefs.edit().putBoolean(upgradeKey, true).apply()
            upgrading = false
            changed()
        }
        if (repo.state(BEST).isReady) { switchOver(); return }
        val pack = VoicePack(repo, bundledRuntime = true, modelIds = listOf(BEST))
        if (!pack.supported) return
        upgrading = true
        pack.start(allowMetered = !repo.wifiOnly, onStopped = { upgrading = false }) { runCatching { switchOver() }.onFailure { upgrading = false } }
    }

    @Volatile private var upgrading = false
    private const val BEST = "asr-sensevoice"
    private const val UPGRADED = "sensevoice_upgrade_done"
    private val STREAMING_BILINGUAL = setOf("asr-stream-mixed-medium", "asr-stream-mixed-high")

    /** 当前档位是否已经有一组可用模型；没有就用默认模型补上。 */
    private fun usable(ctx: Context, repo: com.weavetext.ime.models.ModelRepository): Boolean {
        val engines = runCatching { VoiceHub.engines(ctx) }.getOrNull() ?: return false
        if (engines.selection().isNotEmpty() && engines.selectionSatisfiesMode()) return true
        val ids = defaults[engines.language]!!.filter { repo.state(it).isReady }
        if (ids.isEmpty()) return false
        runCatching { engines.setSelection(ids) }
        return engines.selection().isNotEmpty()
    }

    private fun language(ctx: Context): VoiceLanguage =
        runCatching { VoiceHub.engines(ctx).language }.getOrDefault(VoiceLanguage.MIXED)

    private fun download(ctx: Context, repo: com.weavetext.ime.models.ModelRepository, mode: VoiceLanguage): Boolean {
        if (state == State.Downloading) return true
        val ids = defaults[mode] ?: defaults.getValue(VoiceLanguage.MIXED)
        val pack = VoicePack(repo, bundledRuntime = true, modelIds = ids)
        if (!pack.supported) {
            state = State.Failed("当前设备不支持「${mode.label}」离线语音，请到语音包页换一档")
            changed()
            return false
        }
        state = State.Downloading; changed()
        pack.start(allowMetered = !repo.wifiOnly, onStopped = { message ->
            state = State.Failed(message ?: "语音模型下载已取消，点话筒重试"); changed()
        }) {
            runCatching {
                val engines = VoiceHub.engines(ctx)
                // 下载期间用户换了档位：不把档位拨回去，改为给新档位补模型。
                // The user switched modes meanwhile: keep their choice and fetch for the new mode instead.
                if (engines.language != mode) { state = State.Idle; ensure(ctx) }
                else { engines.setSelection(ids); state = State.Ready }
            }.onFailure { state = State.Failed("语音模型已下载，但加载失败，请重试") }
            changed()
        }
        return true
    }
}
