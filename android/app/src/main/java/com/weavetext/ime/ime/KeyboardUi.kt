package com.weavetext.ime.ime

import android.view.View

/**
 * 键盘界面的入口。界面实现只依赖 [InputController]。
 * Entry point of the keyboard UI; implementations only depend on [InputController].
 */
interface KeyboardUi {
    val view: View
    /** @param restarting 同一输入框重新开始（onStartInputView 的 restarting）。 The same field restarted. */
    fun onShown(restarting: Boolean = false) {}
    fun onHidden() {}
    fun dispose() {}
    /** 由 [WeaveImeService.onComputeInsets] 调用。 Called from onComputeInsets. */
    fun computeInsets(outInsets: android.inputmethodservice.InputMethodService.Insets) {}
    /** 系统返回键：先关闭面板；返回 true 表示已处理。 Back key: close a panel first. */
    fun handleBack(): Boolean = false
    fun onSelectionChanged(selStart: Int, selEnd: Int) {}
    /** 实体键盘模式下代替软键盘显示的候选栏。 The candidate bar shown instead of the keys with a physical keyboard. */
    val candidatesView: View? get() = null
    /** 接上 / 拔掉实体键盘。 A physical keyboard was attached or detached. */
    fun setHardwareMode(on: Boolean) {}

    companion object {
        fun create(service: WeaveImeService, controller: InputController): KeyboardUi =
            com.weavetext.ime.ui.keyboard.WeaveKeyboard(service, controller)
    }
}
