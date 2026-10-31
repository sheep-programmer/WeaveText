package com.weavetext.ime.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.weavetext.ime.core.EngineHolder
import com.weavetext.ime.core.QuickPhrase
import com.weavetext.ime.core.QuickPhrases
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 快捷短语：同一输入码可以有多条，按权重排；可导入导出。修改时先加新的，成功后才删旧的。
 * Quick phrases: several texts per code, ordered by weight, with import and export. An edit adds the new entry and
 * removes the old one only after the add succeeds.
 */
@Composable
fun PersonalShortcutsRow() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val phrases = remember { QuickPhrases.of(ctx) }
    var showing by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf(emptyList<QuickPhrase>()) }
    var editing by remember { mutableStateOf<QuickPhrase?>(null) }
    var code by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    fun refresh() { scope.launch { items = withContext(Dispatchers.IO) { phrases.list() } } }
    fun clearForm() { editing = null; code = ""; text = ""; weight = "" }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val body = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            }
            if (body == null) { message = "无法读取文件"; return@launch }
            val r = withContext(Dispatchers.IO) { phrases.import(body) }
            message = "导入 ${r.added} 条，跳过 ${r.skipped} 条（格式不对或重复）"
            refresh()
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val body = withContext(Dispatchers.IO) { phrases.export() }
            val ok = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(body.toByteArray()) } != null }.getOrDefault(false)
            }
            message = if (ok) "已导出 ${body.lines().count { it.isNotBlank() }} 条" else "导出失败"
        }
    }

    SettingRow("快捷短语与模板", "同一输入码可设多条，按权重排；{date} 自动填日期", subtitleMaxLines = 2,
        onClick = { showing = true; message = ""; refresh() }) { Chevron() }
    if (showing) AlertDialog(
        onDismissRequest = { showing = false; clearForm() },
        title = { Text("快捷短语") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("输入码为 1–32 个小写字母。点已有短语可修改；权重 1–999，留空为 10，越大越靠前。")
            items.forEach { item ->
                TextButton(onClick = { editing = item; code = item.code; text = item.text; weight = item.weight.toString() }) {
                    Text("${item.code} · ${item.weight} · ${item.text.take(30)}")
                }
            }
            OutlinedTextField(code, { code = it.lowercase().filter { c -> c in 'a'..'z' }.take(32) }, label = { Text("输入码，例如 dz") }, singleLine = true)
            OutlinedTextField(text, { text = it }, label = { Text("内容，可使用 {date}") }, maxLines = 5)
            OutlinedTextField(weight, { weight = it.filter(Char::isDigit).take(3) }, label = { Text("权重（1–999）") }, singleLine = true)
            Row {
                TextButton(onClick = { importer.launch(arrayOf("text/plain", "application/octet-stream")) }) { Text("导入") }
                TextButton(onClick = { exporter.launch("weavetext-quick-phrases.txt") }) { Text("导出") }
            }
            if (message.isNotEmpty()) Text(message)
        } },
        confirmButton = { TextButton(onClick = {
            val value = weight.toIntOrNull()
            scope.launch {
                val old = editing
                val error: String? = withContext(Dispatchers.IO) {
                    when {
                        old != null && old.code == code && old.text == text -> phrases.setWeight(code, text, value ?: 10)
                        // 新条目加成功后才删旧的，失败时旧条目保留。
                        old != null -> phrases.add(code, text, value) ?: run { phrases.remove(old.code, old.text); null }
                        else -> phrases.add(code, text, value)
                    }
                }
                message = error ?: "已保存"
                if (error == null) clearForm()
                refresh()
            }
        }) { Text("保存") } },
        dismissButton = { Row {
            if (editing != null) TextButton(onClick = {
                val old = editing ?: return@TextButton
                scope.launch {
                    withContext(Dispatchers.IO) { phrases.remove(old.code, old.text) }
                    clearForm(); message = "已删除"; refresh()
                }
            }) { Text("删除") }
            TextButton(onClick = { showing = false; clearForm() }) { Text("关闭") }
        } },
    )
}

@Composable
fun ClearHandLearningRow() {
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    var confirm by remember {mutableStateOf(false)}
    var message by remember {mutableStateOf("")}
    SettingRow("清空个人手写字形",message.ifEmpty {"恢复内置识别，也取消手写候选的固定与降权"},subtitleMaxLines=2,onClick={confirm=true}) {Chevron()}
    if(confirm) AlertDialog(onDismissRequest={confirm=false},title={Text("清空个人手写字形？")},text={Text("此前纠正后记住的字形将被清空。")},confirmButton={TextButton(onClick={confirm=false;scope.launch {
        val response=withContext(Dispatchers.IO) {EngineHolder.getBlocking(ctx)?.features("""{"op":"clearHand"}""")}
        message=if(runCatching {JSONObject(response.orEmpty()).optBoolean("ok")}.getOrDefault(false)) "已清空" else "暂未清空，请重试"
    }}){Text("清空")}},dismissButton={TextButton(onClick={confirm=false}){Text("取消")}})
}
