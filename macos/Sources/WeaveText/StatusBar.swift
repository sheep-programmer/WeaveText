import AppKit
import Combine
import WeaveCore

/// 菜单栏常驻图标：左键快捷菜单，右键直接打开设置；手机已连接时图标带圆点，文件可以拖到图标上发给手机。
/// Menu bar item: left click for the menu, right click for settings; a dot while a phone is connected, and files
/// dropped on the icon are sent to the phone.
final class StatusBar: NSObject, NSMenuDelegate {
    static let shared = StatusBar()

    private var item: NSStatusItem?
    private var host: EngineHost { .shared }
    private var prefs: Preferences { host.prefs }
    private var link: LinkService { .shared }
    private var linkWatch: AnyCancellable?
    private var badged: Bool?
    /// 菜单开着时实时更新的进度项。 The progress item, updated live while the menu is open.
    private weak var progressItem: NSMenuItem?

    func start() {
        update()
        NotificationCenter.default.addObserver(forName: Preferences.didChange, object: nil, queue: .main) { [weak self] _ in
            self?.update()
        }
        NotificationCenter.default.addObserver(forName: EngineHost.modeDidChange, object: nil, queue: .main) { [weak self] _ in
            self?.update()
        }
        linkWatch = link.objectWillChange.receive(on: DispatchQueue.main).sink { [weak self] _ in
            // objectWillChange 在改动之前发出，下一轮再读。 objectWillChange fires before the change; read on the next turn.
            DispatchQueue.main.async { self?.update() }
        }
    }

    private func update() {
        guard prefs.showStatusItem else {
            if let item { NSStatusBar.system.removeStatusItem(item) }
            item = nil
            badged = nil
            return
        }
        if item == nil {
            let it = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
            if let button = it.button {
                button.target = self
                button.action = #selector(clicked(_:))
                button.sendAction(on: [.leftMouseUp, .rightMouseUp])
                let drop = DropTargetView(frame: button.bounds)
                drop.autoresizingMask = [.width, .height]
                drop.canAccept = { [weak self] in self?.link.canSend == true }
                drop.onDrop = { [weak self] urls in self?.link.sendFiles(urls) }
                button.addSubview(drop)
            }
            item = it
        }
        let phone = link.state.connected.first
        if badged != (phone != nil) {
            badged = phone != nil
            item?.button?.image = Logo.statusImage(badge: phone != nil)
        }
        // 英文模式时图标变淡。 The icon dims in English mode.
        item?.button?.appearsDisabled = !host.chinese
        var tip = host.chinese ? "织文 · 中文" : "织文 · 英文"
        if let phone { tip += "\n已连接 \(phone.displayName) · 可把文件拖到这里发送" }
        item?.button?.toolTip = tip
        if let progressItem {
            if let title = link.queueTitle { progressItem.title = title } else { progressItem.title = "发送完成" }
        }
    }

    @objc private func clicked(_ sender: NSStatusBarButton) {
        let event = NSApp.currentEvent
        if event?.type == .rightMouseUp || event?.modifierFlags.contains(.control) == true {
            SettingsWindow.shared.show()
            return
        }
        guard let item else { return }
        item.menu = buildMenu()
        item.button?.performClick(nil)
    }

    func menuDidClose(_ menu: NSMenu) {
        // 摘掉菜单，否则右键也会弹出它。 Detach, or right clicks would open it too.
        item?.menu = nil
    }

