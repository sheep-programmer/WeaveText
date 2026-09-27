import AppKit
import SwiftUI
import WeaveCore

/// 从磁盘映像或别处双击打开时的安装窗口：安装、更新或重新安装到 ~/Library/Input Methods。
/// The installer window shown when the app is double-clicked from the disk image or anywhere else: install, update or
/// reinstall into ~/Library/Input Methods.
@MainActor
final class InstallerModel: ObservableObject {
    enum Phase: Equatable {
        case ready
        case working(InstallStep)
        case done(listed: Bool)
        case failed(String)
    }

    @Published private(set) var phase: Phase = .ready
    @Published private(set) var plan: InstallPlan
    let version: AppVersion

    private let source: URL
    private let installer: Installer

    init(source: URL = Bundle.main.bundleURL, installer: Installer = .system()) {
        self.source = source
        self.installer = installer
        let info = Bundle.main.infoDictionary
        version = AppVersion(info?["CFBundleShortVersionString"] as? String ?? "0.1.0",
                             build: info?["CFBundleVersion"] as? String)
        plan = InstallPlan.decide(this: version, installed: installer.installedVersion)
    }

    var title: String {
        switch plan {
        case .fresh: return "安装织文输入法"
        case .update: return "更新织文输入法"
        case .reinstall, .newerInstalled: return "重新安装织文输入法"
        }
    }

    /// 主按钮的字。 The primary button's label.
    var actionTitle: String {
        switch plan {
        case .fresh: return "安装"
        case .update(let from): return "更新到 \(label(version, next: from))"
        case .reinstall, .newerInstalled: return "重新安装"
        }
    }

    var planText: String {
        switch plan {
        case .fresh:
            return "织文会装进你的「输入法」文件夹，只对当前用户生效，不需要管理员密码。"
        case .update(let from):
            return "已安装 \(label(from, next: version))。更新后用户词、专业词库与设置都会保留。"
        case .reinstall:
            return "已安装同一版本。重新安装会保留用户词、专业词库与设置。"
        case .newerInstalled(let installed):
            return "已安装更新的 \(label(installed, next: version))，继续会换成这个较旧的 v\(version)。"
        }
    }

    var working: Bool { if case .working = phase { return true } else { return false } }

    /// 在主线程上发起：复制在后台，登记与退出旧副本回到主线程，主线程始终不等待。
    /// Started on the main thread: copying runs in the background, registering and quitting old copies come back to the
    /// main thread, and the main thread never waits.
    func install() {
        guard !working else { return }
        phase = .working(.copying)
        let installer = installer, source = source
        Task { @MainActor [weak self] in
            let result: Result<InstallOutcome, Error>
            do {
                result = .success(try await installer.install(from: source) { step in self?.phase = .working(step) })
            } catch {
                result = .failure(error)
            }
            guard let self else { return }
            switch result {
            case .success(let outcome): self.phase = .done(listed: outcome.listed)
            case .failure(let error):
                self.phase = .failed((error as? LocalizedError)?.errorDescription ?? error.localizedDescription)
                self.plan = InstallPlan.decide(this: self.version, installed: installer.installedVersion)
            }
        }
    }

    /// 截图用。 For snapshots.
    func preview(_ phase: Phase, plan: InstallPlan) {
        self.phase = phase
        self.plan = plan
    }

    /// 同号不同构建时带上构建号，免得「从 0.1.0 更新到 0.1.0」。 Show the build when the numbers match.
    private func label(_ v: AppVersion, next other: AppVersion) -> String {
        v.short == other.short && v.build != other.build ? "v\(v.short)（构建 \(v.build)）" : "v\(v.short)"
    }
}

struct InstallerView: View {
    static let doneText = "已安装。在菜单栏右上角的输入法菜单里选择「织文拼音」"
    static let reloginText = "如果输入法菜单里还没有「织文拼音」，请注销后重新登录一次。"
    static let width: CGFloat = 440

    @ObservedObject var model: InstallerModel
    var close: () -> Void = {}

    var body: some View {
        VStack(spacing: 0) {
            Image(nsImage: NSApp.applicationIconImage)
                .resizable()
                .frame(width: 96, height: 96)
                .padding(.bottom, 10)
            Text(model.title).font(.title2.weight(.semibold))
            Text("版本 \(model.version.short)").font(.callout).foregroundStyle(.secondary).padding(.top, 2)
            status
                .frame(maxWidth: .infinity, minHeight: 64)
                .padding(.vertical, 18)
            buttons
        }
        .padding(.horizontal, 32)
        .padding(.top, 36)
        .padding(.bottom, 24)
        .frame(width: Self.width)
        .tint(Theme.accent)
    }

