import Darwin
import Foundation
import Testing
@testable import WeaveCore

/// 此刻是否在主线程上（异步上下文里也能问）。 Whether this is the main thread (usable from async code too).
private func onMainThread() -> Bool { pthread_main_np() != 0 }

/// 记下调用的假输入源层；也记下每次调用是不是在主线程上。
/// A fake input-source layer that records calls, and whether each call was on the main thread.
private final class FakeRegistry: InputSourceRegistry, @unchecked Sendable {
    var registered: [URL] = []
    var disabled = 0
    var listed = true
    var failure: InstallError?
    /// 登记时看到的目标里的版本。 The version found at the target when registering.
    var seenVersion: String?
    var offMain = 0

    func registerAndEnable(bundleURL: URL) throws -> Bool {
        if !onMainThread() { offMain += 1 }
        if let failure { throw failure }
        registered.append(bundleURL)
        seenVersion = AppVersion(bundleAt: bundleURL)?.short
        return listed
    }

    func disableAll() {
        if !onMainThread() { offMain += 1 }
        disabled += 1
    }
}

private final class FakeApps: AppControl, @unchecked Sendable {
    var quits = 0
    var offMain = 0
    /// 退出时目标处的版本（应当还是旧的，替换在退出之后）。 The version at the target when quitting (still the old one).
    var versionsAtQuit: [String?] = []
    let dest: URL

    init(dest: URL) { self.dest = dest }

    func quitRunningCopies(bundleID: String) async {
        if !onMainThread() { offMain += 1 }
        #expect(bundleID == Installer.bundleID)
        quits += 1
        versionsAtQuit.append(AppVersion(bundleAt: dest)?.short)
    }
}

/// 收集主线程检查层报告的越界调用。 Collects the off-main calls the checking layer reports.
private final class Violations: @unchecked Sendable {
    private let lock = NSLock()
    private var calls: [String] = []
    func add(_ call: String) { lock.lock(); calls.append(call); lock.unlock() }
    var all: [String] { lock.lock(); defer { lock.unlock() }; return calls }
}

/// 和安装窗口一样，把假的系统层包进主线程检查层。 Wrap the fakes in the main-thread checking layer, as the installer
/// window does with the real ones.
private func checkedInstaller(_ ims: URL, _ registry: FakeRegistry, _ apps: FakeApps, _ violations: Violations) -> Installer {
    let checked = MainThreadChecked(registry: registry, apps: apps, violation: { violations.add($0) })
    return Installer(inputMethodsDir: ims, registry: checked, apps: checked)
}

/// 从后台线程发起安装（崩溃时就是这样），系统层的每次调用仍须在主线程上。
/// Start the install from a background thread (as in the crash); every system call must still land on the main thread.
private func installOffMain(_ installer: Installer, from source: URL,
                            progress: @escaping @MainActor (InstallStep) -> Void = { _ in }) async throws -> InstallOutcome {
    try await Task.detached {
        #expect(!onMainThread())
        return try await installer.install(from: source, progress: progress)
    }.value
}

/// 移到临时目录里的「废纸篓」。 A "Trash" that is just a temp directory.
private struct FakeTrash: Trash {
    let dir: URL
    func moveToTrash(_ url: URL) throws {
        try FileManager.default.moveItem(at: url, to: dir.appendingPathComponent(url.lastPathComponent))
    }
}

private func tempDir() throws -> URL {
    let d = FileManager.default.temporaryDirectory.appendingPathComponent("weave-install-\(UUID().uuidString)")
    try FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
    return d
}

