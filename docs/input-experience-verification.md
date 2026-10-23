# 输入与语音体验：源码调研后的落地与验证

日期：2026-10-10。开发基线 HEAD 为 `2878db5b288fe9dd5f62d670a7b35c531b2ab978`；本文记录本轮代码与本地预览验证，正式版尚未发布。官方源码证据、固定提交及各平台差异见 [源码复核](msime-source-review.md)，新增实现独立编写。

## 已落地行为

| 场景 | 新行为与边界 |
| --- | --- |
| Android 外接键盘 | 上下、Tab／Shift+Tab 移动候选；PageUp／PageDown 移动九项，数字对应当前九项页。空格确认；主动移动后回车确认，未移动时仍原码上屏。空闲联想不抢应用 Tab 和翻页滚动。导航复用候选，不重新解码。 |
| 候选注音 | 默认关闭；开启后括号注音跟随实际高亮候选，其他项不显示，键盘高度不变。 |
| 长候选 | 手机长按可查看及复制完整候选，展开网格也支持。后续加载的候选有相同入口；敏感输入不提供复制操作。 |
| 候选更新与点选 | Android 候选具有随输入框／候选变化更新的代次；点击、展开列表及硬件数字选择校验可见快照，重绘前拒绝旧动作。相同文字重新组成也不是旧候选。Mac 窗口的闭包绑定原控制器、显示代次及输入上下文，旧菜单不能转而操作新控制器。 |
| Android 语音 | 启动返回失败、抛错、同步结束和立即重试均收回旧识别器；先使回调过期再取消。取消清空未确认结果，停止／失败保留已有文字，多引擎已结束结果冻结。隐藏面板取消旧长按与触摸，防止继续删除或旧 CANCEL 影响终稿。 |
| Mac 语音 | 准备时可取消，收尾时可结束等待。权限等待上限 60 秒；插件准备及收尾各 15 秒。完成、失败和关闭回收音频及插件；迟到 handle 返回后取消，旧回调不能改新草稿。手动修改后的草稿不会被后续识别结果覆盖。阻塞的插件宿主调用无法被强行抢占。 |
| Mac 草稿确认 | 录音浮窗不抢正文焦点；“文字 → 编辑草稿”打开可接收键盘输入的独立窗口。完成后重新点选其他应用的输入位置，再确认上屏；失败保留草稿。复制／清空收进文字菜单。 |
| 完整离线语音版 APK 下载 | 使用有界、可取消的下载信息读取；校验值绑定准确版本及资产，拒绝非十六进制摘要和近似文件名。每个任务独有取消令牌，清理完才能重试。显示真实连接／下载／校验阶段，取消显示清理状态，沿用选择的镜像。 |
| 组合串插入点 | 全拼、双拼和英文组合中可以移动插入点：手机点按或拖动预编辑行，或在空格上滑动；外接键盘 ←／→、Home／End、Delete。Mac 点击文档里的拼音可放插入点（应用报告点在标记文字里时才接管，否则照旧结束组合），竖排候选时 ←／→ 移动插入点，横排仍移动高亮；Home／End／Delete 两种排列都可用。按音节编辑：外接键盘 Ctrl+←／→、Mac ⌥←／⌥→ 按显示的音节跳（双拼按全拼音节），Ctrl／⌥ 加退格或 Delete 删掉插入点前／后一个音节；九键等不支持音节编辑时退回单键删除。新键插在插入点，退格删插入点前的键，插入点在开头时退格撤回前一次选词。九键、五笔、手写与 v 模式不提供插入点。 |
| 退格撤回选词 | 选定一部分词后，紧接着的退格把刚选的词还原为拼音，连续退格按相反顺序逐个撤回；输入新字母或移动插入点后，退格恢复为删字母。 |
| 以词取字 | 组合中按 `[`／`]` 取高亮候选（未移动时为首选）的首字／末字；手机长按候选菜单有「只上屏首字／末字」。取字吃掉整个词的拼音，不把整词记入学习；取出的字按它自己的读音记下（「我们」取「们」记 men = 们）。 |
| 只看单字 | 手机展开候选侧栏、Mac 展开网格底部有「单字」开关（中文非手写）。只列单个汉字；单字多排在词后，开启时自动多取几页。点选按原候选位置，开关在本次展开中保持。 |
| 字形筛选（辅助码） | 全拼、双拼、九键打完拼音后按 `` ` ``，再输入笔画（h 横 s 竖 p 撇 n 点 z 折）或部件拼音的开头（时 = 日寸：`r`、`rc`）；只留下首字或末字对得上的候选，次序不变（`` zhongguo`w `` 仍以「中国」居首）。字形码不进拼音：退格先删字形码、再退出筛选；选词、移动插入点或标点都会结束筛选。手机展开候选下方有「一丨丿丶乛」笔画键，预编辑里笔画显示为笔形；笔画键（以及外接键盘上大写的 HSPNZ）只按笔画比，免得「点」因为 世 = 廿乚 的 nian 也算对上。数据为万象拼音反查表（CC BY 4.0），只收拼音字库中的 4.3 万字，压缩后约 0.8 MB。 |
| u 模式拆字查字 | 全拼里以 u 开头（u 不是声母），后面打部件拼音或笔画查不会读的字：`ukoukoukou` = 品、`uririri` = 晶、`ukkk`（部件首字母）也行、`uhs` = 十；候选注出带声调的读音。排序先看贴合程度（打全了部件读音或笔画 > 读音连起来的开头 > 每个部件只打首字母 > 其他），同档按常用程度；查字时忽略数据里只有一个部件的辅助码条目。单独一个 u 仍照常纠成 wu、yu；恰好拼成英文词（us、use、up）时英文词排第一。u 模式里不插表情、不能移动插入点。与字形筛选共用同一张表，逐条扫描约 3 ms（Mac）。 |
| 光标编辑与大写锁定 | 手机光标面板为 5 × 4（02 §9）：第一行 Tab、复制、⌃、粘贴、⌫，第二行开头、‹、选择、›、Del，第三行末尾、全选、⌄、剪切、返回，第四行撤销、重做、词 ←、词 →、⏎；每格只放一个键，没有跨行的键，方向键不会盖住撤销与重做。撤销与重做交给编辑器做，因此能连着退很多步（网页字段与终端退回 Ctrl+Z／Ctrl+Shift+Z）；没有选区时复制、剪切变灰，选区没了「已选中文字」提示随之收起。「选择」模式下左右键直接设选区（锚点不动、焦点按字形簇移动），上下键与读不到文本时先按下 Shift 键本身再发方向键——普通文本框只认 Shift 键按下的状态，原先只在事件上标 Shift，实际不会扩选。英文键盘双击 Shift（400 ms 内）或长按 Shift 锁定大写，再点一下解除；锁定不带进中文（切到中文后字母照常打拼音），中文里长按 Shift 仍是一次大写。Mac 用系统 Caps Lock（直通大写）与系统文本编辑，不另设面板。 |
| 悬浮键盘外框 | 顶条从 48dp 收成 28dp（拖动横条、X 停靠、上方两角），底边从 48dp 收成 14dp，只放下方两角，关掉下方两角时不留；去掉「拖动边角调整大小」提示文字。缩放角只画一小段贴着圆角的淡弧，触控区仍是 48dp 宽、与所在的条等高，不压按键。卡片上下合计少占 54dp。 |
| 学过的词带进长句 | 原先学过的词只在整段拼音正好是它时才排前；放进更长的句子，词库里现成的长词（「他们说」）整块胜出，选过两次的「她们」不出现。现在一个词被选中、并且已经顶替系统首选后，含原词的长词让位：让出原词输给学过的词的差距、长词自带的整词优势，再多让一点盖过词边界的搭配代价；搭配模型给学过的词按被顶替的原词打分（模型按字算，「她们」本身没有搭配数据）。只选一次不动长句；单字不外溢（选过「她」不会把「他说」改成「她说」）；原词本来就是首选的（如「手机」）不算顶替；再选回原词两次，长句恢复。整段输入的「近期明确选词」（从列表点选、按空格接受首选都算）不再把被顶替的长词置顶或拉回，否则按空格打过一次长句就永远学不动它；降频过的词再被选中时降频作废，之后照常按学习排序。 |
| 双拼英文片段 | 四套双拼支持有界英文片段，保留大小写及英文结束后的双拼边界；完整合法中文读法优先。原样字母候选避免自动把 `dont` 改成 `don't`。Android 组合中的大写路由已接通，Mac 原有组合字母路由保留大小写。数字、下划线、点号等技术字符串尚未整体纳入片段。详见 [混输边界](learning-and-english.md)。 |
| 收纳袋动图预览 | 收纳袋里存的就是原文件（按文件头识别格式、原字节落盘，不解码也不重编码）。管理页原先用只解第一帧的位图解码，动图看着像一张静止图片；现在手机管理页与键盘面板、悬浮窗走同一个预览组件（动图解成会播放的动画），Mac 收纳窗的缩略图改用会播放动画的 `NSImageView`。 |
| 候选置顶 | 长按候选的菜单里多了「置顶这个词」，选中的词此后排在该读音的最前，直到改成「恢复正常排序」。置顶与「降低优先级」是同一组排序策略，同一时刻只有一个生效；置顶也会把词记进个人词表，所以取消置顶后它仍按学习排序靠前。两端入口相同（手机长按候选，Mac 右键候选）。 |
| Mac 展开网格的笔画键 | Mac 展开候选网格底部多了一排「一丨丿丶乛」笔画键，与手机一致：按一下加一笔字形码，候选随之收窄；只给拼音类方案（全拼、双拼、九键、14 键），手写、英文、五笔没有这一行。 |
| 手写连写真正成行 | 连写（多字）改为「写完一行再确认」：笔画留在同一块画布里一起识别，停笔不再自动上屏，由用户点候选上屏。此前连写也按停顿逐字确认——字间停顿本是写字的常态，于是第一个字先被上屏、画布清空，第二个字永远进不了同一组，实际手感与单字没有区别，内核那套按留白分成 2–4 组的多字识别根本没机会触发。两端同步：手机连写模式下既不因停顿上屏、也不在下一次落笔时提交上一字；Mac 同样取消连写的停笔上屏。手机一行最多留 256 笔（内核单次识别的上限）。单字模式的停笔上屏不变。 |
| 停笔时间的两种用途分开 | 判字时间原先被两件事复用：一是「一笔刚写完」（决定贴着书写区边缘起笔算不算写字），二是「该不该上屏」。关闭停笔自动上屏时它被设成无限值，于是书写区外 8dp 那条容差永远成立——写完之后过很久再去点书写区边缘，仍然会被当成写字，那个位置的按键**永远按不到**。现在判字时间始终是设置里的值，是否上屏由模式与「停笔自动上屏」开关在提交处判断。 |
| 九键标点不被英文三格截断 | 「英文三格」描述的是三格英文联想词；九键「1」键的 16 个本地标点只是借同一行居中展示，却被当成英文建议条砍到 3 个，其余 13 个（`、：；…～“”@` 等）既看不到也没有「更多」入口。现在本地候选列表带独立标记，不再受三格样式影响，可以滚动看全。选用市场样式「圆润」时才会遇到。 |
| 剪贴板卡片与表头图标 | 「只看当前剪贴板」时那张卡片的 id 恰好等于「正在展开动作的卡片」的默认标记，于是动作行（固定／编辑上屏／删除）一进面板就盖住卡片文字，按「固定」「删除」毫无反应；现在只有真正记录下来的条目才提供动作。常用语表头的图标从右往左画，而图标数组的顺序写反，最右的铅笔执行的是「添加常用语」、「+」反而进入多选删除；现在图标与动作逐位对应。列表变短（删除、清空、切「只看已固定」、过期裁剪）后滚动位置会夹回可用范围，面板不再整块空白。 |
| 重新选词时不再承诺「只上屏首字」 | 候选长按菜单的「只上屏首字／末字」只在组合输入时成立；重新选词时一个候选就是整段替换选区（这一行为是有意的，控制器注释写明），菜单却照样列出这两项，点了会把整个词覆盖上去，与文字承诺不符。现在重新选词不显示这两项，与 `onShapeStroke` 已有的同类判断一致。 |
| 右半边符号替换选区 | 成对符号的右半边只检查「光标后那个符号是否就是它」，不看有没有选中文字：选中一段再输入 `”`，本意是替换，实际被当成「跨过已有的右半边」，选区原封不动，像没生效。现在两个分支都先查选区——有选区时左半边也交给普通输入去替换。 |
| 剪贴板与常用语搜索 | 面板表头最左一格是放大镜：打开后列表上方出现搜索行，字母、数字与标点进查询（不上屏），列表按内容过滤，回车把第一条上屏，删空后再按一次退格收起搜索行（那一下不会删编辑器里的字），返回键先收搜索再收面板；查询空着时回车、方向键等功能键照旧，空格与标点归搜索框（不会在正文里打出空格或标点）。中文内容按拼音也能搜：搜索框只能收字母，控制器把打的拼音交给内核读成词（`weixiao` → 「微笑」），再拿这些词去匹配内容；内核已经在组合中（打了一半拼音就开面板）时不做这件事，宁可只按字面搜，也不清掉用户打了一半的拼音。 |
| 表情按名称搜索 | 符号面板停在表情或颜文字分类时，网格上方多一行搜索；点一下搜索行，行下铺开三行字母键（面板盖住了主键盘，这是唯一能打字的地方），退格在末行末尾、长按连删。输入法窗口拿不到输入焦点，面板里不能放 EditText（会顶起第二个输入法），所以查询来自键盘本身：字母在搜索行里组成拼音（未确定部分加下划线、跟候选栏一致），空格只把拼音确定下来（不上屏），回车取第一个结果，退格删空即退出搜索，返回键先退搜索再收面板；查询空着时回车照旧透传，空格、数字与标点归搜索框。外接键盘的字母同样进查询（实体键盘有焦点时面板盖不住它）。上滑 / 长按改写只改查询里刚输入的那个字母，不会撤掉正文。退出搜索、换分类或重开面板都会把查询从行上抹掉。匹配离线做，范围是中文名、英文名、两字以上的分类与名字的拼音（无声调，`weixiao`、`wei'xiao`、`weix` 都命中「微笑」），每个空格分开的词都要命中，拆开的词连起来也算一个词；排序：直接打出表情本身 > 名字等于关键词 > 名字或拼音音节以关键词开头 > 名字里的词以关键词开头 > 包含 > 只有英文名或分类命中，同档短名优先、再按目录顺序。搜索中结果网格读屏播报「名字 · 拼音」。Mac 的表情库窗口有同样的搜索框与同一套排序规则。 |
| 表情目录带英文名与拼音 | 目录新增 CLDR 英文名与逐字拼音（`he hei` 这样的无声调音节），`tools/expressions/build.py` 从内核已有的 `tones.tsv` 取读音、多音词用其中的覆盖表，生成时不需要联网或额外依赖；旧目录缺这两个字段时 Mac 端按空串解码，不报错。 |
| 按应用记住中/英 | 在某个应用里显式切过中/英，回到这个应用就还原那次选择；没切过的应用照旧按输入框类型给默认，别的应用里的切换互不影响，密码框、纯数字框这些不允许中文的输入框既不读也不写这份记忆。同一个输入框重新开始（聊天发送后清空）仍按当前选择走，不回头去读记忆。只记中/英，不记布局与方案（那些是全局偏好）；记住的是显式切过的应用，最多 100 个，超出丢最早的，包名清单与取值都存在设置里，可在「输入方案 → 中/英」一键清除。两端一致：Mac 在 `activateServer` 时按包标识还原，「中英切换」组里有同一个开关与清除按钮。 |
| 斜着轻点不再失灵 | 纵向网格（候选展开页、剪贴板卡片、符号网格）在手指横向滑过 8dp、纵向不足时会把按下项作废，抬手既不滚动也不点选，于是斜一点的轻点偶发「点了没反应」。这些网格只有纵向滚动，横向位移不改变任何东西；真正需要横向滚动的是符号面板底行与分类列，它们各自实现手势，不走这段逻辑。现在只有纵向位移才作废点按。 |

