import Testing
@testable import WeaveCore

@Suite struct PunctuationTests {
    @Test func fullWidthTable() {
        var p = Punctuation()
        let pairs: [(Character, String)] = [
            (",", "，"), (".", "。"), ("?", "？"), ("!", "！"), (":", "："), (";", "；"),
            ("(", "（"), (")", "）"), ("<", "《"), (">", "》"), ("\\", "、"), ("^", "……"), ("_", "——"),
        ]
        for (ascii, full) in pairs { let v7 = p.convert(ascii); #expect(v7 == full) }
    }

    @Test func quotesAlternate() {
        var p = Punctuation()
        let v8 = p.convert("\""); #expect(v8 == "“")
        let v9 = p.convert("'"); #expect(v9 == "‘")
        let v10 = p.convert("'"); #expect(v10 == "’")
        let v11 = p.convert("\""); #expect(v11 == "”")
        let v12 = p.convert("\""); #expect(v12 == "“")
        p.reset()
        let v13 = p.convert("'"); #expect(v13 == "‘")
        let v14 = p.convert("\""); #expect(v14 == "“")
    }

    @Test func numbersKeepHalfWidthSeparators() {
        var p = Punctuation()
        let v15 = p.convert(".", afterDigit: true); #expect(v15 == ".")
        let v16 = p.convert(",", afterDigit: true); #expect(v16 == ",")
        let v17 = p.convert(":", afterDigit: true); #expect(v17 == ":")
        let v18 = p.convert("?", afterDigit: true); #expect(v18 == "？")
    }

    @Test func mappedSet() {
        #expect(Punctuation.isMapped(","))
        #expect(Punctuation.isMapped("\""))
        #expect(!Punctuation.isMapped("-"))
        #expect(!Punctuation.isMapped("a"))
    }
}
