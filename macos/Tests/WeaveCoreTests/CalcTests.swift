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

    @Test func extractsUnicodeArithmeticWithoutLosingTheExpressionPrefix() {
        #expect(Calc.expression(before: "共 12＋3＝") == "12＋3")
        #expect(Calc.expression(before: "共 12－3=") == "12－3")
        #expect(Calc.expression(before: "共 12−3") == "12−3")
        #expect(Calc.expression(before: "共 3．5×2÷（1＋2）＝") == "3．5×2÷（1＋2）")
        #expect(Calc.expression(before: "共 ）12＋3＝") == "12＋3")
        #expect(Calc.expression(before: "12＝3＋4＝") == "3＋4")
    }

    @Test func vModeIsPinyinVFollowedByDigitsOrOperators() {
        #expect(Calc.isVMode(preedit: "v", scheme: "pinyin"))
        #expect(Calc.isVMode(preedit: "v12", scheme: "pinyin"))
        #expect(Calc.isVMode(preedit: "v(1", scheme: "pinyin"))
        #expect(!Calc.isVMode(preedit: "vx", scheme: "pinyin"))
        #expect(!Calc.isVMode(preedit: "vX", scheme: "pinyin"))
        #expect(!Calc.isVMode(preedit: "very", scheme: "pinyin"))
        #expect(!Calc.isVMode(preedit: "ni", scheme: "pinyin"))
        #expect(!Calc.isVMode(preedit: "v12", scheme: "wubi86"))
    }

    @Test func vModeAcceptsTheEngineArithmeticCharacters() {
        for c in "0123456789.+-*/()%^=＝×÷＋－−（）．" {
            #expect(Calc.vAccepts(c))
        }
        for c in "xX１２ abc" {
            #expect(!Calc.vAccepts(c))
        }
    }
}

@Suite struct VModeKeyTests {
    private let v = KeyContext(composing: true, vMode: true)

    @Test func digitsAndOperatorsCompose() {
        for c in "0123456789+-*/().%^=＝×÷＋－−（）．" {
            #expect(KeyMapper.action(for: KeyInput(keyCode: 0, characters: String(c)), in: v) == .letter(c))
        }
    }

    @Test func unicodePreeditsKeepDigitsInVMode() {
        for preedit in ["v×2", "v÷2", "v＋2", "v－2", "v−2", "v（1＋2）", "v）", "v．5", "v=", "v＝", "v(1+2)=", "v（1＋2）＝"] {
            let vMode = Calc.isVMode(preedit: preedit, scheme: "pinyin")
            #expect(vMode)
            let ctx = KeyContext(composing: true, vMode: vMode)
            #expect(KeyMapper.action(for: KeyInput(keyCode: 0, characters: "3"), in: ctx) == .letter("3"))
        }
    }

    @Test func nonArithmeticSuffixKeepsOrdinaryCandidateKeys() {
        for preedit in ["v1abc", "v(1+2)x", "v＋2X"] {
            let vMode = Calc.isVMode(preedit: preedit, scheme: "pinyin")
            #expect(!vMode)
            let ctx = KeyContext(composing: true, vMode: vMode)
            #expect(KeyMapper.action(for: KeyInput(keyCode: 0, characters: "1"), in: ctx) == .select(0))
            #expect(KeyMapper.action(for: KeyInput(keyCode: 24, characters: "="), in: ctx) == .pageNext)
        }
    }

    @Test func trailingEqualsGoesToTheEngine() {
        let ctx = KeyContext(composing: true, vMode: Calc.isVMode(preedit: "v(1+2)", scheme: "pinyin"))
        #expect(KeyMapper.action(for: KeyInput(keyCode: 24, characters: "="), in: ctx) == .letter("="))
        #expect(KeyMapper.action(for: KeyInput(keyCode: 24, characters: "＝"), in: ctx) == .letter("＝"))
    }

    @Test func arithmeticTakesPriorityOverCustomCandidateBindings() {
        for slot in CandidateKeySlot.allCases {
            for c in "1+-*/().%^=＝×÷＋－−（）．" {
                var bindings = CandidateKeys()
                bindings.assign(KeyBinding(keyCode: 0, character: String(c), label: String(c)), to: slot)
                let ctx = KeyContext(composing: true, vMode: true, bindings: bindings)
                #expect(KeyMapper.action(for: KeyInput(keyCode: 0, characters: String(c)), in: ctx) == .letter(c))
            }
        }
    }

    @Test func otherKeysKeepTheirMeaning() {
        #expect(KeyMapper.action(for: KeyInput(keyCode: KeyCode.space, characters: " "), in: v) == .commitHighlighted)
        #expect(KeyMapper.action(for: KeyInput(keyCode: KeyCode.returnKey, characters: "\r"), in: v) == .commitRaw)
        #expect(KeyMapper.action(for: KeyInput(keyCode: KeyCode.down, characters: ""), in: v) == .highlightNext)
        #expect(KeyMapper.action(for: KeyInput(keyCode: 0, characters: ","), in: v) == .pagePrevious)
        #expect(KeyMapper.action(for: KeyInput(keyCode: 0, characters: "="), in: KeyContext(composing: true)) == .pageNext)
        #expect(KeyMapper.action(for: KeyInput(keyCode: 0, characters: "-"), in: KeyContext(composing: true)) == .pagePrevious)
        #expect(KeyMapper.action(for: KeyInput(keyCode: 0, characters: "1"), in: KeyContext(composing: true)) == .select(0))

        let bindings = CandidateKeys(expand: KeyBinding(keyCode: 0, character: ",", label: ","))
        let custom = KeyContext(composing: true, vMode: true, bindings: bindings)
        #expect(KeyMapper.action(for: KeyInput(keyCode: 0, characters: ","), in: custom) == .toggleExpand)
        #expect(KeyMapper.action(for: KeyInput(keyCode: 24, characters: "=", command: true), in: v) == .pass)
    }
}