/// 造一个最小的 .app（Info.plist、可执行文件、资源），可选带隔离属性。
/// Make a minimal .app (Info.plist, executable, a resource), optionally quarantined.
@discardableResult
private func makeBundle(at url: URL, version: String, build: String = "1", quarantined: Bool = false) throws -> URL {
    let fm = FileManager.default
    let contents = url.appendingPathComponent("Contents")
    try fm.createDirectory(at: contents.appendingPathComponent("MacOS"), withIntermediateDirectories: true)
    try fm.createDirectory(at: contents.appendingPathComponent("Resources/data"), withIntermediateDirectories: true)
    let info: [String: Any] = ["CFBundleIdentifier": Installer.bundleID, "CFBundleShortVersionString": version,
                               "CFBundleVersion": build]
    try PropertyListSerialization.data(fromPropertyList: info, format: .xml, options: 0)
        .write(to: contents.appendingPathComponent("Info.plist"))
    try Data("bin \(version)".utf8).write(to: contents.appendingPathComponent("MacOS/WeaveText"))
    try Data("dict".utf8).write(to: contents.appendingPathComponent("Resources/data/pinyin.wvz"))
    if quarantined {
        let value = "0081;66f00000;Safari;"
        for path in [url.path, contents.appendingPathComponent("MacOS/WeaveText").path,
                     contents.appendingPathComponent("Resources/data/pinyin.wvz").path] {
            #expect(setxattr(path, Installer.quarantine, value, value.utf8.count, 0, XATTR_NOFOLLOW) == 0)
        }
    }
    return url
}

private func hasQuarantine(_ path: String) -> Bool {
    getxattr(path, Installer.quarantine, nil, 0, 0, XATTR_NOFOLLOW) >= 0
}

@Suite struct LaunchModeTests {
    private let home = URL(fileURLWithPath: "/Users/someone")
    private var dirs: [URL] { LaunchMode.systemInputMethodDirs(home: home) }

    @Test func installedCopiesAreTheInputMethod() {
        let user = URL(fileURLWithPath: "/Users/someone/Library/Input Methods/WeaveText.app")
        let system = URL(fileURLWithPath: "/Library/Input Methods/WeaveText.app")
        #expect(LaunchMode.decide(bundleURL: user, arguments: ["WeaveText"], inputMethodDirs: dirs) == .inputMethod)
        #expect(LaunchMode.decide(bundleURL: system, arguments: ["WeaveText"], inputMethodDirs: dirs) == .inputMethod)
        // 结尾带斜杠的写法也一样。 A trailing slash makes no difference.
        let slash = URL(fileURLWithPath: "/Users/someone/Library/Input Methods/WeaveText.app/")
        #expect(LaunchMode.decide(bundleURL: slash, arguments: [], inputMethodDirs: dirs) == .inputMethod)
    }

    @Test func anywhereElseIsTheInstaller() {
        for path in ["/Volumes/织文输入法/织文输入法.app", "/Applications/WeaveText.app",
                     "/Users/someone/Downloads/WeaveText.app",
                     "/private/var/folders/xy/T/AppTranslocation/1234/d/织文输入法.app",
                     // 输入法目录下更深一层的不算。 One level deeper inside Input Methods does not count.
                     "/Users/someone/Library/Input Methods/old/WeaveText.app"] {
            #expect(LaunchMode.decide(bundleURL: URL(fileURLWithPath: path), arguments: ["WeaveText"],
                                      inputMethodDirs: dirs) == .installer, "\(path)")
        }
    }

    @Test func imeFlagForcesTheInputMethod() {
        let dmg = URL(fileURLWithPath: "/Volumes/织文输入法/织文输入法.app")
        #expect(LaunchMode.decide(bundleURL: dmg, arguments: ["WeaveText", "--ime"], inputMethodDirs: dirs) == .inputMethod)
        // 程序名本身不算参数。 The program name itself is not an argument.
        #expect(LaunchMode.decide(bundleURL: dmg, arguments: ["--ime"], inputMethodDirs: dirs) == .installer)
    }

    @Test func followsSymlinkedLocations() throws {
        let root = try tempDir()
        defer { try? FileManager.default.removeItem(at: root) }
        let real = root.appendingPathComponent("Input Methods")
        try FileManager.default.createDirectory(at: real, withIntermediateDirectories: true)
        let link = root.appendingPathComponent("link")
        try FileManager.default.createSymbolicLink(at: link, withDestinationURL: real)
        let app = try makeBundle(at: real.appendingPathComponent("WeaveText.app"), version: "1")
        #expect(LaunchMode.decide(bundleURL: link.appendingPathComponent("WeaveText.app"), arguments: [],
                                  inputMethodDirs: [real]) == .inputMethod)
        #expect(LaunchMode.decide(bundleURL: app, arguments: [], inputMethodDirs: [link]) == .inputMethod)
    }
}

