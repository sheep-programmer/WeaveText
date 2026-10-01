# 离线语音实现参考与本次选择

调研日期：2026-10-01。参考实现只用于核对架构与交互，没有复制第三方项目代码。

| 项目 | 源码中的实现 | 织文采用的做法 |
|---|---|---|
| [青简](https://github.com/qingjian-team/qingjian) | Rust 拼音、候选、词典、译词与桌面平台适配；本次检查的主分支未找到录音或 ASR 引擎 | 作为输入体验参考，不能把它当作已有语音方案 |
| [WhisperType Keyboard](https://github.com/Trion129/WhisperTypeKeyboard)（MIT） | 下载模型后才允许录音；AudioRecord 16 kHz PCM，录音线程停止并交完缓冲后写 WAV，Sherpa Whisper 本地识别；默认英语 Whisper 模型 | 下载门槛、明确开始/结束、停止时先交完音频。织文继续流式显示文字并分段处理长录音，中文优先选择已有实测模型 |
| [FUTO Voice Input](https://github.com/futo-org/voice-input)（FUTO Source First，非 OSI 开源许可） | 本地 Whisper；WebRTC VAD 分辨人声，有静音停止开关 | 默认录音由用户结束，人声检测只负责分句；不引入其代码或许可 |
| [Sherpa ONNX](https://github.com/k2-fsa/sherpa-onnx)（Apache-2.0） | Android Kotlin 与 C API、本地流式与非流式 ASR、Silero VAD、标点 | 继续复用已验证的 1.13.8 运行库；两种 APK 的模型设置和分句参数一致 |

具体源码：

- [WhisperType AudioRecorder](https://github.com/Trion129/WhisperTypeKeyboard/blob/main/app/src/main/java/me/trion/whispertype/voice/AudioRecorder.kt)、[KeyboardController](https://github.com/Trion129/WhisperTypeKeyboard/blob/main/app/src/main/java/me/trion/whispertype/ime/KeyboardController.kt)：下载检查、录音与终稿上屏。
- [FUTO AudioRecognizer](https://github.com/futo-org/voice-input/blob/master/app/src/main/java/org/futo/voiceinput/AudioRecognizer.kt)：VAD 与可配置静音停止。
- [Sherpa 1.13.8 VAD](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/kotlin-api/Vad.kt)、[C API](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/c-api/c-api.h)：结构体布局、模型参数与生命周期。

本次新增 Dolphin base/small 的 int8 CTC 导出与 TeleSpeech int8 中文方言模型。目录中保存官方下载地址、许可证、文件大小和实测 SHA-256。Dolphin 完整模型支持的任务不能直接推定为 CTC 导出全部支持的任务；这里只提供语音转文字。[Dolphin](https://github.com/DataoceanAI/Dolphin)、[TeleSpeech](https://github.com/Tele-AI/TeleSpeech-ASR)。

默认录音不设静音自动结束。录音缓冲容量为 1 秒，仍按 40 ms 小块读取；采集线程提高优先级，波纹由音量回调驱动刷新。流式结果为空且没有检测到人声时，不调用终稿模型，避免静音时凭空出字。分句等待 1.6 秒，长句约 28 秒补齐末尾解码后重置句子状态，麦克风保持开启。整句模型的临时重解码按最近耗时降低频率；音频积压超过 60 秒时明确提示换小模型，避免无限占用内存。模型冷启动与停止后的解码分别有 90 秒和 60 秒兜底；界面只有在麦克风真正开始后才显示正在聆听。

回归覆盖：延迟就绪、轻声音量、8 秒思考停顿、长句分段末尾、停止/取消/重开、迟到结果与删除、新增模型真实中文 WAV、人声检测静音与轻声、R8/JNI 字段检查，以及正式 APK 的麦克风到编辑器上屏链路。音频样本与模拟器通过不能保证每种手机、口音和环境的准确率。


2026-10-02 补充：标准、增强采用 Sherpa 官方双语流式 Zipformer transducer，保留编码器、解码器与连接器三个文件；两个 Android 适配器分别接 Kotlin JNI 和 Rust C API。新增 [WeNetSpeech-Yue](https://github.com/ASLP-lab/WenetSpeech-Yue)、[WSYue-ASR 模型卡](https://huggingface.co/ASLP-lab/WSYue-ASR)对应的 WeNet CTC 中英粤语模型，按 Apache-2.0 许可登记。

官方双语包包含根目录与 `64/`、`96/` 下的同名上下文导出。只按 basename 解压会取错版本；现在无目录白名单只匹配包根目录，相同目标重复出现则报错，有目录的运行库白名单仍支持 ABI 后缀。实测两份原包解压后核对大小与 SHA-256。

多个模型共用一次录音，各自加载、排队、识别与出错，结果不合并投票。最多三行始终由用户选择，即使三个结果相同。冷启动先开始采集再加载模型，短按住后松手也交付已采集音频；不靠编造英文音频预热识别器。

准确率记录：自然中英混说的 17.64 秒官方录音中，标准和增强保留了 ON TIME、IN TIME 与“准时”；将不同说话人的中文与英文录音直接拼接，部分双语流式结果漏掉英文，SenseVoice 与 WeNet 也出现英文词错误。这些失败保留，不能把语言支持和少量通过样本写成普遍准确。


正式 APK 的额外边界测试：录音降到原音量的 2.5% 后，声波仍跟随收音且静音回落，但标准模型漏掉部分英文；正常音量的前 4 秒短录音中，标准模型将 ON TIME 误认成中文，增强模型保留 ON TIME、IN TIME 和“准时”。三行结果分别展示了差异；这些测试用于核对用户选择确实有意义，没有用失败结果替换或冒充通过。


Lite 正式包的三模型长录音回归：两段自然中英夹杂录音，中间 8 秒停顿，增强行保留两次 IN TIME 与中文内容，选择后上屏并完整删除；ON TIME 在该次录音中误识别，词级准确率检查失败。2 GB 模拟器上三个模型冷加载与积压解码较慢，完成延迟最高约 39 秒；界面分别显示识别进度，已经完成的行可以先选。录音与选择链路通过不代表每个测试词都正确。


2026-10-02 beta.10：核查 Sherpa 1.13.8 [离线配置更新](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/offline-recognizer-impl.cc)与 [Whisper 解码](https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/offline-recognizer-whisper-impl.h)，语言切换复用权重、更新配置；新录音创建新流，句中端点仍按原流重置。Android 的 UI_HIDDEN=20 表示界面隐藏，不能用 `level >= RUNNING_LOW` 将它当成内存压力。缓存改为只在真实压力事件、20 分钟闲置或模型变更时释放。

新增 Whisper base/small 官方 int8 导出与大小、SHA-256；按 [OpenAI MIT 许可](https://github.com/openai/whisper/blob/main/LICENSE)登记。实际英文样本：基础模型把 chieftain 分成 chief then，增强模型得到正确的 tribal chieftain 与 50 pieces of gold。中文样本增强模型为“開放時間早上9點至下午5點”；自然混说里仍将 on time / in time 粘连并有中文错字。基础模型在该混说样本漏掉了英文，保留失败记录，混说仍推荐双语实时模型。不能将多语种能力当成所有混说都准确。

候选使用原始完整文本，行内横向滚动、长按展开换行全文；展开时左右换模型、上下浏览，滑动不会上屏，明确选择正在查看的模型。语言改变和模型选择取消未选结果，避免设置操作替用户提交首选。

正式 Android 12/API31 arm64 模拟器验证：三模型混说保留 ON TIME、IN TIME 与中文，候选行滑动、长按展开、左右切换到第三模型并提交全文、完整删除通过。切换到另一输入法并发送 UI_HIDDEN=20，再切回录音，模型加载计数保持 3，没有重新加载。该轮初载记录约 0.7–1.2 秒；环境内存 3 GB、4 核，不能与旧轮次不同负载的延迟作直接因果比较。正式包英文录音仍把 chieftain 识别为 chief then，整句上屏和删除通过，词级失败保留。

最终 Lite APK 经下载运行库的 C API 识别真实英文录音，得到完整的 After early nightfall the yellow lamps would light up ... 句子，上屏后键盘完整删除通过。正式语音版中文模式使用 SenseVoice，识别“开放时间早上九点至下午五点”并完整删除通过。