## 本轮验证

**2026-10-22 表情工具栏选中态 / Emoji toolbar selection**

表情面板原先没有声明对应的工具编号，打开后顶栏仍为未选中状态。补齐面板与表情工具的关联，复用已有高亮绘制；关闭面板清除高亮，切到剪贴板则高亮剪贴板。公开触摸回归先观察到面板已打开、按钮状态却为 null（预期“已打开”），修复后打字交互专项 **61 项通过、0 失败／错误／跳过**。设备测试包首轮编译发现新增节点断言缺少可空处理，已修正，未改动生产行为。

The emoji panel previously omitted its toolbar tool id, so opening it left the toolbar unselected. Declaring the association reuses existing highlight rendering; closing clears it and switching to the clipboard highlights that tool. A touch regression first observed an open panel with a null button state instead of “已打开”; the corrected typing interaction group passed **61 tests with zero failures/errors/skips**. The first device-test build found missing nullable-node handling in the new assertions; this was corrected without changing production behavior.

修复后 Android 全量 **1061 项通过、0 失败／错误／跳过**；调试与设备测试 APK 构建成功。最终包的生产 View 设备用例 **1 项通过（13.729 秒）**，通过实际触摸验证表情打开高亮、再次点击关闭清除高亮，以及切到剪贴板后高亮转移。已查看实际截图：顶栏笑脸按钮有圆角高亮背景，底部“表情”分类也保持选中。截图放在临时目录，未修改 tracked 截图；未提交或发布。

The corrected full Android suite passed **1061 tests with zero failures/errors/skips**, and both debug/device-test APKs built successfully. Final-package production-View acceptance passed **one case in 13.729 seconds**, using actual touches to check the emoji highlight, toggle-off clearing and transfer to the clipboard. The inspected device screenshot shows a rounded highlight behind the toolbar smiley and the selected bottom emoji category. Captures remain temporary, with no tracked screenshot changes, commit or release.

**2026-10-22 退笔等待与长按退格 / Undo pauses and repeated backspace**

单字停笔自动上屏现在以最近一次退笔或重做重新计时：成功修改笔迹后取消旧任务，保留笔迹时安排剩余等待，全部退空时停止计时；定时回调在提交前再次核对停笔时长。连写、叠写与关闭自动上屏仍由用户确认。三个公开行为回归先观察到旧任务提前上屏／退空后重做不再上屏，再验证修复；计时断言在截止前后保留余量，避免渲染任务推进测试时钟导致毫秒边界误报。

Single-character auto-commit now starts a fresh pause after undo or redo: successful ink edits cancel the old task, retained ink schedules the remaining wait, and undoing all strokes stops the timer. The callback rechecks elapsed idle time before committing. Continuous/overlay writing and disabled auto-commit still require explicit confirmation. Three behavioral regressions first demonstrated early commits or missing commits after restoring emptied ink, then verified the fix; assertions allow margin around the deadline because render work also advances the test clock.

英文长按退格现在与短按一样刷新派生状态。句首的 `A` 尚未上屏时连删清空组合，下一次输入恢复为 `B`；组合仍含 `A` 时续写保持小写，手动大写锁定也保留。已先观察 `B ` 预期得到 `b ` 的失败，再补齐连删后的状态刷新。

Repeated English backspace now refreshes derived state like an ordinary tap. Deleting an uncommitted sentence-initial `A` restores capitalization for the next `B`; a composition still containing `A` keeps subsequent letters lowercase, and manual Caps Lock remains active. The regression first produced `b ` instead of the expected `B ` before the missing post-delete refresh was added.

手动置顶也服从同一读音资格检查：输入完整 `shijia` 时，置顶于 `shijian` 的“时间”不能靠置顶越过“试驾”；`shijian`、`sj`、`shij` 的正常置顶继续有效。已有候选的排序与首屏外的置顶回填都检查本次解码跨度，不重复解码；补全、纠错和邻键候选仍可按正常分数竞争。四项新增 Rust 回归覆盖完整音节、回填、邻键、纠错与策略重载，并分别验证两处门槛确实能捕获错误。

Manual pins now obey the same reading eligibility: a 时间 pin learned for `shijian` cannot leap ahead of 试驾 for the complete `shijia` reading, while valid `shijian`, `sj` and `shij` pins remain effective. Both existing-candidate sorting and pin backfill inspect this decode's spans without decoding again. Completion, spelling-correction and touch-neighbor candidates continue competing by ordinary scores. Four added Rust regressions cover complete syllables, backfill, touch, correction and policy reload, with independent checks that both eligibility gates catch their respective defect.

手写首次修复后的三个计时断言在截止前 1 毫秒处误报：渲染也推进测试时钟；改为截止前 100 毫秒／截止后 100 毫秒检查，保留旧任务触发点的失败断言。专项 **55 项通过**；英文连删新增回归先出现 **1 项失败**（`b `），修复后 Android 全量 **1060 项通过、0 失败／错误／跳过**。两项生产 View 模拟器验收 **15.206 秒通过**，使用真实包内识别器：慢档 1200 毫秒停笔、退笔后跨过旧截止点仍留一笔、真实长按恢复两笔并最终上屏“十”，以及真实英文连删后上屏 `B `。已查看手写重做与英文输入截图；前者笔迹完整，后者初次截图仍捕获到上一帧气泡，后续取图需等待渲染稳定。首次宿主 GPU 模拟器进程异常退出后，专用验收设备改用软件渲染。

The first implementation's three timing assertions fired one millisecond before the deadline because rendering also advances the test clock. They now check 100 ms before/after it while retaining the old-timer failure assertion. The handwriting group passed **55 tests**. Repeated-backspace coverage first had **one failure** (`b `); the corrected Android suite passed **1060 tests with zero failures/errors/skips**. Two production-View emulator cases passed in **15.206 seconds**, using the packaged recognizer: a 1200 ms pause, undo retaining one stroke beyond the old deadline, a real long press restoring both strokes before committing 十, and English repeated backspace followed by `B `. Both screenshots were inspected; handwriting remained complete, while the first English capture still showed the preceding frame's bubble, requiring a settled follow-up capture. The dedicated emulator switched to software rendering after the host-GPU process exited unexpectedly.

最终统一计时入口后的 Android 全量再次 **1060 项通过、0 失败／错误／跳过**；Lint **0 errors、202 warnings、2 hints**，保留既有提示。测试截图重录输出改到临时目录，并恢复了本轮基线测试生成的 191 张 tracked 截图（会话初始状态中这些文件均无改动），避免将渲染环境差异混入补丁。

After unifying timer scheduling, the final Android suite again passed **1060 tests with zero failures/errors/skips**. Lint reported **zero errors, 202 warnings and two hints**, retaining existing diagnostics. Snapshot recordings now go to a temporary directory; 191 tracked screenshots generated by this round's baseline run were restored because none were modified at session start, keeping renderer differences out of the patch.

最终 Rust release 工作区 **268 项通过、0 失败、3 忽略**，保留 `examples/predict.rs:46` 的既有 `rest` 未使用变量警告。完整桌面 JNI 套件 **30 项通过、0 失败／错误、15 项条件跳过**：10 项 `NativeAsrTest`、3 项 `ModelsTest`，以及 `JniTest.domainPackLoadsAndUnloads`、`JniTest.externalPluginOptional`；跳过项不计入通过。JNI 首轮有 1 项断言失败：夹具先显式提交“试驾”，按现有规则清除了旧置顶，再检查 `sj` 仍置顶“时间”，与状态变更不符。将有效置顶检查移到显式提交之前后复验通过，未为此改动生产逻辑。

The final release Rust workspace passed **268 tests, with zero failures and three ignored**, retaining the existing unused `rest` warning at `examples/predict.rs:46`. The complete desktop JNI suite passed **30 tests, with zero failures/errors and 15 conditional skips**: ten in `NativeAsrTest`, three in `ModelsTest`, and the two named `JniTest` cases above. Skips are not passes. The first JNI run had one assertion failure: explicitly committing 试驾 cleared the earlier pin under existing rules, so the later `sj` assertion incorrectly expected 时间 to remain pinned. Moving valid-pin checks before that explicit commit passed on replay; no production behavior changed for this fixture correction.

最终调试包在专用 Android 14 模拟器上串行运行 **4 项设备验收，全部通过（39.874 秒）**：置顶读音边界、快捷输入、手写和打字手势。手写真实长按重做后保留两笔，随后等待上屏“十”；英文长按删空组合后上屏 `B `。最终三张截图已实际查看：英文为稳定的 `B` 正文，手写“十”笔迹完整，拼音 `shi'jia` 首选“试驾”；键盘底行与三键导航无重叠。截图是生产 View 在真实 Activity 窗口中的画面，尚未在实体手机验收，也不作为真人手写准确率或输入速度证据。

The final debug APK passed **all four serial device cases in 39.874 seconds** on the dedicated Android 14 emulator: pinned-reading boundaries, quick input, handwriting and typing gestures. A real handwriting long press restored both strokes before 十 was committed after the pause; clearing English composition by repeated backspace then committed `B `. All three final screenshots were inspected: settled English text `B`, complete 十 ink, and 试驾 first for `shi'jia`, with no overlap between the keyboard's bottom row and three-button navigation. These are production Views in a real Activity window; physical-phone acceptance and human handwriting/typing performance remain unmeasured.

本轮最终调试 APK SHA-256：`00a64d7b7bf2426d0c7259dbfd75ef97dd82a282f77e6bd584c0a71b67e92e7a`。验收结束后再次核对，实际安装的 base.apk 与本地产物逐字节一致，包内 arm64-v8a／x86_64 的 `libweave.so` 均与当前 strip 产物一致。工作区无 tracked 截图改动；未提交、打标签、推送或发布。

The final debug APK's SHA-256 is recorded above. A post-acceptance check confirmed that the installed base.apk matches the local artifact byte for byte and both packaged `libweave.so` architectures match current stripped outputs. No tracked screenshots are modified. No commit, tag, push or release was made.

后续审查尚未落地：快速语音横滑只有 DOWN／UP 时的终点判定、长全文滚动时的排版复用、超过 64 笔的连写切换叠写时的分组合法性，以及新旧模式偏好同时通知引发的重复识别。这些结论来自源码审查，尚未新增失败回归或设备复现，不计入本轮已完成修复。

Pending source-review follow-ups are release-coordinate handling for voice swipes with only DOWN/UP, layout reuse during long transcript scrolling, valid grouping when switching a line exceeding 64 strokes to overlay, and duplicate recognition from legacy/new preference notifications. These have no new failing regression or device reproduction yet and are excluded from this round's completed fixes.

**2026-10-22 日期时间、u/v 与计算 / Date/time, u/v and calculation**

补齐完整拼音日期／时间／星期、今天／明天／昨天／现在候选，普通词保持首选，动态值随后；双拼／九键按首选完整解码词提供相同入口。句中片段、分段选词后的剩余码不触发。模板新增 `{weekday}`。v 模式支持常见 Unicode 运算符及单个结尾等号，负数／小数提供中文读法、金额与千分位；Android 字符等号和文本等号均可请求正文算式结果，敏感框抑制，v 中括号保持组合。Mac 对齐 v 字符与候选按键优先级，检查整个 v 后缀；正文算式仍用半角等号，默认英文原样直通策略不变。u 拆字／笔画查字沿用现有实现。用法与数值精度边界见[快捷输入](quick-input.md)。

Full pinyin now offers local date/time/weekday and relative-day values after the ordinary first word. Double pinyin/keypad trigger from a complete first decoded word; sentence fragments and partially selected input do not expand. Templates add `{weekday}`. V composition accepts common Unicode arithmetic and one terminal equals sign, plus signed/decimal readings, financial amounts and grouping. Android character/text equals paths both offer editor-side results, suppress sensitive fields and preserve v parentheses. Mac aligns v characters and key priority, validating the entire suffix; editor formulas still use ASCII equals and default literal English policy remains. Existing u shape/stroke lookup is retained. See [Quick input](quick-input.md) for usage and numeric limits.

