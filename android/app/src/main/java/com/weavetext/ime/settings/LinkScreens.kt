package com.weavetext.ime.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
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
    // 一次只能有一个权限请求在途，通知与附近设备合并成一次申请。 Only one request may be in flight: ask for both at once.
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.NEARBY_WIFI_DEVICES] == true) link.rescan()
    }
    val directory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            link.setReceiveDirectory(uri.toString())
        }
    }
    var reconnect by remember { mutableStateOf<LinkPeer?>(null) }

    // 扫码进来：弹出确认。 Opened from a scanned QR code: confirm first.
    LaunchedEffect(s.pendingPair) { s.pendingPair?.let { target = PairTarget(it.name, it.addrs, it.code) } }
    LaunchedEffect(s.pairing) { if (s.pairing is PairState.Done) { target = null; manual = false } }

    SubPage("互联") {
        GroupCard(Modifier.padding(top = 8.dp)) {
            SwitchRow(
                "织文互联", "设备间直传文字、图片和文件，支持局域网及可直连的远程地址，端到端加密",
                checked = s.enabled, icon = R.drawable.ic_devices, subtitleMaxLines = 3,
            ) { on ->
                link.setEnabled(on)
                if (on) {
                    val wanted = buildList {
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        if (Build.VERSION.SDK_INT == 36) add(Manifest.permission.NEARBY_WIFI_DEVICES)
                    }.filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
                    if (wanted.isNotEmpty()) perms.launch(wanted.toTypedArray())
                }
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
            SwitchRow("同步剪贴板", "同步文字、图片和文件。手机端在输入法可读取剪贴板时检测复制内容", checked = s.clipSync, subtitleMaxLines = 3) { link.setClipSync(it) }
            RowDivider(false)
            SettingRow("系统授权", "管理附近设备和通知权限；文件目录通过选择器单独授权", subtitleMaxLines = 2, onClick = { ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))) }) { Chevron() }
            RowDivider(false)
            SettingRow("接收目录", if (s.receiveDirectory.isEmpty()) "下载目录（默认）" else Uri.decode(s.receiveDirectory.substringAfterLast('/')), onClick = { directory.launch(s.receiveDirectory.takeIf { it.isNotEmpty() }?.let(Uri::parse)) }) { Chevron() }
            if (s.receiveDirectory.isNotEmpty()) SettingRow("恢复默认下载目录", onClick = { link.setReceiveDirectory("") }) { Chevron() }
        }

        GroupTitle("发送与接收")
        GroupCard {
            SettingRow("发送内容", "选择文字、剪贴板、图片或文件，再选择接收设备", subtitleMaxLines = 2, onClick = { ctx.startActivity(Intent(ctx, com.weavetext.ime.link.SendToComputerActivity::class.java)) }) { Chevron() }
            RowDivider(false)
            SettingRow("生成本机配对码", "让另一台设备输入本机地址和此配对码", onClick = { link.openPairing() }) { Chevron() }
        }
        if (s.pairingCode.isNotEmpty()) Hint("配对码 ${s.pairingCode}（2 分钟有效）\n本机地址：${s.addrs.joinToString(" · ")}")
        s.serviceError?.let { Hint(it) }
        s.syncMessage?.let { Hint(it) }
        if (s.connected.isNotEmpty()) GroupCard {
            s.connected.forEach {peer->SettingRow("发送个人词库到 ${peer.name}","包含常用词、候选偏好与快捷短语；对方接收后手动合并",subtitleMaxLines=2,onClick={link.sendPersonal(peer.id)}) {Chevron()} }
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
                SettingRow(if (s.discovery == "searching") "正在查找…" else "未发现附近设备", s.discoveryError ?: "两端需开启互联；访客 Wi-Fi 或路由器的设备隔离可能阻止发现", subtitleMaxLines = 3) {
                    if (s.discovery == "searching") CircularProgressIndicator(Modifier.padding(4.dp).height(20.dp), strokeWidth = 2.dp)
                }
            }
            nearby.forEachIndexed { i, n: LinkNearby ->
                if (i > 0) RowDivider(false)
                SettingRow(n.name.ifEmpty { platformName(n.platform) }, platformName(n.platform) + " · 点按输入配对码", onClick = { target = PairTarget(n.name, n.addrs) }) {
                    Text("配对", color = MaterialTheme.colorScheme.primary)
                }
            }
            RowDivider(false)
            SettingRow("重新扫描", onClick = { link.rescan() }) { Chevron() }
            RowDivider(false)
            SettingRow("用地址配对", "支持 IPv4:端口 或 [IPv6]:端口，也可输入远程直接地址", subtitleMaxLines = 2, onClick = { manual = true }) { Chevron() }
        }

        if (s.transfers.isNotEmpty()) {
            GroupTitle("最近传输")
            GroupCard { s.transfers.take(6).forEachIndexed { i, t -> if (i > 0) RowDivider(false); TransferRow(t, link) } }
        }
        Hint("长按图片或文件后选择系统「分享 › WeaveText · 发送到设备」。无需授予所有文件访问权限。远程直传需要公网 IPv6、IPv4 端口映射或直连 VPN；两端都在运营商 NAT 后时，无法保证无服务器直连。")
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
            text = { Text(if (p.connected) "已连接。可以从收发页面或任意应用的「分享」发送内容。" else "暂时不在线。可以更新直接地址并重新连接。") },
            confirmButton = { TextButton(onClick = { peerMenu = null; reconnect = p }) { Text("直接地址重连") } },
            dismissButton = { TextButton(onClick = { peerMenu = null }) { Text("关闭") } },
        )
    }
    reconnect?.let { p ->
        var addr by remember(p.id) { mutableStateOf(p.addrs.firstOrNull().orEmpty()) }
        AlertDialog(onDismissRequest = { reconnect = null }, title = { Text("连接 ${p.name}") }, text = {
            Column {
                OutlinedTextField(addr, { addr = it }, label = { Text("IPv4:端口 或 [IPv6]:端口") }, singleLine = true)
                TextButton(onClick = { link.forget(p.id); reconnect = null }) { Text("取消配对", color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = { link.connect(p.id, listOf(com.weavetext.ime.link.LinkAddress.normalize(addr))); reconnect = null }) { Text("连接") } }, dismissButton = { TextButton(onClick = { reconnect = null }) { Text("取消") } })
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
private fun TransferRow(t: LinkTransfer, link: LinkController) {
    val ctx = LocalContext.current
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
        if (t.incoming && t.path != null) {
            if (t.mime==com.weavetext.ime.link.LinkManager.PERSONAL_MIME) {
                TextButton(onClick={link.importPersonal(t.id)}) {Text("合并个人资料")}
                return@Column
            }
            if (t.state == LinkTransfer.State.FAILED) TextButton(onClick = { link.retrySave(t.id) }) { Text("重新保存") }
            TextButton(onClick = {
                val uri = if (t.path.startsWith('/')) androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", java.io.File(t.path)) else Uri.parse(t.path)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = t.mime; putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = android.content.ClipData.newUri(ctx.contentResolver, t.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx.startActivity(Intent.createChooser(intent, "转发 ${t.name}"))
            }) { Text("打开分享") }
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
    val full = com.weavetext.ime.link.LinkAddress.normalize(addr)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("用地址配对") },
        text = {
            Column {
                Text("输入对方显示的地址及配对码。远程地址必须允许直连其监听端口。", style = MaterialTheme.typography.bodyMedium)
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
