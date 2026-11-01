import CoreGraphics
import CoreText
import Foundation
import Testing
@testable import WeaveCore

private func rawKey(_ s: String, code: UInt16 = 0) -> KeyInput {
    KeyInput(keyCode: code, characters: s, shift: false, control: false, option: false, command: false, capsLock: false)
}

private func context(composing: Bool, scheme: String) -> KeyContext {
    KeyContext(composing: composing, pageKeys: .both, rawKeys: InputScheme.named(scheme).rawKeys)
}

@Suite struct RawKeySchemeTests {
    @Test func newSchemesAreListedOnceWithStableIDs() {
        let ids = InputScheme.all.map(\.id)
        #expect(Set(ids).count == ids.count)
        #expect(ids.contains("bopomofo") && ids.contains("romaji"))
        #expect(InputScheme.named(InputScheme.keypadNinePrefix + "xiaohe").name == "小鹤九键双拼")
        #expect(InputScheme.named(InputScheme.keypadFourteenPrefix + "xiaohe").name == "小鹤14键双拼")
        #expect(InputScheme.named(InputScheme.keypadNinePrefix + "xiaohe").isKeypadShuangpin)
        #expect(InputScheme.named(InputScheme.keypadNinePrefix + "xiaohe").isShuangpinFamily)
        #expect(!InputScheme.named("romaji").isShuangpinFamily)
    }

    @Test func bopomofoRawKeysCoverTheStandardKeyboard() {
        let keys = InputScheme.named("bopomofo").rawKeys
        #expect(keys.count == 41)
        #expect(keys.contains("1") && keys.contains("3") && keys.contains(",") && keys.contains("-"))
        #expect(InputScheme.named("pinyin").rawKeys.isEmpty)
        #expect(InputScheme.named("romaji").rawKeys.isEmpty)
    }

    @Test func bopomofoKeysReachTheEngineWhileComposing() {
        let ctx = context(composing: true, scheme: "bopomofo")
        // 标点与数字在注音里是符号键，不翻页、不选词、不当标点。 In bopomofo these are symbol keys, not paging, picking or punctuation.
        #expect(KeyMapper.action(for: rawKey(","), in: ctx) == .letter(","))
        #expect(KeyMapper.action(for: rawKey("-"), in: ctx) == .letter("-"))
        #expect(KeyMapper.action(for: rawKey("1"), in: ctx) == .letter("1"))
        #expect(KeyMapper.action(for: rawKey("3"), in: ctx) == .letter("3"))
        #expect(KeyMapper.action(for: rawKey("a"), in: ctx) == .letter("a"))
    }

    @Test func bopomofoStartsOnLettersAndDigitsOnly() {
        let idle = context(composing: false, scheme: "bopomofo")
        #expect(KeyMapper.action(for: rawKey("1"), in: idle) == .letter("1"))
        #expect(KeyMapper.action(for: rawKey("s"), in: idle) == .letter("s"))
        #expect(KeyMapper.action(for: rawKey(","), in: idle) != .letter(","))
        var shifted = rawKey("S")
        shifted.shift = true
        #expect(KeyMapper.action(for: shifted, in: idle) == .pass)
    }

    @Test func keypadNineTakesDigitsAsInputAndKeepsSelectionForOthers() {
        let scheme = InputScheme.keypadNinePrefix + "xiaohe"
        #expect(KeyMapper.action(for: rawKey("8"), in: context(composing: true, scheme: scheme)) == .letter("8"))
        #expect(KeyMapper.action(for: rawKey("8"), in: context(composing: false, scheme: scheme)) == .letter("8"))
        // 1 不在九键分组里，仍然用来选第一个候选。 "1" is not a keypad group, so it still picks the first candidate.
        #expect(KeyMapper.action(for: rawKey("1"), in: context(composing: true, scheme: scheme)) == .select(0))
        // 空格仍然上屏。 Space still commits.
        #expect(KeyMapper.action(for: rawKey(" ", code: KeyCode.space), in: context(composing: true, scheme: scheme)) == .commitHighlighted)
    }

    @Test func pinyinDigitsStillPickCandidates() {
        let pinyin = context(composing: true, scheme: "pinyin")
        #expect(KeyMapper.action(for: rawKey("2"), in: pinyin) == .select(1))
        #expect(KeyMapper.action(for: rawKey("-"), in: pinyin) == .pagePrevious)
    }
}

@Suite struct TextRecognitionTests {
    /// 用 CoreText 把一行英文画成位图。 Draw one line of English into a bitmap with CoreText.
    static func render(_ text: String) -> CGImage? {
        let width = 900, height = 220
        guard let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
                                  space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue) else { return nil }
        ctx.setFillColor(gray: 1, alpha: 1)
        ctx.fill(CGRect(x: 0, y: 0, width: width, height: height))
        let font = CTFontCreateWithName("Helvetica" as CFString, 96, nil)
        let attributes = [
            NSAttributedString.Key(kCTFontAttributeName as String): font,
            NSAttributedString.Key(kCTForegroundColorAttributeName as String): CGColor(gray: 0, alpha: 1),
        ] as [NSAttributedString.Key: Any]
        let line = CTLineCreateWithAttributedString(NSAttributedString(string: text, attributes: attributes))
        ctx.textPosition = CGPoint(x: 40, y: 70)
        CTLineDraw(line, ctx)
        return ctx.makeImage()
    }

    @Test func readsPrintedEnglishFromAnImage() throws {
        let image = try #require(Self.render("HELLO 2026"))
        let text = try TextRecognition.recognize(image, languages: ["en-US"])
        let squashed = text.filter { !$0.isWhitespace }
        #expect(squashed.contains("HELLO"))
        #expect(squashed.contains("2026"))
    }
}
