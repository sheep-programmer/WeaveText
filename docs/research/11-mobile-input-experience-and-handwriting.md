# 11. 移动输入体验与手写：官方源码对照及本轮落地项

核对日期：2026-10-06。对象是**当前未提交工作树**，不是发布 APK 或 Git HEAD。只新增本研究文档；没有修改产品源码、重置旧修改、提交、推送或启动 APK/DMG 全量构建。源码行号可能随其他 agent 的并行修改移动，以下同时给出文件及函数名。

本轮落地与验收重点现为：**手写外观/触控笔压力、后台停笔提交与取消、四角缩放与独立停靠、笔画重做、模式与无障碍反馈**。主实现与接线现已完成；据主 agent 收尾反馈，HWR 渲染、手写及异步竞态 JVM 回归已通过，Floating 默认四角与 X 已视觉复核。新增外部光标移动取消迟到提交，Shared Rust 手写候选从 12 扩至 30，不增加 CNN 推理次数。Native/JNI/模拟器验收正在进行，由主 agent 补实际命令、设备及结果；本文不预先填通过。临时无痕和逐字分割/纠错入口仍列为后续缺失。

单字手写、候选、撤销/清空、光标面板、剪贴板保护、单手和悬浮已有实现，不能重新列为缺失。截至此次收尾，主 agent 同步的本轮准确率结论仍只有合成证据，融合未提高；没有可在本文宣称的真人整体模型提升。字体预览、模板或合成评测都不冒充真人识别准确率。本次不联网重查、不新建浏览器空间，沿用首版已经核对的官方来源。

## 1. 官方基线：值得借鉴什么，不能据此声称什么

选择 Rime/Trime、fcitx5-android/libime、HeliBoard、FlorisBoard，是为了覆盖中文方案、用户学习和 Android 键盘交互；没有用下载量或同一真人测试集给它们排强弱名次。源码固定在下列提交，官方 wiki/指南按核对日期读取。未安装或实机对打这些输入法。

| 项目及固定版本 | 官方证据与实际能力 | 对织文的意义与边界 |
|---|---|---|
| Rime `7bc3fb0` / Trime `1ba0780` | [Rime 定制指南][RimeGuide]给出方案、标点映射、候选和关闭用户词典/字频调整的方法；[Trime README][Trime]明确它是 Rime 的 Android 前端，[动作分发源码][TrimeActions]有方案切换、剪贴板窗口、候选选择、主题命令。 | 强项是方案/动作可配置及中文生态。织文已有固定方案和风格包，尚不能等同于任意 Rime schema/按键动作配置。Rime 的内核能力不能直接算作 Android 前端的悬浮、TalkBack 或手写能力；本次官方资料未建立其通用中文手写质量基线。 |
| fcitx5-android `e6199a2` / libime `171edcf` | [Android README][Fcitx]确认拼音、双拼、五笔/码表、插件、展开候选、剪贴板和长按符号；[libime 用户语言模型][LibimeUser]与[历史二元组][LibimeHistory]有保存、加载、遗忘及历史评分接口；[Android 输入服务][FcitxIme]维护组合串和选区。 | 中文学习应比较“选词后能改变排序、误选能撤销、历史能删除”，而非只比较词库条目数。README 的悬浮能力指**实体键盘的候选窗**，不能据此断言整块虚拟键盘可悬浮。libime 是内核库，不是手写识别器。 |
| HeliBoard `bc2b911`（原 Helium314 地址已重定向至 HeliBorg） | [README][Heli]列出词典、学习数据备份、剪贴板、单手、分体键盘，并说明没有网络权限；[官方隐藏功能][HeliHidden]包含光标/按词移动、选择、撤销/重做、无痕、候选删除；[弹出键无障碍源码][HeliA11y]处理浮层打开/关闭及触摸探索选键。 | 可直接借鉴交互和功能的可发现性。它的滑行输入依赖额外闭源库，官方称未随包提供；不能把这当成可复制的开源识别方案，也不能把多语种词典建议等同于中文拼音整句或汉字手写。 |
| FlorisBoard `fe1241f` | [README][Floris]列出剪贴板、主题、扩展、表情，同时明确当时发布版不带字词预测/拼写检查；[设置源码][FlorisPrefs]和[无痕枚举][FlorisIncognito]区分强制开/关与自动模式；[窗口控制器][FlorisWindow]实现悬浮窗口边界、移动/尺寸状态。 | 可借鉴明确的无痕状态和悬浮几何。源码存在某接口、路线图写某能力，都不等于发布版已经具备，更不等于在中文任务上优于织文。 |

这些基线支持“交互缺口”的结论，**不支持**“织文或某开源输入法识别率更高”的结论。

## 2. 当前 Android 功能对照

“接线完成”表示当前代码已有入口与处理路径；“JVM 回归通过”及“视觉已复核”注明来自主 agent 的验收反馈，本研究没有代跑这些检查。Native/JNI/模拟器的定向通过范围见验收补记；“部分”和“后续缺失”表示明确的范围/控制缺口。JVM 或视觉复核不等于全部真机通过认证。

