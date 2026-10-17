import Foundation
import Testing
@testable import WeaveCore

/// 按应用记住中/英（Mac 端）：切过的应用还原、没切过的不参与、上限内丢最早的、可整体清除。
/// Per-app Chinese/English memory on macOS: a switched app is restored, apps never switched in take no part, the
/// oldest entry falls off past the limit, and everything can be cleared at once.
@Suite struct PerAppModeTests {
    private func fresh(_ tag: String) -> (String, UserDefaults, Preferences) {
        let name = "weave-app-mode-\(tag)-\(UUID().uuidString)"
        let d = UserDefaults(suiteName: name)!
        return (name, d, Preferences(defaults: d))
    }

    @Test func theModeSwitchedInOneAppComesBackWhenReturning() {
        let (name, d, p) = fresh("return")
        defer { d.removePersistentDomain(forName: name) }
        #expect(p.perAppMemory)
        #expect(p.appMode("com.a") == nil)
        p.rememberAppMode("com.a", chinese: false)
        p.rememberAppMode("com.b", chinese: true)
        #expect(p.appMode("com.a") == false)
        #expect(p.appMode("com.b") == true)
        #expect(p.appMode("com.c") == nil)
        // 换过一次的重新排到最前，取值跟着改。 Re-remembering an app moves it to the front and updates the value.
        p.rememberAppMode("com.a", chinese: true)
        #expect(p.appMode("com.a") == true)
        #expect(p.appModes.order.prefix(2).elementsEqual(["com.a", "com.b"]))
        // 存了盘，重开还是这些。 It survives a reload.
        #expect(Preferences(defaults: d).appMode("com.a") == true)
    }

    @Test func onlyTheMostRecentAppsAreKept() {
        let (name, d, p) = fresh("limit")
        defer { d.removePersistentDomain(forName: name) }
        for i in 0..<(Preferences.AppModes.limit + 5) { p.rememberAppMode("com.app\(i)", chinese: i % 2 == 0) }
        #expect(p.appMemoryCount == Preferences.AppModes.limit)
        #expect(p.appMode("com.app0") == nil)          // 最老的连同取值一起丢掉。 The oldest is gone with its value.
        #expect(p.appMode("com.app4") == nil)
        #expect(p.appMode("com.app\(Preferences.AppModes.limit + 4)") != nil)
        #expect(Preferences(defaults: d).appMemoryCount == Preferences.AppModes.limit)
    }

    @Test func clearingAndTurningTheSettingOffLeaveTheStoredValues() {
        let (name, d, p) = fresh("clear")
        defer { d.removePersistentDomain(forName: name) }
        p.rememberAppMode("com.a", chinese: false)
        p.clearAppMemory()
        #expect(p.appMemoryCount == 0 && p.appMode("com.a") == nil)
        // 空包名不记。 No bundle id, nothing remembered.
        p.rememberAppMode("", chinese: true)
        p.rememberAppMode(nil, chinese: true)
        #expect(p.appMemoryCount == 0)
        // 关掉开关只影响以后要不要还原／记，不删已有的记录。 Turning the switch off stops future reads and writes only.
        p.rememberAppMode("com.b", chinese: false)
        p.perAppMemory = false
        #expect(!Preferences(defaults: d).perAppMemory)
        #expect(Preferences(defaults: d).appMode("com.b") == false)
    }
}
