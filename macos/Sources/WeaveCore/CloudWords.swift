import Combine
import Foundation

/// 云端热词的状态。 Cloud hot-words status.
public struct CloudStatus: Equatable, Sendable {
    public var enabled: Bool
    public var words = 0
    public var version = ""
    /// 上次成功检查的时间；nil = 从未。 Last successful check; nil = never.
    public var checkedAt: Date?
    public var updating = false
    public var error: String?

    public init(enabled: Bool, words: Int = 0, version: String = "", checkedAt: Date? = nil, updating: Bool = false,
                error: String? = nil) {
        self.enabled = enabled
        self.words = words
        self.version = version
        self.checkedAt = checkedAt
        self.updating = updating
        self.error = error
    }

    /// 「1280 个词 · 9 月 21 日 22:13 检查」/ 正在更新… / 错误 / 还没有下载。 The status line under the switch.
    public func summary(timeZone: TimeZone = .current) -> String {
        if updating { return "正在更新…" }
        if let error { return error }
        guard let checkedAt else { return "还没有下载" }
        let f = DateFormatter()
        f.locale = Locale(identifier: "zh_CN")
        f.timeZone = timeZone
        f.dateFormat = "M 月 d 日 HH:mm"
        return "\(words) 个词 · \(f.string(from: checkedAt)) 检查"
    }
}

/// 云端热词（默认关闭）：每天最多从织文热词仓库下载一次 hotwords.tsv 与签名（带 ETag，没变化就不下载），
/// 内核用内置公钥验签、去掉过期词后作为扩展词库 cloud 挂上。只下载，不上传任何输入内容。只在主线程调用。
/// Cloud hot words (off by default): at most once a day, fetch hotwords.tsv and its signature from the hot-words
/// repository (with an ETag, nothing downloaded when unchanged); the engine verifies it with its pinned key, drops
/// expired words and attaches it as the extra lexicon "cloud". Download only. Call on the main thread.
public final class CloudWords: ObservableObject {
    public static let url = URL(string: "https://raw.githubusercontent.com/sheep-programmer/weavetext-hotwords/dist/hotwords.tsv")!
    public static let packID = "cloud"
    public static let failure = "更新失败，稍后会自动重试"
    static let day: TimeInterval = 24 * 3600
    static let maxBytes = 4 << 20

    enum Key {
        static let enabled = "cloudWords"
        static let count = "cloudWordsCount"
        static let version = "cloudWordsVersion"
        static let checked = "cloudWordsChecked"
        static let etag = "cloudWordsETag"
    }

    @Published public private(set) var status: CloudStatus

    private let defaults: UserDefaults
    private let dir: URL
    private let fetcher: HTTPFetching
    private let load: (String, String) -> Int
    private let unload: () -> Void
    private let now: () -> Date
    private var task: Task<Void, Never>?
    private var error: String?

    public var tsv: URL { dir.appendingPathComponent("hotwords.tsv") }
    public var sig: URL { dir.appendingPathComponent("hotwords.tsv.sig") }

    /// load(tsv, sig)：交给内核验签并挂上，返回词数或 -1；unload：卸下 cloud。
    /// load(tsv, sig): verify and attach through the engine, word count or -1; unload: detach "cloud".
    public init(defaults: UserDefaults, dir: URL, fetcher: HTTPFetching, load: @escaping (String, String) -> Int,
                unload: @escaping () -> Void, now: @escaping () -> Date = Date.init) {
        self.defaults = defaults
        self.dir = dir
        self.fetcher = fetcher
        self.load = load
        self.unload = unload
        self.now = now
        status = CloudStatus(enabled: false)
        publish()
    }

    public var enabled: Bool { defaults.bool(forKey: Key.enabled) }

    public func setEnabled(_ on: Bool) {
        defaults.set(on, forKey: Key.enabled)
        if on {
            refreshNow()
        } else {
            task?.cancel()
            task = nil
            unload()
            try? FileManager.default.removeItem(at: dir)
            for k in [Key.count, Key.version, Key.checked, Key.etag] { defaults.removeObject(forKey: k) }
            error = nil
        }
        publish()
    }