| 功能 | 状态 | 当前证据、范围与差距 |
|---|---|---|
| 手写单字 | 已实现 | [HandPad.strokes][WHand]保存当前字轨迹；[InputController.onHandStrokes][WController]提交整字笔画；[hand_input / hand_models][WSession]调用模板+网络并给候选。手写布局并非只有画布。 |
| 连续连写 | 已接入；范围明确 | [SchemeScreens][WScheme]和键盘内切换提供单字／连续连写；完成一个字后下一次落笔确认前字并清出实时画布，旧墨迹进入淡出层；单字候选仍用原有模型。跨字一笔连接、任意长度和多行不在该机制覆盖范围。 |
| 单字内部连笔/潦草 | 部分 | [handnet.rs][WNet]将轨迹栅格化分类，网络路径不靠笔顺匹配；[hand.rs][WTemplate]的模板路径仍有笔数差距筛选。结构上容忍部分连笔，不能推导真人草书准确率。 |
| 笔锋 | 接线完成；HWR 渲染 JVM 回归通过 | [HandInkStyle][WInkStyle]定义圆珠笔、毛笔、铅笔、荧光笔 4 种样式；[HandwritingAppearanceSettings][WInkSettings]提供选择与真实路径预览；[HandPad.configure / add / end][WHand]按样式、速度及压力生成显示轮廓。[WeaveKeyboard][WKeyboard]已读取/监听偏好。JVM 结果据主 agent 反馈，Native/设备验证继续进行。 |
| 笔宽 | 接线完成；HWR 渲染 JVM 回归通过 | [HandInkPrefs][WInkStyle]规定 2–12 dp、默认 6 dp，设置滑块按整数 dp 更新；[HandPad.widthDp / density / configure][WHand]负责换算和重绘。旧 `strokeWidth`是兼容的 px 接口，不能再以旧 `6dp × iconScale`描述新偏好。 |
| 笔迹颜色 | 接线完成；HWR 渲染 JVM 回归通过 | [HandInkPrefs][WInkStyle]支持跟随主题、墨黑/蓝/红/绿/紫/橙 6 个预置色及自定义 `#RRGGBB`；[设置组件][WInkSettings]拒绝不完整/非法输入，保留上次颜色。[HandPad.inkColor / drawInk][WHand]解析颜色并叠加工具透明度，不再仅使用主题标签色。 |
| 触控笔压力 | 接线完成；渲染 JVM 回归通过，真实笔设备验收待完成 | [KeyboardView.inkPressure][WView]只对 `TOOL_TYPE_STYLUS`读取当前及历史压力，手指使用中性 `1f`；[HandPad.begin/add][WHand]接收压力并只影响显示宽度。没有据此宣称倾角或完整掌拒已实现，也不推导识别准确率提升。 |
| 手写撤销 | 已实现 | [HandPad.undo][WHand]移除最后一笔；[WeaveKeyboard.onKey][WKeyboard]同步调用内核退格并更新候选。与编辑器文本撤销分开。 |
| 笔画重做 | 接线完成；手写 JVM 回归通过 | [HandPad.canRedo / redo][WHand]及几何/显示样本栈通过[模式键长按][WKeyboard]接入，`KeyboardView.redoStroke → InputController.replaceHandInk`恢复笔画并更新候选。普通重写语义不变；Native/设备验收继续进行。 |
| 手写清空/重写 | 已实现，保持原语义 | [clearHand][WKeyboard]清引擎状态及墨迹；长按删除在有笔迹时仍然重写后停止连删；[HandPad.cancel/reset][WHand]区分取消当前一笔和整体重置。新增笔画重做走模式键长按，不把清空/重写改成重做。 |
| 单字/整词候选及提交 | 接线完成；Shared Rust 候选 12→30，Native 链路验收中 | [InputController.onCandidate][WController]、[CandidateGridPanel][WCandidates]有选择及展开；[session.rs][WSession]将 `HAND_CANDIDATES`改为 30，[rank_hand][WRank]输出整词候选。扩大已有结果的候选预算，不新增 CNN 推理；不等于首选准确率提高。目前没有可选字组、逐字候选及用户重分割入口。 |
| 自动提交/手动确认 | 接线完成；手写/异步 JVM 回归通过 | [applyHandPause][WKeyboard]与[KeyboardView][WView]控制停笔策略；关闭 `HAND_AUTO_COMMIT`时保留笔迹/候选，不因后台暂时无候选而提前清墨迹。`handRecognizing`参与状态判断；停笔后的首选由后台识别完成后提交，见 [commitFirst / finishHand][WController]。不把首选排序解释为校准置信度；Native/模拟器已通过下文列明的 6 项定向流程。 |
| 识别中与迟到结果取消 | 接线完成；手写/异步 JVM 回归通过 | [InputController.discardHand / reset / onStartInput / onFinishInput][WController]使 generation 失效并清待提交任务；新增 `onSelectionUpdate`在外部光标移动时丢弃原位置的迟到提交，同时避免将正在应用的自身结果误判为外部移动。结果回来时检查 generation/引擎归属；[WeaveKeyboard.onState][WKeyboard]保留处理中墨迹。取消结果不等于强行中断已运行的原生计算。 |
| 光标移动 | 已实现 | [moveCursor][WController]优先 `setSelection`，按字形簇移动；[EditorCache][WEditor]及 ICU 辅助边界处理。终端、网址/邮箱、选区或待回报提交走方向键回退。 |
| 选区、按词移动、首尾、撤销/重做 | 已实现；兼容性待测 | [CursorPanel][WCursor]提供完整按钮；[cursorArrow / cursorWord / cursorToEdge / undoRedo][WController]调用 Shift/Ctrl 键或编辑器菜单动作。不同宿主可能不支持这些动作，不能据此宣称所有 WebView/终端兼容。 |
| 剪贴板 | 已实现 | [ClipboardRepo / ClipboardPanel][WClipboard]默认征询后才记录、支持当前项/历史/固定/清空；[ClipHistory][WClipHistory]未固定项 24 小时过期并有条数/体积上限。支持媒体路径，超过 fcitx README 所述纯文本范围；本轮未验证宿主媒体粘贴兼容性。 |
| 长按符号、上滑副字符 | 触摸已实现；无障碍部分 | [KeyboardView.onLongPress][WView]显示整个 `key.longPress` 列表；`A11ySource.a11yLongClick`只覆盖副字符及少数功能键，[PopupOverlay][WPopup]未提供浮层虚拟无障碍节点。 |
| 单手模式 | 已实现 | [applyOneHand / OneHandButton][WKeyboard]及[WeavePrefs.ONE_HAND][WPrefs]支持靠左/右切换；宽屏分体也已有开关。实际可触达性仍需小屏和大字体验证。 |
| 悬浮、移动、四角缩放、停靠 | 接线完成；默认四角/X 已视觉复核；无障碍部分 | [FloatingResizePolicy][WFloatPolicy]默认全部四角，允许任选非空集合；[FloatingResizeSettings][WFloatSettings]不能关闭最后一个角。[WeaveKeyboard][WKeyboard]通常预留顶部/底部各 48 dp 控制条，短窗口必要时降为各 24 dp；[FloatingGeometry][WFloatGeometry]与[控制器][WFloatController]支持贴边时平移放大，独立 X 停靠。键区按[指标下限][WMetrics]最小 32 dp/行（4 行 128 dp）约束，避免无限缩小。默认布局已视觉复核，Native/模拟器 6 项定向流程已通过；移动/缩放的等价无障碍动作仍待补。 |
| 字词学习 | 已实现 | [UserDict][WUserDict]保存词频、搭配及近期明确选词；[set_learning / undo_learning][WSession]有禁学习和误选撤销；[DictionaryScreens][WDictionary]提供用户词管理。可参照 libime 的遗忘/历史接口完善行为验证，不能说本项目“尚无学习”。 |
| 手写字形学习与清除 | 已实现 | [非首选纠正与 hand_models][WSession]、[save_hand_samples / clearHand][WFeatures]保存/恢复纠正样本并清除；[ClearHandLearningRow][WScheme]有清除入口。`tools/handnet/README.md`仍称仅当前进程且不写盘，**与当前代码不一致**。 |
| 基础无障碍 | 部分 | [VirtualA11y][WA11y]、[KeyboardView.A11ySource][WView]提供按键、候选等虚拟节点、朗读、点击/部分长按、滚动；[InkLayer][WInk]主动不接收无障碍事件。连写时手写区仍朗读“书写一个字”；完整长按浮层及悬浮手柄动作缺口见上。 |
| 密码/禁止学习输入框 | 已实现自动保护；用户控制部分 | [applyEditorInfo][WController]识别文本、可见/Web、数字密码及 `IME_FLAG_NO_PERSONALIZED_LEARNING`，禁学习并设置 `privateField`；[剪贴板过滤][WClipboard]拒绝敏感标记，不展示私密框历史。未找到普通框的临时无痕入口。 |
| 输入隐私与联网边界 | 部分；不能宣称全离线 | [AndroidManifest][WManifest]申请 INTERNET、关闭系统备份；[WeavePrefs][WPrefs]含可选互联、热词等联网功能。现有敏感框保护应保留，未来无痕必须覆盖历史、符号使用和互联链路；本研究没有审计全部网络请求。HeliBoard“无网络权限”的保证不能套用到织文。 |

