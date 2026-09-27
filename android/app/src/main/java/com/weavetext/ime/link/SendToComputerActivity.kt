package com.weavetext.ime.link

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.weavetext.ime.settings.SettingsActivity
import com.weavetext.ime.settings.WeaveSettingsTheme
import kotlinx.coroutines.delay

/** 要发送的内容。 What is being sent. */
sealed class Outgoing {
    data class Text(val text: String) : Outgoing()
    data class Files(val uris: List<Uri>) : Outgoing()

    companion object {
        fun from(i: Intent): Outgoing? {
            val streams: List<Uri> = when (i.action) {
                Intent.ACTION_SEND -> listOfNotNull(stream(i))
                Intent.ACTION_SEND_MULTIPLE -> streams(i)
                else -> emptyList()
            }
            if (streams.isNotEmpty()) return Files(streams)
            val text = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() } ?: return null
            return Text(text)
        }

        @Suppress("DEPRECATION")
        private fun stream(i: Intent): Uri? =
            if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra(Intent.EXTRA_STREAM)

        @Suppress("DEPRECATION")
        private fun streams(i: Intent): List<Uri> =
            (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
    }
}

/**
 * 系统分享的目标「发送到电脑」：把文字、照片或文件发给已连接的电脑。
 * The share target "send to computer": sends text, photos or files to a connected computer.
 */
class SendToComputerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val what = Outgoing.from(intent)
        if (what == null) { finish(); return }
        val link = LinkManager.get(this)
        link.ensureRunning()
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        setContent { WeaveSettingsTheme(dark) { SendDialog(link, what, onDone = ::finish, onOpenSettings = ::openSettings) { to -> send(link, to, what) } } }
    }

    private fun openSettings() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("weavetext://settings/link"), this, SettingsActivity::class.java))
        finish()
    }

    private fun send(link: LinkController, to: String, what: Outgoing): Boolean = when (what) {
        is Outgoing.Text -> link.sendText(to, what.text, clip = false)
        is Outgoing.Files -> what.uris.map { u ->
            val (name, mime) = describe(u)
            val fd = runCatching { contentResolver.openFileDescriptor(u, "r")?.detachFd() }.getOrNull()
            fd != null && link.sendFd(to, fd, name, mime)
        }.all { it }
    }

    private fun describe(u: Uri): Pair<String, String> {
        val mime = contentResolver.getType(u) ?: "application/octet-stream"
        val name = runCatching {
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: u.lastPathSegment?.substringAfterLast('/') ?: "file"
        return name to mime
    }
}

@Composable
private fun SendDialog(link: LinkController, what: Outgoing, onDone: () -> Unit, onOpenSettings: () -> Unit, send: (String) -> Boolean) {
    val s by link.state.collectAsState()
    var waited by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    // 刚启动时给连接几秒时间。 Give a fresh start a few seconds to connect.
    LaunchedEffect(Unit) { delay(4000); waited = true }
    val connected = s.connected
    val label = when (what) {
        is Outgoing.Text -> "这段文字"
        is Outgoing.Files -> if (what.uris.size == 1) "这个文件" else "${what.uris.size} 个文件"
    }
    LaunchedEffect(connected.size, result) {
        if (result == null && connected.size == 1) {
            val p = connected.first()
            result = if (send(p.id)) "已发送到「${p.name}」" else "发送失败"
        }
    }
    LaunchedEffect(result) { if (result?.startsWith("已发送") == true) { delay(1200); onDone() } }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("发送到电脑") },
        text = {
            Column {
                when {
                    result != null -> Text(result!!)
                    !s.enabled -> Text("织文互联还没有开启。开启并配对电脑后，就能把$label 发过去。")
                    connected.size > 1 -> {
                        Text("选择要发送$label 的设备：")
                        connected.forEach { p ->
                            Text(
                                p.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth().clickable { result = if (send(p.id)) "已发送到「${p.name}」" else "发送失败" }.padding(vertical = 12.dp),
                            )
                        }
                    }
                    !waited -> androidx.compose.foundation.layout.Row { CircularProgressIndicator(Modifier.padding(end = 12.dp)); Text("正在连接电脑…") }
                    else -> Text(if (s.trusted.isEmpty()) "还没有配对的电脑。" else "已配对的电脑不在线：请在电脑上打开织文，并连接同一个 Wi-Fi。")
                }
            }
        },
        confirmButton = {
            if (result == null && (!s.enabled || (waited && connected.isEmpty()))) TextButton(onClick = onOpenSettings) { Text("打开互联设置") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(if (result != null) "完成" else "取消") } },
    )
}
