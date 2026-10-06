package com.weavetext.ime.translate

import android.net.Uri

/** Opens the official consumer UI only after a user action; never scrapes an undocumented API. */
object TranslationWeb {
    const val MAX_CHARS = 5_000
    fun google(request: TranslationRequest): Uri {
        require(request.text.isNotBlank()) { "请先选择或粘贴要翻译的文字" }
        require(request.text.length <= MAX_CHARS) { "网页翻译请分段，每次最多 5000 个字符" }
        require(request.targetLanguage.isNotBlank() && request.targetLanguage != TranslationLanguages.AUTO) { "请选择目标语言" }
        fun language(code: String) = if (code == "zh") "zh-CN" else code
        return Uri.parse("https://translate.google.com/").buildUpon()
            .appendQueryParameter("sl", language(request.sourceLanguage))
            .appendQueryParameter("tl", language(request.targetLanguage))
            .appendQueryParameter("text", request.text)
            .appendQueryParameter("op", "translate").build()
    }
}
