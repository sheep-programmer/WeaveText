import AppKit
import WeaveCore

/// Commands owned by InputMethodKit's system input-source menu; no additional status item.
enum InputMenu {
    static func make(target: NSObject, schema: String, chinese: Bool, traditional: Bool,
                     hasSchema: (String) -> Bool, phones: [String] = [], canSend: Bool = false,
                     progress: String? = nil, enabledExtensions: Set<String> = ExtensionRegistry.defaults) -> NSMenu {
        // 顶层只放常用的几项；其余调整都在设置窗口里，工具与互联收进子菜单。
        // Only the everyday items at the top level; other adjustments live in Settings, tools and link in submenus.
        let menu = NSMenu()
        @discardableResult func add(_ title: String, _ action: Selector, to m: NSMenu = menu) -> NSMenuItem {
            let item = NSMenuItem(title: title, action: action, keyEquivalent: "")
            item.target = target
            m.addItem(item)
            return item
        }
        func submenu(_ title: String) -> NSMenu {
            let sub = NSMenu(title: title)
            let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
            item.submenu = sub
            menu.addItem(item)
            return sub
        }
        add("织文设置…", #selector(WeaveInputController.showPreferences(_:)))
        add(chinese ? "切换到英文" : "切换到中文", #selector(WeaveInputController.toggleMode(_:)))
        menu.addItem(.separator())
        let schemes = submenu("输入方案")
        for (i, scheme) in InputScheme.all.enumerated() where ExtensionRegistry.scheme(scheme.id, enabled: enabledExtensions) {
            let item = add(scheme.name, #selector(WeaveInputController.selectScheme(_:)), to: schemes)
            item.tag = i
            item.state = scheme.id == schema ? .on : .off
            if !hasSchema(scheme.id) { item.action = nil }
        }
        let tools = submenu("工具")
        if enabledExtensions.contains("scheme:hand") { add("手写…", #selector(WeaveInputController.openHandwriting(_:)), to: tools) }
        if enabledExtensions.contains("feature:voice") { add("语音输入…", #selector(WeaveInputController.openVoice(_:)), to: tools) }
        if enabledExtensions.contains("feature:translate") { add("翻译所选文字…", #selector(WeaveInputController.openTranslation(_:)), to: tools) }
        add("重新转换所选文字", #selector(WeaveInputController.reconvertSelection(_:)), to: tools)
        tools.addItem(.separator())
        add("Emoji 与颜文字…", #selector(WeaveInputController.openExpressions(_:)), to: tools)
        if enabledExtensions.contains("feature:stickers") { add("表情收纳袋…", #selector(WeaveInputController.openStickers(_:)), to: tools) }
        add("剪贴板历史…", #selector(WeaveInputController.openClipboard(_:)), to: tools)
        if enabledExtensions.contains("feature:phrases") { add("常用语…", #selector(WeaveInputController.openPhrases(_:)), to: tools) }
        // 互联只在有手机连着或正在传输时出现。 Link appears only with a connected phone or a transfer.
        if enabledExtensions.contains("feature:link") && (!phones.isEmpty || canSend || progress != nil) {
            let link = submenu("互联")
            if !phones.isEmpty { link.addItem(NSMenuItem(title: "已连接 " + phones.joined(separator: "、"), action: nil, keyEquivalent: "")) }
            if canSend {
                add("发送剪贴板到手机", #selector(WeaveInputController.sendClipboard(_:)), to: link)
                add("发送文件到手机…", #selector(WeaveInputController.sendFiles(_:)), to: link)
            }
            if let progress { link.addItem(NSMenuItem(title: progress, action: nil, keyEquivalent: "")) }
            link.addItem(.separator())
            add("互联设置…", #selector(WeaveInputController.openLink(_:)), to: link)
        }
        return menu
    }
}
