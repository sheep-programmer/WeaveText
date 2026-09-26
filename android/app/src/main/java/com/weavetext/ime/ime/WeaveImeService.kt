package com.weavetext.ime.ime

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import com.weavetext.ime.core.EngineHolder

/**
 * 输入法服务：负责生命周期与内核加载，按键逻辑在 [InputController]，界面在 [KeyboardUi]。
 * IME service: lifecycle and engine loading; logic lives in [InputController], views in [KeyboardUi].
 */
class WeaveImeService : InputMethodService() {

    val controller = InputController { currentInputConnection }
    private val main = Handler(Looper.getMainLooper())
    private var ui: KeyboardUi? = null

    /** 仅调试版生效的冒烟测试入口。 Smoke-test hook, active in debug builds only. */
    private val debugBridge by lazy { DebugBridge(controller) }

    override fun onCreate() {
        super.onCreate()
        debugBridge.register(this)
        // 词库拷贝与 mmap 放后台，键盘先出来。 Load off the main thread; keyboard shows first.
        com.weavetext.ime.voice.VoiceHub.preload(this)
        EngineHolder.load(this) { engine ->
            if (engine != null) main.post { controller.attachEngine(engine) }
        }
    }

    override fun onCreateInputView(): View {
        // 配置变化时系统会重建界面，先释放旧的。 Views are recreated on config changes; dispose the old one.
        ui?.dispose()
        val v = KeyboardUi.create(this, controller)
        ui = v
        return v.view
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        controller.onStartInput(attribute, restarting)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        ui?.onShown()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        ui?.onHidden()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        controller.onFinishInput()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        ui?.computeInsets(outInsets)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        ui?.onSelectionChanged(newSelStart, newSelEnd)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.repeatCount == 0 && isInputViewShown && ui?.handleBack() == true) return true
        return super.onKeyDown(keyCode, event)
    }

    /** 内存紧张时丢掉词库解压缓存（键盘收起时更积极）。 Drop dictionary caches under memory pressure. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        val hidden = !isInputViewShown && level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        @Suppress("DEPRECATION")
        if (hidden || level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            com.weavetext.ime.core.EngineHolder.trim()
        }
    }

    override fun onDestroy() {
        debugBridge.unregister(this)
        ui?.dispose()
        // 内核是进程共享的，这里只落盘不销毁。 The engine is shared: flush, don't close.
        controller.detachEngine()?.flush()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WeaveIme"
    }
}
