import Foundation
import Testing
@testable import WeaveCore

private func key(_ s: String, code: UInt16 = 0, shift: Bool = false, command: Bool = false,
                 control: Bool = false, option: Bool = false, caps: Bool = false) -> KeyInput {
    KeyInput(keyCode: code, characters: s, shift: shift, control: control, option: option, command: command,
             capsLock: caps)
}

private let idle = KeyContext(composing: false)
private let busy = KeyContext(composing: true)

@Suite struct KeyMappingTests {
    @Test func lettersGoToTheEngine() {
        #expect(KeyMapper.action(for: key("n"), in: idle) == .letter("n"))
        #expect(KeyMapper.action(for: key("h"), in: busy) == .letter("h"))
    }

    @Test func shortcutsPassAndEnglishLettersOfferCompletions() {
        #expect(KeyMapper.action(for: key("c", command: true), in: busy) == .pass)
        #expect(KeyMapper.action(for: key("a", control: true), in: idle) == .pass)
        #expect(KeyMapper.action(for: key("å", option: true), in: idle) == .pass)
        var en = idle
        en.chinese = false
        #expect(KeyMapper.action(for: key("n"), in: en) == .letter("n"))
        #expect(KeyMapper.action(for: key(","), in: en) == .pass)
        en.composing = true
        #expect(KeyMapper.action(for: key(" ", code: KeyCode.space), in: en) == .commitEnglishWord)
        #expect(KeyMapper.action(for: key(","), in: en) == .punctuation(","))
        #expect(KeyMapper.action(for: key("1"), in: en) == .punctuation("1"))
        #expect(KeyMapper.action(for: key("H", shift: true), in: en) == .letter("H"))
        #expect(KeyMapper.action(for: key("", code: KeyCode.left), in: en) == .finishEnglishAndPass)
    }

    @Test func capsLockAndShiftTypeCapitals() {
        #expect(KeyMapper.action(for: key("N", caps: true), in: idle) == .pass)
        #expect(KeyMapper.action(for: key("N", shift: true), in: idle) == .pass)
    }

    @Test func idleKeysPassExceptPunctuation() {
        #expect(KeyMapper.action(for: key(" ", code: KeyCode.space), in: idle) == .pass)
        #expect(KeyMapper.action(for: key("\r", code: KeyCode.returnKey), in: idle) == .pass)
        #expect(KeyMapper.action(for: key("1"), in: idle) == .pass)
        #expect(KeyMapper.action(for: key(","), in: idle) == .punctuation(","))
        #expect(KeyMapper.action(for: key("\"", shift: true), in: idle) == .punctuation("\""))
        #expect(KeyMapper.action(for: key("-"), in: idle) == .pass)
    }

    @Test func composingEditKeys() {
        #expect(KeyMapper.action(for: key(" ", code: KeyCode.space), in: busy) == .commitHighlighted)
        #expect(KeyMapper.action(for: key("\r", code: KeyCode.returnKey), in: busy) == .commitRaw)
        #expect(KeyMapper.action(for: key("\u{1b}", code: KeyCode.escape), in: busy) == .clear)
        #expect(KeyMapper.action(for: key("\u{7f}", code: KeyCode.delete), in: busy) == .backspace)
        #expect(KeyMapper.action(for: key("", code: KeyCode.left), in: busy) == .highlightPrevious)
        #expect(KeyMapper.action(for: key("", code: KeyCode.up), in: busy) == .highlightPrevious)
        #expect(KeyMapper.action(for: key("", code: KeyCode.right), in: busy) == .highlightNext)
        #expect(KeyMapper.action(for: key("", code: KeyCode.down), in: busy) == .highlightNext)
        #expect(KeyMapper.action(for: key("", code: KeyCode.pageDown), in: busy) == .pageNext)
    }

    @Test func digitsSelectWithinThePage() {
        #expect(KeyMapper.action(for: key("1"), in: busy) == .select(0))
        #expect(KeyMapper.action(for: key("7"), in: busy) == .select(6))
        #expect(KeyMapper.action(for: key("8"), in: busy) == .swallow)
        #expect(KeyMapper.action(for: key("0"), in: busy) == .swallow)
        var nine = busy
        nine.pageSize = 9
        #expect(KeyMapper.action(for: key("9"), in: nine) == .select(8))
    }

    @Test func pageKeysOnlyWhileComposing() {
        #expect(KeyMapper.action(for: key("-"), in: busy) == .pagePrevious)
        #expect(KeyMapper.action(for: key("="), in: busy) == .pageNext)
        #expect(KeyMapper.action(for: key(","), in: busy) == .pagePrevious)
        #expect(KeyMapper.action(for: key("."), in: busy) == .pageNext)
        var minus = busy
        minus.pageKeys = .minusEqual
        #expect(KeyMapper.action(for: key(","), in: minus) == .punctuation(","))
        #expect(KeyMapper.action(for: key("="), in: minus) == .pageNext)
        var comma = busy
        comma.pageKeys = .commaPeriod
        #expect(KeyMapper.action(for: key("-"), in: comma) == .punctuation("-"))
        #expect(KeyMapper.action(for: key("."), in: comma) == .pageNext)
    }

    @Test func separatorsAreTriedByTheEngineFirst() {
        #expect(KeyMapper.action(for: key("'"), in: busy) == .punctuation("'"))
        #expect(KeyMapper.action(for: key(";"), in: busy) == .punctuation(";"))
    }
}

@Suite struct ShiftTapTests {
    @Test func aQuickTapToggles() {
        var d = ShiftTapDetector()
        let v1 = d.flagsChanged(shift: true, otherModifiers: false, at: 0); #expect(!v1)
        let v2 = d.flagsChanged(shift: false, otherModifiers: false, at: 0.2); #expect(v2)
    }

