package com.weavetext.ime.settings

import android.content.SharedPreferences
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.weavetext.ime.translate.HttpTranslationConfig
import com.weavetext.ime.translate.TranslationProtocol
import com.weavetext.ime.translate.TranslationLanguages
import java.net.URI

/** Settings shared by the keyboard, the settings app, and the provider factory. */
object TranslationSettings {
    const val ONLINE_ENABLED = "translation_online_enabled"
    const val ENDPOINT = "translation_endpoint"
    const val TIMEOUT_MS = "translation_timeout_ms"
    const val PROTOCOL = "translation_protocol"
    const val API_KEY = "translation_api_key"
    const val SOURCE_LANGUAGE = "translation_source_language"
    const val TARGET_LANGUAGE = "translation_target_language"
    const val DEFAULT_TIMEOUT_MS = 8_000
    const val MIN_TIMEOUT_MS = 500
    const val MAX_TIMEOUT_MS = 30_000

    const val MODEL_METERED_ALLOWED = "translation_model_metered_allowed"
    const val GOOGLE_DEVICE_MIGRATION = "translation_google_device_migrated_v1"
    const val GOOGLE_WEB_MIGRATION = "translation_google_web_migrated_v1"
    private val migrationLock = Any()

    /** 默认从内置 SDK 迁到官方网页，只做一次；显式自定义服务与保存的配置保留。 */
    fun migrateLegacyProvider(prefs: SharedPreferences) = synchronized(migrationLock) {
        if (prefs.getBoolean(GOOGLE_WEB_MIGRATION, false)) return@synchronized
        val stored = prefs.getString(PROTOCOL, null)
        val endpoint = prefs.getString(ENDPOINT, "").orEmpty().trim()
        val retiredPreset = stored == "mymemory" && TranslationProtocol.entries.none { it.id == stored } ||
            stored == null && endpoint.isBlank() && prefs.getBoolean(ONLINE_ENABLED, false)
        prefs.edit().apply {
            putBoolean(GOOGLE_WEB_MIGRATION, true)
            putBoolean(GOOGLE_DEVICE_MIGRATION, true)
            if (stored == TranslationProtocol.GOOGLE_DEVICE.id || retiredPreset) {
                putString(PROTOCOL, TranslationProtocol.GOOGLE_WEB.id)
                putBoolean(ONLINE_ENABLED, false)
                // Keep any previously saved custom configuration, but never reuse the retired preset URL/key.
                val oldPreset = runCatching {
                    val host = URI(endpoint).host.orEmpty().lowercase(java.util.Locale.ROOT)
                    host == "mymemory.translated.net" || host.endsWith(".mymemory.translated.net")
                }.getOrDefault(false)
                if (retiredPreset && (endpoint.isBlank() || oldPreset)) { remove(ENDPOINT); remove(API_KEY) }
            }
        }.apply()
    }

    /** 自定义在线服务的单独开关，不控制 Google 官方网页或离线插件。 */
    fun onlineEnabled(prefs: SharedPreferences): Boolean {
        migrateLegacyProvider(prefs)
        return prefs.getBoolean(ONLINE_ENABLED, false)
    }
    fun endpoint(prefs: SharedPreferences): String {
        migrateLegacyProvider(prefs)
        return prefs.getString(ENDPOINT, "").orEmpty().trim()
    }
    fun timeoutMs(prefs: SharedPreferences): Int = prefs.getInt(TIMEOUT_MS, DEFAULT_TIMEOUT_MS)
        .coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

    fun protocol(prefs: SharedPreferences): TranslationProtocol {
        migrateLegacyProvider(prefs)
        val stored = prefs.getString(PROTOCOL, null)
        return TranslationProtocol.entries.firstOrNull { it.id == stored }
            ?: if (stored == null && endpoint(prefs).isNotBlank()) TranslationProtocol.GENERIC
            else TranslationProtocol.GOOGLE_WEB
    }

    /** Do not silently interpret an unknown provider as Google; factory callers use this checked getter. */
    fun validatedProtocol(prefs: SharedPreferences): TranslationProtocol {
        migrateLegacyProvider(prefs)
        val stored = prefs.getString(PROTOCOL, null)
        require(stored == null || TranslationProtocol.entries.any { it.id == stored }) { "翻译协议无效" }
        return protocol(prefs)
    }

    fun sourceLanguage(prefs: SharedPreferences): String {
        migrateLegacyProvider(prefs)
        return prefs.getString(SOURCE_LANGUAGE, TranslationLanguages.AUTO) ?: TranslationLanguages.AUTO
    }

    fun allowMobileModelDownload(prefs: SharedPreferences): Boolean = prefs.getBoolean(MODEL_METERED_ALLOWED, false)

