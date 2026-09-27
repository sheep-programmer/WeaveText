import Darwin
import Foundation
import Security

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

/// 系统输入源（测试时换成假的）。系统的输入源接口（TIS）只能在主线程调用，在别的线程上会直接崩溃，所以都标成主线程。
/// The system's input sources (a fake in tests). The system input-source API (TIS) must only be called on the main
/// thread (it traps anywhere else), so every requirement is main-actor isolated.
public protocol InputSourceRegistry {
    /// 登记并启用、选中；返回是否已在输入源列表里找到并启用。 Register, enable and select; returns whether the source
    /// showed up in the input source list and is enabled.
    @MainActor func registerAndEnable(bundleURL: URL) throws -> Bool
    /// 停用本输入法的全部输入源。 Disable all of this input method's sources.
    @MainActor func disableAll()
}

/// 正在运行的旧副本（测试时换成假的）。同样只在主线程上用，等待不阻塞主线程。
/// Running old copies (a fake in tests). Also main-thread only; waiting never blocks the main thread.
public protocol AppControl {
    /// 让正在运行的旧副本（不含本进程）退出，并等它们退出。 Quit running copies (not this process) and wait for them.
    @MainActor func quitRunningCopies(bundleID: String) async
}

/// 包一层检查：每次调用都必须在主线程上，否则报告出来（调试版直接断言）。
/// A checking layer: every call must happen on the main thread, otherwise it is reported (asserted in debug builds).
public struct MainThreadChecked: InputSourceRegistry, AppControl {
    public let registry: InputSourceRegistry
    public let apps: AppControl
    public let violation: @Sendable (String) -> Void

    public init(registry: InputSourceRegistry, apps: AppControl,
                violation: @escaping @Sendable (String) -> Void = MainThreadChecked.fail) {
        self.registry = registry
        self.apps = apps
        self.violation = violation
    }

    /// 默认的报告方式：打到标准错误，调试版断言。 The default report: stderr, and an assertion in debug builds.
    public static let fail: @Sendable (String) -> Void = { call in
        fputs("\(call) called off the main thread\n", stderr)
        assertionFailure("\(call) called off the main thread")
    }

    func check(_ call: String) { if !Thread.isMainThread { violation(call) } }

    public func registerAndEnable(bundleURL: URL) throws -> Bool {
        check("registerAndEnable")
        return try registry.registerAndEnable(bundleURL: bundleURL)
    }

    public func disableAll() {
        check("disableAll")
        registry.disableAll()
    }

    public func quitRunningCopies(bundleID: String) async {
        check("quitRunningCopies")
        await apps.quitRunningCopies(bundleID: bundleID)
    }
}

/// 废纸篓（测试时换成临时目录）。 The Trash (a temp directory in tests).
public protocol Trash {
    func moveToTrash(_ url: URL) throws
}

/// 以管理员身份删除（系统会弹出密码框；测试时换成假的）。用户取消时抛出 `InstallError.cancelled`。
/// Remove as an administrator (the system asks for the password; a fake in tests). Throws `InstallError.cancelled` when
/// the user cancels.
public protocol AdminRemover {
    @MainActor func remove(_ bundles: [URL]) throws
}

/// 用中文说明的安装错误。 Installer errors, explained in plain Chinese.
public enum InstallError: Error, Equatable, LocalizedError {
    case sourceMissing
    case sameLocation
    case copyFailed(String)
    case replaceFailed(String)
    case registerFailed(Int32)
    case badSignature
    case notInstalled
    case removeFailed(String)
    case cancelled
    case needsAdmin(String)

    public var errorDescription: String? {
        switch self {
        case .sourceMissing: return "找不到要安装的程序文件。请重新打开下载的磁盘映像再试。"
        case .sameLocation: return "这份织文已经在输入法目录里了，不用再安装。"
        case .copyFailed(let why): return "没能把织文复制到「输入法」文件夹：\(why)"
        case .replaceFailed(let why): return "没能替换已安装的旧版本：\(why)。请先在菜单栏退出织文再试。"
        case .registerFailed(let code): return "已复制，但系统没有接受这个输入法（错误 \(code)）。请注销后重新登录，再到键盘设置里添加。"
        case .badSignature: return "复制出来的程序签名不完整，没有安装。请重新下载磁盘映像再试。"
        case .notInstalled: return "没有找到已安装的织文输入法。"
        case .removeFailed(let why): return "没能移到废纸篓：\(why)"
        case .cancelled: return "已取消，没有做任何改动。"
        case .needsAdmin(let path): return "「\(path)」需要管理员权限才能删除。请在终端运行：sudo rm -rf \"\(path)\""
        }
    }
}

