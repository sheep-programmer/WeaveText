import AppKit
import Carbon
import Foundation
import WeaveCore

/// 向系统登记并启用输入源（安装窗口与 `WeaveText --register` 使用）。
/// Registers and enables the input source with the system (used by the installer window and `WeaveText --register`).
enum Registration {
    static let bundleID = Installer.bundleID
    static let modeID = "com.weavetext.inputmethod.WeaveText.pinyin"

    static func register(bundleURL: URL) -> Bool {
        do {
            guard try registerAndEnable(bundleURL: bundleURL) else {
                fputs("registered, but no input source with bundle id \(bundleID) was found\n", stderr)
                return false
            }
            return true
        } catch {
            fputs("TISRegisterInputSource failed: \(error)\n", stderr)
            return false
        }
    }

    /// 登记、启用并选中；返回输入源是否已出现在列表里。 Register, enable and select; returns whether it is listed.
    static func registerAndEnable(bundleURL: URL) throws -> Bool {
        let status = TISRegisterInputSource(bundleURL as CFURL)
        guard status == noErr else { throw InstallError.registerFailed(status) }
        let all = sources()
        guard !all.isEmpty else { return false }
        for s in all where bool(s, kTISPropertyInputSourceIsEnableCapable) { TISEnableInputSource(s) }
        if let mode = all.first(where: { string(s: $0, kTISPropertyInputSourceID) == modeID }),
           bool(mode, kTISPropertyInputSourceIsSelectCapable) {
            TISSelectInputSource(mode)
        }
        return true
    }

    /// 卸载前停用。 Disable before uninstalling.
    static func disable() -> Bool {
        let all = sources()
        for s in all { TISDisableInputSource(s) }
        return !all.isEmpty
    }

    private static func sources() -> [TISInputSource] {
        let filter = [kTISPropertyBundleID as String: bundleID] as CFDictionary
        return (TISCreateInputSourceList(filter, true)?.takeRetainedValue() as? [TISInputSource]) ?? []
    }

    private static func string(s: TISInputSource, _ key: CFString) -> String? {
        guard let p = TISGetInputSourceProperty(s, key) else { return nil }
        return Unmanaged<CFString>.fromOpaque(p).takeUnretainedValue() as String
    }

    private static func bool(_ s: TISInputSource, _ key: CFString) -> Bool {
        guard let p = TISGetInputSourceProperty(s, key) else { return false }
        return CFBooleanGetValue(Unmanaged<CFBoolean>.fromOpaque(p).takeUnretainedValue())
    }
}

/// 真实的输入源层。 The real input-source layer.
struct SystemInputSources: InputSourceRegistry {
    func registerAndEnable(bundleURL: URL) throws -> Bool { try Registration.registerAndEnable(bundleURL: bundleURL) }
    func disableAll() { _ = Registration.disable() }
}

/// 真实的进程控制：请旧副本退出（不含本进程），等不及就强制结束；以新实例启动装好的那份。
/// Real process control: ask old copies (never this process) to quit, force them after a while; launch the installed
/// copy as a new instance.
struct SystemApps: AppControl {
    var timeout: TimeInterval = 4

    func quitRunningCopies(bundleID: String) {
        let me = ProcessInfo.processInfo.processIdentifier
        let others = NSRunningApplication.runningApplications(withBundleIdentifier: bundleID)
            .filter { $0.processIdentifier != me }
        guard !others.isEmpty else { return }
        others.forEach { $0.terminate() }
        if !wait(for: others, seconds: timeout) {
            others.filter { !$0.isTerminated }.forEach { $0.forceTerminate() }
            _ = wait(for: others, seconds: 1)
        }
    }

    func launch(bundleURL: URL) throws {
        let config = NSWorkspace.OpenConfiguration()
        config.activates = false
        config.addsToRecentItems = false
        // 安装窗口本身与它同一个包标识，要明确起一个新实例。 The installer shares the bundle id; ask for a new instance.
        config.createsNewApplicationInstance = true
        let done = DispatchSemaphore(value: 0)
        var failure: Error?
        NSWorkspace.shared.openApplication(at: bundleURL, configuration: config) { _, error in
            failure = error
            done.signal()
        }
        if done.wait(timeout: .now() + 15) == .timedOut { throw InstallError.launchFailed("启动超时") }
        if let failure { throw InstallError.launchFailed(failure.localizedDescription) }
    }

    private func wait(for apps: [NSRunningApplication], seconds: TimeInterval) -> Bool {
        let end = Date().addingTimeInterval(seconds)
        while Date() < end {
            // 进程表比 isTerminated 更及时。 The process table is more timely than isTerminated.
            if apps.allSatisfy({ $0.isTerminated || kill($0.processIdentifier, 0) != 0 }) { return true }
            Thread.sleep(forTimeInterval: 0.1)
        }
        return false
    }
}