@Suite struct AppVersionTests {
    @Test func ordersNumbersNumerically() {
        #expect(AppVersion("0.1.0") < AppVersion("0.2.0"))
        #expect(AppVersion("0.9.0") < AppVersion("0.10.0"))
        #expect(AppVersion("1.2") < AppVersion("1.2.1"))
        #expect(AppVersion("1.2") == AppVersion("1.2.0"))
        #expect(!(AppVersion("2.0.0") < AppVersion("1.9.9")))
    }

    @Test func preReleasesComeBeforeTheRelease() {
        #expect(AppVersion("0.2.0-beta.1") < AppVersion("0.2.0"))
        #expect(AppVersion("0.2.0-beta.1") < AppVersion("0.2.0-beta.2"))
        #expect(AppVersion("0.2.0-beta.2") < AppVersion("0.2.0-beta.10"))
        #expect(AppVersion("0.2.0-alpha") < AppVersion("0.2.0-beta"))
        #expect(AppVersion("0.2.0-beta") < AppVersion("0.2.0-beta.1"))
        #expect(AppVersion("0.1.0") < AppVersion("0.2.0-beta.1"))
    }

    @Test func buildNumberBreaksTies() {
        #expect(AppVersion("0.1.0", build: "120") < AppVersion("0.1.0", build: "121"))
        #expect(AppVersion("0.1.0", build: "121") == AppVersion("0.1.0", build: "121"))
        // 版本号优先于构建号。 The version wins over the build.
        #expect(AppVersion("0.1.0", build: "999") < AppVersion("0.1.1", build: "1"))
    }

    @Test func plansFromTheInstalledVersion() {
        let this = AppVersion("0.2.0", build: "200")
        #expect(InstallPlan.decide(this: this, installed: nil) == .fresh)
        #expect(InstallPlan.decide(this: this, installed: AppVersion("0.1.0", build: "150")) == .update(from: AppVersion("0.1.0", build: "150")))
        #expect(InstallPlan.decide(this: this, installed: AppVersion("0.2.0", build: "200")) == .reinstall)
        #expect(InstallPlan.decide(this: this, installed: AppVersion("0.3.0", build: "10")) == .newerInstalled(AppVersion("0.3.0", build: "10")))
    }

    @Test func readsBundles() throws {
        let root = try tempDir()
        defer { try? FileManager.default.removeItem(at: root) }
        let app = try makeBundle(at: root.appendingPathComponent("A.app"), version: "0.3.1", build: "77")
        let v = try #require(AppVersion(bundleAt: app))
        #expect(v.short == "0.3.1" && v.build == 77)
        #expect(AppVersion(bundleAt: root.appendingPathComponent("missing.app")) == nil)
    }
}

