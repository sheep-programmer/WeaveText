# 个人选词、英文补全与本地学习

用户明确选择一个字并上屏，代表希望这个输入码优先给出该字。旧版本只累计词频：如果旧字已记录很多次，连续选五次新字仍可能排在其后。以真实词库的 `shi → 嗜` 为例，预先给「是、时」积累较高词频，选五次后「嗜」仍是第三。

新版同时记录长期次数和近期明确选择。对相同读音，重复确认形成偏好，完整、无纠错的候选优先显示；多次更换选择也能改变偏好。单字的偏好不把更长输入的整句候选挤走：`shi` 选「嗜」不会要求 `shijian` 首选变成两个孤立的单字。全拼、双拼、九键共享汉字读音和学习结果。

选择词句时学习组成的词，也记住最终确认的完整词句。手动分段拼出的词可作为整体候选，不必下次继续拆开选。普通自动组句积累使用次数，不冒充用户明确的偏好。

选择完成和撤销时写入本地日志，换输入框或重新加载不清空偏好；无需等输入法正常退出才保存。选错立即退格会恢复此前的词频、最近选择和搭配，避免误选留下新的“常用字”。密码框及禁止个性化学习的输入框不积累这些记录。记录留在本机，词库清空会同时清除英文个人记录。

## 英文

Mac 的中英切换会切换到英文引擎，英文按键进入补全；Android 普通文字输入框提供英文补全。密码、网址、邮箱及应用明确关闭建议的输入框保留其输入规则。

输入 `hel` 可看到 `hello` 等完整词；输入的原文保留为首项，以免空格、回车或标点把原文擅自替换成另一词。最近常选的补全排列在其他补全前。首次使用的英文词可以保存在本地，再次输入其开头时给出补全；首字母大写和全大写跟随输入。

**点选英文候选只输出单词，不自动补空格。** 主动按空格才输出空格。下一词联想包含少量常见英文搭配和本机实际使用的相邻词，个人搭配在前；它不是大型英文语言模型，陌生搭配仍需自行输入。自定义英文词和搭配也支持立即删除撤销、关闭学习和本地清空。

## 开源调研与本次采用的做法

- [Rime 用户词库](https://github.com/rime/librime/blob/master/src/rime/dict/user_dictionary.cc) 和 [动态权重公式](https://github.com/rime/librime/blob/master/src/rime/algo/dynamics.h)：同时使用累计提交次数与随近期使用变化的权重。织文保留长期次数，并另外记录重复的明确选词，解决新偏好被旧累计次数压住的问题。
- [libime 用户语言模型](https://github.com/fcitx/libime/blob/master/src/libime/core/userlanguagemodel.cpp) 和 [历史搭配模型](https://github.com/fcitx/libime/blob/master/src/libime/core/historybigram.cpp)：把系统模型与用户历史组合，历史既包含单词频率，也包含相邻词。织文将用户的汉字选择与英文补全／搭配接入排序，保留系统候选及原文。
- [Mozc 用户历史预测](https://github.com/google/mozc/blob/master/src/prediction/user_history_predictor.cc) 和 [Rime 学习会话](https://github.com/rime/librime/blob/master/src/rime/gear/memory.cc)：保存近期访问／提交记录，并处理选错后的撤销。织文撤销时恢复提交前的状态，避免仅减次数却留下错误选择的新时间戳。

本次是根据这些实现思路修改织文自己的学习逻辑，没有引入其源码或运行时依赖。

## 验证方法

`cargo test -p weave-engine --test adaptive_learning` 覆盖旧词频很高时反复选单字、全拼／双拼／九键、独立重新加载、完整新词、撤销与隐私、英文补全及英文搭配。Android JNI 和 Mac C ABI 测试使用正式词库重复确认「嗜」，验证读到同一本地日志后的首选。

诊断工具：`cargo run --release -p weave-engine --example learncheck -- DATA_DIR ISOLATED_USER_DIR CODE WORD 5 [SEED_TSV]`。输出每次选词后的排名和重新加载的候选。请使用独立用户目录，不覆盖日常词库。
