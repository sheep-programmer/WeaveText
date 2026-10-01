package com.weavetext.ime.voice

enum class VoiceLanguage(val key: String, val label: String) {
    MIXED("auto", "中英混合"), CHINESE("zh", "中文"), ENGLISH("en", "English");
    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: MIXED
    }
}
