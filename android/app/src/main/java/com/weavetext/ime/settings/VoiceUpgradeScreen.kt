package com.weavetext.ime.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.weavetext.ime.R
import com.weavetext.ime.models.AsrRuntime
import com.weavetext.ime.models.ModelRepository
import com.weavetext.ime.models.VoicePack
import com.weavetext.ime.voice.LOCAL_ENGINE_ID
import com.weavetext.ime.voice.VoiceHelp
import com.weavetext.ime.voice.VoiceUpgrade

/**
 * 轻量版的语音入口：能下载运行库时是「下载语音包」（运行库 + 实时模型，约 30 MB）；
 * 否则（架构不符等）退回「安装离线语音」——下载完整的离线语音版 APK 覆盖安装。
 * The lite build's voice page: "download the voice pack" (runtime + streaming model, ~30 MB) when the
 * runtime is available for this device, else the full offline-voice APK upgrade.
 */
@Composable
fun VoiceUpgradeScreen() {
    val deps = LocalDeps.current
    val repo = remember { deps.models() }
    val pack = remember(repo) { VoicePack(repo) }
    if (!AsrRuntime.bundled && pack.supported) VoicePackPage(repo, pack) else FullBuildPage()
}

@Composable
private fun VoicePackPage(repo: ModelRepository, pack: VoicePack) {
    val deps = LocalDeps.current
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val tick = rememberModelTick(repo)
    val state = remember(tick) { pack.state() }
    var confirmMetered by remember { mutableStateOf(false) }
    // 装好后自动选中本地离线识别。 Select the local engine once installed.
    val start: (Boolean) -> Unit = { metered ->
        pack.start(allowMetered = metered) { runCatching { deps.engines().activeId = LOCAL_ENGINE_ID } }
    }
    val onDownload = { if (repo.wifiOnly && repo.isMetered()) confirmMetered = true else start(!repo.wifiOnly) }

    SubPage("下载语音包") {
        GroupCard(Modifier.padding(top = 8.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("离线语音包", style = MaterialTheme.typography.titleMedium)
                Text(
                    "下载后即可在手机上识别语音：不联网，语音不离开设备，识别效果与离线语音版相同。" +
                        "语音包含识别运行库和「实时识别 · 小」模型，只需下载一次，不用重新安装应用。建议在 Wi-Fi 下下载。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (state) {
                    is VoicePack.State.Idle, is VoicePack.State.Failed -> {
                        val bytes = (state as? VoicePack.State.Idle)?.bytes ?: (state as VoicePack.State.Failed).bytes
                        if (state is VoicePack.State.Failed) {
                            Text(state.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }
                        Button(onClick = onDownload, Modifier.fillMaxWidth()) {
                            Text((if (state is VoicePack.State.Failed) "重新下载" else "下载语音包") + "（${formatSize(bytes)}）")
                        }
                    }
                    is VoicePack.State.Downloading -> {
                        if (state.total > 0) {
                            LinearProgressIndicator(progress = { (state.done.toFloat() / state.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            val speed = if (state.bytesPerSecond > 0) " · ${formatSize(state.bytesPerSecond)}/s" else ""
                            val via = if (state.mirror.isNotEmpty()) " · ${state.mirror}" else ""
                            Text(
                                if (state.done > 0) "${formatSize(state.done)} / ${formatSize(state.total)}$speed$via" else "正在连接…",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { pack.cancel() }) { Text("取消") }
                        }
                    }
                    VoicePack.State.Installing -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("正在解压与校验…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    VoicePack.State.Ready -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(painterResource(R.drawable.ic_check), null, Modifier.size(20.dp), tint = LocalSuccess.current)
                            Text("语音包已装好，语音输入会使用「本地离线识别」", style = MaterialTheme.typography.bodyMedium)
                        }
                        Button(onClick = { nav.pop() }, Modifier.fillMaxWidth()) { Text("完成") }
                    }
                }
            }
        }
        GroupTitle("更多模型")
        GroupCard {
            SettingRow("离线模型", "终稿识别、智能标点等可按需下载，识别更准", onClick = { nav.push(Route.Models) }) { Chevron() }
        }
        GroupTitle("其他方式")
        GroupCard {
            FullBuildRow()
            RowDivider()
            SettingRow("打开系统语音输入设置", "手机自带语音服务可用时，织文会自动使用", onClick = { VoiceHelp.openSystemVoiceSettings(ctx) }) { Chevron() }
        }
    }

    if (confirmMetered) {
        val bytes = (state as? VoicePack.State.Idle)?.bytes ?: (state as? VoicePack.State.Failed)?.bytes ?: 0L
        AlertDialog(
            onDismissRequest = { confirmMetered = false },
            title = { Text("使用移动网络下载？") },
            text = { Text("当前为计流量网络，语音包约需 ${formatSize(bytes)} 流量。") },
            confirmButton = { TextButton(onClick = { confirmMetered = false; start(true) }) { Text("下载") } },
            dismissButton = { TextButton(onClick = { confirmMetered = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun rememberUpgradeState(): VoiceUpgrade.State {
    var state by remember { mutableStateOf(VoiceUpgrade.state) }
    DisposableEffect(Unit) {
        val l: (VoiceUpgrade.State) -> Unit = { state = it }
        VoiceUpgrade.addListener(l)
        onDispose { VoiceUpgrade.removeListener(l) }
    }
    return state
}

/** 「其他方式」里的完整离线语音版：一行入口，下载时在行内显示进度。 Secondary full-build option. */
@Composable
private fun FullBuildRow() {
    val ctx = LocalContext.current
    val state = rememberUpgradeState()
    when (state) {
        VoiceUpgrade.State.Idle, is VoiceUpgrade.State.Failed -> SettingRow(
            "安装完整离线语音版",
            (state as? VoiceUpgrade.State.Failed)?.message ?: "约 140 MB，覆盖安装，设置和词库都保留",
            onClick = { VoiceUpgrade.start(ctx) },
        ) { Chevron() }
        else -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("安装完整离线语音版", style = MaterialTheme.typography.bodyLarge)
            FullBuildProgress(state)
        }
    }
}

/** 完整版安装包的下载进度、校验与安装按钮。 Download progress, verification and install for the full build. */
@Composable
private fun FullBuildProgress(s: VoiceUpgrade.State) {
    val ctx = LocalContext.current
    when (s) {
        VoiceUpgrade.State.Idle, is VoiceUpgrade.State.Failed -> {
            if (s is VoiceUpgrade.State.Failed) {
                Text(s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = { VoiceUpgrade.start(ctx) }, Modifier.fillMaxWidth()) {
                Text(if (s is VoiceUpgrade.State.Failed) "重新下载" else "下载并安装")
            }
        }
        is VoiceUpgrade.State.Downloading -> {
            if (s.total > 0) {
                LinearProgressIndicator(progress = { (s.done.toFloat() / s.total).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val speed = if (s.bytesPerSecond > 0) " · ${formatSize(s.bytesPerSecond)}/s" else ""
                val via = if (s.mirror.isNotEmpty()) " · ${s.mirror}" else ""
                Text(
                    if (s.total > 0) "${formatSize(s.done)} / ${formatSize(s.total)}$speed$via" else "正在连接…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { VoiceUpgrade.cancel() }) { Text("取消") }
            }
        }
        VoiceUpgrade.State.Verifying -> {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("正在校验安装包…", style = MaterialTheme.typography.bodySmall)
        }
        is VoiceUpgrade.State.Ready -> {
            Text("下载完成。点「安装」后按系统提示确认即可；首次安装需要允许织文「安装未知应用」。", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { VoiceUpgrade.install(ctx, s.file) }, Modifier.fillMaxWidth()) { Text("安装") }
        }
    }
}

/** 运行库不能下载时（如架构不符）：安装完整的离线语音版。 Full offline-voice build when the pack can't be used. */
@Composable
private fun FullBuildPage() = SubPage("安装离线语音") {
    val ctx = LocalContext.current
    val state = rememberUpgradeState()
    GroupCard(Modifier.padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("在手机上直接识别语音", style = MaterialTheme.typography.titleMedium)
            Text(
                "当前是轻量版，不含离线语音识别。安装「离线语音版」后即可语音输入：识别全部在手机上完成，不联网、语音不离开设备。" +
                    "它会覆盖安装当前应用，设置、词库和学过的词都保留。安装包约 140 MB，建议在 Wi-Fi 下下载。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FullBuildProgress(state)
        }
    }
    GroupTitle("其他方式")
    GroupCard {
        SettingRow("打开系统语音输入设置", "手机自带语音服务可用时，织文会自动使用", onClick = { VoiceHelp.openSystemVoiceSettings(ctx) }) { Chevron() }
        RowDivider()
        SettingRow("在浏览器中下载", "从发布页手动下载离线语音版", onClick = { VoiceHelp.openOfflineBuild(ctx) }) { Chevron() }
    }
}
