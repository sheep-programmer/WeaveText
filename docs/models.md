# 端侧模型 / On-device Models

## 下载与使用

语音只使用设备上的离线模型。Android 系统 SpeechRecognizer 与云端语音插件不再作为输入后端；旧插件文件和配置保留在应用私有目录，不参与录音。

两个安装包都需先下载识别模型：轻量版一键安装运行库、实时小模型与 Silero 人声检测，下载约 33 MB；语音版已带运行库，一键安装约 24 MB。运行库、模型和人声检测均按目录中的 SHA-256 与大小验证。模型可以分别卸载、重装和切换，只有运行库或只有标点模型均不能开始识别。

推荐先用实时小模型；可选终稿小模型、SenseVoice、Paraformer、Dolphin 轻量/增强、TeleSpeech 中文方言与智能标点。在设置中关闭实时模型可单独使用终稿模型，此时 Silero 检测人声并分句。旧安装未下载 Silero 时保留音量分句兼容路径。Dolphin 与 TeleSpeech 的 Sherpa 导出使用 CTC 分支，不能等同于原项目完整模型的评测效果。目录里的历史字错率来自合成样本相对比较，新增模型没有填入未经测量的分数。

点按模式第二次点击结束，按住模式松手结束；静音不会关闭麦克风。分句静音延长至 1.6 秒，长句约 28 秒分段并补齐末尾解码后继续收音。模型加载和麦克风启动都完成后才显示正在聆听；录音音量采用对数刻度，面板和浮动条显示最近的实际采样音量。停止会先送完录音线程最后一块 PCM，再解码终稿。

语音版仅内置 Sherpa 运行库；轻量版不含 Sherpa 库，下载后由 Rust 动态加载 C API。两版模型均按需下载，构建无需预装识别模型。发布继续保留 R8/JNI 字段检查。

### 正式包语音验证 / Speech in a release APK

sherpa-onnx 的 JNI 会按原名读取 Kotlin 配置与结果字段，因此正式版必须保留
`com.k2fsa.sherpa.onnx` 中的类和成员。只测试 debug 或桌面 JVM 无法覆盖 R8 的改名与删字段。
发布工作流在构建后检查最终 APK 中的全部配置与结果字段；缺失或类型变化会阻止发布。

```sh
python3 tools/check-release-jni.py dist/WeaveText-<version>-arm64-voice.apk .ref/cache/sherpa-onnx-1.13.8.aar
```

设备验证使用独立的 `voice-smoke` instrumentation APK，其签名需与被测正式包一致
（沿用 `android/keystore.properties`）。测试通过被测应用的 class loader 获取类，测试包不带
sherpa 副本，避免绕开正式包的混淆问题。测试目录使用模型压缩包解开的原始目录与 `test_wavs`。

```sh
cd android
./gradlew :voice-smoke:assembleRelease
adb -s <serial> install -r <voice-release.apk>
adb -s <serial> install -r -t voice-smoke/build/outputs/apk/release/voice-smoke-release.apk
cd ..
python3 tools/voice-smoke.py <serial> .ref/sherpa --repeat 2
# 已安装 lite 正式包时，另外指定从运行库包解出的 Android arm64 .so 目录：
python3 tools/voice-smoke.py <serial> .ref/sherpa --native --runtime <runtime-lib-dir> --repeat 2
```

测试依次加载实时小模型、实时大模型、终稿小模型、SenseVoice、Paraformer、Dolphin 两种模型、TeleSpeech、Silero 人声检测和智能标点，
识别模型均输入实际录音并检查样本中的词句；`--repeat` 每次重新打开模型，验证释放后能再次使用。
模型、录音和运行库仅放在独立测试目录里。

测试 APK 还提供普通输入框，可从已安装的正式输入法界面验证麦克风开始、停止、上滑取消与重开：

```sh
adb -s <serial> shell am start -n com.weavetext.ime.voicesmoke/.SpeechEditorActivity
```

这类录音生命周期检查与向模型输入 WAV 的测试不同，模拟器通过也不能代替不同手机上的真人录音、
麦克风权限、音源处理和第三方输入框兼容性验证。

完整上屏回归可使用 `tools/voice-ime-e2e.py`：它通过模拟器 gRPC 把 WAV 送进虚拟麦克风，
点击或按住正式 APK 的语音按钮，确认普通编辑器中出现样本词句，并通过键盘退格删到空。
`--mode tap --repeat 2 --pause-seconds 8` 可验证长停顿后继续说话。它不向输入法发送文字结果；识别、语音会话和 `InputConnection` 均由公开 APK 执行。

已验证的环境为独立的 Android 12/API 31 arm64 AVD、1080×2400 屏幕与三键导航。
模拟器应开启 gRPC token 认证，并用 `-feature -VirtioSndCard` 切到兼容音频转发的声卡。
新版 Android 镜像使用的 virtio 声卡不能套用这条转发路径，调用成功也必须检查实际输入框文字。
脚本在调用前禁止宿主真人麦克风，只注入测试录音。

在隔离的 Python 环境安装 `grpcio`、`grpcio-tools`，并从本机模拟器的
`lib/emulator_controller.proto` 生成 Python 模块后，用 `PYTHONPATH` 指向生成目录：

