import Foundation
import Testing
@testable import WeaveCore

/// 假的系统输入源：登记后出现拼音与英文两个输入源，记下每个调用。
/// A fake system input-source API: after registering, a pinyin and an English source appear; every call is recorded.
@MainActor
private final class FakeTIS: InputSourceBackend {
    static let pinyin = "com.weavetext.inputmethod.WeaveText.pinyin"
    static let english = "com.weavetext.inputmethod.WeaveText.english"

    var status: Int32 = 0
    /// 登记后是否出现在列表里（刚装好、系统还没刷新时是 false）。 Whether sources show up after registering.
    var appears = true
    var registered = false
    var states: [InputSourceState] = [
        InputSourceState(id: FakeTIS.pinyin),
        InputSourceState(id: FakeTIS.english, selectCapable: false),
    ]
    var calls: [String] = []

    func register(_ bundleURL: URL) -> Int32 {
        calls.append("register \(bundleURL.lastPathComponent)")
        if status == 0 { registered = true }
        return status
    }

    func sources() -> [InputSourceState] { registered && appears ? states : [] }

    func enable(_ id: String) {
        calls.append("enable \(id)")
        if let i = states.firstIndex(where: { $0.id == id }) { states[i].enabled = true }
    }

    func select(_ id: String) {
        calls.append("select \(id)")
        for i in states.indices { states[i].selected = states[i].id == id && states[i].enabled }
    }

    func announce() { calls.append("announce") }
}

@MainActor @Suite struct RegisterFlowTests {
    private let bundle = URL(fileURLWithPath: "/Library/Input Methods/WeaveText.app")

    @Test func firstRunEnablesSelectsAndAnnounces() throws {
        let tis = FakeTIS()
        let report = try RegisterFlow.run(bundleURL: bundle, modeID: FakeTIS.pinyin, backend: tis)
        #expect(report.listed && report.selected)
        #expect(report.enabled == [FakeTIS.pinyin, FakeTIS.english])
        #expect(tis.calls == ["register WeaveText.app", "enable \(FakeTIS.pinyin)", "enable \(FakeTIS.english)",
                              "select \(FakeTIS.pinyin)", "announce"])
        #expect(tis.states.first { $0.id == FakeTIS.pinyin }?.selected == true)
    }

    @Test func secondRunChangesNothing() throws {
        let tis = FakeTIS()
        _ = try RegisterFlow.run(bundleURL: bundle, modeID: FakeTIS.pinyin, backend: tis)
        tis.calls = []
        let again = try RegisterFlow.run(bundleURL: bundle, modeID: FakeTIS.pinyin, backend: tis)
        #expect(again.listed && again.unchanged)
        #expect(tis.calls == ["register WeaveText.app"], "no enable, select or broadcast the second time")
        #expect(again.summary.contains("already"))
    }

    @Test func aDisabledNonSelectableSourceIsOnlyEnabled() throws {
        let tis = FakeTIS()
        tis.states[0].enabled = true
        tis.states[0].selected = true
        let report = try RegisterFlow.run(bundleURL: bundle, modeID: FakeTIS.pinyin, backend: tis)
        #expect(report.enabled == [FakeTIS.english] && !report.selected)
        #expect(!tis.calls.contains { $0.hasPrefix("select") })
    }

    @Test func aSystemErrorIsThrown() {
        let tis = FakeTIS()
        tis.status = -50
        #expect(throws: InstallError.registerFailed(-50)) {
            try RegisterFlow.run(bundleURL: bundle, modeID: FakeTIS.pinyin, backend: tis)
        }
        #expect(tis.calls == ["register WeaveText.app"])
    }

    @Test func notListedYetSaysLogOut() throws {
        let tis = FakeTIS()
        tis.appears = false
        let report = try RegisterFlow.run(bundleURL: bundle, modeID: FakeTIS.pinyin, backend: tis)
        #expect(!report.listed && report.unchanged)
        #expect(report.summary.contains("log out"))
    }
}

@Suite struct InstallLogTests {
    @Test func appendsTimestampedLines() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("weave-log-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let log = InstallLog(url: dir.appendingPathComponent("Logs/WeaveText-install.log"))
        log.append("one")
        log.append("two")
        let lines = try String(contentsOf: log.url, encoding: .utf8).split(separator: "\n")
        #expect(lines.count == 2)
        #expect(lines[0].hasSuffix(" one") && lines[1].hasSuffix(" two"))
        #expect(lines[0].first?.isNumber == true)
    }

    @Test func theDefaultIsInTheUsersLogs() {
        #expect(InstallLog.defaultURL.path.hasSuffix("/Library/Logs/WeaveText-install.log"))
    }
}

@Suite struct AdminScriptTests {
    @Test func quotesEveryPathAndForgetsTheReceipt() throws {
        let script = try #require(AdminScript.remove([URL(fileURLWithPath: "/Library/Input Methods/WeaveText.app")]))
        #expect(script.contains("quoted form of \"/Library/Input Methods/WeaveText.app\""))
        #expect(script.contains("pkgutil --forget \(AdminScript.packageID)"))
        #expect(script.hasSuffix("with administrator privileges"))
    }

    @Test func refusesAnythingElse() {
        #expect(AdminScript.remove([]) == nil)
        #expect(AdminScript.remove([URL(fileURLWithPath: "/Library")]) == nil)
        #expect(AdminScript.remove([URL(fileURLWithPath: "/Applications/WeaveText.app")]) == nil)
        #expect(AdminScript.remove([URL(fileURLWithPath: "/Library/Input Methods/../WeaveText.app")]) == nil)
        #expect(AdminScript.remove([URL(fileURLWithPath: "/tmp/a\"b/Input Methods/WeaveText.app")]) == nil)
    }
}

@Suite struct PackageLocatorTests {
    @Test func findsOurPackageNextToTheApp() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("weave-pkg-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let app = dir.appendingPathComponent("织文输入法.app")
        #expect(PackageLocator.package(nextTo: app) == nil)
        for name in ["other.pkg", ".hidden-WeaveText.pkg", "WeaveText-0.1.0.pkg"] {
            try Data().write(to: dir.appendingPathComponent(name))
        }
        #expect(PackageLocator.package(nextTo: app)?.lastPathComponent == "WeaveText-0.1.0.pkg")
        try Data().write(to: dir.appendingPathComponent("双击安装织文输入法.pkg"))
        #expect(PackageLocator.package(nextTo: app)?.lastPathComponent != "other.pkg")
    }
}
