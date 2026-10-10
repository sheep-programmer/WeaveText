import Foundation
import Testing
@testable import WeaveCore

/// Deliberately ignores cancellation, so late responses and progress can be exercised deterministically.
private final class ControlledPackFetcher: HTTPFetching, @unchecked Sendable {
    private struct Request {
        let url: URL
        let progress: (@Sendable (FetchProgress) -> Void)?
        var continuation: CheckedContinuation<HTTPResult, Error>?
    }
    private let lock = NSLock()
    private var requests: [Request] = []
    var urls: [URL] { lock.withLock { requests.map(\.url) } }

    func get(_ url: URL, etag: String?, maxBytes: Int,
             progress: (@Sendable (Int64) -> Void)?) async throws -> HTTPResult {
        try await get(url, etag: etag, maxBytes: maxBytes, downloadProgress: { progress?($0.receivedBytes) })
    }

    func get(_ url: URL, etag: String?, maxBytes: Int,
             downloadProgress: (@Sendable (FetchProgress) -> Void)?) async throws -> HTTPResult {
        try await withCheckedThrowingContinuation { continuation in
            lock.withLock { requests.append(Request(url: url, progress: downloadProgress, continuation: continuation)) }
        }
    }

    func report(_ index: Int, _ progress: FetchProgress) {
        let report = lock.withLock { requests[index].progress }
        report?(progress)
    }

    func complete(_ index: Int, _ result: Result<HTTPResult, Error>) {
        let continuation = lock.withLock {
            let continuation = requests[index].continuation
            requests[index].continuation = nil
            return continuation
        }
        continuation?.resume(with: result)
    }

    func drain() {
        let pending = lock.withLock {
            let pending = requests.compactMap(\.continuation)
            for index in requests.indices { requests[index].continuation = nil }
            return pending
        }
        for continuation in pending { continuation.resume(throwing: CancellationError()) }
    }
}

@MainActor @Suite struct DictPackDownloadTests {
    private let body = Data("abc".utf8)
    private let url = URL(string: "https://example.invalid/abc.wvz")!
    private let mirrors = Mirrors(templates: ["{url}", "https://mirror.invalid/{url}"])

    private final class Engine {
        var attached: Set<String> = []
        var installs = 0
    }

    private func store(_ fetcher: ControlledPackFetcher, _ dir: URL, _ engine: Engine, bytes: Int64 = 3) -> DictPackStore {
        let pack = DictPack(id: "abc", name: "测试", bytes: bytes, sha256: PackCheck.sha256Hex(body))
        return DictPackStore(catalog: DictPackCatalog(base: "https://example.invalid/", packs: [pack]),
                             dir: dir, fetcher: fetcher, mirrors: mirrors,
                             attach: { id, _ in engine.attached.insert(id); engine.installs += 1 },
                             detach: { engine.attached.remove($0) }, loaded: { engine.attached })
    }

    private func until(_ condition: () -> Bool) async throws {
        for _ in 0..<200 {
            if condition() { return }
            try await Task.sleep(nanoseconds: 5_000_000)
        }
        try #require(condition())
    }

    @Test func cancelledDownloadCannotOverwriteOrFinishAnImmediateRetry() async throws {
        let dir = try tempDir("packs-retry-generation"), fetcher = ControlledPackFetcher(), engine = Engine()
        let store = store(fetcher, dir, engine)
        defer { store.cancel("abc"); fetcher.drain(); try? FileManager.default.removeItem(at: dir) }
        store.install("abc")
        try await until { fetcher.urls.count == 1 }
        store.cancel("abc")
        #expect(store.downloads["abc"] == nil && store.state("abc") == .notInstalled)
        store.install("abc")
        try await until { fetcher.urls.count == 2 }
        fetcher.report(1, FetchProgress(receivedBytes: 1, totalBytes: 3))
        try await until { store.state("abc") == .downloading(done: 1) }
        fetcher.report(0, FetchProgress(receivedBytes: 3, totalBytes: 3))
        fetcher.complete(0, .success(HTTPResult(status: 200, body: body)))
        try await Task.sleep(nanoseconds: 30_000_000)
        #expect(store.state("abc") == .downloading(done: 1))
        #expect(engine.installs == 0 && !FileManager.default.fileExists(atPath: store.file("abc").path))
        fetcher.complete(1, .success(HTTPResult(status: 200, body: body)))
        try await until { store.state("abc") == .installed }
        #expect(engine.installs == 1 && store.downloads["abc"] == nil)
        #expect(fetcher.urls == [url, url])
    }

