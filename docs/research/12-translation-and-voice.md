> 2026-10-06 最新拆分：本文此前记录的是内置 SDK 版本。当前默认官方网页，SDK 已移到独立 APK；Mac 系统翻译也需要安装并启用适配插件。实际使用与当前边界以 [离线翻译插件](../offline-translation-plugins.md) 为准。

# 免费官方翻译与语音复核

2026-10-06。用户要求不自建，使用 Google 或其他免费的权威方案。此前 MyMemory 默认与自建默认方案已经撤换；旧 MyMemory 偏好迁移后不再向它发送文字。

## 当前选择

| 平台 | 默认实现 | 第一次使用 | 不支持默认实现时 |
|---|---|---|---|
| Android | Google ML Kit 设备端翻译；捆绑式本地语言识别 | 明确点击翻译后准备所选语言包，默认 Wi-Fi；设置可允许移动数据 | 显式打开 Google Translate 官方网页；不自动转发原文 |
| Mac 15+ | Apple 系统 Translation framework | 系统可能请求确认语言包下载；已有包直接翻译 | 可手动选择 Google 官方网页 |
| Mac 13/14 | Google 官方免费网页 | 点击打开网页，原文交给 Google | 手动复制网页译文返回应用 |

这些路径无需用户自己部署服务器、填写 API key 或购买 Cloud Translation API。Google 和 Apple SDK 不属于开源翻译引擎；用户最新的官方免费偏好优先。高级自定义 HTTP 入口保留给已经配置的用户，但不是默认，也不会把失败请求静默转到公共服务。

## Android Google 设备端

