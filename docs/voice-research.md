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
