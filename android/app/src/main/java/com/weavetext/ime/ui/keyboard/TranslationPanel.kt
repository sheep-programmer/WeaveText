package com.weavetext.ime.ui.keyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.weavetext.ime.ime.InputController
import com.weavetext.ime.settings.TranslationSettings
import com.weavetext.ime.translate.*
import kotlin.math.roundToInt

/** Translation text is mirrored from the local draft; this panel is a single inline top bar. */
class TranslationPanel(
    kb: WeaveKeyboard,
    editor: TranslationEditor? = null,
    private val serviceProvider: () -> TranslationService = { TranslationServices.from(kb.ctx, kb.prefs) },
    toolbarIndex: Int = -1,
) : KbPanel(kb) {
    override val inlineHeightDp: Float get() = 44f
    override val toolIndex = toolbarIndex
    private val controllerEditor = if (editor == null) ControllerTranslationEditor(kb.controller) else null
    private val editor = editor ?: controllerEditor!!
    private val panel = TranslationLayout(kb.ctx)
    private val draft = object : android.view.inputmethod.BaseInputConnection(panel, true) {
        override fun sendKeyEvent(event: android.view.KeyEvent): Boolean {
            if (event.action != android.view.KeyEvent.ACTION_DOWN) return true
            val text = editable ?: return false
            val start = android.text.Selection.getSelectionStart(text).coerceAtLeast(0)
            val end = android.text.Selection.getSelectionEnd(text).coerceAtLeast(0)
            if (event.isCtrlPressed || event.isMetaPressed) {
                when (event.keyCode) {
                    android.view.KeyEvent.KEYCODE_A -> setSelection(0, text.length)
                    android.view.KeyEvent.KEYCODE_V -> paste()
                    android.view.KeyEvent.KEYCODE_C, android.view.KeyEvent.KEYCODE_X -> {
                        val selected = text.subSequence(minOf(start, end), maxOf(start, end)).toString()
                        if (selected.isNotEmpty()) clipboard()?.setPrimaryClip(ClipData.newPlainText("原文", selected))
                        if (event.keyCode == android.view.KeyEvent.KEYCODE_X) commitText("", 1)
                    }
                }
                syncSource(); return true
            }
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_MOVE_HOME -> setSelection(0, 0)
                android.view.KeyEvent.KEYCODE_MOVE_END -> setSelection(text.length, text.length)
                android.view.KeyEvent.KEYCODE_DEL -> if (start != end) commitText("", 1)
                    else if (start > 0) { val from = com.weavetext.ime.ime.EditorCache.clusterStart(text, start); text.delete(from, start) }
                android.view.KeyEvent.KEYCODE_FORWARD_DEL -> if (start != end) commitText("", 1)
                    else if (end < text.length) text.delete(end, end + Character.charCount(Character.codePointAt(text, end)))
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> setSelection((minOf(start, end) - 1).coerceAtLeast(0), (minOf(start, end) - 1).coerceAtLeast(0))
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> setSelection((maxOf(start, end) + 1).coerceAtMost(text.length), (maxOf(start, end) + 1).coerceAtMost(text.length))
                android.view.KeyEvent.KEYCODE_ENTER -> commitText("\n", 1)
            }
            syncSource(); return true
        }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
            super.commitText(text, newCursorPosition).also { syncSource() }
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
            super.deleteSurroundingText(beforeLength, afterLength).also { syncSource() }
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
            super.setComposingText(text, newCursorPosition).also { syncSource() }
        override fun performEditorAction(actionCode: Int) = commitText("\n", 1)
        override fun performContextMenuAction(id: Int): Boolean = when (id) {
            android.R.id.selectAll -> setSelection(0, editable?.length ?: 0)
            android.R.id.paste -> { paste(); true }
            else -> false
        }
    }
    private var shown = false
    private var syncing = false
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
        panel.onClear = {
            kb.controller.reset()
            setDraft("")
        }
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
        syncing = true
        draft.editable?.clear()
        val target = if (controllerEditor != null) kb.controller.beginTranslationDraft(draft) else null
        controllerEditor?.target = target
        val source = if (kb.controller.isSensitiveField) "" else if (controllerEditor == null) editor.selectedText().orEmpty() else draft.editable.toString()
        panel.reset(source, TranslationSettings.sourceLanguage(kb.prefs),
            kb.prefs.getString(TranslationSettings.TARGET_LANGUAGE, "en") ?: "en")
        syncing = false; shown = true
        syncSource()
    }
    override fun onHide() {
        shown = false
        cancelTranslation()
        kb.controller.endTranslationDraft(draft)
        draft.editable?.clear()
        panel.setSource("")
        panel.clearFocus()
    }
    override fun onState(s: com.weavetext.ime.ime.ImeState) { syncSource() }

    private fun syncSource() {
        if (!shown || syncing || controllerEditor == null) return
        val active = kb.controller.translationDraftActive(draft) && !kb.controller.isSensitiveField
        val text = if (active) {
            val committed = draft.editable?.toString().orEmpty()
            val at = android.text.Selection.getSelectionEnd(draft.editable).coerceIn(0, committed.length)
            committed.substring(0, at) + kb.controller.state.preedit + committed.substring(at)
        } else ""
        if (!active || text != panel.sourceText()) {
            cancelTranslation()
            panel.setSource(text)
            if (!active) panel.showStatus("输入位置已改变，请关闭后重新打开翻译", true)
        }
    }
    private fun setDraft(text: String) {
        cancelTranslation()
        if (controllerEditor == null) { panel.setSource(text); return }
        if (!kb.controller.translationDraftActive(draft)) return
        syncing = true
        kb.controller.reset()
        draft.editable?.replace(0, draft.editable?.length ?: 0, text)
        draft.setSelection(text.length, text.length)
        kb.controller.editor.reset(text.length, text.length)
        syncing = false
        syncSource()
    }

    private fun clipboard() = kb.ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    private fun readSelection() {
        if (kb.controller.isSensitiveField) return
        cancelTranslation()
        panel.clearResult()
        val selected = editor.selectedText().orEmpty()
        if (selected.isBlank()) { panel.showStatus("请先在输入框中选择已输入的文字", true); return }
        setDraft(selected)
    }
    private fun paste() {
        if (kb.controller.isSensitiveField) return
        val text = runCatching { clipboard()?.primaryClip?.getItemAt(0)?.text?.toString() }.getOrNull().orEmpty()
        if (text.isBlank()) { panel.showStatus("剪贴板中没有文字", true); return }
        cancelTranslation()
        setDraft(text)
    }
    private fun translate() {
        syncSource()
        if (controllerEditor != null && !kb.controller.translationDraftActive(draft)) return
        if (kb.controller.isSensitiveField) { panel.showStatus("此输入框不可使用翻译", true); return }
        if (kb.controller.state.composing) {
            kb.controller.commitFirst()
            syncSource()
        }
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
                syncSource()
                if (generation != current || !shown || kb.controller.isSensitiveField) return@post
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
        var target: InputController.TranslationTarget? = null
        override fun selectedText(): String? {
            target = controller.captureTranslationTarget(preserveComposition = true)
            return target?.selectedText
        }
        override fun replaceSelection(text: String) = target?.let { controller.writeTranslation(it, text, true) } ?: false
        override fun insert(text: String) = target?.let { controller.writeTranslation(it, text, false) } ?: false
    }

    private inner class TranslationLayout(ctx: Context) : LinearLayout(ctx) {
        private val source = object : TextView(ctx) {
            private val caret = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                if (!kb.controller.translationDraftActive(draft)) return
                val lines = layout ?: return
                val at = (android.text.Selection.getSelectionEnd(draft.editable).coerceAtLeast(0) +
                    kb.controller.state.preedit.length).coerceIn(0, text.length)
                val line = lines.getLineForOffset(at)
                val x = totalPaddingLeft + lines.getPrimaryHorizontal(at) - scrollX
                caret.color = kb.palette.keyAccent
                caret.strokeWidth = dp(2).toFloat()
                canvas.drawLine(x, (totalPaddingTop + lines.getLineTop(line) - scrollY).toFloat(),
                    x, (totalPaddingTop + lines.getLineBottom(line) - scrollY).toFloat(), caret)
            }
        }
        private val clearButton = ImageView(ctx)
        private val result = TextView(ctx)
        private val status = TextView(ctx)
        private val langPill = LinearLayout(ctx)
        private val sourceSpinner = Spinner(ctx)
        private val targetSpinner = Spinner(ctx)
        private val arrowView = TextView(ctx)
        private val sourceLanguages = TranslationLanguages.all
        private val targetLanguages = TranslationLanguages.all.filter { it.code != TranslationLanguages.AUTO }
        private val moreButton = object : Button(ctx) {
            private val micIcon = ContextCompat.getDrawable(ctx, com.weavetext.ime.R.drawable.ic_mic)?.mutate()
            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                micIcon?.let { icon ->
                    icon.setTint(kb.palette.label)
                    val size = dp(18)
                    val l = (width - size) / 2
                    val t = (height - size) / 2
                    icon.setBounds(l, t, l + size, t + size)
                    icon.draw(canvas)
                }
            }
        }
        private val doneButton = Button(ctx)
        private val inputBox = FrameLayout(ctx)
        private var sourceValue = ""
        private var resultValue = ""
        private var languagesValue: Pair<String, String>? = null
        var busy = false
            private set
        var onTranslate: () -> Unit = {}
        var onCancel: () -> Unit = {}
        var onClose: () -> Unit = {}
        var onSettings: () -> Unit = {}
        var onRead: () -> Unit = {}
        var onPaste: () -> Unit = {}
        var onClear: () -> Unit = {}
        var onWeb: () -> Unit = {}
        var onManage: () -> Unit = {}
        var onCopy: () -> Unit = {}
        var onReplace: () -> Unit = {}
        var onInsert: () -> Unit = {}
        var onLanguagesChanged: (String, String) -> Unit = { _, _ -> }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))

            // 1. Language pill [ 中 > 英 ]
            langPill.orientation = HORIZONTAL
            langPill.gravity = Gravity.CENTER_VERTICAL
            langPill.setPadding(dp(8), 0, dp(8), 0)
            for (picker in listOf(sourceSpinner, targetSpinner)) {
                picker.background = null
                picker.setPadding(0, 0, 0, 0)
            }
            sourceSpinner.adapter = adapter(sourceLanguages)
            targetSpinner.adapter = adapter(targetLanguages)
            sourceSpinner.onItemSelectedListener = listener()
            targetSpinner.onItemSelectedListener = listener()
            arrowView.text = ">"
            arrowView.textSize = 11f
            arrowView.gravity = Gravity.CENTER
            arrowView.setPadding(dp(3), 0, dp(3), 0)
            langPill.addView(sourceSpinner, LayoutParams(-2, -2))
            langPill.addView(arrowView, LayoutParams(-2, -2))
            langPill.addView(targetSpinner, LayoutParams(-2, -2))
            addView(langPill, LayoutParams(-2, dp(34)))

            // 2. Circular Mic / Options button ( 🎙️ )
            moreButton.apply {
                contentDescription = "更多"
                text = "更多"
                textSize = 0f
                setTextColor(Color.TRANSPARENT)
                isAllCaps = false
                minWidth = 0; minimumWidth = 0
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    android.widget.PopupMenu(ctx, moreButton).apply {
                        val actions = listOf(
                            "插入" to onInsert, "复制" to onCopy, "清空" to onClear,
                            "读取选区" to onRead, "粘贴文字" to onPaste, "替换选区" to onReplace,
                            "网页翻译" to onWeb, "离线插件" to onManage, "设置" to onSettings
                        )
                        val available = !kb.controller.isSensitiveField
                        actions.forEachIndexed { index, item ->
                            menu.add(0, index, index, item.first).isEnabled = when (item.first) {
                                "插入", "替换选区", "复制" -> available && !busy && resultValue.isNotBlank()
                                "读取选区", "粘贴文字" -> available && !busy
                                "网页翻译", "清空" -> available
                                else -> true
                            }
                        }
                        setOnMenuItemClickListener { item -> actions[item.itemId].second(); true }
                    }.show()
                }
            }
            addView(moreButton, LayoutParams(dp(34), dp(34)).apply {
                marginStart = dp(6); marginEnd = dp(6)
            })

            // 3. Draft input capsule [ 晚安大小姐   ✕ ]
            source.hint = "直接使用下方键盘输入原文"
            source.contentDescription = "翻译原文"
            source.isSingleLine = true
            source.maxLines = 1
            source.textSize = 14f
            source.gravity = Gravity.CENTER_VERTICAL or Gravity.START
            source.setPadding(dp(12), 0, dp(30), 0)
            source.setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_UP && kb.controller.translationDraftActive(draft)) {
                    kb.controller.commitFirst()
                    val at = source.getOffsetForPosition(event.x, event.y).coerceIn(0, draft.editable?.length ?: 0)
                    draft.setSelection(at, at)
                    kb.controller.editor.reset(at, at)
                    source.invalidate()
                }
                true
            }
            inputBox.addView(source, FrameLayout.LayoutParams(-1, -1))

            clearButton.setImageResource(com.weavetext.ime.R.drawable.ic_close)
            clearButton.scaleType = ImageView.ScaleType.CENTER_INSIDE
            clearButton.setPadding(dp(3), dp(3), dp(3), dp(3))
            clearButton.visibility = GONE
            clearButton.setOnClickListener { onClear() }
            inputBox.addView(clearButton, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                marginEnd = dp(6)
            })

            // Invisible result and status TextViews for state tracking and test inspection
            result.contentDescription = "翻译结果"
            result.visibility = GONE
            inputBox.addView(result, FrameLayout.LayoutParams(0, 0))
            status.visibility = GONE
            inputBox.addView(status, FrameLayout.LayoutParams(0, 0))

            addView(inputBox, LayoutParams(0, dp(34), 1f))

            // 4. Action button "完成" (Done)
            doneButton.apply {
                text = "完成"
                contentDescription = "完成"
                textSize = 15f
                isAllCaps = false
                minWidth = 0; minimumWidth = 0
                setPadding(dp(6), 0, dp(6), 0)
                setOnClickListener {
                    if (busy) {
                        onCancel()
                    } else if (resultValue.isNotBlank()) {
                        onReplace()
                    } else if (sourceValue.isNotBlank()) {
                        onTranslate()
                    } else {
                        onClose()
                    }
                }
            }
            addView(doneButton, LayoutParams(-2, dp(34)).apply { marginStart = dp(6) })
        }

        fun reset(text: String, sourceCode: String, targetCode: String) {
            sourceSpinner.setSelection(sourceLanguages.indexOfFirst { it.code == sourceCode }.coerceAtLeast(0), false)
            targetSpinner.setSelection(targetLanguages.indexOfFirst { it.code == targetCode }.takeIf { it >= 0 } ?: 1, false)
            languagesValue = sourceLanguage() to targetLanguage()
            sourceValue = ""; source.text = ""; clearResult(); setBusy(false)
            if (kb.controller.isSensitiveField) showStatus("此输入框不可使用翻译", true)
            else {
                setSource(text)
                showStatus(if (usesGoogle()) "使用已安装的离线插件和语言包" else
                    if (TranslationSettings.protocol(kb.prefs) == TranslationProtocol.GOOGLE_WEB) "官方网页翻译" else "在线翻译")
            }
        }
        fun setSource(text: String) {
            if (text == sourceValue) return
            if (text.length > TranslationRequest.MAX_TEXT_CHARS) {
                sourceValue = ""; source.text = ""; clearResult()
                showStatus("文字太长，请分段翻译", true); return
            }
            sourceValue = text; source.text = text; clearResult()
            clearButton.visibility = if (text.isNotEmpty()) VISIBLE else GONE
        }
        fun sourceText() = sourceValue
        fun sourceLanguage() = sourceLanguages.getOrNull(sourceSpinner.selectedItemPosition)?.code ?: "auto"
        fun targetLanguage() = targetLanguages.getOrNull(targetSpinner.selectedItemPosition)?.code ?: "en"
        fun resultText() = resultValue
        fun clearResult() { resultValue = ""; result.text = ""; updateActions() }
        fun setBusy(value: Boolean) {
            busy = value; updateActions()
            if (value) showStatus("正在翻译…")
        }
        private fun updateActions() {
            val available = !kb.controller.isSensitiveField
            doneButton.isEnabled = available
            doneButton.text = if (busy) "取消" else "完成"
            sourceSpinner.isEnabled = available && !busy
            targetSpinner.isEnabled = available && !busy
        }
        fun showResult(text: String) {
            resultValue = text; result.text = text; updateActions()
            showStatus("翻译完成")
        }
        fun showStatus(text: String, error: Boolean = false) {
            status.text = text
        }
        fun applyPalette() {
            val pal = kb.palette
            // Language pill styling
            langPill.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(17).toFloat()
                setColor(pal.card)
                setStroke(dp(1), pal.divider)
            }
            arrowView.setTextColor(pal.labelSecondary)

            // Mic button styling
            moreButton.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(pal.card)
                setStroke(dp(1), pal.divider)
            }

            // Input box styling: soft accent tint with border
            val boxBg = if (pal.dark) 0xFF202A38.toInt() else 0xFFEAF1FB.toInt()
            val boxStroke = if (pal.dark) 0xFF2E3E56.toInt() else 0xFFCADDF7.toInt()
            inputBox.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(17).toFloat()
                setColor(boxBg)
                setStroke(dp(1), boxStroke)
            }

            source.setTextColor(pal.label)
            source.setHintTextColor(pal.labelHint)

            clearButton.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x55808080)
            }
            clearButton.setColorFilter(0xFFFFFFFF.toInt())

            doneButton.background = null
            doneButton.setTextColor(pal.label)
        }
        private fun usesGoogle() = TranslationSettings.protocol(kb.prefs) == TranslationProtocol.GOOGLE_DEVICE
        private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
        private fun shortLabel(label: String): String = when {
            label.contains("中") -> "中"
            label.contains("英") -> "英"
            label.contains("日") -> "日"
            label.contains("韩") -> "韩"
            label.contains("法") -> "法"
            label.contains("德") -> "德"
            label.contains("西") -> "西"
            label.contains("俄") -> "俄"
            label.contains("自动") -> "中"
            else -> label.take(1)
        }
        private fun adapter(items: List<TranslationLanguage>) = object : ArrayAdapter<String>(context,
            android.R.layout.simple_spinner_item, items.map { it.label }) {
            init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    text = shortLabel(items[position].label)
                    setTextColor(kb.palette.label)
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, 0)
                }
        }
        private fun listener() = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val value = sourceLanguage() to targetLanguage()
                if (languagesValue != value) { languagesValue = value; onLanguagesChanged(value.first, value.second) }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }
}