/// 安装进度的各步。 The steps of an install, for progress text.
public enum InstallStep: Equatable, Sendable {
    case copying, quitting, replacing, registering

    public var title: String {
        switch self {
        case .copying: return "正在复制…"
        case .quitting: return "正在退出旧版本…"
        case .replacing: return "正在替换…"
        case .registering: return "正在向系统登记输入法…"
        }
    }
}

/// 安装结果。 How an install ended.
public struct InstallOutcome: Equatable, Sendable {
    public let installedURL: URL
    /// 输入源已出现在系统列表里并已启用；否则多半要注销一次。 The source is listed and enabled; otherwise a log-out is
    /// usually needed.
    public let listed: Bool
}

/// 把 .app 装进「Input Methods」：先复制到同一目录下的临时名、去掉隔离属性、核对签名，退出旧副本，原子替换，再登记启用。
/// 文件操作在后台做；输入源与进程的调用都回到主线程（见 `InputSourceRegistry`）。不主动启动装好的那份：切换到「织文拼音」时
/// 系统会自己拉起它。
/// Installs the .app into "Input Methods": copy to a temporary name in the same directory, strip quarantine and check
/// the signature, quit old copies, swap atomically, then register and enable. File work runs in the background; the
/// input-source and process calls go back to the main thread (see `InputSourceRegistry`). The installed copy is not
/// launched by hand: the system starts it when 织文拼音 is selected.
public struct Installer {
    public static let bundleName = "WeaveText.app"
    public static let bundleID = "com.weavetext.inputmethod.WeaveText"
    public static let quarantine = "com.apple.quarantine"

    public let inputMethodsDir: URL
    public let registry: InputSourceRegistry
    public let apps: AppControl
    public var fileManager: FileManager = .default
    /// 复制出来的包签名是否完好（系统安装程序用 `signatureIsValid`；测试里的假包没有签名）。
    /// Whether the copied bundle's signature is intact (the real installer uses `signatureIsValid`; fake test bundles
    /// are unsigned).
    public var checkSignature: (URL) -> Bool = { _ in true }

    public init(inputMethodsDir: URL, registry: InputSourceRegistry, apps: AppControl) {
        self.inputMethodsDir = inputMethodsDir
        self.registry = registry
        self.apps = apps
    }

    public var destination: URL { inputMethodsDir.appendingPathComponent(Self.bundleName, isDirectory: true) }

    public var installedVersion: AppVersion? { AppVersion(bundleAt: destination) }

    /// 从哪个线程调用都行；`progress` 在主线程上收到各步。 Callable from any thread; `progress` gets the steps on the main
    /// thread.
    @discardableResult
    public func install(from source: URL,
                        progress: @escaping @MainActor (InstallStep) -> Void = { _ in }) async throws -> InstallOutcome {
        let dest = destination
        await progress(.copying)
        let staging = try await Task.detached { [self] in try stage(source, dest: dest) }.value
        defer { try? FileManager.default.removeItem(at: staging) }

        await progress(.quitting)
        await apps.quitRunningCopies(bundleID: Self.bundleID)

        await progress(.replacing)
        try await Task.detached { try Self.swap(staging, into: dest) }.value
        // 替换前后系统可能又拉起了旧副本，再退出一次。 The system may have relaunched an old copy meanwhile; quit again.
        await apps.quitRunningCopies(bundleID: Self.bundleID)

        await progress(.registering)
        let listed = try await registry.registerAndEnable(bundleURL: dest)
        return InstallOutcome(installedURL: dest, listed: listed)
    }

