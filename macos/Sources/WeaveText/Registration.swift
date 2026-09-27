import AppKit
import Carbon
import Foundation
import WeaveCore

/// 向系统登记并启用输入源（安装窗口与 `WeaveText --register` 使用）。系统的输入源接口只能在主线程调用（在别的线程上
/// 系统会直接中断程序），所以整个类型都在主线程上，每个入口再断言一次。
/// Registers and enables the input source with the system (used by the installer window and `WeaveText --register`).
/// The system input-source API may only be called on the main thread (it traps anywhere else), so the whole type is
/// main-actor isolated and every entry point asserts it again.
@MainActor
enum Registration {
    nonisolated static let bundleID = Installer.bundleID
    nonisolated static let modeID = "com.weavetext.inputmethod.WeaveText.pinyin"
    /// 已启用的键盘输入源变了时系统发的广播，输入法菜单靠它刷新。 The broadcast the system sends when the enabled
    /// keyboard input sources change; the input menu refreshes on it.
    nonisolated static let enabledSourcesChanged = kTISNotifyEnabledKeyboardInputSourcesChanged as String

    /// `--register` 的退出码。 Exit codes of `--register`.
    enum RegisterExit: Int32 {
        case ok = 0
        case failed = 1
        /// 已登记，但列表里还没有（多半要注销一次）。 Registered, but not listed yet (usually needs a log-out).
        case notListed = 2
    }

    /// `--register`：登记、启用并选中，可以反复跑；成功时不输出，经过都记在 ~/Library/Logs/WeaveText-install.log。
    /// `--register`: register, enable and select, safe to repeat; silent on success, every run is written to
    /// ~/Library/Logs/WeaveText-install.log.
    static func register(bundleURL: URL, log: InstallLog = InstallLog()) -> RegisterExit {
        log.append("--register \(bundleURL.path) (user \(NSUserName()))")
        do {
            let report = try RegisterFlow.run(bundleURL: bundleURL, modeID: modeID, backend: SystemTIS())
            log.append(report.summary)
            if report.listed { return .ok }
            fputs("\(report.summary)\n", stderr)
            return .notListed
        } catch {
            let text = (error as? LocalizedError)?.errorDescription ?? "\(error)"
            log.append("failed: \(text)")
            fputs("\(text)\n", stderr)
            return .failed
        }
    }

    /// 登记、启用并选中；返回拼音输入源是否已在列表里并已启用。
    /// Register, enable and select; returns whether the pinyin source is listed and enabled.
    static func registerAndEnable(bundleURL: URL) throws -> Bool {
        try RegisterFlow.run(bundleURL: bundleURL, modeID: modeID, backend: SystemTIS()).listed
    }

    /// 卸载前停用。 Disable before uninstalling.
    static func disable() -> Bool {
        onMain()
        let all = sources()
        for s in all { TISDisableInputSource(s) }
        return !all.isEmpty
    }

    static func tisRegister(_ bundleURL: URL) -> Int32 {
        onMain()
        return TISRegisterInputSource(bundleURL as CFURL)
    }

    static func states() -> [InputSourceState] {
        sources().compactMap { s in
            guard let id = string(s: s, kTISPropertyInputSourceID) else { return nil }
            return InputSourceState(id: id, enableCapable: bool(s, kTISPropertyInputSourceIsEnableCapable),
                                    selectCapable: bool(s, kTISPropertyInputSourceIsSelectCapable),
                                    enabled: bool(s, kTISPropertyInputSourceIsEnabled),
                                    selected: bool(s, kTISPropertyInputSourceIsSelected))
        }
    }

    static func apply(_ id: String, _ call: (TISInputSource) -> OSStatus) {
        for s in sources() where string(s: s, kTISPropertyInputSourceID) == id { _ = call(s) }
    }

    private static func sources() -> [TISInputSource] {
        onMain()
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

    /// 调试版在这里断言，发布版照样调用（系统自己会拦）。 Asserted in debug builds; release builds carry on (the system
    /// traps by itself).
    private static func onMain() {
        assert(Thread.isMainThread, "TIS must be called on the main thread")
    }
}

/// 真实的系统输入源接口。 The real system input-source API.
@MainActor
struct SystemTIS: InputSourceBackend {
    func register(_ bundleURL: URL) -> Int32 { Registration.tisRegister(bundleURL) }
    func sources() -> [InputSourceState] { Registration.states() }
    func enable(_ id: String) { Registration.apply(id, TISEnableInputSource) }
    func select(_ id: String) { Registration.apply(id, TISSelectInputSource) }
    func announce() {
        // 系统在启用时本就会广播；再发一次，让已经开着的输入法菜单也重新读列表。不去结束系统的菜单进程。
        // The system broadcasts on enable already; send it once more so an input menu that is already open re-reads
        // the list. The system's menu process is never killed.
        DistributedNotificationCenter.default().postNotificationName(
            Notification.Name(Registration.enabledSourcesChanged), object: nil, userInfo: nil, deliverImmediately: true)
    }
}

/// 真实的输入源层。 The real input-source layer.
struct SystemInputSources: InputSourceRegistry {
    @MainActor func registerAndEnable(bundleURL: URL) throws -> Bool { try Registration.registerAndEnable(bundleURL: bundleURL) }
    @MainActor func disableAll() { _ = Registration.disable() }
}

/// 真实的进程控制：请旧副本退出（不含本进程），等不及就强制结束。只在主线程上用，等待时让出主线程。
/// Real process control: ask old copies (never this process) to quit, force them after a while. Main thread only; it
/// yields the main thread while waiting.
struct SystemApps: AppControl {
    var timeout: TimeInterval = 4

    @MainActor func quitRunningCopies(bundleID: String) async {
        let me = ProcessInfo.processInfo.processIdentifier
        let others = NSRunningApplication.runningApplications(withBundleIdentifier: bundleID)
            .filter { $0.processIdentifier != me }
        guard !others.isEmpty else { return }
        others.forEach { $0.terminate() }
        if await !wait(for: others, seconds: timeout) {
            others.filter { !$0.isTerminated }.forEach { $0.forceTerminate() }
            _ = await wait(for: others, seconds: 1)
        }
    }

    @MainActor private func wait(for apps: [NSRunningApplication], seconds: TimeInterval) async -> Bool {
        let end = Date().addingTimeInterval(seconds)
        while Date() < end {
            // 进程表比 isTerminated 更及时。 The process table is more timely than isTerminated.
            if apps.allSatisfy({ $0.isTerminated || kill($0.processIdentifier, 0) != 0 }) { return true }
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        return false
    }
}

extension Installer {
    /// 装进当前用户的「输入法」文件夹；所有系统调用都经过主线程检查。 Installs into the current user's Input Methods
    /// folder; every system call goes through the main-thread check.
    static func system(inputMethodsDir: URL = LaunchMode.systemInputMethodDirs()[0]) -> Installer {
        let checked = MainThreadChecked(registry: SystemInputSources(), apps: SystemApps())
        var installer = Installer(inputMethodsDir: inputMethodsDir, registry: checked, apps: checked)
        installer.checkSignature = Installer.signatureIsValid
        return installer
    }
}
