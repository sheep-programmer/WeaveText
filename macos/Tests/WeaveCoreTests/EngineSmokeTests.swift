import Foundation
import Testing
@testable import WeaveCore

private let dataDir = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent().appendingPathComponent("../../../data/build").standardized.path

@Suite(.serialized) struct EngineSmokeTests {
    @Test(.enabled(if: FileManager.default.fileExists(atPath: dataDir + "/pinyin.wvz")))
    func typesNihao() throws {
        let user = FileManager.default.temporaryDirectory.appendingPathComponent("weave-mac-\(getpid())")
        try FileManager.default.createDirectory(at: user, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: user) }
        let e = try #require(WeaveSession(dataDir: dataDir, userDir: user.path))
        #expect(e.setSchema("pinyin"))
        for c in "nihao" { #expect(e.input(c)) }
        let s = e.snapshot()
        #expect(s.composing)
        #expect(s.candidates.first?.text == "你好")
        #expect(e.candidates(offset: 0, limit: 3).first?.text == "你好")
        e.commitFirst()
        #expect(e.snapshot().commit == "你好")
        #expect(!e.isComposing)
        #expect(e.setOption("fuzzy.z_zh", true))
        #expect(!e.setOption("no.such", true))
        #expect(e.setSchema("shuangpin:xiaohe"))
        #expect(e.setSchema("wubi86"))
        #expect(e.setSchema("english"))
    }
}
