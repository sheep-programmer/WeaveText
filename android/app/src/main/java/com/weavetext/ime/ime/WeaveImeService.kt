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
    private val hardware = HardwareKeys(controller)
    /** 实体键盘在用：软键盘隐藏，只显示候选栏。 Physical keyboard in use: keys hidden, candidate bar only. */
    private var hardwareMode = false
    private val stateListener: (ImeState) -> Unit = { updateCandidatesShown(it) }

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
        controller.addListener(stateListener)
    }

    override fun onCreateInputView(): View {
        // 配置变化时系统会重建界面，先释放旧的。 Views are recreated on config changes; dispose the old one.
        ui?.dispose()
        val v = KeyboardUi.create(this, controller)
        ui = v
        v.setHardwareMode(hardwareMode)
        // 候选栏属于同一个界面实例。 The candidate bar belongs to the same UI instance.
        v.candidatesView?.let { detach(it); setCandidatesView(it) }
        return v.view
    }

    override fun onCreateCandidatesView(): View? {
        val v = ui ?: KeyboardUi.create(this, controller).also { ui = it; it.setHardwareMode(hardwareMode) }
        return v.candidatesView?.also { detach(it) }
    }

    private fun detach(v: View) { (v.parent as? android.view.ViewGroup)?.removeView(v) }

    /**
     * 接着实体键盘（且系统没要求同时显示软键盘）时，系统判定不显示输入视图：此时只显示候选栏；拔掉后恢复软键盘。
     * With a physical keyboard (and no system request to also show the soft one) the input view is not
     * shown: we show only the candidate bar, and return to the soft keyboard once it is detached.
     */
    override fun onEvaluateInputViewShown(): Boolean {
        val shown = super.onEvaluateInputViewShown()
        if (hardwareMode != !shown) {
            hardwareMode = !shown
            ui?.setHardwareMode(hardwareMode)
            updateCandidatesShown(controller.state)
        }
        return shown
    }

    private fun updateCandidatesShown(s: ImeState) {
        setCandidatesViewShown(hardwareMode && (s.composing || s.candidates.isNotEmpty()))
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean =
        hardware.onKeyUp(keyCode, event) || super.onKeyUp(keyCode, event)

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
        // 只有候选栏时用系统的默认计算。 Candidate bar only: the default insets are right.
        if (isInputViewShown) ui?.computeInsets(outInsets)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        controller.onSelectionUpdate(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        ui?.onSelectionChanged(newSelStart, newSelEnd)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.repeatCount == 0 && isInputViewShown && ui?.handleBack() == true) return true
        // 实体键盘：有编辑框、且不是只收按键的终端类输入框时经过内核。 Physical keys go through the engine in real text fields.
        val editor = currentInputEditorInfo
        if (currentInputConnection != null && editor != null && editor.inputType != android.text.InputType.TYPE_NULL &&
            hardware.onKeyDown(keyCode, event)
        ) return true
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
        controller.removeListener(stateListener)
        ui?.dispose()
        // 内核是进程共享的，这里只落盘不销毁。 The engine is shared: flush, don't close.
        controller.detachEngine()?.flush()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WeaveIme"
    }
}
