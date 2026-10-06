import AppKit
import WeaveCore

/// Commands owned by InputMethodKit's system input-source menu; no additional status item.
enum InputMenu {
    static func make(target: NSObject, schema: String, chinese: Bool, traditional: Bool,
                     hasSchema: (String) -> Bool, phones: [String] = [], canSend: Bool = false,
                     progress: String? = nil) -> NSMenu {
        let menu = NSMenu()
        @discardableResult func add(_ title: String, _ action: Selector) -> NSMenuItem {
            let item = NSMenuItem(title: title, action: action, keyEquivalent: "")
            item.target = target
            menu.addItem(item)
            return item
        }
        add("织文键盘设置…", #selector(WeaveInputController.showPreferences(_:)))
        add(chinese ? "切换到英文" : "切换到中文", #selector(WeaveInputController.toggleMode(_:)))
        menu.addItem(.separator())
        for (i, scheme) in InputScheme.all.enumerated() {
            let item = add(scheme.name, #selector(WeaveInputController.selectScheme(_:)))
            item.tag = i
            item.state = scheme.id == schema ? .on : .off
            if !hasSchema(scheme.id) { item.action = nil }
        }
        menu.addItem(.separator())
        add("繁体输出", #selector(WeaveInputController.toggleTraditional(_:))).state = traditional ? .on : .off
        add("打开手写窗口…", #selector(WeaveInputController.openHandwriting(_:)))
        add("打开语音悬浮窗…", #selector(WeaveInputController.openVoice(_:)))
        add("翻译所选文字…", #selector(WeaveInputController.openTranslation(_:)))
        add("表情收纳袋…", #selector(WeaveInputController.openStickers(_:)))
        add("Emoji 与颜文字…", #selector(WeaveInputController.openExpressions(_:)))
        add("剪贴板历史…", #selector(WeaveInputController.openClipboard(_:)))
        add("常用语与快捷模板…", #selector(WeaveInputController.openPhrases(_:)))
        add("插件与语音设置…", #selector(WeaveInputController.openPlugins(_:)))
        add("重新转换所选文字", #selector(WeaveInputController.reconvertSelection(_:)))
        add("织文互联…", #selector(WeaveInputController.openLink(_:)))
        if !phones.isEmpty {
            let status = NSMenuItem(title: "已连接 " + phones.joined(separator: "、"), action: nil, keyEquivalent: "")
            menu.addItem(status)
        }
        if canSend {
            add("发送剪贴板到手机", #selector(WeaveInputController.sendClipboard(_:)))
            add("发送文件到手机…", #selector(WeaveInputController.sendFiles(_:)))
        }
        if let progress { menu.addItem(NSMenuItem(title: progress, action: nil, keyEquivalent: "")) }
        menu.addItem(.separator())
        add("关于织文…", #selector(WeaveInputController.openAbout(_:)))
        return menu
    }
}