```sh
# 测试输入框和语音面板已打开，选择「按住说话」；坐标按当前模拟器调整。
python3 tools/voice-ime-e2e.py <serial> <sample.wav> \
  --discovery <emulator-pid.ini> --mic 540 1900 --delete 965 1850 \
  --expect '我想说的是' --report <report-directory>/microphone-e2e.json
```

JSON 报告保存输入框中的实际文字和删除后的状态，旁边的 PNG 保存删除前的实测截图。

Lite 界面测试需要在独立 AVD 里准备已安装的语音包。可用
`-Pweave.voiceSmokePack=<local-assets-directory>` 给测试 APK 临时加入
`voice-ui-pack/{model.int8.onnx,tokens.txt,libonnxruntime.so,libsherpa-onnx-c-api.so}`。
随后运行 `VoiceSmoke` 的 `stage-lite-pack` 用例（`-e pack assets`），它会先按照公开 APK 的模型目录
核对大小和 SHA-256，再复制到该测试实例的私有模型目录。测试代码与这些文件均不会进入产品 APK。

轻量版不带 sherpa-onnx 运行时（约 27 MB）与模型；在「语音包」里装好运行库与识别模型（实时模型，或只装终稿模型时整句识别；§4.1）之前「本地离线识别」不会出现，语音输入可用系统识别、手机上其他的语音输入法（一键切换）或插件。
两个版本的词库都直接从 APK 读取、不再解压（见 `docs/ARCHITECTURE.md` §2.1），装机占用约等于 APK 大小。
*Lite drops the sherpa-onnx runtime (~27 MB) and models; until the runtime and a streaming model are installed from the
voice-pack list (§4.1) the on-device engine is hidden and voice uses the system recognizer, another voice IME on the phone
(one-tap switch) or plugins. Both builds read dictionaries straight from the APK, so the footprint is about the APK size.*

正式版只含 arm64-v8a；调试版额外含 x86_64 以便模拟器。`-Pweave.abis=arm64-v8a,x86_64` 可覆盖。
*Release builds are arm64-v8a only; debug adds x86_64 for emulators; override with `-Pweave.abis`.*

### 4.1 轻量版语音包 / Lite voice pack

中文：
- 「设置 → 语音引擎 → 语音包」是逐项列表，每一项单独安装、卸载（语音面板里的「安装离线语音」也会打开这里）。轻量版顶部另有推荐组合一键安装两样：
  **识别运行库** `asr-runtime`（sherpa-onnx 1.13.8 的 C 接口库与 onnxruntime，arm64-v8a，官方文件原样，解开后约 27 MB）
  和 **实时识别 · 小** `asr-stream-small`，合计下载约 30 MB，合并显示进度、可取消，计流量网络下先询问。
- 运行库按目录里的 `archives` 顺序取：先试织文发布页 `asr-runtime-v1.13.8` 上只含 arm64 的小包（约 9 MB），404 或失败再退到 sherpa-onnx 官方的
  多架构 Android 包（约 46 MB），只解出 `arm64-v8a/` 下的两个文件；两条都走同一组 GitHub 镜像，每个文件按 SHA-256 校验。
- 安装后运行库文件设为只读（Android 14 起动态载入的代码必须不可写），再由 Rust 内核按绝对路径载入并核对版本号
  （`core/weave-ffi/src/asr.rs`）。识别参数（端点规则、线程数、两遍识别）与离线语音版一致，所以识别效果相同。
- 装好后自动选中「本地离线识别」；终稿识别与智能标点在同一列表里按需安装。单独安装模型时若还没有运行库会一起装上；卸载运行库不会连带删除模型。不必重装 APK。
- 运行库只在不随包的构建里、且设备主 ABI 相符时才列出；否则此页退回「安装完整离线语音版」（覆盖安装 APK）。

English: the lite build downloads a ~30 MB voice pack — the unmodified sherpa-onnx 1.13.8 C API + onnxruntime
libraries (arm64-v8a) and the small streaming model — with combined progress, cancel and a metered-network prompt. The
runtime tries a small arm64-only pack first and falls back to the official multi-ABI archive, keeping only
`arm64-v8a/`; files are SHA-256 verified, made read-only (Android 14 dynamic-code rule) and loaded by the Rust core with a
version check. Settings match the offline-voice build, so recognition quality is the same. The local engine is selected
automatically; final-pass and punctuation models remain optional. Without a matching ABI the page falls back to the
full offline-voice APK upgrade.

## 5. 测试 / Tests

`./gradlew :native-test:test` 在桌面 JVM 上验证：目录解析、下载器（镜像回退、坏镜像、断点续传、取消）、两条路线择优与逐文件校验、
Rust 解压真实模型包、两遍识别（真实 sherpa-onnx + 测试用模型 `fetchTestModels`，两句话各出一个正确终稿）、压缩包来源回退（首个 404 → 下一个，只取一个 ABI）；
在 macOS arm64 上还会下载桌面版运行库，经 `NativeAsr`（与轻量版同一条载入路径）端到端识别官方测试音频。`WEAVE_NET_TEST=1` 时额外做真实网络下载。
*Desktop tests cover catalog parsing, downloader fallback/resume/cancel, route selection with per-file checks, real archive
extraction, two-pass recognition with the real runtime, archive-source fallback, and (on macOS arm64) an end-to-end
`NativeAsr` decode through the same run-time loading path as the lite build; `WEAVE_NET_TEST=1` adds a real-network download.*
