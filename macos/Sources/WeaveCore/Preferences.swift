import Combine
import Foundation

/// 中英切换键。 The Chinese/English toggle key.
public enum ToggleKey: String, CaseIterable, Sendable {
    case shift, none
}

/// 翻页键。 Which keys page the candidates.
public enum PageKeys: String, CaseIterable, Sendable {
    /// `-` `=` 与 `,` `.` 都可以。 Both `-`/`=` and `,`/`.`.
    case both
    case minusEqual
    case commaPeriod

    public func pagesBack(_ c: Character) -> Bool {
        switch self {
        case .both: return c == "-" || c == ","
        case .minusEqual: return c == "-"
        case .commaPeriod: return c == ","
        }
    }

    public func pagesForward(_ c: Character) -> Bool {
        switch self {
        case .both: return c == "=" || c == "."
        case .minusEqual: return c == "="
        case .commaPeriod: return c == "."
        }
    }
}

public enum CandidateOrientation: String, CaseIterable, Sendable {
    case horizontal, vertical
}

public enum AppearanceMode: String, CaseIterable, Sendable {
    case system, light, dark
}

/// 偏好设置（输入法与设置窗口在同一进程，改动即时生效）。
/// Preferences; the IME and the settings window share one process, so changes apply live.
public final class Preferences: ObservableObject {
    public static let didChange = Notification.Name("WeaveTextPreferencesDidChange")
    public static let shared = Preferences(defaults: .standard)

    public static let pageSizes = 5...9
    public static let fontSizes = 12...28

    private let d: UserDefaults

    @Published public var schema: String { didSet { save(schema, "schema") } }
    @Published public var toggleKey: ToggleKey { didSet { save(toggleKey.rawValue, "toggleKey") } }
    @Published public var pageSize: Int { didSet { save(pageSize, "pageSize") } }
    @Published public var pageKeys: PageKeys { didSet { save(pageKeys.rawValue, "pageKeys") } }
    @Published public var showStatusItem: Bool { didSet { save(showStatusItem, "showStatusItem") } }
    @Published public var traditional: Bool { didSet { save(traditional, "traditional") } }
    @Published public var emoji: Bool { didSet { save(emoji, "emoji") } }
    @Published public var fuzzy: Set<String> { didSet { save(fuzzy.sorted(), "fuzzy") } }
    @Published public var orientation: CandidateOrientation { didSet { save(orientation.rawValue, "orientation") } }
    @Published public var fontSize: Int { didSet { save(fontSize, "fontSize") } }
    @Published public var appearance: AppearanceMode { didSet { save(appearance.rawValue, "appearance") } }
    /// 织文互联，默认关闭。 WeaveLink, off by default.
    @Published public var linkEnabled: Bool { didSet { save(linkEnabled, "linkEnabled") } }
    @Published public var linkClipSync: Bool { didSet { save(linkClipSync, "linkClipSync") } }
    /// 手机上看到的本机名称；空为系统的电脑名称。 This Mac's name as phones see it; empty = the system computer name.
    @Published public var linkName: String { didSet { save(linkName, "linkName") } }

    public init(defaults: UserDefaults) {
        d = defaults
        schema = InputScheme.named(defaults.string(forKey: "schema") ?? "").id
        toggleKey = defaults.string(forKey: "toggleKey").flatMap(ToggleKey.init) ?? .shift
        pageSize = Self.clamp(defaults.object(forKey: "pageSize") as? Int ?? 7, Self.pageSizes)
        pageKeys = defaults.string(forKey: "pageKeys").flatMap(PageKeys.init) ?? .both
        showStatusItem = defaults.object(forKey: "showStatusItem") as? Bool ?? true
        traditional = defaults.bool(forKey: "traditional")
        emoji = defaults.object(forKey: "emoji") as? Bool ?? true
        fuzzy = Set(defaults.stringArray(forKey: "fuzzy") ?? [])
        orientation = defaults.string(forKey: "orientation").flatMap(CandidateOrientation.init) ?? .horizontal
        fontSize = Self.clamp(defaults.object(forKey: "fontSize") as? Int ?? 16, Self.fontSizes)
        appearance = defaults.string(forKey: "appearance").flatMap(AppearanceMode.init) ?? .system
        linkEnabled = defaults.bool(forKey: "linkEnabled")
        linkClipSync = defaults.object(forKey: "linkClipSync") as? Bool ?? true
        linkName = defaults.string(forKey: "linkName") ?? ""
    }

    public func isFuzzy(_ key: String) -> Bool { fuzzy.contains(key) }

    public func setFuzzy(_ key: String, _ on: Bool) {
        if on { fuzzy.insert(key) } else { fuzzy.remove(key) }
    }

    /// 写给内核的全部开关。 Every switch the engine should receive.
    public var engineOptions: [(String, Bool)] {
        FuzzyPair.all.map { ($0.id, fuzzy.contains($0.id)) }
            + [("output.traditional", traditional), ("candidates.emoji", emoji)]
    }

    private func save(_ value: Any, _ key: String) {
        d.set(value, forKey: key)
        NotificationCenter.default.post(name: Self.didChange, object: self)
    }

    private static func clamp(_ v: Int, _ r: ClosedRange<Int>) -> Int { min(max(v, r.lowerBound), r.upperBound) }
}