    fun setProtocol(prefs: SharedPreferences, protocol: TranslationProtocol) {
        val changed = runCatching { validatedProtocol(prefs) != protocol }.getOrDefault(true)
        prefs.edit().apply {
            putString(PROTOCOL, protocol.id)
            if (changed) putBoolean(ONLINE_ENABLED, false)
        }.apply()
    }

    /** 安装状态由 IPC 入口提供；选择开关不代表安装或下载语言包。 */
    fun setOfflinePluginEnabled(prefs: SharedPreferences, enabled: Boolean, ready: Boolean): Boolean {
        if (enabled && !ready) return false
        if (enabled) setProtocol(prefs, TranslationProtocol.GOOGLE_DEVICE)
        else if (protocol(prefs) == TranslationProtocol.GOOGLE_DEVICE) setProtocol(prefs, TranslationProtocol.GOOGLE_WEB)
        return true
    }

    fun usesCustomHttp(protocol: TranslationProtocol) = protocol != TranslationProtocol.GOOGLE_WEB &&
        protocol != TranslationProtocol.GOOGLE_DEVICE

    fun apiKey(prefs: SharedPreferences): String {
        migrateLegacyProvider(prefs)
        return prefs.getString(API_KEY, "").orEmpty()
    }

    fun httpConfig(prefs: SharedPreferences): HttpTranslationConfig {
        val selected = validatedProtocol(prefs)
        require(usesCustomHttp(selected)) { "Google 官方网页和离线插件不使用自定义 HTTP 配置" }
        return HttpTranslationConfig(endpoint(prefs), timeoutMs(prefs), protocol = selected, apiKey = apiKey(prefs))
    }

    fun matches(prefs: SharedPreferences, config: HttpTranslationConfig): Boolean = runCatching {
        val current = httpConfig(prefs)
        current.endpoint == config.endpoint && current.boundedTimeoutMs == config.boundedTimeoutMs &&
            current.protocol == config.protocol && current.apiKey == config.apiKey
    }.getOrDefault(false)

    /** Changing a custom destination/protocol/key revokes the previous network opt-in. */
    fun saveConfiguration(
        prefs: SharedPreferences, endpoint: String, protocol: TranslationProtocol, apiKey: String, timeoutMs: Int,
    ): Boolean {
        migrateLegacyProvider(prefs)
        if (!usesCustomHttp(protocol)) return false
        val config = HttpTranslationConfig(endpoint.trim(), timeoutMs, protocol = protocol, apiKey = apiKey.trim())
        if (runCatching { config.endpointUrl() }.isFailure) return false
        val changed = !matches(prefs, config)
        prefs.edit().apply {
            putString(ENDPOINT, config.endpoint)
            putString(PROTOCOL, protocol.id)
            if (config.apiKey.isEmpty()) remove(API_KEY) else putString(API_KEY, config.apiKey)
            putInt(TIMEOUT_MS, config.boundedTimeoutMs)
            if (changed) putBoolean(ONLINE_ENABLED, false)
        }.apply()
        return true
    }

    fun setOnlineEnabled(prefs: SharedPreferences, enabled: Boolean): Boolean {
        migrateLegacyProvider(prefs)
        if (enabled && runCatching { httpConfig(prefs).endpointUrl() }.isFailure) return false
        prefs.edit().putBoolean(ONLINE_ENABLED, enabled).apply()
        return true
    }

    fun isValidEndpoint(value: String): Boolean = runCatching { HttpTranslationConfig(value).endpointUrl() }.isSuccess
}

/** 官方网页默认无模型下载；离线能力属于独立插件，自定义服务单独保存并启用。 */
@Composable
fun TranslationSettingsScreen() {
    TranslationSettingsContent(LocalDeps.current.prefs)
}

