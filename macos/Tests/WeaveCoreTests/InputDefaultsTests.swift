import Foundation
import Testing
@testable import WeaveCore

@Suite struct InputDefaultsTests {
    @Test func freshAndExistingDefaultsWithoutOptInUseLiteralEnglishAndNoPredictions() {
        let name = "weave-input-defaults-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        defaults.set("shuangpin:xiaohe", forKey: "schema")
        let prefs = Preferences(defaults: defaults)
        #expect(!prefs.prediction && !prefs.englishCompletion)
        #expect(prefs.autocorrect && prefs.learning && prefs.autoPair)
        #expect(prefs.pinyinHint == .off)
        #expect(!prefs.clipboardRecord && prefs.voiceEngines==["system"])
        #expect(prefs.engineOptions.contains { $0.0 == "candidates.prediction" && !$0.1 })
        #expect(KeyMapper.action(for: KeyInput(keyCode: 0, characters: "r"),
                                 in: KeyContext(composing: false, chinese: false, englishCompletion: prefs.englishCompletion)) == .pass)
    }
    @Test func annotationsDefaultOffAndKeepAnExplicitOnChoice() {
        let name="weave-tone-defaults-\(UUID().uuidString)",d=UserDefaults(suiteName:name)!
        defer {d.removePersistentDomain(forName:name)}
        #expect(Preferences(defaults:d).pinyinHint == .off)
        #expect(Preferences(defaults:d).engineOptions.contains { $0.0 == "candidates.pinyin" && !$0.1 })
        d.set("plain",forKey:"pinyinHint")
        #expect(Preferences(defaults:d).pinyinHint == .off)
        let p=Preferences(defaults:d);p.pinyinHint = .toned
        #expect(Preferences(defaults:d).pinyinHint == .toned)
    }
    @Test func correctionLearningAndWubiSwitchesPersist() {
        let name="weave-parity-settings-\(UUID().uuidString)",d=UserDefaults(suiteName:name)!
        defer {d.removePersistentDomain(forName:name)}
        let p=Preferences(defaults:d)
        p.autocorrect=false;p.learning=false;p.wubiAutoCommit=false;p.wubiPinyinLookup=false;p.wubiCompletion=false;p.autoPair=false
        let next=Preferences(defaults:d)
        #expect(!next.autocorrect && !next.learning && !next.autoPair)
        #expect(!next.wubiAutoCommit && !next.wubiPinyinLookup && !next.wubiCompletion)
        #expect(next.engineOptions.contains {$0.0=="input.autocorrect" && !$0.1})
    }

    @Test func explicitOptInsAndOptOutsPersistIndependently() {
        let name = "weave-input-opt-in-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        let prefs = Preferences(defaults: defaults)
        prefs.prediction = true
        var restored = Preferences(defaults: defaults)
        #expect(restored.prediction && !restored.englishCompletion)
        prefs.englishCompletion = true
        prefs.prediction = false
        restored = Preferences(defaults: defaults)
        #expect(!restored.prediction && restored.englishCompletion)
        prefs.englishCompletion = false
        #expect(!Preferences(defaults: defaults).englishCompletion)
    }
}
