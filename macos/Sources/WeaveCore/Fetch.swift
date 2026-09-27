import Foundation

/// 一次 HTTP GET 的结果。 The result of one HTTP GET.
public struct HTTPResult: Equatable, Sendable {
    public var status: Int
    public var body: Data
    public var etag: String?

    public init(status: Int, body: Data = Data(), etag: String? = nil) {
        self.status = status
        self.body = body
        self.etag = etag
    }
}

public enum FetchError: Error, Equatable {
    case http(Int)
    case tooLarge
}

/// 下载（专业词库、云端热词共用；测试里换成假的）。 Downloads, shared by domain packs and hot words; faked in tests.
public protocol HTTPFetching: Sendable {
    /// etag 非空时带 If-None-Match；没变化返回 304。progress 报已收到的字节数。
    /// Sends If-None-Match when etag is set; 304 when unchanged. progress reports the bytes received so far.
    func get(_ url: URL, etag: String?, maxBytes: Int, progress: (@Sendable (Int64) -> Void)?) async throws -> HTTPResult
}

/// URLSession 实现：不用系统缓存（ETag 自己管），任务取消即停止下载。
/// The URLSession implementation: no system cache (ETags are handled here); cancelling the task stops the download.
public struct URLSessionFetcher: HTTPFetching {
    private let session: URLSession

    public init() {
        let config = URLSessionConfiguration.ephemeral
        config.urlCache = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.timeoutIntervalForRequest = 15
        config.waitsForConnectivity = false
        session = URLSession(configuration: config)
    }

    public func get(_ url: URL, etag: String?, maxBytes: Int,
                    progress: (@Sendable (Int64) -> Void)?) async throws -> HTTPResult {
        var req = URLRequest(url: url)
        if let etag { req.setValue(etag, forHTTPHeaderField: "If-None-Match") }
        let (bytes, response) = try await session.bytes(for: req)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status == 304 { return HTTPResult(status: 304, etag: etag) }
        guard status == 200 else { throw FetchError.http(status) }
        var data = Data()
        let expected = response.expectedContentLength
        if expected > 0, expected <= Int64(maxBytes) { data.reserveCapacity(Int(expected)) }
        var reported = 0
        for try await b in bytes {
            data.append(b)
            if data.count > maxBytes { throw FetchError.tooLarge }
            if data.count - reported >= 16 * 1024 {
                reported = data.count
                progress?(Int64(reported))
            }
        }
        progress?(Int64(data.count))
        let tag = (response as? HTTPURLResponse)?.value(forHTTPHeaderField: "ETag")
        return HTTPResult(status: 200, body: data, etag: tag)
    }
}