Android 的[官方剪贴板指南][AndroidClipboard]说明敏感标记用于阻止 Android 13+ 的复制确认 UI 展示敏感内容。织文额外实现的历史/候选提示过滤仍须由自己的代码负责；不能只看系统标记就推导历史和互联已经受保护。

## 3. 手写链路的关键约束

### 3.1 显示质量与识别质量分别判断

当前链路为 `MotionEvent → HandPad → InputController → HandModels → rank_hand → 候选 → commitText`。`KeyboardView.inkMove`读取历史触点、事件时间和触控笔压力，`InkLayer`让移动只重画墨迹层；样式、速度、压力、平滑与尖尾用于显示，识别继续收到原始接受的几何点。外观变化不改变识别的 `minStep`过滤或坐标。证据：[KeyboardView][WView]、[HandPad][WHand]、[HandInkStyle][WInkStyle]、[InkLayer][WInk]、[InputController][WController]。

新 API 是 `HandInkAppearance(style, widthDp, color)`配合 `HandPad.configure(appearance, density)`；`widthDp`范围 2–12，颜色支持主题/6 预置色/完整 `#RRGGBB`。`HandPad.strokeWidth`保留为 px 兼容接口，不能继续以固定粗细描述新实现。圆珠笔/毛笔/铅笔/荧光笔分别有显示轮廓与透明度，设置预览直接调用 `HandPad`绘制；预览验证的是显示效果，不是识别率。[HandwritingAppearanceSettings][WInkSettings]

`begin/add`新增压力参数，旧签名使用中性压力；工具类型由 `KeyboardView`过滤，只有触控笔采样传入设备压力。外观读取、监听和 `handAppearance`应用已接线，主 agent 已反馈 HWR 渲染 JVM 回归通过；Native/JNI/模拟器流程已通过；真实触控笔硬件仍未验证。倾角和完整掌拒仍没有等同证据，代码结构或 JVM 结果也不能当成掉帧、时延或功耗实测。

### 3.2 连续连写按逐字确认处理

最终复核时，`line_groups`已包含横向空隙与形状宽度规则：先按约 `0.08 × 行高`收集组件，再结合 `0.23 × 行高`空隙、约 `0.55 × 行高`字宽及偏旁保护决定分组，并保留组内原笔顺；超过 4 组回退为一组。这些是**几何启发式**，不证明同一字的偏旁永不分开，或相邻汉字永不合并。见 [handnet.rs][WNet]。

`rank_hand`按 `LINE_PER_GROUP`个候选做组合，最终复核的常量为 5，再叠加搭配评分和词库命中奖励；4 字情形的组合上限由代码推导为 `5^4 = 625`，最终取 `HAND_CANDIDATES=30`，不是只有逐字首选拼接。候选预算由 12 扩至 30 位于 Shared Rust，扩大已有识别结果的输出/排序范围，没有因这项改动额外运行 CNN；这是候选可选范围变化，不是模型首选准确率提高。用户仍不能直接选择某个字组改字或修复分割。见 [handrank.rs][WRank]、[handnet.rs][WNet]和[session.rs][WSession]。

当前连续连写按“一个字完成后再写下一个”工作：下一次落笔前确认上一字，上一字的墨迹进入淡出层，当前画布只保留新字；不再把两个字的笔画持续堆在同一块画布里。跨字连笔、重叠写、多行和任意长度仍需要新的分割/序列机制及真人数据，不能只调阈值后宣布支持。

### 3.3 停笔判字和自动上屏应分开控制

当前 `handPauseMs`控制“下一次落笔前是否提交上一字”，`handIdleMs`控制“没有下一笔也超时上屏”；连续连写也按逐字确认，不再等待整行；`HAND_AUTO_COMMIT`开关控制无下一笔时的自动上屏。见 [KeyboardView][WView]、[applyHandPause][WKeyboard]、[WeavePrefs][WPrefs]。

| 设置档 | 单字自动确认 | 连续连写的字间确认 |
|---|---|---|
| 快 | 550 ms | 550 ms |
| 中 | 800 ms | 800 ms |
| 慢 | 1200 ms | 1200 ms |

关闭自动上屏时，代码设置 `handIdleMs=0`；连续连写仍用停顿判断下一次落笔是否确认上一字，但不会只因停顿自动写入编辑器。主 agent 已反馈手写与异步竞态 JVM 回归通过；JVM 已覆盖“关闭后继续写”“已有计时回调时关闭”“结果返回时仍保留墨迹”，Native 流程已验证实际书写与上屏。不凭初版快照继续将已修复/已回归路径列为当前缺陷。

