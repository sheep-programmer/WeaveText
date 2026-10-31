package com.weavetext.ime.translate

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.weavetext.ime.settings.WeaveSettingsTheme
import com.weavetext.ime.settings.WeavePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** 系统文字选择菜单里的「翻译」：显示原文，点击后翻译，可复制或替换选中文字。 */
class SelectionTranslateActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incoming = SelectionTranslation.incoming(intent)
        val prefs = WeavePrefs.of(this)
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        setContent { WeaveSettingsTheme(dark) {
            var translation by remember { mutableStateOf<String?>(null) }
            var status by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(incoming.text, style = MaterialTheme.typography.bodyLarge)
                translation?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy && incoming.text.isNotBlank(), onClick = {
                        busy = true; status = "正在翻译…"
                        scope.launch {
                            val service = TranslationServices.from(this@SelectionTranslateActivity, prefs)
                            val result = withContext(Dispatchers.Main) { translateOnce(service, SelectionTranslation.request(prefs, incoming.text)) }
                            busy = false
                            when (result) {
                                is TranslationResult.Success -> { translation = result.text; status = "" }
                                is TranslationResult.Failure -> status = result.error.message
                            }
                        }
                    }) { Text("翻译") }
                    translation?.let { text ->
                        OutlinedButton(onClick = { copy(text) }) { Text("复制译文") }
                        if (!incoming.readOnly) OutlinedButton(onClick = {
                            setResult(Activity.RESULT_OK, SelectionTranslation.replacementResult(text))
                            finish()
                        }) { Text("替换选中文字") }
                    }
                    OutlinedButton(onClick = { finish() }) { Text("关闭") }
                }
            }
        } }
    }

    private fun copy(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("译文", text))
        Unit
    }

    /** 服务是回调式的，这里包成一次挂起结果，便于在界面上等待。 Wrap the callback service as one suspended result. */
    private suspend fun translateOnce(service: TranslationService, request: TranslationRequest): TranslationResult =
        suspendCancellableCoroutine { cont ->
            request.validationError()?.let { cont.resume(TranslationResult.Failure(it)); return@suspendCancellableCoroutine }
            val call = service.translate(request) { cont.resume(it) }
            cont.invokeOnCancellation { call.cancel() }
        }
}