公开行为测试已观察日期完整词／模板、数字转换与宿主路由的预期失败后修复。真实词库的首轮 JNI **18 通过、1 失败、2 跳过**，设备组合 **2 通过、1 失败（107.106 秒）**：默认表情装饰在“时间”后插入钟表，挤开当前时间，违背紧随普通词的排序要求。已将日期值插入移到表情装饰之后、繁体转换之前；不通过关闭默认表情或放宽第二候选断言规避问题。修复后完整 Rust release 工作区 **263 通过、0 失败、3 忽略**，JNI **19 通过、0 失败、2 原配置跳过**。Rust 既有示例未使用变量警告保留。

Observed red/green behavior covers full date words/templates, numbers and host routing. The first real-dictionary JNI run had **18 passes, one failure and two skips**; production-View device acceptance had **two passes and one failure in 107.106 seconds**. Default emoji decoration placed a clock immediately after 时间, displacing the current time. Date values now insert after emoji decoration and before traditional conversion; default emoji remains enabled and the second-candidate assertion is retained. The corrected full release Rust workspace passed **263 tests, zero failed, three ignored**; JNI passed **19, zero failed, two existing conditional skips**. The existing unused-variable example warning remains.

Android 最终宿主源码 JVM **1054 项通过、0 failures／errors／skipped**；Lint **0 errors、202 warnings、2 hints**。arm64-v8a／x86_64 原生库已从排序修复后的源码重建；JVM 检查不替代最终 APK 的设备验收。

The final Android host sources passed **1054 JVM tests with zero failures/errors/skips**; lint reported **zero errors, 202 warnings and two hints**. Both native architectures were rebuilt after the ordering correction; JVM results do not substitute for final-APK device acceptance.

Mac 从最终源码重新构建 arm64／x86_64 通用 C ABI 静态库并强制重新链接，完整 Swift 两套测试分别 **60／231 项通过**，另 **2 项跳过、0 失败**；跳过项为 `systemBonjourResolvesBothNativeListeners`、`builtPacksMatchTheCatalog`。编译与链接共 33 次警告，另有两条 sandbox extension 运行消息，测试正常结束；未安装或切换系统输入法，未声称完成 Mac 跨应用人工验收。

Mac rebuilt the final universal arm64/x86_64 C ABI archive and forced fresh Swift linking. The two complete test runs passed **60 and 231 tests**, with **two skips and zero failures**; skipped cases are named above. There were 33 compiler/linker warning emissions and two sandbox-extension runtime messages; tests completed successfully. No system IME was installed/switched, and no Mac cross-application manual acceptance is claimed.

排序修复后的设备组合再次为 **2 通过、1 失败（74.376 秒）**：快捷用例已通过时间与日期候选断言，但 `UiAutomation.takeScreenshot()` 返回 null 导致测试中断。已从行为用例移出截图，改用独立 `adb screencap`，不修改生产逻辑或放宽输入断言。最终调试 APK SHA-256 为 `1c7a1d6fd03f1cb219b1cb6387ff9b5dadf094f1b4f904db3023ab7ddbd4adf1`；实际安装 base.apk 与本地产物逐字节一致，包内两种原生库均与当前 strip 产物一致。

The post-ordering device replay again recorded **two passes and one failure in 74.376 seconds**: date/time candidate assertions passed, then a null `UiAutomation.takeScreenshot()` aborted the quick-input test. Capture moved out of behavioral acceptance to separate `adb screencap`; no production behavior or input assertion was relaxed. The final debug APK SHA-256 is recorded above; the installed base.apk matches the local artifact byte for byte, and both packaged native libraries match current stripped outputs.

最终 APK 的串行生产 View 组合验收 **3 项通过（54.820 秒）**：快捷输入、拼音读音边界、打字手势。快捷用例在真实 Activity 的生产键盘上触摸输入并点选时间／日期，验证 `uhs` 空格为“十”、Unicode v 算式为 `640`，正文 `128*4=` 的本地候选可点选追加 `512`。日期时间值与设备本地时钟核对。尚未在实体手机验收。

Final-APK serial production-View acceptance passed **all three cases in 54.820 seconds**: quick input, pinyin-reading boundaries and typing gestures. The quick-input case touches the production keyboard and time/date candidates in an Activity, checks `uhs` + SPACE → 十 and Unicode v arithmetic → `640`, then taps the local editor-side result to produce `128*4=512`. Date/time values are checked against the device clock. Physical-phone acceptance remains outstanding.

正式 IME 的首个脚本尝试未找到候选无障碍节点而中断；独立 `adb screencap` 实际显示“时间”后紧随当前时间。该系统的 UI 导出只含编辑器节点，因此后续日期时间与 v 选词改用调试桥接的正式控制器选择接口，正文计算用屏幕坐标触摸。生产 View 用例的候选仍通过真实触摸验收，不把 UI 导出失败算作产品输入失败。

The first actual-IME script attempt stopped because no IME candidate accessibility nodes appeared in the exported hierarchy; separate screencap showed the current time immediately after 时间. On this system the dump includes the editor only. Subsequent service checks use the debug bridge's production-controller selection for date/time and v, and a screen-coordinate touch for editor arithmetic. Production-View candidate acceptance still uses real touches; UI export failure is not treated as a product-input failure.

正式服务串行定向检查最终 **5／5 通过**：`shijian` 选当前时间 `02:21`，`riqi` 选 `2026年10月22日`（模拟器本地时间），`uhs` 空格选“十”，`v（128＋32）×4＝` 选 `640`，正文 `128*4=` 后真实触摸结果得到 `128*4=512`。已查看时间／日期／计算候选和最终上屏截图；单层键盘、候选和导航栏可见。未提交、打标签、推送或发布。

Final serial targeted actual-service acceptance passed **5/5**: current time/date selection using the emulator's local clock, `uhs` + SPACE → 十, Unicode v expression → `640`, and a real result touch producing `128*4=512`. Time/date/calculation candidate screenshots and final committed text were inspected, showing one keyboard, visible candidates and navigation. No commit, tag, push or release.

**2026-10-22 拼音读音与近期选词边界 / Pinyin readings and recent-choice boundaries**

隔离用户数据下复现：先打 `shijian` 选一次“时间”，再打 `shijia`，原先“时间”会跳过 `shi jia` 的正常候选成为首选。邻键接口也能复现：近期选过“时间”后输入 `zhijian`，首键携带邻键 `s`，纠错读法会绕过惩罚抢到“之间”之前。原因是近期置前只检查整串覆盖，session 的原拼写保护又对已学词无条件让步；并非已确认的个人词库错误读音污染，“时间”导出仍为 `shi jian`。

With isolated user data, selecting 时间 for `shijian` once made it lead the next `shijia` input, ahead of literal `shi jia` candidates. A touch-neighbor reproduction also let the remembered 时间 bypass the penalty for interpreting initial `z` as `s` in `zhijian`. Recent front-loading checked only complete input coverage, and literal-reading protection exempted learned words unconditionally. Incorrect stored readings were not confirmed: 时间 still exports as `shi jian`.

近期强制置前与原拼写保护共用读法检查：完整音节、已开启模糊音、简拼及未完成音节补全保留偏好；纠错和把已完整音节补长的路径按正常惩罚竞争。未关闭正常纠错、未清理用户词库、未新增设置。公开 Rust 两项回归先红后绿，另覆盖 `sj`／`shij`／`shijian` 学习保留与实际导出读音；JNI 独立回归也先观察到 `shijia` 首选错误，再验证首选与确认上屏不再为“时间”。

Recent front-loading and literal protection now share a reading check. Full syllables, enabled fuzzy readings, abbreviations and unfinished-syllable completion retain learned preferences; corrections and extensions of already complete syllables compete with normal penalties. Ordinary correction remains enabled, user data is preserved, and no setting was added. Two public Rust regressions failed before the fix and passed afterward, with checks for preserved `sj`/`shij`/`shijian` learning and exported readings. An independent JNI regression also demonstrated the original failure before checking both candidate ranking and confirmed text.

独立复核补充两项模糊音边界回归，均观察到修复前失败：`n_l` 开启、先选“李”再选“你”后，加入无关上文不能让旧模糊选择“李”越过解码首选“你”；同样开启 `n_l`、学过“李”后，输入 `ni` 是否携带 `n→l` 邻键信息不应丢掉模糊音的近期偏好。候选构造对带上下文的完整／模糊读法统一保留解码顺序，图边去重保留模糊音与触摸纠错的不同来源，简拼及未完成尾音节补偿继续保留。修复后 `adaptive_learning` **21 项通过**。

Independent review added two fuzzy-reading regressions, both observed failing before the follow-up: with `n_l` enabled, selecting 李 then 你 must not let an older fuzzy 李 displace the decoder's 你 merely because unrelated context is present; a learned fuzzy 李 must retain the same preference whether `ni` arrives with or without `n→l` touch-neighbor metadata. Candidate construction now preserves contextual decoding for both full and fuzzy readings. Graph deduplication retains distinct fuzzy/touch provenance; abbreviation and unfinished-tail compensation remain. All **21 adaptive_learning tests passed** after the follow-up.

模糊音边界修正后的最终优化构建 Rust 工作区 **248 项通过、0 失败、3 项忽略**；Android JVM **1046 项通过、0 failures／errors／skipped**；JNI `JniTest` **18 项通过、2 项按原配置跳过、0 失败**。Lint **0 errors、202 warnings、2 hints**，既有 Rust 示例警告保留。arm64-v8a／x86_64 原生库及调试／设备测试 APK 构建成功，复用已生成词库；包内两种原生库与当前 strip 产物逐字节一致。此前构建／验证因会话或执行时限中断的尝试不计为通过，结果以完成日志及测试报告为准。

After the fuzzy-boundary follow-up, the final optimized Rust workspace passed **248 tests, zero failed, three ignored**; Android JVM passed **1046 tests with zero failures/errors/skips**; JNI `JniTest` passed **18 tests, with two existing conditional skips and zero failures**. Lint reports **zero errors, 202 warnings and two hints**; the existing Rust example warning remains. Both Android native architectures and debug/device-test APKs built successfully using existing generated dictionaries. Packaged native libraries match current stripped outputs byte for byte. Attempts interrupted by session termination or execution limits are not counted as passing; completed logs and reports establish these results.

专用 Android 14 模拟器上的首轮读音边界／打字手势／学习撤销／手写生产 View 组合验收 **4 项通过（19.785 秒）**。模糊音边界修正后的最终 APK 组合运行实际为 **3 项通过、1 项失败（73.539 秒）**：读音、手势与手写通过，学习撤销用例第三轮未生成“日期时间”；同一最终包单独重跑学习用例 **1 项通过（20.741 秒）**；随后确保没有其他 UI 自动化操作设备，按原四项顺序串行组合复验 **4 项通过（76.582 秒）**。学习造词要求相邻提交间隔不超过 `2500ms`，立即退格撤销窗口为 `1500ms`；该设备用例逐键调度并刷新渲染，却未记录这些间隔，因此此前失败是否越过时间窗口尚不能确认。保留首次失败，后续通过不证明其原因已修复。读音用例通过键中心触摸输入 `shijian`、真实触摸选择“时间”，重新开始输入框但保留学习后打 `shijia`，验证首选和空格上屏均不是“时间”；再打完整 `shijian` 仍首选“时间”。该验收使用真实 Activity 窗口中的生产引擎、控制器、键盘与 EditText，不代表实体手机手感或跨应用输入矩阵。

The initial four-case production-View acceptance run passed on the dedicated Android 14 emulator in **19.785 seconds**, covering reading boundaries, typing gestures, learning rollback and handwriting. With the final APK after the fuzzy-boundary follow-up, the combined run recorded **three passes and one failure in 73.539 seconds**: reading, gestures and handwriting passed, while learning rollback did not promote 日期时间 in round three. An isolated rerun with that same final APK passed **one test in 20.741 seconds**. A subsequent serial combined replay, with no other UI automation operating the device, passed **all four tests in 76.582 seconds** in the same order. Phrase promotion requires adjacent commits within `2500ms`, and immediate-backspace rollback uses `1500ms`; this test schedules and renders each key separately without recording those intervals, so a timing-window violation in the earlier failure remains unconfirmed. The initial failure is retained; later passes do not establish that its cause was fixed. The reading case types `shijian` through key-center touches and taps 时间, resets the editor context while retaining learning, then verifies that neither the first `shijia` candidate nor SPACE-committed text is 时间. Exact `shijian` still ranks 时间 first. This uses production components in a real Activity window, without a physical-device feel or cross-application input-matrix claim.

最终 APK 的正式 IME 服务串行定向复验通过：先 `shijian` 选“时间”，重开输入框后 `shijia` 空格提交“试驾”，再输入完整 `shijian` 空格仍提交“时间”。已检查最终正式服务截图：`shi'jia` 首选“试驾”，“时间”位于普通补全位置，单层键盘和系统导航正常。此前一次自动化在读取编辑器 UI 时出现 `null root node`，未取得输入结果；待 instrumentation 结束后串行执行的本次复验成功。整套正式服务冒烟实际为 **30 项通过、1 项失败、崩溃日志 0**；失败项“语音清掉旧联想”读到空文本，期望“我们今天明天见”。在主动确认新编辑器就绪后，同一按键序列独立连续复验 **3／3 通过**；尚未确定首次空文本的原因，不把复验结果改写为整套全绿。此前被会话中断的冒烟不计完成。

Serial targeted actual-IME acceptance passed with the final APK: learn 时间 from `shijian`, reopen the editor, confirm `shijia` with SPACE to get 试驾, then confirm exact `shijian` to get 时间. The inspected final service screenshot shows `shi'jia` with 试驾 first and 时间 as an ordinary completion, one keyboard and normal navigation. An earlier automation attempt failed to read the editor UI with `null root node` and did not obtain an input result; this successful replay ran serially after instrumentation finished. The full service smoke run recorded **30 passes, one failure and zero crash-log matches**. The voice-clears-old-predictions case read empty text instead of 我们今天明天见. After explicitly confirming that each new editor was ready, the same sequence passed **three consecutive isolated rechecks**. The initial empty-text cause remains undetermined; these rechecks do not turn the full smoke run into an all-green result. Earlier session-interrupted smoke attempts are not counted as completed.

