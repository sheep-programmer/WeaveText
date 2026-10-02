import AppKit
import Testing
import WeaveCore
@testable import WeaveText

@MainActor @Suite struct InputMenuTests {
    @Test func settingsAndSchemesLiveInTheSystemInputMenu() {
        let target = NSObject()
        let menu = InputMenu.make(target: target, schema: "pinyin", chinese: true, traditional: false,
                                  hasSchema: { $0 != "wubi86" })
        #expect(menu.items[0].title == "织文键盘设置…")
        #expect(menu.items[0].action == #selector(WeaveInputController.showPreferences(_:)))
        #expect(menu.items[0].target === target)
        let stickers = try! #require(menu.items.first { $0.title == "表情收纳袋…" })
        #expect(stickers.action == #selector(WeaveInputController.openStickers(_:)))
        #expect(stickers.target === target)
        let schemes = menu.items.filter { $0.action == #selector(WeaveInputController.selectScheme(_:)) }
        #expect(schemes.contains { $0.title == InputScheme.named("pinyin").name && $0.state == .on })
        #expect(menu.items.first { $0.title == InputScheme.named("wubi86").name }?.action == nil)
        #expect(!menu.items.contains { $0.title == "发送剪贴板到手机" })
    }

    @Test func connectedPhoneCommandsAreAvailableWithoutAStatusItem() {
        let menu = InputMenu.make(target: NSObject(), schema: "pinyin", chinese: false, traditional: true,
                                  hasSchema: { _ in true }, phones: ["手机"], canSend: true, progress: "正在发送 1/2")
        #expect(menu.items.contains { $0.title == "切换到中文" })
        #expect(menu.items.contains { $0.title == "繁体输出" && $0.state == .on })
        #expect(menu.items.contains { $0.title == "发送剪贴板到手机" && $0.action != nil })
        #expect(menu.items.contains { $0.title == "发送文件到手机…" && $0.action != nil })
        #expect(menu.items.contains { $0.title == "正在发送 1/2" })
    }
}
