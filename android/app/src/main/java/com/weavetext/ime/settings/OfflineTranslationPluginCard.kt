package com.weavetext.ime.settings

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.weavetext.ime.translate.OfflineTranslationPlugin
import com.weavetext.ime.translate.TranslationProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal typealias OfflinePluginState = OfflineTranslationPlugin.State
internal typealias OfflinePluginStatus = OfflineTranslationPlugin.PluginStatus
private val OfflinePluginStatus.ready get() = state == OfflinePluginState.READY
private val OfflinePluginStatus.installed get() = state != OfflinePluginState.MISSING

/** 不持有 Activity 或其他 Context；每次操作由当前 Compose 页面传入上下文。 */
internal interface OfflineTranslationPluginActions {
    fun status(ctx: Context): OfflinePluginStatus
    fun openManager(ctx: Context): Boolean
    fun requestUninstall(ctx: Context): Boolean
    fun installFromUri(ctx: Context, uri: Uri): Result<Unit>
}

internal object AndroidOfflineTranslationPluginActions : OfflineTranslationPluginActions {
    override fun status(ctx: Context) = OfflineTranslationPlugin.status(ctx)
    override fun openManager(ctx: Context) = OfflineTranslationPlugin.openManager(ctx)
    override fun requestUninstall(ctx: Context) = OfflineTranslationPlugin.requestUninstall(ctx)
    override fun installFromUri(ctx: Context, uri: Uri) = OfflineTranslationPlugin.installFromUri(ctx, uri)
}

/** 安装/兼容性与选用协议分别展示；语言包下载、删除全部由插件自己的页面管理。 */
@Composable
internal fun OfflineTranslationPluginCard(
    prefs: SharedPreferences,
    actions: OfflineTranslationPluginActions = AndroidOfflineTranslationPluginActions,
) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val p by rememberLivePrefs(prefs)
    var status by remember(ctx, actions) { mutableStateOf(actions.status(ctx)) }
    var installing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val selected = TranslationSettings.protocol(p) == TranslationProtocol.GOOGLE_DEVICE

    DisposableEffect(owner, ctx, actions) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) status = actions.status(ctx)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(status.state, selected) {
        if (selected && !status.ready) {
            TranslationSettings.setOfflinePluginEnabled(prefs, enabled = false, ready = false)
            message = "离线插件不可用，已改用 Google 官方网页；请先安装兼容且可信的插件。"
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null && !installing) {
            installing = true
            message = "正在检查所选 APK…"
            scope.launch {
                try {
                    // 文件复制和内容校验交给 IPC 插件安装入口；权限授权、安装由系统页面处理。
                    val result = withContext(Dispatchers.IO) { actions.installFromUri(ctx.applicationContext, uri) }
                    message = result.fold(
                        onSuccess = { "请在系统页面授权并完成安装；返回后会刷新插件状态。" },
                        onFailure = { it.message ?: "无法安装所选 APK，请检查插件文件。" },
                    )
                    status = actions.status(ctx)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    message = error.message ?: "无法打开插件安装流程，请重试。"
                } finally { installing = false }
            }
        }
    }

    GroupTitle("离线翻译插件")
    GroupCard(Modifier.testTag("offline_translation_plugin_card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Google 离线翻译插件", style = MaterialTheme.typography.titleMedium)
            Text("独立 APK，使用 Google 翻译模型在设备上翻译，无需 API key 或自建服务器。安装插件后，在插件内准备、删除语言包；键盘不会自动下载语言包。",
                style = MaterialTheme.typography.bodyMedium)
            val label = when (status.state) {
                OfflinePluginState.MISSING -> "未安装 · 请先安装插件"
                OfflinePluginState.READY -> "已安装 · 兼容"
                OfflinePluginState.INCOMPATIBLE -> "已安装 · 不兼容，请更新插件"
                OfflinePluginState.UNTRUSTED -> "已安装 · 签名不可信，无法启用"
            }
            Text(label, Modifier.testTag("offline_translation_plugin_status"),
                style = MaterialTheme.typography.bodyMedium,
                color = if (status.ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text("版本：${status.versionName.ifBlank { if (status.installed) "未提供" else "未安装" }}",
                Modifier.testTag("offline_translation_plugin_version"), style = MaterialTheme.typography.bodySmall)
            if (status.message.isNotBlank()) Text(status.message, style = MaterialTheme.typography.bodySmall)
        }
        SettingRow("使用离线翻译插件",
            if (status.ready) "启用后使用插件；关闭后使用 Google 官方网页。" else "请先安装兼容且可信的插件，再启用离线翻译。",
            enabled = status.ready && !installing, subtitleMaxLines = 2,
            onClick = {
                status = actions.status(ctx)
                message = if (TranslationSettings.setOfflinePluginEnabled(prefs, !selected, status.ready)) ""
                    else "请先安装兼容且可信的插件。"
            },
        ) {
            Switch(selected, onCheckedChange = { enabled ->
                status = actions.status(ctx)
                message = if (TranslationSettings.setOfflinePluginEnabled(prefs, enabled, status.ready)) ""
                    else "请先安装兼容且可信的插件。"
            }, enabled = status.ready && !installing, modifier = Modifier.testTag("offline_translation_plugin_enabled"))
        }
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) },
                enabled = !installing, modifier = Modifier.testTag("offline_translation_plugin_install")) {
                Text(if (status.installed) "安装 / 更新 APK" else "安装 APK")
            }
            OutlinedButton(onClick = {
                message = if (actions.openManager(ctx)) "" else "无法打开插件语言包管理，请检查插件安装状态。"
            }, enabled = status.ready && !installing, modifier = Modifier.testTag("offline_translation_plugin_manage")) {
                Text("管理语言包")
            }
            OutlinedButton(onClick = {
                message = if (actions.requestUninstall(ctx)) "已打开系统卸载页面；返回后会刷新状态。" else "无法打开插件卸载页面。"
            }, enabled = status.installed && !installing, modifier = Modifier.testTag("offline_translation_plugin_uninstall")) {
                Text("卸载插件")
            }
        }
        Text("选择本地插件 APK 安装；系统可能要求允许安装未知来源应用。只有完成安装并通过兼容性与签名检查后才能启用。",
            Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (message.isNotEmpty()) Text(message, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("offline_translation_plugin_message"), style = MaterialTheme.typography.bodyMedium)
    }
}
