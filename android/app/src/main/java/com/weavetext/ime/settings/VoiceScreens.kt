package com.weavetext.ime.settings

import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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

@Composable
fun VoiceListScreen() {
    val deps = LocalDeps.current
    val nav = LocalNav.current
    val engines = remember { deps.engines() }
    var tick by remember { mutableIntStateOf(0) }
    val plugins = remember(tick) { runCatching { engines.list() }.getOrDefault(emptyList()) }
    val active = remember(tick) { engines.active()?.id }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val importer = rememberXipkImporter(engines) { tick++ }

    SubPage("语音引擎", snackbar = snack, actions = {
        TextButton(onClick = importer.launch) {
            Icon(painterResource(R.drawable.ic_import), null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("导入")
        }
    }) {
        // 轻量版没有端侧识别运行时，也就不提供离线模型。 Lite has no on-device runtime, so no models.
        if (com.weavetext.ime.BuildConfig.LOCAL_ASR) OfflineModelsCard(remember { deps.models() }) { nav.push(Route.Models) }
        if (plugins.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 96.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.ic_waveform), null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("还没有语音引擎", style = MaterialTheme.typography.titleMedium)
                FilledTonalButton(onClick = importer.launch) { Text("导入 .xipk 插件") }
            }
        } else {
            GroupTitle("已安装 · ${plugins.size}")
            GroupCard {
                plugins.forEachIndexed { i, p ->
                    if (i > 0) RowDivider()
                    PluginRow(p, p.id == active, onSelect = {
                        engines.activeId = p.id
                        tick++
                        scope.launch { snack.showSnackbar("已切换到 ${p.name}") }
                    }, onDetail = { nav.push(Route.VoiceDetail(p.id)) })
                }
            }
            if (plugins.size >= 2) CombineCard(engines, plugins, active) { tick++ }
        }
        Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(painterResource(R.drawable.ic_info), null, Modifier.size(16.dp).padding(top = 1.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "本地离线识别在手机上完成；导入的插件会把语音发送到其服务进行识别，且只能访问声明的域名。点击行切换主引擎，点击右侧图标查看详情与设置。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
private fun CombineCard(engines: VoiceEngines, plugins: List<VoicePlugin>, active: String?, onChange: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val primaryOk = active != null && engines.canCombine(active)
    val extra = engines.extraIds
    GroupTitle("同时使用")
    GroupCard {
        val others = plugins.filter { it.id != active }
        others.forEachIndexed { i, p ->
            if (i > 0) RowDivider()
            val can = primaryOk && engines.canCombine(p.id)
            val checked = can && p.id in extra
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .let { m -> if (can) m.clickable { toggleExtra(engines, p.id, !checked); onChange() } else m }
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PluginAvatar(p, 24, 6)
                Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
                    Text(p.name, style = MaterialTheme.typography.bodyLarge, color = if (can) cs.onSurface else cs.onSurface.copy(alpha = 0.7f))
                    if (!engines.canCombine(p.id)) {
                        Text("独占麦克风，只能单独使用", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
                androidx.compose.material3.Checkbox(
                    checked = checked, enabled = can,
                    onCheckedChange = { toggleExtra(engines, p.id, it); onChange() },
                )
            }
        }
    }
    val cloud = if (primaryOk) engines.selection().count { !isBuiltinEngine(it.id) } else 0
    val note = when {
        !primaryOk -> "当前主引擎独占麦克风，不能与其它引擎同时使用。换一个主引擎后再勾选。"
        cloud > 0 -> "一次录音同时交给主引擎和勾选的引擎，说完后在键盘上选一条上屏。每次说话会同时连接 $cloud 个插件的服务。"
        else -> "一次录音同时交给主引擎和勾选的引擎，说完后在键盘上选一条上屏；结果一致时直接上屏。"
    }
    Text(note, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp))
}

private fun toggleExtra(engines: VoiceEngines, id: String, on: Boolean) {
    engines.extraIds = if (on) engines.extraIds + id else engines.extraIds - id
}

@Composable
private fun PluginRow(p: VoicePlugin, selected: Boolean, onSelect: () -> Unit, onDetail: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 80.dp).selectable(selected, onClick = onSelect).padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PluginAvatar(p, 40, 10)
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(p.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (p.configured) p.description else "需要先完成配置", style = MaterialTheme.typography.bodySmall,
                color = if (p.configured) cs.onSurfaceVariant else cs.error, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            val n = p.configSchema.size
            val meta = listOfNotNull(p.version.takeIf { it.isNotEmpty() }?.let { "v$it" }, if (n > 0) "$n 项设置" else null).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        RadioButton(selected = selected, onClick = onSelect)
        IconButton(onClick = onDetail) {
            Icon(
                painterResource(if (p.configSchema.isEmpty()) R.drawable.ic_info else R.drawable.ic_settings),
                "详情", tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp),
            )
        }
    }
}

// ------------------------------------------------------------------ import (03 §6.3)

/** .xipk 导入：SAF 选择 → 复制到 cache → 预览确认（名称、版本、网络访问）→ 安装 → 结果。 Import flow. */
class XipkImporter(val launch: () -> Unit, val Sheet: @Composable () -> Unit)

private sealed interface ImportStep {
    data object Busy : ImportStep
    data class Confirm(val file: File, val preview: VoicePlugin) : ImportStep
    data class Done(val result: Result<VoicePlugin>) : ImportStep
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberXipkImporter(engines: VoiceEngines, onInstalled: () -> Unit): XipkImporter {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<ImportStep?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        step = ImportStep.Busy
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(ctx.cacheDir, "import").apply { mkdirs() }
                    val f = File(dir, "plugin.xipk")
                    ctx.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
                        ?: error("无法读取文件")
                    // 旧实现（或测试替身）不支持预览时直接安装。 Fall back to direct install.
                    val preview = engines.inspect(f.absolutePath)
                    if (preview.exceptionOrNull() is UnsupportedOperationException) null to f else (preview.getOrThrow() to f)
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
            val r = withContext(Dispatchers.IO) { engines.install(f.absolutePath).also { f.delete() } }
            // 第一个导入的插件自动设为当前。 The first imported plugin becomes active.
            r.onSuccess { if (first) engines.activeId = it.id; onInstalled() }
            step = ImportStep.Done(r)
        }
    }
    return remember(engines) {
        XipkImporter(
            launch = { picker.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) },
            Sheet = {
                val st = step
                if (st != null) {
                    ModalBottomSheet(onDismissRequest = { if (st !is ImportStep.Busy) step = null }) {
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
fun VoiceDetailScreen(id: String) {
    val deps = LocalDeps.current
    val nav = LocalNav.current
    val engines = remember { deps.engines() }
    var tick by remember { mutableIntStateOf(0) }
    val plugin = remember(tick) { engines.list().firstOrNull { it.id == id } }
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
                Button(onClick = { engines.activeId = id; tick++; scope.launch { snack.showSnackbar("已切换到 ${plugin.name}") } }, enabled = missing.isEmpty()) {
                    Text("设为当前引擎")
                }
                if (missing.isNotEmpty()) {
                    Text(
                        "请先填写：" + missing.joinToString("、") { it.label }, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp),
                    )
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
                SettingRow("管理离线模型", "下载更准的模型，或删除不用的模型", icon = R.drawable.ic_waveform, onClick = { nav.push(Route.Models) }) { Chevron() }
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
            // 内置引擎不能删除。 Built-in engines cannot be removed.
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
    var helpExpanded by remember { mutableStateOf(false) }
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
                modifier = Modifier.clickable { helpExpanded = !helpExpanded },
            )
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