    /// 启动时挂上已下载的热词。 Attach the downloaded hot words at startup.
    public func attach() {
        guard enabled, exists(tsv), exists(sig) else { return }
        if load(tsv.path, sig.path) < 0 { NSLog("WeaveText: stored hot words failed verification") }
    }

    /// 开启且超过一天没检查时更新（很便宜，可以常调）。 Update when on and stale; cheap enough to call often.
    public func refreshIfStale() {
        guard enabled else { return }
        let checked = defaults.double(forKey: Key.checked)
        if checked > 0, now().timeIntervalSince1970 - checked < Self.day { return }
        refreshNow()
    }

    /// 立即更新；已在更新时返回正在跑的那个任务。 Update now; returns the running task when one is already going.
    @discardableResult
    public func refreshNow() -> Task<Void, Never>? {
        guard enabled else { return nil }
        if let task { return task }
        let t = Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                try await self.update()
                self.error = nil
            } catch is CancellationError {
            } catch {
                NSLog("WeaveText: hot words update failed: %@", String(describing: error))
                if self.enabled { self.error = Self.failure }
            }
            self.task = nil
            self.publish()
        }
        task = t
        publish()
        return t
    }

    private func update() async throws {
        let etag = exists(tsv) ? defaults.string(forKey: Key.etag) : nil
        let r = try await fetcher.get(Self.url, etag: etag, maxBytes: Self.maxBytes, progress: nil)
        try Task.checkCancellation()
        if r.status == 304 {
            defaults.set(now().timeIntervalSince1970, forKey: Key.checked)
            return
        }
        guard r.status == 200 else { throw FetchError.http(r.status) }
        let s = try await fetcher.get(Self.url.appendingPathExtension("sig"), etag: nil, maxBytes: 64 << 10,
                                      progress: nil)
        try Task.checkCancellation()
        guard s.status == 200 else { throw FetchError.http(s.status) }
        try install(r.body, s.body, etag: r.etag)
    }

    enum UpdateError: Error { case badSignature }

    /// 先写临时文件交给内核验签，通过才替换旧文件；不通过继续用旧版本。
    /// Verify via the engine from temp files and replace only on success; otherwise keep using the old version.
    private func install(_ body: Data, _ signature: Data, etag: String?) throws {
        // 更新途中被关掉：什么也不留。 Turned off mid-update: leave nothing behind.
        guard enabled else { throw CancellationError() }
        let fm = FileManager.default
        try fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let t = dir.appendingPathComponent("hotwords.tsv.new")
        let s = dir.appendingPathComponent("hotwords.tsv.sig.new")
        try body.write(to: t)
        try signature.write(to: s)
        let n = load(t.path, s.path)
        if n < 0 {
            try? fm.removeItem(at: t)
            try? fm.removeItem(at: s)
            if exists(tsv), exists(sig) { _ = load(tsv.path, sig.path) }
            throw UpdateError.badSignature
        }
        try? fm.removeItem(at: tsv)
        try? fm.removeItem(at: sig)
        try fm.moveItem(at: t, to: tsv)
        try fm.moveItem(at: s, to: sig)
        let head = String(decoding: body.prefix(512), as: UTF8.self).split(separator: "\n").prefix(4)
        let version = head.first { $0.hasPrefix("#! version") }.map {
            $0.dropFirst("#! version".count).trimmingCharacters(in: .whitespaces)
        } ?? ""
        defaults.set(n, forKey: Key.count)
        defaults.set(version, forKey: Key.version)
        defaults.set(now().timeIntervalSince1970, forKey: Key.checked)
        if let etag { defaults.set(etag, forKey: Key.etag) } else { defaults.removeObject(forKey: Key.etag) }
    }

    /// 截图用：直接给出状态。 For snapshots: set the status directly.
    public func preview(_ status: CloudStatus) { self.status = status }

    private func exists(_ url: URL) -> Bool { FileManager.default.fileExists(atPath: url.path) }

    private func publish() {
        let checked = defaults.double(forKey: Key.checked)
        status = CloudStatus(enabled: enabled, words: defaults.integer(forKey: Key.count),
                             version: defaults.string(forKey: Key.version) ?? "",
                             checkedAt: checked > 0 ? Date(timeIntervalSince1970: checked) : nil,
                             updating: task != nil, error: error)
    }
}
