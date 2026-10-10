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

/// HTTP may omit the length (for example, a chunked mirror response).
public struct FetchProgress: Equatable, Sendable {
    public var receivedBytes: Int64
    public var totalBytes: Int64?

    public init(receivedBytes: Int64, totalBytes: Int64? = nil) {
        self.receivedBytes = max(0, receivedBytes)
        self.totalBytes = totalBytes.flatMap { $0 > 0 ? $0 : nil }
    }
}

/// 下载（专业词库、云端热词共用；测试里换成假的）。 Downloads, shared by domain packs and hot words; faked in tests.
public protocol HTTPFetching: Sendable {
    /// etag 非空时带 If-None-Match；没变化返回 304。progress 报已收到的字节数。
    /// Sends If-None-Match when etag is set; 304 when unchanged. progress reports the bytes received so far.
    func get(_ url: URL, etag: String?, maxBytes: Int, progress: (@Sendable (Int64) -> Void)?) async throws -> HTTPResult
    /// Reports bytes and the positive response length, when available; byte-only implementations use nil totals.
    func get(_ url: URL, etag: String?, maxBytes: Int,
             downloadProgress: (@Sendable (FetchProgress) -> Void)?) async throws -> HTTPResult
}

public extension HTTPFetching {
    /// Existing fetchers can report bytes without knowing the response length.
    func get(_ url: URL, etag: String?, maxBytes: Int,
             downloadProgress: (@Sendable (FetchProgress) -> Void)?) async throws -> HTTPResult {
        guard let downloadProgress else {
            return try await get(url, etag: etag, maxBytes: maxBytes, progress: nil)
        }
        return try await get(url, etag: etag, maxBytes: maxBytes,
                             progress: { downloadProgress(FetchProgress(receivedBytes: $0)) })
    }
}

/// URLSession 实现：不用系统缓存（ETag 自己管），任务取消即停止下载。
/// The URLSession implementation: no system cache (ETags are handled here); cancelling the task stops the download.
public struct URLSessionFetcher: HTTPFetching {
    private let session: URLSession

    public init() { self.init(configuration: Self.configuration()) }

    static func configuration() -> URLSessionConfiguration {
        let config = URLSessionConfiguration.ephemeral
        config.urlCache = nil
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.timeoutIntervalForRequest = 15
        // Bound each source even when it keeps trickling bytes; callers can then try the next mirror.
        config.timeoutIntervalForResource = 120
        config.waitsForConnectivity = false
        return config
    }

    init(configuration: URLSessionConfiguration) { session = URLSession(configuration: configuration) }

    public func get(_ url: URL, etag: String?, maxBytes: Int,
                    progress: (@Sendable (Int64) -> Void)?) async throws -> HTTPResult {
        guard let progress else {
            return try await get(url, etag: etag, maxBytes: maxBytes, downloadProgress: nil)
        }
        return try await get(url, etag: etag, maxBytes: maxBytes,
                             downloadProgress: { progress($0.receivedBytes) })
    }

    public func get(_ url: URL, etag: String?, maxBytes: Int,
                    downloadProgress: (@Sendable (FetchProgress) -> Void)?) async throws -> HTTPResult {
        try Task.checkCancellation()
        guard maxBytes >= 0 else { throw FetchError.tooLarge }
        var req = URLRequest(url: url, timeoutInterval: session.configuration.timeoutIntervalForRequest)
        if let etag { req.setValue(etag, forHTTPHeaderField: "If-None-Match") }
        let (bytes, response) = try await session.bytes(for: req)
        // Rejecting headers, reaching the size cap, or cancelling must also stop the underlying transfer.
        defer { bytes.task.cancel() }
        return try await withTaskCancellationHandler {
            try Task.checkCancellation()
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            if status == 304 { return HTTPResult(status: 304, etag: etag) }
            guard status == 200 else { throw FetchError.http(status) }
            let expected = response.expectedContentLength
            guard expected <= Int64(maxBytes) else { throw FetchError.tooLarge }
            let total = expected > 0 ? expected : nil
            var data = Data()
            if let total { data.reserveCapacity(Int(total)) }
            downloadProgress?(FetchProgress(receivedBytes: 0, totalBytes: total))
            var reported = 0
            var reportedAt = ProcessInfo.processInfo.systemUptime
            for try await b in bytes {
                try Task.checkCancellation()
                guard data.count < maxBytes else { throw FetchError.tooLarge }
                data.append(b)
                // Show the first byte and slow transfers too, without flooding the main thread.
                if let downloadProgress {
                    let now = ProcessInfo.processInfo.systemUptime
                    if data.count == 1 || data.count - reported >= 16 * 1024 || now - reportedAt >= 0.25 {
                        reported = data.count
                        reportedAt = now
                        downloadProgress(FetchProgress(receivedBytes: Int64(reported), totalBytes: total))
                    }
                }
            }
            try Task.checkCancellation()
            downloadProgress?(FetchProgress(receivedBytes: Int64(data.count), totalBytes: total))
            let tag = (response as? HTTPURLResponse)?.value(forHTTPHeaderField: "ETag")
            return HTTPResult(status: 200, body: data, etag: tag)
        } onCancel: {
            bytes.task.cancel()
        }
    }
}

/// 下载镜像（与 Android 的 models/catalog.json 里的 "mirrors" 同一份）：模板里的 {url} 换成原地址。
/// Download mirrors, the same "mirrors" as in Android's models/catalog.json: `{url}` in a template becomes the
/// source URL.
public struct Mirrors: Equatable, Sendable {
    public var templates: [String]

    public init(templates: [String] = []) { self.templates = templates }

    /// 接受整份目录（{"mirrors":[…]}）或只有镜像的数组；没有 {url} 的模板跳过。
    /// Takes the whole catalog ({"mirrors":[…]}) or just the array; templates without `{url}` are skipped.
    public static func parse(_ data: Data) -> Mirrors {
        let json = try? JSONSerialization.jsonObject(with: data)
        let list = (json as? [String: Any])?["mirrors"] as? [Any] ?? json as? [Any] ?? []
        let templates = list.compactMap { ($0 as? [String: Any])?["template"] as? String }
        return Mirrors(templates: templates.filter { $0.contains("{url}") })
    }

    /// 先直连，再依次走每个镜像；重复的地址只留一次。 The direct URL first, then each mirror; duplicates once.
    public func sources(for url: URL) -> [URL] {
        var seen = Set<String>()
        return ([url.absoluteString] + templates.map { $0.replacingOccurrences(of: "{url}", with: url.absoluteString) })
            .filter { seen.insert($0).inserted }
            .compactMap(URL.init(string:))
    }
}
