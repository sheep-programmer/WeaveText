import Foundation
import Testing
@testable import WeaveCore

@MainActor @Suite struct ExtensionTests {
    @Test func optionalFeaturesPersistAndBasicInputRemainsAvailable() {
        let suite="weave-ext-\(UUID().uuidString)",d=UserDefaults(suiteName:suite)!
        defer {d.removePersistentDomain(forName:suite)}
        let p=Preferences(defaults:d)
        #expect(p.extensionsEnabled == ExtensionRegistry.defaults)
        p.schema="wubi86";p.setExtension("scheme:wubi86",enabled:false)
        p.setExtension("feature:voice",enabled:false)
        let restored=Preferences(defaults:d)
        #expect(restored.schema == "pinyin")
        #expect(!restored.extensionEnabled("feature:voice"))
        #expect(restored.extensionEnabled("feature:translate"))
        restored.setExtension("feature:calc",enabled:false)
        #expect(restored.engineOptions.contains{$0.0 == "features.calculator" && !$0.1})
        #expect(ExtensionRegistry.scheme("shuangpin:xiaohe",enabled:[]))
        #expect(!ExtensionRegistry.scheme("wubi86",enabled:restored.extensionsEnabled))
    }
    @Test func allSharedThemesInstallAndTheActiveThemeFallsBackOnRemoval() throws {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent("weave-ext-\(UUID().uuidString)")
        defer {try? FileManager.default.removeItem(at:root)}
        let store=ExtensionStore(root:root)
        #expect(store.themes.count == 4)
        let themes=store.items.filter{$0.kind=="theme" && $0.source=="bundled"}
        #expect(themes.count == 10)
        for item in themes {try store.install(item);#expect(store.installed(item));#expect(store.theme(item.id) != nil)}
        #expect(store.themes.count == 14)
        let suite="weave-ext-theme-\(UUID().uuidString)",d=UserDefaults(suiteName:suite)!
        defer {d.removePersistentDomain(forName:suite)}
        let prefs=Preferences(defaults:d)
        let item=try #require(themes.first{$0.id=="sakura"})
        prefs.colorTheme=try #require(ColorTheme(rawValue:"sakura"))
        try store.uninstall(item,prefs:prefs)
        #expect(prefs.colorTheme == .fresh && !store.installed(item))
        let base=try #require(store.items.first{$0.key=="theme:fresh"})
        #expect(throws:ExtensionError.self) {try store.uninstall(base,prefs:prefs)}
    }
    @Test func importedThemesPersistAndUnsafeOrBaseIDsAreRejected() throws {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent("weave-ext-import-\(UUID().uuidString)")
        defer {try? FileManager.default.removeItem(at:root)}
        let store=ExtensionStore(root:root)
        let baseURL=ExtensionStore.resourcesRoot.appendingPathComponent("android/app/src/main/assets/styles/theme-fresh.json")
        let json=try String(contentsOf:baseURL,encoding:.utf8)
        let custom=Data(json.replacingOccurrences(of:"\"id\": \"fresh\"",with:"\"id\": \"custom\"").utf8)
        let theme=try store.importTheme(custom)
        #expect(theme.rawValue == "custom")
        let restored=ExtensionStore(root:root)
        #expect(restored.themes.contains(theme))
        #expect(restored.theme("custom")?.colors("accent",fallback:(0,0)).0 == 0x2E6CF6)
        #expect(ColorTheme(rawValue:"../escape") == nil)
        #expect(throws:ExtensionError.self) {try store.importTheme(Data(json.utf8))}
        #expect(throws:ExtensionError.self) {try store.importTheme(Data(repeating:0,count:256*1024+1))}
        #expect(ColorTheme(rawValue:"paper") == .ink && ColorTheme(rawValue:"violet") == .dusk)
    }
}