    /// 复制到临时名下并清理属性、核对签名；返回临时副本的位置。 Copy to a temporary name, clean the attributes and check
    /// the signature; returns the staging copy.
    func stage(_ source: URL, dest: URL) throws -> URL {
        let fm = fileManager
        var isDir: ObjCBool = false
        guard fm.fileExists(atPath: source.path, isDirectory: &isDir), isDir.boolValue else { throw InstallError.sourceMissing }
        if source.standardizedFileURL.resolvingSymlinksInPath() == dest.standardizedFileURL.resolvingSymlinksInPath() {
            throw InstallError.sameLocation
        }
        let staging = inputMethodsDir.appendingPathComponent(".WeaveText-\(UUID().uuidString).app", isDirectory: true)
        do {
            try fm.createDirectory(at: inputMethodsDir, withIntermediateDirectories: true)
            try fm.copyItem(at: source, to: staging)
            try Self.stripQuarantine(staging)
        } catch {
            try? fm.removeItem(at: staging)
            throw InstallError.copyFailed(Self.reason(error))
        }
        guard checkSignature(staging) else {
            try? fm.removeItem(at: staging)
            throw InstallError.badSignature
        }
        return staging
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

    /// 递归去掉隔离属性（从网络下载的磁盘映像里复制出来的文件都带着它，输入法带着它系统就不肯拉起）。
    /// `com.apple.provenance` 留着：它由系统维护，不挡启动，也不属于签名的内容。
    /// Remove the quarantine attribute recursively (everything copied out of a downloaded disk image carries it, and
    /// the system will not start an input method that has it). `com.apple.provenance` stays: the system maintains it,
    /// it does not block launching and it is not part of the signature.
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
    /// `bundle` 默认是输入法目录里的那份（从正在运行的输入法里卸载时传它自己的位置）；`alsoRemove` 是另一处可能还有的
    /// 副本（~/Library 与 /Library 两处都清掉）。所在文件夹当前用户写不了的（安装包装进 /Library/Input Methods 的那份）
    /// 交给 `admin` 以管理员身份删除，并忘掉安装包的回执；用户取消时恢复启用，什么都不动。
    /// `bundle` defaults to the copy in the input methods directory (the running IME passes its own location);
    /// `alsoRemove` lists other places a copy may be (both ~/Library and /Library are cleaned). Copies in a folder the
    /// user cannot write (the package's /Library/Input Methods) go to `admin`, which removes them as an administrator and
    /// forgets the package receipt; if the user cancels, the sources are enabled again and nothing changes.
    @MainActor
    public func uninstall(bundle: URL? = nil, alsoRemove: [URL] = [], trash: Trash, admin: AdminRemover? = nil,
                          userData: URL?, defaults: (suite: UserDefaults, domain: String)? = nil) throws {
        let primary = bundle ?? destination
        var seen = Set<String>()
        let targets = ([primary] + alsoRemove).filter {
            fileManager.fileExists(atPath: $0.path) && seen.insert($0.standardizedFileURL.path).inserted
        }
        guard !targets.isEmpty else { throw InstallError.notInstalled }
        let needsAdmin = targets.filter { !fileManager.isWritableFile(atPath: $0.deletingLastPathComponent().path) }
        let own = targets.filter { t in !needsAdmin.contains(t) }
        if let first = needsAdmin.first, admin == nil { throw InstallError.needsAdmin(first.path) }

        registry.disableAll()
        if let admin, !needsAdmin.isEmpty {
            do {
                try admin.remove(needsAdmin)
            } catch {
                // 取消或失败：输入源恢复原样。 Cancelled or failed: put the sources back.
                _ = try? registry.registerAndEnable(bundleURL: primary)
                if let e = error as? InstallError { throw e }
                throw InstallError.removeFailed(Self.reason(error))
            }
        }
        do {
            for t in own { try trash.moveToTrash(t) }
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

extension Installer {
    /// 用系统的方式严格核对整个包的签名（含嵌套代码与资源）。 Strictly verify the whole bundle's signature the way the
    /// system does (nested code and resources included).
    public static func signatureIsValid(_ url: URL) -> Bool {
        var code: SecStaticCode?
        guard SecStaticCodeCreateWithPath(url as CFURL, [], &code) == errSecSuccess, let code else { return false }
        let flags = SecCSFlags(rawValue: kSecCSCheckAllArchitectures | kSecCSCheckNestedCode | kSecCSStrictValidate)
        return SecStaticCodeCheckValidity(code, flags, nil) == errSecSuccess
    }
}

/// 系统废纸篓。 The system Trash.
public struct SystemTrash: Trash {
    public init() {}
    public func moveToTrash(_ url: URL) throws { try FileManager.default.trashItem(at: url, resultingItemURL: nil) }
}
