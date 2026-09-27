import Darwin
import Foundation

/// 这次启动是输入法本身，还是从别处双击打开的安装程序。
/// Whether this launch is the input method itself or the installer, double-clicked from somewhere else.
public enum LaunchMode: Equatable, Sendable {
    case inputMethod
    case installer

    /// 强制以输入法方式运行的参数。 The argument that forces input-method mode.
    public static let imeFlag = "--ime"

    /// 放在某个「Input Methods」目录里（系统从那里拉起），或带着 `--ime`，就是输入法；否则是安装程序。
    /// Inside an "Input Methods" directory (where the system launches it from) or with `--ime` it is the input method;
    /// anywhere else it is the installer.
    public static func decide(bundleURL: URL, arguments: [String], inputMethodDirs: [URL]) -> LaunchMode {
        if arguments.dropFirst().contains(imeFlag) { return .inputMethod }
        let parent = bundleURL.standardizedFileURL.resolvingSymlinksInPath().deletingLastPathComponent().path
        for dir in inputMethodDirs where dir.standardizedFileURL.resolvingSymlinksInPath().path == parent {
            return .inputMethod
        }
        return .installer
    }

    /// ~/Library/Input Methods 与 /Library/Input Methods。 The per-user and the system Input Methods directories.
    public static func systemInputMethodDirs(home: URL = FileManager.default.homeDirectoryForCurrentUser) -> [URL] {
        [home.appendingPathComponent("Library/Input Methods", isDirectory: true),
         URL(fileURLWithPath: "/Library/Input Methods", isDirectory: true)]
    }
}

/// 版本号：`0.1.0`、`0.2.0-beta.1` 之类，再加构建号。预发布版排在同号正式版之前，其余相同时比构建号。
/// A version such as `0.1.0` or `0.2.0-beta.1` plus the build number. A pre-release sorts before the release of the
/// same number; otherwise equal versions compare by build.
public struct AppVersion: Comparable, CustomStringConvertible, Sendable {
    public let short: String
    public let build: Int
    private let numbers: [Int]
    private let pre: [String]

    public init(_ short: String, build: String? = nil) {
        let trimmed = short.trimmingCharacters(in: .whitespaces)
        self.short = trimmed
        self.build = Int(build ?? "") ?? 0
        let core = trimmed.split(separator: "+", maxSplits: 1).first.map(String.init) ?? ""
        let parts = core.split(separator: "-", maxSplits: 1).map(String.init)
        var n = (parts.first ?? "").split(separator: ".").map { Int($0) ?? 0 }
        while n.count > 1, n.last == 0 { n.removeLast() }
        numbers = n
        pre = parts.count > 1 ? parts[1].split(separator: ".").map(String.init) : []
    }

    /// 读一个 .app 的 Info.plist。 Read an .app's Info.plist.
    public init?(bundleAt url: URL) {
        let plist = url.appendingPathComponent("Contents/Info.plist")
        guard let data = try? Data(contentsOf: plist),
              let info = try? PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any],
              let short = info["CFBundleShortVersionString"] as? String else { return nil }
        self.init(short, build: info["CFBundleVersion"] as? String)
    }

    public var description: String { short }

    public static func == (a: AppVersion, b: AppVersion) -> Bool { !(a < b) && !(b < a) }

    public static func < (a: AppVersion, b: AppVersion) -> Bool {
        for i in 0..<max(a.numbers.count, b.numbers.count) {
            let x = i < a.numbers.count ? a.numbers[i] : 0
            let y = i < b.numbers.count ? b.numbers[i] : 0
            if x != y { return x < y }
        }
        if a.pre != b.pre {
            if a.pre.isEmpty { return false }
            if b.pre.isEmpty { return true }
            for (x, y) in zip(a.pre, b.pre) where x != y {
                switch (Int(x), Int(y)) {
                case let (i?, j?): return i < j
                case (_?, nil): return true
                case (nil, _?): return false
                default: return x < y
                }
            }
            return a.pre.count < b.pre.count
        }
        return a.build < b.build
    }
}

/// 安装窗口该给出的选择。 What the installer window should offer.
public enum InstallPlan: Equatable, Sendable {
    /// 还没装过。 Not installed yet.
    case fresh
    /// 已装的是旧版本。 An older version is installed.
    case update(from: AppVersion)
    /// 已装的是同一版本。 The same version is installed.
    case reinstall
    /// 已装的比这个新。 A newer version is installed.
    case newerInstalled(AppVersion)

