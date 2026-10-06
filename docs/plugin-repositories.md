# GitHub 插件仓库

手机设置 › 语音引擎 › 插件仓库，或 Mac 设置 › 插件与语音，可以保存自定义 GitHub 仓库，选择分支、标签和插件目录。
「添加仓库」可直接粘贴完整 GitHub HTTPS 地址，也接受 `github.com/owner/repository` 或 `owner/repository`。
仓库首页（含 `.git` 后缀、查询参数或锚点）使用默认分支；`/tree/分支/目录` 自动识别分支和目录，
`/blob/分支/插件文件` 选择对应文件（不限后缀），`/blob/分支/目录/manifest.yaml` 选择所在源码目录并包含入口文件。
输入框下显示解析出的仓库、分支和目录，可用可选字段覆盖。分支名含未编码的 `/` 时，在分支字段填写完整名称。
地址错误时在弹窗内提示，保留已输入内容供修改。
支持 Release 附件、仓库内的插件压缩包，以及带 `manifest.yaml` 的插件源码目录。
压缩包按 ZIP 内容、有效清单与入口脚本识别；`.zip`、自定义后缀、无后缀和旧 `.xipk` 均可使用。
普通附件与缺少清单或入口的 ZIP 不计入压缩包插件数量。仓库逐个探测文件签名，非 ZIP 只读前四字节；
超过 512 个待探测文件时需指定较小目录。源码目录按当前提交的 Git blob 下载并校验，再打包成内部临时 ZIP；
安装前显示名称、版本和网络访问说明。手动导入也不按文件名过滤，仍需内容符合宿主的 Lua 插件 API。

仓库以可展开列表显示，每项标明可导入插件数量及公开 / 私有状态。点仓库标题展开或收起，展开后逐项导入；
刷新、重试和移除操作放在对应仓库中。数量按当前分支、目录中可导入的条目统计（发布包的不同版本也可能单独列出）。
上次读取的数量保存在本机，打开页面时后台更新；空仓库显示 0 项，读取失败单独提示。

## 私有仓库登录

目前使用访问令牌登录，不需要 OAuth Client ID：

1. 点「登录 GitHub」›「在 GitHub 创建只读令牌」。
2. 创建 Fine-grained personal access token，选择需要读取的仓库，授予 **Contents: Read-only**。
3. 将令牌粘贴到应用的 GitHub 登录框。应用通过 `/user` 验证账号，再加密保存令牌。
4. 加入私有仓库地址，选择插件，下载并确认安装。组织仓库可能还需要组织管理员批准或 SSO 授权。

只保存仓库地址、分支与目录；GitHub 令牌独立保存为 Android Keystore AES-GCM 密文或 Mac 钥匙串项，不进入插件配置或个人资料互联同步。
授权头只发送给 `https://api.github.com`，下载重定向不携带授权头。退出登录清除本机授权。
登录框不会保存到界面恢复 Bundle，并在显示时禁止截图。

## 使用导入的插件

导入后在语音引擎页的可展开列表中查看插件，收起时显示名称、版本、设置数量与使用 / 配置状态。
点击标题展开后进入「配置与详情」，填写所需配置，再通过「设为当前引擎」启用；展开或收起不会切换引擎。
右上角搜索可按名称、插件标识或说明查找，并筛选已启用 / 待配置插件。进入配置或仓库页后返回，保留列表的筛选、展开状态和滚动位置，同时重新读取引擎与配置状态。
「同时使用」也可展开调整，仍可选择离线模型，最多三个引擎共享一次录音；未完成配置的插件不能新增选中。
配置项的说明文字可直接点击编辑设置；说明超过三行时，通过单独的「展开说明 / 收起说明」按钮阅读全文。
旧版保存的未知云端引擎偏好不会自动生效。插件中的 GitHub 仓库地址不意味着插件可读取 GitHub 登录令牌。
只安装你信任的插件，联网插件的音频访问说明会在确认安装前显示。

## 验证

单元测试覆盖账号验证、加密存储、退出登录、授权头不随重定向外传、私有仓库目录读取、Git blob 校验、非法路径及损坏下载的清理。
地址测试覆盖完整 HTTPS / `.git` / `www.github.com` 链接、分支目录、插件包文件、manifest 所在目录、路径中的 `+`、百分号编码及手动字段覆盖。
`RepositoryAddressDeviceTest` 在 Android 实际窗口中验证完整地址粘贴、错误纠正、保存、目录识别和分支覆盖，接口使用受控 GitHub 响应。
`PluginRepositoryDeviceTest` 使用受控 GitHub API 响应，在 Android 真实 Keystore 和 JNI 插件宿主中验证下载、安装、选择及运行；不使用真实账号凭据。
`VoicePluginListTest` 覆盖搜索与状态筛选、子页返回后的展开 / 滚动位置恢复、配置状态刷新和说明文字的点击行为。
`VoiceSettingsDeviceTest` 在 Android 实际窗口中点击说明、填写并保存配置、返回列表并启用引擎，语音服务使用独立内存替身。

接口依据：[GitHub Git blob API](https://docs.github.com/en/rest/git/blobs#get-a-blob)、
[GitHub release assets API](https://docs.github.com/en/rest/releases/assets#get-a-release-asset)、
[GitHub authenticated user API](https://docs.github.com/en/rest/users/users#get-the-authenticated-user)。
