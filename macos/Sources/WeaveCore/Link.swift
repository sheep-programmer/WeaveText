import CWeave
import Foundation

/// 织文互联（C 接口）的 Swift 包装。命令与事件都是 JSON（见 core/weave-link/src/lib.rs）。
/// Swift wrapper over WeaveLink's C ABI. Commands and events are JSON (see core/weave-link/src/lib.rs).
///
/// `poll` 可以在后台线程阻塞；`call` 可在任意线程。释放前先 `stop` 并等轮询线程退出。
/// `poll` may block on a background thread; `call` works from any thread. `stop` and join the poller before release.
public final class LinkHandle: @unchecked Sendable {
    public static let defaultPort = 47811

    private let handle: OpaquePointer

    /// config: `{name, platform, stateDir, inboxDir, port?, mdns?}`；失败返回 nil。 nil when the core fails to start.
    public init?(config: [String: Any]) {
        guard let json = LinkJSON.string(config), let h = weave_link_start(json) else { return nil }
        handle = h
    }

    deinit { weave_link_destroy(handle) }

    /// 下一个事件的 JSON；超时为 `{"type":"idle"}`，停止后 nil。 Next event JSON; idle on timeout, nil once stopped.
    public func poll(timeoutMs: UInt32) -> String? {
        guard let p = weave_link_poll(handle, timeoutMs) else { return nil }
        defer { weave_string_free(p) }
        return String(cString: p)
    }

    @discardableResult
    public func call(_ command: [String: Any]) -> [String: Any] {
        guard let json = LinkJSON.string(command), let p = weave_link_call(handle, json) else { return [:] }
        defer { weave_string_free(p) }
        return LinkJSON.object(String(cString: p)) ?? [:]
    }

    /// 只停止，让阻塞中的 poll 返回 nil。 Stop only, so a blocked poll returns nil.
    public func stop() { weave_link_stop(handle) }
}

enum LinkJSON {
    static func string(_ o: [String: Any]) -> String? {
        guard let d = try? JSONSerialization.data(withJSONObject: o) else { return nil }
        return String(data: d, encoding: .utf8)
    }

    static func object(_ s: String) -> [String: Any]? {
        (try? JSONSerialization.jsonObject(with: Data(s.utf8))) as? [String: Any]
    }
}

extension Dictionary where Key == String, Value == Any {
    public func str(_ k: String) -> String { self[k] as? String ?? "" }
    public func bool(_ k: String) -> Bool { self[k] as? Bool ?? false }
    public func int(_ k: String) -> Int64 { (self[k] as? NSNumber)?.int64Value ?? 0 }
    public func strings(_ k: String) -> [String] { self[k] as? [String] ?? [] }
    public func objects(_ k: String) -> [[String: Any]] { self[k] as? [[String: Any]] ?? [] }
}

/// 内核发来的一个事件。 One event from the core.
public enum LinkEvent: Equatable, Sendable {
    case idle
    /// 发现、消失、连上、断开：重新读设备列表。 Found / lost / connected / disconnected: re-read the peers.
    case peersChanged
    case paired(id: String, name: String)
    case pairFailed(reason: String)
    /// 有人对本机的配对码做了一次尝试。 Someone tried our pairing code.
    case pairAttempt(ok: Bool, stillOpen: Bool)
    case text(text: String, clip: Bool, from: String)
    case fileStart(id: String, name: String, size: Int64, incoming: Bool, peer: String, clip: Bool)
    case fileProgress(id: String, done: Int64, size: Int64)
    case fileDone(id: String, name: String, incoming: Bool, path: String?, mime: String, clip: Bool, from: String)
    case fileFailed(id: String, incoming: Bool, reason: String)
    case error(String)
    case unknown(String)

    public static func parse(_ json: String) -> LinkEvent {
        guard let o = LinkJSON.object(json) else { return .unknown(json) }
        let incoming = o.bool("incoming")
        switch o.str("type") {
        case "idle": return .idle
        case "peerFound", "peerLost", "connected", "disconnected": return .peersChanged
        case "paired": return .paired(id: o.str("id"), name: o.str("name"))
        case "pairFailed": return .pairFailed(reason: o.str("reason"))
        case "pairAttempt": return .pairAttempt(ok: o.bool("ok"), stillOpen: o.bool("stillOpen"))
        case "text": return .text(text: o.str("text"), clip: o.bool("clip"), from: o.str("fromName"))
        case "fileStart":
            return .fileStart(id: o.str("id"), name: o.str("name"), size: o.int("size"), incoming: incoming,
                              peer: incoming ? o.str("fromName") : o.str("to"), clip: o.bool("clip"))
        case "fileProgress": return .fileProgress(id: o.str("id"), done: o.int("done"), size: o.int("size"))
        case "fileDone":
            let path = o.str("path")
            return .fileDone(id: o.str("id"), name: o.str("name"), incoming: incoming, path: path.isEmpty ? nil : path,
                             mime: o["mime"] as? String ?? "application/octet-stream", clip: o.bool("clip"),
                             from: o.str("fromName"))
        case "fileFailed": return .fileFailed(id: o.str("id"), incoming: incoming, reason: o.str("reason"))
        case "error": return .error(o.str("message"))
        default: return .unknown(json)
        }
    }
}

