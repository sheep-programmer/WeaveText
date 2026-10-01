import SwiftUI
import WeaveCore

/// 页面上的临时状态（@State 不可用，见 Pages.swift）。 Transient page state (@State is unavailable, see Pages.swift).
final class LinkPageModel: ObservableObject {
    @Published var nameDraft = ""
    @Published var confirmForget: LinkPeer?
    @Published var type = "文字"
    @Published var text = ""
    @Published var address = ""
    @Published var code = ""
    @Published var reconnectAddress = ""
}

/// 互联：与同一局域网内的手机配对，互传文字、剪贴板、图片与文件。
/// WeaveLink: pair with phones on the same LAN and exchange text, clipboard, images and files.
struct LinkPage: View {
    @ObservedObject var prefs: Preferences
    @ObservedObject var link: LinkService
    @StateObject private var model = LinkPageModel()

    private var s: LinkState { link.state }

    var body: some View {
        Form {
            Section {
                Toggle(isOn: $prefs.linkEnabled) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("织文互联")
                        Text("设备间直传文字、图片和文件，支持局域网及可直连的远程地址，端到端加密")
                            .font(.callout)
                            .foregroundStyle(.secondary)
                    }
                }
                .toggleStyle(.switch)
            } footer: {
                if !prefs.linkEnabled {
                    Footnote("开启后点「配对手机」，用手机上的织文扫描二维码即可配对；也可以在手机的「附近的设备」里选这台 Mac，输入这里显示的 6 位配对码。")
                }
            }
            if prefs.linkEnabled { enabledSections }
        }
        .formStyle(.grouped)
        .onAppear { model.nameDraft = link.displayName }
        .sheet(isPresented: Binding(get: { link.state.pairing != nil }, set: { if !$0 { link.closePairing() } })) {
            PairingSheet(link: link)
        }
        .alert(
            "取消与「\(model.confirmForget?.displayName ?? "")」的配对？",
            isPresented: Binding(get: { model.confirmForget != nil }, set: { if !$0 { model.confirmForget = nil } })
        ) {
            Button("取消配对", role: .destructive) {
                if let p = model.confirmForget { link.forget(p) }
                model.confirmForget = nil
            }
            Button("保留", role: .cancel) { model.confirmForget = nil }
        } message: {
            Text("之后要重新扫码才能连接。")
        }
    }

    @ViewBuilder private var enabledSections: some View {
        if link.startFailed || !s.running {
            Section {
                Label("互联服务没有启动，请检查网络后重新打开开关。", systemImage: "exclamationmark.triangle")
                    .foregroundStyle(.orange)
            }
        }
        Section("本机") {
            HStack {
                TextField("名称", text: $model.nameDraft, prompt: Text(LinkService.systemName))
                    .onSubmit { link.rename(model.nameDraft) }
                if model.nameDraft.trimmingCharacters(in: .whitespaces) != link.displayName,
                   !model.nameDraft.trimmingCharacters(in: .whitespaces).isEmpty {
                    Button("保存") { link.rename(model.nameDraft) }
                }
            }
            if !s.info.fingerprint.isEmpty {
                LabeledContent("安全码") {
                    Text(s.info.fingerprint).font(.body.monospaced()).textSelection(.enabled)
                }
            }
            Toggle(isOn: $prefs.linkClipSync) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("同步剪贴板")
                    Text("在 Mac 上复制的文字、图片和文件自动出现在手机上，反之亦然").font(.callout).foregroundStyle(.secondary)
                }
            }
            .toggleStyle(.switch)
            LabeledContent("接收目录") {
                Text(LinkService.inboxDir.path).lineLimit(2).textSelection(.enabled)
                Button("选择…") { link.chooseInbox() }
                if !prefs.linkReceiveDirectory.isEmpty { Button("默认下载目录") { prefs.linkReceiveDirectory = "" } }
            }
        }
        Section("发送内容") {
            Picker("接收设备", selection: $link.selectedTarget) {
                Text("选择设备").tag("")
                ForEach(s.connected) { peer in Text(peer.displayName).tag(peer.id) }
            }
            Picker("类型", selection: $model.type) {
                ForEach(["文字", "剪贴板", "图片", "文件"], id: \.self) { Text($0).tag($0) }
            }.pickerStyle(.segmented)
            if model.type == "文字" { TextEditor(text: $model.text).frame(minHeight: 70, maxHeight: 120) }
            Button(model.type == "图片" || model.type == "文件" ? "选择并发送…" : "发送") {
                switch model.type {
                case "文字": link.sendText(model.text)
                case "剪贴板": link.sendClipboard()
                case "图片": link.chooseFiles(imagesOnly: true)
                default: link.chooseFiles()
                }
            }.disabled(!link.canSend || (model.type == "文字" && model.text.isEmpty))
            if let progress = link.queueTitle { Text(progress).foregroundStyle(.secondary) }
            if let error = link.serviceError { Text(error).foregroundStyle(.orange).textSelection(.enabled) }
        }
        Section {
            if s.trusted.isEmpty {
                Row(title: "还没有配对的手机", subtitle: "点「配对手机」，用手机上的织文扫描二维码")
            }
            ForEach(s.trusted) { p in
                HStack {
                    Image(systemName: p.connected ? "iphone.radiowaves.left.and.right" : "iphone")
                        .foregroundStyle(p.connected ? Theme.accent : .secondary)
                        .frame(width: 22)
                    Row(title: p.displayName, subtitle: LinkText.peerStatus(p))
                    Spacer()
                    if !p.connected { Button("重连") { link.connect(p, address: model.reconnectAddress) } }
                    Button("取消配对…") { model.confirmForget = p }
                }
            }
            HStack {
                Spacer()
                Button("配对手机…") { link.openPairing() }
                    .buttonStyle(.borderedProminent)
                    .disabled(!s.running)
            }
        } header: {
            Text("我的设备")
        }
        Section {
            if s.nearby.isEmpty {
                HStack(spacing: 10) {
                    if link.scanning { ProgressView().controlSize(.small) }
                    Row(title: link.scanning ? "正在查找…" : "未发现附近设备", subtitle: link.discoveryError ?? "两端需开启互联；访客 Wi-Fi 或设备隔离可能阻止发现")
                }
            }
            ForEach(s.nearby) { n in
                HStack {
                    Row(title: n.displayName, subtitle: LinkText.platform(n.platform) + " · 还没有配对")
                    Spacer()
                    Button("配对") { link.openPairing() }
                }
            }
            Button("重新扫描") { link.rescan() }
        } header: {
            Text("附近的设备")
        }
        Section("直接地址与远程连接") {
            TextField("对方 IPv4:端口 或 [IPv6]:端口", text: $model.address)
            TextField("对方的 6 位配对码", text: $model.code)
            Button("用地址配对") { link.pair(address: model.address, code: model.code) }
                .disabled(model.address.isEmpty || model.code.count != 6)
            TextField("已配对设备的新地址（重连时使用）", text: $model.reconnectAddress)
            TextField("本机公网地址（用于配对二维码，可留空）", text: $prefs.linkPublicAddress)
            Text("本机监听端口 \(s.info.port)。远程直传需公网 IPv6、IPv4 端口映射或直连 VPN。两端都在运营商 NAT 后时，无法保证无服务器直连。")
                .font(.callout).foregroundStyle(.secondary).textSelection(.enabled)
        }
        if !s.transfers.isEmpty {
            Section("最近传输") {
                ForEach(s.transfers.prefix(6)) { t in TransferRow(transfer: t, reveal: link.reveal) }
            }
        }
        Section {
            HStack(alignment: .top) {
                Text("在此页面选择发送类型和接收设备。手机上长按图片或文件后选「分享 › WeaveText · 发送到设备」。接收文件默认存入下载目录。")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                Spacer()
                Button("打开文件夹") { link.revealInbox() }
            }
        }
    }
}

