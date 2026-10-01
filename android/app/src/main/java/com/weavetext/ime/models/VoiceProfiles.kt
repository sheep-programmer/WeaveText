package com.weavetext.ime.models

import com.weavetext.ime.voice.VoiceLanguage

data class VoiceProfile(val name: String, val description: String, val modelIds: List<String>, val language: VoiceLanguage = VoiceLanguage.MIXED)

object VoiceProfiles {
    val ALL = listOf(
        VoiceProfile("轻量中文", "中文边说边出字；中英混说请选择标准或增强档。", listOf("asr-stream-small"), VoiceLanguage.CHINESE),
        VoiceProfile("中英标准", "双语 Zipformer 实时出字，支持中文、英语及混说。", listOf("asr-stream-mixed-medium")),
        VoiceProfile("中英增强", "增强双语实时模型与 SenseVoice 分别识别，停止后两行择优。", listOf("asr-stream-mixed-high", "asr-sensevoice")),
        VoiceProfile("英文补强", "Whisper 多语种整句识别，重点改善英文词句；可切换中英混合。", listOf("asr-whisper-small"), VoiceLanguage.ENGLISH),
    )
}