/// 本机信息（`info` 命令）。 This device (`info` command).
public struct LinkInfo: Equatable, Sendable {
    public var id = ""
    public var name = ""
    public var fingerprint = ""
    public var addrs: [String] = []
    public var port = 0

    public init() {}

    public init(json o: [String: Any]) {
        id = o.str("id")
        name = o.str("name")
        fingerprint = o.str("fingerprint")
        addrs = o.strings("addrs")
        port = Int(o.int("port"))
    }
}

/// 已配对设备。 A paired device.
public struct LinkPeer: Equatable, Identifiable, Sendable {
    public var id: String
    public var name: String
    public var platform: String
    public var connected: Bool
    public var nearby: Bool
    public var addrs: [String] = []

    public init(id: String, name: String, platform: String, connected: Bool, nearby: Bool, addrs: [String] = []) {
        self.id = id
        self.name = name
        self.platform = platform
        self.connected = connected
        self.nearby = nearby
        self.addrs = addrs
    }

    public var displayName: String { name.isEmpty ? LinkText.platform(platform) : name }
}

/// 附近还没配对的设备。 A nearby device that is not paired yet.
public struct LinkNearby: Equatable, Identifiable, Sendable {
    public var id: String
    public var name: String
    public var platform: String

    public init(id: String, name: String, platform: String) {
        self.id = id
        self.name = name
        self.platform = platform
    }

    public var displayName: String { name.isEmpty ? LinkText.platform(platform) : name }
}

/// 一次传输。 One transfer.
public struct LinkTransfer: Equatable, Identifiable, Sendable {
    public enum State: Equatable, Sendable { case running, done, failed }

    public var id: String
    public var name: String
    public var incoming: Bool
    public var peer: String
    public var done: Int64
    public var size: Int64
    public var state: State
    public var path: String?

    public init(id: String, name: String, incoming: Bool, peer: String, done: Int64 = 0, size: Int64,
                state: State = .running, path: String? = nil) {
        self.id = id
        self.name = name
        self.incoming = incoming
        self.peer = peer
        self.done = done
        self.size = size
        self.state = state
        self.path = path
    }

    public var fraction: Double { size <= 0 ? 0 : min(1, max(0, Double(done) / Double(size))) }

    /// 「收到中 · 1.2 MB / 3.4 MB」「已发送 · 3.4 MB」「收到失败」。
    public var status: String {
        let dir = incoming ? "收到" : "发送"
        switch state {
        case .running: return "\(dir)中 · \(LinkText.size(done)) / \(LinkText.size(size))"
        case .done: return "已\(dir) · \(LinkText.size(size))"
        case .failed: return "\(dir)失败"
        }
    }
}

/// 打开的配对窗口（本机显示二维码与配对码，手机来连）。 An open pairing window: we show the code, the phone dials in.
public struct LinkPairing: Equatable, Sendable {
    public var code: String
    public var uri: String
    public var addrs: [String]
    public var expires: Date
    /// 错误尝试之类的提示。 A hint such as a wrong attempt.
    public var message: String?
    /// 配对成功的设备名。 Name of the device that just paired.
    public var pairedWith: String?
    /// 尝试次数用完，窗口已被内核关闭。 Attempts used up; the core closed the window.
    public var closed = false

    public init(json o: [String: Any], now: Date = Date()) {
        code = o.str("code")
        uri = o.str("uri")
        addrs = o.strings("addrs")
        let secs = o.int("expiresIn")
        expires = now.addingTimeInterval(TimeInterval(secs > 0 ? secs : 120))
    }

    public func remaining(at now: Date = Date()) -> Int { max(0, Int(expires.timeIntervalSince(now).rounded(.up))) }
    public func expired(at now: Date = Date()) -> Bool { closed || remaining(at: now) == 0 }
}

/// 事件带来的、要由宿主执行的副作用。 Side effects of an event that the host carries out.
public enum LinkEffect: Equatable, Sendable {
    case refreshPeers
    case receivedText(text: String, clip: Bool, from: String)
    /// 收到的剪贴板图片。 A received clipboard image.
    case receivedClipImage(path: String)
    case receivedClipFile(path: String, name: String, mime: String)
    case receivedFile(path: String, name: String, from: String)
    case receivedPersonal(path: String, from: String)
    /// 本机发出的一个文件结束了（成功或失败），发送队列可以继续。 An outgoing file finished; the queue may go on.
    case outgoingFinished(id: String, ok: Bool)
}

/// 互联的界面状态与事件归约（纯逻辑）。 WeaveLink UI state and its event reducer (pure logic).
public struct LinkState: Equatable, Sendable {
    public static let maxTransfers = 20

