package com.weavetext.ime.settings

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.weavetext.ime.R
import com.weavetext.ime.voice.ConfigField
import com.weavetext.ime.voice.LOCAL_ENGINE_ID
import com.weavetext.ime.voice.isBuiltinEngine
import com.weavetext.ime.voice.VoiceEngines
import com.weavetext.ime.voice.VoicePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 插件图标：icon.png 或名称首字头像。 Plugin icon or letter avatar. */
@Composable
fun PluginAvatar(p: VoicePlugin, size: Int, radius: Int) {
    val bmp = remember(p.id, p.version) {
        p.iconPng?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
    }
    if (bmp != null) {
        Image(bmp, null, Modifier.size(size.dp).clip(RoundedCornerShape(radius.dp)))
    } else LetterAvatar(p.name, size, radius)
}

/** 当前值（未设置时取 defaultValue）。 Current value or default. */
private fun VoiceEngines.value(id: String, f: ConfigField) = getConfig(id, f.key) ?: f.defaultValue.orEmpty()

private fun isSecret(f: ConfigField) =
    f.type == "password" || listOf("key", "secret", "token", "password").any { f.key.lowercase().contains(it) }

/** 缺少的必填项。 Required fields still empty. */
fun missingRequired(e: VoiceEngines, p: VoicePlugin): List<ConfigField> =
    p.configSchema.filter { it.required && e.value(p.id, it).isBlank() }

// ------------------------------------------------------------------ list (03 §6.1)

private enum class PluginFilter(val label: String) { All("全部"), Enabled("已启用"), NeedsConfig("待配置") }

