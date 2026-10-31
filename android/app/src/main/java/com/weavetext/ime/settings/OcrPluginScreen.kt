package com.weavetext.ime.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.weavetext.ime.R
import com.weavetext.ime.ocr.OcrPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 拍照识字插件的设置页：可选下载 PP-OCRv5 模型（约 21 MB），装上后工具箱里的「识字」才可用。
 * OCR plugin settings: the optional PP-OCRv5 download (about 21 MB). The toolbox 「识字」 works once installed.
 */
@Composable
fun OcrPluginScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf(OcrPlugin.state(ctx) == OcrPlugin.State.INSTALLED) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var cancel by remember { mutableStateOf<AtomicBoolean?>(null) }
    SubPage("拍照识字") {
        Text(
            "把图片复制到剪贴板，然后在工具箱里点「识字」，识别出的文字会直接输入。整个过程在本机完成，模型只下载一次。",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        GroupCard {
            val running = progress
            when {
                running != null -> {
                    SettingRow("正在下载识字模型", "${(running * 100).toInt()}%", subtitleMaxLines = 2, onClick = { cancel?.set(true) }) {
                        ValueChevron("取消")
                    }
                }
                installed -> {
                    SettingRow("识字模型已安装", "约 21 MB；点击删除以释放空间", subtitleMaxLines = 2, onClick = {
                        OcrPlugin.remove(ctx)
                        installed = false
                        error = null
                    }) { ValueChevron("删除") }
                }
                else -> {
                    SettingRow("下载识字模型", "PP-OCRv5 mobile，约 21 MB，Apache-2.0；下载后离线识别", subtitleMaxLines = 2, icon = R.drawable.ic_settings, onClick = {
                        val flag = AtomicBoolean(false)
                        cancel = flag
                        error = null
                        progress = 0f
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    OcrPlugin.install(ctx, flag) { p -> scope.launch { progress = p } }
                                }
                                installed = true
                            } catch (t: Throwable) {
                                if (!flag.get()) error = t.message ?: "下载失败，请重试"
                            } finally {
                                progress = null
                                cancel = null
                            }
                        }
                    }) { ValueChevron("下载") }
                }
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
    }
}
