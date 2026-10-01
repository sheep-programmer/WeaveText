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
        loadEngine()
        controller.addListener(stateListener)
        // 手写识别单独一个后台线程，最新的一笔优先。 Handwriting recognition on its own background thread.
        controller.handWorker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "weave-hand").apply { isDaemon = true }
        }
        controller.postMain = { main.post(it) }
    }

    /** 正在后台加载内核。 The engine is loading in the background. */
    private var engineLoading = false

    /**
     * 后台加载内核；失败时控制器把记下的键原样输出，下次键盘出现时再试。
     * Load the engine in the background; on failure the controller emits the kept keys as typed, and the next
     * keyboard show tries again.
     */
    private fun loadEngine() {
        if (engineLoading || controller.state.engineReady) return
        engineLoading = true
        EngineHolder.load(this) { engine ->
            main.post {
                engineLoading = false
                if (engine != null) controller.attachEngine(engine) else controller.engineUnavailable()
            }
        }
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
        controller.onStartInputView(info)
        // 上次加载失败（如存储暂时不可读）：键盘再出现时重试。 The last load failed: retry when the keyboard shows again.
        loadEngine()
        ui?.onShown(restarting)
        com.weavetext.ime.voice.VoiceHub.preload(this)
        // 开启了互联时，键盘出现就把服务拉起来（进程被系统回收过也能恢复）。 Revive WeaveLink when the keyboard shows.
        com.weavetext.ime.link.LinkManager.get(this).ensureRunning()
        com.weavetext.ime.core.CloudWords.get(this).refreshIfStale()
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
        if (com.weavetext.ime.voice.local.AsrCachePolicy.releaseForTrim(level)) {
            com.weavetext.ime.core.EngineHolder.trim()
        }
    }

    override fun onDestroy() {
        debugBridge.unregister(this)
        controller.removeListener(stateListener)
        ui?.dispose()
        // 内核是进程共享的，这里只落盘不销毁。 The engine is shared: flush, don't close.
        controller.detachEngine()?.flush()
        controller.handWorker?.shutdownNow()
        controller.handWorker = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WeaveIme"
    }
}
