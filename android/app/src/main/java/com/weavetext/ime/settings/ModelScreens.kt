package com.weavetext.ime.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.weavetext.ime.R
import com.weavetext.ime.models.ModelKind
import com.weavetext.ime.models.AsrRuntime
import com.weavetext.ime.models.VoicePack
import com.weavetext.ime.models.ModelRepository
import com.weavetext.ime.models.ModelSpec
import com.weavetext.ime.models.ModelState
import com.weavetext.ime.models.isReady
import java.util.Locale

// ------------------------------------------------------------------ helpers

/** 十进制 MB（与目录描述里的「约 26 MB」一致），数字与单位间不换行。 Decimal MB with a no-break space. */
fun formatSize(bytes: Long): String {
    val mb = bytes / 1_000_000.0
    return when {
        mb >= 100 -> String.format(Locale.ROOT, "%.0f\u00A0MB", mb)
        mb >= 1 -> String.format(Locale.ROOT, "%.1f\u00A0MB", mb)
        else -> String.format(Locale.ROOT, "%.0f\u00A0KB", bytes / 1000.0)
    }
}

private fun formatSpeed(bps: Long) = if (bps <= 0) null else formatSize(bps) + "/s"


/** 订阅模型状态变化，返回每次变化递增的计数。 Observe model state changes as a counter. */
@Composable
fun rememberModelTick(repo: ModelRepository): Int {
    var tick by remember(repo) { mutableIntStateOf(0) }
    DisposableEffect(repo) {
        val l: () -> Unit = { tick++ }
        repo.addListener(l)
        onDispose { repo.removeListener(l) }
    }
    return tick
}

/**
 * 已用空间：只在状态「类别」变化时重新统计（下载进度不触发）。
 * Used space, recounted only when a state's category changes, not on every progress tick.
 */
@Composable
private fun rememberUsedBytes(repo: ModelRepository, tick: Int): Long {
    val sig = remember(tick) { repo.catalog.models.map { repo.state(it.id).let { s -> if (s is ModelState.Downloading) "d" else s.toString() } } }
    return remember(sig) { runCatching { repo.usedBytes() }.getOrDefault(0L) }
}

/** 语音引擎页顶部的「语音包」入口卡片。 Entry card at the top of the voice engines page. */
@Composable
fun OfflineModelsCard(repo: ModelRepository, onClick: () -> Unit) {
    val tick = rememberModelTick(repo)
    val used = rememberUsedBytes(repo, tick)
    val count = remember(tick) { repo.catalog.models.count { repo.state(it.id).isReady } }
    GroupCard(Modifier.padding(top = 8.dp)) {
        SettingRow("语音包", "已安装 $count 项 · 占用 ${formatSize(used)}", icon = R.drawable.ic_waveform, onClick = onClick) { Chevron() }
    }
}

// ------------------------------------------------------------------ page

// 运行库只在轻量版的目录里出现。 The runtime is only listed in the lite build's catalog.
private val GROUPS = listOf(
    ModelKind.ASR_RUNTIME to "识别运行库", ModelKind.ASR_STREAMING to "实时识别", ModelKind.ASR_OFFLINE to "终稿识别", ModelKind.PUNCTUATION to "标点",
)

