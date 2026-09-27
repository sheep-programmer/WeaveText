import AppKit
import WeaveCore

/// 进程内唯一的内核会话与中英状态。 The process's single engine session and Chinese/English state.
final class EngineHost {
    static let shared = EngineHost()
    static let modeDidChange = Notification.Name("WeaveTextModeDidChange")

    let prefs = Preferences.shared
    let engine: WeaveSession?
    /// 当前接收按键的控制器（切换方案、中英时要先收尾它的组合）。 The controller receiving keys now.
    weak var activeController: WeaveInputController?

    /// 中文模式；false 时按键直通。 Chinese mode; keys pass through when false.
    private(set) var chinese = true

    private init() {
        let data = Bundle.main.resourceURL?.appendingPathComponent("data").path ?? ""
        let user = Self.userDirectory()
        engine = WeaveSession(dataDir: data, userDir: user.path)
        if engine == nil { NSLog("WeaveText: engine failed to load data from %@", data) }
        apply()
        NotificationCenter.default.addObserver(forName: Preferences.didChange, object: nil, queue: .main) { [weak self] _ in
            self?.apply()
        }
    }

    /// ~/Library/Application Support/WeaveText
    static func userDirectory() -> URL {
        // 测试时可用环境变量换到别处。 Tests may point it elsewhere.
        if let custom = ProcessInfo.processInfo.environment["WEAVETEXT_USER_DIR"] {
            return URL(fileURLWithPath: custom, isDirectory: true)
        }
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let dir = base.appendingPathComponent("WeaveText", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private var appliedSchema: String?

    /// 把偏好写进内核；方案变了先收尾组合。 Push preferences into the engine; finish composing on a scheme change.
    private func apply() {
        guard let engine else { return }
        if appliedSchema != prefs.schema {
            activeController?.finishComposition()
            if engine.setSchema(prefs.schema) {
                appliedSchema = prefs.schema
            } else {
                NSLog("WeaveText: scheme %@ has no dictionary, keeping %@", prefs.schema, appliedSchema ?? "-")
            }
        }
        for (key, on) in prefs.engineOptions { engine.setOption(key, on) }
    }

    var scheme: InputScheme { InputScheme.named(appliedSchema ?? prefs.schema) }

    func setChinese(_ on: Bool) {
        guard on != chinese else { return }
        activeController?.finishComposition()
        chinese = on
        NotificationCenter.default.post(name: Self.modeDidChange, object: self)
    }

    func toggleChinese() { setChinese(!chinese) }
}
