import Combine
import CryptoKit
import Foundation

/// 目录里的一个专业词库。 One domain dictionary in the catalog.
public struct DictPack: Identifiable, Equatable, Sendable {
    public var id: String
    public var name: String
    public var description: String
    public var license: String
    public var source: String
    public var words: Int
    public var bytes: Int64
    public var sha256: String

    public init(id: String, name: String, description: String = "", license: String = "", source: String = "",
                words: Int = 0, bytes: Int64 = 0, sha256: String) {
        self.id = id
        self.name = name
        self.description = description
        self.license = license
        self.source = source
        self.words = words
        self.bytes = bytes
        self.sha256 = sha256
    }

    /// 「描述 · 2.3 万词 · 188 KB」。 "description · words · size".
    public var summary: String {
        [description, PackFormat.words(words), PackFormat.size(bytes)].filter { !$0.isEmpty }.joined(separator: " · ")
    }
}

/// 专业词库目录（与 Android 同一份 dictpacks.json）。 The catalog, the same dictpacks.json as Android's.
public struct DictPackCatalog: Equatable, Sendable {
    /// 文件地址前缀：<base><id>.wvz。 File URL prefix: <base><id>.wvz.
    public var base: String
    public var packs: [DictPack]

    public init(base: String, packs: [DictPack]) {
        self.base = base
        self.packs = packs
    }

    /// 宽松解析：缺字段取默认，没有 id、校验和不像 SHA-256 或 id 不合规的条目跳过。
    /// Lenient: missing fields default; entries without an id, a SHA-256-looking checksum or a valid id are skipped.
    public static func parse(_ data: Data) -> DictPackCatalog? {
        guard let o = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        let list = o["packs"] as? [[String: Any]] ?? []
        let packs = list.compactMap { p -> DictPack? in
            let pack = DictPack(
                id: p["id"] as? String ?? "", name: p["name"] as? String ?? "",
                description: p["description"] as? String ?? "", license: p["license"] as? String ?? "",
                source: p["source"] as? String ?? "", words: (p["words"] as? NSNumber)?.intValue ?? 0,
                bytes: (p["bytes"] as? NSNumber)?.int64Value ?? 0, sha256: (p["sha256"] as? String ?? "").lowercased())
            guard isValidID(pack.id), pack.sha256.count == 64, pack.sha256.allSatisfy(\.isHexDigit) else { return nil }
            return pack
        }
        return DictPackCatalog(base: o["base"] as? String ?? "", packs: packs)
    }

    /// 与内核一致：小写字母、数字与 -_，不超过 40 个字符。 As the engine: lowercase, digits, -_, at most 40 chars.
    public static func isValidID(_ id: String) -> Bool {
        !id.isEmpty && id.utf8.count <= 40
            && id.utf8.allSatisfy { ($0 >= 0x61 && $0 <= 0x7a) || ($0 >= 0x30 && $0 <= 0x39) || $0 == 0x2d || $0 == 0x5f }
    }

    public func url(for pack: DictPack) -> URL? {
        base.isEmpty ? nil : URL(string: base + pack.id + ".wvz")
    }
}

/// 下载内容的校验。 Checks on downloaded content.
public enum PackCheck {
    public static func sha256Hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    /// 大小（目录里有时）与 SHA-256 都要对上。 Both the size (when the catalog has one) and the SHA-256 must match.
    public static func verify(_ data: Data, sha256: String, bytes: Int64) -> Bool {
        (bytes <= 0 || Int64(data.count) == bytes) && sha256Hex(data) == sha256.lowercased()
    }

    public static func verify(file: URL, sha256: String, bytes: Int64) -> Bool {
        guard let data = try? Data(contentsOf: file, options: .mappedIfSafe) else { return false }
        return verify(data, sha256: sha256, bytes: bytes)
    }
}

/// 词数与大小的写法（与 Android 一致）。 How word counts and sizes read, as on Android.
public enum PackFormat {
    public static func words(_ n: Int) -> String {
        n >= 10_000 ? String(format: "%.1f 万词", Double(n) / 10_000) : "\(n) 词"
    }