    public static func decide(this: AppVersion, installed: AppVersion?) -> InstallPlan {
        guard let installed else { return .fresh }
        if installed < this { return .update(from: installed) }
        if this < installed { return .newerInstalled(installed) }
        return .reinstall
    }
}

/// 系统输入源（测试时换成假的）。 The system's input sources (a fake in tests).
public protocol InputSourceRegistry {
    /// 登记并启用、选中；返回是否已在输入源列表里找到。 Register, enable and select; returns whether the source
    /// showed up in the input source list.
    func registerAndEnable(bundleURL: URL) throws -> Bool
    /// 停用本输入法的全部输入源。 Disable all of this input method's sources.
    func disableAll()
}

/// 进程的退出与启动（测试时换成假的）。 Quitting and launching processes (a fake in tests).
public protocol AppControl {
    /// 让正在运行的旧副本（不含本进程）退出，并等它们退出。 Quit running copies (not this process) and wait for them.
    func quitRunningCopies(bundleID: String)
    /// 启动装好的那份。 Launch the installed copy.
    func launch(bundleURL: URL) throws
}

/// 废纸篓（测试时换成临时目录）。 The Trash (a temp directory in tests).
public protocol Trash {
    func moveToTrash(_ url: URL) throws
}

/// 用中文说明的安装错误。 Installer errors, explained in plain Chinese.
public enum InstallError: Error, Equatable, LocalizedError {
    case sourceMissing
    case sameLocation
    case copyFailed(String)
    case replaceFailed(String)
    case registerFailed(Int32)
    case launchFailed(String)
    case notInstalled
    case removeFailed(String)

    public var errorDescription: String? {
        switch self {
        case .sourceMissing: return "找不到要安装的程序文件。请重新打开下载的磁盘映像再试。"
        case .sameLocation: return "这份织文已经在输入法目录里了，不用再安装。"
        case .copyFailed(let why): return "没能把织文复制到「输入法」文件夹：\(why)"
        case .replaceFailed(let why): return "没能替换已安装的旧版本：\(why)。请先在菜单栏退出织文再试。"
        case .registerFailed(let code): return "已复制，但系统没有接受这个输入法（错误 \(code)）。请注销后重新登录，再到键盘设置里添加。"
        case .launchFailed(let why): return "已安装，但没能启动织文：\(why)。切换到「织文拼音」时系统会自动启动它。"
        case .notInstalled: return "没有找到已安装的织文输入法。"
        case .removeFailed(let why): return "没能移到废纸篓：\(why)"
        }
    }
}

/// 安装进度的各步。 The steps of an install, for progress text.
public enum InstallStep: Equatable, Sendable {
    case copying, quitting, replacing, registering, launching

    public var title: String {
        switch self {
        case .copying: return "正在复制…"
        case .quitting: return "正在退出旧版本…"
        case .replacing: return "正在替换…"
        case .registering: return "正在向系统登记输入法…"
        case .launching: return "正在启动…"
        }
    }
}

/// 安装结果。 How an install ended.
public struct InstallOutcome: Equatable, Sendable {
    public let installedURL: URL
    /// 输入源已出现在系统列表里；否则多半要注销一次。 The source is listed; otherwise a log-out is usually needed.
    public let listed: Bool
}

/// 把 .app 装进「Input Methods」：先复制到同一目录下的临时名、去掉隔离属性，退出旧副本，原子替换，登记启用，再启动。
/// Installs the .app into "Input Methods": copy to a temporary name in the same directory and strip quarantine, quit
/// old copies, swap atomically, register and enable, then launch.
public struct Installer {
    public static let bundleName = "WeaveText.app"
    public static let bundleID = "com.weavetext.inputmethod.WeaveText"
    public static let quarantine = "com.apple.quarantine"

    public let inputMethodsDir: URL
    public let registry: InputSourceRegistry
    public let apps: AppControl
    public var fileManager: FileManager = .default

    public init(inputMethodsDir: URL, registry: InputSourceRegistry, apps: AppControl) {
        self.inputMethodsDir = inputMethodsDir
        self.registry = registry
        self.apps = apps
    }

    public var destination: URL { inputMethodsDir.appendingPathComponent(Self.bundleName, isDirectory: true) }

    public var installedVersion: AppVersion? { AppVersion(bundleAt: destination) }

