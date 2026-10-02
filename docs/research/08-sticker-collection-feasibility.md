# 表情包收纳袋：跨应用拖入、管理与发送的可行性

调研日期：2026-10-02。范围：Android 与 Mac 的接收、收藏、动图保留、输入法插入和跨应用分享。已核对官方 API、项目原始 README／LICENSE，以及织文现有代码；QQ、微信、抖音的当前客户端尚未逐一实机验证。实现日期：2026-10-03，目标版本 beta.15。下文保留调研依据；已实现的范围与验证见文末。

## 结论

本地表情库、工具栏入口、分类／标签／收藏／搜索／最近使用和悬浮收纳界面都可以实现。跨应用拖入必须由来源 App 提供可读取的图片或文件；显示在聊天中的表情不一定是可拖出的文件。点选后插入当前输入框依赖目标 App 的富媒体协议，是否直接发出由目标 App 控制。不能把 Windows 工具的成功经验推断为三款 Android App 都可任意拖入、自动发送。

应实现一个表情资产库，接收四种入口：文件／相册选择、系统“分享到织文收纳袋”、可读取的图片剪贴板，以及兼容来源 App 的拖拽。后两种不能代替前两种可靠入口。

## 可参考项目及许可

| 项目 | 已核实能力 | 平台／许可 | 可借鉴的部分 |
|---|---|---|---|
| [EmojiManager](https://github.com/Natsukage/EmojiManager/tree/2a9d15f7c2d641cdaed480004149b7b5d00218c4) | 从 QQNT 拖入图片、钉住窗口、分组、最近使用、备注搜索、识别真实格式；点选复制到剪贴板，恢复 QQ 焦点并模拟 Ctrl+V | Windows / .NET；[MIT](https://github.com/Natsukage/EmojiManager/blob/2a9d15f7c2d641cdaed480004149b7b5d00218c4/LICENSE) | 接收后复制原文件、识别伪扩展名、悬浮窗口交互、最近使用；自动粘贴不等于自动按发送 |
| [MemeManager](https://github.com/shuiping233/MemeManager/tree/9f24675d2b90ca0e5825bd38caaebc9fb0261d5f) | 批量拖入、拖出文件、拖拽分类和排序、Mini 窗口、SHA-256 文件名与单独标题、点击后复制并 Ctrl+V | Windows / WinUI 3；[MIT](https://github.com/shuiping233/MemeManager/blob/9f24675d2b90ca0e5825bd38caaebc9fb0261d5f/License) | 收纳袋布局、哈希去重、原文件与显示名称分离、批量管理、缩略图按视口加载 |
| [OhMyMeme Android](https://github.com/OhMyMeme/OhMyMeme-Android/tree/38b6eb70774673de555a681a254234a2906f3469) | 文件与相册导入、GIF／WebP 播放、SQLite 标签／分组／收藏／最近使用、SHA-256 去重；点选通过 FileProvider + ACTION_SEND 分享 | Android / Kotlin；[GPL-3.0](https://github.com/OhMyMeme/OhMyMeme-Android/blob/38b6eb70774673de555a681a254234a2906f3469/LICENSE) | 数据模型和手机交互参考；本项目保持 Apache-2.0 时不直接复制其 GPL 源码。README 中 QQ 缓存导入仍是占位，不当作已有能力 |
| [Coil 动图支持](https://coil-kt.github.io/coil/gifs/) | 提供 GIF 解码／展示方案 | Android 库；采用时另行固定版本并保留许可 | 动图预览，避免把所有 GIF 全部同时解码；不能替代聊天 App 的发送支持 |

以上项目程序的许可不代表它们附带的每张表情图片都可重新打包分发。第一版管理用户主动导入的图片，不复制上游表情素材合集。

## Android 收纳与拖拽

[Android 拖拽文档](https://developer.android.com/develop/ui/views/touch-and-input/drag-drop/concepts)明确由来源应用调用 `startDragAndDrop`。收纳端能接收 Drop，不意味着它能强迫 QQ、微信或抖音产生跨应用拖拽。

- 主体仍是输入法工具栏的“收纳袋”面板，常用表情可以直接选择。
- 单独的可拖动收纳球／小窗作为可选模式。使用 [TYPE_APPLICATION_OVERLAY](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY) 时需要用户授予“显示在其他应用上层”，不能只是输入法内部悬浮布局。
- 默认贴边、避开状态栏、手势区／虚拟导航键和键盘；打开时仅自己的区域接收触摸，避免透明全屏层挡住聊天操作。需要编辑名称时才取得焦点。
- Android 12+ 可以优先采用 `View.setOnReceiveContentListener` 的接收路径；[AOSP View 实现](https://github.com/aosp-mirror/platform_frameworks_base/blob/master/core/java/android/view/View.java)在 Drop 时取得临时拖拽授权并把内容交给接收回调。输入法和悬浮 Service 的旧版本授权路径需要单独原型验证，不能直接调用 Activity 才有的 [`requestDragAndDropPermissions`](https://developer.android.com/reference/android/app/Activity#requestDragAndDropPermissions(android.view.DragEvent))。
- 接收的是可读 content URI／图片字节时，趁授权有效立即导入为自己的文件。若只有分享链接、表情编号或不可读 URI，提示使用来源 App 的分享／保存功能，不把它伪装成已收纳图片。
- 长按 QQ／微信图片可能打开菜单而不是开始系统拖拽；目前不能承诺这两类动作在三款 App 的各版本都成立。

## 发送的三层能力

1. **向编辑器插入图片**：读取目标 `EditorInfo.contentMimeTypes`，匹配 GIF／PNG／WebP MIME 后调用 `commitContent` 并授予读取权限。Android [图片键盘官方文档](https://developer.android.com/develop/ui/views/touch-and-input/image-keyboard)要求 IME 与接收 App 都参与；接收成功也不表示服务器上的聊天消息已发出。
2. **系统分享**：编辑器不支持相应类型时，走分享面板，让用户选 App／联系人。也可提供“保存到相册”。[微信 OpenSDK 官方文档](https://developers.weixin.qq.com/doc/oplatform/Mobile_App/Share_and_Favorites/Android.html)的对话分享场景不能被当作任意 App 获取当前聊天并无确认发送的通用接口。
3. **立即发送到当前聊天**：必须按 QQ、微信、抖音的实际版本验证。只有插入结果、会话归属及发送行为都明确时才能启用该模式；不以发送普通文字回车的通用逻辑去赌图片预览界面的按钮含义。

发送后可能作为图片／GIF 消息显示，不保证成为目标 App 的原生自定义表情，也不保证被加进它的收藏表情库。源 App 的私人缓存／专有格式不能作为第一版的通用导入依据。

## 存储与体验

表情原文件应与剪贴板历史分开，避免受到历史过期或“清空剪贴板”的影响。目录可采用 `stickers/originals/<sha256>.<真实后缀>`；独立元数据保存名称、标签、分组、收藏、加入时间、最近使用和来源。缩略图单独缓存，原 GIF／动态 WebP 原样保留，不能像普通图片同步路径那样全部转成 PNG。

导入时按文件头判断格式，做 SHA-256 去重和图片尺寸／文件大小检查；后台导入、按视口解码，批量任务显示成功／重复／失败。第一版可支持重命名、标签搜索、分组、多选删除、导入导出。字幕 OCR 和语义搜索可后续独立评测，先提供稳定的手动标签。

## Mac

从系统输入法菜单打开一个非激活的收纳窗，继续满足“不要额外菜单栏图标”的要求。AppKit 的 [`NSDraggingDestination`](https://developer.apple.com/documentation/appkit/nsdraggingdestination)负责接收拖拽，优先保留文件／原图表示。支持从收纳窗拖出到聊天，或复制到剪贴板；自动 Cmd+V 的焦点恢复和所需系统授权要单独验证。Windows 的 Win32 焦点／键盘模拟代码不能原样移植到 Mac。

## 织文现有基础与下一步

现有 `LinkContent.import` 已能把临时 URI 复制成自己拥有的文件，并做哈希；`InputController.onContent` 已实现 MIME 检测与 `commitContent`；`ClipboardPanel` 已有插入失败后的系统分享；互联已有原文件传输。可复用这些基础，新增独立表情库和入口，避免重新做一套权限与发送机制。

优先交付：独立表情库 + 键盘收纳袋 + 分享接收／相册导入 + 图片插入和分享。随后验证收纳浮窗与跨 App 拖拽。兼容矩阵至少覆盖三款 App、普通图片／GIF／动态 WebP、原 App 图片与原生表情、收纳和插入两方向；记录 Android／手机型号／App 版本及是否出现图片预览、是否仍需要发送确认。手机三个 App 尚未完成实机兼容矩阵，不能现在称为“都已支持”。


## beta.15 已实现

Android 工具栏增加“表情收纳袋”，设置首页也可打开管理页。支持系统“收纳到织文”单张／批量图片分享、相册、文件、可读取的图片剪贴板和原生拖入；不申请全盘文件访问。管理页提供名称／标签搜索、分组、收藏、最近使用、批量分组／删除和备份。密码及禁止个性化学习的输入框隐藏收纳袋内容。可选悬浮窗由用户授权并开启，有持续通知和关闭入口；小球可移动、展开，避开织文键盘和系统导航区，横屏空间不足时收起。

Mac 从系统输入法菜单的“表情收纳袋…”打开非激活浮窗，支持小窗切换、批量拖入、从文件及剪贴板收纳、分组／标签／收藏／最近使用、批量整理和备份。点选将原图数据及文件 URL 一起放入剪贴板；只有已取得辅助功能权限且目标 App 仍在前台时才尝试 Cmd+V。不会模拟发送回车。也可直接把原文件拖到目标应用。网格使用缩略图，原动图可从右键菜单预览。

两端资产目录独立于剪贴板历史，以图片字节 SHA-256 去重并识别真实 PNG／JPEG／GIF／WebP／BMP。原 GIF／WebP 不转为 PNG。备份格式为 `weavetext-stickers-1`，含原图和元数据，可在 Android／Mac 互导。导入验证文件名、内容哈希、尺寸和单图大小（20 MB），限制档案大小及解包总量（512 MB）。导出超过限额时提示整理，不生成无法恢复的大档案。

实现是按平台原生 API 独立编写，未复制参考项目源码、图标或表情素材，因此没有引入 GPL 代码；原项目许可链接见上表。测试样图由本仓库自行绘制，不打包到正式安装程序。

## 验证与兼容边界

- 存储测试验证伪扩展名、PNG／GIF／动态 WebP 原字节保留、重复收纳、重启、标签／收藏／分组／最近、删除、损坏索引回退、超限／非图片／非法路径／哈希不符与不完整备份。
- Android／Mac 的实际 ZIP 导出交叉导入，元数据及原图字节均检查。
- Mac 使用独立测试剪贴板检查 GIF／WebP 原数据及文件 URL 表示；输入法菜单测试确认收纳袋命令属于系统输入源菜单。
- Android 检查分享接收、原生 receive-content 接收、不可读 URI 提示、私密输入框、支持／不支持图片的编辑器及读取授权。设备测试以独立测试包的 content provider 分享动图，撤销来源授权后检查自己的原文件。
- 明暗主题的键盘收纳袋、管理页及 Mac 窗口有截图检查；可选悬浮窗另检查键盘避让。

QQ／微信／抖音尚未完成逐版本实机兼容矩阵。当前提供“插入图片”和系统分享，并提示用户在目标应用确认发送。来源 App 不给可读取的拖拽文件时，应从其分享／保存菜单导入；目标输入框不接受图片时，应选择分享。这里不将平台协议测试当作商业聊天 App 的实机验证。


最终检查：Android 43 项 JVM 测试、独立来源应用的设备分享／授权测试与 Lite／语音版 Release Lint；Mac 131 项测试及两个架构的包内词库自检。Android 12 模拟器上确认原 GIF 实际播放、竖屏避开键盘、横屏收成小球并避开侧边导航。修复了窗口坐标被系统状态栏再次偏移而压住工具栏的问题。

同时构建时，调试包首次解析全部主题曾发生一次 ANR，栈停在已有的 `StyleRepository` JSON 解析。停止构建、重启测试设备后，以非增量安装的最终签名 Lite 包检查键盘首次打开、切换和两种方向，未再次出现 ANR。这不能代替所有真实手机上的性能评测；商业聊天 App 的拖入／发送仍待实机矩阵。
