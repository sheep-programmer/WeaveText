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
        #expect(e.hasSchema("pinyin"))
        #expect(!e.hasSchema("no.such"))
    }

    @Test(.enabled(if: FileManager.default.fileExists(atPath: dataDir + "/pinyin.wvz")))
    func vModeDatesAndEval() throws {
        let user = FileManager.default.temporaryDirectory.appendingPathComponent("weave-mac-v-\(getpid())")
        try FileManager.default.createDirectory(at: user, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: user) }
        let e = try #require(WeaveSession(dataDir: dataDir, userDir: user.path))
        e.setSchema("pinyin")
        #expect(e.input("v"))
        // 只有 v 时组合串也以 v 开头，数字才会进内核。 A lone v shows as "v", so digits go to the engine.
        #expect(Calc.isVMode(preedit: e.snapshot().preedit, scheme: "pinyin"))
        for c in "1234" { #expect(e.input(c)) }
        var s = e.snapshot()
        #expect(s.preedit == "v1234")
        let money = try #require(s.candidates.first { $0.text == "壹仟贰佰叁拾肆元整" })
        #expect(!money.comment.isEmpty)
        e.clear()
        for c in "v(128+32)*4" { #expect(e.input(c)) }
        s = e.snapshot()
        #expect(s.candidates.first?.text == "640")
        e.commitFirst()
        #expect(e.snapshot().commit == "640")

        e.setUTCOffset(minutes: 480)
        for c in "rq" { _ = e.input(c) }
        #expect(e.snapshot().candidates.contains { $0.text.contains("年") || $0.text.contains("-") })
        e.clear()

        #expect(WeaveSession.eval("128*4") == "512")
        #expect(WeaveSession.eval("(1+2)/4") == "0.75")
        #expect(WeaveSession.eval("你好") == nil)
        #expect(WeaveSession.eval("12") == nil)
    }

    @Test(.enabled(if: FileManager.default.fileExists(atPath: dataDir + "/follow.wvz")))
    func predictsAfterACommitAndUndoesOnBackspace() throws {
        let user = FileManager.default.temporaryDirectory.appendingPathComponent("weave-mac-p-\(getpid())")
        try FileManager.default.createDirectory(at: user, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: user) }
        let e = try #require(WeaveSession(dataDir: dataDir, userDir: user.path))
        e.setSchema("pinyin")
        #expect(e.setOption("candidates.prediction", true))
        for c in "jintian" { #expect(e.input(c)) }
        let i = try #require(e.snapshot().candidates.firstIndex { $0.text == "今天" })
        #expect(e.select(i))
        let s = e.snapshot()
        #expect(s.commit == "今天")
        #expect(!s.composing && s.predicting)
        #expect(s.candidates.contains { ["晚上", "早上", "下午"].contains($0.text) })
        // 选联想词：上屏并接着联想。 Picking a prediction commits it and predicts again.
        #expect(e.select(0))
        #expect(!e.snapshot().commit.isEmpty)
        e.dismissPredictions()
        #expect(!e.snapshot().predicting)
        // 上屏后退格：内核返回 false（由应用删字）。 A backspace after a commit returns false; the app deletes.
        #expect(!e.backspace())
        #expect(e.setOption("candidates.prediction", false))
        for c in "jintian" { _ = e.input(c) }
        e.commitFirst()
        #expect(!e.snapshot().predicting)
    }
}
