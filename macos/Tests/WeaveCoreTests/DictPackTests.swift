import Foundation
import Testing
@testable import WeaveCore

private let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../..").standardized
private let catalogFile = root.appendingPathComponent("android/app/src/main/assets/dictpacks.json")
private let abc = Data("abc".utf8)
private let abcSHA = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

@Suite struct DictPackCatalogTests {
    @Test func parsesTheShippedCatalog() throws {
        let c = try #require(DictPackCatalog.parse(Data(contentsOf: catalogFile)))
        #expect(c.base.hasPrefix("https://"))
        #expect(c.packs.count >= 10)
        let med = try #require(c.packs.first { $0.id == "med" })
        #expect(med.name == "医学" && med.words > 10_000 && med.bytes > 0 && med.sha256.count == 64)
        #expect(c.url(for: med)?.absoluteString == c.base + "med.wvz")
        #expect(Set(c.packs.map(\.id)).count == c.packs.count)
    }

    @Test func skipsBadEntries() throws {
        let json = """
        {"base":"https://x/","packs":[
          {"id":"ok","name":"好","words":12345,"bytes":2048,"sha256":"\(abcSHA.uppercased())"},
          {"id":"","sha256":"\(abcSHA)"},
          {"id":"short","sha256":"abcd"},
          {"id":"Bad ID","sha256":"\(abcSHA)"},
          {"id":"nohex","sha256":"\(String(repeating: "z", count: 64))"}
        ]}
        """
        let c = try #require(DictPackCatalog.parse(Data(json.utf8)))
        #expect(c.packs.map(\.id) == ["ok"])
        #expect(c.packs[0].sha256 == abcSHA)
        #expect(c.packs[0].summary == "1.2 万词 · 2 KB")
        #expect(DictPackCatalog.parse(Data("[]".utf8)) == nil)
        #expect(DictPackCatalog.parse(Data(#"{"packs":[]}"#.utf8))?.url(for: c.packs[0]) == nil)
    }

    @Test func formatsLikeAndroid() {
        #expect(PackFormat.words(22570) == "2.3 万词")
        #expect(PackFormat.words(5153) == "5153 词")
        #expect(PackFormat.size(191838) == "188 KB")
        #expect(PackFormat.size(3 << 20) == "3.0 MB")
    }

    @Test func checksSHA256AndSize() throws {
        #expect(PackCheck.sha256Hex(abc) == abcSHA)
        #expect(PackCheck.verify(abc, sha256: abcSHA, bytes: 3))
        #expect(PackCheck.verify(abc, sha256: abcSHA.uppercased(), bytes: 0))
        #expect(!PackCheck.verify(abc, sha256: abcSHA, bytes: 4))
        #expect(!PackCheck.verify(Data("abd".utf8), sha256: abcSHA, bytes: 3))
        let dir = try tempDir("sha")
        defer { try? FileManager.default.removeItem(at: dir) }
        let f = dir.appendingPathComponent("x.wvz")
        try abc.write(to: f)
        #expect(PackCheck.verify(file: f, sha256: abcSHA, bytes: 3))
        #expect(!PackCheck.verify(file: dir.appendingPathComponent("missing"), sha256: abcSHA, bytes: 3))
    }

    /// 本地构建出的词库文件与目录里的校验和一致。 Locally built pack files match the catalog checksums.
    @Test(.enabled(if: FileManager.default.fileExists(atPath: root.path + "/data/build/packs/med.wvz")))
    func builtPacksMatchTheCatalog() throws {
        let c = try #require(DictPackCatalog.parse(Data(contentsOf: catalogFile)))
        let med = try #require(c.packs.first { $0.id == "med" })
        #expect(PackCheck.verify(file: root.appendingPathComponent("data/build/packs/med.wvz"), sha256: med.sha256,
                                 bytes: med.bytes))
    }
}

@MainActor @Suite struct DictPackStoreTests {
    private func store(_ fetcher: StubFetcher, _ dir: URL, log: Log) -> DictPackStore {
        let catalog = DictPackCatalog(base: "https://example.invalid/", packs: [
            DictPack(id: "abc", name: "测试", words: 3, bytes: 3, sha256: abcSHA),
        ])
        return DictPackStore(catalog: catalog, dir: dir, fetcher: fetcher,
                             attach: { log.events.append("attach \($0) \(URL(fileURLWithPath: $1).lastPathComponent)") },
                             detach: { log.events.append("detach \($0)") })
    }

    final class Log { var events: [String] = [] }

    private func settle(_ s: DictPackStore, _ id: String) async {
        for _ in 0..<200 {
            if case .downloading = s.state(id) { try? await Task.sleep(nanoseconds: 5_000_000) } else { return }
        }
    }

    @Test func installsVerifiedFilesAndRemovesThem() async throws {
        let dir = try tempDir("packs")
        defer { try? FileManager.default.removeItem(at: dir) }
        let f = StubFetcher()
        let url = URL(string: "https://example.invalid/abc.wvz")!
        f.enqueue(url, HTTPResult(status: 200, body: abc))
        let log = Log()
        let s = store(f, dir, log: log)
        #expect(s.state("abc") == .notInstalled)
        s.install("abc")
        #expect(s.state("abc") == .downloading(done: nil))
        await settle(s, "abc")
        #expect(s.state("abc") == .installed)
        #expect(log.events == ["attach abc abc.wvz"])
        #expect(try Data(contentsOf: s.file("abc")) == abc)
        #expect(s.installed.map(\.id) == ["abc"])
        #expect(s.remove("abc"))
        #expect(s.state("abc") == .notInstalled)
        #expect(!FileManager.default.fileExists(atPath: s.file("abc").path))
        #expect(log.events.last == "detach abc")
    }

    @Test func notFoundAndBadChecksumFail() async throws {
        let dir = try tempDir("packs-bad")
        defer { try? FileManager.default.removeItem(at: dir) }
        let f = StubFetcher()
        let url = URL(string: "https://example.invalid/abc.wvz")!
        let log = Log()
        let s = store(f, dir, log: log)
        // 发布页还没有这个文件：404。 The release has no such file yet: 404.
        s.install("abc")
        await settle(s, "abc")
        #expect(s.state("abc") == .failed("下载失败，请检查网络后重试"))
        f.enqueue(url, HTTPResult(status: 200, body: Data("abd".utf8)))
        s.install("abc")
        await settle(s, "abc")
        #expect(s.state("abc") == .failed(DictPackStore.failure))
        #expect(log.events.isEmpty)
        #expect(!FileManager.default.fileExists(atPath: s.file("abc").path))
    }

    @Test func cancelReturnsToNotInstalled() async throws {
        let dir = try tempDir("packs-cancel")
        defer { try? FileManager.default.removeItem(at: dir) }
        let f = StubFetcher()
        f.enqueue(URL(string: "https://example.invalid/abc.wvz")!, HTTPResult(status: 200, body: abc))
        let log = Log()
        let s = store(f, dir, log: log)
        s.install("abc")
        s.cancel("abc")
        #expect(s.state("abc") == .notInstalled)
        try await Task.sleep(nanoseconds: 50_000_000)
        #expect(s.state("abc") == .notInstalled)
        #expect(log.events.isEmpty)
    }
}
