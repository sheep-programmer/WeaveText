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

    @Test func shortcutsAndEnglishPassThrough() {
        #expect(KeyMapper.action(for: key("c", command: true), in: busy) == .pass)
        #expect(KeyMapper.action(for: key("a", control: true), in: idle) == .pass)
        #expect(KeyMapper.action(for: key("å", option: true), in: idle) == .pass)
        var en = idle
        en.chinese = false
        #expect(KeyMapper.action(for: key("n"), in: en) == .pass)
        #expect(KeyMapper.action(for: key(","), in: en) == .pass)
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