最终本地调试 APK SHA-256：`68918c479b5f68f7eb963e4c7f601fc3d6a9bb7bf6f407520b9d2846b3eb9922`；模糊音边界修正后重新核对，模拟器实际安装的 base.apk 与本地产物逐字节一致，包内 arm64-v8a／x86_64 原生库均与当前 strip 产物一致。未提交或发布。

Local debug APK SHA-256 is recorded above; the emulator's installed base.apk matches the local artifact byte for byte. No commit or release.

**2026-10-21–22 打字手势与联想衰减 / Typing gestures and prediction decay**

中文上屏后联想改用个人搭配随本机学习步数衰减后的有效次数，复用词库淘汰阈值 `0.25`。近期习惯可以超过衰减后的旧习惯，过期记录不再凭个人来源绕过普通联想门控；仍有有效证据的旧搭配继续保留，同一词也可按普通词库接续出现。逻辑时钟按学习推进，不是日历天数。未新增设置、词库来源或持久化格式。

Chinese next-word predictions now score personal pairs by counts decayed over local learning steps, using the existing dictionary-pruning threshold `0.25`. Recent habits can overtake older decayed ones; expired records no longer bypass ordinary prediction gates through personal priority. Older pairs with remaining evidence persist, and the same text may still appear as a normal lexicon continuation. Age follows the learning clock, not calendar days. No settings, dictionary sources or persistence formats were added.

两项推荐行为已观察到修复前失败：旧“下午→开会”压住近期“下午→吃饭”，以及过期搭配在日志压缩前继续作为个人强联想。修复后均通过；另两项回归确认有效旧搭配保留、过期个人搭配可退回词库接续。完整优化构建 Rust 套件 **244 项通过、0 失败、3 项忽略**；既有 `examples/predict.rs` 未使用变量警告仍在。实际生成词库存在，联想门控与学习整合测试使用这些资源运行。

Both recommendation regressions failed before the fix: old 下午→开会 outranked recent 下午→吃饭, and expired history remained a personal prediction before log compaction. Both passed after the fix; two additional cases verify retention of valid older pairs and fallback to lexicon continuation. The complete optimized Rust workspace passed **244 tests, zero failed, three ignored**. The existing unused-variable warning in `examples/predict.rs` remains. Generated dictionary assets are present and used by prediction-policy and learning integration tests.

空格光标手势以“本次是否曾发出光标步进”记录状态，替代左右抵消的净步数；往返移动后的松手和另一根手指落下均不补空格，从未产生步进的短滑仍保留空格。候选栏在 UP 也复核最终横向位移并更新滚动，不再依赖 MOVE 必须先到达。新增五项 JVM 回归均观察到修复前编辑器内容错误，修复后 `TypingFeelTest` **57 项通过**，包含短滑与轻点偏移保护。

Space-bar cursor gestures now record whether any cursor step was emitted instead of net movement. Returning to the starting position does not insert a space on release or when another finger lands; a short slide with no cursor step still does. The candidate bar checks final horizontal displacement and scroll position on UP even without a preceding MOVE. Five new JVM regressions each demonstrated incorrect editor text before the fixes; afterward all **57 TypingFeelTest cases passed**, including short slides and tap jitter.

Android 全量 JVM **1046 项通过、0 failures／errors／skipped**；JNI `JniTest` 显式重新执行 **17 项通过、2 项按原配置跳过、0 失败**；Lint **0 errors、202 warnings、2 hints**。arm64-v8a／x86_64 原生库均重新编译，调试／设备测试 APK 构建成功，构建复用既有生成词库。首次直接在仓库根目录调用原生交叉编译未找到 Cargo.toml，切到 core 工作目录后两种 ABI 成功；失败尝试不计为通过。界面编译仍显示已有的恒真条件与弃用无障碍公告警告。

All **1046 Android JVM tests passed, with zero failures/errors/skips**. An explicit rerun of JNI `JniTest` passed **17 cases, with two existing conditional skips and zero failures**. Lint reported **zero errors, 202 warnings and two hints**. Both Android native architectures were rebuilt and debug/device-test APKs built successfully, reusing generated dictionaries. The initial cross-compilation invocation from the repository root could not locate Cargo.toml; running from the core workspace succeeded for both ABIs. That failed attempt is not counted as passing. Existing always-true-condition and deprecated accessibility-announcement compiler warnings remain.

专用 Android 14 模拟器安装最新包后，打字手势／学习／手写设备测试 **3 项通过（28.982 秒）**。新手势验收使用真实窗口、生产 NativeEngine／InputController／键盘和原生 EditText：光标 `5→3→5` 往返后文字仍为 `hello`；普通空格可输入；候选 DOWN→UP 横滑保留 `ni` 组合串，3dp 偏移的轻点随后提交首选。全屏截图已检查，最终为 `hello 你`、单层键盘及三键导航。测试文件／缓存／偏好隔离并在 finally 释放，截图留在调试应用缓存；此为 Activity 窗口生产 View 验收，不声称实体手机手感或真人输入速度已测量。

After installing the latest package on the dedicated Android 14 emulator, gesture/learning/handwriting device acceptance passed **three tests (28.982 seconds)**. The gesture case uses a real window, production native engine/controller/keyboard and a native EditText: cursor movement `5→3→5` preserves `hello`, a normal space works, a candidate DOWN→UP slide preserves the `ni` composition, and a subsequent 3dp-jitter tap commits the first candidate. The inspected full-screen image shows `hello 你`, one keyboard and three-button navigation. Fixture storage/preferences are isolated and resources released in finally; the image remains in debug app cache. This is Activity-window production-View acceptance, without a physical-device feel or human typing-speed claim.

安装包与本地 APK 的 SHA-256 一致：`32f826b9d72663dd6f11c111ea9d7609baf297268bcde517b63b2a7a7a114984`；包内两种 ABI 的原生库均与本次 strip 产物逐字节一致。

The installed package and local APK have the same SHA-256 recorded above; both packaged ABI libraries match the current stripped build outputs byte for byte.

正式 IME 服务窗口冒烟 **31 项通过、0 失败、崩溃日志 0**。最后在稳定窗口重新输入“你好”并检查全屏截图，只有一层正式键盘，位于系统三键导航之上。设备脚本验证功能与手势结果，不将脚本耗时当作真人手感或语音识别准确率。

The actual IME service passed **31 smoke checks, zero failures and zero crash-log matches**. A final settled-window 你好 input and inspected full-screen image show one service keyboard above system three-button navigation. Scripted checks validate behavior and gesture results; their timings do not establish human typing feel or speech-recognition accuracy.

复核后的设备夹具补齐持久应用上下文的互联单例初始化，短按与候选横滑在同一次主线程调用回放 DOWN／UP，清理目录前等待剪贴板后台 I/O 排空。仅重建设备测试包，三项组合复验再次 **3 项通过（16.286 秒）**，最终手势截图重新检查。生产算法与调试 APK 未变；条件性单例和写入清理路径按源码核实，未额外注入媒体剪贴板或调度超时故障。

Fixture review follow-up initializes the link singleton with the durable app context, replays short DOWN/UP gestures in one main-thread call, and drains clipboard I/O before deleting temporary storage. Rebuilding only the device-test APK and repeating the three scenarios again passed **three tests (16.286 seconds)**; the final gesture screenshot was inspected again. Production algorithms and debug APK are unchanged. Conditional singleton and cleanup paths were checked in source, without additional media-clipboard or scheduling-timeout fault injection.

**2026-10-21 学习撤销与上下文排序 / Learning rollback and contextual ranking**

连续提交造词的临时证据加入提交前像：立即退格既撤销本次证据，也恢复达到门槛前的有效次数；容量清理所移除的其他临时项同样保留在撤销记录中。完整拼写有前文时，候选列表沿用解码器综合近期选择与搭配评分的胜者，不再二次强插近期词。简拼、补全与纠错仍保留既有近期补偿，尚未统一为上下文竞争评分。没有新增设置、持久格式或外部运行依赖。

Provisional phrase learning now records pre-commit state: immediate backspace removes rejected evidence and restores valid evidence from before promotion. Other provisional entries removed by capacity clearing are retained in the rollback record too. For clean full readings with context, candidates preserve the decoder's recency/collocation winner instead of prepending recent choices again. Abbreviation, completion and correction retain existing preference compensation; their contextual scoring is not yet unified. No settings, persistence formats or external runtime dependencies were added.

个人手写纠正只在实际非空笔数相同的个人模板中匹配，避免同字的另一个样本替跨笔数命中通过护栏；基础识别仍保留既有连笔、断笔及笔顺容错。同笔数的缩放／平移适应与隐私限制不变。研究与固定上游证据留在忽略的参考目录，实现独立编写。本轮没有训练模型，也没有已核实来源许可的独立真人准确率结论。

Personal handwriting corrections compare only templates with the same actual nonempty stroke count, preventing an unrelated sample of the same character from admitting a cross-count match. Base recognition retains existing joined/split-stroke and stroke-order tolerance; equal-count scale/translation adaptation and privacy restrictions remain. Research and immutable upstream evidence stay in ignored reference directories; implementation is original. No model was trained and no independent real-writer accuracy claim is made from data of unverified provenance/license.

新增五项 Rust 行为回归均观察到修复前失败：两项临时造词撤销、一项个人模板笔数隔离、一项上下文胜者、一项无关前文下的简拼习惯。最后一项发现并约束了过宽的首版上下文修复。最终优化构建全套 **240 项通过、0 失败、3 项忽略**；Android JVM **1039 项通过、0 failures／errors／skipped**；设备／JNI 测试编译通过；Lint **0 errors、202 warnings、2 hints**。Rust 示例仍有原有 `unused variable: rest` 警告。构建复用已生成词库，不声称重新生成数据。

Five new Rust behavior regressions failed before their fixes: two provisional rollback cases, personal-template stroke-count isolation, contextual winner preservation, and learned abbreviations with unrelated context. The last case caught and narrowed the initial overly broad context filter. The final optimized workspace passed **240 tests, zero failed, three ignored**. Android JVM passed **1039 tests with zero failures/errors/skips**; device/JNI tests compiled; Lint reported **zero errors, 202 warnings and two hints**. A pre-existing Rust example warning for `unused variable: rest` remains. Builds reuse generated dictionaries rather than claiming newly generated data.

JNI 新增的学习撤销与个人笔数隔离已通过，整个 `JniTest` **17 项通过、2 项按原配置跳过、0 失败**。首次个人纠正测试固定选择索引 1，误选了生产候选在“十”后附加的表情；改为按文字定位“土”后通过，未修改生产排序来迎合测试。

The new JNI rollback and personal stroke-count isolation cases passed; the complete `JniTest` run passed **17 tests, with two existing conditional skips and zero failures**. The first correction fixture incorrectly selected fixed index 1, which was an emoji inserted after 十. Selecting 土 by its visible text fixed the fixture without changing production ordering.

最新 arm64-v8a／x86_64 原生库及调试／设备测试 APK 构建通过，安装至专用 Android 14 模拟器后，学习与手写设备测试 **2 项通过（8.686 秒）**。真实窗口中的生产键盘通过触摸事件输入、选词与退格，验证被否定的一次词对不促成造词、保留两次才学成；普通退格仍只删除最后一个字。手写复验覆盖包内识别、连写、同区叠写、跨字退笔／无障碍重做与确认上屏。两张全屏截图均已检查：学习最终文字为“日期时间”；叠写只有一层键盘，确认前编辑器为空、显示“叠写 2/4”和“十十”候选。此为 Activity 窗口生产 View 验收，不等同于跨应用正式 IME 叠写矩阵。

Both arm64-v8a/x86_64 native cores and debug/device-test APKs built successfully. Installed on the dedicated Android 14 emulator, learning and handwriting acceptance passed **two tests (8.686 seconds)**. Production keyboard Views in a real window receive touch events, selections and backspace: a rejected pair contributes no phrase evidence, two retained pairs promote it, and ordinary backspace still removes only the final character. Handwriting covers packaged recognition, line/overlay modes, cross-character undo/accessibility redo and confirmed insertion. Both full-screen images were inspected: 日期时间 in the learning editor; one overlay keyboard, an empty editor before confirmation, “叠写 2/4” and 十十 candidates. This Activity-window acceptance is not a cross-application overlay matrix in the actual IME service.

正式 IME 服务窗口冒烟 **31 项通过、0 失败、崩溃日志 0**，覆盖全拼、双拼、九键、五笔、组合编辑、辅助码、拆字和模拟语音文本后的删除。安装包与本地调试 APK 的 SHA-256 均为 `1383ebc5f191fc81015cc5bec7d60548328f1566a39b77da5c226760b27e43cc`。额外启动后的首张截图落在 Activity 切换动画中，不作为验收；窗口稳定后重新输入“你好”，全屏图已检查，单层正式键盘位于三键导航之上。脚本测量不等于真人输入速度，模拟语音文字也不衡量识别准确率。

The actual IME service passed **31 smoke checks, zero failed, zero crash-log matches**, covering input schemes, composition editing, shape/structure lookup and deletion after simulated speech text. The installed APK matches the local debug APK's SHA-256 recorded above. An additional initial screenshot caught the Activity transition and was excluded; after the window settled, a new 你好 input and full-screen image confirmed one service keyboard above three-button navigation. Script timings do not measure human typing speed, and simulated speech text does not measure recognition accuracy.

复核收尾：补齐新学习设备测试的文件／缓存目录隔离，在临时上下文出现前将云词进程单例绑定持久应用上下文，并将键盘资源释放移至 Activity 存活期间的 UI 线程 `finally`。仅重建设备测试包后，学习／手写两项组合复验再次 **2 项通过（8.223 秒）**；生产算法与 APK 未变。失败路径资源释放经控制流复核，未故意注入设备断言失败。

