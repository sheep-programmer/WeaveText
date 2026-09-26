package com.weavetext.ime.voice

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.weavetext.ime.BuildConfig

/**
 * 没有可用语音引擎时给用户的出路：下载带离线识别的版本、打开系统语音设置、导入插件。
 * Ways out when no voice engine is available: get the build with offline recognition, open the system
 * voice settings, or import a plugin.
 */
object VoiceHelp {
    /** 发布页（离线语音版的下载地址）。 Release page hosting the offline-voice build. */
    const val RELEASES_URL = "https://github.com/sheep-programmer/WeaveText/releases/latest"

    /** 轻量版没有端侧识别，可以引导去下载离线语音版（测试可覆盖）。 Lite has no on-device engine; tests may override. */
    @Volatile var canOfferOfflineBuild: Boolean = !BuildConfig.LOCAL_ASR

    fun openOfflineBuild(ctx: Context) {
        start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)))
    }

    /** 打开系统「语音输入」设置；个别系统没有该页面时退到语言与输入法、再退到设置首页。 */
    fun openSystemVoiceSettings(ctx: Context) {
        for (action in listOf(Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_INPUT_METHOD_SETTINGS, Settings.ACTION_SETTINGS)) {
            if (start(ctx, Intent(action))) return
        }
    }

    private fun start(ctx: Context, intent: Intent): Boolean = try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
