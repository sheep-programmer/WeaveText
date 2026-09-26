package com.weavetext.ime.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.weavetext.ime.voice.VoiceHelp
import com.weavetext.ime.voice.VoiceUpgrade

/**
 * 轻量版「安装离线语音」：说明、下载进度、一键安装。 Lite: install offline voice — explanation, progress, install.
 */
@Composable
fun VoiceUpgradeScreen() = SubPage("安装离线语音") {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf(VoiceUpgrade.state) }
    DisposableEffect(Unit) {
        val l: (VoiceUpgrade.State) -> Unit = { state = it }
        VoiceUpgrade.addListener(l)
        onDispose { VoiceUpgrade.removeListener(l) }
    }
    GroupCard(Modifier.padding(top = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("在手机上直接识别语音", style = MaterialTheme.typography.titleMedium)
            Text(
                "当前是轻量版，不含离线语音识别。安装「离线语音版」后即可语音输入：识别全部在手机上完成，不联网、语音不离开设备。" +
                    "它会覆盖安装当前应用，设置、词库和学过的词都保留。安装包约 140 MB，建议在 Wi-Fi 下下载。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (val s = state) {
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
    }
    GroupTitle("其他方式")
    GroupCard {
        SettingRow("打开系统语音输入设置", "手机自带语音服务可用时，织文会自动使用", onClick = { VoiceHelp.openSystemVoiceSettings(ctx) }) { Chevron() }
        RowDivider()
        SettingRow("在浏览器中下载", "从发布页手动下载离线语音版", onClick = { VoiceHelp.openOfflineBuild(ctx) }) { Chevron() }
    }
}
