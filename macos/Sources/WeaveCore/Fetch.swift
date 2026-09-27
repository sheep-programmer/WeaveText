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