Review follow-up: the new learning fixture now isolates direct file/cache access, initializes the process-wide cloud-word singleton with the durable application context before temporary contexts, and disposes keyboard resources in a UI-thread `finally` while the Activity remains alive. Rebuilding only the device-test APK and rerunning both cases again passed **two tests (8.223 seconds)**; production algorithms and APK were unchanged. Failure-path disposal was reviewed through control flow, without an injected failing device assertion.

**2026-10-20 按停顿分字的同区叠写 / Pause-delimited same-region overlay**

模式键循环「单字 → 连写 → 叠写」。叠写保留相同区域覆盖的原始笔迹，按既有判字时长在下一次落笔时建立字界；最多四字、每字 64 笔，只在明确确认时上屏。原生通过明确笔画结束索引逐字识别并复用上文／词库排序，同一分界用于异步结果、回退、退笔／重做及个人纠正，不用平移笔迹模拟留白。字内超过判字时长的停顿会拆字；尚不支持无停顿自动分界、跨字连笔或返回补前字。本轮没有训练模型或开展独立真人准确率评测。

The mode key cycles through Single, Line and Overlay. Overlay retains original ink at the same coordinates and establishes character boundaries on the next pen-down after the existing character-pause duration. Up to four characters and 64 strokes per character wait for explicit confirmation. Native recognition consumes explicit stroke-end indices, reuses context/lexicon ranking, and preserves the same spans for worker results, fallback, undo/redo and personal correction. Ink is not translated to simulate whitespace. Pausing beyond the threshold inside a character splits it. Uninterrupted boundary inference, cross-character strokes and adding strokes to earlier characters remain unsupported. No model training or independent real-writer accuracy evaluation was performed.

Android 全量 **1039 项通过**，0 failures／errors／skipped；JVM 新增 13 项覆盖模式、分组、取消、上限与异步边界快照。Rust 优化构建全套 **235 项通过、0 失败、3 项忽略**；JNI **15 项通过、2 项按原配置跳过**，包括正式包内模型的明确字组往返与非法分界拒绝。Lint **0 errors、202 warnings、2 hints**。首次调试版 Rust 全套在性能测试处达到任务时限而停止；组合构建也曾在词库重建处超时，后复用已生成词库继续。新增 JNI 测试曾因缺少断言导入编译失败，补齐后通过。这些中断及失败不计作通过。

All **1039 Android tests passed**, with zero failures/errors/skips; 13 JVM regressions cover modes, grouping, cancellation, limits and immutable asynchronous boundaries. The optimized Rust workspace passed **235 tests**, with zero failures and three ignored. JNI passed **15 tests**, with two existing conditional skips, including explicit-group round trips through shipped models and invalid-boundary rejection. Lint reported **0 errors, 202 warnings and 2 hints**. The initial debug Rust suite reached its task time limit during performance tests, and one combined build timed out rebuilding dictionaries. Subsequent builds reused the generated dictionaries. Missing assertion imports initially prevented the new JNI test from compiling; it passed after correction. Interrupted or failed runs are not counted as passing.

Android 14 模拟器首轮手写设备测试 **1 项通过**，覆盖同坐标「十十」候选、确认前不写入编辑器、跨字退笔／无障碍重做及确认上屏。首张全屏截图发现测试键盘与系统输入法并存，因此不作为干净界面验收；测试随后隔离窗口，最终复验结果见后续记录。设备脚本笔迹是手绘几何回放，不是真人识别准确率，也未完成实体手机或完整人工 TalkBack 验收。

The first Android 14 device test passed **one test**, covering same-coordinate 十十, no editor commit before confirmation, cross-character undo/accessibility redo and confirmed insertion. Its full-screen image exposed both the fixture keyboard and system IME, so it is not accepted as clean UI evidence. The fixture window was subsequently isolated; final acceptance is recorded below. Scripted geometric strokes do not measure real-writer accuracy. Physical-device and complete manual TalkBack acceptance remain outstanding.

最终包复验：修正叠写候选仍标“连写”的问题，并让测试窗口隔离系统输入法后，最新安装包设备测试再次 **1 项通过（9.982 秒）**。全屏截图已检查，只有一层键盘，显示“叠写 2/4 · 字间停顿800毫秒”与“十十”候选。生产手写 View 在测试 Activity 窗口运行，笔画由 MotionEvent 回放；确认前原生 EditText 为空，退笔／无障碍重做保留 `[2,4]` 字界，明确确认后上屏“十十”。最终源码 Rust 全套再次通过 **235 项、3 项忽略**；两种 ABI APK、Lint 与 JNI 检查完成。此验收不是正式 IME 服务窗口的跨应用叠写矩阵。

Final-package acceptance: after correcting overlay candidates mislabeled as line writing and isolating the fixture window from the system IME, the installed package again passed **one device test (9.982 seconds)**. The full-screen image was inspected: one keyboard, the overlay count/pause hint and 十十 candidates are visible. The production handwriting View runs in a test Activity, driven by MotionEvent replay. The real EditText stays empty before confirmation; undo/accessibility redo retain `[2,4]` boundaries; explicit confirmation inserts 十十. The final Rust workspace again passed **235 tests, with three ignored**; both ABI packages, Lint and JNI checks completed. This is not a cross-application overlay matrix in the actual IME service window.

**2026-10-20 恢复操作与无障碍等价动作 / Recovery and accessible action parity**

清空恢复绑定原输入会话、连接、编辑器版本、删除后的空选区及有限前后文，3 秒内只能执行一次。光标移动、选区变化、应用改写、输入框重启、隐藏或到期后不再写入旧文本。删除返回失败或无法复核时不提供撤销；原选区在确认原文插回后恢复，包括反向选区。恢复文本最多 2000 个 UTF-16 单元，清空边界保留完整代理对。无法提供新鲜选区的受限编辑器仍可清空，但不提供无法验证的恢复；局部前后文比较不等于整篇文档版本或跨进程原子事务。

Clear recovery binds the original input session, connection, editor version, resulting empty selection and bounded surrounding text. It is single-use within three seconds and rejects cursor/selection conflicts, host rewrites, restarts, hidden input or expiry. Failed or unverifiable deletion offers no recovery. Verified reinsertion restores original selection endpoints, including reversed selections. Recovery retains at most 2000 UTF-16 units and preserves surrogate pairs at the clear boundary. Restricted editors without fresh selection data can still clear text but cannot offer unverifiable recovery. Local context validation is neither full-document revision tracking nor an atomic cross-process transaction.

手写模式键在可重做且没有当前落笔时，提供具名无障碍长按动作“重做上一笔”；复用原来的笔画和识别更新流程，不切换单字／连写模式。过期动作不能重复恢复或打断当前笔画。参考源码只用于交互思路，独立实现，没有新增设置或工具栏。

The handwriting mode key exposes a named “Redo last stroke” accessibility long-click only when redo is available and no stroke is active. It reuses the existing geometry/recognition path without changing writing mode. Stale actions cannot repeat recovery or interrupt live ink. Source comparisons informed independently written interaction improvements without adding settings or toolbar rows.

本轮新增 **13 项 JVM 回归**；无障碍重做、五项恢复冲突及大上限代理对边界均先复现失败再修复。Android 全量 **1026 项**通过，0 failures／errors／skipped；Lint **0 errors、203 warnings、2 hints**，调试与设备测试 APK 构建通过。专用 Android 14 模拟器中，原生编辑器恢复与手写识别／无障碍重做 **2 项通过**，手写单独重跑 **1 项通过**；设备 View 的连写截图已检查。设备测试修复了 applicationContext 绕过偏好隔离的问题，避免上轮连写状态污染下轮；此前模式切换失败保留为历史记录。无障碍动作经平台节点调用验证，未运行完整 TalkBack 人工流程，也未做实体手机验收。

This round adds **13 JVM regressions**. Accessible redo, five recovery conflicts and the oversized-limit surrogate case failed before their fixes. All **1026 Android tests** passed with zero failures/errors/skips. Lint reported **0 errors, 203 warnings and 2 hints**; debug and device-test APKs built. A dedicated Android 14 emulator passed **two device tests** for real-editor recovery and packaged handwriting recognition/accessibility redo; a separate handwriting rerun also passed. Its on-device View image was inspected. Preference isolation now includes applicationContext so one run's line mode cannot contaminate the next. Earlier mode-switch failures remain historical evidence. Platform accessibility-node invocation was tested; a complete manual TalkBack flow and physical-device acceptance remain outstanding.

导航补验：健康启动的专用模拟器上 `NavigationDeviceTest` **1 项通过**，覆盖三键／手势、横屏／竖屏、各两次显示共八组。此记录补齐此前被系统弹窗阻挡的矩阵；不删除旧失败，也不代表实体手机兼容矩阵完成。

Navigation follow-up: **one device test passed** all eight combinations of three-button/gesture navigation, portrait/landscape and two show cycles. This completes the previously blocked emulator matrix while retaining earlier failures; physical-device compatibility remains unverified.

最终包复验：安装上述全量构建产出的最新调试 APK 后，组合运行手写识别／无障碍重做、原生编辑器清空恢复、导航矩阵，**3 项全部通过**（38.902 秒）。导航覆盖上述八组；此结果确认最终源码对应安装包的这三项设备行为，不扩大为真人识别准确率或实体手机兼容性结论。

Final-package acceptance: after installing the latest debug APK from the full build above, the combined run passed **all three device tests** for packaged handwriting recognition/accessibility redo, real-editor clear recovery and the eight-case navigation matrix (**38.902 seconds**). This verifies those behaviors in the installed final build; it establishes neither real-writer recognition accuracy nor physical-device compatibility.

**2026-10-20 跨页面剪贴建议 / Clipboard suggestions across pages**

复制或剪切后切到新输入框，键盘重新显示 30 秒内的当前剪贴板文字，点击才粘贴；建议显示 10 秒后收起。再次复制相同文字也会重新提示，同一输入框重启不会重新弹出已粘贴的建议。恢复显示不重复记录历史、不恢复已删除的历史，也不重复同步；关闭历史记录仍可使用。保留验证码只粘贴代码、密码／无痕框及敏感剪贴内容的过滤。

After copying or cutting, opening a new editor restores the current clipboard suggestion if copied within 30 seconds. Tapping pastes; the suggestion hides after 10 seconds. Copying identical text renews the suggestion, while restarting the same editor does not re-offer an already pasted item. Restoring the suggestion neither records history again, resurrects deleted history nor repeats synchronization. It also works with recording disabled and retains code-only OTP paste and private/sensitive-content filtering.

新增九项 JVM 回归，其中六项在保留旧去重分支时复现失败；校正测试剪贴板时间戳，使其与 Android 的系统日期语义一致。剪贴板专项 **31 项**、Android 全量 **1013 项**通过，0 failures／errors／skipped。`lintDebug` **0 errors、203 warnings、2 hints**，调试 APK 与设备测试 APK 构建通过。首次设备测试编译因误用编辑器菜单接口失败，改用 `EditText.onTextContextMenuItem` 后构建通过。

Nine JVM regressions were added; six fail with the previous duplicate guard retained. The test clipboard timestamps were corrected to Android wall-clock semantics. All **31 focused clipboard tests** and **1013 Android tests** passed with zero failures/errors/skips. Lint reported **0 errors, 203 warnings and 2 hints**. The debug and device-test APKs built after correcting the device fixture's editor-menu API to `EditText.onTextContextMenuItem`.

专用 Android 14 模拟器（1080 × 2400、420dpi）运行 `ClipboardSuggestionDeviceTest` **1 项通过**，覆盖复制和剪切两条路径：真实编辑器执行系统菜单动作，移除源页面并等待输入法隐藏，再聚焦新页面输入框；通过系统触摸注入点击正式输入法窗口里的建议，核对包含首尾空格、换行、表情的完整文字，剪切后源框为空。全屏 `clipboard-suggestion-device.png` 已检查。测试期间关闭历史记录，并在结束后恢复相关偏好与系统设置。此项为同一测试 Activity 内的跨页面验收，未覆盖不同应用或实体手机。

On a dedicated Android 14 emulator (1080 × 2400, 420dpi), **one device test passed**, covering both copy and cut. Real editor context-menu actions populate the clipboard; the source page is removed and the IME hides before the new page's field is focused. Screen-injected touch taps the suggestion in the actual IME service window. Exact text, including surrounding spaces, newlines and emoji, is verified, as is the empty cut source. The full-screen `clipboard-suggestion-device.png` was inspected. Recording is disabled during the test and relevant preferences/system settings are restored afterward. This acceptance navigates pages within one test Activity; cross-application and physical-device checks remain outstanding.

**2026-10-20 Android 模拟器界面验收 / Android emulator UI acceptance**

新增 `VoiceResultsDeviceTest`：正式面板在 Android Activity 窗口内显示三组自写文字样本，系统触摸注入完成质量排序后的左右切换、点击全文、明确确认对应文字上屏、返回与取消。完整设备测试独立运行 **1 项通过**，实际屏幕的卡片和全文 PNG 已检查；不是离屏 Canvas 截图。使用模拟识别结果，不录音、不联网，不代表语音准确率。手写设备测试首轮 **1 项通过**，使用包内原生识别器识别「十」、撤销／重做、连续保留「十十」并确认上屏；其截图来自设备上的实际 View。基础输入设备冒烟 **31 项通过、崩溃记录 0**。

`VoiceResultsDeviceTest` mounts the production panel in an Android Activity window. Screen-injected touch selects quality-ranked local fixture transcripts, opens full text, confirms the matching text in the real editor, returns and cancels. Its standalone device run passed **one test**, with full-screen card and preview PNGs inspected. Fixture transcripts do not measure recording or recognition accuracy. The first handwriting device run passed **one test**, using the packaged native recognizer for 十, undo/redo, retained 十十 and confirmation; that image captures the on-device View. All **31 basic input device smoke checks** passed with zero crash records.

