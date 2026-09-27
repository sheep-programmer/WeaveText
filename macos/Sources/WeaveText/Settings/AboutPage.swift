import ServiceManagement
import SwiftUI
import WeaveCore

/// 卸载的临时状态（@State 不可用，见 Pages.swift）。 Transient uninstall state (@State is unavailable, see Pages.swift).
@MainActor
final class UninstallModel: ObservableObject {
    @Published var purge = false
    @Published var confirming = false
    @Published var error: String?

    /// 停用输入源、移到废纸篓后直接退出；删除数据时不再写回用户词。
    /// Disable the sources, move to the Trash and quit at once; when the data goes too, nothing is written back.
    func uninstall() {
        let bundle = Bundle.main.bundleURL
        let installer = Installer.system(inputMethodsDir: bundle.deletingLastPathComponent())
        if !purge { EngineHost.shared.engine?.flush() }
        if SMAppService.mainApp.status == .enabled { try? SMAppService.mainApp.unregister() }
        do {
            try installer.uninstall(bundle: bundle, trash: SystemTrash(),
                                    userData: purge ? EngineHost.userDirectory() : nil,
                                    defaults: purge ? (.standard, Installer.bundleID) : nil)
        } catch {
            self.error = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            return
        }
        let done = NSAlert()
        done.messageText = "已卸载织文输入法"
        done.informativeText = "如果键盘设置的输入法列表里还留着「织文拼音」，把它移除即可。"
        done.addButton(withTitle: "好")
        NSApp.activate(ignoringOtherApps: true)
        done.runModal()
        exit(0)
    }
}

/// 关于：版本、链接与随包数据的许可（与 docs/THIRD_PARTY.md 一致）。
/// About: version, links, and licences of the shipped data (as in docs/THIRD_PARTY.md).
struct AboutPage: View {
    private static let repo = "https://github.com/sheep-programmer/WeaveText"

    private struct Notice: Identifiable {
        let name: String
        let use: String
        let license: String
        let url: String
        var id: String { name }
    }

    private let notices = [
        Notice(name: "万象拼音词库 rime_wanxiang", use: "拼音字词与词频、英文词频、表情联想；专业词库的领域词表",
               license: "CC BY 4.0", url: "https://github.com/amzxyz/rime_wanxiang"),
        Notice(name: "THUOCL 清华开放中文词库", use: "专业词库（可选下载）的领域词表，按需下载的独立数据文件", license: "MIT",
               url: "https://github.com/thunlp/THUOCL"),
        Notice(name: "RIME-LMDG 字符搭配模型", use: "整句组词", license: "CC BY 4.0",
               url: "https://github.com/amzxyz/RIME-LMDG"),
        Notice(name: "OpenCC 简繁转换表", use: "繁体输出", license: "Apache-2.0",
               url: "https://github.com/BYVoid/OpenCC"),
        Notice(name: "rime-wubi 五笔 86 码表", use: "五笔 86（独立、可替换的数据文件 wubi86.wvz）", license: "LGPL-3.0",
               url: "https://github.com/rime/rime-wubi"),
        Notice(name: "memmap2", use: "词库内存映射", license: "MIT OR Apache-2.0",
               url: "https://github.com/RazrFalcon/memmap2-rs"),
        Notice(name: "brotli-decompressor", use: "分块压缩词库解压", license: "BSD-3-Clause OR MIT",
               url: "https://github.com/dropbox/rust-brotli-decompressor"),
        Notice(name: "serde_json", use: "内核接口数据", license: "MIT OR Apache-2.0",
               url: "https://github.com/serde-rs/json"),
        Notice(name: "snow（含 chacha20poly1305、blake2、aes-gcm 等 RustCrypto 组件）", use: "织文互联的 Noise 加密通道",
               license: "Apache-2.0 OR MIT", url: "https://github.com/mcginty/snow"),
        Notice(name: "curve25519-dalek、subtle", use: "织文互联的密钥交换", license: "BSD-3-Clause",
               url: "https://github.com/dalek-cryptography/curve25519-dalek"),
        Notice(name: "ed25519-dalek", use: "云端热词的签名校验", license: "BSD-3-Clause",
               url: "https://github.com/dalek-cryptography/curve25519-dalek"),
        Notice(name: "spake2（含 hkdf、hmac、sha2）", use: "织文互联配对码的口令认证密钥交换", license: "MIT OR Apache-2.0",
               url: "https://github.com/RustCrypto/PAKEs"),
        Notice(name: "mdns-sd（含 flume、socket2、if-addrs）", use: "织文互联局域网发现", license: "Apache-2.0 OR MIT",
               url: "https://github.com/keepsimple1/mdns-sd"),
        Notice(name: "serde", use: "织文互联的设备列表", license: "MIT OR Apache-2.0", url: "https://github.com/serde-rs/serde"),
        Notice(name: "mio、spin、zmij、getrandom 等", use: "网络、并发与随机数等基础组件", license: "MIT / MIT OR Apache-2.0",
               url: "https://github.com/tokio-rs/mio"),
    ]

