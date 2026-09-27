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

    @Test func missingFieldsDefault() throws {
        let s = try #require(Snapshot.decode(#"{"commit":"你好"}"#))
        #expect(s.commit == "你好" && !s.composing && s.candidates.isEmpty)
        #expect(Snapshot.decode("not json") == nil)
    }

    @Test func userWords() throws {
        let w = try JSONDecoder().decode([UserWord].self, from: Data(#"[{"pinyin":"zhi wen","text":"织文","count":3}]"#.utf8))
        #expect(w == [UserWord(pinyin: "zhi wen", text: "织文", count: 3)])
    }
}