首次手机模拟器冷启动出现系统无响应弹窗和 Android 系统进程故障；混合重跑的语音手势被系统弹窗拦截、手写模式切换及导航检查失败，不计为通过。独立重跑语音完成后通过。设备验收补齐全屏截图回退，并修正导航测试对 Android `frame=`／旧 `mFrame=` 的解析；暗色超时快照也改为按真实超时常量推进时间并断言状态。参考应用的源码差距已另行整理；尚未落地的建议不算产品功能。

Cold phone-emulator startup produced System UI dialogs and an Android system-process failure. A combined rerun failed voice input delivery, handwriting mode switching and navigation checks; those runs are not counted as passing. The later standalone voice rerun passed. Device tooling now includes a full-screen capture fallback and recognizes both Android window frame formats. The dark timeout snapshot uses the actual timeout constant and asserts its state. Source-research recommendations remain separate from implemented behavior.

导航独立重跑仍失败：能读到导航栏 `Rect(0, 2274–1080, 2400)`，但系统无响应弹窗遮挡应用，无法取得输入法按键节点。真实竖屏截图可见底行在三键导航上方，但两种导航模式与横竖屏的完整自动矩阵尚未通过。混合重跑的手写测试在模式切换断言处失败；仅首轮成功可作为已完成证据，不宣称重复稳定性。

The standalone navigation rerun still failed: the navigation rectangle was readable, but a System UI non-response dialog blocked the app and no IME key node was available. A real portrait capture shows the bottom row above three-button navigation; the complete mode/rotation matrix remains unverified. The combined handwriting rerun failed at its mode-switch assertion. Only its successful first run is counted; repeated stability is not claimed.

源码对照后的优先项：补齐语音卡片与手写重做的无障碍操作；清空撤销绑定原输入位置与上下文；展开候选保留浏览位置；语音全文保留错误／超时状态；统一关闭动画时的语音反馈。上述均为后续工作，未在本轮实现或测量收益。只借鉴交互思路，不复制外部实现。

Source comparison prioritizes accessible voice cards and handwriting redo, position/context-bound clear recovery, candidate browsing continuity, visible error/timeout status in full transcripts, and consistent reduced motion. These remain follow-up work, with no implementation or measured benefit claimed here. Interaction ideas inform independently written code.

**2026-10-20 手写触屏边界 / Handwriting touch boundaries**

超长单笔达到原生每笔 4096 点上限后疏化历史采样，保留首尾、采样顺序及对应的显示元数据，并继续接收后续运动；上限以内的原始点列不变。已完成笔画的撤销／重做保留数组身份与重绘结果，取消新笔不改已有笔迹。当前笔画尚未抬起时，候选与手写确认不能提前提交上一轮识别的半个字。系统取消当前笔画后，为保留的已完成笔迹恢复完整停笔等待；计时器、下一次落笔及设置刷新共用取消事件的时间基准，取消后立即续写不会提前提交。连写和关闭自动上屏仍不安排自动确认，内部面板复位不安排新计时。

Overlong strokes thin historical samples only at the native 4096-point ceiling, retaining both ends, order and matching display metadata while accepting further motion. Geometry within the limit remains unchanged. Undo/redo preserve completed geometry identity and rendering, and canceling new ink preserves finished strokes. Candidate and handwriting confirmation wait for the live stroke to finish. System touch cancellation restarts the retained ink's full pause. The timer, next pen-down and settings refresh share the cancellation event timestamp, so immediately resumed ink is not committed prematurely. Continuous writing and manual confirmation do not schedule automatic commits, and internal panel resets do not start a timer.

本轮新增六项回归，长笔画及四项触屏／时间状态判据均先复现失败再修复；恰好 4096 点的笔画逐点保留。手写专项 **56 项**、Android 全量 **1004 项**通过（0 failures、0 errors、0 skipped）。`lintDebug` 为 **0 errors、203 warnings、2 hints**，调试 APK 构建通过；两种 ABI 的打包原生库均与当前 stripped 构建产物一致。未做本轮手写设备验收，没有改模型或开展真人准确率评测。

Six regressions were added: the overlong-stroke case and four touch/timing cases failed before their fixes, while the exact 4096-point boundary retains every sample. All **56 focused handwriting tests** and **1004 Android tests** passed with zero failures/errors/skips. Lint reported **0 errors, 203 warnings and 2 hints**, and the debug APK built. Packaged native libraries for both ABIs match the current stripped build outputs. No device handwriting acceptance was performed this round; no model changed and no real-writer accuracy was measured.

**2026-10-20 三结果语音卡片 / Three voice result cards**

最多三张等宽卡片并排显示，左右滑动切换选中结果并渐变高亮，点按只查看完整文字，底部明确确认才上屏。全文支持上下滚动和左右换结果，短面板压缩状态行以保留正文。质量排序仅使用同组评测的相对分数；未知时回退主引擎。用户交互后按引擎 ID 保留选择；按下确认后固定当时的结果与会话，其他引擎完成或新会话开始不能改选。已覆盖取消、重录、迟到回调、待识别／失败结果不可确认及语言切换。

Android 全量 **998 项**通过（0 failures、0 errors、0 skipped），`lintDebug` **0 errors、203 warnings**，调试 APK 构建通过；原生渲染的亮／暗卡片及全文截图已检查。新增确认竞态和短面板正文测试均先失败后修复。模拟器基础输入冒烟 **31 项**通过、无崩溃；该冒烟不验证真实多引擎语音准确率，三卡片手势由 Robolectric 真实 MotionEvent 回放覆盖。未在实体手机验收。

Up to three equal-width cards show independent transcripts, with horizontal selection and animated emphasis. Tapping opens full text; only explicit confirmation commits. Full text scrolls vertically and switches horizontally. Short panels compact metadata to retain transcript space. Ranking uses shared-benchmark relative quality, with the primary engine as the unscored fallback. Engine IDs preserve deliberate selections, and pressing commit captures the result and session so later completions cannot change the choice. Pending/failed results cannot be committed.

All **998 Android tests** passed, with zero failures/errors/skips. Lint reported **zero errors and 203 warnings**, and the debug APK built. Light/dark card and expanded-text renders were inspected. Confirmation-race and short-panel regressions failed before their fixes. All **31 emulator input smoke checks** passed without crashes; that smoke run does not measure real multi-engine accuracy. Card gestures use actual MotionEvent replay under Robolectric. Physical-device acceptance remains outstanding.

**2026-10-20 手写基线 / Handwriting baseline**

现有离线评测已可复现：同一 Apple M4 主机上，100 个三级合成变形字的生产融合识别 Top-1 为 84%、Top-5 为 94%，平均 25.16 ms、p95 31.64 ms。字形来源与训练重叠，这不是独立真人笔迹准确率，也不是 Android 时延。横向标准字形近距留白测试中，两字精确分组 98/100、四字 96/100；较宽留白均为 100/100。已定位的失败包括窄字「卜」并入相邻字，单字和宽字反例需与修复一起复测。尚无来源与授权均核实的真人测试集，因此未使用来源不明笔迹训练或发布新模型。

The offline baseline is reproducible. On an Apple M4, production fusion recognized 100 level-three synthetic distortions at 84% top-1 and 94% top-5, with mean 25.16 ms and p95 31.64 ms. Glyph sources overlap training; these are neither independent real-writer accuracy nor Android latency. Close-gap canonical rows grouped exactly in 98/100 two-character and 96/100 four-character cases; wider gaps reached 100/100 in both cases. Narrow-character merges are reproducible. No new model was trained or published from handwriting with unresolved provenance or permission.

分组修复增加一个有界的窄字切分候选，只允许细分原组、不得移动已有字界；每个新片段须有强模板匹配，被拆原组若有强整字模板或个人纠正匹配则保留整体。成对固定合成评测中，近距两字／四字整行首选从 95%／90% 到 100%／100%（各 40 行），较宽留白仍为 100%，单字 Top-1／Top-5 仍为 84%／94%。最终近距两字／四字平均 44.13／79.97 ms；并行工作负载影响计时，性能是否回退仍未确定。内核回归 170 项通过、2 项忽略，覆盖窄字、完整宽字、字界保护、个人纠正与四字上限。设备逐笔回放因前台应用切换中断，不计为通过。

The bounded fix tests a narrower alternative that may refine original groups but cannot move existing boundaries. Every new piece requires a strong template match; strong whole-glyph or personal-correction evidence protects the original group. Paired fixed synthetic evaluation improves close-gap two-/four-character exact line top-1 from 95%/90% to 100%/100% over 40 rows each. Wider-gap and single-character scores remain unchanged. Final close-gap means are 44.13/79.97 ms; concurrent workloads leave performance regression unresolved. Core regression passed 170 tests with two ignored, covering narrow and wide glyphs, boundaries, personal corrections and the four-character limit. Device stroke replay was interrupted by a foreground application change and is not counted as passed.

- 2026-10-20 Android 大小写与连续输入：字母会结束 Shift 的双击窗口，`Shift → A → Shift` 不再误锁定；上滑改成数字或系统取消触摸，在成功撤回字母后恢复单次大写，标点不消耗单次态。中英切换统一清理旧大写状态，覆盖软键、工具栏和 Ctrl+Space；移动正文光标重算自动大写，同位置回报保留用户手动关闭大写的选择。双拼组合中 Shift 可临时输入一个大写字母；英文上滑或长按撇号继续单词组合（`don't`），密码、网址、邮件与关闭联想的字段保持直接输入。
  - Android 全量 **976 项**通过（0 failures、0 errors、0 skipped），`lintDebug` 0 errors（203 warnings、2 hints），调试 APK 构建通过。新增 18 项覆盖上述输入行为及撤回、空格／回车／候选确认；保留双指连打与长按回归。原生双拼专项 **11 项**通过，1 项真实词库检查按默认配置忽略。
  - 模拟器（1080 × 2400、420dpi）真实触摸：长按 Shift 后 `QWER` 保持锁定；切中文后 `ni` 留在拼音组合；软键输入 `don`、上滑 J 输入撇号、再输入 `t`，整词保持组合，空格上屏 `don't `。截图已检查。`tools/device-smoke.sh` **31 项**全部通过，无崩溃。首次运行因测试应用记住英文选择而中止；恢复中文起始状态后重跑通过。冒烟脚本默认测试应用从中文开始；若该应用记住了英文选择，须先切回中文再运行。未在实体手机上验收。

Android capitalization and continuous typing, 2026-10-20: letters end the Shift double-tap window; successful digit-swipe replacements and touch cancellations restore one-shot capitals. Punctuation preserves them. Language switches clear previous capitals; editor cursor moves recheck automatic capitals while preserving manually enabled capitals, and unchanged selection echoes preserve manual lowercase. Double-pinyin Shift accepts a temporary capital during composition, and an English apostrophe continues the word. All **976 Android tests** passed, lint reported **0 errors** (203 warnings and 2 hints), and the debug APK built successfully. Native double-pinyin checks passed **11 tests**, with one real-dictionary check ignored by default. Emulator touch checks verified caps lock, Chinese composition after switching, and swipe-entered `don't`. All **31 device smoke checks** passed with no crashes after restoring the script's expected Chinese starting mode; physical-device acceptance remains outstanding.

- 2026-10-18 撤掉面板搜索、修「虚拟按键偶尔挡住键盘」：用户反馈两件事——面板里的迷你字母键盘「很难看、不需要」，以及切应用后键盘底部偶尔被系统虚拟按键挡住、要再点一下键（或收起再打开）才回来。
  - **面板搜索整套撤掉**：表情 / 颜文字 / 剪贴板 / 常用语面板里的搜索行与三行字母键（`PanelKeys`）、查询状态（`SearchQuery`）、目录的名称与拼音索引（`ExpressionSearch`）、`ExpressionCatalog.searchRows`、控制器 `lookupText`、实体键盘的 `hardwareToPanel` 路由、`{open-search}` 调试入口与相关用例、快照一并删除；面板回到「表头 + 网格」两段。撤的时候按 beta.25 之后的版本改，不是回退到功能之前：`8d68b1a` 修过的常用语表头图标与动作逐位对应（`headerIcons`）、卡片动作不误盖当前剪贴板卡片（`id >= 0`）、列表变短后滚动位置夹回范围都留着——第一次回退到功能之前的版本把这三处一起丢了，靠跑用例发现（`thePhraseHeaderPlusAddsTheClipboardText` 直接编译不过）。Mac 表情库窗口里的搜索框没有动：那是独立窗口里的真输入框，不是面板里的小键盘；要一并撤掉说一声。
  - **导航栏的根因**：拿到过渡值就重排。切应用的动画、窗口进出场、系统重算边衬时，`navigationBars` 会先报一次 0；键盘据此把底部留白收掉，之后没有事件再把它纠回来，于是一直压到下次重新显示（用户说的「点一下收缩键 / 设置键才恢复」正是重新走一遍显示流程）。三处改动：① 还没读到任何边衬时先按系统资源 `navigation_bar_height` 留出底边（三键导航；`navigation_mode == 2` 手势导航不预留，系统只画手势条），第一帧的高度里就有这条；② 把某条边整个收成 0 的读数不当真，记下来隔 180 ms 复核，两次一致才收下，中间来的其它读数（变大的、变小的但不归零的、横屏换到侧边的）一律立刻生效——多留一条空白只是不好看，少留就把键压在系统按键下面；③ 显示后在 120 / 350 / 700 ms 各再量一次，切应用动画走完、窗口真正定位好都在 `onShown` 那一瞬之后。复核**按时间算不是按次数**：那一下 0 常常连着几次重读都在（一次布局会触发好几遍重读），按「第二份相同读数」认等于没认。
  - Android 全量 **958 项** 0 failures（beta.25 为 977 项，减少的是撤掉的搜索用例），`lintDebug` 0 errors。导航相关用例改写并新增 6 项（`NavigationInsetReliabilityTest` 15 项、`NavigationClearanceTest` 6 项）：过场中的 0 不收起留白、真的收起时复核后照样让开、第一帧就按系统资源留出底边、三键与手势导航的预留在纯函数上对得上、按次数不算复核。三条先写出失败的判据再修，其中两次失败暴露的是复核逻辑本身的问题（第一份 0 被 `held == null` 分支直接生效；按次数复核等于没复核）。`HandwritingAreaModeTest.systemBarChangesResizeTheAreaWithoutKeepingOldClearance` 跟着改成「归零那一下先不当真，复核后再收掉」，它原来断言的是立刻收掉。
  - 模拟器冒烟（`emulator-5554`，1080 × 2400、420dpi、三键导航、`navigation_mode=0`）：`dumpsys window` 里输入法窗口 `frame=[0,1366][1080,2400]`、导航栏 `frame=[0,2274][1080,2400]`——输入法窗口确实一直铺到屏幕底边，导航栏压在它上面 126，与「底边要自己让」的判断一致。连做 5 轮「回桌面 → 开设置 → 回到输入法自带的 SmokeActivity → 点输入框」，每轮截屏检查导航栏那一条（y ≥ 2274）有没有键面白像素，5 轮全 0；底行「符 / 123 / 空格 / 中英 / 回车」始终落在 2274 之上。同一个脚本也看了面板：表情面板只剩「网格 + 底行」，剪贴板面板表头三个图标（图钉 / 铅笔 / 垃圾桶）与截图位置一致、没有放大镜，都没有搜索行。没有在实体手机上验收——模拟器复现不出用户说的那种状态，改动是按根因与用例写的；`tools/device-smoke.sh` 未跑。
