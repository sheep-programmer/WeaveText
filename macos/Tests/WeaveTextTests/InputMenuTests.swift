import AppKit
import Testing
import WeaveCore
@testable import WeaveText

@MainActor @Suite struct InputMenuTests {
    /// 菜单里所有项（含子菜单）。 Every item, submenus included.
    private func everything(_ menu: NSMenu) -> [NSMenuItem] {
        menu.items.flatMap { [$0] + ($0.submenu.map(everything) ?? []) }
    }

    @Test func theTopLevelStaysShortAndToolsLiveInSubmenus() {
        let target = NSObject()
        let menu = InputMenu.make(target: target, schema: "pinyin", chinese: true, traditional: false,
                                  hasSchema: { $0 != "wubi86" })
        #expect(menu.items[0].title == "织文设置…")
        #expect(menu.items[0].action == #selector(WeaveInputController.showPreferences(_:)))
        #expect(menu.items[0].target === target)
        #expect(menu.items.filter { !$0.isSeparatorItem }.map(\.title) == ["织文设置…", "切换到英文", "输入方案", "工具"])
        let all = everything(menu)
        let stickers = try! #require(all.first { $0.title == "表情收纳袋…" })
        #expect(stickers.action == #selector(WeaveInputController.openStickers(_:)))
        #expect(stickers.target === target)
        let voice = try! #require(all.first { $0.title == "语音输入…" })
        #expect(voice.action == #selector(WeaveInputController.openVoice(_:)))
        let translation = try! #require(all.first { $0.title == "翻译所选文字…" })
        #expect(translation.action == #selector(WeaveInputController.openTranslation(_:)))
        let schemes = all.filter { $0.action == #selector(WeaveInputController.selectScheme(_:)) }
        #expect(schemes.contains { $0.title == InputScheme.named("pinyin").name && $0.state == .on })
        #expect(all.first { $0.title == InputScheme.named("wubi86").name }?.action == nil)
        #expect(!all.contains { $0.title == "发送剪贴板到手机" || $0.title == "互联" || $0.title == "繁体输出" })
    }

    @Test func disabledExtensionsDisappearFromSubmenusWhileBasicCommandsStay() {
        let menu=InputMenu.make(target:NSObject(),schema:"pinyin",chinese:true,traditional:false,
            hasSchema:{_ in true},phones:["手机"],canSend:true,enabledExtensions:[])
        let all=everything(menu)
        #expect(!all.contains{$0.title == "语音输入…" || $0.title == "手写…" || $0.title == "表情收纳袋…" || $0.title == "翻译所选文字…" || $0.title == "常用语…" || $0.title == "互联" || $0.title == "五笔 86"})
        #expect(all.contains{$0.title == "剪贴板历史…"})
        #expect(all.contains{$0.title == "Emoji 与颜文字…"})
        #expect(all.contains{$0.title == "全拼"})
    }

    @Test func floatingInputPanelsDoNotTakeKeyboardFocus() throws {
        _ = NSApplication.shared
        let panel = InputPanel(contentRect: NSRect(x: 0, y: 0, width: 400, height: 300),
                               styleMask: [.nonactivatingPanel, .titled], backing: .buffered, defer: false)
        #expect(!panel.canBecomeKey && !panel.canBecomeMain)
        let model = VoiceModel()
        let voice = VoiceWindow.panel(model: model)
        #expect(!voice.canBecomeKey && !voice.canBecomeMain)
        #expect(!voice.hidesOnDeactivate)
        #expect(voice.styleMask.contains(.nonactivatingPanel))
        #expect(voice.contentLayoutRect.height > 200)
        let hosting = try #require(voice.contentView as? ClickThroughHostingView<VoiceView>)
        #expect(!hosting.needsPanelToBecomeKey && hosting.acceptsFirstMouse(for: nil))
        voice.close(); panel.close()
    }

    @Test func closingVoiceStopsItWithoutSendingOrDeletingTheDraft() {
        let model = VoiceModel()
        model.text = "hello world"
        model.close()
        #expect(model.state == .idle && model.text == "hello world")
        #expect(!model.commit())
        #expect(model.text == "hello world")
        model.clear()
        #expect(model.text.isEmpty)
    }

    @Test func connectedPhoneCommandsAreAvailableWithoutAStatusItem() {
        let menu = InputMenu.make(target: NSObject(), schema: "pinyin", chinese: false, traditional: true,
                                  hasSchema: { _ in true }, phones: ["手机"], canSend: true, progress: "正在发送 1/2")
        #expect(menu.items.contains { $0.title == "切换到中文" })
        let link = try! #require(menu.items.first { $0.title == "互联" }?.submenu)
        #expect(link.items.contains { $0.title == "已连接 手机" })
        #expect(link.items.contains { $0.title == "发送剪贴板到手机" && $0.action != nil })
        #expect(link.items.contains { $0.title == "发送文件到手机…" && $0.action != nil })
        #expect(link.items.contains { $0.title == "正在发送 1/2" })
    }
}