@Composable
internal fun TranslationSettingsContent(
    prefs: SharedPreferences,
    pluginActions: OfflineTranslationPluginActions = AndroidOfflineTranslationPluginActions,
) {
    val ctx = LocalContext.current
    val p by rememberLivePrefs(prefs)
    var endpoint by rememberSaveable { mutableStateOf(TranslationSettings.endpoint(p)) }
    var timeoutText by rememberSaveable { mutableStateOf(TranslationSettings.timeoutMs(p).toString()) }
    var apiKey by remember { mutableStateOf(TranslationSettings.apiKey(p)) }
    var message by remember { mutableStateOf("") }
    val selected = TranslationSettings.protocol(p)
    var advanced by rememberSaveable { mutableStateOf(TranslationSettings.usesCustomHttp(selected)) }
    LaunchedEffect(selected) { if (TranslationSettings.usesCustomHttp(selected)) advanced = true }
    val endpointValid = TranslationSettings.isValidEndpoint(endpoint)
    val timeout = timeoutText.toIntOrNull()
    val timeoutValid = timeout != null && timeout in TranslationSettings.MIN_TIMEOUT_MS..TranslationSettings.MAX_TIMEOUT_MS

    SubPage("翻译") {
        GroupTitle("翻译方式")
        GroupCard {
            val chooseWeb = { TranslationSettings.setProtocol(p, TranslationProtocol.GOOGLE_WEB); message = "" }
            SettingRow("Google 官方网页（默认）", "使用时需要联网，无需下载模型或语言包。", subtitleMaxLines = 2, onClick = chooseWeb) {
                RadioButton(selected == TranslationProtocol.GOOGLE_WEB, onClick = chooseWeb,
                    modifier = Modifier.testTag("translation_protocol_google_web"))
            }
            Text("无需 API key 或自建服务。只有点击翻译时才使用网页服务；原文会发送给 Google。",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = {
                message = if (runCatching {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://translate.google.com/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess) "" else "无法打开浏览器，请检查可用浏览器。"
            }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("打开 Google 官方网页") }
        }
        OfflineTranslationPluginCard(prefs, pluginActions)

        GroupTitle("高级：自定义服务")
        GroupCard {
            TextButton(onClick = { advanced = !advanced }, modifier = Modifier.testTag("translation_advanced_toggle")) {
                Text(if (advanced) "收起自定义服务设置" else "展开自定义服务设置")
            }
            if (advanced) {
                TranslationProtocol.entries.filter(TranslationSettings::usesCustomHttp).forEach { option ->
                    val choose = { TranslationSettings.setProtocol(p, option); message = "" }
                    SettingRow(option.label, onClick = choose) {
                        RadioButton(selected == option, onClick = choose,
                            modifier = Modifier.testTag("translation_protocol_${option.id}"))
                    }
                }
                if (TranslationSettings.usesCustomHttp(selected)) {
                    SwitchRow("启用自定义在线翻译", "默认关闭；保存配置并开启后，点击翻译才会发送源文到你填写的服务。",
                        TranslationSettings.onlineEnabled(p), subtitleMaxLines = 3) {
                        val draft = HttpTranslationConfig(endpoint.trim(), timeout ?: TranslationSettings.DEFAULT_TIMEOUT_MS,
                            protocol = selected, apiKey = apiKey.trim())
                        message = if (it && (!timeoutValid || !TranslationSettings.matches(p, draft))) {
                            "请先保存当前服务配置，再开启自定义在线翻译"
                        } else if (TranslationSettings.setOnlineEnabled(p, it)) "" else "请先保存有效的服务配置"
                    }
                    OutlinedTextField(endpoint, { endpoint = it.take(2_000) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("translation_custom_endpoint"),
                        singleLine = true, isError = endpoint.isNotBlank() && !endpointValid,
                        label = { Text("完整翻译接口地址") }, placeholder = { Text("https://你的服务地址/translate") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        supportingText = { Text("填写自己的服务完整地址；不设置公共默认服务器，不跟随重定向。") })
                    Text("建议 HTTPS；HTTP 不加密原文和 API key。", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                    if (selected == TranslationProtocol.LIBRE_TRANSLATE) {
                        OutlinedTextField(apiKey, { apiKey = it.take(HttpTranslationConfig.MAX_API_KEY_CHARS) },
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("translation_custom_api_key"), singleLine = true,
                            label = { Text("API key（可选）") }, visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            supportingText = { Text("只放请求体；自定义服务无需密钥时可留空。") })
                    } else if (selected == TranslationProtocol.GENERIC) {
                        Text("Generic 是自定义兼容协议；不会发送 LibreTranslate API key。", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedTextField(timeoutText, { timeoutText = it.filter(Char::isDigit).take(5) },
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true, isError = !timeoutValid,
                        label = { Text("自定义服务超时（毫秒）") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = { Text("500–30000 毫秒；包含排队、连接、发送和读取。") })
                    Button(onClick = {
                        message = if (TranslationSettings.saveConfiguration(p, endpoint, selected, apiKey, timeout ?: TranslationSettings.DEFAULT_TIMEOUT_MS)) {
                            "配置已保存，请确认服务后开启自定义在线翻译"
                        } else "配置无效，请检查地址、协议或 API key"
                    }, enabled = endpointValid && timeoutValid, modifier = Modifier.padding(16.dp)) { Text("保存自定义配置") }
                } else Text("自定义在线服务默认关闭。需要使用时，先选择协议、保存配置，再单独启用。",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (message.isNotEmpty()) Text(message, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        GroupTitle("隐私和确认")
        GroupCard {
            Text("密码框和 NO_PERSONALIZED_LEARNING 输入框禁用翻译。源文仅来自已选文字或手动粘贴，不上传拼音组合串。译文须确认后写回，也可复制；目标失效时仍可复制。",
                modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