停笔 `commitFirst`遇到尚未完成的识别时，将任务加入 `handCommits`并立即返回；后台完成后交回结果并提交首选，不让停笔 UI 等待识别。无可提交手写候选时返回 false，视图据提交结果决定清墨迹。`discardHand`使 generation 失效，`finishHand`检查 generation 与引擎归属；清空、换新编辑框和结束输入丢弃迟到结果。新增 `onSelectionUpdate`在外部光标移动时取消原位置的待提交任务，`applyingHandResult`区分自身结果回报。接线已完成，异步 JVM 回归据主 agent 反馈通过；Native/JNI/模拟器结果尚待补录。其他显式引擎读取仍有状态同步路径，不能据此推导全部 UI 操作的实测性能。[InputController][WController]

### 3.4 悬浮控制与笔画重做的验收边界

四角策略默认全部启用，接受 15 个非空角集合；配置不能将最后一个角关闭。控制条通常顶部/底部各 48 dp，短窗口放不下候选条与最小键区时各降到 24 dp，不能继续声称所有窗口都是 48 dp 触点。body 的最小行距为 32 dp，4 行键区高度下限为 128 dp；不足以容纳固定控制区与最小键区时不继续无限缩小。贴边放大允许卡片平移进可用边界，测量后的锚定保留同一策略。独立 X 结束悬浮并恢复底部键盘。默认四角/X 已由主 agent 视觉复核，极窄/短窗口、触摸和 Native/模拟器边界继续验收，不扩称所有设备通过。[FloatingResizeSettings][WFloatSettings]、[FloatingGeometry][WFloatGeometry]、[FloatingResizeController][WFloatController]、[WeaveKeyboard][WKeyboard]、[KbMetrics][WMetrics]

笔画重做已接入模式键长按，普通点按仍切单字/连写，清空/重写及长按删除语义不变。`HandPad.redo`恢复几何与显示样本，`KeyboardView.redoStroke → InputController.replaceHandInk`替换笔迹并重新识别，不等待已取消的旧推理。主 agent 已反馈手写 JVM 回归通过；重做栈、新写/清空/换框与 Native 候选同步仍按具体验收层级记录，恢复笔画不冒充上屏、造词或识别率提升。[HandPad][WHand]、[WeaveKeyboard][WKeyboard]、[笔画重做测试][WRedoTest]

## 4. 本轮已落地的 5 项与剩余验收

本轮条目按主 agent 状态与现有源码更新：已完成接线；HWR 渲染、手写/异步 JVM 回归通过及 Floating 视觉复核作为主 agent 汇报记录，本研究没有重跑。Native/JNI/模拟器实际结果由主 agent 后续填入，不预先标通过。

| 优先级 | 当前落地状态与目标 | 代码落点（本研究不编辑） | 可判定的验收 |
|---|---|---|---|
| 1 | **手写外观与触控笔压力**：4 笔锋、2–12 dp、主题/6 色/自定义 RGB 完成接线；HWR 渲染 JVM 回归通过。 | [HandInkStyle / HandInkPrefs][WInkStyle]、[HandwritingAppearanceSettings][WInkSettings]、`HandPad.configure/inkColor/drawInk`、`KeyboardView.handAppearance/inkPressure`、`WeaveKeyboard`偏好同步。 | Native/真实笔设备继续检查设置/预览/实写、压力与主题透明度，旋转/悬浮/重建不丢墨迹；不将 JVM 渲染通过写成真人识别提升。 |
| 2 | **后台确认与取消/保墨迹**：接线完成，手写/异步竞态 JVM 回归通过；新增外部光标移动取消迟到提交；Shared Rust 候选 12→30，不新增 CNN 推理。 | `InputController.commitFirst/discardHand/onSelectionUpdate/finishHand`、`handGeneration/handCommits`；`WeaveKeyboard.onState/applyHandPause`；`session.rs::HAND_CANDIDATES`。 | Native/JNI/模拟器验证首选只提交一次、清空/换框/移光标不回灌、关闭自动上屏保墨迹，以及 30 候选传输/选择；候选预算扩大不当作模型准确率提升。 |
| 3 | **四角缩放与独立 X 停靠**：接线完成，默认四角/X 已视觉复核；控制条通常 48 dp、短窗口必要时 24 dp，贴边可平移放大，body 最小 32 dp/行。 | [FloatingResizePolicy][WFloatPolicy]、[FloatingResizeSettings][WFloatSettings]、[FloatingGeometry][WFloatGeometry]、[FloatingResizeController][WFloatController]、[KbMetrics][WMetrics]；`WeaveKeyboard.fitFloatingViewport/ResizeGrip/FloatCloseButton`。 | 保留 15 个非空集合、最后一角、禁用角、短窗口/贴边放大、控制区不覆盖键区的场景；Native/模拟器已覆盖四角、指定角、取消、X 停靠及贴底放大，6 项定向流程通过。 |
| 4 | **笔画重做**：模式键长按与候选重新识别已接线，手写 JVM 回归通过。 | `HandPad.canRedo/redo`、`KeyboardView.redoStroke`、`WeaveKeyboard.onLongPressFunc`、`InputController.replaceHandInk`。 | Native 链路复核笔画/候选同步、新写/清空/换框策略和不误提交；普通模式点按、清空/重写、长按删除语义保持原状。 |
| 5 | **模式与无障碍反馈验收**：单字/连写、识别中状态和 X 按钮已有反馈；手写区朗读、重做长按动作及缩放等价动作按接入情况补齐。 | `KeyboardView.drawHand/A11ySource`、`VirtualA11y`、`WeaveKeyboard`模式/控制手柄；外观设置已有语义描述。 | 单字/连写视觉与朗读一致；识别中不读过期候选；重做动作有明确语义；X 可通过无障碍点击停靠；仍缺的移动/缩放和符号浮层动作明确保留，不能因有描述文本就标全部可操作。 |

后续缺失保持独立：

- **临时无痕**：普通输入框主动开关与明确状态仍待补，参考[HeliBoard][HeliHidden]及[FlorisBoard][FlorisPrefs]。沿现有保护链使用 `password ∨ noPersonalizedLearning ∨ userIncognito`，不能让用户开关解除密码保护；覆盖学习、符号历史、剪贴板及互联，而非只隐藏 UI。
- **逐字分割/纠错入口**：现有整词候选和空间分组未提供可选字组、重分割或逐字重写。后续显式暴露组信息/组候选，不从整词字符串猜分割；重叠写、跨字连接、多行仍需新机制与真人数据。
- **完整长按符号浮层无障碍及缩放等价动作**：参照[HeliBoard 浮层行为][HeliA11y]独立实现，不能复制 GPL 文件；读屏需能逐项选择、取消与恢复焦点，移动/缩放也需可操作入口。

