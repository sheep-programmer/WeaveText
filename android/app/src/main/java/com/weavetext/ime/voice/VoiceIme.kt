package com.weavetext.ime.voice

import android.content.Context
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype

/**
 * 手机上其他带「语音」子类型的输入法（如各类语音输入应用）：织文不必下载任何东西，一点就切过去说话。
 * Other IMEs on the phone that offer a voice subtype: a zero-download way to dictate — tap to switch.
 */
object VoiceIme {
    data class Option(
        val id: String,
        val label: String,
        val subtype: InputMethodSubtype?,
        /** 已在系统里启用（未启用的只能引导去设置里打开）。 Enabled in system settings. */
        val enabled: Boolean,
    )

    private const val VOICE_MODE = "voice"

    /** 查找函数（测试可替换）。 Finder, replaceable in tests. */
    @Volatile var finder: (Context) -> List<Option> = ::scan

    fun find(ctx: Context): List<Option> = finder(ctx)

    private fun scan(ctx: Context): List<Option> = runCatching {
        val imm = ctx.getSystemService(InputMethodManager::class.java) ?: return emptyList()
        val enabledIds = imm.enabledInputMethodList.map { it.id }.toSet()
        imm.inputMethodList
            .filter { it.packageName != ctx.packageName }
            .mapNotNull { imi -> option(ctx, imm, imi, imi.id in enabledIds) }
            .sortedByDescending { it.enabled }
    }.getOrDefault(emptyList())

    private fun option(ctx: Context, imm: InputMethodManager, imi: InputMethodInfo, enabled: Boolean): Option? {
        val voice = (0 until imi.subtypeCount).map { imi.getSubtypeAt(it) }.filter { it.mode == VOICE_MODE }
        if (voice.isEmpty()) return null
        val chosen = if (enabled) {
            imm.getEnabledInputMethodSubtypeList(imi, true).firstOrNull { it.mode == VOICE_MODE } ?: voice.first()
        } else {
            voice.first()
        }
        val label = runCatching { imi.loadLabel(ctx.packageManager).toString() }.getOrDefault(imi.packageName)
        return Option(imi.id, label, chosen, enabled)
    }

    /** 按钮上用的短名。 Short label for buttons. */
    fun shortLabel(o: Option): String = if (o.label.length > 8) o.label.take(7) + "…" else o.label
}