    private func buildMenu() -> NSMenu {
        let menu = NSMenu()
        menu.delegate = self
        let mode = add(menu, host.chinese ? "切换到英文" : "切换到中文", #selector(toggleMode))
        mode.image = nil

        let schemes = NSMenu()
        for (i, s) in InputScheme.all.enumerated() {
            let it = add(schemes, s.name, #selector(selectScheme(_:)))
            it.tag = i
            it.state = s.id == prefs.schema ? .on : .off
            if host.engine?.hasSchema(s.id) == false { it.action = nil }
        }
        let schemeItem = NSMenuItem(title: "输入方案", action: nil, keyEquivalent: "")
        schemeItem.submenu = schemes
        menu.addItem(schemeItem)

        add(menu, "繁体输出", #selector(toggleTraditional)).state = prefs.traditional ? .on : .off
        if prefs.linkEnabled { addLinkItems(menu) }
        menu.addItem(.separator())
        add(menu, "设置…", #selector(openSettings), key: ",")
        add(menu, "关于织文", #selector(openAbout))
        menu.addItem(.separator())
        add(menu, "退出", #selector(quit), key: "q")
        return menu
    }

    /// 互联：连接状态与「发送到手机」。 WeaveLink: connection status and "Send to phone".
    private func addLinkItems(_ menu: NSMenu) {
        menu.addItem(.separator())
        let phones = link.state.connected
        let status = NSMenuItem(title: phones.isEmpty ? "手机未连接" : "已连接 " + phones.map(\.displayName).joined(separator: "、"),
                                action: nil, keyEquivalent: "")
        status.isEnabled = false
        menu.addItem(status)

        let send = NSMenu()
        send.autoenablesItems = false
        let canSend = link.canSend
        add(send, "发送剪贴板", #selector(sendClipboard)).isEnabled = canSend
        add(send, "发送文件…", #selector(sendFiles)).isEnabled = canSend
        if let title = link.queueTitle {
            send.addItem(.separator())
            let p = NSMenuItem(title: title, action: nil, keyEquivalent: "")
            p.isEnabled = false
            send.addItem(p)
            progressItem = p
        }
        if !canSend {
            send.addItem(.separator())
            let hint = NSMenuItem(title: "手机打开织文并连上同一个 Wi-Fi 后可用", action: nil, keyEquivalent: "")
            hint.isEnabled = false
            send.addItem(hint)
        }
        let sendItem = NSMenuItem(title: "发送到手机", action: nil, keyEquivalent: "")
        sendItem.submenu = send
        menu.addItem(sendItem)
        // 顶层也显示进度，不用展开子菜单。 Progress on the top level too, no need to open the submenu.
        if let title = link.queueTitle {
            let p = NSMenuItem(title: title, action: nil, keyEquivalent: "")
            p.isEnabled = false
            menu.addItem(p)
            progressItem = p
        }
    }

    @discardableResult
    private func add(_ menu: NSMenu, _ title: String, _ action: Selector, key: String = "") -> NSMenuItem {
        let it = NSMenuItem(title: title, action: action, keyEquivalent: key)
        it.target = self
        menu.addItem(it)
        return it
    }

    @objc private func toggleMode() { host.toggleChinese() }

    @objc private func selectScheme(_ sender: NSMenuItem) {
        guard InputScheme.all.indices.contains(sender.tag) else { return }
        prefs.schema = InputScheme.all[sender.tag].id
    }

    @objc private func toggleTraditional() { prefs.traditional.toggle() }
    @objc private func openSettings() { SettingsWindow.shared.show() }
    @objc private func openAbout() { SettingsWindow.shared.show(page: .about) }
    @objc private func sendClipboard() { link.sendClipboard() }

    @objc private func sendFiles() {
        // 等菜单收起再弹窗。 Open the panel after the menu has closed.
        DispatchQueue.main.async { self.link.chooseFiles() }
    }

    @objc private func quit() {
        host.engine?.flush()
        link.shutdown()
        NSApp.terminate(nil)
    }
}

/// 盖在菜单栏按钮上的透明层：接收拖进来的文件，鼠标点击照常交给按钮。
/// A transparent layer over the status button: accepts dropped files, hands clicks to the button as usual.
final class DropTargetView: NSView {
    var canAccept: () -> Bool = { false }
    var onDrop: ([URL]) -> Void = { _ in }

    override init(frame: NSRect) {
        super.init(frame: frame)
        registerForDraggedTypes([.fileURL])
    }

    required init?(coder: NSCoder) { nil }

    override func mouseDown(with event: NSEvent) { superview?.mouseDown(with: event) }
    override func mouseUp(with event: NSEvent) { superview?.mouseUp(with: event) }
    override func rightMouseDown(with event: NSEvent) { superview?.rightMouseDown(with: event) }
    override func rightMouseUp(with event: NSEvent) { superview?.rightMouseUp(with: event) }

    private func files(_ info: NSDraggingInfo) -> [URL] {
        info.draggingPasteboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true])
            as? [URL] ?? []
    }

    override func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation {
        guard canAccept(), !files(sender).isEmpty else { return [] }
        (superview as? NSButton)?.highlight(true)
        return .copy
    }

    override func draggingExited(_ sender: NSDraggingInfo?) {
        (superview as? NSButton)?.highlight(false)
    }

    override func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        (superview as? NSButton)?.highlight(false)
        let urls = files(sender)
        guard canAccept(), !urls.isEmpty else { return false }
        onDrop(urls)
        return true
    }
}