## 5. 算法、模型与数据许可分别处理

当前仓库主代码为 [Apache-2.0][WLicense]。[THIRD_PARTY.md][WThirdParty]是现有数据登记，不能因为一个项目“开源”就把它的 UI、运行时、训练数据和权重都当成同一许可证。本轮没有复制上游实现或导入模型。

| 对象 | 本次核对的许可/证据 | 实际约束与取舍 |
|---|---|---|
| librime | [官方仓库][Librime]标示 BSD-3-Clause | 如采用库/代码，需要保留版权和许可；词典、schema、插件另查来源，不能把 librime 的 BSD 套给整个 Rime 生态。 |
| Trime / HeliBoard 前端 | [Trime 源文件][TrimePrefs]为 GPL-3.0-or-later；[HeliBoard README][Heli]为 GPLv3，[浮层文件][HeliA11y]同时标 Apache-2.0 AND GPL-3.0-only | 直接移植会引入 copyleft 条件，不能复制到本项目后仅标 Apache-2.0。这里借鉴用户可观察行为，用现有织文架构独立实现。AOSP 历史来源不自动解除后来修改的 GPL。 |
| fcitx5-android / libime | [Android 剪贴板文件][FcitxClipboard]和[libime 文件][LibimeHistory]明确 LGPL-2.1-or-later | 真正引入需要区分修改库、链接方式及相应源码/替换或重链接条件；不能把代码搬入自研内核后抹掉 LGPL。依赖的词库/语言模型另核对。此轮不引入该库。 |
| FlorisBoard | [README][Floris]及源文件为 Apache-2.0 | 复用仍需保留许可证、版权及适用 NOTICE，并注明修改。此轮仅作行为/结构参考，未复制代码；外部扩展不能自动套用主仓库许可。 |
| Zinnia 引擎 / Tomoe 模型 | [官方说明][Zinnia]：SVM，输入笔画、输出 n-best 字符；[引擎 COPYING][ZinniaLicense]为 BSD-3-Clause；[Tomoe 模型 COPYING][ZinniaModelLicense]为 LGPL-2.1 | 引擎不含模型，不承担墨迹渲染或移动键盘体验。运行库可移植性不证明中文模型覆盖、连笔/整行质量和 Android 时延；引擎宽松许可不等于模型可随意换许可。作为离线单字研究参照，不据此替换现有融合模型。 |
| Tegaki / Wagomu / 模型 | [Tegaki README][Tegaki]及[Wagomu README][Wagomu]声明 GPL；Wagomu 是 DTW 路径匹配。其[模型 README][TegakiModels]指向 COPYING；本次检查的[简体模型元数据][TegakiMeta]只给名称和语言 | Python/桌面工程需要额外移动适配；单字 DTW 不自动解决跨字分割。没有从 `.meta`获得足够的独立权重/训练数据授权链，因此不能宣布模型许可已清洁核准、商业可用或与 BSD Zinnia 一致；如评测特定发布模型，先取得它随包的 COPYING、训练来源和哈希。 |
| 织文模板和网络训练来源 | [Make Me a Hanzi COPYING][HanziLicense]将 `graphics.txt`列为 Arphic Public License，将 `dictionary.txt`另列 LGPL-3.0-or-later；[handgen.rs][WHandGen]、[handnet 说明][WTrain]和[第三方登记][WThirdParty]记录本项目从笔画中线/字体生成数据 | 模板及混合训练权重当前按 Arphic Public License 登记并独立分发，需保留来源、许可及派生说明；不要误把 `dictionary.txt`当成相同授权。字形中线和字体骨架是合成训练来源，不是真人笔迹数据。这里沿用仓库登记，不重新裁定混合权重的法律归属。 |
| OFL 字体及渲染数据 | [SIL OFL 官方 FAQ][OflFaq]的 1.1、1.25、1.26 区分图形作品、以 ML 生成新字体和非字体的 AI 设计输出 | “渲染图片不自动受 OFL 约束”不能扩大成任何训练产物一律无条件授权。保留每个字体的版本/许可及训练清单；若分发字体或其修改版，遵守 OFL 和保留字体名条件；识别模型不是新字体，但混合数据来源仍需逐项核对。 |
| Android ML Kit Digital Ink | [Android 官方指南][MlAndroid]、[条款与隐私][MlTerms] | 是可选的非开源 SDK/模型路线，**不是开源替代库**。API 23+，语言模型需先下载、官方估计每种约 20 MB；织文[构建配置][WGradle]的 `minSdk=26`仅满足最低版本，不能保证设备/下载可用。识别在设备端，但 SDK 会请求更新并发送性能/使用指标；条款限制逆向和提取相关软件。不能将模型抽出转包为自研权重。 |

ML Kit 支持整行文字、书写区域与前文提示；官方要求笔画遵循自然书写顺序，前文最多使用最后 20 字符，并提醒语言/用户书写风格会影响准确性。其文档说明识别得分只对形状分类器提供，不能假定文字候选有可直接用于“低置信度不自动提交”的分数。若做适配，应在采集时保留点时间和顺序，补模型下载/缺失/删除状态及离线回退；当前几何点数组不是完整的带时间输入记录。见 [ML Kit 官方指南][MlAndroid]。

建议本轮保留自研模板+CNN，先解决可控制提交、纠错和输入反馈。Zinnia/Tegaki 可作取得许可后的基线；ML Kit 可独立作明确选择的可选评测后端。没有同一真人集上的质量、下载、存储和时延证据前，不推荐更换默认模型。

## 6. 真人评测：允许报告什么，以及拒报条件

### 6.1 现有工具的证据级别

