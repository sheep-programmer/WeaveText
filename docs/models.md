# 端侧模型 / On-device Models

## 下载与使用

语音只使用设备上的离线模型。Android 系统 SpeechRecognizer 与云端语音插件不再作为输入后端；旧插件文件和配置保留在应用私有目录，不参与录音。

两个安装包都需先下载识别模型。顶部档位对应真实模型：

| 档位 | 模型 | 下载与安装 |
|---|---|---|
| 轻量中文 | 实时识别 · 小 | 语音版约 24 MB；Lite 加运行库约 33 MB |
| 中英标准 | 双语 Zipformer 标准 | 官方压缩包约 358 MB，所选模型文件约 50 MB |
| 中英增强 | 双语 Zipformer 增强 + SenseVoice | 双语压缩包约 347 MB，所选文件约 122 MB；另下载 SenseVoice |

标准与增强双语压缩包含浮点和多种上下文版本，所以下载量大于安装后的模型大小；界面显示实际下载量。Lite 另下载运行库，各档都安装 Silero 人声检测。文件按目录中的 SHA-256 和大小校验。新增 WeNet 中英粤语可单独下载，Dolphin、TeleSpeech、Paraformer 等中文模型继续保留；结构支持不能替代实际识别准确率测量。

语音面板的模型下拉列表只显示已装好的识别模型，可勾选 1–3 个。默认中英混合，中文、English 模式只提供适用的模型，各模式独立记住选择。一份 PCM 同时交给各模型独立识别，停止后显示最多三行结果，点击一行上屏；相同文本也保留各自结果。候选行左右滑动查看完整文字，长按展开换行全文；展开后左右换模型、上下浏览，点击上屏选择所看结果。未选中的模型缓存会释放，选中的模型处理过慢时给出错误。只有运行库、人声检测或标点时不能开始识别。

点按模式第二次点击结束，按住模式松手结束；空格长按同样可说话，允许小幅手指漂移。麦克风立即采集，首次模型加载期间缓存音频，松手后仍处理已经收到的语音。静音不会关闭麦克风，分句等待 1.6 秒，长句约 28 秒分段并补齐末尾。声波取最近的真实 PCM 音量，轻声可见，响声保留变化，数字静音保持平直。停止前送完最后一块 PCM，再完成各模型的解码。

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

JSON 报告保存输入框中的实际文字和删除后的状态，旁边的 PNG 保存删除前的实测截图。多模型验证可加 `--choose X Y` 指定结果行；先等待该行识别完成，默认覆盖 60 秒的离线兜底，可用 `--choose-wait-seconds` 调整。

Lite 界面测试需要在独立 AVD 里准备已安装的语音包。可用
`-Pweave.voiceSmokePack=<local-assets-directory>` 给测试 APK 临时加入
`voice-ui-pack/{model.int8.onnx,tokens.txt,libonnxruntime.so,libsherpa-onnx-c-api.so}`。
随后运行 `VoiceSmoke` 的 `stage-lite-pack` 用例（`-e pack assets`），它会先按照公开 APK 的模型目录
核对大小和 SHA-256，再复制到该测试实例的私有模型目录。测试代码与这些文件均不会进入产品 APK。

轻量版不带 Sherpa 运行库与识别模型；从语音包页面下载后使用 Rust 动态加载 C API，识别参数与离线语音版一致。两版均只使用设备上的离线模型。两个版本的词库都直接从 APK 读取、不再解压（见 `docs/ARCHITECTURE.md` §2.1）。

正式版只含 arm64-v8a；调试版额外含 x86_64 以便模拟器。`-Pweave.abis=arm64-v8a,x86_64` 可覆盖。
*Release builds are arm64-v8a only; debug adds x86_64 for emulators; override with `-Pweave.abis`.*

### 4.1 轻量版语音包 / Lite voice pack

- 「设置 → 语音引擎 → 语音包」可选轻量中文、中英标准、中英增强，一键安装相应模型、人声检测与缺少的运行库；也可逐项下载、卸载。
- 运行库先尝试织文发布页 `asr-runtime-v1.13.8` 的 arm64 小包（约 9 MB），失败时退到 Sherpa 官方多架构包（约 46 MB），只取 `arm64-v8a/`。每个文件按 SHA-256 校验。
- 库设为只读后按绝对路径载入并核对版本。安装某个模型时一并补齐缺少的运行库和人声检测；卸载运行库不会删除模型。
- 安装档位后选中相应模型。下载器合并进度，可取消，计流量网络先提示。语音面板里再按需多选最多三个已下载模型。
- ABI 不符时提供完整离线语音版安装入口；完整包也仍需下载识别模型。

## 5. 测试 / Tests

`./gradlew :native-test:test` 在桌面 JVM 上验证：目录解析、下载器（镜像回退、坏镜像、断点续传、取消）、两条路线择优与逐文件校验、
Rust 解压真实模型包、两遍识别（真实 sherpa-onnx + 测试用模型 `fetchTestModels`，两句话各出一个正确终稿）、压缩包来源回退（首个 404 → 下一个，只取一个 ABI）；
在 macOS arm64 上还会下载桌面版运行库，经 `NativeAsr`（与轻量版同一条载入路径）端到端识别官方测试音频。`WEAVE_NET_TEST=1` 时额外做真实网络下载。
*Desktop tests cover catalog parsing, downloader fallback/resume/cancel, route selection with per-file checks, real archive
extraction, two-pass recognition with the real runtime, archive-source fallback, and (on macOS arm64) an end-to-end
`NativeAsr` decode through the same run-time loading path as the lite build; `WEAVE_NET_TEST=1` adds a real-network download.*


## beta.10 缓存与英文补强

隐藏键盘保留模型和词库缓存；语音模型闲置 20 分钟、真正内存压力或模型变更时释放。主模型提前加载，其他模型随后准备，权重加载串行，解码线程按并行数分配。录音之间重建流状态并复用权重；中文、英文的配置变更不重读同一模型。

新增 Whisper base/small int8，英文模式推荐增强（small），混说优先双语 Zipformer。Whisper 在停止或分句时整句处理，避免反复解码造成积压。基础压缩包约 208 MB/模型 161 MB，增强约 639 MB/模型 375 MB；HuggingFace 逐文件路线可能下载更少。各文件和压缩包都核对 SHA-256。

各模型的实际中英混说仍有错词、粘连或漏词，语音调研记录保留了失败案例。内存不够时会在相应结果行说明，其他可用模型仍能继续，不替换用户选择的模型。