官方 [Android 翻译文档](https://developers.google.com/ml-kit/language/translation/android) 的版本为 `translate:17.0.3`，最低 API 23，项目 API 26 满足。语言识别采用官方 [捆绑式 `language-id:17.0.6`](https://developers.google.com/ml-kit/language/identification/android)，自动源语言识别无需下载这个识别器模型。识别不确定时提示用户选语言；已是目标语言时保留原文。

`GoogleTranslationService` 将识别、语言包准备、实际翻译拆成可取消的阶段，只有用户发起请求后才创建 SDK client。成功与失败均释放 Translator/LanguageIdentifier；取消隔离晚到回调并释放客户端。SDK 管理的模型下载可能继续，界面只说取消本次翻译，不承诺取消全局下载。源文有长度上界，等待过程显示真实阶段，模型失败不会伪造译文或静默调用自建/收费服务。

翻译模型与 Google Translate 应用离线模式采用同类模型。官方说明它适合日常简单翻译，非英语语言间可能经英语中转，不能承诺与 Google 网站或付费 Cloud Translation 完全相同的质量：[能力和限制](https://developers.google.com/ml-kit/language/translation)。首次语言包约 30 MB，官方建议只在需要时下载，默认 Wi-Fi。

原文设备处理不等于 SDK 零联网。Google 会收集设备、应用、配置语言、事件和性能等诊断信息；翻译文本不作为服务器翻译请求发送：[数据披露](https://developers.google.com/ml-kit/android-data-disclosure)。结果旁使用官方 powered by Google Translate 徽章，按钮注明 Google 翻译，遵循 [归因指南](https://developers.google.com/ml-kit/language/translation/translation-terms)。徽章是官方下载的原样 PNG，不是仿制图。

## Mac 系统翻译与 Google 网页

`NativeTranslationView` 只在 macOS 15+ 构建 `.translationTask` 会话；点击翻译产生任务配置，出现窗口或读取选区不会自动翻译。自动源语言保持 Apple 会话的 source=nil，由系统直接翻译并检测；NaturalLanguage 仅用于“已是目标语言”的原文捷径。显式源语言才先准备语言包，避免 auto 先 prepare 缺少源语言的错误。系统包已安装时保留原目标绑定；需要系统下载授权时先撤销文档写回能力，再显示授权流程，结果仍可复制。代际校验隔离关闭、取消、改语言后的旧结果。

正式实现不使用仅 macOS 26 提供的 `init(installedSource:target:)` 或 `cancel()` 来冒充 macOS 15 API。开发主机 27.0.1 的只读探针显示中英包已安装，使用 installed-only 初始化实际翻译合成 `你好世界` 得到 `Hello, world.`，没有下载新系统包或修改系统输入源。

官方说明所有 TranslationSession 翻译在设备处理，Apple 的 API 指标不含原文和译文：[TranslationSession](https://developer.apple.com/documentation/translation/translationsession)。Apple 系统翻译与 Google 网站属于不同模型，不承诺逐字相同。

Google 网页只构造 `https://translate.google.com/` 的 `sl`、`tl`、`text`、`op=translate` 参数，经 URL 编码后明确打开；不发送自建密钥，不打印含原文的 URL，不抓取网页结果或私有端点。浏览器实测合成 `你好世界`、中文到英语显示 `Hello World`。网页结果由用户复制，不能把打开网页当作已取得内嵌译文。

## 编辑器边界

手机保留工具箱及可选顶部工具栏，Mac 保留输入法菜单和独立窗口。只读取已选已上屏文字或手动粘贴，未确认拼音/手写不会作为源文。密码与隐私输入禁用；普通结果必须确认替换、插入或复制。

Android 绑定输入会话代际、连接引用、选区、原文与光标前后各 64 字符；写回前复核，变更后拒绝旧目标。Mac 绑定 owner/client/epoch/range/选区与前后文。写回会清理旧候选、组合与编辑器缓存，不能串进新输入框。取消和源语言变化使旧结果失效。

## 验证

Google 客户端有可注入任务边界，JVM 回归不下载模型，不用假译文替代生产 SDK。真实设备测试使用合成中英文字准备官方包并调用 SDK，结果按实际执行记录报告。Apple 桥接测试覆盖配置生命周期、取消、过期回调、源语言检测和写回边界；已安装系统包的真实翻译探针单独记录，不能将 mock 测试当作下载验证。

## Android 语音输入体验与 WeaveText 回归 / Android voice input and WeaveText regressions

本节是 2026-10-06 对 Android 官方 API、Gboard 官方帮助和 AOSP LatinIME 源码的核对，范围是 `VoiceSession`、`VoicePanel`、`VoiceScreens` 及现有语音测试。Android 平台没有规定所有输入法必须采用“按住说话”；按住/松手属于输入法自己的触摸交互，平台规定的是识别回调、输入连接和重启时序。

### 官方行为基线 / Official baseline

| 用户动作或能力 | 官方行为 | 对 WeaveText 的约束 |
|---|---|---|
| 点按开始、再点停止 | Gboard 的 Android 帮助以点按话筒开始，并说明再次点按话筒可暂停录音；高级语音输入也以点按话筒开始、点按或说“Stop”结束 | 点按模式必须有明确的开始、收音中、收尾和空闲状态；停止后先显示实时文字，不能把“停止收音”误当成“最终结果已到” |
| 按住说话 | Android `SpeechRecognizer` API 没有规定按住手势；`ACTION_UP`、`ACTION_CANCEL`、滑出取消和权限失败都由 IME 自己定义 | `VoicePanel` 必须把正常松手、系统取消和上滑取消分别测试；取消不能把未确认 composing 留在编辑器里 |
| 停止与重录 | `SpeechRecognizer.stopListening()` 会把已采集音频当作用户此时停止说话，并要求调用方等待 `onResults` 或 `onError` 后才能再次 `startListening()` | “重录”必须丢弃旧会话并隔离旧回调；若识别器实现不能立即复用，界面要明确显示收尾/重试状态 |
| 实时文字 | `RecognitionListener.onPartialResults()` 可以每次识别返回零次、一次或多次，服务实现可以忽略 partial 请求 | partial 是可替换的预览，不可追加成重复文字；没有 partial 也必须能在 final 时正常上屏 |
| 最终结果与候选 | `onResults()` 默认是从本次 `onReadyForSpeech()` 起的整段结果；API 33 起可用 segmented session 的 `onSegmentResults()` | 若插件回调契约使用“分段 final”，系统识别适配器必须先把整段结果转换成分段或替换事件，不能无条件把累计全文 append 两次 |
| 候选确认 | AOSP LatinIME 的历史语音实现限制候选数量并建立 alternatives，再把结果交给输入法 UI；这说明“识别结果”和“用户确认上屏”是两个阶段 | 多引擎结果可继续逐行显示；`LOADING`、`ERROR`、无文字的 `TIMEOUT` 行不可确认，确认后必须结束旧会话 |
| 纠错 | Gboard 官方帮助允许点选误识别的词，再重新说、输入、拼读或选择建议替代词 | WeaveText 当前有插件 `onReplace(old,new)`，但没有面向用户的词级选择/建议入口；插件纠错只能在受控会话窗口内更新，不能让任意晚到回调写入新输入框 |
| 语言切换 | Gboard 官方帮助支持通过语言键盘切换，也在高级语音输入中说明自动检测/手动切换语言 | 切换语言要停止或取消旧会话，清掉旧 partial，重新选择与档位匹配的模型，并让旧回调失效；不能把旧语言结果写进新会话 |

原始来源：[`SpeechRecognizer`](https://developer.android.com/reference/android/speech/SpeechRecognizer)（`stopListening`/重新 `startListening` 时序）、[`RecognitionListener`](https://developer.android.com/reference/android/speech/RecognitionListener)（partial、final、error、language detection 回调）、[`RecognizerIntent`](https://developer.android.com/reference/android/speech/RecognizerIntent)（`EXTRA_PARTIAL_RESULTS`、`EXTRA_LANGUAGE`、segmented session）、[`InputConnection`](https://developer.android.com/reference/android/view/inputmethod/InputConnection)（`setComposingText`、`commitText`、`finishComposingText`）、[Gboard：用语音输入文字](https://support.google.com/gboard/answer/2781851?hl=en)、[Gboard：高级语音输入](https://support.google.com/gboard/answer/11197787?hl=en)，以及 [AOSP LatinIME 的 VoiceInput.java](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/16668d952ff1ba71dcd61ceea809c82463b3d0e1/src/com/android/inputmethod/voice/VoiceInput.java)。AOSP 源码链接是历史实现参考；按住手势仍是 WeaveText 自己的 UI 合约。

### 当前实现与测试覆盖 / Current implementation and coverage

- `VoiceSession` 的状态是 `IDLE → CONNECTING → LISTENING → FINALIZING → IDLE`，多引擎停止后进入 `CHOOSING`，错误进入 `ERROR`。单引擎 partial 通过 `InputController.voicePartial()` 写入 composing，final 通过 `voiceFinal()` 提交；多引擎只在候选行确认时提交。
- `VoicePanel` 点按模式在话筒上点按开始/停止；按住模式在 `ACTION_DOWN` 开始，普通松手停止，向上超过 64dp 后松手取消，系统 `ACTION_CANCEL` 也取消。语言档位点击会取消会话、停止语音条并使旧回调失效。`VoiceScreens` 负责插件、模型、语言档位和“同时使用”设置，不能代替键盘面板里的会话状态测试。
- 新增的 [VoiceInputGestureTest.kt](/Users/sheep/Public/git/WeaveText/android/app/src/test/java/com/weavetext/ime/ui/VoiceInputGestureTest.kt) 覆盖点按开始/停止、按住松手/上滑取消、切换 English 后丢弃晚到结果。
- [MultiVoiceSessionTest.kt](/Users/sheep/Public/git/WeaveText/android/app/src/test/java/com/weavetext/ime/ime/MultiVoiceSessionTest.kt) 新增候选列表重录、失败/等待行不可确认；原有测试继续覆盖主引擎预览、超时、取消、收起键盘自动确认和相同结果仍需选行的行为。
- [VoiceSessionRecoveryTest.kt](/Users/sheep/Public/git/WeaveText/android/app/src/test/java/com/weavetext/ime/ime/VoiceSessionRecoveryTest.kt) 新增会话结束前的插件纠错；原有测试覆盖收尾超时、卡住接管、错误释放麦克风、回车/删除前 settle 和结束后的晚到回调隔离。
- `androidTest` 目前的 [VoiceSettingsDeviceTest.kt](/Users/sheep/Public/git/WeaveText/android/app/src/androidTest/java/com/weavetext/ime/ui/VoiceSettingsDeviceTest.kt) 验证真实 Android 窗口中的语音插件配置；麦克风音频和系统识别服务不能在稳定的无设备测试中伪造，面板触摸状态因此放在 Robolectric 测试。

### 发现历史、修复状态与保留边界 / Findings, fixes, and retained boundaries

1/2/3/6 为已修复的历史发现；4/5 继续作为用户纠错及 final 事件语义的能力边界保留。

1. **已修复：无引擎 + 按住模式时缺少引导。** 历史实现仅在 `engines > 0` 时开始会话，空引擎按下/松手没有下载或导入反馈。当前按住入口统一调用 `session.start()`；空引擎分支检查模型准备并触发 `onNoEngine`，保留面板下载/导入入口，不启动录音。`VoiceInputGestureTest.holdingWithoutAnEnginePreparesModelsAndKeepsDownloadAndImportActions` 覆盖此行为。

2. **已修复：停止后立即点话筒被前 8 秒收尾保护吞掉。** 历史 `FINALIZING` 分支提前返回 `true`，造成重录点按没有反馈。当前 `restart()` 先取消旧会话再开始，`start()` 在收尾态也先取消；面板点按/按住入口接入重录，并显示“取消并重录”文案。会话 token 隔离旧回调，`VoiceSessionRecoveryTest` 覆盖立即重录及同步旧 `onEnd` 不能恢复旧 partial。

3. **已修复：跨输入框取消清掉新字段的 composing。** 历史 `voiceCancel()` 直接取当前 `ic()`，A 会话取消可能改到 B 字段。当前取消与 partial/final/replace 同样通过 `voiceIc()` 检查起始字段和 `voiceEpoch/inputEpoch`；相同 package/field id 的新会话也拒绝旧结果。`TranslationTargetTest.cancelAndLateVoiceResultDoNotTouchANewFieldWithSameId` 覆盖该回归。

4. **插件纠错和 Android 用户纠错之间还有能力断层。** `VoiceSession` 的 `live()` 只在 active 或 `CHOOSING` 接受 `onReplace`；识别已经 `onEnd` 后，晚到纠错会被丢弃。这保护了“旧会话不能恢复已删除文字”，现有 `endedSessionCannotRestoreDeletedText` 已锁住该安全边界，但它也意味着当前没有 Gboard 式的“点词 → 重新说/选候选”用户流程。主 agent 应在 `InputController` 的选区/重选功能接好后，再增加带会话 token 和目标选区的显式纠错入口；不要放宽为任意晚到插件回调。

5. **整段 final 与分段 final 的接口语义必须固定。** Android `onResults()` 给的是整段识别结果，而 `MultiEngineResults.final()` 会按“新分段”追加。当前插件 API 的注释采用分段语义，因而系统适配器或未来插件若把累计全文当成分段 final，会稳定地产生“上一句重复”。主 agent 应在适配层明确 `full-session`、`segment`、`replace-range` 三种事件之一，或给 final 增加 segment id/范围；现有测试只覆盖分段累积，还没有累计全文误传的回归样例。

6. **已修复：“相同结果直接上屏”的注释与确认交互不一致。** 历史类注释与候选确认测试冲突。当前 `VoiceSession` 注释明确结果相同也保留各行供用户确认；`MultiVoiceSessionTest.identicalResultsStillRequireChoosingARow` 继续锁定此行为，不自动提交相同结果。

### 精度观察 / Accuracy observations

- `TwoPassRecognizer.clean()` 会把“全大写字母串”按普通英文词归一化，只保留少数硬编码缩写（例如 `API`、`USB`）。因此专名或用户有意输入的全大写词（例如 NASA）可能被改成小写；这是可重复的规范化损失，不应通过 UI 测试伪装成模型精度提升。建议主 agent 用语音评测集决定保留原大小写、仅修复模型常见全大写伪影，或把大小写作为候选而不是无条件修改。
- English 档位会拒绝含汉字的终稿，混合档位才接受中英混说；这是一条明确的语言约束，不能把“英文档识别中文姓名失败”误报为随机精度问题。应在语言切换 UI 和测试中继续显示该边界。
- 实时 partial 允许为零次，因此“没有字幕”不能判定录音失败；验收应看最终提交、收尾超时和再次录音，而不是要求每台设备都产生 partial。

2026-10-06 本轮执行结果：Android 翻译定向 JVM 65 项通过；初始化修复后对应定向回归通过。macOS 41+176=217 项通过，两种架构均构建成功，Translation.framework 以 LC_LOAD_WEAK_DYLIB 链接。Android 36.1 独立模拟器经官方 SDK 下载所需语言包，合成 `你好世界` 中译英、`Hello world` 自动识别后译成中文通过；同时验证客户端重复创建、选区写回、跨框拒绝与密码禁用。

真实 SDK 检查发现过重复初始化失败：ML Kit 的 ContentProvider 已初始化，重复调用 MlKit.initialize 会抛状态错误。已移除重复调用，新增 multipleSdkClientsReuseProviderInitialization 设备回归；失败没有计作翻译通过。Google native JNI 的 ELF load segments 均为 16384 字节对齐。模拟器首次软件 GPU 启动时出现系统卡住/未 attach，重启使用 host GPU 后才得到上述 SDK 结果；这不是手机识别质量的评测。
