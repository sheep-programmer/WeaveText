package com.weavetext.ime.translate

import android.content.Intent
import android.content.SharedPreferences
import com.weavetext.ime.settings.TranslationSettings

/**
 * 系统「文字选择」菜单里的翻译：读取宿主选中的文字，按已保存的语言构造请求，并把译文作为替换结果返回。
 * 只在用户点击翻译时才调用服务，与键盘里的翻译一样不在后台发送原文。
 *
 * Translate from the system text-selection menu: read the host's selected text, build the request from the saved
 * languages, and return the translation as the replacement. The service runs only when the user taps translate.
 */
object SelectionTranslation {
    data class Incoming(val text: String, val readOnly: Boolean)

    fun incoming(intent: Intent): Incoming = Incoming(
        text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty(),
        readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false),
    )

    fun request(prefs: SharedPreferences, text: String): TranslationRequest = TranslationRequest(
        sourceLanguage = TranslationSettings.sourceLanguage(prefs),
        targetLanguage = prefs.getString(TranslationSettings.TARGET_LANGUAGE, "en") ?: "en",
        text = text,
    )

    /** 宿主收到的结果会替换原先选中的文字。 The host replaces the selection with this text. */
    fun replacementResult(translation: String): Intent = Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, translation)
}
