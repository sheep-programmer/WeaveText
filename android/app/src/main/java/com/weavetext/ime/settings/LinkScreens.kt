package com.weavetext.ime.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.weavetext.ime.R
import com.weavetext.ime.link.LinkController
import com.weavetext.ime.link.LinkNearby
import com.weavetext.ime.link.LinkPeer
import com.weavetext.ime.link.LinkTransfer
import com.weavetext.ime.link.PairState

private fun platformName(p: String) = when (p) {
    "mac" -> "Mac"
    "android" -> "Android"
    "windows" -> "Windows"
    "linux" -> "Linux"
    else -> "电脑"
}

private fun size(n: Long): String = when {
    n >= 1 shl 30 -> "%.1f GB".format(n / (1 shl 30).toDouble())
    n >= 1 shl 20 -> "%.1f MB".format(n / (1 shl 20).toDouble())
    n >= 1 shl 10 -> "%.0f KB".format(n / (1 shl 10).toDouble())
    else -> "$n B"
}

/** 目标：配对码对话框要配的设备。 Target of the code dialog. */
private data class PairTarget(val name: String, val addrs: List<String>, val code: String = "")

/**
 * 织文互联：同一局域网内与电脑配对，互传文字、剪贴板、图片与文件（03 §11）。
 * WeaveLink: pair with a computer on the same LAN and exchange text, clipboard, images and files.
 */
