import Foundation
import Testing
@testable import WeaveCore

@Suite struct ThemePreferencesTests {
    @Test func colorAndAppearanceChoicesPersistIndependently() {
        let suite="weave-theme-\(UUID().uuidString)",defaults=UserDefaults(suiteName:suite)!
        defer{defaults.removePersistentDomain(forName:suite)}
        let prefs=Preferences(defaults:defaults)
        #expect(prefs.colorTheme == .fresh && prefs.appearance == .system)
        prefs.colorTheme = .mint;prefs.appearance = .dark
        let restored=Preferences(defaults:defaults)
        #expect(restored.colorTheme == .mint && restored.appearance == .dark)
        restored.appearance = .light
        #expect(Preferences(defaults:defaults).colorTheme == .mint)
        #expect(!restored.prediction && !restored.englishCompletion && restored.pinyinHint == .toned)
    }
}