/// 两行文字：标题与说明。 Two lines: title and subtitle.
private struct Row: View {
    let title: String
    let subtitle: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
            Text(subtitle).font(.callout).foregroundStyle(.secondary)
        }
    }
}

private struct TransferRow: View {
    let transfer: LinkTransfer
    let reveal: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Image(systemName: transfer.incoming ? "arrow.down.circle" : "arrow.up.circle")
                    .foregroundStyle(transfer.state == .failed ? Color.red : Theme.accent)
                Text(transfer.name).lineLimit(1).truncationMode(.middle)
                Spacer()
                if transfer.incoming, transfer.state == .done, let path = transfer.path {
                    Button("在访达中显示") { reveal(path) }.buttonStyle(.link)
                }
            }
            Text(transfer.status + (transfer.peer.isEmpty ? "" : " · " + transfer.peer))
                .font(.callout)
                .foregroundStyle(transfer.state == .failed ? Color.red : .secondary)
            if transfer.state == .running {
                ProgressView(value: transfer.fraction).progressViewStyle(.linear)
            }
        }
        .padding(.vertical, 2)
    }
}

/// 配对窗口：二维码、6 位配对码、地址与两分钟倒计时；配对成功后自动关闭。
/// The pairing sheet: QR code, 6-digit code, addresses and a two-minute countdown; closes itself once paired.
struct PairingSheet: View {
    @ObservedObject var link: LinkService