@Composable
fun LinkScreen() {
    val deps = LocalDeps.current
    val link: LinkController = remember { deps.link() }
    val s by link.state.collectAsState()
    val ctx = LocalContext.current
    var target by remember { mutableStateOf<PairTarget?>(null) }
    var manual by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var peerMenu by remember { mutableStateOf<LinkPeer?>(null) }
    val notifyPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // 扫码进来：弹出确认。 Opened from a scanned QR code: confirm first.
    LaunchedEffect(s.pendingPair) { s.pendingPair?.let { target = PairTarget(it.name, it.addrs, it.code) } }
    LaunchedEffect(s.pairing) { if (s.pairing is PairState.Done) { target = null; manual = false } }

    SubPage("互联") {
        GroupCard(Modifier.padding(top = 8.dp)) {
            SwitchRow(
                "织文互联", "与同一 Wi-Fi 下的电脑互传文字、图片和文件，端到端加密，不经过任何服务器",
                checked = s.enabled, icon = R.drawable.ic_devices, subtitleMaxLines = 3,
            ) { on ->
                if (on && Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    notifyPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                link.setEnabled(on)
            }
        }
        if (!s.enabled) {
            Hint("开启后，在电脑版织文里选「互联 › 配对手机」，用手机相机扫描二维码即可配对；也可以在这里输入电脑上显示的 6 位配对码。")
            return@SubPage
        }
        if (!s.running) Hint("互联服务没有启动，请检查网络后重新打开开关。")

        GroupTitle("本机")
        GroupCard {
            SettingRow("名称", s.fingerprint.takeIf { it.isNotEmpty() }?.let { "安全码 $it" }, onClick = { renaming = true }) { ValueChevron(s.name) }
            RowDivider(false)
            SwitchRow("同步剪贴板", "在手机上复制的文字自动出现在电脑上，反之亦然", checked = s.clipSync, subtitleMaxLines = 2) { link.setClipSync(it) }
        }

        GroupTitle("我的设备")
        GroupCard {
            if (s.trusted.isEmpty()) {
                SettingRow("还没有配对的设备", "在电脑上打开织文 › 互联 › 配对手机", subtitleMaxLines = 2)
            }
            s.trusted.forEachIndexed { i, p ->
                if (i > 0) RowDivider(false)
                SettingRow(
                    p.name.ifEmpty { platformName(p.platform) },
                    when { p.connected -> "已连接 · " + platformName(p.platform); p.nearby -> "在附近，正在连接…"; else -> "不在线" },
                    onClick = { peerMenu = p },
                ) { Chevron() }
            }
        }

        val nearby = s.nearby
        GroupTitle("附近的设备")
        GroupCard {
            if (nearby.isEmpty()) {
                SettingRow("正在查找…", "电脑需要打开织文并连接同一个 Wi-Fi", subtitleMaxLines = 2) {
                    CircularProgressIndicator(Modifier.padding(4.dp).height(20.dp), strokeWidth = 2.dp)
                }
            }
            nearby.forEachIndexed { i, n: LinkNearby ->
                if (i > 0) RowDivider(false)
                SettingRow(n.name.ifEmpty { platformName(n.platform) }, platformName(n.platform) + " · 点按输入配对码", onClick = { target = PairTarget(n.name, n.addrs) }) {
                    Text("配对", color = MaterialTheme.colorScheme.primary)
                }
            }
            RowDivider(false)
            SettingRow("用地址配对", "找不到设备时（例如公司网络），输入电脑上显示的地址和配对码", subtitleMaxLines = 2, onClick = { manual = true }) { Chevron() }
        }

        if (s.transfers.isNotEmpty()) {
            GroupTitle("最近传输")
            GroupCard { s.transfers.take(6).forEachIndexed { i, t -> if (i > 0) RowDivider(false); TransferRow(t) } }
        }
        Hint("收到的文件保存在「下载/WeaveText」。在其他应用里选「分享 › 织文 发送到电脑」即可把照片和文件发到电脑。")
    }

    target?.let { t ->
        PairDialog(t.name, t.code, s.pairing, onDismiss = { target = null; link.offerPair(null); link.resetPairing() }) { code -> link.pair(t.addrs, code) }
    }
    if (manual) ManualPairDialog(s.pairing, onDismiss = { manual = false; link.resetPairing() }) { addr, code -> link.pair(listOf(addr), code) }
    if (renaming) RenameDialog(s.name, onDismiss = { renaming = false }) { renaming = false; link.rename(it) }
    peerMenu?.let { p ->
        AlertDialog(
            onDismissRequest = { peerMenu = null },
            title = { Text(p.name.ifEmpty { platformName(p.platform) }) },
            text = { Text(if (p.connected) "已连接。可以从键盘工具栏或任意应用的「分享」把内容发给它。" else "暂时不在线。电脑打开织文并连上同一个 Wi-Fi 后会自动重连。") },
            confirmButton = { TextButton(onClick = { peerMenu = null; link.forget(p.id) }) { Text("取消配对", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { peerMenu = null }) { Text("关闭") } },
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
    )
}

@Composable
private fun TransferRow(t: LinkTransfer) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(t.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
        val dir = if (t.incoming) "收到" else "发送"
        val status = when (t.state) {
            LinkTransfer.State.RUNNING -> "${dir}中 · ${size(t.done)} / ${size(t.size)}"
            LinkTransfer.State.DONE -> "已$dir · ${size(t.size)}"
            LinkTransfer.State.FAILED -> "${dir}失败"
        }
        Text(status, style = MaterialTheme.typography.bodyMedium, color = if (t.state == LinkTransfer.State.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (t.state == LinkTransfer.State.RUNNING) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { t.fraction }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PairStatus(state: PairState) {
    when (state) {
        PairState.Working -> Row2 { CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp); Text("  正在配对…") }
        is PairState.Failed -> Text(state.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        else -> {}
    }
}

@Composable
private fun Row2(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { content() }
}

@Composable
private fun PairDialog(name: String, preset: String, state: PairState, onDismiss: () -> Unit, onPair: (String) -> Unit) {
    var code by remember(preset) { mutableStateOf(preset) }
    val fromQr = preset.isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("与「${name.ifEmpty { "电脑" }}」配对") },
        text = {
            Column {
                Text(if (fromQr) "确认电脑上显示的配对码与下面一致，再点「配对」。" else "输入电脑上显示的 6 位配对码。", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    code, { v -> code = v.filter(Char::isDigit).take(6) }, singleLine = true, label = { Text("配对码") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), readOnly = fromQr,
                )
                Spacer(Modifier.height(8.dp))
                PairStatus(state)
            }
        },
        confirmButton = { TextButton(enabled = code.length == 6 && state != PairState.Working, onClick = { onPair(code) }) { Text("配对") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ManualPairDialog(state: PairState, onDismiss: () -> Unit, onPair: (String, String) -> Unit) {
    var addr by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val full = if (addr.contains(':')) addr.trim() else addr.trim() + ":" + com.weavetext.ime.link.LinkPorts.DEFAULT
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("用地址配对") },
        text = {
            Column {
                Text("电脑上的配对窗口会显示地址（如 192.168.1.8）和配对码。", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(addr, { addr = it.trim() }, singleLine = true, label = { Text("地址") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    code, { v -> code = v.filter(Char::isDigit).take(6) }, singleLine = true, label = { Text("配对码") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                Spacer(Modifier.height(8.dp))
                PairStatus(state)
            }
        },
        confirmButton = { TextButton(enabled = addr.isNotBlank() && code.length == 6 && state != PairState.Working, onClick = { onPair(full, code) }) { Text("配对") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本机名称") },
        text = { OutlinedTextField(name, { name = it.take(40) }, singleLine = true, label = { Text("电脑上看到的名字") }) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
