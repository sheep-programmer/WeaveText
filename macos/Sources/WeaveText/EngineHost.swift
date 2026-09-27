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

    /// 专业词库（~/Library/Application Support/WeaveText/packs，内核启动时自动载入）。
    /// Domain dictionaries in the user dir's packs/, which the engine loads by itself at startup.
    let packs: DictPackStore
    /// 云端热词（默认关闭），文件在用户目录的 cloud/。 Cloud hot words (off by default), files in the user dir's cloud/.
    let cloud: CloudWords
    private var cloudTimer: Timer?

    /// 中文模式；false 时按键直通。 Chinese mode; keys pass through when false.
    private(set) var chinese = true

    private init() {
        let data = Bundle.main.resourceURL?.appendingPathComponent("data").path ?? ""
        let user = Self.userDirectory()
        let engine = WeaveSession(dataDir: data, userDir: user.path)
        self.engine = engine
        if engine == nil { NSLog("WeaveText: engine failed to load data from %@", data) }
        let fetcher = URLSessionFetcher()
        let catalog = Bundle.main.url(forResource: "dictpacks", withExtension: "json")
            .flatMap { try? Data(contentsOf: $0) }.flatMap(DictPackCatalog.parse) ?? DictPackCatalog(base: "", packs: [])
        packs = DictPackStore(catalog: catalog, dir: user.appendingPathComponent("packs", isDirectory: true),
                              fetcher: fetcher, attach: { id, path in engine?.loadPack(id: id, path: path) },
                              detach: { id in engine?.unloadPack(id: id) })
        cloud = CloudWords(defaults: .standard, dir: user.appendingPathComponent("cloud", isDirectory: true),
                           fetcher: fetcher, load: { engine?.loadHotwords(tsv: $0, sig: $1) ?? -1 },
                           unload: { engine?.unloadPack(id: CloudWords.packID) })
        apply()
        syncClock()
        cloud.attach()
        NotificationCenter.default.addObserver(forName: Preferences.didChange, object: nil, queue: .main) { [weak self] _ in
            self?.apply()
        }
        NotificationCenter.default.addObserver(forName: .NSSystemTimeZoneDidChange, object: nil, queue: .main) { [weak self] _ in
            self?.syncClock()
        }
    }

    /// 开始后台工作：云端热词开启时按天检查（输入法进程常驻，所以每小时看一次是否过期）。
    /// Start background work: daily hot-word checks when on (the IME stays running, so staleness is looked at hourly).
    func startBackground() {
        cloud.refreshIfStale()
        guard cloudTimer == nil else { return }
        cloudTimer = Timer.scheduledTimer(withTimeInterval: 3600, repeats: true) { [weak self] _ in
            self?.cloud.refreshIfStale()
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
            if engine.hasSchema(prefs.schema) {
                activeController?.finishComposition()
                engine.setSchema(prefs.schema)
                appliedSchema = prefs.schema
            } else {
                NSLog("WeaveText: scheme %@ has no dictionary, keeping %@", prefs.schema, appliedSchema ?? "-")
            }
        }
        for (key, on) in prefs.engineOptions { engine.setOption(key, on) }
    }

    /// 本地时区给内核（rq / sj / xq）；夏令时切换也会变，所以每次激活时也同步。
    /// Hand the local UTC offset to the engine (rq / sj / xq); DST changes it too, so it is also synced on activation.
    func syncClock() {
        NSTimeZone.resetSystemTimeZone()
        engine?.setUTCOffset(minutes: TimeZone.current.secondsFromGMT() / 60)
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