- [handbench.rs][WHandBench]和[handctx.rs][WHandCtx]明示合成变形笔迹。它们适合算法回归、分组/重排消融，结果只能标为**合成测试**。
- [HandwritingTest][WHandTest]及相关 UI 测试使用 [FakeEngine][WFake]，验证笔画、定时、撤销/清空和操作状态；`N笔0`等假候选不是识别输出，测试通过也不能证明准确率。
- [handeval.rs][WEval]能读取外部几何笔迹，计算单字 Top-1/5/10 与平均用时；它本身不证明文件来自真人。它只取标签的第一个字符、跳过部分非法行，不能直接用于多字连写或严格的数据审计。
- [训练 README][WTrain]已有外部笔迹上的历史百分比，但本次未取得原文件、书写者划分、采集元数据和哈希，**不复述为可验证的真人指标**。其中 `HANDNET_EVAL`每 1000 步用于训练反馈，若据此选模型/参数，该文件应归为开发集，不能再当最终测试集。
- 当前仓库 `data/eval`核对到的是拼音/纠错评测，没有本次可用的、带来源和书写者划分的手写测试集。本次没有执行真人准确率或设备时延评测。
- **本轮核心结论**：按主 agent 最新同步，准确率目前仅有合成证据，融合未提高；本文不填真人提升百分比。候选由 12 扩到 30、JVM 回归通过、Floating 视觉复核均是各自的功能/验证结果，不能转写为准确率证据。任何后续真人报告仍须限定测试集、书写者划分、模型/数据哈希、上文和个体适应条件，不能扩称“整体模型已提高”。

### 6.2 最小可执行协议

1. **先定义任务与授权。** 分别采集单字正常书写、单字连笔、2/3/4 字留空连写；把重叠写、跨字连接和多行列为边界任务。取得测试/保存/可否再分发的明确授权，不使用真实账号、密码、私人聊天。数量和年龄/经验分布由实际招募记录决定，不能由合成样本补足。
2. **保留采集事实。** 使用匿名 `writer_id`、`session_id`、设备/Android/屏幕/密度、手指或笔、任务模式、目标文本，以及原始 `strokes[{x,y,t}]`。保留空结果、取消/重写和所有有效尝试。固定字体显示只负责提示目标，不能将提示字体描摹数据混入自然书写集。
3. **按书写者隔离。** 训练、调参和最终测试的书写者不重叠；同一笔迹的旋转、缩放、重采样、字体模板或重复输入不能跨集合。固定模型、词库、重排参数、自动提交设置及 SHA-256 后才运行最终集。
4. **分开评测适应。** 基线关闭个人样本与历史；个体适应测试允许先纠正，再测试同一书写者的**另一批新笔迹**。重复已经学过的同一轨迹只算记忆/机制检查。分别报告字形模型、词库/上文重排、个体学习贡献。
5. **按任务报指标。** 单字报 Top-1/5/10、覆盖率、空结果率；连写报整行完全正确率、按 Unicode 字符计算的 CER、分割错误/错误组数。UI 报候选操作次数、重写比例、误提前提交率、完成时间。正确单字的平均用时不能代替整段任务效率。
6. **实机测延迟和稳定性。** 独立记录落笔到墨迹、抬笔到可见候选、确认到宿主文本的 P50/P95/P99；注明冷/热模型、设备、后台任务和电源状态。测试切框、密码/禁止学习框、取消/清空、悬浮/旋转和屏幕阅读器。桌面 `ms/char`不是 Android 触摸到候选时延。
7. **公开可审计摘要。** 报真实书写者数、各任务有效/无效数、预设剔除规则、模型与数据哈希、许可、书写者层面的置信区间/重采样方法。报告集无法公开时，也保留可复查的审计记录和复现配置；不要假定几千个相关笔画等于几千名独立用户。

**拒报条件：** 无法确认真人来源/授权、测试与训练或调参泄漏、只有字体/模板变形、只有 FakeEngine、只剩正确案例、没有分母或没有最终模型哈希时，均不得发布“真人手写准确率”。可以报告“接口测试通过”“合成回归结果”或“真人试用观察”，并注明实际证据范围。

### 6.3 可复查命令及执行边界

下面是有授权外部数据后的**评测入口示例，本轮未执行**。从工作区根运行，`HAND_REAL_TEST`与`HAND_TEMPLATE`须指向实际且经核准的文件；`.wvz`压缩资源不能冒充此工具期望的原始模板 `.wvh`。

```sh
cargo run --manifest-path core/Cargo.toml -p weave-dict --example handeval -- data/hand/hand_net.wvn "$HAND_REAL_TEST" 100000 "$HAND_TEMPLATE"
```

正式评测先单独验证格式/非法值并记录拒收数量，不允许沿用 `handeval`的静默跳过规则来缩小分母。多字任务应调用与产品一致的 `recognize_groups → rank_hand`链路，再分别计算分割和整行指标，不能让 `handeval`只取首字后输出所谓连写准确率。

已有测试文件 `HandwritingTest.kt`、`HandRecognitionAsyncTest.kt`、`KeyboardAccessibilityTest.kt`、`ClipboardPrivacyTest.kt`、`CursorActionsTest.kt`、`core/weave-engine/tests/hand_context.rs`可作为定向验证入口。本研究只阅读覆盖范围，未自行执行；HWR 渲染、手写/异步竞态 JVM 回归通过来自主 agent 最新反馈，不能扩大为这里所有文件均通过。

新增 API 的定向入口为[笔迹渲染测试][WInkTest]、[笔画重做测试][WRedoTest]、[外观设置测试][WInkSettingsTest]、[悬浮控制/几何测试][WFloatTest]、[四角设置测试][WFloatSettingsTest]；[后台识别测试][WHandAsyncTest]含识别任务替换、待提交任务、清空/换框和外部光标移动丢弃迟到提交场景。上述覆盖与主 agent 的已通过回归范围分别记录；预览、几何或 FakeEngine 测试不等同于真人识别评测。

**验收交接状态：** 主实现接线完成；HWR 渲染、手写/异步 JVM 回归通过（主 agent 反馈）；Floating 默认四角/X 视觉复核完成（主 agent 反馈）；Android 31 arm64 模拟器的 6 项定向 Native 流程通过，实际范围和限制见主线程验收补记。

## 7. 验证记录：官方来源沿用，本次仅本地复核