    public var running = false
    public var info = LinkInfo()
    public var trusted: [LinkPeer] = []
    public var nearby: [LinkNearby] = []
    public var transfers: [LinkTransfer] = []
    public var pairing: LinkPairing?
    /// 剪贴板传输不进列表，但要跟踪进度以便结束时识别。 Clipboard transfers stay out of the list.
    var clipTransfers: Set<String> = []

    public init() {}

    public var connected: [LinkPeer] { trusted.filter(\.connected) }

    public mutating func setPeers(json o: [String: Any]) {
        trusted = o.objects("trusted").map {
            LinkPeer(id: $0.str("id"), name: $0.str("name"), platform: $0.str("platform"),
                     connected: $0.bool("connected"), nearby: $0.bool("nearby"), addrs: $0.strings("addrs"))
        }
        nearby = o.objects("nearby").map { LinkNearby(id: $0.str("id"), name: $0.str("name"), platform: $0.str("platform")) }
    }

    /// 内核停了：全部离线。 The core stopped: everything goes offline.
    public mutating func stopped() {
        running = false
        trusted = trusted.map { var p = $0; p.connected = false; p.nearby = false; return p }
        nearby = []
        pairing = nil
        transfers = transfers.map { var t = $0; if t.state == .running { t.state = .failed }; return t }
    }

    public mutating func apply(_ e: LinkEvent) -> [LinkEffect] {
        switch e {
        case .idle, .error, .unknown, .pairFailed:
            return []
        case .peersChanged:
            return [.refreshPeers]
        case .paired(_, let name):
            pairing?.pairedWith = name
            return [.refreshPeers]
        case .pairAttempt(let ok, let stillOpen):
            guard !ok, pairing != nil else { return [] }
            pairing?.message = stillOpen ? "有设备输入了错误的配对码" : "错误次数太多，配对码已失效，请重新生成"
            if !stillOpen { pairing?.closed = true }
            return []
        case .text(let text, let clip, let from):
            return text.isEmpty ? [] : [.receivedText(text: text, clip: clip, from: from)]
        case .fileStart(let id, let name, let size, let incoming, let peer, let clip):
            if clip {
                clipTransfers.insert(id)
                return []
            }
            let who = incoming ? peer : (trusted.first { $0.id == peer }?.displayName ?? peer)
            transfers.removeAll { $0.id == id }
            transfers.insert(LinkTransfer(id: id, name: name, incoming: incoming, peer: who, size: size), at: 0)
            if transfers.count > Self.maxTransfers { transfers.removeLast(transfers.count - Self.maxTransfers) }
            return []
        case .fileProgress(let id, let done, let size):
            update(id) {
                $0.done = done
                if size > 0 { $0.size = size }
            }
            return []
        case .fileDone(let id, let name, let incoming, let path, let mime, let clip, let from):
            clipTransfers.remove(id)
            update(id) {
                $0.state = .done
                $0.done = max($0.done, $0.size)
                $0.path = path
            }
            guard incoming else { return [.outgoingFinished(id: id, ok: true)] }
            guard let path else { return [] }
            if mime=="application/x-weavetext-personal" {return [.receivedPersonal(path:path,from:from)]}
            if clip && mime.hasPrefix("image/") { return [.receivedClipImage(path: path)] }
            if clip { return [.receivedClipFile(path: path, name: name, mime: mime)] }
            return [.receivedFile(path: path, name: name, from: from)]
        case .fileFailed(let id, let incoming, _):
            clipTransfers.remove(id)
            update(id) { $0.state = .failed }
            return incoming ? [] : [.outgoingFinished(id: id, ok: false)]
        }
    }

    private mutating func update(_ id: String, _ f: (inout LinkTransfer) -> Void) {
        guard let i = transfers.firstIndex(where: { $0.id == id }) else { return }
        f(&transfers[i])
    }

    /// 某个传输的进度（剪贴板传输不在列表里时为 nil）。 Progress of a transfer, nil when unknown.
    public func transfer(_ id: String) -> LinkTransfer? { transfers.first { $0.id == id } }
}

/// 界面文字。 UI strings.
public enum LinkText {
    public static func platform(_ p: String) -> String {
        switch p {
        case "mac": return "Mac"
        case "android": return "Android"
        case "windows": return "Windows"
        case "linux": return "Linux"
        default: return "设备"
        }
    }

    public static func size(_ n: Int64) -> String {
        let d = Double(n)
        switch n {
        case (1 << 30)...: return String(format: "%.1f GB", d / Double(1 << 30))
        case (1 << 20)...: return String(format: "%.1f MB", d / Double(1 << 20))
        case (1 << 10)...: return String(format: "%.0f KB", d / Double(1 << 10))
        default: return "\(n) B"
        }
    }

    public static func peerStatus(_ p: LinkPeer) -> String {
        if p.connected { return "已连接 · " + platform(p.platform) }
        if p.nearby { return "在附近，正在连接…" }
        return "不在线"
    }
}
