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
        /// 像内核一样：验签失败时旧的 cloud 保持挂着。 As the engine: a failed verification keeps the old "cloud".
        var attached = false
        func load(_ tsv: String, _ sig: String) -> Int {
            loads.append(URL(fileURLWithPath: tsv).lastPathComponent)
            let body = (try? String(contentsOfFile: tsv, encoding: .utf8)) ?? ""
            guard good.contains(body) else { return -1 }
            attached = true
            return 2
        }
    }

    private func make(_ f: StubFetcher, _ e: Engine, mirrors: Mirrors = Mirrors(),
                      clock: @escaping () -> Date) throws -> (CloudWords, URL, UserDefaults) {
        let dir = try tempDir("cloud")
        let suite = "weave-mac-cloud-\(UUID().uuidString)"
        let d = UserDefaults(suiteName: suite)!
        let c = CloudWords(defaults: d, dir: dir.appendingPathComponent("cloud"), fetcher: f, mirrors: mirrors,
                           load: e.load, unload: { e.unloads += 1; e.attached = false }, loaded: { e.attached },
                           now: clock)
        return (c, dir, d)
    }

    final class DeferredFetcher:HTTPFetching,@unchecked Sendable {
        private let lock=NSLock()
        private var pending:[CheckedContinuation<HTTPResult,Error>]=[]
        var count:Int {lock.withLock {pending.count}}
        func get(_ url:URL,etag:String?,maxBytes:Int,progress:(@Sendable(Int64)->Void)?) async throws->HTTPResult {
            try await withCheckedThrowingContinuation {continuation in lock.withLock {pending.append(continuation)}}
        }
        func complete(_ index:Int,_ body:Data) {
            let continuation=lock.withLock {pending[index]}
            continuation.resume(returning:HTTPResult(status:200,body:body))
        }
    }
    @Test func cancellingOldUpdateDoesNotHideTheNewLoadingIndicator() async throws {
        let fetcher=DeferredFetcher();let engine=Engine();let dir=try tempDir("cloud-cancel")
        let suite="weave-cloud-cancel-\(UUID().uuidString)";let defaults=UserDefaults(suiteName:suite)!
        defer {try? FileManager.default.removeItem(at:dir);defaults.removePersistentDomain(forName:suite)}
        let cloud=CloudWords(defaults:defaults,dir:dir,fetcher:fetcher,mirrors:Mirrors(),load:engine.load,
            unload:{engine.attached=false},loaded:{engine.attached})
        let body=Data("#! version 2\n".utf8);engine.good=[String(decoding:body,as:UTF8.self)]
        cloud.setEnabled(true);let old=try #require(cloud.refreshNow())
        while fetcher.count<1 {await Task.yield()}
        cloud.setEnabled(false);cloud.setEnabled(true);let next=try #require(cloud.refreshNow())
        while fetcher.count<2 {await Task.yield()}
        fetcher.complete(0,body);await old.value
        #expect(cloud.status.updating && cloud.status.enabled)
        fetcher.complete(1,body)
        while fetcher.count<3 {await Task.yield()}
        fetcher.complete(2,Data("signature".utf8));await next.value
        #expect(cloud.status.attached && !cloud.status.updating && cloud.status.error==nil)
    }

    @Test func disablingTheModuleStopsWorkAndKeepsTheUsersSetting() throws {
        let fetcher=StubFetcher(),engine=Engine()
        let (cloud,dir,defaults)=try make(fetcher,engine,clock:Date.init)
        defer {try? FileManager.default.removeItem(at:dir)}
        defaults.set(true,forKey:"cloudWords")
        cloud.moduleEnabled=false
        #expect(!cloud.enabled && !cloud.status.enabled)
        #expect(cloud.refreshNow() == nil)
        #expect(defaults.bool(forKey:"cloudWords"))
        #expect(engine.unloads == 1)
        #expect(fetcher.requests.isEmpty)
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
        #expect(c.status.attached && !c.status.needsRetry)
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

        // 200 但签名不对：临时文件删掉，内核里旧版本一直挂着（不再重新载入），词数不变。 200 with a bad signature:
        // temp files go, the old version stays attached in the engine (no reload), the count stays.
        let checked = now
        now += 24 * 3600
        f.enqueue(tsvURL, HTTPResult(status: 200, body: Data("tampered".utf8), etag: "\"e2\""))
        f.enqueue(sigURL, HTTPResult(status: 200, body: Data("sig2".utf8)))
        await c.refreshNow()?.value
        #expect(c.status.error == CloudWords.failure && c.status.summary() == "更新失败，稍后会自动重试")
        #expect(e.loads.count == 2 && e.loads.last == "hotwords.tsv.new")
        #expect(c.status.attached && c.status.needsRetry)
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

    /// 文件在但内核没挂上：提示重试，重试时不带 ETag 重新下载。 Files there but not attached: ask for a retry,
    /// which downloads again without the ETag.
    @Test func storedFilesThatFailToLoadAskForARetry() async throws {
        let f = StubFetcher()
        let e = Engine()
        let v1 = "#! version 7\n"
        e.good = [v1]
        let (c, dir, _) = try make(f, e, clock: Date.init)
        defer { try? FileManager.default.removeItem(at: dir) }
        f.enqueue(tsvURL, HTTPResult(status: 200, body: Data(v1.utf8), etag: "\"e1\""))
        f.enqueue(sigURL, HTTPResult(status: 200, body: Data("s".utf8)))
        c.setEnabled(true)
        await c.refreshNow()?.value
        #expect(c.status.attached)
        // 下次启动时文件坏了。 At the next start the file is corrupt.
        e.attached = false
        try Data("corrupt".utf8).write(to: c.tsv)
        c.attach()
        #expect(c.status.notLoaded && c.status.needsRetry && c.status.summary() == CloudWords.notLoaded)
        f.enqueue(tsvURL, HTTPResult(status: 200, body: Data(v1.utf8), etag: "\"e1\""))
        f.enqueue(sigURL, HTTPResult(status: 200, body: Data("s".utf8)))
        await c.refreshNow()?.value
        #expect(f.requests[f.requests.count - 2] == StubFetcher.Request(url: tsvURL, etag: nil))
        #expect(c.status.attached && !c.status.needsRetry && c.status.error == nil)
    }

    /// 直连失败后换镜像，热词与签名走同一个镜像；镜像给了坏签名就再换下一个。 After the direct URL fails, mirrors
    /// are tried, words and signature from the same one; a mirror with a bad signature moves on to the next.
    @Test func fallsBackThroughTheMirrors() async throws {
        let f = StubFetcher()
        let e = Engine()
        let v1 = "#! version 9\n"
        e.good = [v1]
        let mirrors = Mirrors(templates: ["{url}", "https://m1.invalid/{url}", "https://m2.invalid/{url}"])
        let (c, dir, _) = try make(f, e, mirrors: mirrors, clock: Date.init)
        defer { try? FileManager.default.removeItem(at: dir) }
        let m1 = URL(string: "https://m1.invalid/" + tsvURL.absoluteString)!
        let m2 = URL(string: "https://m2.invalid/" + tsvURL.absoluteString)!
        let m2sig = URL(string: "https://m2.invalid/" + sigURL.absoluteString)!
        f.enqueue(tsvURL, error: URLError(.timedOut))
        f.enqueue(m1, HTTPResult(status: 200, body: Data("tampered".utf8)))
        f.enqueue(URL(string: "https://m1.invalid/" + sigURL.absoluteString)!, HTTPResult(status: 200, body: Data("x".utf8)))
        f.enqueue(m2, HTTPResult(status: 200, body: Data(v1.utf8)))
        f.enqueue(m2sig, HTTPResult(status: 200, body: Data("s".utf8)))
        c.setEnabled(true)
        await c.refreshNow()?.value
        #expect(f.requests.map(\.url).suffix(2) == [m2, m2sig])
        #expect(f.requests.count == 5)
        #expect(c.status.version == "9" && c.status.error == nil && c.status.attached)
    }
}
