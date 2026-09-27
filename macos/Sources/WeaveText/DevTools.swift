import AppKit
import SwiftUI
import WeaveCore

/// 构建脚本用的自检与截图（不启动输入法服务）。 Self-test and snapshots for the build script (no IME server).
enum DevTools {
    /// 用包内词库打「nihao」，首选应为「你好」；上屏「今天」后有联想。 Type "nihao" with the bundled data; the top pick
    /// must be 你好, and committing 今天 must offer predictions.
    static func selfTest() -> Bool {
        let data = Bundle.main.resourceURL!.appendingPathComponent("data").path
        let user = FileManager.default.temporaryDirectory.appendingPathComponent("weavetext-selftest-\(getpid())")
        try? FileManager.default.createDirectory(at: user, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: user) }
        guard let e = WeaveSession(dataDir: data, userDir: user.path) else {
            print("selftest: engine failed to load \(data)")
            return false
        }
        var ok = true
        for scheme in InputScheme.all where !e.hasSchema(scheme.id) {
            print("selftest: no dictionary for \(scheme.id)")
            ok = false
        }
        e.setSchema("pinyin")
        "nihao".forEach { _ = e.input($0) }
        let first = e.snapshot().candidates.first?.text ?? "-"
        print("selftest: nihao → \(first)")
        e.clear()
        "v(128+32)*4".forEach { _ = e.input($0) }
        let calc = e.snapshot().candidates.first?.text ?? "-"
        print("selftest: v(128+32)*4 → \(calc)")
        e.clear()
        // 联想表随包：上屏「今天」后应有联想。 The prediction table ships: committing 今天 must predict.
        "jintian".forEach { _ = e.input($0) }
        if let i = e.snapshot().candidates.firstIndex(where: { $0.text == "今天" }) { e.select(i) }
        let next = e.snapshot()
        print("selftest: 今天 → \(next.candidates.prefix(5).map(\.text).joined(separator: " "))")
        return ok && first == "你好" && calc == "640" && next.predicting && !next.candidates.isEmpty
    }

    /// 把候选窗与设置页画成 PNG。 Render the candidate bar and the settings pages to PNG.
    static func snapshot(into dir: URL) throws {
        _ = NSApplication.shared
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let words = ["你好", "你", "妮", "拟好", "泥", "尼", "逆"]
        let horizontal = CandidateState(preedit: "ni hao", candidates: words.map { Candidate(text: $0) }, highlight: 0,
                                        hasPrevious: false, hasNext: true, orientation: .horizontal, fontSize: 16)
        var vertical = horizontal
        vertical.orientation = .vertical
        vertical.highlight = 1
        vertical.hasPrevious = true
        vertical.candidates[3].comment = "ni hao"
        let money = CandidateState(
            preedit: "v1234",
            candidates: [Candidate(text: "1234"), Candidate(text: "壹仟贰佰叁拾肆元整", comment: "大写金额"),
                         Candidate(text: "一千二百三十四", comment: "中文数字"), Candidate(text: "1,234", comment: "千分位")],
            highlight: 0, hasPrevious: false, hasNext: false, orientation: .horizontal, fontSize: 16)
        for (name, state) in [("candidates-horizontal", horizontal), ("candidates-vertical", vertical),
                              ("candidates-comments", money)] {
            for dark in [false, true] {
                let view = CandidateBar(state: state, pick: { _ in })
                    .background(Color(nsColor: .windowBackgroundColor))
                    .clipShape(RoundedRectangle(cornerRadius: CandidatePanel.cornerRadius))
                    .padding(12)
                try render(view, size: nil, dark: dark, to: dir.appendingPathComponent("\(name)-\(dark ? "dark" : "light").png"))
            }
        }
        // 互联页与配对窗口用示例状态画；偏好用一次性的域，不碰真实设置。 The link page and pairing sheet use a sample
        // state; preferences live in a throwaway domain, never the real settings.
        let link = LinkService.shared
        link.preview(linkSample())
        let suite = "com.weavetext.inputmethod.WeaveText.snapshot"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let prefs = Preferences(defaults: defaults)
        prefs.linkEnabled = true
        var pairing = linkSample()
        pairing.pairing = LinkPairing(json: [
            "code": "482913", "expiresIn": 120, "addrs": ["192.168.1.8:47811", "10.0.0.5:47811"],
            "uri": "weavelink://pair?v=1&id=4f2a9c0d1e7b&n=MacBook&p=mac&a=192.168.1.8%3A47811&c=482913",
        ])
        for dark in [false, true] {
            let sheet = PairingSheet(link: link).background(Color(nsColor: .windowBackgroundColor))
            link.preview(pairing)
            try render(sheet, size: nil, dark: dark, to: dir.appendingPathComponent("link-pairing-\(dark ? "dark" : "light").png"))
        }
        link.preview(linkSample())
        let nav = SettingsNavigation()
        for page in SettingsPage.allCases {
            nav.page = page
            let root = SettingsRoot(prefs: prefs, navigation: nav).tint(Theme.accent)
            try render(root, size: NSSize(width: 720, height: 560), dark: false,
                       to: dir.appendingPathComponent("settings-\(page.rawValue).png"))
        }
        nav.page = .link
        try render(SettingsRoot(prefs: prefs, navigation: nav).tint(Theme.accent), size: NSSize(width: 720, height: 900),
                   dark: true, to: dir.appendingPathComponent("settings-link-dark.png"))
    }

    private static func linkSample() -> LinkState {
        var s = LinkState()
        s.running = true
        s.info.fingerprint = "AB12-CD34-EF56-7890"
        s.trusted = [
            LinkPeer(id: "a", name: "Pixel 9", platform: "android", connected: true, nearby: true),
            LinkPeer(id: "b", name: "家里的平板", platform: "android", connected: false, nearby: false),
        ]
        s.nearby = [LinkNearby(id: "c", name: "会议室的手机", platform: "android")]
        s.transfers = [
            LinkTransfer(id: "t1", name: "旅行照片.jpg", incoming: true, peer: "Pixel 9", done: 3_300_000, size: 7_800_000),
            LinkTransfer(id: "t2", name: "季度报告.pdf", incoming: false, peer: "Pixel 9", done: 1_200_000, size: 1_200_000,
                         state: .done),
        ]
        return s
    }

    private static func render<V: View>(_ view: V, size: NSSize?, dark: Bool, to url: URL) throws {
        let hosting = NSHostingView(rootView: view)
        let frame = NSRect(origin: .zero, size: size ?? hosting.fittingSize)
        let window = NSWindow(contentRect: frame, styleMask: [.titled], backing: .buffered, defer: false)
        window.appearance = NSAppearance(named: dark ? .darkAqua : .aqua)
        window.contentView = hosting
        hosting.frame = frame
        // 让 SwiftUI 完成几轮布局。 Let SwiftUI settle a few layout passes.
        for _ in 0..<5 {
            hosting.layoutSubtreeIfNeeded()
            RunLoop.current.run(until: Date().addingTimeInterval(0.05))
        }
        guard let rep = hosting.bitmapImageRepForCachingDisplay(in: hosting.bounds) else { return }
        hosting.cacheDisplay(in: hosting.bounds, to: rep)
        try rep.representation(using: .png, properties: [:])?.write(to: url)
    }
}
