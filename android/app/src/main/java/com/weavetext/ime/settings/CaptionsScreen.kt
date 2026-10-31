package com.weavetext.ime.settings

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.weavetext.ime.captions.CaptionEvent
import com.weavetext.ime.captions.CaptionSession
import com.weavetext.ime.captions.CaptionsPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 实时字幕页：开始/停止，显示临时文字和定稿句子（带说话人），说话人插件可选下载。
 * Live captions page: start/stop, partial text and final lines with speaker labels; the speaker plugin is optional.
 */
@Composable
fun CaptionsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val main = remember { Handler(Looper.getMainLooper()) }
    var lines by remember { mutableStateOf(listOf<String>()) }
    var partial by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var speakerReady by remember { mutableStateOf(CaptionsPlugin.state(ctx) == CaptionsPlugin.State.INSTALLED) }
    var downloading by remember { mutableStateOf<Float?>(null) }
    val session = remember {
        CaptionSession(ctx) { event ->
            // 识别线程的回调切回主线程更新界面。 Callbacks come from the recognizer thread; hop to the main thread.
            main.post {
                when (event) {
                    is CaptionEvent.Partial -> partial = event.text
                    is CaptionEvent.Line -> {
                        partial = ""
                        lines = (lines + "说话人 ${event.speaker}：${event.text}").takeLast(200)
                    }
                    is CaptionEvent.Error -> { error = event.message; running = false }
                }
            }
        }
    }
    DisposableEffect(session) { onDispose { session.stop() } }
    SubPage("实时字幕") {
        Text(
            "把周围的话实时转成文字。说话人区分需要先下载说话人模型（约 28 MB）；不装也能用，只是所有话都标成说话人 1。",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        GroupCard {
            val d = downloading
            when {
                d != null -> SettingRow("正在下载说话人模型", "${(d * 100).toInt()}%", subtitleMaxLines = 2) { ValueChevron("请稍候") }
                speakerReady -> SettingRow("说话人区分已安装", "约 28 MB；点击删除", subtitleMaxLines = 2, onClick = {
                    CaptionsPlugin.remove(ctx)
                    speakerReady = false
                }) { ValueChevron("删除") }
                else -> SettingRow("下载说话人模型", "3D-Speaker CAM++，Apache-2.0，约 28 MB", subtitleMaxLines = 2, onClick = {
                    downloading = 0f
                    error = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                CaptionsPlugin.install(ctx, AtomicBoolean(false)) { f -> main.post { downloading = f } }
                            }
                            speakerReady = true
                        } catch (t: Throwable) {
                            error = t.message ?: "下载失败，请重试"
                        } finally {
                            downloading = null
                        }
                    }
                }) { ValueChevron("下载") }
            }
        }
        GroupCard {
            SettingRow(if (running) "停止字幕" else "开始字幕", if (running) "正在听" else "需要麦克风权限；语音识别模型需已安装",
                subtitleMaxLines = 2, onClick = {
                    error = null
                    if (running) {
                        session.stop()
                        running = false
                        partial = ""
                    } else {
                        running = session.start()
                    }
                }) { ValueChevron(if (running) "停止" else "开始") }
        }
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        }
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            for (line in lines) Text(line, style = MaterialTheme.typography.bodyLarge)
            if (partial.isNotEmpty()) Text(partial, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