    /// 隐私说明（与 Android 的隐私页一致）。 The privacy notes, as on Android's privacy page.
    private let privacy: [(String, String)] = [
        ("本机处理", "拼音、五笔、联想与用户词学习全部在本机完成，织文不收集、不上传你的输入内容。"),
        ("云端热词", "默认关闭。开启后每天从公开的织文热词库下载一次词表（带签名校验），只下载、不上传，你的输入不会因此离开这台 Mac。"),
        ("专业词库", "按需从织文的 GitHub 发布页下载，下载时只请求词库文件本身。"),
        ("织文互联", "默认关闭。开启后只在同一局域网内与你配对过的设备直接通信，全程端到端加密，不经过任何服务器。"),
    ]

    @StateObject private var removal = UninstallModel()

    private var version: String {
        let info = Bundle.main.infoDictionary
        let v = info?["CFBundleShortVersionString"] as? String ?? "0.1.0"
        let b = info?["CFBundleVersion"] as? String ?? ""
        return b.isEmpty || b == v ? v : "\(v) (\(b))"
    }

    var body: some View {
        Form {
            Section {
                HStack(spacing: 14) {
                    Image(nsImage: NSApp.applicationIconImage)
                        .resizable()
                        .frame(width: 64, height: 64)
                    VStack(alignment: .leading, spacing: 3) {
                        Text("织文输入法").font(.title2.weight(.semibold))
                        Text("版本 \(version)").foregroundStyle(.secondary)
                        Text("自研内核，离线、隐私优先。代码以 Apache-2.0 许可开源。")
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 4)
                Link("源代码与问题反馈", destination: URL(string: Self.repo)!)
                Link("第三方组件与数据", destination: URL(string: Self.repo + "/blob/main/docs/THIRD_PARTY.md")!)
            }
            Section("隐私") {
                ForEach(privacy, id: \.0) { title, text in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title)
                        Text(text).font(.callout).foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            Section {
                ForEach(notices) { n in
                    VStack(alignment: .leading, spacing: 2) {
                        HStack {
                            Link(n.name, destination: URL(string: n.url)!)
                            Spacer()
                            Text(n.license).font(.callout.monospaced()).foregroundStyle(.secondary)
                        }
                        Text(n.use).font(.callout).foregroundStyle(.secondary)
                    }
                }
            } header: {
                Text("开源许可")
            } footer: {
                Footnote("本应用词库数据部分来自万象拼音（amzxyz/rime_wanxiang），依 CC BY 4.0 授权使用，已做格式转换；"
                         + "专业词库另含 THUOCL 清华开放中文词库（thunlp/THUOCL，MIT）。")
            }
            Section {
                Toggle("同时删除词库与设置", isOn: $removal.purge)
                HStack {
                    Spacer()
                    Button("卸载织文输入法…", role: .destructive) { removal.confirming = true }
                }
            } header: {
                Text("卸载")
            } footer: {
                Footnote(removal.purge ? "织文会移到废纸篓；用户词、专业词库、热词与互联配对也一起移到废纸篓，偏好设置会被清除。"
                         : "织文会移到废纸篓；用户词、专业词库与设置留在本机，以后重新安装还能接着用。")
            }
        }
        .formStyle(.grouped)
        .alert("卸载织文输入法？", isPresented: $removal.confirming) {
            Button("卸载", role: .destructive, action: removal.uninstall)
            Button("取消", role: .cancel) {}
        } message: {
            Text(removal.purge ? "输入法与你的词库、设置都会移到废纸篓。" : "输入法会移到废纸篓，词库与设置保留。")
        }
        .alert("没能卸载", isPresented: Binding(get: { removal.error != nil }, set: { if !$0 { removal.error = nil } })) {
            Button("好") {}
        } message: {
            Text(removal.error ?? "")
        }
    }
}
