package com.weavetext.ime.debug

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout

/** 冒烟测试页：一个多行输入框（id = smoke_input），可用 `--ei type N` 指定 inputType。 */
class SmokeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val edit = EditText(this).apply {
            id = android.R.id.edit
            contentDescription = "smoke_input"
            inputType = intent.getIntExtra("type", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
            imeOptions = EditorInfo.IME_ACTION_NONE
            minLines = 3
        }
        setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(edit) })
        edit.requestFocus()
    }
}