@Suite struct InstallerTests {
    @Test func freshInstallCopiesStripsQuarantineAndRegisters() async throws {
        let root = try tempDir()
        defer { try? FileManager.default.removeItem(at: root) }
        let source = try makeBundle(at: root.appendingPathComponent("dmg/织文输入法.app"), version: "0.2.0", quarantined: true)
        let ims = root.appendingPathComponent("Library/Input Methods")
        let registry = FakeRegistry()
        let apps = FakeApps(dest: ims.appendingPathComponent(Installer.bundleName))
        let violations = Violations()
        let installer = checkedInstaller(ims, registry, apps, violations)
        #expect(installer.installedVersion == nil)

        var steps: [InstallStep] = []
        let outcome = try await installOffMain(installer, from: source) { steps.append($0) }

        let dest = ims.appendingPathComponent("WeaveText.app")
        #expect(outcome == InstallOutcome(installedURL: dest, listed: true))
        #expect(steps == [.copying, .quitting, .replacing, .registering])
        #expect(installer.installedVersion?.short == "0.2.0")
        // 副本上没有隔离属性，原件不动。 The copy carries no quarantine; the original is untouched.
        for rel in ["", "Contents/MacOS/WeaveText", "Contents/Resources/data/pinyin.wvz"] {
            #expect(!hasQuarantine(dest.appendingPathComponent(rel).path), "\(rel)")
        }
        #expect(hasQuarantine(source.appendingPathComponent("Contents/MacOS/WeaveText").path))
        #expect(registry.registered == [dest])
        // 系统层的调用全在主线程上。 Every system call happened on the main thread.
        #expect(violations.all.isEmpty)
        #expect(registry.offMain == 0 && apps.offMain == 0)
        #expect(apps.quits == 2)
        // 没有留下临时副本。 No staging copy is left behind.
        #expect(try FileManager.default.contentsOfDirectory(atPath: ims.path) == ["WeaveText.app"])
    }

    @Test func theCheckingLayerReportsOffMainCalls() async {
        let violations = Violations()
        let checked = MainThreadChecked(registry: FakeRegistry(), apps: FakeApps(dest: URL(fileURLWithPath: "/")),
                                        violation: { violations.add($0) })
        await Task.detached { checked.check("registerAndEnable") }.value
        await MainActor.run { checked.check("disableAll") }
        #expect(violations.all == ["registerAndEnable"])
    }

    @Test func replacesAnOlderCopyAfterQuittingIt() async throws {
        let root = try tempDir()
        defer { try? FileManager.default.removeItem(at: root) }
        let ims = root.appendingPathComponent("Input Methods")
        let dest = ims.appendingPathComponent("WeaveText.app")
        try makeBundle(at: dest, version: "0.1.0")
        // 旧版本里多出的文件替换后不应残留。 A file only the old version had must not survive.
        try Data("old".utf8).write(to: dest.appendingPathComponent("Contents/Resources/data/stale.wvz"))
        let source = try makeBundle(at: root.appendingPathComponent("new/WeaveText.app"), version: "0.2.0")
        let registry = FakeRegistry()
        let apps = FakeApps(dest: dest)
        let violations = Violations()
        let installer = checkedInstaller(ims, registry, apps, violations)
        #expect(InstallPlan.decide(this: AppVersion("0.2.0"), installed: installer.installedVersion)
                == .update(from: AppVersion("0.1.0", build: "1")))

        _ = try await installOffMain(installer, from: source)

