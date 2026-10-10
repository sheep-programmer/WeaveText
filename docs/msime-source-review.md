# 水杉官方源码复核：平台、选择代次与语音收尾

取证日期：2026-10-10。**本轮用户已明确授权读取官方源码；本文件 supersedes [旧比较报告](msime-comparison.md) §2.1 的“未授权／未读取竞品源码”范围限制，仅适用于本轮。**旧报告此前的文档取证仍按其证据等级理解，由 parent 更新旧文档。本轮只新增本文件，不修改代码或其他文档，不构建、不安装、不运行输入法、不下载模型；只总结设计和静态证据，未移植源码、提示词、词库、模型或测试数据。

WeaveText 阅读基线 HEAD：`2878db5b288fe9dd5f62d670a7b35c531b2ab978`，另有大量并行工作区修改。parent 已告知正在实现 Android 硬件候选上／下、Tab／Shift+Tab、Page±9、当前页数字确认和 selected hint，处理 VoiceUpgrade 取消／下载阶段；Mac、Android agents 正在修语音 lifecycle。下文将这些记作**并行实施项的验收补充**，不再宣称它们是无人处理的缺口。工作区链接表示阅读时状态，不是该 HEAD 的不可变内容。

## 1. 固定来源及可用性边界

官网 [code 页面](https://msime.app/code/) 明确指向 `msime` 主仓库和 `msime-windows` Windows 正式产品仓库。本轮在工作区外浅克隆，采用 GitHub API、源码和官方平台文档，未使用浏览器，未创建 TaskSpace。下列链接均固定到本次检出的完整 SHA。

| 来源 | 固定 commit | 许可证据及边界 |
| --- | --- | --- |
| 水杉主仓库 `develop` | [6c9efc64abec8cf826fe86731bb3dd6d1109b363][mc] | [README][mread] 明确 GPL-3.0-only；[LICENSE][ml]。模型、SDK、运行库各自授权。 |
| 官方 Windows 产品 `develop` | [0d07b93268847348e43905e0e961de239a387a56][wc] | [根 LICENSE][wl]、[voice LICENSE][wvl] 为 GPL v3 文本；第三方依赖另有许可。 |
| 官方云服务，补充核查实际音频去向 | [5be3131e08263cb7288f25790d937e2462dfe78a][cc] | 本次树中未找到项目根 LICENSE／COPYING，不能把官网页脚或客户端 GPL 自动套给整个服务；[固定目录树][ct]。 |
| FUTO Voice Input 官方 GitHub 镜像 `master` | [d6e1eb2d139dc1a6342a4681c283686cca4bfceb][fc] | [FUTO Source First 1.0][fl]，有非商业等限制；属于源码可读项目，不将其称为 OSI 开源。 |
| Handy 官方仓库 `main` | [f6b3f8297061acaa763a48c4cdefbbf342c814ac][hc] | [MIT][hl]；模型许可另计。 |
| WhisperTypeKeyboard 官方仓库 `main` | [169626ba5a7e013adeac1c06ed487bc2ef0b851e][tc] | [MIT][tl]；Sherpa、Whisper 权重另计。 |

**代码接线、条件可用、已发布、真机通过是四件不同的事。**API 核对到 Android [`android-v0.3.1`](https://github.com/metasequoiaime/msime/releases/tag/android-v0.3.1) 对应 `b5c1b46ad049480420c9501e3bd85ac7fbc2ed26`，Mac [`macos-v0.52.0`](https://github.com/metasequoiaime/msime/releases/tag/macos-v0.52.0) 对应 `4758c648420cd198ddf5bba68d03f102092b3ac9`，Windows [`v0.9.5`](https://github.com/metasequoiaime/msime-windows/releases/tag/v0.9.5) 对应 `5d6eaa5bd1a6003e4bf25c4bef8c581443616da3`。这些都不等于本次默认分支 SHA；未逐项审计发布 tag 或安装包，不能保证本文分支功能已经包含在这些包中。

本次实证是“固定文件中存在可追踪的调用、条件或约束”。应用运行实测为零；仓库测试文件、Release 正文、模型目录中的“最好／最快”描述均不等于本次通过结果，不据此比较速度、准确率或稳定性。

## 2. 输入、候选与混输：源码能确认的范围

| 平台 | 输入与候选的实际路径 | 混输事实 |
| --- | --- | --- |
| macOS 主仓库 | 原生 IMK controller 调共享 runtime；左右键是组合光标移动，Delete 是前向删除，上下键是候选导航，分页受设置控制。[按键接线][mkeys] | 共享引擎把英文前缀候选加入中文候选；独立／临时英文是另外的状态，不能等同于中文句内拉丁片段解码。[查询][mmix] |
| Android 主仓库 | 原生 InputMethodService；展开候选保存 `session/generation/index`，经 `selectAnyCandidate` 回 runtime。预编辑行有点选组合光标的位置映射。[候选][acand]、[预编辑行][acaret] | 使用同一 Rust 查询层，但移动端按键、字段类型和组合显示另由宿主处理。共享引擎有功能不代表每个移动端动作都已实测。[查询][mmix] |
| 官方 Windows 产品 | TSF DLL → 管道 → Server → 仓内 C++ engine；组合局部编辑和取首／尾汉字有实际实现。[编辑][wedit]、[取字][wedge] | Server 后台查英文前缀，最多 5 项；仅全串 ASCII 字母进入该查询，大写先转小写，非字母不查。任务结束按 generation 检查过期。[混候选][wmix] |

主仓库 `Queries::mixed` 的英文查询条件是整个 prefix 为小写 ASCII，并有方案、开关、最短前缀门槛；它是**候选合并**证据，不能证明双拼句中 `GitHub/API` 与中文片段共同解码，更不能证明英文大小写、URL、数字符号保真。[mmix] 织文 [Session](../core/weave-engine/src/session.rs)、[Decoder](../core/weave-engine/src/decoder.rs) 已有全拼 `decode_with_latin`；应保护这项能力，再验证双拼片段与宿主收尾，不因竞品“混输”文案重做整串英文候选。

### 2.1 候选身份比文本相等更强

1. 主仓库 runtime 在选择、取字、固定、移除等动作中检查 `session + generation + index`；普通选择还限制当前页，展开列表走另一种选择动作。失焦不执行普通输入动作，旧身份返回 `StaleCandidate`。[runtime][rid]
2. Mac 数字／空格选择专门读取**已绘制按钮**的候选 ID，而不是直接取最新 `_view` 的同槽位项。源码解释的触发窗口是引擎先换代、AppKit 尚未重绘；宿主先检查 ID，再由 runtime 检查。[可见候选身份][mvisible]
3. Android 展开按钮闭包保留原 ID，检查 session，并把 generation/index 传入 native；这比“点击时重新找同序号词”更可追踪。[acand]

对 parent 当前硬件候选工作，最有价值的补验收是：显示第 2 页后引擎排序改变，界面尚未刷新时按 `2`；处理结果必须对应用户所见的有效候选，或拒绝过期动作。还要覆盖“候选文本一样但 session／generation 已变”“同词不同 canonical reading”“旧展开菜单／取字动作迟到”。单独比较文本不能证明它仍属于原输入会话。此处借鉴的是身份契约，不要求复制竞品 ID 格式。

## 3. 官方语音流程：逐平台核查

### 3.1 macOS：系统离线条件、本地 helper、云与润色分别判断

- **系统路径**：Mac 26+ 且编译器满足条件时尝试 SpeechAnalyzer；只对准备完成的 locale 创建 session，首次请求会后台检查／安装语言资源，本次录音先回退到旧 API。`finishAudio` 结束输入并 finalize；取消清空 handler 并 cancel analyzer。[Analyzer][manalyzer]
- **旧系统 API 不是强制离线**：`SFSpeechRecognizer` 仅在 `supportsOnDeviceRecognition` 为真时设置 `requiresOnDeviceRecognition`。因此这个路径在缺少离线能力的 locale 可能使用 Apple 服务，不能统称“系统离线”。Apple 官方文档确认[能力条件](https://developer.apple.com/documentation/speech/sfspeechrecognizer/supportsondevicerecognition)与[请求约束](https://developer.apple.com/documentation/speech/sfspeechrecognitionrequest/requiresondevicerecognition)。[实际分支][msystem]
- **本地路径已接线**：provider `local` 走 `LocalVoiceRequest`，把音频交给独立 `msime-voice-local` helper。start/audio/finish 排在串行队列，request 有 session ID 和状态；finish 与 cancel 是不同操作，旧 helper 消息必须匹配 ID。helper 协议有行长、排队字节限制；模型 manifest 和运行库实际存在才可用。[宿主路由][mroute]、[本地 request][mlocal]、[helper][mhelper]
- **停止与迟到终稿**：先关闭采集、交尾音频，再 finish；不会把 stop 等同于 cancel。系统路径收尾定时为 30 秒，启动时另有 100 秒保护；这些只是上限。最终写回同时校验原 client、session、voice generation、request/token，并有 final-once 标志。系统 Speech 回调还先回主线程检查 transcription generation。[收尾／归属][mfinish]、[msystem]
- **云／整理**：豆包流式与 HTTP 转写有独立适配；本地或系统结果也能进入用户开启的 HTTP 润色，失败保留原识别文字。因此“ASR 本地”不意味着后续文字不出设备。[润色与回退][mpolish]、[mfinish]

织文读取时 [VoiceWindow](../macos/Sources/WeaveText/VoiceWindow.swift) 已要求 `supportsOnDeviceRecognition && isAvailable` 并强制 `requiresOnDeviceRecognition = true`，缺资源明确报错；这是应保留的隐私边界。已有 generation、stop/endAudio、15 秒收尾、草稿确认及最多三引擎，不应列为缺失。与竞品可取证的差距是系统资源准备、能力分级及受控本地模型入口；当前源码未见 SpeechAnalyzer 接线。agents 正在修 lifecycle，以上只是审阅快照。

### 3.2 Android：默认系统服务与明确选择的本地后端不同

- 默认系统路径使用 `createSpeechRecognizer` 和普通识别 Intent，不是 `createOnDeviceSpeechRecognizer`，也未设置离线偏好。Android [官方 SpeechRecognizer 文档](https://developer.android.com/reference/android/speech/SpeechRecognizer)说明服务可能把音频发往远端，并提供独立的设备端能力 API。因此源码只能证明“系统服务接线”，不能保证离线／免账号／任意设备可用。[Intent][aintent]、[键区系统入口][aplatform]
- 本地路径使用 AudioRecord 16 kHz 单声道 PCM16 → capture queue → JNI → 共享 LocalAsr。采集先于冷模型加载，保存首次说话音频；stop 设置停止采集，decode 等采集结束且队列清空，再 `localSpeechFinish`。cancel 另设取消标志并取消 native session。[录音与收尾][alocal]
- 本地录音上限 60 秒、可接收 transcript 上限 2000 code points；这是移动端产品约束，不能由 Mac／共享层推断成任意长语音。运行库可能随包，也可能需先下载；manifest 缺失／模型不适配／运行库缺失／权限／空结果分别处理。[本地策略][apolicy]
- 明确选择本地模型后，模型失效不会偷偷改走系统服务。另有用户开启的离线 fallback，用于网络不可用且本地模型已装的情况；这不是未配置模型时万能兜底。[策略][apolicy]、[路由][achoose]
- 键区系统服务在尚未开始聆听时失败，只有部分错误转 Activity 重试；已经停止或聆听后的失败按错误码报告。所有交付检查当前 generation；取消会推进 generation，迟到文字不再交付。[错误／交付][adeliver]
- 润色可作用于本地、系统或云结果；超时／非 2xx／坏 JSON／空文返回失败并保留原文，Activity cancel 可断开 polish 连接。键区系统结果的 polish 使用单独 worker 与 generation 门禁，不能由“取消不上屏”推导后台网络一定即时结束。[整理策略][apolpolicy]、[HTTP 行为][apolish]、[adeliver]

织文 [voice-research](voice-research.md) 已记录 Silero 分句、长停顿不关麦、双遍终稿、模型冷启动、三模型独立候选和迟到结果验证。当前 [VoiceBackend](../android/app/src/main/java/com/weavetext/ime/voice/VoiceBackend.kt) 也有尾块投递与 cancel generation，[InputController](../android/app/src/main/java/com/weavetext/ime/ime/InputController.kt) 记录开始录音的 field/inputEpoch；这些是已有保护，不能泛化为“缺少离线／VAD／多模型／目标检查”。

### 3.3 官方 Windows 产品：语音为云路径，仓库内的 VAD 不等于产品接线

- 产品 provider 列表是豆包、OpenAI、SiliconFlow、Groq；对应默认模型包括 `whisper-1`、`whisper-large-v3-turbo` 等**服务端模型 ID**。入口没有主仓库 Sherpa local 路由，也没有 Windows 系统听写接口；不把主仓库 Windows 宿主的实现外推到正式产品。[providers][wproviders]、[产品录音入口][wvoice]
- Server 采集回调只做音量显示、豆包音频发送或全量 samples 缓冲，没有调用 `VadSegmenter`。仓内 RMS 分段器用于另一个 `engine/voice/platforms/windows` 独立工具；Silero 资源文件存在本身也不能证明实际调用，更不能认定产品具备本地 ASR。[采集][wcapture]、[独立工具][wtool]、[RMS 分段器][wvad]
- stop 先停止 capture，再取得尾音频；小于 0.25 秒的录音丢弃。豆包 Finish 排空完整 chunk、发送负序号结束帧并取终稿；服务器分句参数与本地 VAD 是两件事。批量识别上传 WAV，连接／总超时为 15／60 秒，SiliconFlow 分支允许一次额外尝试。[stop][wstop]、[豆包收尾][wdoubao]、[批量 HTTP][whttp]
- cancel 推进 `g_voice_session`，最终提交检查 session，能阻止旧会话结果上屏；正在运行的 batch Recognize／Polish 没有接该取消标志的 curl 中断回调，网络可能继续到返回／超时。旧任务错误提示也不能仅靠最终提交门禁认为一定消失。[cancel 与终稿][wstop]、[HTTP][whttp]
- **静态目标归属风险**：最终 TSF 发送读取当时 `GetActivePipeClient()`；`SetImeActive(false)` 只在 `g_recording` 时排 cancel。已停止、仍在后台识别时换输入框，voice session 门禁不等于原输入框归属；本次未运行，不能宣称已经复现串框。TSF 失败还可走 SendInput／Ctrl+V，不能把 fallback 当作“安全写回原框”的证明。[发送][wtarget]、[失活][wdeactivate]
- 润色是可选后续 HTTP，总超时常量为 3 秒；失败回原文，最后以 TSF／SendInput／Ctrl+V 上屏。它自动提交终稿，与织文 Mac 草稿确认和 Android 多模型手选的交互不同，不是应统一照搬的规则。[整理][wpolish]、[发送][wtarget]

补充的官方云仓库是**网关**证据：批量转写检查 WAV 后转交配置的上游，并使用服务端配置模型；流式入口转发到配置的豆包／兼容供应商。未见这些入口在网关本机执行声学模型，不能把“自有域名”当本地识别，也不能从源码样例推断生产账号、价格、额度或当前服务质量。[转写][ctrans]、[流式][cstream]

### 3.4 共享本地 ASR 的模型、VAD 与文字整理

| 层次 | 固定源码事实 | 不能由此推断 |
| --- | --- | --- |
| 运行库／模型 | Sherpa ONNX 1.13.8，有平台来源、size、SHA-256；模型目录完成后才写 manifest。[运行库锁][mruntime]、[目录][mmodels] | 本次未下载验证二进制或 weights，也未测内存／延迟。 |
| 目录条目 | X-ASR 中英流式，约 169 MB 已装大小、原生热词；SenseVoice 整句，约 230 MB、拼音后替换；Fun-ASR-Nano，约 1.05 GB、原生热词。以上大小来自目录声明。[mmodels] | 目录的“混说最好／速度最快”不作为准确率／性能证据。 |
| 流式端点 | 明设 trailing silence 2.4／1.0 秒、最长 utterance 20 秒；finish 加 0.6 秒零样本、InputFinished 再 decode。[端点][mendpoint]、[finish][mvad] | 参数不是实测尾延迟，补零不是新的人声。 |
| 整句 VAD | Silero threshold 0.5，512 样本窗口；静音 0.5 秒、人声最短 0.25 秒；最大段 Nano 20 秒、其他 28 秒。逐窗口送 VAD，finish flush 剩余段。[mvad] | 分句不等于静音停止整次录音；也不证明任意弱音／短词不丢。 |
| 文本整理 | 拼接拉丁段时加必要空格，清除中文标点周围空格、合并孤立大写缩写；SenseVoice ITN 打开。拼音热词替换是识别后的字面改写。[格式][mtidy]、[热词][mhotwords] | 格式规则不是声学精度提升；同音／模糊音替换可能改掉正确原文，未测误改率。 |

许可证必须按**项目源码／运行库／权重／转换产物**分别登记。主目录把 SenseVoice 权重标为 FunASR Model License 1.1，而不是以 Sherpa Apache-2.0 代替；本次另核对了 [FunASR 模型条款固定文件][funlicense]和 [SenseVoice 仓库源码 MIT][senselicense]，两者不是同一个授权对象。织文当前模型目录已单列 SenseVoice 的 FunASR 许可，本轮不声称缺少登记。[本地目录](../android/app/src/main/assets/models/catalog.json)

## 4. 三个补充官方项目：真正可借鉴的语音设计

| 项目 | 实际接线与设计证据 | 收尾／取消／真实可用性边界 |
| --- | --- | --- |
| FUTO Voice Input | Activity 支持 `RECOGNIZE_SPEECH` 结果交付，另有 voice IME；本地 Whisper。WebRTC GMM VAD，16 kHz、480 样本帧；有说话后连续非语音 >66 帧触发结束，>33 帧给即将结束状态，开关可关闭。约 2 秒来自帧数计算，不是测量。[Activity／IME][fmanifest]、[VAD][faudio] | stop 开始整句 decode，cancel 清 recorder/job；native infer 旁仍有取消提前退出的 TODO，不能保证推理即时终止。`WhisperRecognizerService` 回调为空且 manifest 的该 service 被注释，不能作为可选系统 RecognitionService 后端。[native 边界][fmodel]、[空服务][fstub]、[fmanifest] |
| Handy | 本地 ASR engine 路由有 Whisper、SenseVoice、Parakeet、Moonshine 等；VAD 有前缓冲、连续起音确认、尾部 hangover，流式／整句策略分开。stop 等采集边界确认、排空 ring、flush 重采样尾部，并记录丢样本／VAD 未发尾部信息。[模型路由][hmodels]、[平滑 VAD][hvad]、[录音收尾][hrec] | stop 后 pipeline 保存 cancel generation，ASR 后、整理后、排到主线程后／真正粘贴前再检查。原识别／整理结果分开保存；可选整理允许外部 API 或平台能力，因此离线 ASR 不代表所有功能离线。它是桌面粘贴工具，取消代次不自动等于 IME field ownership。[最终动作][hactions] |
| WhisperTypeKeyboard | 模型准备后录音，16 kHz PCM16，stop 先等录音线程、取得 PCM、静音过滤、写 WAV，后台 Sherpa Whisper decode 再 commit。默认 `base.en`，目录只有 `tiny.en/base.en/small.en`，并显式传 `en`。[录音][taudio]、[模型目录][tmodels]、[识别][tengine] | 是停止后整句路径，不能作为中文／自然中英混说证据。join 有 1.5 秒期限且调用入口未见先切后台；不能借该实现认定主线程永不等待或尾音绝不丢。取消 coroutine 也不证明 native decode 可抢占。结果用当前 InputConnection 提交，不借此替织文已有 field/epoch 保护背书。[controller][tcontroller] |

FUTO 的 glossary 被当作模型 prompt，并有英文更适用的代码注释；Handy 的模糊自定义词后替换明确限制 ASCII，避免把英文 Soundex 套到 CJK。[FUTO prompt][fmodel]、[Handy 文本规则][htext] 这说明热词能力要按 tokenizer／模型／语言划分，不能因为某个项目“支持个人词典”就对中文宣称有效。

## 5. 对照织文的具体优先级与验收

优先级是本次设计建议，验收是待执行条件，不是竞品或织文的本次通过结果。既有 [msime-comparison](msime-comparison.md) A05/A06/A07/A08 与 [voice-research](voice-research.md) 保留为背景；以下只列源码复核后最有价值的补充，不重复扩展巨大路线图。

| 优先级／归属 | 具体差距或补强点 | 可验证验收 |
| --- | --- | --- |
| **P0；parent 候选工作补验收** | 当前页数字／空格确认需要绑定可见快照和会话代次；单靠 selected index、文本相等不足。参照 [rid]/[mvisible] 的触发窗口，独立定义织文身份。 | 第二页候选变更但尚未绘制时数字确认不选隐藏新词；同词不同代次拒绝旧点击；新 session 不能接受旧菜单／取字／翻译插入；纯注释更新与候选内容更新分开验证。硬件上下／Tab／分页同时覆盖末页不足 9 项和高亮可见性。 |
| **P0；Mac／Android agents lifecycle 补验收** | 已有 generation 和 Android field/inputEpoch；应分别验证采集、ASR、终稿、replace、最终 UI 操作的迟到边界。Handy 在主线程粘贴前再检查；水杉 Windows 展示“取消提交≠停止网络≠原框归属”。 | A 录音 stop→B 新会话→A partial/final/error/replace 迟到均不能改 B 的状态／草稿；同 app 同 fieldId 重新连接也拒绝旧结果；排到 UI 后再 cancel 必须不上屏。Mic 先释放、尾块先送，再 final；超时有保留草稿出口。手动重新选目标后的确认行为单独定义，不强套自动上屏规则。 |
| **P1；独立后续实现** | **原稿与整理稿分离**。织文双遍、标点已有，本次建议保留 ASR 原稿和处理来源，避免标点／缩写／ITN／热词或未来润色合并成不可恢复文本。不要默认新增云 LLM。 | 同一识别结果可看原稿；标点或整理失败保留原稿，取消整理不变成取消全部识别；`GitHub/API/v1.2.3/端口8080` 不经猜词“修正”为新内容；不存在可靠后处理支持的模型明确保持原识别。选择模型与选择整理版本不替用户提交。 |
| **P1；Mac 后续能力，独立于本轮 lifecycle** | [voice-research](voice-research.md) 的 Mac 系统离线＋插件已成立；主机缺系统资源时，受控本地包或 SpeechAnalyzer 资源准备可降低不可用比例。水杉展示两种接线，但其旧 API 云 fallback 不符合织文现有离线约束。 | 支持／不支持 locale、模型缺失、资源下载中、可识别分开；缺资源仍能拼音输入。准备完成后断网识别；不支持离线时不上送 Apple／云；原系统／插件路线仍可使用。模型许可、摘要、运行库、最低系统与 CPU 架构独立列出。 |
| **P1；混输与编辑后续** | 保留织文全拼片段解码，优先解决双拼内英文意图／宿主原码收尾；组合中间编辑和以词取字有明确竞品源码依据，比增加英文候选宣传更具体。[编辑][medit]、[取字][medge] | `women` 中文解释可保留、英文可主动选；全拼／小鹤双拼含 `GitHub/API/WiFi`，Enter 原码保留大小写；`foo_bar/GPT4o/v1.2.3` 不丢字符。中间删除只改目标片段，不提前提交右侧；取词中单字不错误学习整词拼音，旧取字菜单受代次检查。 |
| **P2；模型评测与热词** | 织文 [VoiceHotwords](../android/app/src/main/java/com/weavetext/ime/voice/local/VoiceHotwords.kt) 已有显式开启、个人词与 native transducer 适配；Mac 系统请求阅读时未见 contextualStrings。优先补模型／语言能力说明和误改评测，X-ASR 只作为可测候选，不直接设默认。 | 同一批获授权录音包含轻声、8 秒停顿、短英文、中文同音人名、纯静音；报告原始转写、CER／英文词保留、误改率、冷／暖加载、停止至终稿时间。启用热词后命中收益与新增误改同时给出；数据不同不作速度因果比较。 |

本次没有新增上述功能或测试，也没有确认并行代理已完成验收。交付时应按 parent／agents 最终 diff 更新“待补”状态，避免将阅读时快照写成发布后的缺陷。

## 6. 证据索引（file/commit permalinks）

[mc]: https://github.com/metasequoiaime/msime/commit/6c9efc64abec8cf826fe86731bb3dd6d1109b363
[wc]: https://github.com/metasequoiaime/msime-windows/commit/0d07b93268847348e43905e0e961de239a387a56
[cc]: https://github.com/metasequoiaime/msime-cloud/commit/5be3131e08263cb7288f25790d937e2462dfe78a
[ct]: https://github.com/metasequoiaime/msime-cloud/tree/5be3131e08263cb7288f25790d937e2462dfe78a
[fc]: https://github.com/futo-org/voice-input/commit/d6e1eb2d139dc1a6342a4681c283686cca4bfceb
[hc]: https://github.com/cjpais/Handy/commit/f6b3f8297061acaa763a48c4cdefbbf342c814ac
[tc]: https://github.com/Trion129/WhisperTypeKeyboard/commit/169626ba5a7e013adeac1c06ed487bc2ef0b851e
[mread]: https://github.com/metasequoiaime/msime/blob/6c9efc64abec8cf826fe86731bb3dd6d1109b363/README.md
[ml]: https://github.com/metasequoiaime/msime/blob/6c9efc64abec8cf826fe86731bb3dd6d1109b363/LICENSE
[wl]: https://github.com/metasequoiaime/msime-windows/blob/0d07b93268847348e43905e0e961de239a387a56/LICENSE
[wvl]: https://github.com/metasequoiaime/msime-windows/blob/0d07b93268847348e43905e0e961de239a387a56/engine/voice/LICENSE
[fl]: https://github.com/futo-org/voice-input/blob/d6e1eb2d139dc1a6342a4681c283686cca4bfceb/LICENSE.md
[hl]: https://github.com/cjpais/Handy/blob/f6b3f8297061acaa763a48c4cdefbbf342c814ac/LICENSE
[tl]: https://github.com/Trion129/WhisperTypeKeyboard/blob/169626ba5a7e013adeac1c06ed487bc2ef0b851e/LICENSE
[mkeys]: https://github.com/metasequoiaime/msime/blob/6c9efc64abec8cf826fe86731bb3dd6d1109b363/platforms/macos/src/input/InputController.mm#L5833-L5876
[mmix]: https://github.com/metasequoiaime/msime/blob/6c9efc64abec8cf826fe86731bb3dd6d1109b363/crates/engine/src/ime/queries.rs#L201-L240
[acand]: https://github.com/metasequoiaime/msime/blob/6c9efc64abec8cf826fe86731bb3dd6d1109b363/platforms/android/java/app/msime/android/core/ImeCandidates.java#L153-L216
[acaret]: https://github.com/metasequoiaime/msime/blob/6c9efc64abec8cf826fe86731bb3dd6d1109b363/platforms/android/java/app/msime/android/core/MSIMEInputService.java#L7470-L7504
[wedit]: https://github.com/metasequoiaime/msime-windows/blob/0d07b93268847348e43905e0e961de239a387a56/engine/core/input_session_editing.cpp#L160-L210
[wedge]: https://github.com/metasequoiaime/msime-windows/blob/0d07b93268847348e43905e0e961de239a387a56/engine/core/input_session.cpp#L352-L381
[wmix]: https://github.com/metasequoiaime/msime-windows/blob/0d07b93268847348e43905e0e961de239a387a56/server/src/mixed/mixed_candidates.cpp#L19-L142
[rid]: https://github.com/metasequoiaime/msime/blob/6c9efc64abec8cf826fe86731bb3dd6d1109b363/crates/input-runtime/src/runtime.rs#L1740-L1770
[mvisible]: https://github.com/metasequoiaime/msime/blob/6c9efc64abec8cf826fe86731bb3dd6d1109b363/platforms/macos/src/input/InputController.mm#L670-L720
[manalyzer]: https://github.com/metasequoiaime/msime/blob/6c9efc64abec8cf826fe86731bb3dd6d1109b363/platforms/macos/src/backend/voice/BackendSpeechAnalyzer.swift#L1-L152
[msystem]: https://github.com/metasequoiaime/msime/blob/6c9efc64abec8cf826fe86731bb3dd6d1109b363/platforms/macos/src/voice/VoiceInputService.mm#L150-L189
[mroute]: https://github.com/metasequoiaime/msime/blob/6c9efc64abec8cf826fe86731bb3dd6d1109b363/platforms/macos/src/input/InputController.mm#L3689-L3704
[mlocal]: https://github.com/metasequoiaime/msime/blob/6c9efc64abec8cf826fe86731bb3dd6d1109b363/platforms/macos/src/voice/LocalVoiceRequest.mm#L374-L492
[mhelper]: https://github.com/metasequoiaime/msime/blob/6c9efc64abec8cf826fe86731bb3dd6d1109b363/shared/voice/LocalAsrHelper.cpp
[mfinish]: https://github.com/metasequoiaime/msime/blob/6c9efc64abec8cf826fe86731bb3dd6d1109b363/platforms/macos/src/input/InputController.mm#L3980-L4096
[mpolish]: https://github.com/metasequoiaime/msime/blob/6c9efc64abec8cf826fe86731bb3dd6d1109b363/platforms/macos/src/voice/HTTPVoiceRequest.mm#L19-L60
[aintent]: https://github.com/metasequoiaime/msime/blob/6c9efc64abec8cf826fe86731bb3dd6d1109b363/platforms/android/java/app/msime/android/voice/VoiceRecognitionActivity.java#L600-L609
[aplatform]: https://github.com/metasequoiaime/msime/blob/6c9efc64abec8cf826fe86731bb3dd6d1109b363/platforms/android/java/app/msime/android/core/ImeVoiceEntry.java#L267-L315
[alocal]: https://github.com/metasequoiaime/msime/blob/6c9efc64abec8cf826fe86731bb3dd6d1109b363/platforms/android/java/app/msime/android/voice/LocalAsrRecognizer.java#L101-L315
[apolicy]: https://github.com/metasequoiaime/msime/blob/6c9efc64abec8cf826fe86731bb3dd6d1109b363/platforms/android/java/app/msime/android/voice/LocalAsrPolicy.java#L16-L66
[achoose]: https://github.com/metasequoiaime/msime/blob/6c9efc64abec8cf826fe86731bb3dd6d1109b363/platforms/android/java/app/msime/android/core/ImeVoiceEntry.java#L66-L127
[adeliver]: https://github.com/metasequoiaime/msime/blob/6c9efc64abec8cf826fe86731bb3dd6d1109b363/platforms/android/java/app/msime/android/core/ImeVoiceEntry.java#L322-L394
[apolpolicy]: https://github.com/metasequoiaime/msime/blob/6c9efc64abec8cf826fe86731bb3dd6d1109b363/platforms/android/java/app/msime/android/voice/VoicePolishPolicy.java
[apolish]: https://github.com/metasequoiaime/msime/blob/6c9efc64abec8cf826fe86731bb3dd6d1109b363/platforms/android/java/app/msime/android/voice/VoicePolisher.java#L20-L100
[wproviders]: https://github.com/metasequoiaime/msime-windows/blob/0d07b93268847348e43905e0e961de239a387a56/server/src/voice-input/voice_providers.cpp#L127-L190
[wvoice]: https://github.com/metasequoiaime/msime-windows/blob/0d07b93268847348e43905e0e961de239a387a56/server/src/voice-input/voice_input_service.cpp#L876-L966
[wcapture]: https://github.com/metasequoiaime/msime-windows/blob/0d07b93268847348e43905e0e961de239a387a56/server/src/voice-input/voice_input_service.cpp#L835-L858
[wtool]: https://github.com/metasequoiaime/msime-windows/blob/0d07b93268847348e43905e0e961de239a387a56/engine/voice/platforms/windows/src/main.cpp#L229
[wvad]: https://github.com/metasequoiaime/msime-windows/blob/0d07b93268847348e43905e0e961de239a387a56/engine/voice/src/vad.cpp#L10-L71
[wstop]: https://github.com/metasequoiaime/msime-windows/blob/0d07b93268847348e43905e0e961de239a387a56/server/src/voice-input/voice_input_service.cpp#L968-L1146
[wdoubao]: https://github.com/metasequoiaime/msime-windows/blob/0d07b93268847348e43905e0e961de239a387a56/server/src/voice-input/doubao_asr_client.cpp#L361-L523
[whttp]: https://github.com/metasequoiaime/msime-windows/blob/0d07b93268847348e43905e0e961de239a387a56/server/src/voice-input/voice_input_service.cpp#L505-L594
[wtarget]: https://github.com/metasequoiaime/msime-windows/blob/0d07b93268847348e43905e0e961de239a387a56/server/src/voice-input/voice_input_service.cpp#L726-L833
[wdeactivate]: https://github.com/metasequoiaime/msime-windows/blob/0d07b93268847348e43905e0e961de239a387a56/server/src/voice-input/voice_input_service.cpp#L1228-L1240
[wpolish]: https://github.com/metasequoiaime/msime-windows/blob/0d07b93268847348e43905e0e961de239a387a56/server/src/voice-input/voice_input_service.cpp#L596-L648
[ctrans]: https://github.com/metasequoiaime/msime-cloud/blob/5be3131e08263cb7288f25790d937e2462dfe78a/internal/server/handlers.go#L384-L470
[cstream]: https://github.com/metasequoiaime/msime-cloud/blob/5be3131e08263cb7288f25790d937e2462dfe78a/internal/server/stream.go#L36-L100
[mruntime]: https://github.com/metasequoiaime/msime/blob/6c9efc64abec8cf826fe86731bb3dd6d1109b363/resources/voice-runtime.lock.json
[mmodels]: https://github.com/metasequoiaime/msime/blob/6c9efc64abec8cf826fe86731bb3dd6d1109b363/resources/local-asr-models.json
[mendpoint]: https://github.com/metasequoiaime/msime/blob/6c9efc64abec8cf826fe86731bb3dd6d1109b363/shared/voice/LocalAsr.cpp#L648-L701
[mvad]: https://github.com/metasequoiaime/msime/blob/6c9efc64abec8cf826fe86731bb3dd6d1109b363/shared/voice/LocalAsr.cpp#L870-L952
[mtidy]: https://github.com/metasequoiaime/msime/blob/6c9efc64abec8cf826fe86731bb3dd6d1109b363/shared/voice/LocalAsr.cpp#L1027-L1045
[mhotwords]: https://github.com/metasequoiaime/msime/blob/6c9efc64abec8cf826fe86731bb3dd6d1109b363/crates/client-core/src/voice/hotwords.rs#L105-L155
[funlicense]: https://github.com/modelscope/FunASR/blob/58830eca4012644aac0c3218c3ccc7d98f003fda/MODEL_LICENSE
[senselicense]: https://github.com/FunAudioLLM/SenseVoice/blob/7e41210ed16d97de8a21b5fec764e0cc287c1d40/LICENSE
[fmanifest]: https://github.com/futo-org/voice-input/blob/d6e1eb2d139dc1a6342a4681c283686cca4bfceb/app/src/main/AndroidManifest.xml
[faudio]: https://github.com/futo-org/voice-input/blob/d6e1eb2d139dc1a6342a4681c283686cca4bfceb/app/src/main/java/org/futo/voiceinput/AudioRecognizer.kt#L352-L454
[fmodel]: https://github.com/futo-org/voice-input/blob/d6e1eb2d139dc1a6342a4681c283686cca4bfceb/app/src/main/java/org/futo/voiceinput/ml/WhisperModel.kt#L139-L186
[fstub]: https://github.com/futo-org/voice-input/blob/d6e1eb2d139dc1a6342a4681c283686cca4bfceb/app/src/main/java/org/futo/voiceinput/WhisperRecognizerService.kt
[hmodels]: https://github.com/cjpais/Handy/blob/f6b3f8297061acaa763a48c4cdefbbf342c814ac/src-tauri/src/managers/transcription.rs#L675-L718
[hvad]: https://github.com/cjpais/Handy/blob/f6b3f8297061acaa763a48c4cdefbbf342c814ac/src-tauri/src/audio_toolkit/vad/smoothed.rs#L1-L110
[hrec]: https://github.com/cjpais/Handy/blob/f6b3f8297061acaa763a48c4cdefbbf342c814ac/src-tauri/src/audio_toolkit/audio/recorder.rs#L871-L999
[hactions]: https://github.com/cjpais/Handy/blob/f6b3f8297061acaa763a48c4cdefbbf342c814ac/src-tauri/src/actions.rs#L688-L763
[htext]: https://github.com/cjpais/Handy/blob/f6b3f8297061acaa763a48c4cdefbbf342c814ac/src-tauri/src/audio_toolkit/text.rs#L29-L68
[taudio]: https://github.com/Trion129/WhisperTypeKeyboard/blob/169626ba5a7e013adeac1c06ed487bc2ef0b851e/app/src/main/java/me/trion/whispertype/voice/AudioRecorder.kt#L24-L133
[tmodels]: https://github.com/Trion129/WhisperTypeKeyboard/blob/169626ba5a7e013adeac1c06ed487bc2ef0b851e/app/src/main/java/me/trion/whispertype/voice/ModelCatalog.kt
[tengine]: https://github.com/Trion129/WhisperTypeKeyboard/blob/169626ba5a7e013adeac1c06ed487bc2ef0b851e/app/src/main/java/me/trion/whispertype/voice/SherpaWhisperEngine.kt
[tcontroller]: https://github.com/Trion129/WhisperTypeKeyboard/blob/169626ba5a7e013adeac1c06ed487bc2ef0b851e/app/src/main/java/me/trion/whispertype/ime/KeyboardController.kt#L725-L833
[medit]: https://github.com/metasequoiaime/msime/blob/6c9efc64abec8cf826fe86731bb3dd6d1109b363/crates/engine/src/session/editing.rs#L19-L177
[medge]: https://github.com/metasequoiaime/msime/blob/6c9efc64abec8cf826fe86731bb3dd6d1109b363/crates/engine/src/session/commit.rs#L35-L71
