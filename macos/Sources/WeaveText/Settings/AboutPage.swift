import SwiftUI

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
        Notice(name: "万象拼音词库 rime_wanxiang", use: "拼音字词与词频、英文词频、表情联想", license: "CC BY 4.0",
               url: "https://github.com/amzxyz/rime_wanxiang"),
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
        Notice(name: "spake2（含 hkdf、hmac、sha2）", use: "织文互联配对码的口令认证密钥交换", license: "MIT OR Apache-2.0",
               url: "https://github.com/RustCrypto/PAKEs"),
        Notice(name: "mdns-sd（含 flume、socket2、if-addrs）", use: "织文互联局域网发现", license: "Apache-2.0 OR MIT",
               url: "https://github.com/keepsimple1/mdns-sd"),
        Notice(name: "serde", use: "织文互联的设备列表", license: "MIT OR Apache-2.0", url: "https://github.com/serde-rs/serde"),
        Notice(name: "mio、spin、zmij、getrandom 等", use: "网络、并发与随机数等基础组件", license: "MIT / MIT OR Apache-2.0",
               url: "https://github.com/tokio-rs/mio"),
    ]

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
                Footnote("本应用词库数据部分来自万象拼音（amzxyz/rime_wanxiang），依 CC BY 4.0 授权使用，已做格式转换。")
            }
        }
        .formStyle(.grouped)
    }
}