    var body: some View {
        VStack(spacing: 16) {
            if let p = link.state.pairing {
                if let name = p.pairedWith {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 56))
                        .foregroundStyle(Theme.accent)
                    Text("已与「\(name)」配对").font(.title3.weight(.semibold))
                    Text("之后发现对方或直接地址可达时会自动连接。").foregroundStyle(.secondary)
                } else {
                    open(p)
                }
            }
            HStack {
                Spacer()
                Button(link.state.pairing?.pairedWith == nil ? "取消" : "完成") { link.closePairing() }
                    .keyboardShortcut(.cancelAction)
            }
        }
        .padding(24)
        .frame(width: 400)
    }

    @ViewBuilder private func open(_ p: LinkPairing) -> some View {
        VStack(spacing: 6) {
            Text("配对手机").font(.title2.weight(.semibold))
            Text("在手机上打开织文 › 设置 › 互联，扫描下面的二维码；或在「附近的设备」里选这台 Mac，输入配对码。")
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        TimelineView(.periodic(from: .now, by: 1)) { ctx in
            let left = p.remaining(at: ctx.date)
            VStack(spacing: 12) {
                ZStack {
                    QRView(text: p.uri)
                        .opacity(p.expired(at: ctx.date) ? 0.12 : 1)
                    if p.expired(at: ctx.date) {
                        VStack(spacing: 8) {
                            Text("配对码已失效").font(.headline)
                            Button("重新生成") { link.openPairing() }
                        }
                    }
                }
                Text(Self.spaced(p.code))
                    .font(.system(size: 34, weight: .semibold, design: .monospaced))
                    .foregroundStyle(p.expired(at: ctx.date) ? .secondary : Theme.candidate)
                    .textSelection(.enabled)
                Text(p.expired(at: ctx.date) ? "已过期" : String(format: "%d:%02d 后失效", left / 60, left % 60))
                    .font(.callout.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
        }
        if !p.addrs.isEmpty {
            Text("地址 " + p.addrs.joined(separator: " · "))
                .font(.callout.monospaced())
                .foregroundStyle(.secondary)
                .textSelection(.enabled)
                .multilineTextAlignment(.center)
        }
        if let m = p.message {
            Label(m, systemImage: "exclamationmark.triangle").font(.callout).foregroundStyle(.orange)
        }
    }

    static func spaced(_ code: String) -> String {
        code.count == 6 ? code.prefix(3) + " " + code.suffix(3) : code
    }
}

/// 二维码：始终白底黑块（深色模式下扫码更稳）；按整数倍像素生成，显示时不再缩放，边缘清晰。
/// The QR code: always dark modules on white (scans reliably in dark mode too); generated at an integer pixel multiple
/// and shown unscaled, so the edges stay crisp.
struct QRView: View {
    let text: String
    var side: CGFloat = 200

    private static var cache: (String, CGImage)?
    /// 按 2x 屏生成。 Generated for 2x screens.
    private static let pixelScale: CGFloat = 2

    private var image: CGImage? {
        if let c = Self.cache, c.0 == text { return c.1 }
        guard let raw = QRCode.image(for: text, scale: 1, margin: 0) else { return nil }
        let k = max(1, Int(side * Self.pixelScale) / raw.width)
        guard let img = QRCode.image(for: text, scale: k, margin: 0) else { return nil }
        Self.cache = (text, img)
        return img
    }

    var body: some View {
        Group {
            if let image {
                Image(decorative: image, scale: Self.pixelScale).interpolation(.none)
            } else {
                Color.clear.frame(width: side, height: side)
            }
        }
        .padding(12)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(Theme.divider, lineWidth: 1))
    }
}