        // 第一次退出时还是旧版本（先退出再替换），替换后再查一次。 Old copy quit before the swap, checked again after.
        #expect(apps.versionsAtQuit == ["0.1.0", "0.2.0"])
        #expect(registry.seenVersion == "0.2.0")
        #expect(try String(contentsOf: dest.appendingPathComponent("Contents/MacOS/WeaveText"), encoding: .utf8) == "bin 0.2.0")
        #expect(!FileManager.default.fileExists(atPath: dest.appendingPathComponent("Contents/Resources/data/stale.wvz").path))
        #expect(try FileManager.default.contentsOfDirectory(atPath: ims.path) == ["WeaveText.app"])
        #expect(violations.all.isEmpty)
    }

    @Test func refusesMissingSourceOrItself() async throws {
        let root = try tempDir()
        defer { try? FileManager.default.removeItem(at: root) }
        let ims = root.appendingPathComponent("Input Methods")
        let dest = try makeBundle(at: ims.appendingPathComponent("WeaveText.app"), version: "0.1.0")
        let apps = FakeApps(dest: dest)
        let installer = Installer(inputMethodsDir: ims, registry: FakeRegistry(), apps: apps)
        await #expect(throws: InstallError.sourceMissing) {
            try await installer.install(from: root.appendingPathComponent("none.app"))
        }
        await #expect(throws: InstallError.sameLocation) { try await installer.install(from: dest) }
        #expect(apps.quits == 0)
        #expect(installer.installedVersion?.short == "0.1.0")
    }

    @Test func aBrokenSignatureInstallsNothing() async throws {
        let root = try tempDir()
        defer { try? FileManager.default.removeItem(at: root) }
        let ims = root.appendingPathComponent("Input Methods")
        let source = try makeBundle(at: root.appendingPathComponent("a/WeaveText.app"), version: "0.2.0")
        let registry = FakeRegistry()
        let apps = FakeApps(dest: ims.appendingPathComponent("WeaveText.app"))
        var installer = Installer(inputMethodsDir: ims, registry: registry, apps: apps)
        var checked: [URL] = []
        installer.checkSignature = { checked.append($0); return false }
        await #expect(throws: InstallError.badSignature) { try await installer.install(from: source) }
        // 核对的是临时副本，而不是原件。 The staging copy is checked, not the original.
        #expect(checked.count == 1 && checked.first != source)
        #expect(installer.installedVersion == nil)
        #expect(apps.quits == 0 && registry.registered.isEmpty)
        #expect(try FileManager.default.contentsOfDirectory(atPath: ims.path).isEmpty)
        // 假包没有签名，系统的核对也不会放行。 A fake bundle is unsigned, so the real check refuses it too.
        #expect(!Installer.signatureIsValid(source))
    }

    @Test func aRegistrationFailureKeepsTheCopyAndSaysSo() async throws {
        let root = try tempDir()
        defer { try? FileManager.default.removeItem(at: root) }
        let ims = root.appendingPathComponent("Input Methods")
        let source = try makeBundle(at: root.appendingPathComponent("a/WeaveText.app"), version: "0.2.0")
        let registry = FakeRegistry()
        registry.failure = .registerFailed(-50)
        let apps = FakeApps(dest: ims.appendingPathComponent("WeaveText.app"))
        let installer = Installer(inputMethodsDir: ims, registry: registry, apps: apps)
        await #expect(throws: InstallError.registerFailed(-50)) { try await installOffMain(installer, from: source) }
        #expect(installer.installedVersion?.short == "0.2.0")
        #expect(InstallError.registerFailed(-50).errorDescription?.contains("注销") == true)
    }

    @Test func notListedYetIsReported() async throws {
        let root = try tempDir()
        defer { try? FileManager.default.removeItem(at: root) }
        let ims = root.appendingPathComponent("Input Methods")
        let source = try makeBundle(at: root.appendingPathComponent("a/WeaveText.app"), version: "0.2.0")
        let registry = FakeRegistry()
        registry.listed = false
        let installer = Installer(inputMethodsDir: ims, registry: registry, apps: FakeApps(dest: ims))
        #expect(try await installOffMain(installer, from: source).listed == false)
    }

    @MainActor @Test func uninstallMovesToTrashAndKeepsDataByDefault() throws {
        let root = try tempDir()
        defer { try? FileManager.default.removeItem(at: root) }
        let ims = root.appendingPathComponent("Input Methods")
        try makeBundle(at: ims.appendingPathComponent("WeaveText.app"), version: "0.2.0")
        let data = root.appendingPathComponent("Application Support/WeaveText")
        try FileManager.default.createDirectory(at: data, withIntermediateDirectories: true)
        let trash = root.appendingPathComponent("Trash")
        try FileManager.default.createDirectory(at: trash, withIntermediateDirectories: true)
        let registry = FakeRegistry()
        let violations = Violations()
        let installer = checkedInstaller(ims, registry, FakeApps(dest: ims), violations)

        try installer.uninstall(trash: FakeTrash(dir: trash), userData: nil)

        #expect(registry.disabled == 1)
        #expect(violations.all.isEmpty && registry.offMain == 0)
        #expect(installer.installedVersion == nil)
        #expect(FileManager.default.fileExists(atPath: trash.appendingPathComponent("WeaveText.app").path))
        #expect(FileManager.default.fileExists(atPath: data.path))
        #expect(throws: InstallError.notInstalled) { try installer.uninstall(trash: FakeTrash(dir: trash), userData: nil) }
    }

    @MainActor @Test func uninstallCanTakeUserDataAndPreferences() throws {
        let root = try tempDir()
        defer { try? FileManager.default.removeItem(at: root) }
        let ims = root.appendingPathComponent("Input Methods")
        try makeBundle(at: ims.appendingPathComponent("WeaveText.app"), version: "0.2.0")
        let data = root.appendingPathComponent("WeaveText")
        try FileManager.default.createDirectory(at: data, withIntermediateDirectories: true)
        try Data("w".utf8).write(to: data.appendingPathComponent("user.tsv"))
        let trash = root.appendingPathComponent("Trash")
        try FileManager.default.createDirectory(at: trash, withIntermediateDirectories: true)
        let domain = "com.weavetext.inputmethod.WeaveText.uninstall-test"
        let defaults = try #require(UserDefaults(suiteName: domain))
        defaults.set(true, forKey: "linkEnabled")
        defer { defaults.removePersistentDomain(forName: domain) }
        let installer = Installer(inputMethodsDir: ims, registry: FakeRegistry(), apps: FakeApps(dest: ims))

        try installer.uninstall(trash: FakeTrash(dir: trash), userData: data, defaults: (defaults, domain))

        #expect(!FileManager.default.fileExists(atPath: data.path))
        #expect(FileManager.default.fileExists(atPath: trash.appendingPathComponent("WeaveText/user.tsv").path))
        #expect(defaults.persistentDomain(forName: domain)?["linkEnabled"] == nil)
    }
}

