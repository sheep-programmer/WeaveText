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
| 双拼英文片段 | 四套双拼支持有界英文片段，保留大小写及英文结束后的双拼边界；完整合法中文读法优先。原样字母候选避免自动把 `dont` 改成 `don't`。Android 组合中的大写路由已接通，Mac 原有组合字母路由保留大小写。数字、下划线、点号等技术字符串尚未整体纳入片段。详见 [混输边界](learning-and-english.md)。 |

## 本轮验证

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

组合串中间编辑、以词取字、辅助码扩展、26 键滑行与隐式手写的区分、Mac 本地语音模型／SpeechAnalyzer 资源准备，以及原稿与整理稿分别展示仍有差距。手机真实导航栏、连续切应用、真实录音尾音／权限／音频焦点及 Mac 应用间焦点需要设备运行验证。已有半屏／全屏手写不等于在普通字母键盘上自动识别手写手势。

这些项目保持明确边界，未用本轮单元检查或官网／源码功能描述将它们标为已完成。
