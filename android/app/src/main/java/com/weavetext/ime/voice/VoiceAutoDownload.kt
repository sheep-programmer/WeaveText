package com.weavetext.ime.voice

import android.content.Context
import android.content.SharedPreferences
import com.weavetext.ime.models.AsrRuntime
import com.weavetext.ime.models.ModelManager
import com.weavetext.ime.models.ModelRepository
import com.weavetext.ime.models.ModelState
import com.weavetext.ime.models.VoicePack
import com.weavetext.ime.models.isReady
import com.weavetext.ime.ui.VoiceAccess
import com.weavetext.ime.voice.local.LocalAsrChoice
import java.util.concurrent.CopyOnWriteArrayList

/** 首次点语音时自动准备当前档位需要的离线模型（默认中英混合），不要求用户先打开语音包页面。 */
object VoiceAutoDownload {
    sealed interface State {
        data object Idle : State
        data object Downloading : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    @Volatile var state: State = State.Idle
        private set
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
    private fun changed() = listeners.forEach { it() }

    private var preparation: VoiceModelPreparation? = null
    private var repository: ModelRepository? = null
    private var engines: VoiceEngines? = null
    /** UI 回归测试隔离真实模型下载；正常使用保持 null。 */
    @androidx.annotation.VisibleForTesting internal var ensureOverride: ((Context) -> Boolean)? = null

