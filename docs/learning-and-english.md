# 个人选词、英文补全与本地学习

用户明确选择一个字并上屏，代表希望这个输入码优先给出该字。旧版本只累计词频：如果旧字已记录很多次，连续选五次新字仍可能排在其后。以真实词库的 `shi → 嗜` 为例，预先给「是、时」积累较高词频，选五次后「嗜」仍是第三。

新版同时记录长期次数和近期明确选择。对相同读音，确认一次就形成偏好，多次更换选择也能改变偏好。没有上文时，覆盖整个输入且符合当前读法的近期选择优先；完整拼写或已开启的模糊音有上文时，最终候选保留解码器结合上下文／已学搭配选出的胜者，避免在列表生成时再次无条件置前。简拼、未完整音节的补全及已开启的模糊音仍可使用近期选择补偿；纠错或把完整音节补成长音节的候选不能仅凭近期选过就强行置前。例如选过 `shijian → 时间` 后，`shijia` 仍优先按 `shi jia` 选词，不把 `jia` 擅自补成 `jian`；未打完的 `shij` 仍可补全，完整 `shijian` 仍保留学习结果。**简拼与云端热词也能学习**：`sj` 选出「使劲」后，下次打 `sj` 保留近期优先；从热词里选出的词也进入同一套个人学习，完整拼写有上文时仍按上述规则竞争。偏好要覆盖整个输入才生效，单字的偏好不把更长输入的整句候选挤走：`shi` 选「嗜」不会要求 `shijian` 首选变成两个孤立的单字。全拼、双拼、九键共享汉字读音和学习结果。

Recent choices and long-term counts both affect ranking. Without context, a recent choice covering the input and matching its reading may lead. For full or enabled fuzzy readings with context, candidate construction preserves the decoder's contextual/collocation winner instead of unconditionally prepending a recent choice again. Abbreviations, incomplete-syllable completions and enabled fuzzy readings retain preference compensation. Correction paths and extensions of already complete syllables cannot lead solely because the word was recently selected. After learning `shijian → 时间`, `shijia` still favors `shi jia` rather than extending `jia` to `jian`; unfinished readings can still complete, and the exact `shijian` reading keeps its learned preference.

选择词句时学习组成的词，也记住最终确认的完整词句。手动分段拼出的词可作为整体候选，不必下次继续拆开选。普通自动组句积累使用次数，不冒充用户明确的偏好。

选择完成和撤销时写入本地日志，换输入框或重新加载不清空偏好；无需等输入法正常退出才保存。选错立即退格会恢复此前的词频、最近选择和搭配，避免误选留下新的“常用字”。连续提交达到门槛后生成新词的临时证据也按提交前状态恢复：被否定的词对不计数，撤回新词不会丢掉此前有效的一次证据。密码框及禁止个性化学习的输入框不积累这些记录。记录留在本机，词库清空会同时清除英文个人记录。

Immediate backspace restores pre-commit word counts, recent choices, collocations and provisional evidence used to learn consecutive word pairs. Rejected pairs contribute no evidence; undoing a newly promoted phrase restores earlier valid evidence. Learning remains disabled in private fields, and persistent records stay local.

## 上屏后的中文联想

个人搭配按长期次数和后续学习步数衰减后的有效次数排序。例如很久以前常写「下午开会」，近期改写「下午吃饭」，新习惯可以超过衰减后的旧习惯。这里的“时间”是本机学习时钟：每次有效学习推进，不按日历天数计算，也不会因为几天没打开输入法而清空习惯。

有效次数低于个人词库原有淘汰阈值的搭配，不再占据个人联想优先档，无需等到日志压缩。仍有有效证据的旧搭配会保留；同一词即使不再有个人证据，仍可作为词库接续出现，并遵循普通联想的上下文与收尾门控。候选数量、连选深度与隐私开关沿用已有规则。

