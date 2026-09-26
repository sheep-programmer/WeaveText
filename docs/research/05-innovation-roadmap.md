# 05 · 「织文」创新与优化路线图 / WeaveText Innovation Roadmap

> 共 **22 条**，分 P0 / P1 / P2；每条含**做法**、**预期收益**、**验收指标**。
> *22 items across P0/P1/P2, each with approach, expected gain and acceptance criteria.*

---

## 0. 总原则：我们凭什么比 Rime 好 / Why we can beat Rime

**中文**
Rime 的算法并不差，它的**架构假设**过时了：假设输入法是「配置驱动的、可重新编译的、用户愿意折腾的」。
织文反过来假设：**输入法是系统组件，必须零配置、秒开、永不阻塞、数据可控**。

由此推出三条不可妥协的架构约束（P0 里所有条目都服务于它们）：
- **A. 运行期零部署**：词典是随包分发的只读产物，用户改词只写日志。
- **B. UI 永不等待引擎**：键盘第一帧不依赖引擎；引擎在后台预热，结果通过快照替换。
- **C. 所有状态可重建**：进程被杀、权限被拒、direct boot，都退化为「降级键盘」而非崩溃。

**English**
Rime's algorithms are fine; its architectural assumptions are stale. WeaveText assumes an IME is a system component that must be zero-config, instant, never blocking and data-sovereign. Three non-negotiable constraints follow: (A) zero runtime deployment, (B) UI never waits for the engine, (C) all state is reconstructible.

---

## 1. 优先级总表 / Priority Overview

| # | 条目 Item | 优先级 | 一句话收益 Gain |
|---|---|---|---|
| 1 | 零部署只读词典 + 追加日志 | **P0** | 启动从 3–9 s 降到 <200 ms |
| 2 | 首帧键盘（引擎后台预热） | **P0** | 视觉上「键盘秒出」 |
| 3 | 单线程引擎 actor + 不可变快照 | **P0** | 消灭并发崩溃与掉帧 |
| 4 | 编译期输入管线（Rust 泛型/宏） | **P0** | 单键解码 P99 < 16 ms |
| 5 | 内存预算 + 全 mmap | **P0** | RSS ≤ 40 MB，不被系统杀 |
| 6 | 输入会话状态机 + App 兼容矩阵 | **P0** | 聊天应用/WebView 不再丢字重影 |
| 7 | 内存全量用户词 + 指数衰减 + 惰性丢弃 | **P0** | 长输入不卡、词库不膨胀 |
| 8 | 九键一等公民（构建期数字→音节索引） | **P0** | 九键可用性达到商业输入法水平 |
| 9 | 五笔86 完整语义（自动上屏/顶屏/反查） | **P0** | 五笔用户可直接迁移 |
| 10 | 双拼方案文件格式 + 半成品输入 | **P0** | 一套格式覆盖小鹤/自然码/微软 |
| 11 | 流式语音面板（partial/final 不闪烁） | **P0** | 语音可用、句尾不丢字 |
| 12 | 自适应 beam + 整句候选 | **P1** | 长句准确率显著提升 |
| 13 | 中英混输 | **P1** | 免切换输入 `zuogeDNAjiance` |
| 14 | 分层误触纠错 | **P1** | 手滑容错且不污染候选 |
| 15 | 增量词库热更新（差分包） | **P1** | 更新词库不重启、不部署 |
| 16 | 隐私模式与数据主权 | **P1** | 密码框零学习、可审计 |
| 17 | 用户 n-gram 双轨学习 | **P1** | 不增词条也能改善整句 |
| 18 | 端到端加密跨设备同步 | **P1** | 换机无缝，服务端不可读 |
| 19 | 光标编辑 + 剪贴板面板 | **P1** | 补齐主流商业输入法级体验 |
| 20 | 性能回归 CI 门禁 | **P1** | 性能不随迭代退化 |
| 21 | 端上神经语言模型（量化） | **P2** | 整句准确率再上一台阶 |
| 22 | 可复现词库自举工具链 | **P2** | 许可证可控、可审计、可替换 |

