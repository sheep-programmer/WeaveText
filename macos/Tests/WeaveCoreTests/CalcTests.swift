import Testing
@testable import WeaveCore

@Suite struct CalcTests {
    @Test func takesTheExpressionBeforeTheCaret() {
        #expect(Calc.expression(before: "单价 128*4") == "128*4")
        #expect(Calc.expression(before: "单价 128*4=") == "128*4")
        #expect(Calc.expression(before: "(128+32)*4") == "(128+32)*4")
        #expect(Calc.expression(before: "共 3.5×2") == "3.5×2")
        #expect(Calc.expression(before: "你好") == nil)
        #expect(Calc.expression(before: "a 12") == nil)
        #expect(Calc.expression(before: "") == nil)
    }

    @Test func vModeIsPinyinVFollowedByDigitsOrOperators() {
        #expect(Calc.isVMode(preedit: "v", scheme: "pinyin"))
        #expect(Calc.isVMode(preedit: "v12", scheme: "pinyin"))
        #expect(Calc.isVMode(preedit: "v(1", scheme: "pinyin"))
        #expect(!Calc.isVMode(preedit: "very", scheme: "pinyin"))
        #expect(!Calc.isVMode(preedit: "ni", scheme: "pinyin"))
        #expect(!Calc.isVMode(preedit: "v12", scheme: "wubi86"))
    }
}

@Suite struct VModeKeyTests {
    private let v = KeyContext(composing: true, vMode: true)

    @Test func digitsAndOperatorsCompose() {
        for c in "0123456789+-*/().%^" {
            #expect(KeyMapper.action(for: KeyInput(keyCode: 0, characters: String(c)), in: v) == .letter(c))
        }
    }

    @Test func otherKeysKeepTheirMeaning() {
        #expect(KeyMapper.action(for: KeyInput(keyCode: KeyCode.space, characters: " "), in: v) == .commitHighlighted)
        #expect(KeyMapper.action(for: KeyInput(keyCode: KeyCode.returnKey, characters: "\r"), in: v) == .commitRaw)
        #expect(KeyMapper.action(for: KeyInput(keyCode: KeyCode.down, characters: ""), in: v) == .highlightNext)
        #expect(KeyMapper.action(for: KeyInput(keyCode: 0, characters: "="), in: v) == .pageNext)
        #expect(KeyMapper.action(for: KeyInput(keyCode: 0, characters: "1"), in: KeyContext(composing: true)) == .select(0))
    }
}