@Composable
fun VoiceListScreen(statusVersion: Int = 0) {
    val deps = LocalDeps.current
    val nav = LocalNav.current
    val engines = remember { deps.engines() }
    val models = remember { deps.models() }
    val modelTick = rememberModelTick(models)
    var pluginTick by remember { mutableIntStateOf(0) }
    val importer = rememberPluginImporter(engines) { pluginTick++ }
    var expandedPlugins by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(PluginFilter.All) }
    var language by remember { mutableStateOf(engines.language) }
    // Returning during the navigation animation can reuse the existing composition.
    LaunchedEffect(nav.current, statusVersion) {
        if (nav.current == Route.Voice) { language = engines.language; pluginTick++ }
    }
    val engine = remember(modelTick, language, pluginTick) { engines.active() }
    val allEngines = remember(modelTick, language, pluginTick) { engines.list() }
    val selection = remember(modelTick, language, pluginTick) { engines.selection() }
    val installedPlugins = allEngines.filterNot { isBuiltinEngine(it.id) }
    val missingById = installedPlugins.associate { it.id to missingRequired(engines, it) }
    val selectedIds = selection.map { it.id }.toSet()
    val search = query.trim()
    val visiblePlugins = installedPlugins.filter { p ->
        (search.isEmpty() || listOf(p.name, p.id, p.description).any { it.contains(search, ignoreCase = true) }) && when (filter) {
            PluginFilter.All -> true
            PluginFilter.Enabled -> p.id in selectedIds
            PluginFilter.NeedsConfig -> !p.configured || missingById.getValue(p.id).isNotEmpty()
        }
    }
    val prefs by rememberLivePrefs(deps.prefs)
    SubPage("语音引擎", actions = {
        if (installedPlugins.isNotEmpty()) IconButton(onClick = {
            showSearch = !showSearch
            if (!showSearch) { query = ""; filter = PluginFilter.All }
        }) {
            Icon(painterResource(if (showSearch) R.drawable.ic_close else R.drawable.ic_search),
                if (showSearch) "关闭插件筛选" else "搜索语音插件")
        }
    }) {
        GroupTitle("插件")
        GroupCard {
            SettingRow("插件仓库", "从自定义 GitHub 仓库导入，支持私有仓库", subtitleMaxLines = 2, onClick = { nav.push(Route.PluginRepositories) }) { Chevron() }
            RowDivider(false)
            SettingRow("导入本地插件", "选择插件压缩包，按包内内容识别，不限制后缀", onClick = importer.launch) { Chevron() }
        }
        GroupTitle("已安装 · ${installedPlugins.size} 个语音插件")
        if (showSearch && installedPlugins.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("voice_plugin_search"),
                    singleLine = true, label = { Text("搜索已安装插件") },
                    placeholder = { Text("名称、标识或说明") },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                    trailingIcon = if (query.isNotEmpty()) ({
                        IconButton(onClick = { query = "" }) { Icon(painterResource(R.drawable.ic_close), "清除插件搜索") }
                    }) else null,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PluginFilter.entries.forEach { mode ->
                        FilterChip(selected = filter == mode, onClick = { filter = mode }, label = { Text(mode.label) },
                            modifier = Modifier.testTag("voice_filter_${mode.name}"))
                    }
                }
                Text("显示 ${visiblePlugins.size} / ${installedPlugins.size} 个插件", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(2.dp))
            }
        }
        if (installedPlugins.isEmpty()) {
            GroupCard {
                SettingRow("还没有安装语音插件", "从上方插件仓库或本地文件导入", icon = R.drawable.ic_waveform, subtitleMaxLines = 2)
            }
        } else {
            if (visiblePlugins.isEmpty()) GroupCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("没有找到匹配的插件", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { query = ""; filter = PluginFilter.All }) { Text("清除筛选") }
                }
            }
            visiblePlugins.forEachIndexed { i, p ->
                key(p.id) {
                    if (i > 0) Spacer(Modifier.height(10.dp))
                    val missing = missingById.getValue(p.id)
                    VoicePluginCard(
                        p, selected = engine?.id == p.id,
                        combined = selection.any { it.id == p.id && it.id != engine?.id },
                        missing = missing, expanded = p.id in expandedPlugins,
                        onToggle = {
                            expandedPlugins = if (p.id in expandedPlugins) expandedPlugins - p.id else expandedPlugins + p.id
                        },
                        onSelect = {
                            if (p.configured && missingRequired(engines, p).isEmpty()) {
                                engines.activeId = p.id
                                pluginTick++
                            }
                        },
                        onDetail = { nav.push(Route.VoiceDetail(p.id)) },
                    )
                }
            }
            CombineCard(engines, allEngines, engine?.id, selection) { pluginTick++ }
        }
        OfflineModelsCard(models) { nav.push(Route.Models) }
        GroupTitle("识别语言")
        GroupCard {
            Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                com.weavetext.ime.voice.VoiceLanguage.entries.forEach { mode ->
                    androidx.compose.material3.FilterChip(selected = language == mode,
                        onClick = { engines.language = mode; language = mode }, label = { Text(mode.label) })
                }
            }
            Text("默认中英混合；中文、英文模式使用对应模型的语言参数。", Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), style = MaterialTheme.typography.bodySmall)
        }
        if (engine == null) {
            GroupCard(Modifier.padding(top = 12.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("下载后才能使用语音", style = MaterialTheme.typography.titleMedium)
                    Text("先下载一个离线识别模型。轻量版会同时安装运行库，之后识别无需联网。")
                    Button(onClick = { nav.push(Route.VoiceUpgrade) }) { Text("下载离线语音包") }
                }
            }
        } else {
            GroupTitle("识别设置")
            GroupCard {
                SettingRow("模型设置与标点", "语音页下拉多选已下载模型，最多三个", onClick = { nav.push(Route.VoiceDetail(engine.id)) }) { Chevron() }
            }
        }
        GroupTitle("语音面板")
        GroupCard {
            SwitchRow(
                "说完后文字留在面板上", "默认关闭：文字上屏后，面板上的字幕立即消失。说话时点一下字幕可以看全文",
                checked = WeavePrefs.voiceKeepText(prefs), subtitleMaxLines = 3,
            ) { prefs.edit().putBoolean(WeavePrefs.VOICE_KEEP_TEXT, it).apply() }
        }
        Text(
            "点按开始、再点结束；长按空格松手后结束。可多选三个离线模型，同一次录音分别识别，最后点一行上屏。中文专用与中英混说模型已在列表中标明。波纹跟随实际收音音量。",
            Modifier.padding(24.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    importer.Sheet()
}

/**
 * 「同时使用」（06 §6）：一次录音同时交给主引擎和勾选的引擎，说完后在键盘上的结果列表里选一条上屏。
 * 系统语音识别自己占用麦克风，只能单独使用。
 * "Use together": one recording goes to the primary and the ticked engines; the platform
 * recognizer owns the mic and can only be used alone.
 */
@Composable
private fun CombineCard(engines: VoiceEngines, plugins: List<VoicePlugin>, active: String?, selection: List<VoicePlugin>, onChange: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val primaryOk = active != null && engines.canCombine(active)
    val primaryReady = plugins.firstOrNull { it.id == active }?.let { it.configured && missingRequired(engines, it).isEmpty() } == true
    var expanded by rememberSaveable { mutableStateOf(false) }
    val angle by animateFloatAsState(if (expanded) 180f else 0f, label = "combined engines chevron")
    GroupTitle("同时使用")
    GroupCard(Modifier.testTag("voice_combination").animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button,
                onClickLabel = if (expanded) "收起引擎组合" else "展开引擎组合", onClick = { expanded = !expanded })
                .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(painterResource(R.drawable.ic_waveform), null, Modifier.size(24.dp), tint = cs.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("多引擎识别", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("已选 ${selection.size} 个 · 最多同时使用 3 个", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Icon(painterResource(R.drawable.ic_chevron_down), null, Modifier.size(18.dp).rotate(angle), tint = cs.onSurfaceVariant)
        }
        if (expanded) {
            RowDivider(false)
            val others = plugins.filter { it.id != active }.distinctBy { it.id }
            others.forEachIndexed { i, p ->
                if (i > 0) RowDivider()
                val checked = selection.any { it.id == p.id }
                val ready = p.configured && missingRequired(engines, p).isEmpty()
                val can = checked || (primaryOk && primaryReady && engines.canCombine(p.id) && ready && selection.size < 3)
                Row(
                    Modifier.testTag("voice_combination_${p.id}").fillMaxWidth().heightIn(min = 64.dp)
                        .let { m -> if (can) m.clickable { toggleExtra(engines, p.id, !checked); onChange() } else m }
                        .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PluginAvatar(p, 24, 6)
                    Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
                        Text(p.name, style = MaterialTheme.typography.bodyLarge, color = if (can) cs.onSurface else cs.onSurface.copy(alpha = 0.7f))
                        val hint = when {
                            !engines.canCombine(p.id) -> "独占麦克风，只能单独使用"
                            !ready -> "请先完成插件配置"
                            !checked && selection.size >= 3 -> "已选满 3 个引擎"
                            else -> null
                        }
                        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    androidx.compose.material3.Checkbox(
                        checked = checked, enabled = can,
                        onCheckedChange = { toggleExtra(engines, p.id, it); onChange() },
                    )
                }
            }
            val cloud = if (primaryOk) selection.count { !isBuiltinEngine(it.id) } else 0
            val note = when {
                !primaryReady -> "请先完成当前引擎的配置，再选择同时使用的引擎。"
                !primaryOk -> "当前主引擎独占麦克风，不能与其它引擎同时使用。换一个主引擎后再勾选。"
                cloud > 0 -> "一次录音同时交给选中的引擎，说完后在键盘上选一条上屏。每次说话会同时连接 $cloud 个插件的服务。"
                else -> "一次录音同时交给选中的引擎，说完后在键盘上选一条上屏；结果一致时直接上屏。"
            }
            Text(note, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
    }
}

private fun toggleExtra(engines: VoiceEngines, id: String, on: Boolean) {
    engines.extraIds = if (on) engines.extraIds + id else engines.extraIds - id
}

@Composable
private fun VoicePluginCard(
    p: VoicePlugin, selected: Boolean, combined: Boolean, missing: List<ConfigField>, expanded: Boolean,
    onToggle: () -> Unit, onSelect: () -> Unit, onDetail: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val ready = p.configured && missing.isEmpty()
    val angle by animateFloatAsState(if (expanded) 180f else 0f, label = "voice plugin chevron")
    Surface(
        shape = RoundedCornerShape(16.dp), color = cs.surfaceContainer,
        border = if (expanded) BorderStroke(1.dp, cs.primary.copy(alpha = 0.22f)) else null,
        modifier = Modifier.testTag("voice_plugin_${p.id}").padding(horizontal = 16.dp).fillMaxWidth().animateContentSize(),
    ) {
        Column {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val compact = maxWidth < 340.dp * LocalDensity.current.fontScale
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button,
                        onClickLabel = if (expanded) "收起插件" else "展开插件", onClick = onToggle)
                        .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PluginAvatar(p, 42, 12)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(p.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val meta = listOfNotNull(p.version.takeIf { it.isNotBlank() }?.let { "v$it" }, "${p.configSchema.size} 项设置").joinToString(" · ")
                        Text(meta, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        if (compact) VoicePluginStatus(ready, selected, combined)
                    }
                    if (!compact) VoicePluginStatus(ready, selected, combined)
                    Icon(painterResource(R.drawable.ic_chevron_down), null, Modifier.size(18.dp).rotate(angle), tint = cs.onSurfaceVariant)
                }
            }
            if (expanded) {
                HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp, color = cs.outlineVariant)
                if (p.description.isNotBlank()) Text(p.description, Modifier.padding(horizontal = 16.dp).padding(top = 14.dp), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                if (!ready) Text(
                    if (missing.isNotEmpty()) "请先填写：" + missing.joinToString("、") { it.label } else "请先完成插件配置，再启用语音识别。",
                    Modifier.padding(horizontal = 16.dp).padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = cs.error,
                )
                SettingRow("配置与详情", "查看设置、插件信息与网络访问", icon = R.drawable.ic_settings, subtitleMaxLines = 2, onClick = onDetail) { Chevron() }
                FilledTonalButton(onClick = onSelect, enabled = ready && !selected,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).fillMaxWidth()) {
                    if (selected) { Icon(painterResource(R.drawable.ic_check), null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
                    Text(if (selected) "当前引擎" else "设为当前引擎")
                }
            }
        }
    }
}

@Composable
private fun VoicePluginStatus(ready: Boolean, selected: Boolean, combined: Boolean) {
    val cs = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(shape = RoundedCornerShape(8.dp), color = when {
            !ready -> cs.errorContainer
            selected || combined -> cs.primaryContainer
            else -> cs.surfaceContainerHigh
        }) {
            Text(when {
                !ready -> "待配置"
                selected -> "当前引擎"
                combined -> "同时使用"
                else -> "已配置"
            }, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium,
                color = when { !ready -> cs.onErrorContainer; selected || combined -> cs.onPrimaryContainer; else -> cs.onSurfaceVariant })
        }
        if (!ready && (selected || combined)) Text(if (selected) "当前引擎" else "同时使用", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
    }
}

// ------------------------------------------------------------------ import (03 §6.3)

/** 插件包导入：SAF 选择 → 复制到 cache → 预览确认（名称、版本、网络访问）→ 安装 → 结果。 Import flow. */
class PluginImporter(val launch: () -> Unit, val importFile: (File) -> Unit, val Sheet: @Composable () -> Unit)

private sealed interface ImportStep {
    data object Busy : ImportStep
    data class Confirm(val file: File, val preview: VoicePlugin) : ImportStep
    data class Done(val result: Result<VoicePlugin>) : ImportStep
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberPluginImporter(engines: VoiceEngines, onInstalled: () -> Unit): PluginImporter {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<ImportStep?>(null) }
    DisposableEffect(engines) { onDispose { (step as? ImportStep.Confirm)?.file?.delete() } }
    val importFile: (File) -> Unit = { file ->
        step = ImportStep.Busy
        scope.launch {
            var retained = false
            try {
                val r = withContext(Dispatchers.IO) { engines.inspect(file.absolutePath) }
                step = r.fold(onSuccess = { retained = true; ImportStep.Confirm(file, it) }, onFailure = { ImportStep.Done(Result.failure(it)) })
            } finally { if (!retained) file.delete() }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        step = ImportStep.Busy
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(ctx.cacheDir, "import").apply { mkdirs() }
                    val f = File.createTempFile("plugin-", ".archive", dir)
                    try {
                        ctx.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { output ->
                            val buffer = ByteArray(32 * 1024); var copied = 0L
                            while (true) {
                                val n = input.read(buffer); if (n < 0) break
                                copied += n; require(copied <= 64L * 1024 * 1024) { "插件压缩包超过 64 MB" }
                                output.write(buffer, 0, n)
                            }
                        } } ?: error("无法读取文件")
                        // 旧实现（或测试替身）不支持预览时直接安装。 Fall back to direct install.
                        val preview = engines.inspect(f.absolutePath)
                        if (preview.exceptionOrNull() is UnsupportedOperationException) null to f else (preview.getOrThrow() to f)
                    } catch (error: Throwable) { f.delete(); throw error }
                }
            }
            step = r.fold(
                onSuccess = { (preview, f) ->
                    if (preview != null) ImportStep.Confirm(f, preview) else {
                        ImportStep.Done(withContext(Dispatchers.IO) { engines.install(f.absolutePath).also { f.delete() } })
                    }
                },
                onFailure = { ImportStep.Done(Result.failure(it)) },
            )
            (step as? ImportStep.Done)?.result?.onSuccess { onInstalled() }
        }
    }
    val install: (File) -> Unit = { f ->
        step = ImportStep.Busy
        scope.launch {
            val first = runCatching { engines.list().none { !isBuiltinEngine(it.id) } }.getOrDefault(false)
            val r = withContext(Dispatchers.IO) { try { engines.install(f.absolutePath) } finally { f.delete() } }
            // 第一个导入的插件自动设为当前。 The first imported plugin becomes active.
            r.onSuccess { if (first) engines.activeId = it.id; onInstalled() }
            step = ImportStep.Done(r)
        }
    }
    return remember(engines) {
        PluginImporter(
            launch = { picker.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) },
            importFile = importFile,
            Sheet = {
                val st = step
                if (st != null) {
                    ModalBottomSheet(onDismissRequest = { if (st !is ImportStep.Busy) { (st as? ImportStep.Confirm)?.file?.delete(); step = null } }) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            when (st) {
                                ImportStep.Busy -> Text("正在处理…", style = MaterialTheme.typography.titleMedium)
                                is ImportStep.Confirm -> ImportPreview(st.preview, onCancel = { st.file.delete(); step = null }, onInstall = { install(st.file) })
                                is ImportStep.Done -> ImportResult(st.result, onClose = { step = null })
                            }
                        }
                    }
                }
            },
        )
    }
}

