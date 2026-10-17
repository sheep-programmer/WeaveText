import Foundation
import Testing
@testable import WeaveCore

@Suite struct SnapshotTests {
    @Test func decodesTheEngineShape() throws {
        let json = """
        {"commit":"","preedit":"ni hao","composing":true,"total":42,
         "candidates":[{"text":"你好","comment":"","user":false},{"text":"拟好","comment":"ni hao","user":true}],
         "pinyinOptions":[],"schema":"pinyin"}
        """
        let s = try #require(Snapshot.decode(json))
        #expect(s.composing && s.preedit == "ni hao" && s.total == 42 && s.schema == "pinyin")
        #expect(s.candidates == [Candidate(text: "你好"), Candidate(text: "拟好", comment: "ni hao", user: true)])
    }

    @Test func cloudAndLearnedFlagsAreIndependent() throws {
        let s=try #require(Snapshot.decode(#"{"candidates":[{"text":"云词","cloud":true,"user":false},{"text":"学过的云词","cloud":true,"user":true},{"text":"本地词"}]}"#))
        #expect(s.candidates[0].cloud && !s.candidates[0].user)
        #expect(s.candidates[1].cloud && s.candidates[1].user)
        #expect(!s.candidates[2].cloud)
    }
    @Test func pronunciationAndOtherCandidateNotesAreIndependent() throws {
        let s=try #require(Snapshot.decode(#"{"candidates":[{"text":"你好","pinyin":"nǐ hǎo","comment":"已固定","user":true},{"text":"银行","pinyin":"yín háng","cloud":true}]}"#))
        #expect(s.candidates[0].pinyin == "nǐ hǎo" && s.candidates[0].comment == "已固定")
        #expect(s.candidates[1].pinyin == "yín háng" && s.candidates[1].cloud)
    }
    @Test func decodesPredictions() throws {
        let json = #"{"commit":"今天","preedit":"","composing":false,"predicting":true,"total":2,"candidates":[{"text":"晚上"},{"text":"下午"}]}"#
        let s = try #require(Snapshot.decode(json))
        #expect(s.predicting && !s.composing && s.candidates.map(\.text) == ["晚上", "下午"])
        #expect(Snapshot.decode(#"{"composing":true}"#)?.predicting == false)
    }

    @Test func missingFieldsDefault() throws {
        let s = try #require(Snapshot.decode(#"{"commit":"你好"}"#))
        #expect(s.commit == "你好" && !s.composing && s.candidates.isEmpty)
        #expect(Snapshot.decode("not json") == nil)
    }

    @Test func userWords() throws {
        let w = try JSONDecoder().decode([UserWord].self, from: Data(#"[{"pinyin":"zhi wen","text":"织文","count":3}]"#.utf8))
        #expect(w == [UserWord(pinyin: "zhi wen", text: "织文", count: 3)])
    }

    @Test func decodesCorrectionMarks() throws {
        let json = #"{"preedit":"nihao","composing":true,"marks":[{"start":1,"end":3,"kind":"swap","removed":""},{"start":4,"end":4,"kind":"delete","removed":"x"}]}"#
        let s = try #require(Snapshot.decode(json))
        #expect(s.marks == [PreeditMark(start: 1, end: 3, kind: .swap), PreeditMark(start: 4, end: 4, kind: .delete, removed: "x")])
        // 删除类没有字母可标；其余按 UTF-16 范围。 A deletion has nothing to mark; the rest map to UTF-16 ranges.
        #expect(PreeditMark.ranges(s.marks, in: s.preedit) == [NSRange(location: 1, length: 2)])
        #expect(Snapshot.decode(#"{"composing":true}"#)?.marks == [])
        // 越界的标记被截断，不崩。 Out-of-range marks are clamped.
        #expect(PreeditMark.ranges([PreeditMark(start: 3, end: 9, kind: .replace)], in: "abcd") == [NSRange(location: 3, length: 1)])
    }

    @Test func onlyOneHanCharacterCountsAsASingle() {
        #expect(Candidate(text: "你").isSingleHan)
        #expect(Candidate(text: "𠀀").isSingleHan, "extension B is still one character")
        #expect(!Candidate(text: "你好").isSingleHan)
        #expect(!Candidate(text: "a").isSingleHan)
        #expect(!Candidate(text: "👌").isSingleHan)
        #expect(!Candidate(text: "").isSingleHan)
    }

    @Test func aClickInTheMarkedTextMapsToAnEngineIndex() {
        let marked = NSRange(location: 10, length: 6)
        #expect(Snapshot.markedClickIndex(13, markedRange: marked, preedit: "ni'hao") == 3, "document index")
        #expect(Snapshot.markedClickIndex(2, markedRange: marked, preedit: "ni'hao") == 2, "relative index")
        #expect(Snapshot.markedClickIndex(40, markedRange: marked, preedit: "ni'hao") == nil)
        #expect(Snapshot.markedClickIndex(NSNotFound, markedRange: marked, preedit: "ni'hao") == nil)
        // 𠀀 占两个 UTF-16 单元，内核按一个字计。 𠀀 is two UTF-16 units but one engine char.
        let none = NSRange(location: NSNotFound, length: 0)
        #expect(Snapshot.markedClickIndex(3, markedRange: none, preedit: "𠀀hao") == 2)
        #expect(Snapshot.markedClickIndex(1, markedRange: none, preedit: "𠀀hao") == 0)
    }
}