Chinese personal next-word predictions use counts decayed by subsequent learning steps, so recent habits can overtake older ones. Age follows the local learning clock rather than calendar time. Pairs below the existing dictionary-pruning threshold lose personal priority immediately, without waiting for log compaction. Older pairs with remaining evidence stay available; an expired personal pair may still appear as an ordinary lexicon continuation under the existing context/closing rules. Candidate limits, chain depth and privacy controls are unchanged.

## 候选注音与动态排序

手机和 Mac 默认不显示拼音，可在「输入方案」打开。打开后只给正在选的那个候选在词后用括号标出完整带声调拼音，例如 银行(yín háng)，其他候选不标，候选栏高度不变。手机未移动候选时为首选，外接键盘移动后注音跟随高亮项；Mac 也跟随高亮项。展开候选只显示词语，不显示注音；读音按实际词库音节及常见多音词匹配，轻声按规范不加调号。拼音和五笔编码等说明分别传输，关闭开关立即刷新当前组合。

手机外接键盘可用上下键、Tab／Shift+Tab 逐项移动，PageUp／PageDown 移动九项。候选自动滚入可见范围，1–9 对应高亮项所在的九项页，空格确认；主动移动后回车也确认该项。未移动候选时回车仍上屏原始输入码，联想候选上的回车交给应用并清空候选，Esc 清空。英文空格确认保留用户主动输入的空格。左右键与应用快捷键继续按编辑器规则处理。

手机长按候选（包括展开后的候选）可查看和复制完整文字。弹窗中的排序操作绑定当时的输入框与候选状态，候选改变或键盘隐藏即收起，过期按钮不能作用到下一次输入。

候选不显示「已固定」，也不提供永久固定入口。多次使用和近期明确选词共同影响排序；同样输入下，后来更常选另一个词，就能更新首选。旧版人工置顶记录遇到新的明确选择后也会让位，仍保留已学词频与近期选择记录。

## 英文

Mac 默认关闭联想词和英文单词补全，英文按键原样交给当前应用；输入 `recieve` 就保留 `recieve`，数字、大小写、空格和标点也原样输入。可在「输入方案」中分别开启联想词和英文单词补全。开启英文补全后，候选窗里的 `1`–`9` 用来选词，选词数字不写入文本；没有英文组合串时数字照常输入。Android 普通文字输入框提供英文补全。密码、网址、邮箱及应用明确关闭建议的输入框保留其输入规则。

开启英文补全时，输入 `hel` 可看到 `hello` 等完整词；输入的原文保留为首项，以免空格、回车或标点把原文擅自替换成另一词。最近常选的补全排列在其他补全前。首次使用的英文词可以保存在本地，再次输入其开头时给出补全；首字母大写和全大写跟随输入。

**点选英文候选只输出单词，不自动补空格。** 主动按空格才输出空格。下一词联想包含少量常见英文搭配和本机实际使用的相邻词，个人搭配在前；它不是大型英文语言模型，陌生搭配仍需自行输入。自定义英文词和搭配也支持立即删除撤销、关闭学习和本地清空。

## 开源调研与本次采用的做法