@Composable
internal fun androidx.compose.foundation.layout.ColumnScope.ImportPreview(p: VoicePlugin, onCancel: () -> Unit, onInstall: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        PluginAvatar(p, 48, 12)
        Column {
            Text(p.name, style = MaterialTheme.typography.titleMedium)
            Text("v${p.version} · 语音识别", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
    }
    if (p.description.isNotBlank()) Text(p.description, style = MaterialTheme.typography.bodyMedium)
    // 弹层内已有左右边距：组标题与卡片不再缩进。 The sheet already has side padding.
    Text("网络访问", style = MaterialTheme.typography.labelLarge, color = cs.primary, modifier = Modifier.padding(top = 8.dp))
    Surface(color = cs.surfaceContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column { NetworkAccessRows(p) }
    }
    Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onCancel) { Text("取消") }
        Button(onClick = onInstall) { Text("安装") }
    }
}

/**
 * 插件的网络访问说明（导入确认与详情页共用），放在分组卡片里。不联网时不提「发送到上述地址」。
 * Network access rows (shared by import and detail), placed inside a group card.
 */
@Composable
fun NetworkAccessRows(p: VoicePlugin) {
    val cs = MaterialTheme.colorScheme
    when {
        p.unrestrictedNetwork -> SettingRow(
            "可访问任意网络地址", "语音可能发送到任意地址进行识别。只安装你信任的插件。",
            icon = R.drawable.ic_warning, titleColor = cs.error, subtitleMaxLines = 3,
        )
        p.networkHosts.isEmpty() -> SettingRow("不联网", "该插件不访问网络", icon = R.drawable.ic_lock)
        else -> {
            p.networkHosts.forEachIndexed { i, h ->
                if (i > 0) RowDivider(false)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(h, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = cs.onSurface)
                }
            }
            RowDivider(false)
            Text(
                "语音会发送到上述地址进行识别。", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun ImportResult(r: Result<VoicePlugin>, onClose: () -> Unit) {
    if (r.isSuccess) {
        val p = r.getOrThrow()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PluginAvatar(p, 48, 12)
            Column {
                Text(p.name, style = MaterialTheme.typography.titleMedium)
                Text("v${p.version} · 已安装", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Button(onClick = onClose, Modifier.fillMaxWidth()) { Text("完成") }
    } else {
        Text("无法安装插件", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
        Text(r.exceptionOrNull()?.message ?: "未知错误", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onClose) { Text("关闭") }
    }
}

// ------------------------------------------------------------------ detail & form (03 §6.2)

@Composable
fun VoiceDetailScreen(id: String, statusVersion: Int = 0) {
    val deps = LocalDeps.current
    val nav = LocalNav.current
    val engines = remember { deps.engines() }
    var tick by remember { mutableIntStateOf(0) }
    val plugin = remember(tick, statusVersion) { engines.list().firstOrNull { it.id == id } }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    if (plugin == null) {
        SubPage("语音引擎") { Text("插件不存在或已删除", Modifier.padding(32.dp)) }
        return
    }
    val isActive = engines.active()?.id == id
    val missing = missingRequired(engines, plugin)

    SubPage(plugin.name, snackbar = snack) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PluginAvatar(plugin, 64, 16)
            Spacer(Modifier.height(12.dp))
            Text(plugin.name + if (plugin.version.isNotBlank()) " · v${plugin.version}" else "", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(plugin.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(plugin.description, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            if (isActive) {
                OutlinedButton(onClick = {}, enabled = false) {
                    Icon(painterResource(R.drawable.ic_check), null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("当前引擎")
                }
            } else {
                Button(onClick = { engines.activeId = id; tick++; scope.launch { snack.showSnackbar("已切换到 ${plugin.name}") } }, enabled = plugin.configured && missing.isEmpty()) {
                    Text("设为当前引擎")
                }
                if (missing.isNotEmpty()) {
                    Text(
                        "请先填写：" + missing.joinToString("、") { it.label }, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp),
                    )
                } else if (!plugin.configured) {
                    Text("请先完成插件配置，再启用语音识别。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        // 按 section 首次出现顺序分组，缺省归入「通用」。 Group by section in first-seen order.
        val groups = LinkedHashMap<String, MutableList<ConfigField>>()
        for (f in plugin.configSchema) groups.getOrPut(f.section ?: "通用") { mutableListOf() } += f
        for ((section, fields) in groups) {
            GroupTitle(section)
            GroupCard {
                fields.forEachIndexed { i, f ->
                    if (i > 0) RowDivider(false)
                    ConfigRow(engines, plugin.id, f, tick) { tick++ }
                }
            }
        }
        if (plugin.id == LOCAL_ENGINE_ID) {
            GroupCard(Modifier.padding(top = 12.dp)) {
                SettingRow("管理语音包", "逐项安装更准的模型，或卸载不用的", icon = R.drawable.ic_waveform, onClick = { nav.push(Route.Models) }) { Chevron() }
            }
        }
        if (!isBuiltinEngine(plugin.id)) {
            GroupTitle("网络访问")
            GroupCard { NetworkAccessRows(plugin) }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            if (plugin.configSchema.isNotEmpty()) {
                TextButton(onClick = {
                    for (f in plugin.configSchema) engines.setConfig(id, f.key, f.defaultValue.orEmpty())
                    tick++
                    scope.launch { snack.showSnackbar("已恢复默认设置") }
                }) { Text("恢复默认设置") }
            } else Spacer(Modifier)
            // 内置引擎不能删除；系统语音识别可以停用。 Built-ins can't be removed; the platform recognizer can be disabled.
            if (!isBuiltinEngine(plugin.id)) {
                TextButton(onClick = { confirmDelete = true }) { Text("删除插件", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除「${plugin.name}」？") },
            text = { Text("插件及其设置将被移除，之后可重新导入。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    engines.uninstall(id).onSuccess { nav.pop() }.onFailure { e -> scope.launch { snack.showSnackbar("删除失败：${e.message}") } }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

/** configSchema 单个字段（03 §6.2 渲染规则）。 One configSchema field. */
@Composable
private fun ConfigRow(engines: VoiceEngines, id: String, f: ConfigField, tick: Int, onChanged: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val value = remember(tick) { engines.value(id, f) }
    var dialog by remember { mutableStateOf(false) }
    var helpExpanded by remember(f.helpText) { mutableStateOf(false) }
    var helpOverflows by remember(f.helpText) { mutableStateOf(false) }
    val title = @Composable {
        Row {
            Text(f.label, style = MaterialTheme.typography.bodyLarge)
            if (f.required) Text(" *", color = cs.error, style = MaterialTheme.typography.bodyLarge)
        }
    }
    val help = @Composable {
        f.helpText?.let {
            Text(
                it, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                maxLines = if (helpExpanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                onTextLayout = { layout -> if (!helpExpanded) helpOverflows = layout.hasVisualOverflow },
            )
            if (helpOverflows) TextButton(onClick = { helpExpanded = !helpExpanded }) {
                Text(if (helpExpanded) "收起说明" else "展开说明", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    fun save(v: String) { engines.setConfig(id, f.key, v); onChanged() }
    val rowMod = Modifier.fillMaxWidth().heightIn(min = if (f.helpText == null) 56.dp else 72.dp)
    when (f.type) {
        "switch" -> {
            val on = value == "true"
            Row(rowMod.clickable { save((!on).toString()) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { title(); help() }
                Switch(checked = on, onCheckedChange = { save(it.toString()) })
            }
        }
        "select", "text", "password", "number" -> {
            val secret = isSecret(f)
            val shown = when {
                value.isEmpty() -> "未设置"
                secret -> "••••" + value.takeLast(4)
                else -> value
            }
            Row(rowMod.clickable { dialog = true }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { title(); help() }
                Box(Modifier.padding(start = 12.dp)) { ValueChevron(shown) }
            }
            if (dialog) {
                if (f.type == "select") SelectDialog(f, value, { dialog = false }) { save(it); dialog = false }
                else TextDialog(f, value, secret, { dialog = false }) { save(it); dialog = false }
            }
        }
        else -> {
            Log.w("WeaveSettings", "unknown config field type ${f.type} for ${f.key}")
            Row(rowMod.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${f.label}：$value", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SelectDialog(f: ConfigField, value: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(f.label) },
        text = {
            Column {
                for (o in f.options) {
                    Row(
                        Modifier.fillMaxWidth().height(48.dp).selectable(o == value) { onPick(o) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = o == value, onClick = null)
                        Text(o, Modifier.padding(start = 16.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun TextDialog(f: ConfigField, value: String, secret: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var t by remember { mutableStateOf(value) }
    var visible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(f.label) },
        text = {
            OutlinedTextField(
                t, { t = it }, singleLine = true,
                placeholder = { Text(f.helpText ?: "") },
                visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (f.type == "number") KeyboardType.Number else if (secret) KeyboardType.Password else KeyboardType.Text),
                textStyle = if (secret) MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
                trailingIcon = if (secret) ({
                    TextButton(onClick = { visible = !visible }) { Text(if (visible) "隐藏" else "显示") }
                }) else null,
            )
        },
        confirmButton = { TextButton(onClick = { onSave(t.trim()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
