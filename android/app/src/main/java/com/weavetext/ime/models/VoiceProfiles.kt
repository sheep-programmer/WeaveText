package com.weavetext.ime.models

data class VoiceProfile(val name: String, val description: String, val modelIds: List<String>)

object VoiceProfiles {
    val ALL = listOf(
        VoiceProfile("轻量中文", "中文边说边出字；中英混说请选择标准或增强档。", listOf("asr-stream-small")),
        VoiceProfile("中英标准", "双语 Zipformer 实时出字，支持中文、英语及混说。", listOf("asr-stream-mixed-medium")),
        VoiceProfile("中英增强", "增强双语实时模型与 SenseVoice 分别识别，停止后两行择优。", listOf("asr-stream-mixed-high", "asr-sensevoice")),
    )
}
