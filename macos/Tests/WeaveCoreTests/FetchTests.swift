import Foundation
import Testing
@testable import WeaveCore

private final class FetchFixture: @unchecked Sendable {
    let url = URL(string: "https://\(UUID().uuidString.lowercased()).fetch.test/file")!
    let status: Int
    let body: Data
    let length: Int?
    let finish: Bool
    private let lock = NSLock()
    private var stopped = false
    private var seenRequest: URLRequest?
    private var values: [FetchProgress] = []

    init(status: Int = 200, body: Data = Data("abc".utf8), length: Int? = nil, finish: Bool = true) {
        self.status = status; self.body = body; self.length = length; self.finish = finish
        FetchProtocol.register(self)
    }

    var wasStopped: Bool { lock.withLock { stopped } }
    var request: URLRequest? { lock.withLock { seenRequest } }
    var progress: [FetchProgress] { lock.withLock { values } }
    func stop() { lock.withLock { stopped = true } }
    func receive(_ request: URLRequest) { lock.withLock { seenRequest = request } }
    func report(_ progress: FetchProgress) { lock.withLock { values.append(progress) } }
    func fetcher() -> URLSessionFetcher {
        let config = URLSessionFetcher.configuration()
        config.protocolClasses = [FetchProtocol.self]
        return URLSessionFetcher(configuration: config)
    }
}

private final class FetchProtocol: URLProtocol, @unchecked Sendable {
    private static let lock = NSLock()
    private static var fixtures: [URL: FetchFixture] = [:]
    static func register(_ fixture: FetchFixture) { lock.withLock { fixtures[fixture.url] = fixture } }
    static func unregister(_ fixture: FetchFixture) { lock.withLock { _ = fixtures.removeValue(forKey: fixture.url) } }
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host?.hasSuffix(".fetch.test") == true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        guard let url = request.url, let fixture = Self.lock.withLock({ Self.fixtures[url] }) else { return }
        fixture.receive(request)
        var headers = ["ETag": "\"fixture\""]
        if let length = fixture.length { headers["Content-Length"] = String(length) }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: url, statusCode: fixture.status,
                             httpVersion: "HTTP/1.1", headerFields: headers)!, cacheStoragePolicy: .notAllowed)
        if !fixture.body.isEmpty { client?.urlProtocol(self, didLoad: fixture.body) }
        if fixture.finish { client?.urlProtocolDidFinishLoading(self) }
    }
    override func stopLoading() {
        if let url = request.url { Self.lock.withLock { Self.fixtures[url] }?.stop() }
    }
}