---

## P0 — 决定成败 / Make or break

### 1. 零部署只读词典 + 追加日志
**做法**
- 词典在**构建机**上编译成单一二进制（FST/双数组 + 词条 + 权重 + n-gram），随 APK 或作为独立资源包分发，**运行期只读 mmap**。
- 用户行为（新词、词频、删词）写入**追加式日志** `user.log`（每条 < 32 字节：`op, wordId/词串, delta, tick`），**不做任何词典重编译**。
- 启动时：mmap 系统词典 + **异步**回放用户日志到内存哈希表（几万条 < 10 ms）。
- 后台在 `JobScheduler`/`WorkManager` 中把日志**压实**（compaction）成快照，失败不影响使用。
- **绝不**做运行期文件校验（对比 [librime#627](https://github.com/rime/librime/pull/627)：全量 CRC32 是启动最耗时函数）。

**预期收益**：彻底消除 Rime 的「重新部署」概念；冷启动词典加载 ≤ 30 ms（对比 Trime 实测 init 2.6 s + `full_check` 最高 5 s，见 [trime#713](https://github.com/osfans/trime/issues/713)）。
**验收**：100 万词条词典冷启动加载 ≤ 30 ms；改词后立即可用（无「部署中」状态）；日志压实后体积 ≤ 原始日志 20%。

### 2. 首帧键盘（引擎后台预热）
**做法**
- `onCreateInputView` 只构造 View，**不读磁盘、不初始化引擎**，用默认主题渲染 26 键 + 数字行。
- `onStartInputView` 触发后台 `EngineWarmup`（单独线程 + 低优先级），完成后通过 `StateFlow<EngineSnapshot>` 推给 UI。
- 候选栏在引擎未就绪时显示**本地即时候选**（英文预测、最近使用、剪贴板），**永不空白**。
- 主题/布局参数**编译进资源**，不做运行期 YAML 解析（对比 [trime#452](https://github.com/osfans/trime/issues/452)「弹键盘卡一下」、[trime#713](https://github.com/osfans/trime/issues/713) `Config` 载入 0.9 s）。

**预期收益**：用户感知为「键盘秒出」；即使引擎冷启动需要 300 ms，第一帧也已可交互。
**验收**：`onCreateInputView` → 第一帧 ≤ 32 ms（2 帧）；键盘可见到可打字 ≤ 200 ms（低端机 ≤ 400 ms）。

### 3. 单线程引擎 actor + 不可变快照
**做法**
- 引擎独占一个线程，外部只通过 `mpsc::Sender<Request>` 交互；**任何时刻只有一个可变借用**。
- 跨线程共享的是 `Arc<EngineSnapshot>`（不可变：候选、词频快照、配置），UI 直接读，**不加锁**。
- 学习事件、日志落盘、插件调用走**独立线程**，通过通道提交。
- 插件（`mlua`）**不跨线程共享 `Lua` 实例**；每个插件会话独立实例 + 资源限额。
- **禁止** `Mutex<Engine>`：UI 抢锁 = 掉帧。

**预期收益**：消灭「随机崩溃」（真实案例 [weasel#1487](https://github.com/rime/weasel/issues/1487)：多线程并发调用 librime 导致崩溃，且崩溃点误导到 Lua 插件）；按键延迟方差大幅下降。
**验收**：72 小时 monkey 测试零 native crash；按键 → 候选刷新 P99 ≤ 16 ms。

### 4. 编译期输入管线（Rust 泛型/宏替代 YAML 组件）
**做法**
- 把 Rime 的 `processor/segmentor/translator/filter` 五级流水，改为**编译期组合的泛型管线**：
  ```rust
  type Pipeline = Pipeline<(AsciiProcessor, PunctProcessor, Speller, Selector, Editor)>;
  ```
  每个 stage 是 `fn(&mut Ctx) -> Flow`，**静态分发、可内联**，编译器直接优化掉整条链。
- 拼写运算（模糊音/简拼/双拼/纠错）用**宏在编译期展开**成静态表（`const` 数组 + 二分/完美哈希），运行期只查表。这正对 Rime 的痛点：`prism` 的 spelling map 是部署期展开的巨型结构。
- 输入串处理全程用 `&str` 切片 + 小字符串优化（`smallvec`/`arrayvec`），**避免每键分配**（librime 专门有 [PR#1197](https://github.com/rime/librime/pull/1197) 去减少临时字符串拷贝）。

**预期收益**：单键解码 P99 从毫秒级降到微秒级；无运行期配置解析；配置错误在编译期暴露。
**验收**：`cargo bench` 单键解码 P99 ≤ 2 ms（含候选生成）；每键堆分配次数 = 0（用分配计数器断言）。

### 5. 内存预算 + 全 mmap
**做法**
- 所有只读数据 mmap（`memmap2`），**零反序列化**；结构体布局用 `#[repr(C)]` + 偏移寻址或 `rkyv` 零拷贝。
- 用户数据上限 5 MB，全量在内存（哈希表 + 小顶堆）；系统词库不复制到堆。
- **禁止**在启动路径构建 `HashMap<String, Vec<Entry>>` 这类堆结构（这正是「30 MB 词库卡死」的成因，见 [fcitx5-android#532](https://github.com/fcitx5-android/fcitx5-android/issues/532)）。
- Lattice 节点用**对象池**复用，解码结束归还，不 free。

**预期收益**：常驻 RSS ≤ 40 MB（对标用户期望「一般输入法占 20 MB 左右」，见 [trime#92](https://github.com/osfans/trime/issues/92)）；大词库不再卡首键。
**验收**：`adb shell dumpsys meminfo` 显示 IME 进程 PSS ≤ 40 MB（含键盘 View）；挂 50 MB 词典首键 ≤ 20 ms。

### 6. 输入会话状态机 + App 兼容矩阵
**做法**
- 定义**唯一真相**：`EditSession { committed_tail: SmallString, composing: SmallString, cursor: u32, ic_version: u64 }`；任何变更都**全量重算**要写入 IC 的内容。
- 所有 IC 调用收敛到 `IcGateway`：null 检查、版本校验、`beginBatchEdit/endBatchEdit` 成对、`try/finally`。
- `setComposingText` 后**必须**同步 `setSelection`（官方文档：composing 与 selection 相互独立）。
- 维护 **App 兼容矩阵**：`packageName → Profile`（`WebViewProfile` 缩短 composing 生命周期、`ImeOptionsProfile` 跟随 `imeOptions` 决定回车键文字、`PasswordProfile` 关闭学习）。真实依据：[florisboard#2135](https://github.com/florisboard/florisboard/issues/2135)（WebView 光标后重复文本）、[AnySoftKeyboard#3839](https://github.com/AnySoftKeyboard/AnySoftKeyboard/issues/3839)（Compose TextField 回车多字）、[trime#1541](https://github.com/osfans/trime/issues/1541)（数字框重复输入）。

**预期收益**：消灭「候选栏对但输入框错」这一类最难排查的 bug；兼容性问题变成**配置项**而不是代码分支。
**验收**：兼容矩阵覆盖 ≥ 20 个常见 App；每个 App 的 composing/删除/光标场景有自动化 UI 测试。

### 7. 内存全量用户词 + 指数衰减 + 惰性丢弃
**做法**
- 参考 librime `formula_d`：`d += da * exp((ta - t) / 200)`（半衰期 ≈ 138 tick），tick 用「输入次数」而非墙钟（避免时区/休眠问题）。
- 融合公式参考 `formula_p`：前 20 次缓慢上升，之后加速（`4^(d/kM)`），**误触不会立刻霸榜**。
- **惰性丢弃**：查询时跳过权重低于阈值的条目（参考 [librime#1205](https://github.com/rime/librime/pull/1205)：`1e-200` ≈ 10 万次未使用，清理约 3%）；压实日志时物理删除。
- 用户词**全量在内存**（不用 LevelDB 随机 IO，对比 [librime#510](https://github.com/rime/librime/issues/510) 长输入卡顿）。

**预期收益**：常用词越用越准、陈旧词自动退场、用户词库永不膨胀；长输入不卡。
**验收**：10 万条用户词下，单键查询 P99 ≤ 200 µs；连续输入 500 键无延迟上升（内存无增长）。

### 8. 九键一等公民（构建期数字→音节索引）
**做法**
- **不做**「数字→字母枚举→全拼」的指数展开；改为**构建期预计算**：对每个音节，算出其「数字签名」（如 `xian → 9426`），建立 `数字序列 → 音节集合` 的索引（FST 或双数组）。
- 解码时九键输入直接在音节图上生成边，**与全拼共用同一套 Lattice 与打分**。
- 九键专属优化：**候选优先单字**（九键用户习惯逐字），词组候选按需展开；支持「数字键长按输入数字」。
- 参考反面教材：[trime#1894](https://github.com/osfans/trime/issues/1894)、[trime#1005](https://github.com/osfans/trime/issues/1005)、[trime#1464](https://github.com/osfans/trime/issues/1464)、[fcitx5-android#109](https://github.com/fcitx5-android/fcitx5-android/issues/109)（用户长期呼吁原生九宫格，生态方案靠 YAML 硬编码、体验差）。

**预期收益**：九键可用性达到商业输入法水平；零配置（用户不需要装九键方案）。
**验收**：九键输入 5 个数字，首屏候选包含正确词组的比例 ≥ 90%（自建测试集）；单键延迟与全拼同级。

### 9. 五笔86 完整语义
**做法**
- **四码唯一自动上屏**：查询时同时判断「是否存在更长的、以当前码为前缀的词条」，有则**不自动上屏**（这是最常见的吞字 bug）。
- **顶屏**：已有候选时输入下一字首码 → 当前候选上屏；排除 `z` 与标点键。
- **`z` 键拼音反查**：**只对单字建反查索引**（不建词组），体积可压到 Rime 反查词典的 1/5 以内。
- **简码**：一级/二级简码与全码共存，按码长分层给权重。
- **码表来源自建**（86 版规则确定），避免 rime-wubi 的 LGPL-3.0 传染（见 `04-data-sources-licenses.md`）。

**预期收益**：五笔用户可直接从 Rime/极点迁移；反查体积可控。
**验收**：四码唯一自动上屏准确率 100%（含「存在更长码」的反例集）；`z` 反查响应 ≤ 30 ms。

### 10. 双拼方案文件格式 + 半成品输入
**做法**
- 方案文件格式参考 libime 的五节结构（`[方案] / [零声母标识] / [声母] / [韵母] / [音节]`，见 [libime 双拼方案格式](https://github.com/fcitx/libime/blob/master/src/libime/pinyin/shuangpin-profile-format_zh.md)），并**允许一对多映射**（一个键对应多个韵母）。
- **必须支持半成品状态**：只打了声母、只打了半个韵母时，音节图要保留「不完整音节」的边，否则界面闪空（libime 为此有 `Enable matching partial shuangpin`）。
- 内置小鹤、自然码、微软、智能ABC、紫光、搜狗等方案，用户可导入自定义方案。
- 双拼与全拼**共用词库与用户词**（用户切换方案不丢学习成果）。

**预期收益**：一套格式覆盖主流方案；用户迁移零成本。
**验收**：内置 ≥ 6 套方案，每套通过「全音节往返测试」（音节 → 双拼键序 → 音节）；输入半成品时首屏候选非空率 100%。

### 11. 流式语音面板
**做法**
- 权限：IME 无法自己弹框 → 用**透明 Activity** 请求 `RECORD_AUDIO`，结果回传；回来后**恢复 composing 状态**；Manifest 声明 `<queries><intent><action android:name="android.speech.RecognitionService"/></intent></queries>`（targetSdk ≥ 30 必需）。
- 录音：`AudioRecord` 16 kHz / 单声道 / PCM 16-bit，`VOICE_RECOGNITION` 音源（**不启用 AGC/AEC**，保持原始信号给云端 ASR），缓冲取 `getMinBufferSize` 的 2–4 倍。
- 显示：partial 用 `setComposingText` 覆盖（天然不闪烁），final 用 `commitText` + `finishComposingText`；服务端分片替换（`pgs=rpl`）时**只替换对应片段**。
- 结束：面板关闭/失焦立即 `stop()` + `release()`，并落定已识别内容；被打断（来电/抢麦）时**上屏已有结果**。
- 插件：Lua 5.4 插件格式（`manifest.yaml` 的 `capabilities.speech.inputMode`、`host.ws`、`host.asr.emitPartial/emitFinal/emitEnd`、`host.crypto`），织文**不内置任何插件或凭据**，由用户自行导入并自填密钥。

**预期收益**：语音输入可用且稳定；句尾不丢字（`emitEnd`）；用户可用自己的服务商。
**验收**：连续 50 次「进入面板 → 说话 → 上屏 → 退出」无泄漏、无卡死；partial 到 final 切换无可见闪烁；录音在键盘不可见时 100% 已释放。

---

## P1 — 差异化 / Differentiators

### 12. 自适应 beam + 整句候选
**做法**：beam 宽度随输入长度自适应（短输入 8、长句 32，参考 libime `beamSizeDefault = 20`）；候选栏**混合展示**「top-N 整句」与「末段词组」，整句候选带标点预览。
**收益**：长句一次上屏率提升；不再依赖用户额外安装语法模型（Rime 的整句质量强依赖八股文插件）。
**验收**：自建 500 句长句测试集，一次上屏正确率 ≥ 92%（对比不启用整句候选的基线提升 ≥ 15%）。

### 13. 中英混输
**做法**：大写字母序列作为独立的 `latin` 段（如 `zuogeDNAjiance` → 「做个DNA检测」）；英文词走独立英文词表 + 词频；中英之间**不插入空格**但可选「自动加空格」开关（默认关，保持精简设置）。
**收益**：程序员/科研用户的高频场景；Rime 生态靠 schema 打补丁实现，织文原生支持。
**验收**：混输测试集（含 DNA/CPU/iPhone/α 等）正确率 ≥ 95%。

### 14. 分层误触纠错
**做法**：音节图阶段加入两类纠错边——① 相邻键交换（`eh`↔`he`，参考 librime `Syllabifier::Transpose`）；② 相邻键替换（QWERTY 邻接表，参考 libime `PinyinCorrectionProfile`）。纠错边带 **credibility 权重**，**永远排在精确匹配之后**；**同一位置最多替换一次**，且**不与模糊音叠加**。
**收益**：手滑容错，但不造成候选爆炸（纠错是候选膨胀的头号来源）。
**验收**：注入 5% 随机邻键错误的测试集，首选命中率 ≥ 90%；开启纠错后首屏候选数增长 ≤ 30%。

### 15. 增量词库热更新（差分包）
**做法**：词库分片（按音节首字母/词频分层），更新只下发变化的 shard（bsdiff/zstd 差分）；新 shard 写入新文件，**原子切换指针**，无需重启进程。
**收益**：词库更新不再需要「重新部署」；用户无感知。
**验收**：10 万词条更新包 ≤ 2 MB；切换耗时 ≤ 50 ms；切换期间输入不中断。

### 16. 隐私模式与数据主权
**做法**：① 尊重 `EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING` 与 `TYPE_TEXT_VARIATION_PASSWORD/WEB_PASSWORD`（官方语义：*"should not update any personalized data such as typing history and personalized language model"*）；② 提供**「完全本地」开关**：一键禁用所有网络（语音插件除外，需单独授权）；③ 敏感词过滤在**本地**做，不上传；④ 用户词库可一键导出/删除/加密。
**收益**：可审计、可宣称「不上传任何输入内容」；这是相对商业输入法的核心卖点。
**验收**：密码框输入 100 次后用户词库零变化（自动化断言）；离线模式下无任何 socket 连接（抓包验证）。

### 17. 用户 n-gram 双轨学习
**做法**：除用户词表外，另存**用户二元组计数**（`(prevWord, word) → count`），解码时作为额外打分项；不增加词典条目（参考 libime 的 `HistoryBigram` + `UserLanguageModel` 分层）。
**收益**：改善整句而不污染词表；自动造词需求大幅减少（Rime 的自动造词常被抱怨造出垃圾词）。
**验收**：常用搭配（如「这个」「因为…所以」）的首选率提升 ≥ 10%；用户词表条目增长 ≤ 30% 于旧方案。

### 18. 端到端加密跨设备同步
**做法**：只同步**增量日志**（不是全量词库），用用户口令派生密钥（Argon2id）做 AEAD 加密；服务端只存密文；冲突解决用「tick + 权重取大」而非时间戳（避免时钟漂移）。
**收益**：换机无缝；服务端不可读；词库不重复上传。
**验收**：两设备交替输入 1000 词后，双方词频一致（误差 < 1 tick）；服务端抓包无明文。

### 19. 光标编辑 + 剪贴板面板
**做法**：候选栏上方一行「光标条」（左右移动、按词移动、选中、剪切/复制/粘贴），剪贴板历史面板（本地加密存储，敏感内容自动打码，参考 [florisboard#3289](https://github.com/florisboard/florisboard/issues/3289) 的打码过度问题——**打码要可切换**）。
**收益**：补齐主流商业输入法级体验；Android 上文本选择一直是痛点。
**验收**：光标移动在 WebView/Compose/原生 EditText 三类编辑器均正确；剪贴板面板打开 ≤ 100 ms。

### 20. 性能回归 CI 门禁
**做法**：`cargo bench` + Android macrobenchmark（`StartupTimingMetric`、`FrameTimingMetric`）纳入 CI；设定阈值（启动 ≤ 200 ms、单键 P99 ≤ 16 ms、RSS ≤ 40 MB），超标即失败；同时用 `ApplicationExitInfo` 采集线上 native crash。
**收益**：性能不随迭代退化——这是 Rime 生态长期缺失的（[trime#1766](https://github.com/osfans/trime/issues/1766)「最新每夜版性能劣化」、[trime#1172](https://github.com/osfans/trime/issues/1172)「大家觉得 3.2.16 卡顿吗？」都是没有性能门禁的后果）。
**验收**：CI 对每个 PR 输出性能对比表；连续 20 个 PR 无性能回归。

---

## P2 — 长期 / Longer term

### 21. 端上神经语言模型（量化）
**做法**：用 4-bit 量化的小型 Transformer/CNN-LM（< 20 MB）替代或融合 n-gram，在 NPU/NNAPI 上跑；只在长句候选重排阶段使用（不做全词表打分），保证延迟可控。
**收益**：整句准确率再上一台阶；可离线。
**验收**：长句一次上屏率 ≥ 95%；重排延迟 ≤ 30 ms；模型 ≤ 20 MB。

### 22. 可复现词库自举工具链
**做法**：`data/` 下提供**一键构建**：抓取开放语料（每步记录许可证）→ 清洗 → 注音（Unihan）→ 统计词频 → 训练 n-gram → 编译二进制；全程可复现（固定种子 + 输入哈希）；`docs/THIRD_PARTY.md` 自动生成。
**收益**：许可证完全可控、可审计、可替换；不受任何上游词库变动影响。
**验收**：在干净机器上一条命令重建出与发布版一致的词库（哈希相同）；生成的许可证清单与手工核对一致。

---

## 3. 反模式清单（明确不做的事）/ Anti-patterns

| 不做 | 原因 |
|---|---|
| ❌ 运行期 YAML 配置引擎行为 | 解析慢、错误运行期才暴露、用户配置门槛高（Rime 最大的抱怨来源） |
| ❌ 「重新部署」概念 | 用户不该理解「部署」；改词应立即生效 |
| ❌ 用 `Mutex` 保护引擎 | UI 抢锁 = 掉帧；应单线程 actor + 不可变快照 |
| ❌ 用户词放 LevelDB/随机 IO | 每键随机读盘必然卡顿（librime 的历史包袱） |
| ❌ 在产品中内置第三方服务凭据 | 法律与道德双重风险；应要求用户自填 |
| ❌ 在 `onCreateInputView` 里读磁盘/初始化引擎 | 直接导致「弹键盘卡一下」 |
| ❌ 运行期全量校验词库 CRC32 | 启动杀手（librime#627 的教训） |
| ❌ 设置项堆砌 | 能自动判断的绝不做开关 |
| ❌ 抄任何 GPL 项目的代码 | 只借鉴思路 |

---

## 4. 里程碑建议 / Suggested Milestones

| 里程碑 | 内容 | 验收 |
|---|---|---|
| **M1 · 骨架** | #1 #2 #3 #5 #6 | 键盘秒开、全拼可用、进程稳定 |
| **M2 · 输入方式** | #8 #9 #10 #14 | 全拼/双拼/九键/五笔四模式可用 |
| **M3 · 智能** | #4 #7 #12 #13 #17 | 整句与学习达到可发布水平 |
| **M4 · 体验** | #11 #16 #19 #20 | 语音、隐私、编辑面板、CI 门禁 |
| **M5 · 生态** | #15 #18 #22 | 词库更新、同步、自举工具链 |
| **M6 · 进阶** | #21 | 端上神经语言模型 |

---

## 5. 出处 / Sources

- librime 启动慢（全量 CRC32）: <https://github.com/rime/librime/pull/627>
- librime 用户词惰性丢弃与半衰期: <https://github.com/rime/librime/pull/1205>
- librime 用户词内存缓存: <https://github.com/rime/librime/pull/1196>
- librime 减少临时字符串拷贝: <https://github.com/rime/librime/pull/1197>
- librime 长输入卡顿: <https://github.com/rime/librime/issues/510>
- librime 配置与状态目录分离建议: <https://github.com/rime/librime/issues/1169>
- libime beam search 默认 20: <https://github.com/fcitx/libime/blob/master/src/libime/core/decoder.h>
- libime 邻键纠错 profile: <https://github.com/fcitx/libime/blob/master/src/libime/pinyin/pinyincorrectionprofile.h>
- libime 双拼方案格式: <https://github.com/fcitx/libime/blob/master/src/libime/pinyin/shuangpin-profile-format_zh.md>
- libime 历史 bigram / 用户语言模型: <https://github.com/fcitx/libime/tree/master/src/libime/core>
- Trime 弹键盘耗时分析: <https://github.com/osfans/trime/issues/713>
- Trime 切键盘强制部署: <https://github.com/osfans/trime/issues/1169>
- Trime 性能劣化 / 卡顿讨论: <https://github.com/osfans/trime/issues/1766> · <https://github.com/osfans/trime/issues/1172>
- Trime RAM 占用: <https://github.com/osfans/trime/issues/92>
- Trime 九宫格问题: <https://github.com/osfans/trime/issues/1894> · <https://github.com/osfans/trime/issues/1005> · <https://github.com/osfans/trime/issues/1464>
- fcitx5-android 大词库首键卡顿: <https://github.com/fcitx5-android/fcitx5-android/issues/532>
- fcitx5-android 九宫格请求: <https://github.com/fcitx5-android/fcitx5-android/issues/109>
- FlorisBoard WebView 重复文本: <https://github.com/florisboard/florisboard/issues/2135>
- FlorisBoard 剪贴板打码过度: <https://github.com/florisboard/florisboard/issues/3289>
- AnySoftKeyboard Compose TextField 回车多字: <https://github.com/AnySoftKeyboard/AnySoftKeyboard/issues/3839>
- Weasel 多线程并发调用崩溃: <https://github.com/rime/weasel/issues/1487>
- `EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING` 语义: <https://developer.android.com/reference/android/view/inputmethod/EditorInfo>
- `SpeechRecognizer` 的 `<queries>` 要求: <https://developer.android.com/reference/android/speech/SpeechRecognizer>
- 语音音源与 AEC/AGC 原文: <https://developer.android.com/reference/android/media/MediaRecorder.AudioSource>
