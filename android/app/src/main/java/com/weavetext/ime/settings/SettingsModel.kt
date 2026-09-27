package com.weavetext.ime.settings

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import kotlin.reflect.KProperty
import com.weavetext.ime.core.UserDictionary
import com.weavetext.ime.models.ModelManager
import com.weavetext.ime.models.ModelRepository
import com.weavetext.ime.ui.VoiceAccess
import com.weavetext.ime.voice.VoiceEngines

/** 启用状态（03 §3）。 Onboarding status. */
data class ImeStatus(val enabled: Boolean, val isDefault: Boolean, val micGranted: Boolean)

/**
 * 设置 App 的外部依赖，截图测试可整体替换为假实现。
 * External dependencies of the settings app; replaced wholesale in screenshot tests.
 */
class SettingsDeps(
    val ctx: Context,
    val prefs: SharedPreferences = WeavePrefs.of(ctx),
    val engines: () -> VoiceEngines = { VoiceAccess.engines(ctx) },
    val models: () -> ModelRepository = { ModelManager.get(ctx) },
    val dictionary: UserDictionary = UserDictionary.of(ctx),
    val status: () -> ImeStatus = { detectStatus(ctx) },
    val link: () -> com.weavetext.ime.link.LinkController = { com.weavetext.ime.link.LinkManager.get(ctx) },
    val packs: () -> com.weavetext.ime.core.DictPackRepository = { com.weavetext.ime.core.DictPacks.get(ctx) },
    val cloud: () -> com.weavetext.ime.core.CloudWordsRepository = { com.weavetext.ime.core.CloudWords.get(ctx) },
    val versionName: String = com.weavetext.ime.BuildConfig.VERSION_NAME,
    val versionCode: Int = com.weavetext.ime.BuildConfig.VERSION_CODE,
) {
    companion object {
        fun detectStatus(ctx: Context): ImeStatus {
            val imm = ctx.getSystemService(InputMethodManager::class.java)
            val pkg = ctx.packageName
            val enabled = imm?.enabledInputMethodList?.any { it.packageName == pkg } == true
            val def = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
            val mic = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            return ImeStatus(enabled, def.startsWith("$pkg/"), mic)
        }
    }
}

/**
 * 「活」的设置：`val p by rememberLivePrefs(raw)`，每次读取 p 都登记一次状态依赖，
 * 因此任何读取设置的 lambda 都会在设置变化时重组。
 * Live preferences: every read of the delegated property registers a snapshot-state read, so any
 * composable lambda reading settings recomposes when they change.
 */
class LivePrefs(private val raw: SharedPreferences, private val tick: State<Int>) {
    operator fun getValue(thisRef: Any?, property: KProperty<*>): SharedPreferences {
        tick.value
        return raw
    }
}

@Composable
fun rememberLivePrefs(prefs: SharedPreferences): LivePrefs {
    val tick = remember(prefs) { mutableIntStateOf(0) }
    DisposableEffect(prefs) {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> tick.intValue++ }
        prefs.registerOnSharedPreferenceChangeListener(l)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(l) }
    }
    return remember(prefs) { LivePrefs(prefs, tick) }
}

/** 当前方案的一行描述（首页副文字）。 One-line scheme summary for the home page. */
fun schemeSummary(p: SharedPreferences): String {
    val kbs = WeavePrefs.keyboards(p)
    val parts = kbs.filter { it != "english" }.map {
        when (it) {
            "shuangpin" -> (WeavePrefs.SHUANGPIN_SCHEMES.firstOrNull { s -> s.first == WeavePrefs.shuangpinScheme(p) }?.second ?: "") + "双拼"
            else -> WeavePrefs.KEYBOARD_NAMES[it] ?: it
        }
    }
    return parts.ifEmpty { listOf("英文 26 键") }.joinToString(" · ")
}

fun themeName(t: String) = when (t) { "light" -> "浅色"; "dark" -> "深色"; else -> "跟随系统" }
val HEIGHT_NAMES = listOf("紧凑", "较矮", "适中", "较高", "高")