    @Test func timeoutSwitchesSourceAndIgnoresOldOrRegressingProgress() async throws {
        let dir = try tempDir("packs-attempt-progress"), fetcher = ControlledPackFetcher(), engine = Engine()
        let store = store(fetcher, dir, engine)
        defer { store.cancel("abc"); fetcher.drain(); try? FileManager.default.removeItem(at: dir) }
        store.install("abc")
        try await until { fetcher.urls.count == 1 }
        fetcher.report(0, FetchProgress(receivedBytes: 2, totalBytes: 999))
        try await until { store.state("abc") == .downloading(done: 2) }
        #expect(store.downloads["abc"]?.progress?.totalBytes == 3)
        fetcher.complete(0, .failure(URLError(.timedOut)))
        try await until { fetcher.urls.count == 2 }
        #expect(store.state("abc") == .downloading(done: nil))
        #expect(store.downloads["abc"]?.source == mirrors.sources(for: url)[1])
        #expect(store.downloads["abc"]?.attempt == 2 && store.downloads["abc"]?.sourceCount == 2)
        #expect(store.downloads["abc"]?.progress == nil)
        fetcher.report(0, FetchProgress(receivedBytes: 3))
        fetcher.report(1, FetchProgress(receivedBytes: 1))
        try await until { store.state("abc") == .downloading(done: 1) }
        fetcher.report(1, FetchProgress(receivedBytes: 0))
        try await Task.sleep(nanoseconds: 30_000_000)
        #expect(store.state("abc") == .downloading(done: 1))
        fetcher.complete(1, .success(HTTPResult(status: 200, body: body)))
        try await until { store.state("abc") == .installed }
        #expect(try Data(contentsOf: store.file("abc")) == body)
    }

    @Test func cancellationErrorDoesNotFallBackOrBecomeANetworkFailure() async throws {
        let dir = try tempDir("packs-cancellation-error"), fetcher = ControlledPackFetcher(), engine = Engine()
        let store = store(fetcher, dir, engine)
        defer { store.cancel("abc"); fetcher.drain(); try? FileManager.default.removeItem(at: dir) }
        store.install("abc")
        try await until { fetcher.urls.count == 1 }
        fetcher.complete(0, .failure(CancellationError()))
        try await until { store.state("abc") == .notInstalled }
        #expect(fetcher.urls == [url] && store.downloads["abc"] == nil)
        #expect(engine.installs == 0)
    }

    @Test func missingCatalogSizeUsesResponseLengthWhenAvailableAndStillVerifiesSHA() async throws {
        let dir = try tempDir("packs-unknown-size"), fetcher = ControlledPackFetcher(), engine = Engine()
        let store = store(fetcher, dir, engine, bytes: 0)
        defer { store.cancel("abc"); fetcher.drain(); try? FileManager.default.removeItem(at: dir) }
        store.install("abc")
        try await until { fetcher.urls.count == 1 }
        fetcher.report(0, FetchProgress(receivedBytes: 1))
        try await until { store.state("abc") == .downloading(done: 1) }
        #expect(store.downloads["abc"]?.progress?.totalBytes == nil)
        fetcher.report(0, FetchProgress(receivedBytes: 2, totalBytes: 3))
        try await until { store.downloads["abc"]?.progress?.totalBytes == 3 }
        fetcher.complete(0, .success(HTTPResult(status: 200, body: Data("abd".utf8))))
        try await until { fetcher.urls.count == 2 }
        #expect(engine.installs == 0 && !FileManager.default.fileExists(atPath: store.file("abc").path))
        fetcher.complete(1, .success(HTTPResult(status: 200, body: body)))
        try await until { store.state("abc") == .installed }
        #expect(try Data(contentsOf: store.file("abc")) == body)
    }

    @Test func badDownloadsLeaveAnExistingFileAndAttachedDictionaryUntouched() async throws {
        let dir = try tempDir("packs-preserve-existing"), fetcher = ControlledPackFetcher(), engine = Engine()
        let store = store(fetcher, dir, engine)
        defer { store.cancel("abc"); fetcher.drain(); try? FileManager.default.removeItem(at: dir) }
        try body.write(to: store.file("abc"), options: .atomic)
        engine.attached = ["abc"]
        store.install("abc")
        try await until { fetcher.urls.count == 1 }
        fetcher.complete(0, .success(HTTPResult(status: 200, body: Data("abd".utf8))))
        try await until { fetcher.urls.count == 2 }
        fetcher.complete(1, .failure(FetchError.tooLarge))
        try await until { store.state("abc") == .failed(DictPackStore.failure) }
        #expect(try Data(contentsOf: store.file("abc")) == body)
        #expect(engine.attached == ["abc"] && engine.installs == 0)
        #expect(store.downloads["abc"] == nil)
    }
}