    @Test func shiftAsAModifierDoesNotToggle() {
        var d = ShiftTapDetector()
        _ = d.flagsChanged(shift: true, otherModifiers: false, at: 0)
        d.keyDown()
        let v3 = d.flagsChanged(shift: false, otherModifiers: false, at: 0.1); #expect(!v3)
    }

    @Test func holdingTooLongOrChordsDoNotToggle() {
        var d = ShiftTapDetector()
        _ = d.flagsChanged(shift: true, otherModifiers: false, at: 0)
        let v4 = d.flagsChanged(shift: false, otherModifiers: false, at: 1.0); #expect(!v4)
        _ = d.flagsChanged(shift: true, otherModifiers: false, at: 2)
        _ = d.flagsChanged(shift: true, otherModifiers: true, at: 2.05)
        let v5 = d.flagsChanged(shift: false, otherModifiers: false, at: 2.1); #expect(!v5)
        let v6 = d.flagsChanged(shift: false, otherModifiers: false, at: 3); #expect(!v6)
    }
}

@Suite struct IdlePassTests {
    private func key(_ s: String, _ code: UInt16 = 0, command: Bool = false, option: Bool = false) -> KeyInput {
        KeyInput(keyCode: code, characters: s, option: option, command: command)
    }

    /// 退格总是先给内核（上屏后没写别的字时由内核撤销学习）。 Backspace always reaches the engine first.
    @Test func backspaceGoesToTheEngine() {
        #expect(KeyMapper.idlePass(for: key("\u{7f}", KeyCode.delete)) == .backspace)
        #expect(KeyMapper.idlePass(for: key("\u{7f}", KeyCode.delete, option: true)) == .backspace)
    }

    /// 会写字或挪光标的键断开连续上屏。 Keys that write or move the caret break the chain.
    @Test func writingAndMovingKeysBreakTheChain() {
        for k in [key(","), key(" ", KeyCode.space), key("1"), key("@"), key("\r", KeyCode.returnKey),
                  key("\t", KeyCode.tab), key("", KeyCode.left), key("v", command: true), key("A")] {
            #expect(KeyMapper.idlePass(for: k) == .breakChain)
        }
        #expect(KeyMapper.idlePass(for: key("\u{1b}", KeyCode.escape)) == .none)
    }
}

@Suite struct CandidateShortcutTests {
    private func key(_ code: UInt16, _ chars: String) -> KeyInput { KeyInput(keyCode: code, characters: chars) }

    @Test func customKeysBeatTheDefaultsWhileComposing() {
        var keys = CandidateKeys()
        keys.assign(KeyBinding(keyCode: 33, character: "[", label: "["), to: .pagePrevious)
        keys.assign(KeyBinding(keyCode: KeyCode.right, character: nil, label: "→"), to: .pageNext)
        let ctx = KeyContext(composing: true, bindings: keys)
        #expect(KeyMapper.action(for: key(33, "["), in: ctx) == .pagePrevious)
        // → 默认是高亮右移，改绑后成了下一页。 → used to move the highlight; rebound, it pages.
        #expect(KeyMapper.action(for: key(KeyCode.right, ""), in: ctx) == .pageNext)
        // 没改的键照旧。 Untouched keys keep their meaning.
        #expect(KeyMapper.action(for: key(KeyCode.left, ""), in: ctx) == .highlightPrevious)
        // 不在组合时自定义键不拦截。 Not composing: custom keys don't intercept.
        let idle = KeyContext(composing: false, bindings: keys)
        #expect(KeyMapper.action(for: key(KeyCode.right, ""), in: idle) == .pass)
    }

    @Test func expandHasNoDefaultKeyButCanBeBound() {
        var keys = CandidateKeys()
        let ctx = KeyContext(composing: true, bindings: keys)
        #expect(KeyMapper.action(for: key(KeyCode.down, ""), in: ctx) == .highlightNext)
        keys.assign(KeyBinding(keyCode: KeyCode.down, character: nil, label: "↓"), to: .expand)
        #expect(KeyMapper.action(for: key(KeyCode.down, ""), in: KeyContext(composing: true, bindings: keys)) == .toggleExpand)
    }

    @Test func recordingRefusesKeysTheInputNeeds() {
        for (code, chars) in [(UInt16(36), "\r"), (53, "\u{1b}"), (51, "\u{7f}"), (49, " "), (0, "a"), (18, "1")] {
            let made = KeyBinding.make(from: key(code, chars))
            #expect(made.binding == nil && made.refusal != nil)
        }
        #expect(KeyBinding.make(from: KeyInput(keyCode: 33, characters: "[", command: true)).binding == nil)
        #expect(KeyBinding.make(from: key(33, "[")).binding?.label == "[")
        #expect(KeyBinding.make(from: key(KeyCode.pageDown, "")).binding?.label == "Page Down")
    }

    @Test func assigningTheSameKeyMovesItAndSettingsRoundTrip() throws {
        var keys = CandidateKeys()
        let bracket = KeyBinding(keyCode: 30, character: "]", label: "]")
        keys.assign(bracket, to: .pageNext)
        keys.assign(bracket, to: .highlightNext)
        #expect(keys.pageNext == nil && keys.highlightNext == bracket)
        let suite = UserDefaults(suiteName: "weave-keys-\(getpid())")!
        defer { suite.removePersistentDomain(forName: "weave-keys-\(getpid())") }
        let prefs = Preferences(defaults: suite)
        prefs.candidateKeys = keys
        #expect(Preferences(defaults: suite).candidateKeys == keys)
        prefs.candidateKeys = CandidateKeys()
        #expect(Preferences(defaults: suite).candidateKeys.isDefault)
    }
}
