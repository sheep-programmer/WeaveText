package com.weavetext.ime.voice

import android.content.Context
import com.weavetext.ime.models.AsrRuntime
import com.weavetext.ime.models.ModelManager
import com.weavetext.ime.models.VoicePack
import java.util.concurrent.CopyOnWriteArrayList

/** 首次点语音时自动准备默认的中英混合模型，不要求用户先打开语音包页面。 */
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

    /** Returns true when a download was started or is already running. */
    fun ensure(ctx: Context): Boolean {
        val repo = ModelManager.get(ctx)
        if (AsrRuntime.engineReady(repo)) { state = State.Ready; return false }
        if (state == State.Downloading) return true
        val pack = VoicePack(repo, bundledRuntime = true, modelIds = listOf("asr-stream-mixed-medium"))
        if (!pack.supported) {
            state = State.Failed("当前设备没有可用的中英混合模型")
            changed()
            return false
        }
        state = State.Downloading;changed()
        pack.start(allowMetered = !repo.wifiOnly) {
            runCatching {
                VoiceHub.engines(ctx).apply {
                    language = VoiceLanguage.MIXED
                    setSelection(listOf("asr-stream-mixed-medium"))
                }
                state = State.Ready
            }.onFailure { state = State.Failed("语音模型已下载，但加载失败，请重试") }
            changed()
        }
        return true
    }
}