@Suite(.timeLimit(.minutes(1))) struct FetchTests {
    private func waitFor(_ condition: () -> Bool) async {
        for _ in 0..<200 {
            if condition() { return }
            try? await Task.sleep(nanoseconds: 5_000_000)
        }
    }

    @Test func defaultsBoundIdleAndWholeTransferTimes() {
        let config = URLSessionFetcher.configuration()
        #expect(config.timeoutIntervalForRequest == 15)
        #expect(config.timeoutIntervalForResource == 120)
        #expect(!config.waitsForConnectivity && config.urlCache == nil)
    }

    @Test(arguments: [false, true]) func reportsResponseLengthAndFirstBytes(knownLength: Bool) async throws {
        let fixture = FetchFixture(length: knownLength ? 3 : nil)
        defer { FetchProtocol.unregister(fixture) }
        let result = try await fixture.fetcher().get(fixture.url, etag: "\"old\"", maxBytes: 3,
                                                    downloadProgress: { fixture.report($0) })
        #expect(result == HTTPResult(status: 200, body: Data("abc".utf8), etag: "\"fixture\""))
        #expect(fixture.request?.value(forHTTPHeaderField: "If-None-Match") == "\"old\"")
        #expect(fixture.request?.timeoutInterval == 15)
        #expect(fixture.progress.first == FetchProgress(receivedBytes: 0, totalBytes: knownLength ? 3 : nil))
        #expect(fixture.progress.contains(FetchProgress(receivedBytes: 1, totalBytes: knownLength ? 3 : nil)))
        #expect(fixture.progress.last == FetchProgress(receivedBytes: 3, totalBytes: knownLength ? 3 : nil))
    }

    @Test func legacyCallbackAndByteOnlyFetchersStillWork() async throws {
        let fixture = FetchFixture(length: 3)
        defer { FetchProtocol.unregister(fixture) }
        _ = try await fixture.fetcher().get(fixture.url, etag: nil, maxBytes: 3,
                                           progress: { fixture.report(FetchProgress(receivedBytes: $0)) })
        #expect(fixture.progress.last?.receivedBytes == 3)
        let stub = StubFetcher()
        stub.enqueue(fixture.url, HTTPResult(status: 200, body: Data("a".utf8)))
        _ = try await stub.get(fixture.url, etag: nil, maxBytes: 3, downloadProgress: { fixture.report($0) })
        #expect(fixture.progress.last == FetchProgress(receivedBytes: 1))
    }

    @Test func rejectsHTTPFailuresAndOversizedDeclaredLengths() async {
        // These URLProtocol responses must finish: CFNetwork did not expose the unfinished fixtures to
        // bytes(for:) in the full test run. Cancellation is covered by the explicitly stalled request below.
        for fixture in [FetchFixture(status: 503, body: Data()),
                        FetchFixture(body: Data(repeating: 0, count: 10_000), length: 10_000)] {
            defer { FetchProtocol.unregister(fixture) }
            let expected: FetchError = fixture.status == 503 ? .http(503) : .tooLarge
            await #expect(throws: expected) {
                try await fixture.fetcher().get(fixture.url, etag: nil, maxBytes: 3,
                                               downloadProgress: { fixture.report($0) })
            }
            // Header rejection emits no download progress. A completed task need not call stopLoading.
            #expect(fixture.progress.isEmpty)
        }
    }

    @Test func unknownLengthBodyIsRejectedAtTheSizeCap() async {
        let fixture = FetchFixture(body: Data("abcd".utf8))
        defer { FetchProtocol.unregister(fixture) }
        await #expect(throws: FetchError.tooLarge) {
            try await fixture.fetcher().get(fixture.url, etag: nil, maxBytes: 3,
                                           downloadProgress: { fixture.report($0) })
        }
        #expect(fixture.progress.contains { $0.receivedBytes > 0 })
        #expect(fixture.progress.allSatisfy { $0.receivedBytes <= 3 && $0.totalBytes == nil })
    }

    @Test func cancellationStopsAStalledRequest() async throws {
        let fixture = FetchFixture(body: Data("a".utf8), finish: false)
        defer { FetchProtocol.unregister(fixture) }
        let task = Task {
            try await fixture.fetcher().get(fixture.url, etag: nil, maxBytes: 3,
                                           downloadProgress: { fixture.report($0) })
        }
        defer { task.cancel() }
        // Synchronize on startLoading, rather than assuming headers/body have reached AsyncBytes yet.
        await waitFor { fixture.request != nil }
        try #require(fixture.request != nil)
        task.cancel()
        switch await task.result {
        case .success: Issue.record("A cancelled transfer returned a body")
        case .failure(let error): #expect(error is CancellationError || (error as? URLError)?.code == .cancelled)
        }
        await waitFor { fixture.wasStopped }
        #expect(fixture.wasStopped)
    }

    @Test func notModifiedPreservesTheETagWithoutBodyProgress() async throws {
        let fixture = FetchFixture(status: 304, body: Data())
        defer { FetchProtocol.unregister(fixture) }
        let result = try await fixture.fetcher().get(fixture.url, etag: "\"old\"", maxBytes: 3,
                                                    downloadProgress: { fixture.report($0) })
        #expect(result == HTTPResult(status: 304, etag: "\"old\""))
        #expect(fixture.progress.isEmpty)
    }
}
