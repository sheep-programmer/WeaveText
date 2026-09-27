import CoreImage
import CoreImage.CIFilterBuiltins
import Foundation

/// 剪贴板同步的判断：哪些变化该发给手机，刚从手机收到的不回传。
/// Clipboard sync decisions: which changes go to the phone; what just came from it is never echoed back.
public struct ClipboardGuard: Sendable {
    /// 超长文本不自动同步（避免把整篇文档悄悄发出去）。 Very long text isn't synced silently.
    public static let maxChars = 20_000
    public static let maxImageBytes = 20 << 20
    /// 密码管理器等标记为保密或临时的内容不同步（nspasteboard.org 约定）。
    /// Content marked concealed or transient (nspasteboard.org convention), e.g. by password managers, is skipped.
    public static let skippedTypes: Set<String> = ["org.nspasteboard.ConcealedType", "org.nspasteboard.TransientType"]

    private var seenChange: Int
    private var ownChange: Int?
    private var lastRemote: String?
    private var lastSent: String?

    public init(changeCount: Int) { seenChange = changeCount }

    /// 剪贴板计数有变化且不是本机刚写入的。 The change count moved and it wasn't our own write.
    public mutating func changed(_ changeCount: Int) -> Bool {
        guard changeCount != seenChange else { return false }
        seenChange = changeCount
        return changeCount != ownChange
    }

    /// 这段本机复制的文字要不要发。 Whether a local copy should be sent.
    public mutating func shouldSend(text: String, types: [String]) -> Bool {
        guard !types.contains(where: Self.skippedTypes.contains) else { return false }
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, text.count <= Self.maxChars else { return false }
        guard text != lastRemote, text != lastSent else { return false }
        lastSent = text
        return true
    }

    /// 图片用摘要去重。 Images are de-duplicated by digest.
    public mutating func shouldSend(imageDigest: String, bytes: Int, types: [String]) -> Bool {
        guard !types.contains(where: Self.skippedTypes.contains), bytes > 0, bytes <= Self.maxImageBytes else { return false }
        return shouldSendKey("image:" + imageDigest)
    }

    private mutating func shouldSendKey(_ key: String) -> Bool {
        guard key != lastRemote, key != lastSent else { return false }
        lastSent = key
        return true
    }

    /// 把手机发来的内容写进剪贴板之后调用。 Call after putting what came from the phone on the pasteboard.
    public mutating func wroteRemote(text: String? = nil, imageDigest: String? = nil, changeCount: Int) {
        lastRemote = text ?? imageDigest.map { "image:" + $0 }
        ownChange = changeCount
        seenChange = changeCount
    }
}

/// 一批待发文件，一个接一个发，给出「正在发送 3/5 · 42%」。
/// A batch of files sent one after another, reported as "正在发送 3/5 · 42%".
public struct SendQueue: Equatable, Sendable {
    public private(set) var pending: [String] = []
    public private(set) var current: String?
    /// 内核给的传输 id。 The core's transfer id of the current file.
    public var currentId: String?
    public private(set) var total = 0
    public private(set) var finished = 0
    public private(set) var failed = 0

    public init() {}

    public var isActive: Bool { current != nil || !pending.isEmpty }

    public mutating func add(_ paths: [String]) {
        if !isActive { total = 0; finished = 0; failed = 0 }
        pending += paths
        total += paths.count
    }

    /// 取下一个要发的路径（当前没有在发时）。 The next path to send, when nothing is in flight.
    public mutating func next() -> String? {
        guard current == nil, !pending.isEmpty else { return nil }
        current = pending.removeFirst()
        currentId = nil
        return current
    }

    /// 当前这个结束了（ok = 成功）。 The current file ended.
    public mutating func finish(ok: Bool) {
        guard current != nil else { return }
        current = nil
        currentId = nil
        finished += 1
        if !ok { failed += 1 }
    }

    public mutating func cancelAll() {
        pending = []
        current = nil
        currentId = nil
    }

    /// 「正在发送 3/5 · 42%」；没在发时 nil。 nil when idle.
    public func title(fraction: Double) -> String? {
        guard isActive else { return nil }
        let n = min(total, finished + 1)
        return total > 1 ? "正在发送 \(n)/\(total) · \(Int(fraction * 100))%" : "正在发送 · \(Int(fraction * 100))%"
    }
}

/// 配对二维码。 The pairing QR code.
public enum QRCode {
    /// 每个模块 scale 像素、四周留 margin 个模块的空白，最近邻放大保持清晰。
    /// `scale` pixels per module with a `margin`-module quiet zone, nearest-neighbour so edges stay crisp.
    public static func image(for text: String, scale: Int = 8, margin: Int = 2) -> CGImage? {
        let f = CIFilter.qrCodeGenerator()
        f.message = Data(text.utf8)
        f.correctionLevel = "M"
        guard let raw = f.outputImage else { return nil }
        let s = CGFloat(max(1, scale))
        let scaled = raw.samplingNearest().transformed(by: CGAffineTransform(scaleX: s, y: s))
        let pad = CGFloat(margin) * s
        let white = CIImage(color: .white).cropped(to: scaled.extent.insetBy(dx: -pad, dy: -pad))
        let out = scaled.composited(over: white)
        let ctx = CIContext(options: [.useSoftwareRenderer: false])
        return ctx.createCGImage(out, from: out.extent)
    }
}
