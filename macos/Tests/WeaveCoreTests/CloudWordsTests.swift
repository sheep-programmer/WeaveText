import Foundation
import Testing
@testable import WeaveCore

private let tsvURL = CloudWords.url
private let sigURL = CloudWords.url.appendingPathExtension("sig")

@MainActor @Suite struct CloudWordsTests {
    final class Engine {
        /// 路径 → 返回值；没列出的 -1（验签失败）。 Path → result; unlisted paths fail verification (-1).
        var loads: [String] = []
        var unloads = 0
        var good: Set<String> = []
        func load(_ tsv: String, _ sig: String) -> Int {
            loads.append(URL(fileURLWithPath: tsv).lastPathComponent)
            let body = (try? String(contentsOfFile: tsv, encoding: .utf8)) ?? ""
            return good.contains(body) ? 2 : -1
        }
    }

    private func make(_ f: StubFetcher, _ e: Engine, clock: @escaping () -> Date) throws -> (CloudWords, URL, UserDefaults) {
        let dir = try tempDir("cloud")
        let suite = "weave-mac-cloud-\(UUID().uuidString)"
        let d = UserDefaults(suiteName: suite)!
        let c = CloudWords(defaults: d, dir: dir.appendingPathComponent("cloud"), fetcher: f, load: e.load,
                           unload: { e.unloads += 1 }, now: clock)
        return (c, dir, d)
    }

    @Test func offByDefaultAndDoesNothing() throws {
        let f = StubFetcher()
        let (c, dir, _) = try make(f, Engine(), clock: Date.init)
        defer { try? FileManager.default.removeItem(at: dir) }
        #expect(!c.status.enabled)
        #expect(c.refreshNow() == nil)
        c.refreshIfStale()
        #expect(f.requests.isEmpty)
    }

    @Test func downloadsThenNotModifiedThenBadSignatureKeepsTheOld() async throws {
        let f = StubFetcher()
        let e = Engine()
        var now = Date(timeIntervalSince1970: 1_790_000_000)
        let (c, dir, _) = try make(f, e, clock: { now })
        defer { try? FileManager.default.removeItem(at: dir) }
        let v1 = "#! weavetext-hotwords 1\n#! version 2026092701\n织文\tzhi wen\t300\t\n"
        e.good = [v1]

        // 200：下载、验签、换上。 200: download, verify, install.
        f.enqueue(tsvURL, HTTPResult(status: 200, body: Data(v1.utf8), etag: "\"e1\""))
        f.enqueue(sigURL, HTTPResult(status: 200, body: Data("sig1".utf8)))
        c.setEnabled(true)
        #expect(c.status.updating && c.status.summary() == "正在更新…")
        await c.refreshNow()?.value
        #expect(c.status.words == 2 && c.status.version == "2026092701" && c.status.error == nil)
        #expect(c.status.checkedAt == now)
        #expect(try String(contentsOf: c.tsv, encoding: .utf8) == v1)
        #expect(e.loads == ["hotwords.tsv.new"])
        #expect(c.status.summary(timeZone: TimeZone(identifier: "Asia/Shanghai")!).hasPrefix("2 个词 · "))

        // 不到一天：不检查。 Less than a day: no check.
        now += 3600
        c.refreshIfStale()
        #expect(f.requests.count == 2)

        // 304：带上 ETag，不下载也不重新载入。 304: the ETag goes along; nothing is downloaded or reloaded.
        now += 24 * 3600
        f.enqueue(tsvURL, HTTPResult(status: 304))
        c.refreshIfStale()
        await c.refreshNow()?.value
        #expect(f.requests.last == StubFetcher.Request(url: tsvURL, etag: "\"e1\""))
        #expect(f.requests.count == 3)
        #expect(e.loads.count == 1)
        #expect(c.status.checkedAt == now && c.status.words == 2)

        // 200 但签名不对：临时文件删掉，旧版本重新挂上，词数不变。 200 with a bad signature: temp files go, the old
        // version is attached again, the count stays.
        let checked = now
        now += 24 * 3600
        f.enqueue(tsvURL, HTTPResult(status: 200, body: Data("tampered".utf8), etag: "\"e2\""))
        f.enqueue(sigURL, HTTPResult(status: 200, body: Data("sig2".utf8)))
        await c.refreshNow()?.value
        #expect(c.status.error == CloudWords.failure && c.status.summary() == "更新失败，稍后会自动重试")
        #expect(e.loads.suffix(2) == ["hotwords.tsv.new", "hotwords.tsv"])
        #expect(try String(contentsOf: c.tsv, encoding: .utf8) == v1)
        #expect(!FileManager.default.fileExists(atPath: c.tsv.path + ".new"))
        #expect(c.status.words == 2 && c.status.checkedAt == checked)

        // 网络错误同样保留旧版本。 A network error keeps the old version too.
        f.enqueue(tsvURL, error: URLError(.notConnectedToInternet))
        await c.refreshNow()?.value
        #expect(c.status.error == CloudWords.failure)
        #expect(FileManager.default.fileExists(atPath: c.sig.path))

        // 关闭：卸下并删掉文件。 Off: detach and delete the files.
        c.setEnabled(false)
        #expect(e.unloads == 1)
        #expect(!FileManager.default.fileExists(atPath: c.tsv.path))
        #expect(!c.status.enabled && c.status.words == 0 && c.status.checkedAt == nil && c.status.error == nil)
    }

    @Test func attachLoadsStoredFilesOnlyWhenOn() async throws {
        let f = StubFetcher()
        let e = Engine()
        let v1 = "#! version 7\n"
        e.good = [v1]
        let (c, dir, _) = try make(f, e, clock: Date.init)
        defer { try? FileManager.default.removeItem(at: dir) }
        c.attach()
        #expect(e.loads.isEmpty)
        f.enqueue(tsvURL, HTTPResult(status: 200, body: Data(v1.utf8)))
        f.enqueue(sigURL, HTTPResult(status: 200, body: Data("s".utf8)))
        c.setEnabled(true)
        await c.refreshNow()?.value
        #expect(c.status.version == "7")
        c.attach()
        #expect(e.loads == ["hotwords.tsv.new", "hotwords.tsv"])
    }
}
