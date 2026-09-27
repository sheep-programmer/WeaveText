import AppKit
import WeaveCore

/// 菜单栏常驻图标：左键快捷菜单，右键直接打开设置。 Menu bar item: left click for the menu, right click for settings.
final class StatusBar: NSObject, NSMenuDelegate {
    static let shared = StatusBar()

    private var item: NSStatusItem?
    private var host: EngineHost { .shared }
    private var prefs: Preferences { host.prefs }

    func start() {
        update()
        NotificationCenter.default.addObserver(forName: Preferences.didChange, object: nil, queue: .main) { [weak self] _ in
            self?.update()
        }
        NotificationCenter.default.addObserver(forName: EngineHost.modeDidChange, object: nil, queue: .main) { [weak self] _ in
            self?.update()
        }
    }

    private func update() {
        guard prefs.showStatusItem else {
            if let item { NSStatusBar.system.removeStatusItem(item) }
            item = nil
            return
        }
        if item == nil {
            let it = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
            it.button?.image = Logo.statusImage()
            it.button?.target = self
            it.button?.action = #selector(clicked(_:))
            it.button?.sendAction(on: [.leftMouseUp, .rightMouseUp])
            item = it
        }
        // 英文模式时图标变淡。 The icon dims in English mode.
        item?.button?.appearsDisabled = !host.chinese
        item?.button?.toolTip = host.chinese ? "织文 · 中文" : "织文 · 英文"
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
        }
        let schemeItem = NSMenuItem(title: "输入方案", action: nil, keyEquivalent: "")
        schemeItem.submenu = schemes
        menu.addItem(schemeItem)

        add(menu, "繁体输出", #selector(toggleTraditional)).state = prefs.traditional ? .on : .off
        menu.addItem(.separator())
        add(menu, "设置…", #selector(openSettings), key: ",")
        add(menu, "关于织文", #selector(openAbout))
        menu.addItem(.separator())
        add(menu, "退出", #selector(quit), key: "q")
        return menu
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

    @objc private func quit() {
        host.engine?.flush()
        NSApp.terminate(nil)
    }
}