- 2026-10-17 beta.24 后的两处面板缺陷与审查补漏：用户报告「光标编辑 UI 错位、表情框点击不能搜索」，两条都在模拟器上复现并修好。
  - 光标面板按 02 §9 改成 5 × 4、每格一键（原先左右键跨两行，压住底行的撤销、重做：点撤销实际移动光标）。新增 `PanelKeysTest` 逐键核对行高与两两不重叠；快照 `keyboard_cursor_{light,dark}.png` 人工复核四行干净、无叠字。
  - 表情面板搜索（2026-10-18 已整套撤掉，见上一条）：根因是面板盖住主键盘后没有地方打字母。新增 `PanelKeys`（三行 QWERTY，退格长按连删），点搜索行铺开；剪贴板 / 常用语面板同样接入。模拟器实测：点搜索行铺开字母板，点出 `weixiao`，结果缩到 11 项且首位「微笑」，点首位把 ☺️ 上屏；返回键退出搜索后行上的查询被清掉、网格回到全部表情；剪贴板面板点字母 `q` 进查询、正文不动；实体键盘（`adb input keyevent`）打 `wei` 同样进查询。
  - 顺带修掉审查发现的问题（均由同一提交引入或在此前搜索功能里暴露）：退出搜索 / 换分类 / 重开面板后搜索行仍显示旧查询；查询空着时空格、`，。` 会落进正文（现在归搜索框，标点也进查询）；搜索中上滑 / 长按改写会把正文里已上屏的字撤掉（改写现在只改查询）；外接键盘在面板搜索开着时把字打进正文（现在先给查询，方向键与快捷键不抢）；手写超过 256 笔后多出的笔画画完即丢（现在到上限就不再收笔，并在注释里写明内核上限）。
  - 引擎：字形筛选后按标点不再刷新候选（返回 `false` 表示界面仍持有候选表，刷新会让界面提交另一个字：`shi`hs` 后按逗号原先打出「是」而不是「时」），改为结束筛选但保留候选与次序；以词取字只用于有读音键的拼音词，日期 `2026-10-17`、原样上屏的 `hello` 等不再被 `]` 截成最后一个字符。内核 54 项会话测试与全量 `cargo test -p weave-engine` 通过。
  - Mac：`swift build` 通过；`swift test`（带 Testing 插件路径）**225 项**全部通过。三处小修：双拼方案 id 形如 `shuangpin:xiaohe` 时展开网格也显示笔画键；名称搜索先折叠大小写再清洗（`WEI` 与 `wei` 命中数相同）；连写模式的手写提示不再许诺停笔自动上屏。
  - Android 全量 **977 项** 0 failures（新增面板字母板 4 项、搜索行清理 1 项、空格与标点 2 项、上滑改写 1 项、实体键盘 1 项）；`lintDebug` 0 errors。模拟器冒烟只做到上面列出的手工步骤，`tools/device-smoke.sh` 未跑；未在实体手机与 Mac 真实应用中验收。
- 2026-10-17 搭配模型上游当天再次原地替换：beta.24 首次推标签后，CI 在 `data/build.sh` 的校验处失败（`grammar source changed upstream`）。原因不是本地下载损坏——上游 `LTS/wanxiang-lts-zh-hans.gram` 在 09:57 UTC 被换掉（GitHub 记录的新摘要 `1849e5e4…`，398,505,004 字节；本地 10-16 那份是 `20f968df…`，398,391,340 字节），旧字节上游不再提供，本地那份只是恰好留在 `.ref` 里。处理：`GRAM_SHA` 换成新摘要，重新下载并重建词库（`grammar.wvz` 8.49 MB，二进制不同）。同一 1000 句评测集重跑：全拼 **75.7%** / 80.0% / 95.85%，小鹤 75.7% / 79.9% / 95.87%，九键 49.2% / 55.9% / 85.66%（10-16 版为 76.0% / 80.0% / 95.89%、76.0% / 79.9% / 95.91%、49.0% / 55.7% / 85.68%）——全拼与小鹤各低 0.3 个百分点、九键高 0.2，属同一水平；上游没有说明，也无从比对旧版。README、架构表与发布说明里的准确率随之改为新值，并注明哪些数字是更早的模型上测的、未随替换重测。
- 2026-10-17 beta.24 发布前的本地复核：内核全量 **222 项**通过（3 项忽略；按 CI 的 `--features weave-dict/pack` 跑为 **223 项**），Android 全量 **968 项** 0 failures、`lintDebug` 0 errors（205 条警告、2 条提示），桌面 JNI **40 项**（15 项按需跳过；本机首次跑 `:native-test:test` 报 `Downloader.sha256Of` 找不到一参数版本，`d7f7c6a` 给该方法加了带默认值的第二个参数，本机留着更早编译的测试类，`clean` 后重跑通过，源码无误），Mac 核心 **225 项**通过。两端各按发布路径打了一遍：Android `:app:assembleRelease` 成功（R8 与资源压缩、`lintVitalRelease` 通过，产物 45.3 MB，含 `shape.wvz`、有 `libonnxruntime.so`、无 `.onnx`；本机没有 `keystore.properties`，该产物是调试签名，正式签名由 CI 用仓库密钥完成）；Mac `build-app.sh --skip-tests` 成功（通用二进制、`WeaveText-0.1.0-beta.24.dmg` 40 MB）。顺带把此前仍链接旧搭配表的内核静态库按当前源码重建，Mac 核心检查是在这版静态库上跑的。正式产物以 CI 构建为准。
- 2026-10-17 两件「试过但没做」的事，都留了数字。
  - 停顿重排（pause rerank）：想的是停手时用更宽的一遍搜索把整句重新排一次。先看有没有可捡的：把整句束宽从 6 提到 24，同一套 1000 句评测集**一字未变**（首选 75.7%、前三 80.0%、字准确率 95.85% → 95.84%），单句耗时 324.8 ms → 1361.8 ms（约 4.2 倍）；把每个起点的候选池从 8 提到 24（束宽仍 6）同样一字未变（75.7% / 80.0% / 95.85%，306.5 ms），说明现在错的句子不是「搜得不够宽」漏掉的，加宽只是白花时间，所以没有做这项功能，也没有留下开关。常量已改回原值。
  - 滑行输入（glide typing，26 键）：先做路径解码再谈接不接。用真实 26 键几何合成路径来量——路径走的是穿过各键中心的样条（拐角会切角，不是直角折线），每两键之间 8 个采样点，落点偏差 ±12% 键宽、抖动 ±4% 键宽，25 个常用词（拼音与英文）各 5 条。两种解码都不够用：按「划过的格子」解码（同键连续采样合并）逐字命中 0/125——从一个键斜划到远处的键，中途穿过的键全被算成字母（nihao 解成 njihgfdsasdfyuio）；按「拐点」解码（重采样、五点平滑、按固定弧长窗口取转角局部极大值，窗口与阈值扫了 6°–18°、0.3–0.7 键宽）最好只有 59/125。天花板测量：目标词确实是「划过键序列」子序列的有 115/125（92%），丢的 10 条全是叠字母（zhongguo 的 gg——手指停一次，路径上只有一个拐弯）。真正的难点是剪枝：nihao 的 n、i、h、a、o 五个键路径都经过，niao 的四个键也在路径上，形状打分给不出差别，要靠「哪个是词」的语言模型来定；内核能对**给定**的一串字母排序，但不对两串不同的字母给出可比的分数，而拼音合法音节（409 个，取自 `tones.tsv`）两条都合法，所以这条路在本轮没有走到能用的精度，功能未接、设置里也没有开关，代码未保留。
- 2026-10-17 按应用记住中/英：Android 端控制器在 `onStartInput` 记下包名，输入框起用时先读记忆、没有再按类型给默认，用户切一次就写回；同一个输入框的重启（`restarting`）保留当前选择。Android 全量 **968 项** 0 failures（新增 7 项：控制器 4 项——切换后回到该应用还原、不允许中文的输入框既不读也不写、关掉记忆后与从前一致、同一输入框重启保留当前选择；设置存取 3 项——还原与不重复计数、上限丢最早且不停留取值、整体清除）。设置页新增「中/英」一组：开关（默认开）与清除项（显示已记住几个应用，有确认框）。Mac 端做同一件事：`EngineHost.toggleChinese` 记在 `clientDidActivate` 拿到的包标识名下，`activateServer` 时按记忆还原；设置窗口的「中英切换」组里有同一个开关与清除按钮，存储上限同样是 100 个应用、超出丢最早的（存成 JSON，取值与名单一起丢）。Mac 核心 **225 项** 通过（新增 3 项：`PerAppModeTests` 的还原与重排、上限丢最早、清除与关开关的行为）；UI／平台那一套在本机命令行工具链下跑不干净——不带 Testing 插件路径时 `TranslationWindowTests` 报三条「等超时」的假失败（本机已知现象），带上 `build-app.sh` 用的插件路径后测试进程直接以 signal 5 退出，单独跑 `TranslationWindowTests` 则 **8 项** 全通过，所以本轮不把 UI 套件的结果算作验收，改动本身不在这条路径上。Android 侧未在实体手机上验收，模拟器冒烟未跑。
- 2026-10-17 剪贴板与常用语搜索：表头新增放大镜（图标从右往左，排在最后，原有的加号／铅笔／固定／删除次序不动），搜索行为与表情面板同一套。Android 全量 **961 项** 0 failures（新增剪贴板搜索用例 3 项：字母过滤、回车取首条、删空后退格收行、数字查询、返回键先退搜索）。拼音读中文那条靠控制器新增的 `lookupText`：它只在内核未组合时使用，读完 `clear()`，不上屏、不学习；测试替身新增可配置的读音表来验证（用 `weixiao → 微笑` 找到含「微笑」的复制记录）。截图 `app/src/test/snapshots/style_clipboard_search_light.png` 人工复核：放大镜高亮、搜索行下划线查询、过滤后只剩命中卡片。未在实体手机上验收，模拟器冒烟未跑。
- 2026-10-17 表情按名称搜索：目录新增英文名与拼音（1898 个表情、132 个颜文字，缺名 0、缺拼音 0，文件 170 KB → 222 KB；中文名、文本、分类与肤色基底逐项未变）。Android 全量 **957 项** 0 failures（新增搜索用例 11 项：规则 6 项——拼音／隔音符／音节前缀、中英文与分类、多词与连写、打出表情本身与空查询、前缀计数，面板 5 项——字母搜索不上屏、英文与拼音搜索、回车取首个与空格只确定拼音、退格与返回退出、非表情分类不搜索）。Mac 端：`swift build` 通过；新增的 `ExpressionSearch` 与目录用例在本机跑不起来——命令行工具链缺 Swift Testing 宏（需要 Xcode 许可），改为把 `ExpressionSearch.swift` 逐字编译成可执行文件、拿真实 `catalog.json` 断言同一批规则（weixiao／weix／wei'xiao 首选「微笑」、dog 命中狗脸、mao 命中猫脸、动物命中猫脸、打出 😀 命中嘿嘿、zzzz 空结果、空查询保持目录顺序），全部通过。符号面板截图（`/tmp/weavetext-expression-android-ui/search_weixiao.png`）人工复核：搜索行、下划线拼音、结果网格与「找到 11 项」提示位置正确。未在实体手机和 Mac 真实应用中验收，模拟器冒烟未跑。
- 2026-10-16 搭配模型上游改了资产：本轮跑全量构建时 `data/build.sh` 在拉取上游 LTS 资产后校验失败（`grammar source changed upstream`），才发现上游会**原地替换**该文件——网址 `.../releases/download/LTS/wanxiang-lts-zh-hans.gram` 不带版本号，2026-10-16 上游把资产换成了新文件（GitHub 记录的摘要 `20f968df…`），仓库里固定的仍是 `71bc2ef5…`。这不是本地下载损坏：重下两次摘要一致，且与 GitHub API 给出的 asset digest 相同。处理：把 `GRAM_SHA` 更新为新摘要（`data/build.sh` 注释写明原因），用新模型重建搭配表（`grammar.wvz` 8.49 MB，旧版 8.49 MB；二进制不同，旧表无法从仓库或 `data/build` 复原）。同一 1000 句评测集重跑：全拼首选 **76.0%**、前三 80.0%、字准确率 95.89%（前一版 75.6% / 79.6% / 95.84%）；小鹤 76.0% / 79.9% / 95.91%（原 75.8% / 79.7% / 95.88%）；九键 49.0% / 55.7% / 85.68%（原 48.8% / 55.5% / 85.56%）；50 条错拼用例 98.0% / 100% / 99.07% 不变。上游没有发布这次替换的说明，只能按评测数字判断没有退化（三套方案都略升）。重建后回归：内核全量通过（库内 88、集成及辅助 **222 项** 通过，3 项忽略）；Android 全量 **946 项** 0 failures；模拟器冒烟 **31 项**全部通过，无崩溃。文档里的准确率数字同步改为新版；Mac 端的内核静态库仍是改动前的构建，发布前需按 `macos/scripts/build-app.sh` 重建（本次未重建，Mac 上仍是旧模型）。
- 2026-10-16 三条疑点取实证：内核新增 **2 项**（补全候选后的前 120 项逐项一致、逐页取整列与一次取全一致；内核全量 **137 项**通过，其中库内 88、集成 49）。Android 新增 **6 项**：成对符号 2 项（跨过已有右半边；有选区时替换选区）、纵向网格手势 4 项（轻点命中、纵向拖动滚动且不点选、斜着轻点仍命中、长按后抬手不点选）。成对符号与斜着轻点两条都先在修复前跑失败，确认钉的是真实缺陷；网格翻页那条注入「补全后交换前两项」的变异后失败，确认判据有效，结论是按排除处理——这条没有缺陷可修，只是拿到证据后不再列为疑点。
- 2026-10-16 候选栏与剪贴板面板三处缺陷：Android 全量 **940 项** 0 failures（新增 3 项）。三条用例都先在旧实现上跑失败、改回修复后才通过，确认钉的是真实缺陷：九键标点被英文三格截成 3 个；「只看当前剪贴板」时卡片上的「固定」是空操作；常用语表头图标与动作对调。剪贴板列表变短后的滚动越界只按代码路径核实（滚动由动画驱动，Robolectric 里不便驱动到该状态），未写用例，也未在模拟器上复现。未在实体手机上验收。
- 2026-10-16 重新选词不列「只上屏首字／末字」：这条只按代码路径核实。判据是控制器里既有的说明（重新选词整词替换选区）与 `onShapeStroke` 里同一条守卫；自动化用例需要测试替身支持 `reconvert` 并能在无输入的情况下产出候选，改测试替身的代价大于这条改动的风险，因此没有为它写用例，也未在设备上走一遍重选流程。
- 2026-10-16 手写连写成行与停笔时间拆分：Android 全量 **937 项** 0 failures（手写新增 5 项：连写停笔不上屏且攒住多字、单字仍按停笔上屏、画布笔画上限、关闭自动上屏后书写区边缘仍能按到键，以及改写 2 项描述旧行为的用例）；Mac UI／平台 **60 项**、核心 **221 项**通过。设备上以打包识别器跑真实笔画回放：写第一个「十」后停笔 1400 ms（超过 800 ms 的判字档）不上屏、笔画仍在画布，接着写第二个「十」，两个字一起识别出「十十」并点选上屏——修复前同一流程会在停笔时先上屏第一个字并清空画布。新增的「边缘落笔」用例先在旧实现上跑失败（关闭自动上屏时判字时间被设成无限值，书写区外 8dp 的容差永远成立，那个位置的键按不到），改回修复后才通过，确认它钉的是真实缺陷。模拟器冒烟 **31 项**全部通过，无崩溃。未在实体手机上验收，也未用真实书写评测识别率（本次只改交互，未改模型或排序）。
- 2026-10-16 候选置顶与 Mac 笔画键：内核的置顶策略本就完整，只是两端界面只能取消、不能设置；现在手机长按候选、Mac 右键候选都能置顶。Android 全量 **933 项** 0 failures，Mac UI／平台 **60 项**（新增笔画键 1 项）、核心 **221 项**通过。模拟器实测（清空用户词库后）：`ken` 原为「肯|啃|恳|ken|垦」，置顶「啃」后变「啃|肯|恳|ken|垦」，重新输入仍是「啃」在前；调试桥新增 `{policy:TEXT:MODE}` 用于这项验证。模拟器冒烟 **31 项**全部通过，无崩溃。Mac 的笔画键只验证了键位映射与显示条件（SwiftUI 按钮不落成 AppKit 视图，无法在测试里点击），未在 Mac 真实应用中验收。
- 2026-10-16 收纳袋动图预览：Android 新增设备测试 2 项（动图解成可播放动画、静态图仍是位图），在模拟器上通过；Mac 新增 2 项（缩略图保留全部帧、静态图单帧）。Android 全量 **933 项** 0 failures，Mac UI／平台 **59 项**、核心 **221 项**通过。模拟器实测：向收纳袋放入一张两帧红蓝 GIF，连拍六张截图，缩略图区域为红→红→蓝→蓝→红→红，确认在循环播放；修复前同一张图连拍始终是红色。模拟器冒烟 **31 项**全部通过，无崩溃。未在实体手机和 Mac 真实应用中验收。
- 2026-10-15 学过的词带进长句：内核新增长句继承与「整段近期选词不挡住继承」2 项（她们说／她们都／她们之间翻转、仍能选回「他们…」、单字不外溢、选回原词恢复、按空格接受过整句首选后仍能继承）、降频后再选恢复 1 项，以词取字补查取出字的读音；库内 **88 项**通过（1 项忽略），release 全量各集成套件 0 failures；Android 全量 **933 项** 0 failures。真实词库探查：放进五个不同长句，「她们」5/5、「誓言」4/4、「惟一」2/2、「登陆」「纪录」各 3/3 出现在首位；「她」0/4（单字按设计不外溢）。1000 句评测集分两半模拟：按 A 半纠正一遍后重打 A 半，首选整句正确 393/500 → 458/500；未见过的 B 半 373/500 → 373/500，逐句对比只有 2 句变化，都变对（「全是公交」→「全市公交」、「该是计划」→「该市计划」）。模拟器上以设备自带的用户词库实测：词库里原有「他们说」的整段近期选词记录时，点两次「她们」后 `tamenshuo` 从「他们说」变为「她们说」，再点回「他们」两次恢复；修复前同一份词库不让位。模拟器冒烟见下条；未在实体手机和 Mac 真实应用中验收。
- 2026-10-13 字形筛选：字形表格式与匹配 1 项、内核筛选 5 项（部件、笔画、首末字次序、九键、退格／选词／插入点／标点结束筛选），库内 85 项通过；Android 全量 **928 项** 0 failures、0 errors（新增笔画键与笔形显示）；Mac UI／平台 57 项、核心 220 项通过，其中真实词库检查在本机 `data/build` 下全部运行。模拟器冒烟 **29 项**全部通过（新增部件、末字对上的词、笔画键、九键笔画、筛选中退格 5 项），无崩溃；展开网格笔画行经截图人工复核（竖屏、横屏）。未在实体手机和 Mac 真实应用中验收。
- 2026-10-13 u 模式拆字查字：字形表查字贴合程度断言并入原有 1 项，内核新增 1 项（部件、贴合程度优先于常用程度、单独 u 照常解码、不插表情、不能移动插入点、双拼不受影响），库内 86 项通过；真实词库探查 `ukoukoukou`、`ukkk`、`uririri`、`uhs`、`umumu`、`us` 等结果合理。Android 全量 **928 项** 0 failures；Mac UI／平台 57 项、核心 **221 项**通过（新增真实词库 u 模式 1 项）。模拟器冒烟 **31 项**全部通过（新增部件查字、笔画查字 2 项），无崩溃；候选栏读音注释经截图人工复核。未在实体手机和 Mac 真实应用中验收。
- 2026-10-13 光标编辑与大写锁定：Android 全量 **932 项** 0 failures（新增长按锁定／切中文解锁、光标面板剪切与提示收起、选择模式左右扩选与 Shift 键回退、Shift 长按锁定共 4 项，更新 2 项）；光标面板截图人工复核。模拟器上实测：英文长按 Shift 后连打 `QWER` 保持大写，切中文后打出小写拼音；选择模式左移两次选中「时节」、剪切后移到开头粘贴得「时节你好」。模拟器冒烟 **31 项**两轮全部通过，无崩溃。未在实体手机上验收。
- 2026-10-13 悬浮键盘外框：Android 全量 **933 项** 0 failures（新增顶条／底边高度与关掉下方两角后不留底边 1 项），悬浮截图人工复核。模拟器上实测：开启悬浮后外框变薄，拖右下角能放大，点 X 停靠回底部。模拟器冒烟 **31 项**全部通过，无崩溃。未在实体手机上验收。
- 2026-10-13 组合串编辑：内核新增编辑检查 13 项（含按音节移动／删除与双拼音节），库内 80 项全部通过；Android 全量 **926 项** 0 failures、0 errors；Mac UI／平台 57 项、核心 218 项通过（含点击位置换算与单字网格绘制）。模拟器上用系统注入的 Ctrl+←、Ctrl+退格、Ctrl+Delete 逐步核对了插入点与删除结果。ARM64 调试包在模拟器上以真实词库运行冒烟 **24 项**全部通过（新增撤回选词、插入点补字、插入点前向删除、取首字、取末字 5 项），无崩溃；插入点绘制与「单字」筛选另经模拟器截图人工复核。未在实体手机和 Mac 真实应用中验收；Mac 点击拼音依赖各应用对字符位置的报告，尚未在真实应用里逐个试过。
- 推送前复查：补齐外接键盘英文联想词按空格确认后的显式分隔符，新增用例先复现失败再验证修复；Android 全量 **854 项**重跑通过，APK 重建、设备测试 Kotlin 编译通过。Mac **269 项**复查通过，预览包签名及实际资源自检再次通过。官方市场目录仍为 32 项。以下 853 项为复查前的开发验证快照。
- Android 静态检查补齐两处 API 30 窗口接口的版本守卫后，手写区域／导航避让／实体键盘 **56 项**针对检查、`lintDebug`、APK 构建和设备测试 Kotlin 编译再次通过；Lint 为 0 errors。版本守卫修复保持原有几何行为，不把静态检查等同于旧系统真机验收。
- Android 全量 **853 项**：0 failures、0 errors；调试 APK 构建与设备测试 Kotlin 编译通过。新增候选代次、同文字新会话、页外候选复制、注音跟随、英文空格、语音失败／取消等检查包含在内。
- Mac **269 项**：UI／平台 56 项、核心 213 项通过。独立草稿窗口能成为 key window，录音浮窗保持不能成为 key window；这不等于真实应用间焦点与麦克风权限已验证。最后文字菜单宽度调整另经编译和原生截图复核。
- ARM64 Android 与 Mac 原生内核已重建。APK 中 `libweave.so` 的 SHA-256 与本轮打包的 stripped 产物一致；Mac 重新链接后通过全部检查，预览包签名校验及实际资源自检通过。
- 双拼专项在正式资源下运行 **12 项**，全部通过；release 组合上限压力检查 **4 项**通过。长串随机字母仍昂贵，检查只证明组合长度／后段成本不继续无界增长，不能证明手机响应达标。旧性能报告也记录过这个风险；本轮不宣称通用延迟已解决，不用一次受负载影响的计时作准确率或速度优劣结论。
- Android 截图来自 Robolectric 原生绘制，Mac 截图来自原生 SwiftUI 离屏绘制；未安装竞品，未运行竞品准确率或设备延迟对比。

本机测试日志曾保存在 `/tmp` 下，不属于仓库产物，临时目录清理后不保证保留。Android JUnit 报告生成于 `android/app/build/test-results/testDebugUnitTest`；可复现命令见下方。

```sh
cd android
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin \
  -Pweave.abis=arm64-v8a -Pweave.snapshotDir=/tmp/weave-check-snapshots -x buildDicts -x syncDicts
