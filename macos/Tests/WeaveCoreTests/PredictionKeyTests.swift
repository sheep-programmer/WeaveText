import Testing
@testable import WeaveCore

private func key(_ s: String, code: UInt16 = 0, shift: Bool = false, command: Bool = false) -> KeyInput {
    KeyInput(keyCode: code, characters: s, shift: shift, command: command)
}

private func act(_ k: KeyInput, count: Int = 8, pageSize: Int = 7) -> PredictionAction {
    KeyMapper.predictionAction(for: k, count: count, pageSize: pageSize)
}

@Suite struct PredictionKeyTests {
    @Test func digitsPickWithinThePage() {
        #expect(act(key("1")) == .select(0))
        #expect(act(key("7")) == .select(6))
        // 超出本页或联想个数：收起后照常输入数字。 Past the page or the list: dismiss and type the digit.
        #expect(act(key("8")) == .dismissAndHandle)
        #expect(act(key("4"), count: 3) == .dismissAndHandle)
        #expect(act(key("0")) == .dismissAndHandle)
    }

    @Test func spaceReturnArrowsAndPunctuationDismissThenHandle() {
        #expect(act(key(" ", code: KeyCode.space)) == .dismissAndHandle)
        #expect(act(key("\r", code: KeyCode.returnKey)) == .dismissAndHandle)
        #expect(act(key("", code: KeyCode.left)) == .dismissAndHandle)
        #expect(act(key("", code: KeyCode.down)) == .dismissAndHandle)
        #expect(act(key(",")) == .dismissAndHandle)
        #expect(act(key("!", shift: true)) == .dismissAndHandle)
        #expect(act(key("\u{7f}", code: KeyCode.delete)) == .dismissAndHandle)
    }

    @Test func escapeOnlyDismisses() {
        #expect(act(key("\u{1b}", code: KeyCode.escape)) == .dismiss)
    }

    @Test func lettersAndShortcutsGoOnAsUsual() {
        #expect(act(key("n")) == .dismissAndHandle)
        #expect(act(key("1", command: true)) == .dismissAndHandle)
        // 收起后字母照常开始新的组合。 After dismissing, a letter starts a new composition.
        #expect(KeyMapper.action(for: key("n"), in: KeyContext(composing: false)) == .letter("n"))
    }
}
