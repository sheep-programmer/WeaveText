package com.weavetext.translate.google

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.UUID

/** Language-pack management works even if the IME app has not been installed. No automatic downloads. */
class MainActivity : Activity() {
    private lateinit var engine: Engine
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var languages: LinearLayout
    private lateinit var mobile: CheckBox
    private val pending = mutableMapOf<String, UiRequest>()
    private var destroyed = false
    private class UiRequest(var call: PluginCall? = null, var finished: Boolean = false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = Engine()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(24), dp(16), dp(12)); setBackgroundColor(Color.WHITE) }
        root.addView(text("织文翻译语言包", 23f))
        root.addView(text("机器翻译由 Google Translate 提供，原文在本插件设备端处理。先手动安装所需语言包；翻译不会自动下载。", 15f))
        root.addView(text("英语无需额外语言包。其他官方语言包安装到本插件，卸载插件会移除其应用数据。Google SDK 并非开源；模型下载及使用/性能信息可能联网。", 13f))
        root.addView(button("Google Translate") {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://translate.google.com/"))) }
                .onFailure { status.text = "没有可打开 Google Translate 的浏览器。" }
        })
        val prefs = getSharedPreferences("plugin_settings", MODE_PRIVATE)
        mobile = CheckBox(this).apply {
            text = "允许移动网络下载（默认仅 Wi-Fi）"
            isChecked = prefs.getBoolean("model_metered_allowed", false)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("model_metered_allowed", checked).apply() }
        }
        root.addView(mobile)
        val actions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        actions.addView(button("刷新语言包") { refresh() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(button("取消本页请求") { cancelPending() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(actions)
        status = text("正在读取已安装语言包…", 14f)
        root.addView(status)
        progress = ProgressBar(this).apply { isIndeterminate = true; visibility = android.view.View.GONE }
        root.addView(progress)
        languages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(languages) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        refresh()
    }

    private fun text(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.rgb(30, 33, 38)); setPadding(0, dp(5), 0, dp(5))
    }
    private fun button(value: String, action: () -> Unit) = Button(this).apply { text = value; setOnClickListener { action() } }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun refresh() = request(JSONObject().put("op", "languages"))

    private fun request(command: JSONObject) {
        val id = UUID.randomUUID().toString()
        val node = UiRequest()
        pending[id] = node
        progress.visibility = android.view.View.VISIBLE
        node.call = engine.request(command.toString()) { event -> runOnUiThread {
            if (destroyed || node.finished || pending[id] !== node) return@runOnUiThread
            val data = JSONObject(event.encode())
            when (data.getString("type")) {
                "progress" -> status.text = data.getString("message")
                "languages" -> { showLanguages(data); status.text = "语言包列表已更新；请选择下载或删除。" }
                "downloaded" -> status.text = "${languageLabel(data.getString("language"))} 语言包已可用"
                "deleted" -> status.text = "${languageLabel(data.getString("language"))} 语言包已删除"
                "error" -> status.text = data.getString("message")
            }
            if (event.terminal) {
                node.finished = true; pending.remove(id)
                progress.visibility = if (pending.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
                if (data.getString("type") in listOf("downloaded", "deleted")) refresh()
            }
        } }
        if (node.finished) node.call?.cancel()
    }

    private fun showLanguages(event: JSONObject) {
        languages.removeAllViews()
        val list = event.getJSONArray("languages")
        for (index in 0 until list.length()) {
            val item = list.getJSONObject(index)
            val code = item.getString("code")
            val label = item.getString("label")
            val installed = item.getBoolean("installed")
            val builtin = item.optBoolean("builtin")
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
            row.addView(text("$label ($code)\n${if (builtin) "无需额外语言包" else if (installed) "已安装" else "未安装"}", 15f), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(button(if (builtin) "已可用" else if (installed) "删除" else "下载") {
                if (installed) AlertDialog.Builder(this).setTitle("删除 $label 语言包？")
                    .setMessage("删除后，相关语言的翻译会提示缺包，直到再次手动安装。")
                    .setPositiveButton("删除") { _, _ -> request(JSONObject().put("op", "delete").put("language", code)) }
                    .setNegativeButton("取消", null).show()
                else request(JSONObject().put("op", "download").put("language", code).put("wifiOnly", !mobile.isChecked))
            }.apply { isEnabled = !builtin }, LinearLayout.LayoutParams(dp(82), dp(48)))
            languages.addView(row)
        }
    }

    private fun cancelPending() {
        pending.values.toList().forEach { it.finished = true; it.call?.cancel() }
        pending.clear()
        progress.visibility = android.view.View.GONE
        status.text = "已取消本页请求；SDK 后台语言包下载可能继续，请稍后刷新查看。"
    }

    override fun onDestroy() {
        destroyed = true
        cancelPending()
        engine.close()
        super.onDestroy()
    }
}
