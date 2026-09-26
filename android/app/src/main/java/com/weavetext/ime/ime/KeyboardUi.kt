package com.weavetext.ime.ime

import android.view.View

/**
 * 键盘界面的入口。界面实现只依赖 [InputController]。
 * Entry point of the keyboard UI; implementations only depend on [InputController].
 */
interface KeyboardUi {
    val view: View
    fun onShown() {}
    fun onHidden() {}
    fun dispose() {}
    /** 由 [WeaveImeService.onComputeInsets] 调用。 Called from onComputeInsets. */
    fun computeInsets(outInsets: android.inputmethodservice.InputMethodService.Insets) {}
    /** 系统返回键：先关闭面板；返回 true 表示已处理。 Back key: close a panel first. */
    fun handleBack(): Boolean = false
    fun onSelectionChanged(selStart: Int, selEnd: Int) {}

    companion object {
        fun create(service: WeaveImeService, controller: InputController): KeyboardUi =
            com.weavetext.ime.ui.keyboard.WeaveKeyboard(service, controller)
    }
}
