import Foundation
import Testing

/// 安装包的 preinstall / postinstall：在 bash 里载入共用函数，把与系统打交道的几个函数换成桩，只在临时目录里动文件。
/// 不以 root 运行、不碰真实的输入源与进程。
/// The package's preinstall / postinstall: source the shared functions in bash, replace the functions that touch the
/// system with stubs, and only touch files in a temp directory. Never runs as root, never touches the real input
/// sources or processes.
@Suite struct PackageScriptTests {
    static let scripts = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        .deletingLastPathComponent().appendingPathComponent("scripts/pkg/scripts")
    static var lib: String { scripts.appendingPathComponent("weavetext-lib.sh").path }

    private struct Run {
        let status: Int32
        let out: String
        /// 桩记下的调用。 Calls recorded by the stubs.
        let calls: [String]
    }

    private let dir: URL

    init() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("weave-pkgscripts-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    private var app: URL { dir.appendingPathComponent("Library/Input Methods/WeaveText.app") }
    private var home: URL { dir.appendingPathComponent("Users/alice") }
    private var userCopy: URL { home.appendingPathComponent("Library/Input Methods/WeaveText.app") }

    /// 载入函数、装上桩，再跑 `body`。 Source the functions, install the stubs, then run `body`.
    private func bash(_ body: String, owner: String = "alice") throws -> Run {
        let calls = dir.appendingPathComponent("calls.txt")
        try? FileManager.default.removeItem(at: calls)
        let script = """
        set -u
        . '\(Self.lib)'
        CALLS='\(calls.path)'
        console_owner() { printf '%s\\n' '\(owner)'; }
        user_uid() { [[ "$1" == alice ]] && echo 501; }
        user_home() { echo '\(home.path)'; }
        installed_bundle() { echo '\(app.path)'; }
        as_user() { echo "as_user $*" >> "$CALLS"; return "${AS_USER_STATUS:-0}"; }
        signal_pids() { echo "signal $*" >> "$CALLS"; }
        pause_tick() { :; }
        weave_pids() { :; }
        \(body)
        """
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/bin/bash")
        p.arguments = ["-c", script]
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = pipe
        try p.run()
        let out = String(decoding: pipe.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
        p.waitUntilExit()
        let recorded = (try? String(contentsOf: calls, encoding: .utf8)) ?? ""
        return Run(status: p.terminationStatus, out: out, calls: recorded.split(separator: "\n").map(String.init))
    }

    private func makeApp(at url: URL) throws {
        try FileManager.default.createDirectory(at: url.appendingPathComponent("Contents/MacOS"),
                                                withIntermediateDirectories: true)
    }

    @Test func consoleUserSkipsTheLoginWindowAndSystemAccounts() throws {
        defer { try? FileManager.default.removeItem(at: dir) }
        for owner in ["", "root", "loginwindow", "_mbsetupuser"] {
            let run = try bash("console_user || echo none", owner: owner)
            #expect(run.out == "none\n", "\(owner)")
        }
        #expect(try bash("console_user", owner: "alice").out == "alice\n")
    }

    @Test func postinstallRegistersAsTheConsoleUserInTheirSession() throws {
        defer { try? FileManager.default.removeItem(at: dir) }
        try makeApp(at: app)
        let value = "0081;66f00000;Safari;"
        #expect(setxattr(app.path, "com.apple.quarantine", value, value.utf8.count, 0, XATTR_NOFOLLOW) == 0)
        let run = try bash("postinstall_main pkg / /")
        #expect(run.status == 0)
        #expect(run.calls == ["as_user alice 501 \(app.path)/Contents/MacOS/WeaveText --register"])
        #expect(getxattr(app.path, "com.apple.quarantine", nil, 0, 0, XATTR_NOFOLLOW) < 0, "quarantine removed")
    }

    @Test func postinstallWithoutAConsoleUserOrOnAnotherDiskOnlyInstalls() throws {
        defer { try? FileManager.default.removeItem(at: dir) }
        try makeApp(at: app)
        for owner in ["loginwindow", "root"] {
            let run = try bash("postinstall_main pkg / /", owner: owner)
            #expect(run.status == 0 && run.calls.isEmpty, "\(owner)")
            #expect(run.out.contains("next login"))
        }
        let other = try bash("postinstall_main pkg / /Volumes/Other")
        #expect(other.status == 0 && other.calls.isEmpty)
    }

    @Test func aFailedRegistrationDoesNotFailTheInstall() throws {
        defer { try? FileManager.default.removeItem(at: dir) }
        try makeApp(at: app)
        let run = try bash("AS_USER_STATUS=2 postinstall_main pkg / /")
        #expect(run.status == 0)
        #expect(run.out.contains("status 2") && run.out.contains("WeaveText-install.log"))
    }

    @Test func postinstallFailsWhenThePayloadIsMissing() throws {
        defer { try? FileManager.default.removeItem(at: dir) }
        let run = try bash("postinstall_main pkg / /")
        #expect(run.status == 1 && run.calls.isEmpty)
    }

    @Test func preinstallQuitsRunningCopiesAndRemovesThePerUserCopy() throws {
        defer { try? FileManager.default.removeItem(at: dir) }
        try makeApp(at: userCopy)
        let data = home.appendingPathComponent("Library/Application Support/WeaveText")
        try FileManager.default.createDirectory(at: data, withIntermediateDirectories: true)
        // 第一次查到 123，收到 TERM 后就没了。 Finds 123 the first time, gone after TERM.
        let run = try bash("""
        weave_pids() { [[ -f "$CALLS" ]] && grep -q TERM "$CALLS" || echo 123; }
        preinstall_main pkg / /
        """)
        #expect(run.status == 0)
        #expect(run.calls == ["signal TERM 123"])
        #expect(!FileManager.default.fileExists(atPath: userCopy.path))
        #expect(FileManager.default.fileExists(atPath: data.path), "user words stay")
    }

    @Test func aCopyThatWontQuitIsForced() throws {
        defer { try? FileManager.default.removeItem(at: dir) }
        let run = try bash("""
        weave_pids() { printf '123\\n77\\n123\\n'; }
        WEAVE_QUIT_TICKS=3 preinstall_main pkg / /
        """)
        #expect(run.status == 0)
        #expect(run.calls == ["signal TERM 123 77", "signal KILL 123 77"] || run.calls == ["signal TERM 77 123", "signal KILL 77 123"])
    }

    @Test func preinstallLeavesSymlinksAloneAndSkipsWithoutAUser() throws {
        defer { try? FileManager.default.removeItem(at: dir) }
        let elsewhere = dir.appendingPathComponent("elsewhere/WeaveText.app")
        try makeApp(at: elsewhere)
        try FileManager.default.createDirectory(at: userCopy.deletingLastPathComponent(), withIntermediateDirectories: true)
        try FileManager.default.createSymbolicLink(at: userCopy, withDestinationURL: elsewhere)
        let run = try bash("preinstall_main pkg / /")
        #expect(run.status == 0)
        #expect(FileManager.default.fileExists(atPath: elsewhere.path))
        #expect((try? FileManager.default.destinationOfSymbolicLink(atPath: userCopy.path)) != nil)

        let nobody = try bash("""
        weave_pids() { echo 123; }
        preinstall_main pkg / /
        """, owner: "loginwindow")
        #expect(nobody.status == 0 && nobody.calls.isEmpty)
    }

    @Test func theScriptsAreExecutableAndParse() throws {
        defer { try? FileManager.default.removeItem(at: dir) }
        for name in ["preinstall", "postinstall"] {
            let url = Self.scripts.appendingPathComponent(name)
            #expect(FileManager.default.isExecutableFile(atPath: url.path), "\(name)")
            let text = try String(contentsOf: url, encoding: .utf8)
            #expect(text.hasPrefix("#!/bin/bash") && text.contains("weavetext-lib.sh") && text.contains("\(name)_main"))
        }
        let parse = try bash("bash -n '\(Self.lib)' && echo ok")
        #expect(parse.out.hasSuffix("ok\n"))
    }
}