@Composable
fun ModelsScreen() {
    val repo = LocalDeps.current.models()
    val tick = rememberModelTick(repo)
    var settingsTick by remember { mutableIntStateOf(0) }
    var confirmMetered by remember { mutableStateOf<ModelSpec?>(null) }
    var confirmDelete by remember { mutableStateOf<ModelSpec?>(null) }
    var sourceDialog by remember { mutableStateOf(false) }
    var customDialog by remember { mutableStateOf(false) }

    // 仅 Wi-Fi 且当前计流量：先确认。 Ask first on a metered network when Wi-Fi only is on.
    // 轻量版里语音模型离不开运行库：没装时一起装。 On lite a model needs the runtime: install it too.
    val startDownload: (ModelSpec) -> Unit = { m ->
        if (repo.wifiOnly && repo.isMetered()) confirmMetered = m else VoicePack.install(repo, m.id, allowMetered = !repo.wifiOnly)
    }
    val lite = !AsrRuntime.bundled

    SubPage("语音包") {
        // 轻量版：顶部是推荐组合一键安装（装齐后隐藏）。 Lite: the one-tap recommended set on top.
        if (lite) VoicePackHeader(repo)
        for ((kind, title) in GROUPS) {
            val models = repo.catalog.models.filter { it.kind == kind }
            if (models.isEmpty()) continue
            GroupTitle(title)
            GroupCard {
                models.forEachIndexed { i, m ->
                    if (i > 0) RowDivider(false)
                    val state = remember(tick) { repo.state(m.id) }
                    ModelItem(
                        m, state,
                        onDownload = { startDownload(m) },
                        onCancel = { repo.cancel(m.id) },
                        onDelete = { confirmDelete = m },
                    )
                }
            }
        }
        InfoNote(
            "每一项都可以单独安装或卸载。识别在手机上完成，语音不离开设备。字错率仅供模型间相对比较，数字越小越准。" +
                if (lite) "识别模型需要「识别运行库」，安装模型时会一起装上。" else "",
        )

        GroupTitle("下载设置")
        GroupCard {
            val pref = remember(settingsTick) { repo.mirrorPreference }
            val wifiOnly = remember(settingsTick) { repo.wifiOnly }
            SettingRow("下载源", sourceName(repo, pref), onClick = { sourceDialog = true }) { Chevron() }
            RowDivider(false)
            SwitchRow("仅 Wi-Fi 下载", "移动网络下先询问再下载", checked = wifiOnly) { repo.wifiOnly = it; settingsTick++ }
            RowDivider(false)
            SettingRow("已用空间", "已下载的模型与未完成的下载") {
                Text(formatSize(rememberUsedBytes(repo, tick)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (lite) VoiceOtherWays()
    }

    confirmMetered?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmMetered = null },
            title = { Text("使用移动网络下载？") },
            text = { Text("当前为计流量网络，下载「${m.name}」约需 ${formatSize(m.archiveSize)} 流量。") },
            confirmButton = { TextButton(onClick = { confirmMetered = null; VoicePack.install(repo, m.id, allowMetered = true) }) { Text("下载") } },
            dismissButton = { TextButton(onClick = { confirmMetered = null }) { Text("取消") } },
        )
    }
    confirmDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("卸载「${m.name}」？") },
            text = {
                Text(
                    "将释放约 ${formatSize(m.installedSize)}，之后可重新下载。" +
                        if (m.kind == ModelKind.ASR_RUNTIME) "已下载的识别模型会保留，但要重新安装运行库后才能使用。" else "",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; repo.delete(m.id) }) { Text("卸载", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } },
        )
    }
    if (sourceDialog) {
        SourceDialog(
            repo,
            onDismiss = { sourceDialog = false },
            onPick = { repo.mirrorPreference = it; settingsTick++; sourceDialog = false },
            onCustom = { sourceDialog = false; customDialog = true },
        )
    }
    if (customDialog) {
        CustomMirrorDialog(repo.customMirror.orEmpty(), onDismiss = { customDialog = false }) { t ->
            if (t.isEmpty()) {
                repo.customMirror = null
                if (repo.mirrorPreference == CUSTOM) repo.mirrorPreference = AUTO
            } else {
                repo.customMirror = t
                repo.mirrorPreference = CUSTOM
            }
            settingsTick++
            customDialog = false
        }
    }
}

private const val AUTO = "auto"
private const val CUSTOM = "custom"

private fun sourceName(repo: ModelRepository, pref: String): String = when (pref) {
    AUTO -> "自动测速"
    CUSTOM -> repo.customMirror?.let { "自定义：$it" } ?: "自动测速"
    else -> repo.allSources().firstOrNull { it.id == pref }?.name ?: "自动测速"
}

