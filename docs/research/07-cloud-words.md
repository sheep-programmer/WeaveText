# 云端词的展示与可复用数据

核对日期：2026-10-02。织文的云词是维护者签名发布、客户端下载的补充词表；候选解码在本机完成。

| 项目 | 核实内容 | 本次处理 |
|---|---|---|
| [Fcitx5 CloudPinyin 候选源码](https://github.com/fcitx/fcitx5-chinese-addons/blob/master/modules/cloudpinyin/cloudpinyin_public.h) | 异步候选用云字符占位，未返回时不能选择，并处理与本地候选重复的情况；源码为 LGPL-2.1-or-later | 参考“来源可见、加载反馈、去重”的交互思路，独立实现图标和状态；没有引入其联网请求或源码 |
| [万象拼音](https://github.com/amzxyz/rime-wanxiang/tree/908108a09121aaf7ba2cad1fcfbe71b427ea9090) | 仓库 [LICENSE](https://github.com/amzxyz/rime-wanxiang/blob/908108a09121aaf7ba2cad1fcfbe71b427ea9090/LICENSE) 为 CC BY 4.0；基础词表带拼音与权重 | 固定版本，筛掉内置词和低频内容，纳入 24 个通用补充词；保留作者、许可、来源、修改说明 |
| [THUOCL](https://github.com/thunlp/THUOCL/tree/a30ce79d895d01ab5132a5c74c29703ff7efb4cc) | 原始 [LICENSE](https://github.com/thunlp/THUOCL/blob/a30ce79d895d01ab5132a5c74c29703ff7efb4cc/LICENSE) 为 MIT；词表含词频，没有拼音 | IT 词表按基础万象数据注音，筛掉不可注音与已内置词，纳入 1200 项；保留 MIT 声明，注音派生部分保留 CC BY 4.0 |
| [雾凇拼音](https://github.com/iDvel/rime-ice) | 仓库 LICENSE 是 GPL-3.0，词典头还列有多种第三方来源 | 未直接合并，需要逐个数据文件核对原始许可及再分发要求；没有把仓库许可证当作所有第三方数据的授权证明 |
| [fcitx5-pinyin-zhwiki](https://github.com/felixonmars/fcitx5-pinyin-zhwiki) | README 明确区分工具的 Unlicense 与生成词典适用的 Wikimedia 数据许可 | 本轮未合并；不能把生成词库误标为 Unlicense／CC0，后续可评估独立词包 |

## 展示

手机候选栏、展开列表和 Mac 候选窗在云词旁画蓝色云朵；读屏也能知道是云词。标记与“用户学过的词”是不同字段，不会因为蓝云而把未学过的词当作可删除用户词。本地已具备的整词优先保留本地标记；整句包含云端补充词也可以显示蓝云。

更新时在手机组合栏与设置页、Mac 候选头部与设置页提供加载反馈；失败显示重试，保留已通过验证的旧数据。取消或关闭云词后，较早的异步操作不能把新操作的加载状态清掉，也不会把关闭的词库重新挂上。更新词库不即时重排正在点选的候选。

蓝云表示下载的词表对这个候选提供补充；不会逐键提交拼音、文字或个人词频到服务器。用户选择云词后仍沿用本地学习，关闭云词后已学会的词仍可以作为本地词使用。

## 发布与许可

数据仓库从两个示例扩充至 1264 个有效词：2 个原示例、24 个万象补充、1200 个 THUOCL IT 术语及 38 个自主整理的 AI／输入法术语。这是通用与术语补充，不将 2018 年的 THUOCL 数据称作实时新闻热榜。

导入词表保留固定版本、输入及输出摘要、注音基线、复现脚本。自主整理的数据仍为 CC0；导入内容保留 CC BY 4.0／MIT，不能把整个合并文件声明为 CC0。签名文件的注释头携带署名、许可证链接、转换说明和 MIT 原始声明；客户端提供“词库来源与许可”入口。

公开数据及逐项许可见 [weavetext-hotwords/SOURCES.md](https://github.com/sheep-programmer/weavetext-hotwords/blob/main/SOURCES.md)。签名密钥及客户端公钥不变。

## 验证

- 核心：云词、整句中的云词、与本地重复、学过的云词、卸载后的本地学习记录。
- 实际 JNI：签名词表加载，快照与分页保留蓝云标记；云词标记与学习标记独立。
- Mac：候选 JSON 兼容旧字段；取消旧更新后新更新仍显示加载；原有 304、坏签名、镜像重试回归。
- 手机与 Mac 的明暗主题截图检查；Android 点击／长按回归及 lint。
- 使用客户端内置公钥验证实际发布的 1264 词签名；真实词库下“时间复杂度”“正则表达式”“上下文工程”可选择并带云词标记。
