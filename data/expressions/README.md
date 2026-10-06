# 两端共用的表情目录

`catalog.json` 内含 1898 个不重复的基础 Emoji、132 个颜文字及中文名称。肤色变体由界面选择，不重复挤占目录；Android 按系统字体能力隐藏不能正确绘制的 Emoji。手机和 Mac 从同一文件打包，应用运行时不下载目录。

Emoji 内容来自 Unicode Emoji 15.1，中文名称来自 CLDR 48，许可原文在 `UNICODE-LICENSE.txt`。颜文字及其中文分类由项目整理。

更新时将以下来源保存到一个工作目录，再运行 `python3 tools/expressions/build.py --source-dir <目录>`：

- `emoji-test.txt`：[Unicode Emoji 15.1](https://www.unicode.org/Public/emoji/15.1/emoji-test.txt)
- `zh.xml`：[CLDR 48 中文名称](https://raw.githubusercontent.com/unicode-org/cldr/release-48/common/annotations/zh.xml)
- `zh-derived.xml`：[CLDR 48 派生名称](https://raw.githubusercontent.com/unicode-org/cldr/release-48/common/annotationsDerived/zh.xml)
- `LICENSE.txt`：[Unicode 许可](https://www.unicode.org/license.txt)

`kaomoji.tsv` 是颜文字源数据，列为分类、名称、内容。生成器会去掉重复内容，并报告中文名称缺失项。

长按名称提示贴近表情字形显示；手势表情的肤色面板在上方，名称贴在表情下方。只长按查看不会输入，滑动选中肤色后才输入。