@Composable
private fun InfoNote(text: String) {
    Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(R.drawable.ic_info), null, Modifier.size(16.dp).padding(top = 1.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ------------------------------------------------------------------ one model

@Composable
private fun ModelItem(m: ModelSpec, state: ModelState, onDownload: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(m.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (m.builtin) Tag("内置")
        }
        if (m.description.isNotBlank()) Text(m.description, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        val size = if (m.builtin) "大小 ${formatSize(m.installedSize)}" else "下载 ${formatSize(m.archiveSize)} · 安装后 ${formatSize(m.installedSize)}"
        Text(size, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        if (m.license.isNotBlank()) Text("许可证 ${m.license}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        m.bench?.let { b ->
            Text(
                String.format(Locale.ROOT, "字错率 干净 %.2f%% · 嘈杂 %.2f%%", b.cerClean, b.cerNoisy) + "（织文自测，合成语音）",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ModelActions(m, state, onDownload, onCancel, onDelete)
        }
    }
}

@Composable
private fun Tag(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(6.dp)) {
        Text(
            text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.StatusText(text: String, icon: Int, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(painterResource(icon), null, Modifier.size(18.dp), tint = color)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ModelActions(
    m: ModelSpec, state: ModelState, onDownload: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    when (state) {
        ModelState.Builtin -> StatusText("随应用内置，无需下载", R.drawable.ic_check, LocalSuccess.current)
        ModelState.Installed -> {
            StatusText("已安装", R.drawable.ic_check, LocalSuccess.current)
            TextButton(onClick = onDelete) { Text("卸载", color = cs.error) }
        }
        ModelState.NotInstalled -> {
            Text("未安装", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            FilledTonalButton(onClick = onDownload) { Text("安装") }
        }
        is ModelState.Failed -> {
            Row(Modifier.weight(1f).padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(R.drawable.ic_warning), null, Modifier.size(18.dp).padding(top = 1.dp), tint = cs.error)
                Text(state.message, style = MaterialTheme.typography.bodyMedium, color = cs.error, maxLines = 3)
            }
            FilledTonalButton(onClick = onDownload) { Text("重试") }
        }
        ModelState.Waiting, ModelState.Extracting, is ModelState.Downloading -> {
            Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val p = (state as? ModelState.Downloading)?.progress
                if (p != null && p.total > 0) {
                    LinearProgressIndicator(progress = { (p.downloaded.toFloat() / p.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                } else LinearProgressIndicator(Modifier.fillMaxWidth())
                val label = when {
                    state == ModelState.Waiting -> "正在准备…"
                    state == ModelState.Extracting -> "正在解压与校验…"
                    p != null -> listOfNotNull(
                        if (p.total > 0) "${(p.downloaded * 100 / p.total).coerceIn(0, 100)}%" else formatSize(p.downloaded),
                        formatSpeed(p.bytesPerSecond),
                        p.mirror.ifBlank { null },
                    ).joinToString(" · ")
                    else -> ""
                }
                Text(label, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1)
            }
            // 解压在原生层进行，不能中途取消。 Extraction runs natively and cannot be cancelled.
            if (state != ModelState.Extracting) TextButton(onClick = onCancel) { Text("取消") }
        }
    }
}

// ------------------------------------------------------------------ dialogs

@Composable
private fun SourceDialog(repo: ModelRepository, onDismiss: () -> Unit, onPick: (String) -> Unit, onCustom: () -> Unit) {
    val pref = repo.mirrorPreference
    val custom = repo.customMirror
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("下载源") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SourceOption("自动测速（推荐）", "测速后选最快的源，失败自动换源", pref == AUTO) { onPick(AUTO) }
                for (s in repo.allSources().filter { it.id != CUSTOM }) SourceOption(s.name, null, pref == s.id) { onPick(s.id) }
                SourceOption("自定义镜像", custom ?: "填写镜像地址模板", pref == CUSTOM && custom != null) { onCustom() }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SourceOption(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

/** 自定义镜像模板是否有效：http(s) 地址且含 `{url}`。 A valid template is an http(s) URL containing `{url}`. */
fun isValidMirrorTemplate(t: String): Boolean =
    t.contains("{url}") && (t.startsWith("https://") || t.startsWith("http://"))

@Composable
internal fun CustomMirrorDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var t by remember { mutableStateOf(initial) }
    val trimmed = t.trim()
    val error = trimmed.isNotEmpty() && !isValidMirrorTemplate(trimmed)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义镜像") },
        text = {
            OutlinedTextField(
                t, { t = it }, singleLine = true, isError = error,
                placeholder = { Text("https://example.com/{url}") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                supportingText = {
                    Text(if (error) "地址需以 http(s):// 开头并包含 {url}" else "{url} 会替换为原始下载地址；留空则不使用")
                },
            )
        },
        confirmButton = { TextButton(onClick = { onSave(trimmed) }, enabled = !error) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
