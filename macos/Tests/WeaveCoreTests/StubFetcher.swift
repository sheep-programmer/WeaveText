import Foundation
@testable import WeaveCore

/// 按地址排队给出响应的假下载器，并记下每次请求。 A fake fetcher answering from per-URL queues; records requests.
final class StubFetcher: HTTPFetching, @unchecked Sendable {
    struct Request: Equatable {
        var url: URL
        var etag: String?
    }

    private let lock = NSLock()
    private var queues: [URL: [Result<HTTPResult, Error>]] = [:]
    private(set) var requests: [Request] = []

    func enqueue(_ url: URL, _ r: HTTPResult) { lock.withLock { queues[url, default: []].append(.success(r)) } }
    func enqueue(_ url: URL, error: Error) { lock.withLock { queues[url, default: []].append(.failure(error)) } }

    func get(_ url: URL, etag: String?, maxBytes: Int,
             progress: (@Sendable (Int64) -> Void)?) async throws -> HTTPResult {
        let next: Result<HTTPResult, Error>? = lock.withLock {
            requests.append(Request(url: url, etag: etag))
            return queues[url]?.isEmpty == false ? queues[url]!.removeFirst() : nil
        }
        guard let next else { throw FetchError.http(404) }
        let r = try next.get()
        progress?(Int64(r.body.count))
        return r
    }
}

func tempDir(_ name: String) throws -> URL {
    let d = FileManager.default.temporaryDirectory.appendingPathComponent("weave-mac-\(name)-\(getpid())-\(UUID().uuidString)")
    try FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
    return d
}
