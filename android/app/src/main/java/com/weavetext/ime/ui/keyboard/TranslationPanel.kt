package com.weavetext.ime.ui.keyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.ImageView
import com.weavetext.ime.ime.InputController
import com.weavetext.ime.settings.TranslationSettings
import com.weavetext.ime.translate.*
import kotlin.math.roundToInt

/** Translation uses committed selections or an explicit paste, never an IME-local EditText. */
class TranslationPanel(
    kb: WeaveKeyboard,
    editor: TranslationEditor? = null,
    private val serviceProvider: () -> TranslationService = { TranslationServices.from(kb.ctx, kb.prefs) },
    toolbarIndex: Int = -1,
) : KbPanel(kb) {
    override val full = true
    override val toolIndex = toolbarIndex
    private val editor = editor ?: ControllerTranslationEditor(kb.controller)
    private val panel = TranslationLayout(kb.ctx)
    override val view: View = panel
    private var call: TranslationCall? = null
    private var generation = 0L

    init {
        panel.onTranslate = { translate() }
        panel.onCancel = { cancelTranslation() }
        panel.onClose = { kb.closePanel() }
        panel.onSettings = { kb.openSettings("translation") }
        panel.onRead = { readSelection() }
        panel.onPaste = { paste() }
        panel.onWeb = {
            if (!kb.controller.isSensitiveField) {
                cancelTranslation()
                panel.clearResult()
                val request = TranslationRequest(panel.sourceLanguage(), panel.targetLanguage(), panel.sourceText())
                runCatching {
                    val url = TranslationWeb.google(request)
                    kb.ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    panel.showStatus("请在官方网页复制译文，再返回粘贴")
                }.onFailure { panel.showStatus(if (it is IllegalArgumentException) it.message.orEmpty() else "无法打开网页翻译，请检查浏览器", true) }
            }
        }
        panel.onCopy = {
            if (!kb.controller.isSensitiveField && panel.resultText().isNotBlank()) {
                clipboard()?.setPrimaryClip(ClipData.newPlainText("译文", panel.resultText()))
                panel.showStatus("已复制译文")
            }
        }
        panel.onReplace = { commit(true) }
        panel.onInsert = { commit(false) }
        panel.onLanguagesChanged = { source, target ->
            cancelTranslation()
            panel.clearResult()
            kb.prefs.edit().putString(TranslationSettings.SOURCE_LANGUAGE, source)
                .putString(TranslationSettings.TARGET_LANGUAGE, target).apply()
        }
        panel.onManage = {
            cancelTranslation()
            if (!OfflineTranslationPlugin.openManager(kb.ctx)) kb.openSettings("translation")
        }
    }

    override fun applyTheme() { kb.paintBackground(panel); panel.applyPalette() }
    override fun onShow() {
        cancelTranslation()
        val source = if (kb.controller.isSensitiveField) "" else editor.selectedText().orEmpty()
        panel.reset(source,
            TranslationSettings.sourceLanguage(kb.prefs),
            kb.prefs.getString(TranslationSettings.TARGET_LANGUAGE, "en") ?: "en")
    }
    override fun onHide() { cancelTranslation(); panel.clearFocus() }

    private fun clipboard() = kb.ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    private fun readSelection() {
        if (kb.controller.isSensitiveField) return
        cancelTranslation()
        panel.clearResult()
        val selected = editor.selectedText().orEmpty()
        if (selected.isBlank()) { panel.showStatus("请先在输入框中选择已输入的文字", true); return }
        panel.setSource(selected)
    }
    private fun paste() {
        if (kb.controller.isSensitiveField) return
        val text = runCatching { clipboard()?.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull().orEmpty()
        if (text.isBlank()) { panel.showStatus("剪贴板中没有文字", true); return }
        cancelTranslation()
        panel.setSource(text)
    }
    private fun translate() {
        if (kb.controller.isSensitiveField) { panel.showStatus("此输入框不可使用翻译", true); return }
        if (TranslationSettings.protocol(kb.prefs) == TranslationProtocol.GOOGLE_WEB) { panel.onWeb(); return }
        val request = TranslationRequest(panel.sourceLanguage(), panel.targetLanguage(), panel.sourceText())
        request.validationError()?.let { panel.showStatus(it.message, true); return }
        cancelTranslation()
        val current = generation
        panel.clearResult()
        panel.setBusy(true)
        call = runCatching {
            val service = serviceProvider()
            val complete: (TranslationResult) -> Unit = { result -> panel.post {
                if (generation != current) return@post
                call = null
                panel.setBusy(false)
                when (result) {
                    is TranslationResult.Success -> panel.showResult(result.text)
                    is TranslationResult.Failure -> panel.showStatus(result.error.message, true)
                }
            } }
            if (service is TranslationProgressService) service.translate(request, { progress ->
                panel.post { if (generation == current) panel.showStatus(progress.message) }
            }, complete) else service.translate(request, complete)
        }.getOrElse { panel.setBusy(false); panel.showStatus("翻译服务不可用，请检查设置", true); null }
    }
    private fun cancelTranslation() {
        generation++
        call?.cancel(); call = null
        if (panel.busy) { panel.setBusy(false); panel.showStatus("已取消翻译") }
    }
    private fun commit(replace: Boolean) {
        val text = panel.resultText()
        if (text.isBlank() || kb.controller.isSensitiveField) return
        val ok = runCatching { if (replace) editor.replaceSelection(text) else editor.insert(text) }.getOrDefault(false)
        if (ok) kb.closePanel()
        else panel.showStatus("输入位置已改变或无法写入，请复制译文后粘贴", true)
    }

    private class ControllerTranslationEditor(private val controller: InputController) : TranslationEditor {
        private var target: InputController.TranslationTarget? = null
        override fun selectedText(): String? {
            target = controller.captureTranslationTarget()
            return target?.selectedText
        }
        override fun replaceSelection(text: String) = target?.let { controller.writeTranslation(it, text, true) } ?: false
        override fun insert(text: String) = target?.let { controller.writeTranslation(it, text, false) } ?: false
    }

    private inner class TranslationLayout(ctx: Context) : LinearLayout(ctx) {
        private val source = TextView(ctx)
        private val result = TextView(ctx)
        private val status = TextView(ctx)
        private val scroll = ScrollView(ctx)
        private val title = TextView(ctx)
        private val attribution = ImageView(ctx)
        private val sourceSpinner = Spinner(ctx)
        private val targetSpinner = Spinner(ctx)
        private val sourceLanguages = TranslationLanguages.all
        private val targetLanguages = TranslationLanguages.all.filter { it.code != TranslationLanguages.AUTO }
        private val buttons = mutableListOf<Button>()
        private lateinit var readButton: Button
        private lateinit var pasteButton: Button
        private lateinit var webButton: Button
        private lateinit var translateButton: Button
        private lateinit var cancelButton: Button
        private lateinit var replaceButton: Button
        private lateinit var insertButton: Button
        private lateinit var copyButton: Button
        private var sourceValue = ""
        private var resultValue = ""
        var busy = false
            private set
        var onTranslate: () -> Unit = {}
        var onCancel: () -> Unit = {}
        var onClose: () -> Unit = {}
        var onSettings: () -> Unit = {}
        var onRead: () -> Unit = {}
        var onPaste: () -> Unit = {}
        var onWeb: () -> Unit = {}
        var onManage: () -> Unit = {}
        var onCopy: () -> Unit = {}
        var onReplace: () -> Unit = {}
        var onInsert: () -> Unit = {}
        var onLanguagesChanged: (String, String) -> Unit = { _, _ -> }

        init {
            orientation = VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(8))
            val header = row()
            title.text = "翻译"; title.textSize = 18f
            header.addView(title, LayoutParams(0, dp(42), 1f)); title.gravity = Gravity.CENTER_VERTICAL
            addButton(header, "设置") { onSettings() }
            addButton(header, "关闭") { onClose() }
            addView(header)
            val content = LinearLayout(ctx).apply { orientation = VERTICAL }
            scroll.addView(content)
            addView(scroll, LayoutParams(-1, 0, 1f))
            val languages = row()
            languages.addView(sourceSpinner, LayoutParams(0, dp(42), 1f))
            languages.addView(TextView(ctx).apply { text = "→"; gravity = Gravity.CENTER }, LayoutParams(dp(28), dp(42)))
            languages.addView(targetSpinner, LayoutParams(0, dp(42), 1f))
            content.addView(languages)
            val inputs = row()
            readButton = addButton(inputs, "读取选区") { onRead() }
            pasteButton = addButton(inputs, "粘贴文字") { onPaste() }
            content.addView(inputs)
            val webActions = row()
            webButton = addButton(webActions, "Google Translate 网页版") { onWeb() }
            addButton(webActions, "离线插件") { onManage() }
            content.addView(webActions)
            source.hint = "先选择已输入的文字，或粘贴要翻译的内容"
            source.setTextIsSelectable(true); source.minHeight = dp(50)
            source.setPadding(dp(10), dp(8), dp(10), dp(8))
            content.addView(source, LayoutParams(-1, -2))
            val actions = row()
            translateButton = addButton(actions, "翻译") { onTranslate() }
            cancelButton = addButton(actions, "取消请求") { onCancel() }
            content.addView(actions)
            status.textSize = 12f; status.setPadding(dp(4), dp(4), dp(4), dp(4))
            content.addView(status, LayoutParams(-1, -2))
            result.setTextIsSelectable(true); result.minHeight = dp(50)
            result.setPadding(dp(10), dp(8), dp(10), dp(8))
            content.addView(result, LayoutParams(-1, -2))
            attribution.setImageResource(com.weavetext.ime.R.drawable.google_translate_attribution)
            attribution.contentDescription = "powered by Google Translate"
            attribution.scaleType = ImageView.ScaleType.FIT_START
            attribution.setOnClickListener {
                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://translate.google.com/")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            addView(attribution, LayoutParams(dp(176), dp(24)).apply { topMargin = dp(6) })
            attribution.visibility = GONE
            val outputs = row()
            replaceButton = addButton(outputs, "替换选区") { onReplace() }
            insertButton = addButton(outputs, "插入") { onInsert() }
            copyButton = addButton(outputs, "复制") { onCopy() }
            addView(outputs)
            sourceSpinner.adapter = adapter(sourceLanguages)
            targetSpinner.adapter = adapter(targetLanguages)
            sourceSpinner.onItemSelectedListener = listener()
            targetSpinner.onItemSelectedListener = listener()
        }
        fun reset(text: String, sourceCode: String, targetCode: String) {
            sourceSpinner.setSelection(sourceLanguages.indexOfFirst { it.code == sourceCode }.coerceAtLeast(0), false)
            targetSpinner.setSelection(targetLanguages.indexOfFirst { it.code == targetCode }.takeIf { it >= 0 } ?: 1, false)
            sourceValue = ""; source.text = ""; clearResult(); setBusy(false)
            if (kb.controller.isSensitiveField) showStatus("此输入框不可使用翻译", true)
            else {
                setSource(text)
                showStatus(if (usesGoogle()) "使用已安装的离线插件和语言包；缺少语言时打开插件管理" else
                    if (TranslationSettings.protocol(kb.prefs) == TranslationProtocol.GOOGLE_WEB) "官方网页无需下载，网页译文请复制后返回粘贴" else
                    if (TranslationSettings.onlineEnabled(kb.prefs)) "选择或粘贴文字后，点击翻译" else "请先在设置中开启并配置翻译服务")
            }
        }
        fun setSource(text: String) {
            if (text.length > TranslationRequest.MAX_TEXT_CHARS) {
                sourceValue = ""; source.text = ""; clearResult()
                showStatus("文字太长，请分段翻译", true); return
            }
            sourceValue = text; source.text = text; clearResult()
        }
        fun sourceText() = sourceValue
        fun sourceLanguage() = sourceLanguages.getOrNull(sourceSpinner.selectedItemPosition)?.code ?: "auto"
        fun targetLanguage() = targetLanguages.getOrNull(targetSpinner.selectedItemPosition)?.code ?: "en"
        fun resultText() = resultValue
        fun clearResult() { resultValue = ""; result.text = "翻译结果"; attribution.visibility = GONE; updateActions() }
        fun setBusy(value: Boolean) {
            busy = value; updateActions()
            if (value) showStatus(if (usesGoogle()) "正在连接离线翻译插件…" else "正在翻译…")
        }
        private fun updateActions() {
            val available = !kb.controller.isSensitiveField
            readButton.isEnabled = available && !busy; pasteButton.isEnabled = available && !busy
            webButton.isEnabled = available
            translateButton.isEnabled = available && !busy; cancelButton.isEnabled = busy
            translateButton.text = if (usesGoogle()) "使用 Google Translate 翻译" else
                if (TranslationSettings.protocol(kb.prefs) == TranslationProtocol.GOOGLE_WEB) "打开 Google Translate" else "翻译"
            sourceSpinner.isEnabled = available && !busy; targetSpinner.isEnabled = available && !busy
            for (button in listOf(replaceButton, insertButton, copyButton)) button.isEnabled = available && !busy && resultValue.isNotBlank()
        }
        fun showResult(text: String) {
            resultValue = text; result.text = text; updateActions()
            attribution.visibility = if (usesGoogle()) VISIBLE else GONE
            showStatus("翻译完成，可替换选区、插入或复制")
            // Reveal the translation in the compact IME window as soon as it arrives.
            scroll.post { scroll.scrollTo(0, result.top) }
        }
        fun showStatus(text: String, error: Boolean = false) {
            status.text = text
            status.setTextColor(if (error) kb.palette.danger else kb.palette.labelSecondary)
        }
        fun applyPalette() {
            val pal = kb.palette
            attribution.setImageResource(if (pal.dark) com.weavetext.ime.R.drawable.google_translate_attribution_white
                else com.weavetext.ime.R.drawable.google_translate_attribution)
            title.setTextColor(pal.label)
            for (text in listOf(source, result)) { text.setTextColor(pal.label); text.setHintTextColor(pal.labelHint); text.background = rounded(pal.card) }
            for (button in buttons) { button.setTextColor(pal.label); button.background = rounded(pal.keyFunc) }
            translateButton.setTextColor(pal.onAccent); translateButton.background = rounded(pal.keyAccent)
            status.setTextColor(pal.labelSecondary)
        }
        private fun row() = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        private fun usesGoogle() = TranslationSettings.protocol(kb.prefs) == TranslationProtocol.GOOGLE_DEVICE
        private fun addButton(row: LinearLayout, text: String, action: () -> Unit): Button {
            val button = Button(context).apply { this.text = text; textSize = 13f; isAllCaps = false; minWidth = 0; minimumWidth = 0; setPadding(dp(4), 0, dp(4), 0); setOnClickListener { action() } }
            row.addView(button, LayoutParams(0, dp(42), 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            buttons += button; return button
        }
        private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
        private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(10).toFloat() }
        private fun adapter(items: List<TranslationLanguage>) = ArrayAdapter(context, android.R.layout.simple_spinner_item, items.map { it.label }).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        private fun listener() = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { onLanguagesChanged(sourceLanguage(), targetLanguage()) }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }
}