    @ViewBuilder private var status: some View {
        switch model.phase {
        case .ready:
            Text(model.planText)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        case .working(let step):
            VStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text(step.title).foregroundStyle(.secondary)
            }
        case .done(let listed):
            VStack(spacing: 6) {
                Label(Self.doneText, systemImage: "checkmark.circle.fill")
                    .labelStyle(CenteredLabel(color: .green))
                if !listed {
                    Text(Self.reloginText).font(.callout).foregroundStyle(.secondary)
                }
            }
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
        case .failed(let message):
            Label(message, systemImage: "exclamationmark.triangle.fill")
                .labelStyle(CenteredLabel(color: .orange))
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    @ViewBuilder private var buttons: some View {
        HStack(spacing: 10) {
            switch model.phase {
            case .done:
                Button("打开键盘设置") { KeyboardSettings.open() }
                Spacer()
                Button("完成", action: close).keyboardShortcut(.defaultAction).buttonStyle(.borderedProminent)
            case .failed:
                Spacer()
                Button("取消", action: close).keyboardShortcut(.cancelAction)
                Button("重试") { model.install() }.keyboardShortcut(.defaultAction).buttonStyle(.borderedProminent)
            case .ready, .working:
                Spacer()
                Button("取消", action: close).keyboardShortcut(.cancelAction).disabled(model.working)
                Button(model.actionTitle) { model.install() }
                    .keyboardShortcut(.defaultAction)
                    .buttonStyle(.borderedProminent)
                    .disabled(model.working)
            }
        }
        .controlSize(.large)
    }
}

/// 图标在上、文字居中的状态行。 A status line with the icon above centred text.
private struct CenteredLabel: LabelStyle {
    let color: Color
    func makeBody(configuration: Configuration) -> some View {
        VStack(spacing: 6) {
            configuration.icon.font(.title2).foregroundStyle(color)
            configuration.title
        }
    }
}

/// 打开系统设置的键盘页（新版系统的扩展地址，不行就换旧的偏好设置面板）。
/// Opens the Keyboard page of System Settings (the new extension URL, falling back to the older pane).
enum KeyboardSettings {
    static func open() {
        let candidates = ["x-apple.systempreferences:com.apple.Keyboard-Settings.extension",
                          "x-apple.systempreferences:com.apple.preference.keyboard"]
        for s in candidates {
            if let url = URL(string: s), NSWorkspace.shared.open(url) { return }
        }
        NSWorkspace.shared.open(URL(fileURLWithPath: "/System/Library/PreferencePanes/Keyboard.prefPane"))
    }
}

/// 安装模式下的应用：一个普通窗口，关掉即退出。 The app in installer mode: one ordinary window; closing it quits.
@MainActor
final class InstallerAppDelegate: NSObject, NSApplicationDelegate {
    private var window: NSWindow?
    private let model = InstallerModel()

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.mainMenu = Self.menu()
        // Info.plist 里是 LSUIElement（输入法不占程序坞），安装窗口要当普通程序出现在前面。
        // Info.plist says LSUIElement (the IME stays out of the Dock); the installer must come up as an ordinary app.
        NSApp.setActivationPolicy(.regular)
        let w = Self.window(model: model) { NSApp.terminate(nil) }
        window = w
        w.makeKeyAndOrderFront(nil)
        w.orderFrontRegardless()
        NSApp.activate(ignoringOtherApps: true)
    }

    /// 安装窗口：内容给定宽度，高度取视图的理想高度，窗口随内容变化，不会缩成零高。
    /// The installer window: a fixed content width with the view's ideal height; the window follows the content and
    /// can never collapse to zero height.
    static func window(model: InstallerModel, close: @escaping () -> Void) -> NSWindow {
        let host = NSHostingController(rootView: InstallerView(model: model, close: close))
        host.sizingOptions = [.preferredContentSize, .minSize]
        let fitting = host.view.fittingSize
        let size = NSSize(width: max(fitting.width, InstallerView.width), height: max(fitting.height, 320))
        let w = NSWindow(contentRect: NSRect(origin: .zero, size: size),
                         styleMask: [.titled, .closable, .fullSizeContentView], backing: .buffered, defer: false)
        w.contentViewController = host
        w.setContentSize(size)
        w.titlebarAppearsTransparent = true
        w.titleVisibility = .hidden
        w.title = model.title
        w.isMovableByWindowBackground = true
        w.isReleasedWhenClosed = false
        w.center()
        return w
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        // 安装到一半不退出，免得留下半个副本。 Don't quit halfway through an install.
        model.working ? .terminateCancel : .terminateNow
    }

    private static func menu() -> NSMenu {
        let main = NSMenu()
        let appItem = NSMenuItem()
        let app = NSMenu()
        app.addItem(withTitle: "退出织文输入法安装", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        appItem.submenu = app
        main.addItem(appItem)
        let windowItem = NSMenuItem()
        let windowMenu = NSMenu(title: "窗口")
        windowMenu.addItem(withTitle: "关闭", action: #selector(NSWindow.performClose(_:)), keyEquivalent: "w")
        windowItem.submenu = windowMenu
        main.addItem(windowItem)
        return main
    }
}