    /**
     * 保证当前语言档位有一组可用的已装模型；缺模型时开始下载。返回 true 表示已开始（或正在进行）下载。
     * Make sure the current language mode has a usable installed set, downloading when it does not. Returns true when
     * a download was started or is already running.
     */
    fun ensure(ctx: Context): Boolean {
        ensureOverride?.let { return it(ctx) }
        val repo = ModelManager.get(ctx)
        val selected = VoiceAccess.engines(ctx)
        if (preparation == null || repository !== repo || engines !== selected) {
            repository = repo; engines = selected
            preparation = VoiceModelPreparation(repo, selected,
                ctx.getSharedPreferences("voice_auto", Context.MODE_PRIVATE),
                ctx.getSharedPreferences(LocalAsrChoice.PREFS, Context.MODE_PRIVATE),
                ctx.getSharedPreferences("weave_plugin_selection", Context.MODE_PRIVATE),
            ) { next -> state = next; changed() }
        }
        return preparation!!.ensure()
    }
}

/** 下载策略与 UI/录音分离，仓库、选择和偏好可在回归测试中独立驱动。 */
internal class VoiceModelPreparation(
    private val repo: ModelRepository,
    private val engines: VoiceEngines,
    private val prefs: SharedPreferences,
    private val modelPrefs: SharedPreferences,
    private val pluginPrefs: SharedPreferences,
    private val onChanged: (VoiceAutoDownload.State) -> Unit = {},
) {
    var state: VoiceAutoDownload.State = VoiceAutoDownload.State.Idle
        private set
    private var downloading = false
    private var upgrading = false

    private fun publish(next: VoiceAutoDownload.State) { state = next; onChanged(next) }

    fun ensure(): Boolean {
        // 可用的模型始终可继续录音，后台补装不占用前台 Downloading/Failed 状态。
        if (AsrRuntime.ready(repo) && usable()) {
            publish(VoiceAutoDownload.State.Ready)
            if (!downloading) upgradeInBackground()
            return false
        }
        if (downloading) return true
        return download(engines.language)
    }

    /**
     * 旧默认实时模型用户补装终稿，只迁移一次。中文只下载终稿，保留原 selection，由 finalCompanion
     * 为单模型会话提供两遍终稿；双语沿用 SenseVoice 升级。手动改选优先，失败不影响已有模型。
     */
    private fun upgradeInBackground() {
        if (upgrading) return
        val mode = engines.language
        val chinese = mode == VoiceLanguage.CHINESE
        val target = if (chinese) CHINESE_FINAL else BEST
        val upgradeKey = if (chinese) CHINESE_UPGRADED else "${UPGRADED}_${mode.key}"
        if (prefs.getBoolean(upgradeKey, false)) return
        val selected = engines.selection().map { it.id }
        val legacy = if (chinese) selected == listOf(CHINESE_STREAM) &&
            modelPrefs.getString(LocalAsrChoice.KEY_STREAM, CHINESE_STREAM) == CHINESE_STREAM &&
            modelPrefs.getString(LocalAsrChoice.KEY_FINAL, null) in listOf(null, CHINESE_FINAL)
        else selected.isNotEmpty() && selected.all { it in STREAMING_BILINGUAL }
        if (!legacy) return
        val pack = VoicePack(repo, modelIds = listOf(target))
        if (!pack.supported) return
        // 计流量网络上不排队、不标记完成；下次在 Wi-Fi 下打开面板再补装。
        if (pack.state() != VoicePack.State.Ready && repo.wifiOnly && repo.isMetered()) return
        upgrading = true
        val choice = watchChoice()
        val upgraded = if (chinese) selected + target else listOf(target)
        fun finish(ready: Boolean) {
            choice.close()
            try {
                if (ready && chinese) {
                    // 补装不等于改选；即使用户在下载时换过模型，也只记录补装完成。
                    prefs.edit().putBoolean(upgradeKey, true).apply()
                } else if (ready && choice.unchanged(mode, selected)) {
                    engines.setSelection(upgraded)
                    prefs.edit().putBoolean(upgradeKey, true).apply()
                } else if (ready && choice.preferencesUnchanged(mode) && engines.selection().map { it.id } == upgraded) {
                    // 没保存过选择时，安装完成可能已使默认选择自然变成目标模型；无需再写 selection。
                    prefs.edit().putBoolean(upgradeKey, true).apply()
                } else if (choice.changed) {
                    // 明确改选之后不在下次 ensure 又替用户改回去，包括改选后再切回实时模型。
                    prefs.edit().putBoolean(upgradeKey, true).apply()
                }
            } finally { upgrading = false; onChanged(state) }
        }
        startPack(pack, onStopped = { finish(false) }, onReady = { finish(true) })
    }

    /** 已安装的默认模型可以恢复缺失选择，但不为已有有效选择换模型。 */
    private fun usable(): Boolean {
        if (engines.selection().isNotEmpty() && engines.selectionSatisfiesMode()) return true
        val ids = defaults[engines.language]!!.filter { repo.state(it).isReady }
        if (ids.isEmpty()) return false
        engines.setSelection(if (engines.language == VoiceLanguage.CHINESE) ids.take(1) else ids)
        return engines.selection().isNotEmpty() && engines.selectionSatisfiesMode()
    }

    private fun download(mode: VoiceLanguage): Boolean {
        val ids = defaults[mode] ?: defaults.getValue(VoiceLanguage.MIXED)
        val pack = VoicePack(repo, modelIds = ids)
        if (!pack.supported) {
            publish(VoiceAutoDownload.State.Failed("当前设备不支持「${mode.label}」离线语音，请下载运行库或导入语音插件"))
            return false
        }
        val selected = engines.selection().map { it.id }
        val choice = watchChoice()
        downloading = true
        publish(VoiceAutoDownload.State.Downloading)
        startPack(pack, onStopped = { message ->
            choice.close(); downloading = false
            if (AsrRuntime.ready(repo) && usable()) publish(VoiceAutoDownload.State.Ready)
            else publish(VoiceAutoDownload.State.Failed(message ?: "语音模型下载已取消，点话筒重试或导入插件"))
        }) {
            choice.close(); downloading = false
            if (choice.unchanged(mode, selected)) {
                engines.setSelection(if (mode == VoiceLanguage.CHINESE) listOf(CHINESE_STREAM) else ids)
            }
            if (engines.language != mode) { publish(VoiceAutoDownload.State.Idle); ensure() }
            else publish(if (usable()) VoiceAutoDownload.State.Ready else VoiceAutoDownload.State.Idle)
        }
        return true
    }

    /** 先排完缺失项再观察结论，避免第一项的同步失败/状态通知提前结束整包观察。 */
    private fun startPack(pack: VoicePack, onStopped: (String?) -> Unit, onReady: () -> Unit) {
        var queued = false
        var finished = false
        val watcher = object : () -> Unit {
            override fun invoke() {
                if (!queued || finished) return
                when (val next = pack.state()) {
                    VoicePack.State.Ready -> { finished = true; repo.removeListener(this); onReady() }
                    is VoicePack.State.Failed -> { finished = true; repo.removeListener(this); onStopped(next.message) }
                    is VoicePack.State.Idle -> { finished = true; repo.removeListener(this); onStopped(null) }
                    else -> {}
                }
            }
        }
        repo.addListener(watcher)
        try {
            for (part in pack.parts) {
                val current = repo.state(part.id)
                if (current == ModelState.NotInstalled || current is ModelState.Failed) repo.download(part.id, !repo.wifiOnly)
            }
            queued = true
            watcher()
        } catch (error: Exception) {
            if (!finished) { finished = true; repo.removeListener(watcher); onStopped("语音模型下载失败，请重试") }
        }
    }

    /** 除完成时比较选择，还监听改选后又切回原选择的情况。 */
    private fun watchChoice() = ChoiceWatch()
    private inner class ChoiceWatch {
        var changed = false
            private set
        private val original = choiceValues()
        private val listener = SharedPreferences.OnSharedPreferenceChangeListener { preferences, key ->
            if (key == null || (preferences === modelPrefs && key in MODEL_CHOICE_KEYS) ||
                (preferences === pluginPrefs && key in PLUGIN_CHOICE_KEYS)) changed = true
        }
        init { modelPrefs.registerOnSharedPreferenceChangeListener(listener); pluginPrefs.registerOnSharedPreferenceChangeListener(listener) }
        fun preferencesUnchanged(mode: VoiceLanguage) = !changed && original == choiceValues() && engines.language == mode
        fun unchanged(mode: VoiceLanguage, ids: List<String>) = preferencesUnchanged(mode) &&
            engines.selection().map { it.id } == ids
        fun close() { modelPrefs.unregisterOnSharedPreferenceChangeListener(listener); pluginPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun choiceValues(): List<Any?> = MODEL_CHOICE_KEYS.map { modelPrefs.all[it] } +
        PLUGIN_CHOICE_KEYS.map { pluginPrefs.all[it] }

    companion object {
        private const val BEST = "asr-sensevoice"
        private const val UPGRADED = "sensevoice_upgrade_done"
        private const val CHINESE_STREAM = "asr-stream-small"
        private const val CHINESE_FINAL = "asr-final-small"
        private const val CHINESE_UPGRADED = "chinese_final_upgrade_done"
        private val STREAMING_BILINGUAL = setOf("asr-stream-mixed-medium", "asr-stream-mixed-high")
        private val MODEL_CHOICE_KEYS = listOf("language", "selected_models", "selected_models_zh", "selected_models_en",
            LocalAsrChoice.KEY_STREAM, LocalAsrChoice.KEY_FINAL)
        private val PLUGIN_CHOICE_KEYS = listOf("active", "also")
        // 中文安装两个模型，只自动选择实时模型；finalCompanion 提供终稿。手工多选仍逐行确认。
        private val defaults = mapOf(
            VoiceLanguage.MIXED to listOf(BEST), VoiceLanguage.ENGLISH to listOf(BEST),
            VoiceLanguage.CHINESE to listOf(CHINESE_STREAM, CHINESE_FINAL),
        )
    }
}
