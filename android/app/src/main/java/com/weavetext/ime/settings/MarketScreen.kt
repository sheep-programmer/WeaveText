package com.weavetext.ime.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.weavetext.ime.extensions.OfficialMarket
import com.weavetext.ime.extensions.MarketTransfer
import com.weavetext.ime.extensions.ExtensionItem
import com.weavetext.ime.extensions.ExtensionStore
import com.weavetext.ime.extensions.Extensions
import com.weavetext.ime.style.StyleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val MARKET_KINDS = listOf("all" to "全部", "feature" to "功能", "scheme" to "输入方案", "theme" to "主题", "layout" to "布局", "speech" to "语音引擎", "translation" to "翻译", "dict" to "词库")

@Composable
fun MarketScreen(initialKind: String = "all") {
    val deps = LocalDeps.current
    val nav = LocalNav.current
    val p by rememberLivePrefs(deps.prefs)
    val store = remember { ExtensionStore(deps.ctx, deps.prefs).also { it.migrateSelected() } }
    var kind by rememberSaveable(initialKind) { mutableStateOf(initialKind) }
    var query by rememberSaveable { mutableStateOf("") }
    var onlyInstalled by rememberSaveable { mutableStateOf(false) }
    var transfer by remember { mutableStateOf<MarketTransfer?>(null) }
    var transferGeneration by remember { mutableIntStateOf(0) }
    var cancelling by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(store) { onDispose { store.cancelTransfer() } }
    // Read the stamp in this scope so installation updates every card immediately.
    val stamp = p.getLong(Extensions.STAMP, 0)
    val items = remember(kind, query, onlyInstalled, stamp, p.getStringSet(Extensions.ENABLED, Extensions.defaults)) {
        store.items.filter { item ->
            (kind == "all" || kind == item.kind) &&
                (query.isBlank() || "${item.name} ${item.summary}".contains(query.trim(), ignoreCase = true)) &&
                (!onlyInstalled || if (item.builtin) Extensions.enabled(p, item.key) else store.installed(item))
        }
    }
    fun perform(action: () -> Unit) { runCatching(action).onFailure { error = it.message ?: "操作失败，请重试" } }

    val scope = rememberCoroutineScope()
    fun startTransfer(action: ((MarketTransfer) -> Unit) -> Unit) {
        if (transfer != null) return
        cancelling = false
        val generation = ++transferGeneration
        transfer = MarketTransfer("正在准备")
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { action { status -> scope.launch { if (generation == transferGeneration && transfer != null && !cancelling) transfer = status } } }
            }.onFailure { if (!cancelling) error = it.message ?: "下载失败，请重试" }
            transfer = null
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    deps.ctx.contentResolver.openInputStream(uri)?.use { store.importData(it) } ?: kotlin.error("无法读取文件")
                }
            }.onSuccess { item -> StyleRepository.get(deps.ctx).invalidate(); kind = item.kind }
                .onFailure { error = it.message ?: "导入失败，请重试" }
        }
    }
    SubPage("插件市场", actions = {
        TextButton({ startTransfer { progress -> store.refreshMarket(progress) } }, enabled = transfer == null) { Text("刷新") }
        TextButton({ importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }) { Text("导入") } }) {
        Surface(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("搭配你的输入方式", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("从配色到输入工具，按需添加，随时调整。", style = MaterialTheme.typography.bodyMedium)
            }
        }
        transfer?.let { status ->
            Surface(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(status.stage, Modifier.weight(1f))
                        TextButton({ cancelling = true; store.cancelTransfer(); transfer = status.copy(stage = "正在取消") }, enabled = !cancelling) { Text("取消") }
                    }
                    if (status.total > 0) {
                        LinearProgressIndicator(progress = { (status.received.toFloat() / status.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text("${formatSize(status.received)} / ${formatSize(status.total)}", style = MaterialTheme.typography.bodySmall)
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (status.received > 0) Text("已下载 ${formatSize(status.received)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        GroupCard { SettingRow("官方扩展仓库", "公开源码与更新目录", onClick = {
            deps.ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(OfficialMarket.REPOSITORY)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Chevron() } }
        OutlinedTextField(query, { query = it }, Modifier.padding(horizontal = 16.dp, vertical = 14.dp).fillMaxWidth(), placeholder = { Text("搜索功能、主题或布局") }, singleLine = true, shape = RoundedCornerShape(16.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MARKET_KINDS.forEach { (id, name) -> FilterChip(kind == id, { kind = id }, label = { Text(name) }) }
        }
        Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${items.size} 项", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            FilterChip(onlyInstalled, { onlyInstalled = !onlyInstalled }, label = { Text("已添加") })
        }
        if (kind == "all" || kind in listOf("speech", "translation", "dict")) {
            GroupCard {
                if ((kind == "all" || kind == "speech") && Extensions.feature(p, "voice")) {
                    SettingRow("语音引擎与插件仓库", "安装、导入并搭配识别引擎", onClick = { nav.push(Route.Voice) }) { Chevron() }
                    SettingRow("加入官方语音仓库", "公开插件源码与安装", onClick = {
                        deps.pluginRepositories().save(com.weavetext.ime.plugins.GitHubRepository.parse(OfficialMarket.REPOSITORY + "/tree/main/plugins"))
                        nav.push(Route.PluginRepositories)
                    }) { Chevron() }
                }
                if ((kind == "all" || kind == "translation") && Extensions.feature(p, "translate")) {
                    SettingRow("翻译服务与离线插件", "选择服务与语言", onClick = { nav.push(Route.Translation) }) { Chevron() }
                }
                if (kind == "all" || kind == "dict") SettingRow("专业词库", "医学、法律、IT 等词库，按需下载", onClick = { nav.push(Route.DictPacks) }) { Chevron() }
                if (kind == "speech" && !Extensions.feature(p, "voice")) SettingRow("先启用语音输入", onClick = { kind = "feature" }) { Chevron() }
                if (kind == "translation" && !Extensions.feature(p, "translate")) SettingRow("先启用翻译", onClick = { kind = "feature" }) { Chevron() }
            }
        }
        items.forEach { item ->
            val enabled = item.builtin && Extensions.enabled(p, item.key)
            val installed = store.installed(item)
            val selected = when (item.kind) {
                "theme" -> WeavePrefs.styleTheme(p) == item.id
                "layout" -> WeavePrefs.styleLayout(p) == item.id
                else -> false
            }
            Surface(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(MARKET_KINDS.first { it.first == item.kind }.second + if (item.base) " · 基础内置" else if (item.builtin) " · 功能模块" else " · 扩展", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        if (item.builtin) Switch(enabled, { store.setEnabled(item.key, it) }, Modifier.semantics { contentDescription = "启用${item.name}" })
                    }
                    if (item.kind == "theme") {
                        val json = remember(item, stamp) { store.themePreview(item) }
                        if (json != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (mode in listOf("light", "dark")) for (key in listOf("background", "accent", "key")) {
                                val raw = json.getJSONObject(mode).opt(key)
                                val hex = (raw as? String) ?: (raw as? org.json.JSONObject)?.optJSONArray("colors")?.optString(0) ?: "#808080"
                                Surface(Modifier.size(22.dp), shape = RoundedCornerShape(7.dp), color = androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(hex))) {}
                            }
                        }
                        }
                    }
                    Text(item.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        if (!item.base && !item.builtin && installed) TextButton({ perform { store.uninstall(item); StyleRepository.get(deps.ctx).invalidate() } }) { Text("卸载") }
                        when {
                            item.builtin && enabled -> extensionRoute(item)?.let { route -> TextButton({ nav.push(route) }) { Text("设置") } }
                            !item.builtin && !installed -> FilledTonalButton({ startTransfer { progress -> store.installWithProgress(item, progress); StyleRepository.get(deps.ctx).invalidate() } }, enabled = transfer == null) { Text("安装") }
                            !item.builtin && store.updateAvailable(item) -> FilledTonalButton({ startTransfer { progress -> store.installWithProgress(item, progress); StyleRepository.get(deps.ctx).invalidate() } }, enabled = transfer == null) { Text("更新") }
                            !item.builtin -> FilledTonalButton({
                                p.edit().apply {
                                    putString(if (item.kind == "theme") WeavePrefs.STYLE_THEME else WeavePrefs.STYLE_LAYOUT, item.id)
                                    if (item.kind == "layout") putString(WeavePrefs.STYLE_THEME, "auto")
                                }.apply()
                            }, enabled = !selected) { Text(if (selected) "使用中" else "使用") }
                            else -> {}
                        }
                    }
                }
            }
        }
        if (items.isEmpty() && kind !in listOf("speech", "translation", "dict")) Text("没有匹配的扩展", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
    }
    error?.let { message -> AlertDialog(onDismissRequest = { error = null }, title = { Text("未能完成") }, text = { Text(message) }, confirmButton = { TextButton({ error = null }) { Text("知道了") } }) }
}

private fun extensionRoute(item: ExtensionItem): Route? = when (item.key) {
    "feature:voice" -> Route.Voice
    "feature:translate" -> Route.Translation
    "feature:link" -> Route.Link
    "feature:cloudwords" -> Route.Dictionary
    "scheme:hand", "scheme:wubi86" -> Route.Schemes
    else -> null
}

/** Deep links remain useful when a feature has been turned off. */
@Composable
fun DisabledExtensionScreen(kind: String) {
    val nav = LocalNav.current
    SubPage("扩展已停用") {
        GroupCard { SettingRow("去插件市场启用", "启用后即可继续使用", onClick = { nav.push(Route.Market(kind)) }) {} }
    }
}