- 通过 ego-browser 建立并复用唯一 TaskSpace `42`，仅访问公开仓库/文档；官方源码采用固定提交的公开 raw/API 获取。没有进入账号、个人资料、通知或私人内容页面。已执行一次 `task.finish({keep:[]})`并收到 `finished:true`，没有遗留研究页面。
- 已核对 Rime/Trime、fcitx5-android/libime、HeliBoard、FlorisBoard、Tegaki、Zinnia、ML Kit 的官方页面/源码；许可和功能结论分别关联来源。对于 Tegaki 特定权重授权、外部真人数据和实机体验，明确保留未核准状态。
- 首版 29 个官方链接已验证 HTTP 200，来源与固定提交原样沿用；本次没有联网或新建/恢复浏览器空间。新增引用仅指向本地外观、四角缩放与定向测试代码。
- 本次静态复核确认接线完成、外部光标移动取消待提交任务、Shared Rust `HAND_CANDIDATES=30`、模式键长按重做、通常 48 dp/短窗口 24 dp 控制条及 32 dp/行下限。主 agent 已通过的 JVM/视觉检查与 Native/JNI/模拟器的定向结果分别标注，不把源码存在当成全部产品验收。
- 本次执行下方 Python 文档校验通过：75 个引用定义、46 个本地路径，表格列数、代码块及空白格式有效；29 个官方来源保留初版的已验证链接，没有重新发起 HTTP 检查。
- `git diff --check -- docs/research/11-mobile-input-experience-and-handwriting.md`退出 0、无输出。新文件还用 `git diff --no-index --check /dev/null docs/research/11-mobile-input-experience-and-handwriting.md`检查：无错误输出，退出 1 表示存在新文件差异，不是空白错误。`git status --short --`该路径显示 `??`，保持未提交。
- 本研究仅改文档，没有模型训练、真人采集、产品测试执行或准确率评测，没有触发 APK/DMG 全量构建。其他 agent 的 JVM 回归和视觉复核以主 agent 反馈记录，实际 Native/JNI/模拟器结果见下方主线程验收补记。

可复查的文档静态校验命令（工作区根目录）：

```sh
python3 - <<'PY'
from pathlib import Path
import re
p = Path('docs/research/11-mobile-input-experience-and-handwriting.md')
s = p.read_text()
pairs = re.findall(r'^\[([^\]]+)\]:\s+(\S+)\s*$', s, re.M)
refs = dict(pairs)
assert len(pairs) == len(refs)
assert all(u.startswith('http') or (p.parent / u).resolve().is_file() for u in refs.values())
body = re.sub(r'^\[[^\]]+\]:.*$', '', s, flags=re.M)
assert set(re.findall(r'\[[^\]\n]+\]\[([^\]\n]+)\]', body)) <= refs.keys()
assert len(re.findall(r'^```', s, re.M)) % 2 == 0 and not re.search(r'[ \t]+$', s, re.M)
columns = None
for line in s.splitlines():
    if line.startswith('|'):
        n = len(re.findall(r'(?<!\\)\|', line))
        if columns is None:
            columns = n
        assert n == columns
    else:
        columns = None
