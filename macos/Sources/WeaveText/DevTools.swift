import AppKit
import SwiftUI
import WeaveCore

/// 构建脚本用的自检与截图（不启动输入法服务）。 Self-test and snapshots for the build script (no IME server).
enum DevTools {
    /// 用包内词库打「nihao」，首选应为「你好」。 Type "nihao" with the bundled data; the top pick must be 你好.
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
        for scheme in InputScheme.all where !e.setSchema(scheme.id) {
            print("selftest: no dictionary for \(scheme.id)")
            ok = false
        }
        e.setSchema("pinyin")
        "nihao".forEach { _ = e.input($0) }
        let first = e.snapshot().candidates.first?.text ?? "-"
        print("selftest: nihao → \(first)")
        return ok && first == "你好"
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
        for (name, state) in [("candidates-horizontal", horizontal), ("candidates-vertical", vertical)] {
            for dark in [false, true] {
                let view = CandidateBar(state: state, pick: { _ in })
                    .background(Color(nsColor: .windowBackgroundColor))
                    .clipShape(RoundedRectangle(cornerRadius: CandidatePanel.cornerRadius))
                    .padding(12)
                try render(view, size: nil, dark: dark, to: dir.appendingPathComponent("\(name)-\(dark ? "dark" : "light").png"))
            }
        }
        let nav = SettingsNavigation()
        for page in SettingsPage.allCases {
            nav.page = page
            let root = SettingsRoot(prefs: .shared, navigation: nav).tint(Theme.accent)
            try render(root, size: NSSize(width: 720, height: 560), dark: false,
                       to: dir.appendingPathComponent("settings-\(page.rawValue).png"))
        }
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