- [Rime 用户词库](https://github.com/rime/librime/blob/master/src/rime/dict/user_dictionary.cc) 和 [动态权重公式](https://github.com/rime/librime/blob/master/src/rime/algo/dynamics.h)：同时使用累计提交次数与随近期使用变化的权重。织文保留长期次数，并另外记录重复的明确选词，解决新偏好被旧累计次数压住的问题。
- [libime 用户语言模型](https://github.com/fcitx/libime/blob/master/src/libime/core/userlanguagemodel.cpp) 和 [历史搭配模型](https://github.com/fcitx/libime/blob/master/src/libime/core/historybigram.cpp)：把系统模型与用户历史组合，历史既包含单词频率，也包含相邻词。织文将用户的汉字选择与英文补全／搭配接入排序，保留系统候选及原文。
- [Mozc 用户历史预测](https://github.com/google/mozc/blob/master/src/prediction/user_history_predictor.cc) 和 [Rime 学习会话](https://github.com/rime/librime/blob/master/src/rime/gear/memory.cc)：保存近期访问／提交记录，并处理选错后的撤销。织文撤销时恢复提交前的状态，避免仅减次数却留下错误选择的新时间戳。

本次是根据这些实现思路修改织文自己的学习逻辑，没有引入其源码或运行时依赖。

## 验证方法

`cargo test -p weave-engine --test adaptive_learning` 覆盖旧词频很高时反复选单字、全拼／双拼／九键、独立重新加载、完整新词、简拼与云词选一次即置顶、撤销与隐私、英文补全及英文搭配。Android JNI 和 Mac C ABI 测试使用正式词库重复确认「嗜」，验证读到同一本地日志后的首选。

诊断工具：`cargo run --release -p weave-engine --example learncheck -- DATA_DIR ISOLATED_USER_DIR CODE WORD 5 [SEED_TSV]`。输出每次选词后的排名和重新加载的候选。请使用独立用户目录，不覆盖日常词库。

## 常用符号

在符号面板（包括「常用」分类）和九键标点候选里点选都会记录使用。最近明确选择的符号立即靠前，常用分类同时保留长期使用统计；后续改选其他符号会更新顺序。分类保持打开时也立即重排。密码等敏感输入不学习。

## 双拼英文片段与原码候选（内核）

双拼内核保留实际输入的大小写，只在查双拼表时转小写。四套双拼共用已有英文片段解码，英文结束后仍用当前双拼方案继续解码，包括奇数长度的 `API`、`hello`。每个片段最多搜索 32 个字母；使用现有英文词库、本机学过的英文词和显式全大写片段，不添加外部词库或语言模型。显式大写的词库精确命中可保留低频技术词，如 `GitHub`，输出片段沿用输入的大小写。

完整合法的双拼中文读法仍在英文混输前。例如正式词库下，小鹤 `niGitHubhc` 从「你gi汤ub好」变为「你GitHub好」；`nireviewhc` 首选仍为「你热值诶好」，另给出「你review好」供明确选择。未知字母技术词也有整串原样候选，不必安装英文词库。全拼和双拼中的 `RuSt`、`GitHub`、`WiFi`、`API` 保留实际字母；字母原文候选优先于原先会排首位的英文词库变体，`dont` 不会因自动确认擅自加撇号，`don't` 仍可主动选择。回车原码上屏保留双拼大小写，分段选过中文后也保留剩余原码。

原样字母候选及混输片段中的英文学习归英文个人词库，中文部分按汉字读音学习，不把英文或整段混输写成中文新词。立即退格同时恢复两类学习记录；关闭学习时不记录，也不读取个人英文片段偏好。单纯原码回车不学习。未知小写词不会自动猜成内嵌英文，数字、下划线、点号等也未扩展成片段；微软／搜狗双拼的分号韵母键保持原规则。

这是 **core 行为**，不表示两端宿主已全部接通。组合中须将原始 ASCII 字母逐字符传给 `input_char`／`input_key`，不能提前提交首选或转小写；idle 大写可继续直接上屏。引擎返回 `false` 的数字／普通符号交给宿主处理，宿主应按用户的原码意图完成当前组合并插入该键，不能忽略返回值。保留原码的回车调用 `commit_raw()`；全拼撇号仍是隔音符，v 计算模式仍受已有开关控制。Android／Mac 的大小写路由与数字收尾需要各自的 host tests。

专门验证：`core/weave-engine/tests/shuangpin_mixed.rs` 使用自行构造的小词库覆盖四套双拼、奇偶长度英文、合法中文优先、技术词字面候选、大小写回车、分段选词、数字符号回放、学习归属与隐私。正式资源测试显式标为 ignored，避免缺资源时假装运行；在仓库根目录执行 `WEAVE_TEST_DATA_DIR="$PWD/android/app/build/dictAssets/dict" cargo test --manifest-path core/Cargo.toml --target-dir /tmp/weave-next-core-target -p weave-engine --test shuangpin_mixed -- --include-ignored` 可实际运行。正式延迟及压力验证使用同一资源目录并加 `--release`，不把 debug 耗时当发布性能。
