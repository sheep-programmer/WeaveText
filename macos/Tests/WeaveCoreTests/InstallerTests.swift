import Darwin
import Foundation
import Testing
@testable import WeaveCore

/// 记下调用的假输入源层。 A fake input-source layer that records calls.
private final class FakeRegistry: InputSourceRegistry {
    var registered: [URL] = []
    var disabled = 0
    var listed = true
    var failure: InstallError?
    /// 登记时看到的目标里的版本。 The version found at the target when registering.
    var seenVersion: String?

    func registerAndEnable(bundleURL: URL) throws -> Bool {
        if let failure { throw failure }
        registered.append(bundleURL)
        seenVersion = AppVersion(bundleAt: bundleURL)?.short
        return listed
    }

    func disableAll() { disabled += 1 }
}

private final class FakeApps: AppControl {
    var quits = 0
    var launched: [URL] = []
    /// 退出时目标处的版本（应当还是旧的，替换在退出之后）。 The version at the target when quitting (still the old one).
    var versionsAtQuit: [String?] = []
    let dest: URL

    init(dest: URL) { self.dest = dest }

    func quitRunningCopies(bundleID: String) {
        #expect(bundleID == Installer.bundleID)
        quits += 1
        versionsAtQuit.append(AppVersion(bundleAt: dest)?.short)
    }

    func launch(bundleURL: URL) throws { launched.append(bundleURL) }
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
    @Test func freshInstallCopiesStripsQuarantineRegistersAndLaunches() throws {
        let root = try tempDir()
        defer { try? FileManager.default.removeItem(at: root) }
        let source = try makeBundle(at: root.appendingPathComponent("dmg/织文输入法.app"), version: "0.2.0", quarantined: true)
        let ims = root.appendingPathComponent("Library/Input Methods")
        let registry = FakeRegistry()
        let apps = FakeApps(dest: ims.appendingPathComponent(Installer.bundleName))
        let installer = Installer(inputMethodsDir: ims, registry: registry, apps: apps)
        #expect(installer.installedVersion == nil)

        var steps: [InstallStep] = []
        let outcome = try installer.install(from: source) { steps.append($0) }

        let dest = ims.appendingPathComponent("WeaveText.app")
        #expect(outcome == InstallOutcome(installedURL: dest, listed: true))
        #expect(steps == [.copying, .quitting, .replacing, .registering, .launching])
        #expect(installer.installedVersion?.short == "0.2.0")
        // 副本上没有隔离属性，原件不动。 The copy carries no quarantine; the original is untouched.
        for rel in ["", "Contents/MacOS/WeaveText", "Contents/Resources/data/pinyin.wvz"] {
            #expect(!hasQuarantine(dest.appendingPathComponent(rel).path), "\(rel)")
        }
        #expect(hasQuarantine(source.appendingPathComponent("Contents/MacOS/WeaveText").path))
        #expect(registry.registered == [dest])
        #expect(apps.launched == [dest])
        // 没有留下临时副本。 No staging copy is left behind.
        #expect(try FileManager.default.contentsOfDirectory(atPath: ims.path) == ["WeaveText.app"])
    }

    @Test func replacesAnOlderCopyAfterQuittingIt() throws {
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
        let installer = Installer(inputMethodsDir: ims, registry: registry, apps: apps)
        #expect(InstallPlan.decide(this: AppVersion("0.2.0"), installed: installer.installedVersion)
                == .update(from: AppVersion("0.1.0", build: "1")))

        try installer.install(from: source)

        // 第一次退出时还是旧版本（先退出再替换），替换后再查一次。 Old copy quit before the swap, checked again after.
        #expect(apps.versionsAtQuit == ["0.1.0", "0.2.0"])
        #expect(registry.seenVersion == "0.2.0")
        #expect(try String(contentsOf: dest.appendingPathComponent("Contents/MacOS/WeaveText"), encoding: .utf8) == "bin 0.2.0")
        #expect(!FileManager.default.fileExists(atPath: dest.appendingPathComponent("Contents/Resources/data/stale.wvz").path))
        #expect(try FileManager.default.contentsOfDirectory(atPath: ims.path) == ["WeaveText.app"])
    }

    @Test func refusesMissingSourceOrItself() throws {
        let root = try tempDir()
        defer { try? FileManager.default.removeItem(at: root) }
        let ims = root.appendingPathComponent("Input Methods")
        let dest = try makeBundle(at: ims.appendingPathComponent("WeaveText.app"), version: "0.1.0")
        let apps = FakeApps(dest: dest)
        let installer = Installer(inputMethodsDir: ims, registry: FakeRegistry(), apps: apps)
        #expect(throws: InstallError.sourceMissing) { try installer.install(from: root.appendingPathComponent("none.app")) }
        #expect(throws: InstallError.sameLocation) { try installer.install(from: dest) }
        #expect(apps.quits == 0)
        #expect(installer.installedVersion?.short == "0.1.0")
    }

    @Test func aRegistrationFailureKeepsTheCopyAndSaysSo() throws {
        let root = try tempDir()
        defer { try? FileManager.default.removeItem(at: root) }
        let ims = root.appendingPathComponent("Input Methods")
        let source = try makeBundle(at: root.appendingPathComponent("a/WeaveText.app"), version: "0.2.0")
        let registry = FakeRegistry()
        registry.failure = .registerFailed(-50)
        let apps = FakeApps(dest: ims.appendingPathComponent("WeaveText.app"))
        let installer = Installer(inputMethodsDir: ims, registry: registry, apps: apps)
        #expect(throws: InstallError.registerFailed(-50)) { try installer.install(from: source) }
        #expect(installer.installedVersion?.short == "0.2.0")
        #expect(apps.launched.isEmpty)
        #expect(InstallError.registerFailed(-50).errorDescription?.contains("注销") == true)
    }

    @Test func notListedYetIsReported() throws {
        let root = try tempDir()
        defer { try? FileManager.default.removeItem(at: root) }
        let ims = root.appendingPathComponent("Input Methods")
        let source = try makeBundle(at: root.appendingPathComponent("a/WeaveText.app"), version: "0.2.0")
        let registry = FakeRegistry()
        registry.listed = false
        let installer = Installer(inputMethodsDir: ims, registry: registry, apps: FakeApps(dest: ims))
        #expect(try installer.install(from: source).listed == false)
    }

    @Test func uninstallMovesToTrashAndKeepsDataByDefault() throws {
        let root = try tempDir()
        defer { try? FileManager.default.removeItem(at: root) }
        let ims = root.appendingPathComponent("Input Methods")
        try makeBundle(at: ims.appendingPathComponent("WeaveText.app"), version: "0.2.0")
        let data = root.appendingPathComponent("Application Support/WeaveText")
        try FileManager.default.createDirectory(at: data, withIntermediateDirectories: true)
        let trash = root.appendingPathComponent("Trash")
        try FileManager.default.createDirectory(at: trash, withIntermediateDirectories: true)
        let registry = FakeRegistry()
        let installer = Installer(inputMethodsDir: ims, registry: registry, apps: FakeApps(dest: ims))

        try installer.uninstall(trash: FakeTrash(dir: trash), userData: nil)

        #expect(registry.disabled == 1)
        #expect(installer.installedVersion == nil)
        #expect(FileManager.default.fileExists(atPath: trash.appendingPathComponent("WeaveText.app").path))
        #expect(FileManager.default.fileExists(atPath: data.path))
        #expect(throws: InstallError.notInstalled) { try installer.uninstall(trash: FakeTrash(dir: trash), userData: nil) }
    }

    @Test func uninstallCanTakeUserDataAndPreferences() throws {
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