```

上述命令复用已有兼容词库；新检出需先按仓库构建说明准备词库。只检查 JVM 逻辑时可加 `-Pweave.skipRust=true`；原生内核改动的打包验证须重新编译。

## 预览产物与界面

- Android ARM64 调试 APK：本地输出 `android/app/build/outputs/apk/debug/app-debug.apk`。
- Mac Apple Silicon 预览 ZIP：本地输出 `macos/build/WeaveText-AppleSilicon-preview.zip`，本地临时签名，未公证；不是 Intel 通用包。
- [手机高亮注音](screenshots/input-experience/android-selected-pinyin.png)、[展开后选中项自动可见](screenshots/input-experience/android-expanded-selection.png)
- [Mac 语音浅色](screenshots/input-experience/macos-voice-light.png)、[深色](screenshots/input-experience/macos-voice-dark.png)

APK、ZIP 与构建目录不提交到 Git；正式发布产物以 [GitHub Releases](https://github.com/sheep-programmer/WeaveText/releases) 为准。

## 尚未完成或需要设备验收

全拼分段选拼音、字形筛选的双拼辅助码方案（目前只有笔画与部件全拼）、26 键滑行输入（glide typing，本轮量过路径解码精度，见验证记录）、字母键盘自己的多步撤销与重做（现在只在光标面板里把撤销／重做交给编辑器，内核会话内的组合撤销仍只有退格撤回选词）、系统词库词的屏蔽、按音节编辑的触屏入口（内核与控制器已有按音节移动／删除，只有外接键盘能触发）、Mac 没有「关闭停笔自动上屏」这一项（只有快／中／慢三档，手机有该开关）、Mac 展开网格的笔画键只做了映射与显示条件验证、Mac 本地语音模型／SpeechAnalyzer 资源准备，以及原稿与整理稿分别展示仍有差距。手机真实导航栏、连续切应用、真实录音尾音／权限／音频焦点及 Mac 应用间焦点需要设备运行验证。已有半屏／全屏手写不等于在普通字母键盘上自动识别手写手势。

这些项目保持明确边界，未用本轮单元检查或官网／源码功能描述将它们标为已完成。

以下三条曾只是代码路径上的疑点，本轮逐一取到实证：网格翻页时内核**不会**改变已显示候选的前缀——新增内核用例比较补全前后前 120 项是否逐项一致，并断言补全确实发生；注入「补全后交换前两项」的变异后该用例失败，判据有效，这条按排除处理。`pairText` 的右半边分支确实不看选区，有选中文字时输入右半边不会替换选区（新增用例，修复前失败）。`ScrollGridView` 在横向位移超过阈值、纵向不足时确实既不滚动也不点选（新增用例，修复前失败）；原先担心的「有意的保守设计」不成立——真正需要横向滚动的是符号面板底行与分类列，它们各自实现手势，不走这段逻辑。
