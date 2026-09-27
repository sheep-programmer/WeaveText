import AppKit
import InputMethodKit

// 织文输入法入口。命令行模式供安装脚本与构建脚本使用。
// WeaveText entry point. Command-line modes serve the install and build scripts.
let args = CommandLine.arguments
if args.count > 1 {
    switch args[1] {
    case "--register":
        exit(Registration.register(bundleURL: Bundle.main.bundleURL) ? 0 : 1)
    case "--disable":
        exit(Registration.disable() ? 0 : 1)
    case "--selftest":
        exit(DevTools.selfTest() ? 0 : 1)
    case "--snapshot" where args.count > 2:
        do {
            try DevTools.snapshot(into: URL(fileURLWithPath: args[2]))
            exit(0)
        } catch {
            fputs("snapshot failed: \(error)\n", stderr)
            exit(1)
        }
    case "--render-icons" where args.count > 2:
        do {
            try IconRenderer.render(into: URL(fileURLWithPath: args[2]))
            exit(0)
        } catch {
            fputs("render failed: \(error)\n", stderr)
            exit(1)
        }
    default:
        break
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    private var server: IMKServer?

    func applicationDidFinishLaunching(_ notification: Notification) {
        let name = Bundle.main.infoDictionary?["InputMethodConnectionName"] as? String
            ?? "com.weavetext.inputmethod.WeaveText_Connection"
        server = IMKServer(name: name, bundleIdentifier: Bundle.main.bundleIdentifier)
        _ = EngineHost.shared
        StatusBar.shared.start()
    }

    func applicationWillTerminate(_ notification: Notification) {
        EngineHost.shared.engine?.flush()
    }
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.accessory)
app.run()
