import AppKit
import CryptoKit
import UniformTypeIdentifiers
import UserNotifications
import WeaveCore

/// 织文互联在 Mac 上的唯一实例：开关内核、后台轮询事件、维护界面状态，
/// 同步剪贴板、把收到的文件存进「下载/WeaveText」并发通知。
/// The single WeaveLink instance on the Mac: starts/stops the core, polls events on a background thread, keeps the UI
/// state, syncs the clipboard, saves received files to Downloads/WeaveText and posts notifications.
final class LinkService: NSObject, ObservableObject, UNUserNotificationCenterDelegate {
    static let shared = LinkService()

    @Published private(set) var state = LinkState()
    @Published private(set) var queue = SendQueue()
    /// 开着开关却没能启动（端口、网络）。 Enabled but the core failed to start.
    @Published private(set) var startFailed = false

    private let prefs = Preferences.shared
    @Published private(set) var scanning = false
    @Published private(set) var discoveryError: String?
    @Published private(set) var serviceError: String?
    @Published var selectedTarget = ""
    private let bonjour = LinkBonjour()
    private var appliedInbox: String?
    private var queueTarget: String?
    private var queuedTargets: [String] = []
    private var queuedClips: [Bool] = []
    private var queueClip = false
    private var handle: LinkHandle?
    private var pollerDone: DispatchSemaphore?
    private var clipTimer: Timer?
    private var clip = ClipboardGuard(changeCount: NSPasteboard.general.changeCount)
    /// 剪贴板图片的临时文件，发完删掉。 Temp files of clipboard images, removed once sent.
    private var tempFiles: [String: URL] = [:]
    private var appliedName: String?
    private var started = false
    private var notificationsAsked = false

    static var stateDir: URL { EngineHost.userDirectory().appendingPathComponent("link", isDirectory: true) }