    @discardableResult
    public func install(from source: URL, progress: (InstallStep) -> Void = { _ in }) throws -> InstallOutcome {
        let fm = fileManager
        var isDir: ObjCBool = false
        guard fm.fileExists(atPath: source.path, isDirectory: &isDir), isDir.boolValue else { throw InstallError.sourceMissing }
        let dest = destination
        if source.standardizedFileURL.resolvingSymlinksInPath() == dest.standardizedFileURL.resolvingSymlinksInPath() {
            throw InstallError.sameLocation
        }

        progress(.copying)
        let staging = inputMethodsDir.appendingPathComponent(".WeaveText-\(UUID().uuidString).app", isDirectory: true)
        do {
            try fm.createDirectory(at: inputMethodsDir, withIntermediateDirectories: true)
            try fm.copyItem(at: source, to: staging)
            try Self.stripQuarantine(staging)
        } catch {
            try? fm.removeItem(at: staging)
            throw InstallError.copyFailed(Self.reason(error))
        }
        defer { try? fm.removeItem(at: staging) }

        progress(.quitting)
        apps.quitRunningCopies(bundleID: Self.bundleID)

        progress(.replacing)
        try Self.swap(staging, into: dest)
        // 替换前后系统可能又拉起了旧副本，再退出一次。 The system may have relaunched an old copy meanwhile; quit again.
        apps.quitRunningCopies(bundleID: Self.bundleID)

        progress(.registering)
        let listed = try registry.registerAndEnable(bundleURL: dest)

        progress(.launching)
        try apps.launch(bundleURL: dest)
        return InstallOutcome(installedURL: dest, listed: listed)
    }

    /// 原子替换：已有旧版本时与它互换（旧的留在临时名下随后删掉），否则直接改名。
    /// Atomic replace: swap with an existing copy (the old one ends up under the temporary name and is removed after),
    /// or a plain rename when there is none.
    static func swap(_ staging: URL, into dest: URL) throws {
        let flags = FileManager.default.fileExists(atPath: dest.path) ? UInt32(RENAME_SWAP) : UInt32(RENAME_EXCL)
        if renamex_np(staging.path, dest.path, flags) != 0 {
            throw InstallError.replaceFailed(String(cString: strerror(errno)))
        }
    }

    /// 递归去掉隔离属性（从网络下载的磁盘映像里复制出来的文件都带着它）。
    /// Remove the quarantine attribute recursively (everything copied out of a downloaded disk image carries it).
    public static func stripQuarantine(_ url: URL) throws {
        removexattr(url.path, quarantine, XATTR_NOFOLLOW)
        guard let e = FileManager.default.enumerator(atPath: url.path) else { return }
        for case let rel as String in e {
            removexattr(url.appendingPathComponent(rel).path, quarantine, XATTR_NOFOLLOW)
        }
    }

    /// 卸载：停用输入源，把程序移到废纸篓；勾选时连同用户数据目录一起移走、清掉偏好。
    /// Uninstall: disable the sources and move the bundle to the Trash; when asked, the user data directory goes too and
    /// the preferences are cleared.
    /// `bundle` 默认是输入法目录里的那份（从正在运行的输入法里卸载时传它自己的位置）。
    /// `bundle` defaults to the copy in the input methods directory (the running IME passes its own location).
    public func uninstall(bundle: URL? = nil, trash: Trash, userData: URL?,
                          defaults: (suite: UserDefaults, domain: String)? = nil) throws {
        let dest = bundle ?? destination
        guard fileManager.fileExists(atPath: dest.path) else { throw InstallError.notInstalled }
        registry.disableAll()
        do {
            try trash.moveToTrash(dest)
            if let userData, fileManager.fileExists(atPath: userData.path) { try trash.moveToTrash(userData) }
        } catch {
            throw InstallError.removeFailed(Self.reason(error))
        }
        if userData != nil, let defaults { defaults.suite.removePersistentDomain(forName: defaults.domain) }
    }

    static func reason(_ error: Error) -> String {
        let ns = error as NSError
        if let under = ns.userInfo[NSUnderlyingErrorKey] as? NSError, under.domain == NSPOSIXErrorDomain,
           let code = POSIXErrorCode(rawValue: Int32(under.code)) {
            return String(cString: strerror(code.rawValue))
        }
        return ns.localizedDescription
    }
}

/// 系统废纸篓。 The system Trash.
public struct SystemTrash: Trash {
    public init() {}
    public func moveToTrash(_ url: URL) throws { try FileManager.default.trashItem(at: url, resultingItemURL: nil) }
}