/// 拿真正构建出的包走一遍复制与清理（只在临时目录里，不登记），签名必须仍然完好。
/// Run the real built bundle through copying and cleaning (in a temp directory only, never registered); the signature
/// must stay intact.
@Suite struct BuiltBundleTests {
    static let built = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().appendingPathComponent("build/WeaveText.app")

    @Test(.enabled(if: FileManager.default.fileExists(atPath: built.path)))
    func stagingKeepsTheSignature() throws {
        let root = try tempDir()
        defer { try? FileManager.default.removeItem(at: root) }
        let source = root.appendingPathComponent("dmg/织文输入法.app")
        try FileManager.default.createDirectory(at: source.deletingLastPathComponent(), withIntermediateDirectories: true)
        try FileManager.default.copyItem(at: Self.built, to: source)
        let value = "0081;66f00000;Safari;"
        #expect(setxattr(source.path, Installer.quarantine, value, value.utf8.count, 0, XATTR_NOFOLLOW) == 0)
        let ims = root.appendingPathComponent("Input Methods")
        var installer = Installer(inputMethodsDir: ims, registry: FakeRegistry(), apps: FakeApps(dest: ims))
        installer.checkSignature = Installer.signatureIsValid
        let staging = try installer.stage(source, dest: installer.destination)
        #expect(!hasQuarantine(staging.path))
        #expect(Installer.signatureIsValid(staging))
        // 改动包里的文件，核对就不放行。 Tampering with a file in the bundle fails the check.
        try Data("x".utf8).write(to: staging.appendingPathComponent("Contents/Resources/mirrors.json"))
        #expect(!Installer.signatureIsValid(staging))
    }
}
