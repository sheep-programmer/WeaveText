package com.weavetext.ime.link

import android.content.ClipboardManager
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.weavetext.ime.settings.SettingsActivity
import com.weavetext.ime.settings.WeaveSettingsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class Outgoing {
    data class Text(val text: String, val clip: Boolean = false) : Outgoing()
    data class Files(val uris: List<Uri>, val clip: Boolean = false) : Outgoing()
    companion object {
        @Suppress("DEPRECATION")
        fun from(i: Intent): Outgoing? {
            val streams: List<Uri> = when (i.action) {
                Intent.ACTION_SEND -> listOfNotNull(if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM))
                Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty()
                else -> emptyList()
            }
            val uris = streams.ifEmpty {
                if (i.action == Intent.ACTION_SEND || i.action == Intent.ACTION_SEND_MULTIPLE) {
                    val clip = i.clipData
                    if (clip == null) emptyList() else (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                } else emptyList()
            }
            if (uris.isNotEmpty()) return Files(uris)
            return i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let { Text(it) }
        }
    }
}

/** System share target and standalone sender use the same composer. */
class SendToComputerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = LinkManager.get(this); link.ensureRunning()
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        setContent { WeaveSettingsTheme(dark) {
            SendComposer(link, Outgoing.from(intent), ::finish, {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("weavetext://settings/link"), this, SettingsActivity::class.java)); finish()
            }) { to, what, done ->
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            when (what) {
                                is Outgoing.Text -> link.sendText(to, what.text, what.clip)
                                is Outgoing.Files -> what.uris.map { LinkContent.send(this@SendToComputerActivity, link, to, it, what.clip) }.all { it }
                            }
                        }
                    }
                    done(result.fold({ if (it) "传输已开始，请在互联页查看完成状态" else "发送失败，请检查设备连接后重试" }, { it.message ?: "文件授权失效，请重新分享" }))
                }
            }
        } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SendComposer(link: LinkController, incoming: Outgoing?, close: () -> Unit, settings: () -> Unit,
    send: (String, Outgoing, (String) -> Unit) -> Unit) {
    val s by link.state.collectAsState()
    val ctx = LocalContext.current
    var mode by remember { mutableStateOf(if (incoming is Outgoing.Files) "文件" else "文字") }
    var text by remember { mutableStateOf((incoming as? Outgoing.Text)?.text.orEmpty()) }
    var uris by remember { mutableStateOf((incoming as? Outgoing.Files)?.uris.orEmpty()) }
    var target by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris = it; result = null }
    fun readClipboard() {
        val clip = ctx.getSystemService(ClipboardManager::class.java)?.primaryClip
        if (clip?.description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true) { result = "此剪贴板内容被标记为敏感"; text = ""; uris = emptyList(); return }
        if (clip == null || clip.itemCount == 0) { result = "剪贴板为空"; text = ""; uris = emptyList(); return }
        uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        text = if (uris.isEmpty()) clip.getItemAt(0).text?.toString().orEmpty() else ""
        result = null
    }
    LaunchedEffect(s.connected.map { it.id }) { if (s.connected.none { it.id == target }) target = s.connected.firstOrNull()?.id.orEmpty() }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text("WeaveText · 发送到设备") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!s.enabled) Text("先开启互联并配对设备")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("文字", "剪贴板", "图片", "文件").forEach { label -> FilterChip(selected = mode == label, onClick = { mode = label; result = null; if (label == "剪贴板") readClipboard() }, label = { Text(label) }, enabled = !busy) }
            }
            if (mode == "剪贴板") {
                TextButton(enabled = !busy, onClick = { readClipboard() }) { Text("刷新剪贴板") }
                Text(if (uris.isNotEmpty()) "当前剪贴板：${uris.size} 个图片或文件" else text.ifEmpty { "剪贴板为空" }, maxLines = 4)
            }
            if (mode == "文字") OutlinedTextField(text, { text = it; result = null }, label = { Text("要发送的文字") }, modifier = Modifier.fillMaxWidth(), enabled = !busy, maxLines = 5)
            else if (mode != "剪贴板") {
                TextButton(enabled = !busy, onClick = { picker.launch(arrayOf(if (mode == "图片") "image/*" else "*/*")) }) { Text(if (mode == "图片") "选择图片…" else "选择文件…") }
                Text(if (uris.isEmpty()) "还未选择内容" else "已选择 ${uris.size} 项", style = MaterialTheme.typography.bodySmall)
            }
            Text("接收设备")
            s.connected.forEach { p -> FilterChip(selected = target == p.id, onClick = { target = p.id }, label = { Text(p.name) }, enabled = !busy) }
            if (s.connected.isEmpty()) Text("没有已连接设备，可在设置中配对或用直接地址连接")
            if (busy) Row { CircularProgressIndicator(Modifier.size(20.dp)); Text(" 正在读取并发送…") }
            result?.let { Text(it) }
        }
    }, confirmButton = {
        TextButton(enabled = !busy && target.isNotEmpty() && (if (mode == "文字" || mode == "剪贴板" && uris.isEmpty()) text.isNotBlank() else uris.isNotEmpty()), onClick = {
            busy = true; result = null
            send(target, if (mode == "文字" || mode == "剪贴板" && uris.isEmpty()) Outgoing.Text(text, mode == "剪贴板") else Outgoing.Files(uris, mode == "剪贴板")) { result = it; busy = false }
        }) { Text("发送") }
    }, dismissButton = {
        Row { if (!s.enabled || s.connected.isEmpty()) TextButton(onClick = settings) { Text("互联设置") }; TextButton(enabled = !busy, onClick = close) { Text("关闭") } }
    })
}
