package com.weavetext.ime.ui

import android.content.Context
import com.weavetext.ime.voice.VoiceEngines
import com.weavetext.ime.voice.VoiceHub
import com.weavetext.ime.voice.VoiceRecognizer

/**
 * 界面访问语音能力的唯一入口，默认转发到 [VoiceHub]；截图测试替换为假实现（不触发原生库）。
 * The UI's single entry to voice, forwarding to [VoiceHub]; screenshot tests swap in fakes.
 */
object VoiceAccess {
    @Volatile var enginesProvider: (Context) -> VoiceEngines = { VoiceHub.engines(it) }
    @Volatile var recognizerProvider: (Context) -> VoiceRecognizer = { VoiceHub.recognizer(it) }

    fun engines(ctx: Context): VoiceEngines = enginesProvider(ctx)
    fun recognizer(ctx: Context): VoiceRecognizer = recognizerProvider(ctx)
}
