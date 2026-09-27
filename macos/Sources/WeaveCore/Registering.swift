import Foundation

/// 系统输入源列表里的一项（只取登记时要看的几个属性）。 One entry of the system input source list (just the properties
/// registering looks at).
public struct InputSourceState: Equatable, Sendable {
    public var id: String
    public var enableCapable: Bool
    public var selectCapable: Bool
    public var enabled: Bool
    public var selected: Bool

    public init(id: String, enableCapable: Bool = true, selectCapable: Bool = true, enabled: Bool = false,
                selected: Bool = false) {
        self.id = id
        self.enableCapable = enableCapable
        self.selectCapable = selectCapable
        self.enabled = enabled
        self.selected = selected
    }
}

/// 系统输入源接口（TIS）最底下的几个调用，测试时换成假的。只能在主线程上调用。
/// The lowest-level calls of the system input-source API (TIS), a fake in tests. Main thread only.
public protocol InputSourceBackend {
    /// 登记包，返回系统状态码（0 为成功）。 Register the bundle; returns the system status (0 on success).
    @MainActor func register(_ bundleURL: URL) -> Int32
    /// 本输入法的全部输入源。 All of this input method's sources.
    @MainActor func sources() -> [InputSourceState]
    @MainActor func enable(_ id: String)
    @MainActor func select(_ id: String)
    /// 广播「已启用的输入源变了」，让输入法菜单重新读列表。 Broadcast that the enabled sources changed so the input menu
    /// re-reads the list.
    @MainActor func announce()
}

/// 一次登记的结果。 What one registration did.
public struct RegisterReport: Equatable, Sendable {
    /// 这次新启用的输入源。 Sources enabled by this run.
    public var enabled: [String] = []
    /// 这次选中了拼音。 Pinyin was selected by this run.
    public var selected = false
    /// 登记后拼音输入源在列表里且已启用。 After registering, the pinyin source is listed and enabled.
    public var listed = false

    /// 什么都不用做（再跑一次时就是这样）。 Nothing needed doing (as on a second run).
    public var unchanged: Bool { enabled.isEmpty && !selected }

    public var summary: String {
        guard listed else { return "registered; the input mode is not listed yet (log out and back in once)" }
        if unchanged { return "registered; already enabled and selected" }
        var parts = ["registered"]
        if !enabled.isEmpty { parts.append("enabled \(enabled.joined(separator: ", "))") }
        if selected { parts.append("selected") }
        return parts.joined(separator: "; ")
    }
}

/// 登记、启用并选中。可以反复跑：已启用的不再启用，已选中的不再选中，没有变化时也不广播。
/// Register, enable and select. Safe to run again and again: enabled sources are not re-enabled, a selected mode is not
/// re-selected, and nothing is broadcast when nothing changed.
public enum RegisterFlow {
    @MainActor
    public static func run(bundleURL: URL, modeID: String, backend: InputSourceBackend) throws -> RegisterReport {
        let status = backend.register(bundleURL)
        guard status == 0 else { throw InstallError.registerFailed(status) }
        var report = RegisterReport()
        let before = backend.sources()
        for s in before where s.enableCapable && !s.enabled {
            backend.enable(s.id)
            report.enabled.append(s.id)
        }
        // 启用之后再看一次：没启用的输入源不能选中。 Look again after enabling: a disabled source can't be selected.
        if let mode = backend.sources().first(where: { $0.id == modeID }), mode.selectCapable, !mode.selected {
            backend.select(modeID)
            report.selected = true
        }
        if !report.unchanged { backend.announce() }
        report.listed = backend.sources().contains { $0.id == modeID && $0.enabled }
        return report
    }
}

/// 安装日志 ~/Library/Logs/WeaveText-install.log（`--register` 与安装程序写，一行一条，带时间）。
/// The install log ~/Library/Logs/WeaveText-install.log (written by `--register` and the installer; one timestamped
/// line per entry).
public struct InstallLog: Sendable {
    public let url: URL

    public init(url: URL = InstallLog.defaultURL) { self.url = url }

    public static var defaultURL: URL {
        FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Logs/WeaveText-install.log")
    }

    /// 写不进去就算了：日志不能让登记失败。 Failures are ignored: the log must never make registering fail.
    public func append(_ message: String, date: Date = Date()) {
        let stamp = ISO8601DateFormatter.string(from: date, timeZone: .current,
                                                formatOptions: [.withInternetDateTime])
        let line = Data("\(stamp) \(message)\n".utf8)
        let fm = FileManager.default
        try? fm.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        if let handle = try? FileHandle(forWritingTo: url) {
            defer { try? handle.close() }
            _ = try? handle.seekToEnd()
            try? handle.write(contentsOf: line)
        } else {
            try? line.write(to: url)
        }
    }
}

/// 需要管理员权限才能删的副本（装在 /Library/Input Methods 里的那份）用的 AppleScript。只接受「Input Methods」里的
/// WeaveText.app，路径逐个加引号。
/// The AppleScript for copies only an administrator may delete (the one in /Library/Input Methods). Only WeaveText.app
/// inside an "Input Methods" folder is accepted, and every path is quoted.
public enum AdminScript {
    public static let packageID = "com.weavetext.inputmethod.WeaveText.pkg"

    public static func isRemovable(_ url: URL) -> Bool {
        let std = url.standardizedFileURL
        return std.lastPathComponent == Installer.bundleName
            && std.deletingLastPathComponent().lastPathComponent == "Input Methods"
            && !std.path.contains("\"") && !std.path.contains("\\") && !std.path.contains("\n")
    }

    /// 删除这些副本，并忘掉安装包的回执（没有回执时不算错）。有不认识的路径时返回 nil。
    /// Remove these copies and forget the package receipt (a missing receipt is fine). Returns nil for any unexpected
    /// path.
    public static func remove(_ bundles: [URL]) -> String? {
        guard !bundles.isEmpty, bundles.allSatisfy(isRemovable) else { return nil }
        let rm = bundles.map { "quoted form of \"\($0.standardizedFileURL.path)\"" }.joined(separator: " & \" \" & ")
        return "do shell script \"/bin/rm -rf \" & \(rm) & \"; /usr/sbin/pkgutil --forget \(packageID) >/dev/null 2>&1; true\""
            + " with prompt \"卸载织文输入法需要管理员权限。\" with administrator privileges"
    }
}

/// 在程序旁边找安装包（从磁盘映像或下载文件夹打开时）。 Look for the installer package next to the app (when opened
/// from the disk image or Downloads).
public enum PackageLocator {
    public static func package(nextTo bundle: URL, fileManager: FileManager = .default) -> URL? {
        let dir = bundle.standardizedFileURL.deletingLastPathComponent()
        guard let names = try? fileManager.contentsOfDirectory(atPath: dir.path) else { return nil }
        let pkgs = names.filter { $0.lowercased().hasSuffix(".pkg") && !$0.hasPrefix(".") }.sorted()
        let ours = pkgs.first { $0.contains("织文") || $0.lowercased().contains("weavetext") }
        return ours.map { dir.appendingPathComponent($0) }
    }
}