    public static func size(_ n: Int64) -> String {
        n >= 1 << 20 ? String(format: "%.1f MB", Double(n) / Double(1 << 20)) : "\((n + 1023) / 1024) KB"
    }
}

public enum PackState: Equatable, Sendable {
    case notInstalled
    /// done：已下载的字节数，还没开始时 nil。 done: bytes received, nil before the first ones arrive.
    case downloading(done: Int64?)
    case installed
    case failed(String)
}

/// 专业词库的下载、校验、安装与删除；只在主线程调用。装好的文件放在 dir/<id>.wvz（内核启动时自动载入该目录）。
/// Download, verification, install and removal of domain dictionaries; call on the main thread. Files live in
/// dir/<id>.wvz, a directory the engine loads by itself at startup.
public final class DictPackStore: ObservableObject {
    public static let failure = "下载失败，请检查网络后重试"

    public let catalog: DictPackCatalog
    public var packs: [DictPack] { catalog.packs }
    @Published public private(set) var states: [String: PackState] = [:]

    private let dir: URL
    private let fetcher: HTTPFetching
    private let attach: (String, String) -> Void
    private let detach: (String) -> Void
    private var tasks: [String: Task<Void, Never>] = [:]

    /// attach(id, path) / detach(id)：挂到内核上或卸下。 Attach to or detach from the engine.
    public init(catalog: DictPackCatalog, dir: URL, fetcher: HTTPFetching,
                attach: @escaping (String, String) -> Void, detach: @escaping (String) -> Void) {
        self.catalog = catalog
        self.dir = dir
        self.fetcher = fetcher
        self.attach = attach
        self.detach = detach
    }

    public func file(_ id: String) -> URL { dir.appendingPathComponent(id + ".wvz") }

    public func state(_ id: String) -> PackState {
        if let s = states[id] { return s }
        return FileManager.default.fileExists(atPath: file(id).path) ? .installed : .notInstalled
    }

    public var installed: [DictPack] { packs.filter { state($0.id) == .installed } }

    public func install(_ id: String) {
        guard let pack = packs.first(where: { $0.id == id }), let url = catalog.url(for: pack) else { return }
        if case .downloading = state(id) { return }
        states[id] = .downloading(done: nil)
        let fetcher = fetcher
        tasks[id] = Task { @MainActor [weak self] in
            do {
                let limit = pack.bytes > 0 ? Int(pack.bytes) + 1024 : 64 << 20
                let r = try await fetcher.get(url, etag: nil, maxBytes: limit) { n in
                    Task { @MainActor in self?.progress(id, n) }
                }
                try Task.checkCancellation()
                guard r.status == 200, PackCheck.verify(r.body, sha256: pack.sha256, bytes: pack.bytes) else {
                    throw FetchError.http(r.status)
                }
                self?.finish(pack, r.body)
            } catch {
                self?.fail(id, cancelled: Task.isCancelled || error is CancellationError)
            }
        }
    }

    public func cancel(_ id: String) {
        tasks.removeValue(forKey: id)?.cancel()
        states[id] = nil
    }

    @discardableResult
    public func remove(_ id: String) -> Bool {
        cancel(id)
        detach(id)
        let f = file(id)
        let ok = !FileManager.default.fileExists(atPath: f.path) || (try? FileManager.default.removeItem(at: f)) != nil
        states[id] = nil
        objectWillChange.send()
        return ok
    }

    /// 截图用：直接给出各词库的状态。 For snapshots: set the pack states directly.
    public func preview(_ states: [String: PackState]) { self.states = states }

    private func progress(_ id: String, _ n: Int64) {
        guard case .downloading = states[id] else { return }
        states[id] = .downloading(done: n)
    }

    private func finish(_ pack: DictPack, _ body: Data) {
        guard tasks.removeValue(forKey: pack.id) != nil else { return }
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            try body.write(to: file(pack.id), options: .atomic)
            attach(pack.id, file(pack.id).path)
            states[pack.id] = nil
            objectWillChange.send()
        } catch {
            states[pack.id] = .failed(Self.failure)
        }
    }

    private func fail(_ id: String, cancelled: Bool) {
        guard tasks.removeValue(forKey: id) != nil else { return }
        states[id] = cancelled ? nil : .failed(Self.failure)
    }
}
