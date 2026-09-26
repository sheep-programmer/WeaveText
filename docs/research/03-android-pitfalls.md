# 03 · Android 输入法坑位与语音输入 / Android IME Pitfalls & Voice Input

> 每条格式：**现象 / 根因 / 做法 / 出处**。所有 API 名称与行为均对照官方文档核实；
> 所有 issue 链接均为真实抓取所得。
> *Each item: symptom / root cause / fix / source. API names verified against official docs; issue links are real.*

---

## A. 生命周期与 InputConnection

### A1. composing 区与光标是「两个独立的东西」
**现象**：预编辑串（拼音串）与光标位置不同步，出现重影、跳字、末尾多字、删除删错位置。
**根因**：官方文档明确写：*"The composing region and the selection are completely independent of each other, and the IME may use them however they see fit."*（[InputConnection 文档](https://developer.android.com/reference/android/view/inputmethod/InputConnection)）很多输入法误以为「设置 composing 就会顺带设置光标」。
**做法**：
- 每次 `setComposingText(text, 1)` **之后立刻** `setSelection(start + composingLen, start + composingLen)`，或使用 `setComposingRegion(start, end)` + 单独 `setSelection`。
- 引擎侧维护**唯一真相**：`(commitBase: String, composing: String, cursorInComposing: Int)`；每次状态变化**全量重算**要写入 IC 的内容，禁止增量打补丁。
- 收到 `onUpdateSelection` 时，若 `newSelStart != 预期`，说明编辑器侧改动了内容（用户手动移动光标/其他程序改文本），此时应**结束 composing**（`finishComposingText()`）并重置状态，而不是试图「纠正」编辑器。

### A2. `InputConnection` 可能为 null 或失效
**现象**：偶发 `NullPointerException`、输入无反应、候选栏正常但不上屏。
**根因**：`getCurrentInputConnection()` 在 `onCreateInputView` 之前、`onFinishInputView` 之后、以及编辑器进程死亡后返回 null；IC 对象跨进程，对方进程死亡后调用静默失败。
**做法**：
- **只在 `onStartInputView` 之后、`onFinishInputView` 之前**使用 IC；每个 IC 调用都走一个 `safeIc { }` 包装，null 时降级为「缓存到本地，等下次 IC 可用时补发」。
- 引擎线程与 UI 线程之间**不传递 IC 引用**，只传「要提交的文本 + 版本号」；由 UI 线程持有 IC 并校验版本号。

### A3. 批量编辑与事务性
**现象**：连续删除/替换时画面闪烁、Undo 栈错乱、某些编辑器只保留了部分修改。
**做法**：连续多个 IC 操作（例如「删除 N 个字符 + 插入候选」）用 `beginBatchEdit()` / `endBatchEdit()` 包裹；注意这两个调用**必须成对**，异常路径用 `try/finally`。
**坑**：部分编辑器（尤其 WebView）在 `endBatchEdit()` 时才真正合并，因此**不要在 batch 内读取内容再依赖它**。

### A4. 删除与「按词删除」
**做法**：
- 优先 `deleteSurroundingTextInCodePoints`（API 24+，[文档](https://developer.android.com/reference/android/view/inputmethod/InputConnection)）而非 `deleteSurroundingText`——后者按 UTF-16 code unit 计数，遇到 emoji（代理对）会删半个字符。
- 「按住退格按词删除」：先用 `getSurroundingText`（API 24+）取上下文，本地做分词，再删除；取不到时退化为逐字符删除。
- FlorisBoard 记录过「滑行删除模式在光标移动后仍然激活」的 bug（[#916](https://github.com/florisboard/florisboard/issues/916)）——**任何「模式」状态都必须挂到 `onUpdateSelection` 上清零**。

### A5. 全屏编辑模式（extract mode）
**现象**：横屏时键盘上方突然出现一个全屏输入框；或输入框里内容和 App 内不同步。
**根因**：编辑器可用 `EditorInfo.IME_FLAG_NO_EXTRACT_UI` / `IME_FLAG_NO_FULLSCREEN` 拒绝（[EditorInfo 文档](https://developer.android.com/reference/android/view/inputmethod/EditorInfo)）；输入法侧可覆写 `onUpdateExtractingViews` / `onUpdateExtractingVisibility` 主动拒绝。
**做法**：**织文默认拒绝全屏编辑**（`onUpdateExtractingVisibility` 返回 false），自己用候选栏上方的一行「内联编辑条」代替。这与主流输入法的现代交互一致，也避免与候选栏重复。

### A6. 线程安全：引擎**不是**线程安全的
**现象**：随机崩溃、状态错乱，且往往发生在压力测试或频繁切窗口时。
**根因**：真实案例——[weasel#1487](https://github.com/rime/weasel/issues/1487)：多个 `PipeServer::_ProcessPipeThread` 并发调用 librime，而 **librime 并不支持多线程并发调用**，加了并发检查后成功抓到并发调用；崩溃位置却总在 Lua 插件里，误导了排查方向。
**做法**：
- 引擎用**单线程 actor**：所有请求（按键、查询、学习）走一个 `mpsc` 通道，引擎线程串行处理。
- 所有跨线程共享的只能是**不可变快照**（`Arc<Snapshot>`），不是 `&mut Engine`。
- 学习/落盘在**独立线程**，通过通道提交「学习事件」，不直接碰引擎状态。
- **不要**用 `Mutex<Engine>` 糊过去：UI 线程抢锁会造成掉帧。

---

## B. 各 App 兼容性

| App / 场景 | 现象 | 根因与做法 | 出处 |
|---|---|---|---|
| WebView（WhatsApp Web To Go 等） | 光标后出现**重复/镜像文本** | WebView 对 composing 的处理与原生 EditText 不同：它可能把 composing 内容同时当作已提交文本。**做法**：检测到编辑器是 WebView（`EditorInfo.packageName` 或 `privateImeOptions`）时，**缩短 composing 生命周期**，候选确定后立即 `commitText` 并 `finishComposingText`，不做长时间 composing | [florisboard#2135](https://github.com/florisboard/florisboard/issues/2135) |
| Chrome / 浏览器地址栏 | 需要开关几次输入法才能输入 | Trime 历史问题，与 IC 复用/焦点时序有关 | [trime#868](https://github.com/osfans/trime/issues/868) |
| Compose `TextField` | 回车键额外插入字符 | ASK 真实 issue：`imeOptions` 的 action 与 `KeyEvent` 双路径导致重复 | [AnySoftKeyboard#3839](https://github.com/AnySoftKeyboard/AnySoftKeyboard/issues/3839) |
| Threema 等安全聊天 App | App 不识别输入法 | 编辑器对 `InputConnection` 能力探测严格 | [AnySoftKeyboard#3898](https://github.com/AnySoftKeyboard/AnySoftKeyboard/issues/3898) |
| 数字/单词混合输入 | 单词内打数字或按回车导致**词重复** | 建议（suggestion）生命周期与 commit 竞态 | [AnySoftKeyboard#4877](https://github.com/AnySoftKeyboard/AnySoftKeyboard/issues/4877) |
| 数字输入框 | 数字重复输入 | 未区分 `TYPE_CLASS_NUMBER` 与文本编辑路径 | [trime#1541](https://github.com/osfans/trime/issues/1541) |
| 密码框 / 自动填充 | 未完成的密码自动填充导致**输入法崩溃** | 与 Autofill 框架交互（`onCreateInlineSuggestionsRequest`、`autofillId`）未做空值防护 | [trime#1608](https://github.com/osfans/trime/issues/1608)、[trime#1660](https://github.com/osfans/trime/issues/1660) |
| 聊天应用 / 表情面板切换 | 交替点击表情与输入框导致严重卡顿 | 后台同步与 IC 重建叠加 | [trime#935](https://github.com/osfans/trime/issues/935) |

**通用做法 / General approach**
1. 建立 **App 兼容矩阵**（`packageName` → 行为覆盖表）：主流聊天应用、Chrome、WebView 容器、支付宝、WPS、VS Code Web、Termux 等各测一遍。
2. **不要信任 IC 的能力探测结果**：`getExtractedText`、`requestCursorUpdates` 在部分编辑器返回 null/false，必须有降级路径。
3. **候选上屏走单一出口**：所有「上屏」都收敛到一个函数，内部处理 composing/batch/selection，避免各调用点行为不一致。

---

## C. 窗口、Insets 与屏幕形态

### C1. 手势导航下键盘底部被透明导航栏覆盖
**现象**：全面屏手势导航时，键盘最下一排按键压在导航条区域，或下方出现一条透明带。
**出处**：[trime#1649](https://github.com/osfans/trime/issues/1649)（全屏手势导航下，在部分应用中，输入法下方导航栏是透明的）。
**做法**：
- 输入法窗口的 insets 由系统分发，但**不要假设一定有导航栏高度**：用 `WindowInsets.getInsets(Type.navigationBars() | Type.systemGestures())` 读取，并给键盘底部预留**至少 48dp 的可触区**。
- 底部留白用**键盘背景色**填充（与 §设计系统「一个平面」一致），不要留透明。
- Android 15（API 35）起应用强制 edge-to-edge，**targetSdk 36 必须显式处理 insets**，否则会出现键盘被裁切或下方留黑。

### C2. 横屏/后台切回时宽度突变导致按键变形
**现象**：横屏 App 切后台再切回前台，按键瞬时加宽、键盘右侧被裁切。
**出处**：[trime#2129](https://github.com/osfans/trime/issues/2129)（[BUG] 横屏App切后台切回前台，瞬时宽度导致按键加宽、键盘右侧裁切）。
**根因**：`onSizeChanged`/`onConfigurationChanged` 期间用「瞬时宽度」重新计算布局，而窗口宽度尚未稳定。
**做法**：
- 布局只在 **`onMeasure` 得到稳定尺寸后**计算，且**加最小宽度守卫**（如宽度 < 200dp 时沿用上一次布局）。
- 横竖屏切换用 `android:configChanges` 自己处理（输入法通常已如此），并在尺寸变化时**只重排不重建 View**。

### C3. 折叠屏 / 平板
**做法**：
- 用 `WindowManager` 的 `WindowMetrics`（API 30+）而非已废弃的 `Display.getSize()`；折叠屏展开/折叠会触发窗口尺寸变化但**不触发配置变更**，必须监听 `onConfigurationChanged` + `WindowLayoutInfo`（`androidx.window`）。
- 大屏键盘宽度设上限（如 ≤ 720dp）并居中，两侧留背景色，避免按键被拉成巨型。
- **铰链/分屏**：键盘可能被放在半屏，必须验证最小可用宽度下的九键与全键盘布局。

### C4. Direct Boot（设备未解锁）
**现象**：设备刚重启未解锁时，输入法闪退或无法使用。
**真实案例**：[fxliang/fcitx5-android#37](https://github.com/fxliang/fcitx5-android/issues/37)——「设备未解锁时访问 SharedPreferences 导致应用闪退（`ExceptionInInitializerError`）」。
**做法**：
- IME 声明 `android:directBootAware="true"`，但**必须**保证所有启动路径不触碰**凭据加密存储**（`filesDir`、`SharedPreferences`、Room 默认库）。
- 需要预解锁可用的数据放 `createDeviceProtectedStorageContext()`；把「解锁后才加载」的部分做成**惰性 + 可失败**。
- **注意**：direct boot 下 `UserManager.isUserUnlocked()` 为 false，此时**不要**初始化引擎与用户词库，只显示一个可用的英文键盘，解锁后再热加载。

---

## D. 稳定性：ANR、崩溃、JNI

### D1. 进程被杀后恢复
**现象**：输入法进程被系统回收后再弹出键盘，键盘空白、主题丢失、或直接崩溃。
**出处**：[trime#1626](https://github.com/osfans/trime/issues/1626)（杀掉后台再启动键盘时崩溃）、[trime#2058](https://github.com/osfans/trime/issues/2058)（`_activeTheme` 未初始化）。
**做法**：
- **`onCreateInputView` 必须是纯 View 构造**，不读磁盘、不初始化引擎；引擎初始化在 `onStartInputView` 的**后台线程**触发，UI 先用默认主题渲染。
- 所有「全局单例」在 `Application.onCreate` 里**不允许**依赖进程内可变状态；`_activeTheme` 这类必须**惰性 + 有默认值**。
- 用一个 `Bootstrap` 状态机（`Idle → Loading → Ready → Failed`），任何阶段失败都渲染「降级键盘」（英文 + 数字 + 符号），**永不白屏**。

### D2. ANR 常见来源
1. 主线程做词典加载/部署（[trime#2034](https://github.com/osfans/trime/issues/2034) 部署时卡住；[trime#713](https://github.com/osfans/trime/issues/713) 弹键盘耗时 5–9 s）。
2. 主线程做文件 IO（用户词落盘、日志）。**做法**：日志默认关闭或写内存环形缓冲，落盘在后台。
3. 主线程等锁（见 A6）。
4. 广播/Service 的同步 `onReceive`。
**硬性规则**：`onStartInputView` / `onCreateInputView` / 按键回调 / 候选刷新，**四者绝不触碰文件系统与网络**。

### D3. JNI / native 崩溃
**现象**：`libc++_shared.so not found`、SIGSEGV 无堆栈、只在部分 ABI 崩溃。
**出处**：[fcitx5-android#701](https://github.com/fcitx5-android/fcitx5-android/issues/701)（`library "libFcitx5Core.so" not found`）、[fcitx5-android#470](https://github.com/fcitx5-android/fcitx5-android/issues/470)（降级应用后循环崩溃）、[fcitx5-android#252](https://github.com/fcitx5-android/fcitx5-android/issues/252)（横屏长按 p 必现崩溃）。
**做法**：
- Rust 侧用 `panic = "abort"` 之外，**必须**在每个 JNI 入口用 `catch_unwind` 包裹，把 panic 转成 Java 异常；否则 Rust panic 跨 FFI 边界是 UB。
- `jni` crate 的 `JNIEnv` 只在**当前线程**有效：跨线程回调 Java 必须通过 `JavaVM::attach_current_thread`，并**缓存 `GlobalRef`**（`JObject` 不能跨线程/跨调用保存）。
- 打包时统一 `libc++_shared.so`（Rust 侧用 `c++_shared` 链接 std，或完全避免 C++ 依赖），并在 `jniLibs` 里按 ABI 齐全。
- **集成 tombstone 采集**：Android 11+ 可读 `ApplicationExitInfo`（`getHistoricalProcessExitReasons`）拿到 native crash 的 `REASON_CRASH_NATIVE` 与 tombstone 摘要，用于线上诊断。

### D4. 渲染延迟
**参考**：[weasel#1913](https://github.com/rime/weasel/issues/1913)——把候选窗 D2D 渲染目标由 `DEFAULT` 改为 `SOFTWARE` 后实测延迟明显缩短。虽然是 Windows 案例，但结论通用：**候选窗/键盘这类小面积高频重绘的 UI，软件渲染路径常常比 GPU 合成更快**（省掉合成与同步开销）。
**织文做法**：键盘 View 关闭硬件层（`setLayerType(LAYER_TYPE_NONE, null)`），只对**面板切换动画**启用硬件层；候选栏用 `Canvas.drawText` 直接绘制，避免每帧创建 `Paint`/`TextPaint`。

---

## E. 振动与音效

| 项 | 做法 | 坑 |
|---|---|---|
| 振动 | API 31+ 用 `VibratorManager.getDefaultVibrator()`；API 29+ 用 `VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)` 或 `createOneShot(ms, amplitude)` | 不要用已废弃的 `Vibrator.vibrate(long)`；不要每键 new 一个 `Vibrator` 对象 |
| 振动强度可调 | 用 `createOneShot` 的 amplitude 参数（`Vibrator.hasAmplitudeControl()` 为 false 的设备忽略 amplitude） | 用户在系统设置里可能全局关闭了触感，`Vibrator.hasVibrator()` 也不代表当前可用 |
| 音效 | 用 `View.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)` 走系统触感（零延迟、尊重系统设置） | 自己用 `Vibrator` 会有 10–30 ms 延迟 |
| 按键音 | `SoundPool` 预加载 + `play()`；音量走 `AudioManager.STREAM_SYSTEM` | `AudioManager.playSoundEffect` 在部分 ROM 被静音策略拦截 |
| 低延迟要求 | 按键反馈必须在**按下同一帧**发生（见 `docs/design/01-design-system.md` §0.4） | 不要在 `onTouchEvent` 里做任何可能阻塞的工作 |

**真实坑**：[trime#494](https://github.com/osfans/trime/issues/494)「按键振动强度调解失效」——振动参数在不同 ROM 上语义不一致，**必须提供「跟随系统」作为默认值**，而不是自己定强度。

---

## F. 语音输入

### F1. 权限：IME **不能**自己弹权限框
**现象**：IME 里调用 `requestPermissions` 无效果（`InputMethodService` 不是 `Activity`）。
**做法**：
1. IME 检测 `checkSelfPermission(RECORD_AUDIO)`，未授权时**启动一个透明 Activity**（`Intent.FLAG_ACTIVITY_NEW_TASK` + `FLAG_ACTIVITY_CLEAR_TOP`）来请求权限。
2. 该 Activity 用 `ActivityResultContracts.RequestPermission` 拿结果，通过 `LocalBroadcast`/`StateFlow`/`ResultReceiver` 回传给 IME Service。
3. **关键**：请求权限期间键盘会失去焦点（`onFinishInputView`），必须保证**回来后能恢复**录音按钮的 UI 状态，并且**不要把用户已输入的 composing 串丢掉**。
4. 若使用系统 `SpeechRecognizer`：targetSdk ≥ 30 时**必须在 Manifest 声明包可见性**，否则找不到识别服务：
   ```xml
   <queries>
     <intent><action android:name="android.speech.RecognitionService" /></intent>
   </queries>
   ```
   （[SpeechRecognizer 文档](https://developer.android.com/reference/android/speech/SpeechRecognizer)）
5. 权限被永久拒绝时，提供一个「去系统设置」的入口（`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`），不要反复弹框。

### F2. 录音参数
```kotlin
val sampleRate = 16000
val minBuf = AudioRecord.getMinBufferSize(sampleRate,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
val record = AudioRecord.Builder()
    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
    .setAudioFormat(AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(sampleRate)
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
    .setBufferSizeInBytes(minBuf * 2)   // 双缓冲，避免 underrun
    .build()
```
**要点**：
- **16 kHz / 单声道 / PCM 16-bit** 是主流云 ASR 的事实标准（常见帧格式为 `audio/L16;rate=16000` 裸 PCM）。
- `AudioRecord.getMinBufferSize` 是**下限**，实际给 2–4 倍以吸收 GC 抖动。
- 读取用**专用线程 + 阻塞 `read()`**，不要用 `AudioRecord.read` 的非阻塞模式轮询。

### F3. 音源与降噪（重要且反直觉）
官方文档原文（[MediaRecorder.AudioSource](https://developer.android.com/reference/android/media/MediaRecorder.AudioSource)）：
- `VOICE_RECOGNITION`：*"Microphone audio source tuned for voice recognition."*
- `VOICE_COMMUNICATION`：*"Microphone audio source tuned for voice communications such as VoIP. It will for instance take advantage of echo cancellation or automatic gain control if available."*

**结论**：
- 想要**原始信号给云端 ASR** → 用 `VOICE_RECOGNITION`（不启用 AGC/AEC，避免把语音压平）。
- 想要**端上降噪** → 用 `VOICE_COMMUNICATION`，但要注意 AGC 可能放大背景噪声。
- `AcousticEchoCanceler.create(sessionId)` / `NoiseSuppressor.create(sessionId)` **可能返回 null**（设备不支持），必须判空；且**不要与 `VOICE_COMMUNICATION` 叠加**（系统可能已启用，重复处理会失真）。
- 需要时可自研轻量降噪（谱减法/RNNoise 类），但**默认关闭**：多数场景下云端 ASR 自带降噪，端上处理反而有害。

### F4. 蓝牙耳机
- **API 34 起** `AudioManager.startBluetoothSco()` / `stopBluetoothSco()` **已废弃**，改用 `AudioManager.setCommunicationDevice(AudioDeviceInfo)` / `clearCommunicationDevice()`（官方文档原文：*"This method was deprecated in API level 34. Use AudioManager.setCommunicationDevice(AudioDeviceInfo) instead."*）。
- 用 `AudioManager.getDevices(GET_DEVICES_INPUTS)` 找到 `TYPE_BLUETOOTH_SCO` / `TYPE_BLE_HEADSET` 设备，再 `AudioRecord.setPreferredDevice(device)`。
- Android 12+ 需要 `BLUETOOTH_CONNECT` 运行时权限才能枚举/连接蓝牙设备。
- **录制中设备切换**：注册 `AudioRecord.registerAudioRecordingCallback`（API 29+），在 `onRoutingChanged` 时**重建 AudioRecord**（旧实例可能进入 `ERROR_DEAD_OBJECT`）。

### F5. 流式 partial/final 不闪烁
**问题**：partial 结果直接 `commitText` 会不断追加；`setComposingText` 又可能与编辑器自带联想冲突。
**做法（推荐）**：
1. 打开语音面板时，**先保存当前 composing 状态**。
2. partial 结果用 `setComposingText(partialText, 1)` 显示（可被下一次 partial 覆盖，天然不闪烁）。
3. final 结果用 `commitText(finalText, 1)` + `finishComposingText()`。
4. **断句**：服务端返回 `pgs="rpl"`（替换第 rg[1]..rg[2] 片）时，只替换对应片段，不要重发整句——插件应按片段号合并结果。
5. **句尾丢字**：`host.asr.emitEnd`（SDK 0.4.0+）用于「立刻上屏」，否则要等超时；这是参考实现里专门解决过的坑。
6. 面板关闭/失焦时**必须** `stop()` + `release()`，并把 composing 落定，避免用户切走后文字消失。

### F6. 录音被抢占与焦点
- 来电、其他 App 录音、系统助手唤醒都会打断录音。**做法**：监听 `AudioManager.AudioRecordingCallback` 与 `AudioFocusRequest`（`AUDIOFOCUS_LOSS` / `LOSS_TRANSIENT`），被打断时**立即结束会话并上屏已识别内容**，而不是静默失败。
- 进入语音面板时申请**短暂音频焦点**（`AUDIOFOCUS_GAIN_TRANSIENT`），退出时 `abandonAudioFocusRequest`。
- **不要在键盘可见但用户没点麦克风时持有录音**：Android 10+ 对后台录音有严格限制，且会触发系统的「正在录音」指示器，用户会反感。

### F7. Lua 插件宿主的能力面
语音插件宿主需要提供的能力面：

| 宿主 API | 用途 | 备注 |
|---|---|---|
| `host.ws` | WebSocket（域名受 `manifest.network.hosts` 白名单约束） | 必须做域名校验 |
| `host.asr.emitPartial` / `emitFinal` / `emitEnd` | 结果回传 | `emitEnd` 决定句尾是否丢字 |
| `host.crypto.hmacSha256` / `base64` / `urlEncode` | 鉴权签名 | 纯 Rust 实现即可，不必依赖 OpenSSL |
| `manifest.capabilities.speech.inputMode: streaming` | 声明流式 | 宿主据此选择「边录边发」还是「录完再发」 |
| `manifest.minHostVersion` | 版本协商 | 宿主需给出明确的 SDK 版本号 |

**织文必须注意**：
- 织文**不内置任何语音插件或第三方服务凭据**；插件由用户自行导入，凭据应由用户**自填自己的密钥**，或通过官方开放平台的正规渠道申请。
- 插件是**不可信代码**：必须限制网络白名单、限制 CPU/内存、限制单次会话时长，并在独立线程运行（`mlua` 的 `Lua` 实例不跨线程共享）。

---

## G. 落地自检清单 / Pre-ship Checklist

**中文**
- [ ] `onCreateInputView` 无 IO、无引擎初始化、无锁
- [ ] 冷启动到键盘可见 ≤ 200 ms（低端机 ≤ 400 ms）
- [ ] 所有 IC 调用都经过 null/失效保护与版本校验
- [ ] composing 与 selection 每次同步设置
- [ ] 引擎单线程 actor，无并发调用
- [ ] JNI 入口全部 `catch_unwind`，无 Rust panic 跨边界
- [ ] direct boot 下可用（仅英文降级键盘）
- [ ] 手势导航 / 三键导航 / 横屏 / 折叠屏 / 分屏 五种形态各测一遍
- [ ] 密码框与 `IME_FLAG_NO_PERSONALIZED_LEARNING` 下不学习、不联想
- [ ] 语音：权限跳转 → 恢复 → 录音 → partial → final → 释放，全链路可重复进入
- [ ] 进程被杀后重建不崩溃、不白屏
- [ ] `ApplicationExitInfo` 采集 native crash

**English** — same list: no IO/locks/engine init in `onCreateInputView`; ≤200 ms cold start; null-safe versioned IC access; composing and selection set together; single-threaded engine actor; `catch_unwind` at every JNI entry; usable under direct boot (English-only fallback); tested across gesture/3-button nav, landscape, foldable, split-screen; no learning in password fields or with `IME_FLAG_NO_PERSONALIZED_LEARNING`; full voice loop re-enterable; no crash/blank after process death; native crash reporting via `ApplicationExitInfo`.

---

## H. 出处 / Sources

**官方文档**
- `InputConnection`（composing 与 selection 相互独立）: <https://developer.android.com/reference/android/view/inputmethod/InputConnection>
- `EditorInfo`（`IME_FLAG_NO_PERSONALIZED_LEARNING` / `IME_FLAG_NO_FULLSCREEN` / `IME_FLAG_NO_EXTRACT_UI` / `TYPE_TEXT_VARIATION_WEB_PASSWORD`）: <https://developer.android.com/reference/android/view/inputmethod/EditorInfo>
- `MediaRecorder.AudioSource`（`VOICE_RECOGNITION` / `VOICE_COMMUNICATION` 与 AEC/AGC 原文）: <https://developer.android.com/reference/android/media/MediaRecorder.AudioSource>
- `AudioRecord`（`getMinBufferSize` / `ERROR_DEAD_OBJECT` / `registerAudioRecordingCallback` / `setPreferredDevice`）: <https://developer.android.com/reference/android/media/AudioRecord>
- `AudioManager`（`startBluetoothSco` 已于 API 34 废弃 → `setCommunicationDevice`）: <https://developer.android.com/reference/android/media/AudioManager>
- `SpeechRecognizer`（`<queries>` 声明 `android.speech.RecognitionService`）: <https://developer.android.com/reference/android/speech/SpeechRecognizer>

**Issue 证据**
- Trime: [#713](https://github.com/osfans/trime/issues/713) · [#1169](https://github.com/osfans/trime/issues/1169) · [#935](https://github.com/osfans/trime/issues/935) · [#452](https://github.com/osfans/trime/issues/452) · [#1766](https://github.com/osfans/trime/issues/1766) · [#1085](https://github.com/osfans/trime/issues/1085) · [#92](https://github.com/osfans/trime/issues/92) · [#334](https://github.com/osfans/trime/issues/334) · [#494](https://github.com/osfans/trime/issues/494) · [#868](https://github.com/osfans/trime/issues/868) · [#1341](https://github.com/osfans/trime/issues/1341) · [#1541](https://github.com/osfans/trime/issues/1541) · [#1556](https://github.com/osfans/trime/issues/1556) · [#1608](https://github.com/osfans/trime/issues/1608) · [#1626](https://github.com/osfans/trime/issues/1626) · [#1649](https://github.com/osfans/trime/issues/1649) · [#1660](https://github.com/osfans/trime/issues/1660) · [#2003](https://github.com/osfans/trime/issues/2003) · [#2034](https://github.com/osfans/trime/issues/2034) · [#2058](https://github.com/osfans/trime/issues/2058) · [#2129](https://github.com/osfans/trime/issues/2129)
- fcitx5-android: [#252](https://github.com/fcitx5-android/fcitx5-android/issues/252) · [#320](https://github.com/fcitx5-android/fcitx5-android/issues/320) · [#383](https://github.com/fcitx5-android/fcitx5-android/issues/383) · [#410](https://github.com/fcitx5-android/fcitx5-android/issues/410) · [#439](https://github.com/fcitx5-android/fcitx5-android/issues/439) · [#470](https://github.com/fcitx5-android/fcitx5-android/issues/470) · [#531](https://github.com/fcitx5-android/fcitx5-android/issues/531) · [#532](https://github.com/fcitx5-android/fcitx5-android/issues/532) · [#701](https://github.com/fcitx5-android/fcitx5-android/issues/701) · [#841](https://github.com/fcitx5-android/fcitx5-android/issues/841)
- FlorisBoard: [#916](https://github.com/florisboard/florisboard/issues/916) · [#1146](https://github.com/florisboard/florisboard/issues/1146) · [#1822](https://github.com/florisboard/florisboard/pull/1822) · [#2135](https://github.com/florisboard/florisboard/issues/2135)
- AnySoftKeyboard: [#3839](https://github.com/AnySoftKeyboard/AnySoftKeyboard/issues/3839) · [#3898](https://github.com/AnySoftKeyboard/AnySoftKeyboard/issues/3898) · [#4877](https://github.com/AnySoftKeyboard/AnySoftKeyboard/issues/4877)
- Weasel: [#1487](https://github.com/rime/weasel/issues/1487)（引擎不支持并发调用） · [#1913](https://github.com/rime/weasel/issues/1913)（渲染目标改 SOFTWARE 降延迟）
- Direct boot 崩溃实例: <https://github.com/fxliang/fcitx5-android/issues/37>
- 语音 IME 参考实现（K6nele）: <https://github.com/Kaljurand/K6nele>