    static var inboxDir: URL {
        let selected = Preferences.shared.linkReceiveDirectory
        return selected.isEmpty ? FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0] : URL(fileURLWithPath: selected, isDirectory: true)
    }

    /// 手机上看到的名字。 The name phones see.
    var displayName: String {
        let n = prefs.linkName.trimmingCharacters(in: .whitespaces)
        return n.isEmpty ? Self.systemName : n
    }

    static let systemName = Host.current().localizedName ?? "Mac"

    /// 应用启动时调用：按开关启停，并跟随偏好变化。 Called at launch; follows the switch from then on.
    func start() {
        guard !started else { return }
        started = true
        UNUserNotificationCenter.current().delegate = self
        NotificationCenter.default.addObserver(forName: Preferences.didChange, object: nil, queue: .main) { [weak self] _ in
            self?.sync()
        }
        sync()
    }

    private func sync() {
        guard started else { return }
        if prefs.linkEnabled {
            ensureRunning()
            if let h = handle, appliedName != displayName {
                h.call(["op": "rename", "name": displayName])
                appliedName = displayName
                state.info.name = displayName
                bonjour.start(info: state.info)
            }
            if let h = handle, appliedInbox != Self.inboxDir.path {
                let result = h.call(["op": "setInbox", "path": Self.inboxDir.path])
                if result.bool("ok") { appliedInbox = Self.inboxDir.path } else { serviceError = "接收目录不可写：" + result.str("error") }
            }
        } else {
            shutdown()
        }
        updateClipTimer()
    }

    // MARK: - 启停 / Start and stop

    private func ensureRunning() {
        guard handle == nil else { return }
        let config: [String: Any] = [
            "name": displayName, "platform": "mac", "stateDir": Self.stateDir.path, "inboxDir": Self.inboxDir.path,
            "mdns": false,
        ]
        guard let h = LinkHandle(config: config) else {
            startFailed = true
            return
        }
        startFailed = false
        handle = h
        appliedName = displayName
        state.running = true
        state.info = LinkInfo(json: h.call(["op": "info"]))
        appliedInbox = Self.inboxDir.path
        bonjour.command = { [weak self] command in self?.handle?.call(command) }
        bonjour.status = { [weak self] scanning, error in self?.scanning = scanning; self?.discoveryError = error }
        bonjour.start(info: state.info)
        refreshPeers()
        let done = DispatchSemaphore(value: 0)
        pollerDone = done
        let t = Thread { [weak self] in
            while let json = h.poll(timeoutMs: 1000) {
                let e = LinkEvent.parse(json)
                if e == .idle { continue }
                DispatchQueue.main.async { self?.receive(e, from: h) }
            }
            done.signal()
        }
        t.name = "weavelink-poll"
        t.start()
    }

    /// 关掉内核（关开关、退出时）。 Stop the core (switch off, quit).
    func shutdown() {
        guard let h = handle else { return }
        bonjour.stop()
        handle = nil
        h.stop()
        _ = pollerDone?.wait(timeout: .now() + 2)
        pollerDone = nil
        state.stopped()
        queue.cancelAll(); queuedTargets = []; queuedClips = []
        for url in tempFiles.values { try? FileManager.default.removeItem(at: url) }
        tempFiles = [:]
        updateClipTimer()
    }

    private func refreshPeers() {
        guard let h = handle else { return }
        state.setPeers(json: h.call(["op": "peers"]))
        if !state.connected.contains(where: { $0.id == selectedTarget }) { selectedTarget = state.connected.first?.id ?? "" }
        updateClipTimer()
    }

    // MARK: - 事件 / Events

    private func receive(_ e: LinkEvent, from h: LinkHandle) {
        guard h === handle else { return }
        if case .error(let m) = e { serviceError = m }
        if case .pairFailed(let reason) = e { serviceError = "配对失败：" + reason }
        if case .fileFailed(_, _, let reason) = e { serviceError = "传输失败：" + reason }
        for effect in state.apply(e) { perform(effect) }
        // 配对成功：显示一下结果后自动关闭配对窗口。 Paired: show the result briefly, then close the sheet.
        if case .paired = e, state.pairing?.pairedWith != nil {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
                if self?.state.pairing?.pairedWith != nil { self?.closePairing() }
            }
        }
    }

    /// 截图用：换上示例状态。 For snapshots: install a sample state.
    func preview(_ sample: LinkState) {
        state = sample
    }

    private func perform(_ effect: LinkEffect) {
        switch effect {
        case .refreshPeers:
            refreshPeers()
        case .receivedText(let text, let isClip, let from):
            // 剪贴板同步关着时不接收对方的剪贴板；主动发来的文字总会收下。
            // With sync off the phone's clipboard is ignored; text sent on purpose is always taken.
            if isClip && !prefs.linkClipSync { return }
            let pb = NSPasteboard.general
            pb.clearContents()
            pb.setString(text, forType: .string)
            clip.wroteRemote(text: text, changeCount: pb.changeCount)
            if !isClip {
                notify(title: from.isEmpty ? "收到文字" : "来自 \(from) 的文字", body: "已复制：" + String(text.prefix(80)))
            }
        case .receivedClipImage(let path):
            guard prefs.linkClipSync, let image = NSImage(contentsOfFile: path) else { return }
            let pb = NSPasteboard.general
            pb.clearContents()
            pb.writeObjects([image])
            clip.wroteRemote(imageDigest: Self.pasteboardPNG(pb)?.digest ?? "", changeCount: pb.changeCount)
        case .receivedClipFile(let path, _, _):
            guard prefs.linkClipSync else { return }
            let pb = NSPasteboard.general
            pb.clearContents()
            pb.writeObjects([URL(fileURLWithPath: path) as NSURL])
            clip.wroteRemote(changeCount: pb.changeCount)
        case .receivedFile(let path, let name, let from):
            let where_ = "已保存到 " + URL(fileURLWithPath: path).deletingLastPathComponent().path
            notify(title: "收到文件：\(name)", body: from.isEmpty ? where_ : "来自 \(from) · \(where_)", path: path)
        case .outgoingFinished(let id, let ok):
            if let url = tempFiles.removeValue(forKey: id) { try? FileManager.default.removeItem(at: url) }
            if id == queue.currentId {
                queue.finish(ok: ok)
                sendNext()
            }
        }
    }

    // MARK: - 界面操作 / UI actions

    func setEnabled(_ on: Bool) { prefs.linkEnabled = on }

    func rename(_ name: String) {
        let n = String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(40))
        guard !n.isEmpty else { return }
        prefs.linkName = n == Self.systemName ? "" : n
    }

    func openPairing() {
        guard let h = handle else { return }
        var command: [String: Any] = ["op": "openPairing"]
        if !prefs.linkPublicAddress.trimmingCharacters(in: .whitespaces).isEmpty { command["addrs"] = [Self.endpoint(prefs.linkPublicAddress)] }
        let r = h.call(command)
        guard !r.isEmpty, r["code"] is String else { serviceError = "本机公网地址无效，请使用 IPv4:端口 或 [IPv6]:端口"; return }
        state.pairing = LinkPairing(json: r)
    }

    func closePairing() {
        handle?.call(["op": "closePairing"])
        state.pairing = nil
    }

    func forget(_ peer: LinkPeer) {
        handle?.call(["op": "forget", "id": peer.id])
        refreshPeers()
    }

    static func endpoint(_ input: String) -> String {
        let value = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if value.hasPrefix("[") { return value.hasSuffix("]") ? value + ":47811" : value }
        if value.filter({ $0 == ":" }).count > 1 { return "[" + value + "]:47811" }
        return value.contains(":") ? value : value + ":47811"
    }
    func rescan() { bonjour.start(info: state.info) }
    func connect(_ peer: LinkPeer, address: String) {
        let addrs = address.isEmpty ? peer.addrs : [Self.endpoint(address)]
        let r = handle?.call(["op": "connect", "id": peer.id, "addrs": addrs]) ?? [:]
        if !r.bool("ok") { serviceError = "地址无效，请检查 IPv4:端口 或 [IPv6]:端口" }
    }
    func pair(address: String, code: String) {
        let result = handle?.call(["op": "pair", "addrs": [Self.endpoint(address)], "code": code]) ?? [:]
        if !result.bool("ok") { serviceError = "地址或配对码无效" }
    }
    func chooseInbox() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true; panel.canChooseFiles = false; panel.allowsMultipleSelection = false
        panel.prompt = "选择接收目录"
        if panel.runModal() == .OK, let url = panel.url { prefs.linkReceiveDirectory = url.path }
    }
    func sendText(_ text: String, clipboard: Bool = false) {
        guard canSend else { return }
        let r = handle?.call(["op": "sendText", "to": selectedTarget, "text": text, "clip": clipboard]) ?? [:]
        if !r.bool("ok") { serviceError = "发送失败，请检查接收设备连接" }
    }

    func revealInbox() {
        let dir = Self.inboxDir
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        NSWorkspace.shared.open(dir)
    }

    func reveal(_ path: String) {
        NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: path)])
    }

    var canSend: Bool { handle != nil && !state.connected.isEmpty }

    /// 「发送剪贴板」：文件发文件，图片发成 PNG 文件，文字发文字。
    /// "Send clipboard": files as files, an image as a PNG file, text as text.
    func sendClipboard() {
        guard let h = handle, canSend else { return }
        let pb = NSPasteboard.general
        let types = pb.types?.map(\.rawValue) ?? []
        guard !types.contains(where: ClipboardGuard.skippedTypes.contains) else { serviceError = "此剪贴板内容被标记为敏感"; return }
        switch LinkClipboard.read(pb) {
        case .files(let urls): sendFiles(urls, clip: true)
        case .image(let data):
            guard let url = writeTemp(data, name: "剪贴板图片.png") else { serviceError = "无法读取剪贴板图片"; return }
            let r = h.call(["op": "sendFile", "to": selectedTarget, "path": url.path, "name": "剪贴板图片.png", "mime": "image/png", "clip": true])
            if r.bool("ok") { tempFiles[r.str("id")] = url } else { try? FileManager.default.removeItem(at: url); serviceError = "图片发送失败" }
        case .text(let text): sendText(text, clipboard: true)
        case .empty: serviceError = "剪贴板为空或格式无法读取"
        }
    }

    /// 「发送文件…」 "Send files…"
    func chooseFiles(imagesOnly: Bool = false) {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        if imagesOnly { panel.allowedContentTypes = [.image] }
        panel.canChooseDirectories = false
        panel.canChooseFiles = true
        panel.prompt = "发送"
        panel.message = "选择要发送到手机的文件"
        NSApp.activate(ignoringOtherApps: true)
        if panel.runModal() == .OK { sendFiles(panel.urls) }
    }

    /// 排队逐个发送。 Queue the files and send them one by one.
    func sendFiles(_ urls: [URL], to: String? = nil, clip: Bool = false) {
        guard canSend else { return }
        let files = urls.filter { url in
            var dir: ObjCBool = false
            return FileManager.default.fileExists(atPath: url.path, isDirectory: &dir) && !dir.boolValue
        }
        guard !files.isEmpty else { return }
        queuedTargets += Array(repeating: to ?? selectedTarget, count: files.count)
        queuedClips += Array(repeating: clip, count: files.count)
        queue.add(files.map(\.path))
        sendNext()
    }

    private func sendNext() {
        guard let h = handle else {
            queue.cancelAll()
            return
        }
        while let path = queue.next() {
            queueTarget = queuedTargets.isEmpty ? selectedTarget : queuedTargets.removeFirst()
            queueClip = queuedClips.isEmpty ? false : queuedClips.removeFirst()
            let url = URL(fileURLWithPath: path)
            let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
            let r = h.call(["op": "sendFile", "to": queueTarget ?? selectedTarget, "path": path, "name": url.lastPathComponent, "mime": mime, "clip": queueClip])
            if r.bool("ok") {
                queue.currentId = r.str("id")
                return
            }
            queue.finish(ok: false)
        }
    }

    /// 当前批次的进度。 Progress of the current batch.
    var queueTitle: String? {
        let fraction = queue.currentId.flatMap(state.transfer)?.fraction ?? 0
        return queue.title(fraction: fraction)
    }

    // MARK: - 剪贴板同步 / Clipboard sync

    private func updateClipTimer() {
        let want = handle != nil && prefs.linkClipSync && !state.connected.isEmpty
        if want, clipTimer == nil {
            // 连上之前复制的内容不补发。 Whatever was copied before connecting isn't sent.
            _ = clip.changed(NSPasteboard.general.changeCount)
            let t = Timer(timeInterval: 0.5, repeats: true) { [weak self] _ in self?.clipTick() }
            RunLoop.main.add(t, forMode: .common)
            clipTimer = t
        } else if !want, let t = clipTimer {
            t.invalidate()
            clipTimer = nil
        }
    }

    private func clipTick() {
        let pb = NSPasteboard.general
        guard let h = handle, clip.changed(pb.changeCount) else { return }
        let types = pb.types?.map(\.rawValue) ?? []
        switch LinkClipboard.read(pb) {
        case .files(let urls):
            let key = urls.map { url in
                let attributes = try? FileManager.default.attributesOfItem(atPath: url.path)
                return url.path + ":" + String(describing: attributes?[.size]) + ":" + String(describing: attributes?[.modificationDate])
            }.joined(separator: "|")
            if clip.shouldSend(filesKey: key, types: types) {
                for peer in state.connected { sendFiles(urls, to: peer.id, clip: true) }
            }
        case .image(let data):
            let digest = SHA256.hash(data: data).prefix(12).map { String(format: "%02x", $0) }.joined()
            guard clip.shouldSend(imageDigest: digest, bytes: data.count, types: types) else { return }
            for peer in state.connected {
                guard let url = writeTemp(data, name: "clipboard.png") else { continue }
                let r = h.call(["op": "sendFile", "to": peer.id, "path": url.path, "name": "clipboard.png", "mime": "image/png", "clip": true])
                if r.bool("ok") { tempFiles[r.str("id")] = url } else { try? FileManager.default.removeItem(at: url) }
            }
        case .text(let text):
            if clip.shouldSend(text: text, types: types) { h.call(["op": "sendText", "text": text, "clip": true]) }
        case .empty: break
        }
    }

    /// 剪贴板里的图片（PNG 或 TIFF）转成 PNG 与它的摘要。 The pasteboard image (PNG or TIFF) as PNG plus its digest.
    static func pasteboardPNG(_ pb: NSPasteboard) -> (data: Data, digest: String)? {
        var data = pb.data(forType: .png)
        if data == nil, let tiff = pb.data(forType: .tiff) {
            data = NSBitmapImageRep(data: tiff)?.representation(using: .png, properties: [:])
        }
        guard let data else { return nil }
        let digest = SHA256.hash(data: data).prefix(12).map { String(format: "%02x", $0) }.joined()
        return (data, digest)
    }

    private func writeTemp(_ data: Data, name: String) -> URL? {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("weavelink-\(UUID().uuidString)")
        let url = dir.appendingPathComponent(name)
        do {
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            try data.write(to: url)
            return url
        } catch {
            return nil
        }
    }

    // MARK: - 通知 / Notifications

    /// 第一次要发通知时才请求授权。 Authorization is requested the first time a notification is needed.
    private func notify(title: String, body: String, path: String? = nil) {
        let center = UNUserNotificationCenter.current()
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        if let path { content.userInfo = ["path": path] }
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        let post = { center.add(request) { _ in } }
        if notificationsAsked {
            post()
            return
        }
        notificationsAsked = true
        center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
            if granted { post() }
        }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list])
    }

    /// 点收到文件的通知：在访达中显示。 Clicking a received-file notification reveals it in Finder.
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        if let path = response.notification.request.content.userInfo["path"] as? String {
            DispatchQueue.main.async { self.reveal(path) }
        }
        completionHandler()
    }
}
