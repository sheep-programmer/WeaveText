package com.weavetext.ime.settings

import androidx.compose.foundation.layout.Column
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun PersonalShortcutsRow() {
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    var showing by remember {mutableStateOf(false)}
    var code by remember {mutableStateOf("")}
    var text by remember {mutableStateOf("")}
    var message by remember {mutableStateOf("")}
    var items by remember {mutableStateOf(emptyList<Pair<String,String>>())}
    fun refresh() {scope.launch {
        val response=withContext(Dispatchers.IO) {EngineHolder.getBlocking(ctx)?.features("""{"op":"snippets"}""")}
        val array=runCatching {JSONObject(response.orEmpty()).optJSONArray("items")}.getOrNull()
        items=if(array==null) emptyList() else (0 until array.length()).map { val r=array.getJSONObject(it);r.getString("code") to r.getString("text") }
    }}
    SettingRow("快捷短语与模板","设置输入码，一键插入常用语；{date} 自动填日期",subtitleMaxLines=2,onClick={showing=true;refresh()}) {Chevron()}
    if(showing) AlertDialog(onDismissRequest={showing=false},title={Text("快捷短语")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("输入码最多 24 个字母。点已有短语可修改，清空内容后保存即删除。")
        items.forEach { (c,t)->TextButton(onClick={code=c;text=t}) {Text("$c · ${t.take(30)}")} }
        OutlinedTextField(code,{code=it},label={Text("输入码，例如 dz")},singleLine=true)
        OutlinedTextField(text,{text=it},label={Text("内容，可使用 {date}")},maxLines=5)
        if(message.isNotEmpty())Text(message)
    }},confirmButton={TextButton(onClick={scope.launch {
        val response=withContext(Dispatchers.IO){EngineHolder.getBlocking(ctx)?.features(JSONObject().put("op","setSnippet").put("code",code).put("text",text).toString())}
        val ok=runCatching {JSONObject(response.orEmpty()).optBoolean("ok")}.getOrDefault(false)
        message=if(ok) "已保存" else "输入码或内容不符合要求";if(ok)refresh()
    }}){Text("保存")}},dismissButton={TextButton(onClick={showing=false}){Text("关闭")}})
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
