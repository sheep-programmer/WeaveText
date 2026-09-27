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

    /// 上屏后直接退格撤销学习；中间输入法自己写过字（逗号）就不撤销。 A backspace right after a commit undoes the
    /// learning; with text the IME wrote in between (a comma) it doesn't.
    @Test(.enabled(if: FileManager.default.fileExists(atPath: dataDir + "/pinyin.wvz")))
    func backspaceUndoesLearningUnlessTheChainIsBroken() throws {
        let user = try tempDir("undo")
        defer { try? FileManager.default.removeItem(at: user) }
        let e = try #require(WeaveSession(dataDir: dataDir, userDir: user.path))
        e.setSchema("pinyin")
        e.setOption("candidates.prediction", false)
        var word = ""
        func count() -> Int { e.userWords(query: word).first { $0.text == word }?.count ?? 0 }
        func learn() throws -> Int {
            for c in "shijian" { _ = e.input(c) }
            word = try #require(e.snapshot().candidates.first?.text)
            e.select(0)
            #expect(e.snapshot().commit == word)
            return count()
        }
        let learned = try learn()
        #expect(learned >= 1)
        // 上屏 + 退格：撤销。 Commit + backspace: undone.
        #expect(!e.backspace())
        #expect(count() == learned - 1)
        // 上屏 + 逗号（输入法写的）+ 退格：保留。 Commit + a comma the IME wrote + backspace: kept.
        let again = try learn()
        e.breakChain()
        #expect(!e.backspace())
        #expect(count() == again)
    }

    @Test(.enabled(if: FileManager.default.fileExists(atPath: dataDir + "/pinyin.wvz")))
    func hotWordsAndPacksThroughTheCABI() async throws {
        let fixtures = URL(fileURLWithPath: dataDir).appendingPathComponent("../../core/weave-engine/tests/fixtures").standardized
        let user = try tempDir("hot")
        defer { try? FileManager.default.removeItem(at: user) }
        let e = try #require(WeaveSession(dataDir: dataDir, userDir: user.path))
        let tsv = fixtures.appendingPathComponent("hotwords.tsv").path
        let sig = fixtures.appendingPathComponent("hotwords.tsv.sig").path
        // 数据目录旁的 packs/ 也会自动载入，所以跟起点比。 packs/ beside the data dir loads too: compare to the start.
        let base = e.packIDs()
        #expect(!base.contains(CloudWords.packID))
        #expect(e.loadHotwords(tsv: tsv, sig: sig) == 2)
        #expect(e.packIDs() == base.union([CloudWords.packID]))
        // 签名对不上：-1，已挂上的旧热词不动。 A mismatching signature: -1, the attached words stay.
        let bad = user.appendingPathComponent("bad.tsv")
        try (String(contentsOfFile: tsv, encoding: .utf8) + "多余\tduo yu\t1\t\n").write(to: bad, atomically: true, encoding: .utf8)
        #expect(e.loadHotwords(tsv: bad.path, sig: sig) == -1)
        #expect(e.packIDs() == base.union([CloudWords.packID]))
        #expect(e.unloadPack(id: CloudWords.packID))
        #expect(e.packIDs() == base)

        // 经 CloudWords 用真内核验签（下载换成本地夹具）。 CloudWords with the real engine verifying fixture bytes.
        let f = StubFetcher()
        f.enqueue(CloudWords.url, HTTPResult(status: 200, body: try Data(contentsOf: URL(fileURLWithPath: tsv)), etag: "x"))
        f.enqueue(CloudWords.url.appendingPathExtension("sig"), HTTPResult(status: 200, body: try Data(contentsOf: URL(fileURLWithPath: sig))))
        let suite = "weave-mac-hot-\(UUID().uuidString)"
        let cloud = await MainActor.run {
            CloudWords(defaults: UserDefaults(suiteName: suite)!, dir: user.appendingPathComponent("cloud"), fetcher: f,
                       load: { e.loadHotwords(tsv: $0, sig: $1) }, unload: { e.unloadPack(id: CloudWords.packID) },
                       loaded: { e.packIDs().contains(CloudWords.packID) })
        }
        let task = await MainActor.run { () -> Task<Void, Never>? in
            cloud.setEnabled(true)
            return cloud.refreshNow()
        }
        await task?.value
        let status = await MainActor.run { cloud.status }
        #expect(status.words == 2 && status.attached)

        let pack = URL(fileURLWithPath: dataDir).appendingPathComponent("packs/med.wvz").path
        if FileManager.default.fileExists(atPath: pack) {
            #expect(e.loadPack(id: "med", path: pack))
            #expect(e.packIDs().contains("med"))
            #expect(e.unloadPack(id: "med"))
            #expect(!e.packIDs().contains("med"))
        }
    }
}