print('PASS: references, local paths, tables, code fences, whitespace')
PY
git diff --check -- docs/research/11-mobile-input-experience-and-handwriting.md
```

## 来源

[RimeGuide]: https://github.com/rime/home/wiki/CustomizationGuide
[Librime]: https://github.com/rime/librime/tree/7bc3fb0005a03aff1c086611e4d52bb7f6444fdd
[Trime]: https://github.com/osfans/trime/tree/1ba0780c1acee5597f56456d076732b371a03c2c
[TrimeActions]: https://github.com/osfans/trime/blob/1ba0780c1acee5597f56456d076732b371a03c2c/app/src/main/java/com/osfans/trime/ime/keyboard/CommonKeyboardActionListener.kt
[TrimePrefs]: https://github.com/osfans/trime/blob/1ba0780c1acee5597f56456d076732b371a03c2c/app/src/main/java/com/osfans/trime/ime/keyboard/KeyboardPrefs.kt
[Fcitx]: https://github.com/fcitx5-android/fcitx5-android/tree/e6199a2801b0c76e1baeff7a48f6b18e910e9dd7
[FcitxIme]: https://github.com/fcitx5-android/fcitx5-android/blob/e6199a2801b0c76e1baeff7a48f6b18e910e9dd7/app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt
[FcitxClipboard]: https://github.com/fcitx5-android/fcitx5-android/blob/e6199a2801b0c76e1baeff7a48f6b18e910e9dd7/app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClipboardManager.kt
[LibimeUser]: https://github.com/fcitx/libime/blob/171edcf137001e8eb8274f53ec010058cda70b09/src/libime/core/userlanguagemodel.h
[LibimeHistory]: https://github.com/fcitx/libime/blob/171edcf137001e8eb8274f53ec010058cda70b09/src/libime/core/historybigram.h
[Heli]: https://github.com/HeliBorg/HeliBoard/tree/bc2b91189d60692090bcf30448ba35a9feef8110
[HeliHidden]: https://github.com/HeliBorg/HeliBoard/wiki/9.-Hidden-features
[HeliA11y]: https://github.com/HeliBorg/HeliBoard/blob/bc2b91189d60692090bcf30448ba35a9feef8110/app/src/main/java/helium314/keyboard/accessibility/PopupKeysKeyboardAccessibilityDelegate.kt
[Floris]: https://github.com/florisboard/florisboard/tree/fe1241f4921b3eae923571ff7a3e113a7e10d677
[FlorisPrefs]: https://github.com/florisboard/florisboard/blob/fe1241f4921b3eae923571ff7a3e113a7e10d677/app/src/main/kotlin/dev/patrickgold/florisboard/app/AppPrefs.kt
[FlorisIncognito]: https://github.com/florisboard/florisboard/blob/fe1241f4921b3eae923571ff7a3e113a7e10d677/app/src/main/kotlin/dev/patrickgold/florisboard/ime/keyboard/IncognitoMode.kt
[FlorisWindow]: https://github.com/florisboard/florisboard/blob/fe1241f4921b3eae923571ff7a3e113a7e10d677/app/src/main/kotlin/dev/patrickgold/florisboard/ime/window/ImeWindowController.kt
[Zinnia]: https://github.com/taku910/zinnia/blob/581faa8f6f15e4a7b21964be3a5ec36265c80e5b/index.html
[ZinniaLicense]: https://github.com/taku910/zinnia/blob/581faa8f6f15e4a7b21964be3a5ec36265c80e5b/zinnia/COPYING
[ZinniaModelLicense]: https://github.com/taku910/zinnia/blob/581faa8f6f15e4a7b21964be3a5ec36265c80e5b/zinnia-tomoe-model/COPYING
[Tegaki]: https://github.com/tegaki/tegaki/tree/7a74e442c4130cccc226a7e7c2b683ac94c0cccb
[Wagomu]: https://github.com/tegaki/tegaki/blob/7a74e442c4130cccc226a7e7c2b683ac94c0cccb/tegaki-engines/tegaki-wagomu/README
[TegakiModels]: https://github.com/tegaki/tegaki/blob/7a74e442c4130cccc226a7e7c2b683ac94c0cccb/tegaki-models/README.in
[TegakiMeta]: https://github.com/tegaki/tegaki/blob/7a74e442c4130cccc226a7e7c2b683ac94c0cccb/tegaki-models/tegaki-zinnia-simplified-chinese/handwriting-zh_CN.meta
[HanziLicense]: https://github.com/skishore/makemeahanzi/blob/bddc96d4/COPYING
[OflFaq]: https://openfontlicense.org/documents/OFL-FAQ.txt
[MlAndroid]: https://developers.google.com/ml-kit/vision/digital-ink-recognition/android?hl=en
[MlTerms]: https://developers.google.com/ml-kit/terms?hl=en
[AndroidClipboard]: https://developer.android.com/develop/ui/views/touch-and-input/copy-paste?hl=en

[WHand]: ../../android/app/src/main/java/com/weavetext/ime/ui/keyboard/HandPad.kt
[WInkStyle]: ../../android/app/src/main/java/com/weavetext/ime/ui/keyboard/HandInkStyle.kt
[WInkSettings]: ../../android/app/src/main/java/com/weavetext/ime/settings/HandwritingAppearanceSettings.kt
[WFloatPolicy]: ../../android/app/src/main/java/com/weavetext/ime/ui/keyboard/FloatingResizePolicy.kt
[WFloatSettings]: ../../android/app/src/main/java/com/weavetext/ime/settings/FloatingResizeSettings.kt
[WFloatGeometry]: ../../android/app/src/main/java/com/weavetext/ime/ui/keyboard/FloatingGeometry.kt
[WFloatController]: ../../android/app/src/main/java/com/weavetext/ime/ui/keyboard/FloatingResizeController.kt
[WInk]: ../../android/app/src/main/java/com/weavetext/ime/ui/keyboard/InkLayer.kt
[WView]: ../../android/app/src/main/java/com/weavetext/ime/ui/keyboard/KeyboardView.kt
[WMetrics]: ../../android/app/src/main/java/com/weavetext/ime/ui/keyboard/KbTheme.kt
[WKeyboard]: ../../android/app/src/main/java/com/weavetext/ime/ui/keyboard/WeaveKeyboard.kt
[WController]: ../../android/app/src/main/java/com/weavetext/ime/ime/InputController.kt
[WPrefs]: ../../android/app/src/main/java/com/weavetext/ime/settings/WeavePrefs.kt
[WScheme]: ../../android/app/src/main/java/com/weavetext/ime/settings/SchemeScreens.kt
[WKeys]: ../../android/app/src/main/java/com/weavetext/ime/ui/keyboard/Keys.kt
[WCursor]: ../../android/app/src/main/java/com/weavetext/ime/ui/keyboard/CursorPanel.kt
[WEditor]: ../../android/app/src/main/java/com/weavetext/ime/ime/EditorCache.kt
[WClipboard]: ../../android/app/src/main/java/com/weavetext/ime/ui/keyboard/ClipboardPanel.kt
[WClipHistory]: ../../android/app/src/main/java/com/weavetext/ime/ime/ClipHistory.kt
[WCandidates]: ../../android/app/src/main/java/com/weavetext/ime/ui/keyboard/CandidateGridPanel.kt
[WA11y]: ../../android/app/src/main/java/com/weavetext/ime/ui/keyboard/VirtualA11y.kt
[WPopup]: ../../android/app/src/main/java/com/weavetext/ime/ui/keyboard/PopupOverlay.kt
[WDictionary]: ../../android/app/src/main/java/com/weavetext/ime/settings/DictionaryScreens.kt
[WSession]: ../../core/weave-engine/src/session.rs
[WFeatures]: ../../core/weave-engine/src/session/features.rs
[WRank]: ../../core/weave-engine/src/session/handrank.rs
[WTemplate]: ../../core/weave-dict/src/hand.rs
[WNet]: ../../core/weave-dict/src/handnet.rs
[WUserDict]: ../../core/weave-engine/src/userdict.rs
[WTrain]: ../../tools/handnet/README.md
[WHandGen]: ../../core/weave-dict/src/bin/handgen.rs
[WThirdParty]: ../THIRD_PARTY.md
[WManifest]: ../../android/app/src/main/AndroidManifest.xml
[WGradle]: ../../android/app/build.gradle.kts
[WLicense]: ../../LICENSE
[WEval]: ../../core/weave-dict/examples/handeval.rs
[WHandBench]: ../../core/weave-dict/examples/handbench.rs
[WHandCtx]: ../../core/weave-engine/examples/handctx.rs
[WFake]: ../../android/app/src/test/java/com/weavetext/ime/testing/FakeEngine.kt
[WHandTest]: ../../android/app/src/test/java/com/weavetext/ime/ui/HandwritingTest.kt
[WHandAsyncTest]: ../../android/app/src/test/java/com/weavetext/ime/ime/HandRecognitionAsyncTest.kt
[WInkTest]: ../../android/app/src/test/java/com/weavetext/ime/ui/HandInkRenderingTest.kt
[WRedoTest]: ../../android/app/src/test/java/com/weavetext/ime/ui/HandInkRedoTest.kt
[WInkSettingsTest]: ../../android/app/src/test/java/com/weavetext/ime/settings/HandwritingAppearanceSettingsTest.kt
[WFloatTest]: ../../android/app/src/test/java/com/weavetext/ime/ui/FloatingResizeTest.kt
[WFloatSettingsTest]: ../../android/app/src/test/java/com/weavetext/ime/settings/FloatingResizeSettingsTest.kt

## 主线程验收补记

Android 31 arm64 独立干净模拟器：`FloatingResizeDeviceTest` 5 项与 `HandwritingExperienceDeviceTest` 1 项均通过（27.296 秒）。验证包内识别器的单字、笔迹设置保持几何、撤销/重做、多字连写「十十」上屏，以及四角默认、指定角、取消、X 停靠、贴底放大；这些是定向原生功能测试，不是独立真人准确率。

Rust 手写 19 项与上下文/第30候选选择的 6 项集成通过；Android 最近一轮定向 JVM 117 项通过，覆盖外部光标移动、换输入框、取消迟到结果、自动提交关闭、笔迹外观与编辑路径。Mac 189 项测试通过。两端安装包验证通过，Android 调试/发布 JNI 内核相同。

仍未验证真实触控笔设备、全量系统 IME 窗口兼容、全日使用掉帧/功耗或通用真人准确率；不将模板训练同源的合成数据用作真人识别率。
