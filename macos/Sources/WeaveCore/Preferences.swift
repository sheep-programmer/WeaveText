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
    case brackets

    public func pagesBack(_ c: Character) -> Bool {
        switch self {
        case .both: return c == "-" || c == ","
        case .minusEqual: return c == "-"
        case .commaPeriod: return c == ","
        case .brackets: return c == "["
        }
    }

    public func pagesForward(_ c: Character) -> Bool {
        switch self {
        case .both: return c == "=" || c == "."
        case .minusEqual: return c == "="
        case .commaPeriod: return c == "."
        case .brackets: return c == "]"
        }
    }
}

public enum CandidateOrientation: String, CaseIterable, Sendable {
    case horizontal, vertical
}

/// 候选后面的拼音提示。 Pinyin shown after each candidate.
public enum PinyinHint: String, CaseIterable, Sendable {
    case off, toned, plain
}

/// 手写停笔多久自动上屏（单字；连写按 2 倍）。 How long a pause commits handwriting (spaced lines wait twice as long).
public enum HandPause: String, CaseIterable, Sendable {
    case fast, medium, slow
    public var seconds: Double {
        switch self {
        case .fast: return 0.6
        case .medium: return 0.9
        case .slow: return 1.4
        }
    }
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
    @Published public var traditional: Bool { didSet { save(traditional, "traditional") } }
    @Published public var emoji: Bool { didSet { save(emoji, "emoji") } }
    /// 联想词：上屏后推荐下一个词，默认打开。 Next-word predictions after a commit, on by default.
    @Published public var prediction: Bool { didSet { save(prediction, "prediction") } }
    /// 联想深度：连着选联想词最多接几次（1–6），默认 3。 How many predictions may be picked in a row (1–6), 3 by default.
    @Published public var predictionDepth: Int { didSet { save(predictionDepth, "predictionDepth") } }
    public static let predictionDepths = 1...6
    /// 自定义的候选快捷键（上一页、下一页、左右移动高亮、展开全部）。 Custom candidate shortcuts.
    @Published public var candidateKeys: CandidateKeys {
        didSet { save((try? JSONEncoder().encode(candidateKeys)) ?? Data(), "candidateKeys") }
    }
    @Published public var voiceShortcut: VoiceShortcut? {
        didSet { save(voiceShortcut.flatMap { try? JSONEncoder().encode($0) } ?? Data(), "voiceShortcut") }
    }
    @Published public var voiceLanguage: String { didSet { save(voiceLanguage, "voiceLanguage") } }
    /// 候选后显示拼音（可带声调），默认关闭。 Pinyin after candidates (optionally with tones), off by default.
    @Published public var pinyinHint: PinyinHint { didSet { save(pinyinHint.rawValue, "pinyinHint") } }
    /// 手写停笔自动上屏的快慢。 How quickly a pause commits handwriting.
    @Published public var handPause: HandPause { didSet { save(handPause.rawValue, "handPause") } }
    @Published public var fuzzy: Set<String> { didSet { save(fuzzy.sorted(), "fuzzy") } }
    @Published public var orientation: CandidateOrientation { didSet { save(orientation.rawValue, "orientation") } }
    @Published public var fontSize: Int { didSet { save(fontSize, "fontSize") } }
    @Published public var appearance: AppearanceMode { didSet { save(appearance.rawValue, "appearance") } }
    /// 织文互联，默认关闭。 WeaveLink, off by default.
    @Published public var linkEnabled: Bool { didSet { save(linkEnabled, "linkEnabled") } }
    @Published public var linkClipSync: Bool { didSet { save(linkClipSync, "linkClipSync") } }
    /// 手机上看到的本机名称；空为系统的电脑名称。 This Mac's name as phones see it; empty = the system computer name.
    @Published public var linkName: String { didSet { save(linkName, "linkName") } }

    @Published public var linkReceiveDirectory: String { didSet { save(linkReceiveDirectory, "linkReceiveDirectory") } }
    @Published public var linkPublicAddress: String { didSet { save(linkPublicAddress, "linkPublicAddress") } }

    public init(defaults: UserDefaults) {
        d = defaults
        schema = InputScheme.named(defaults.string(forKey: "schema") ?? "").id
        toggleKey = defaults.string(forKey: "toggleKey").flatMap(ToggleKey.init) ?? .shift
        pageSize = Self.clamp(defaults.object(forKey: "pageSize") as? Int ?? 7, Self.pageSizes)
        pageKeys = defaults.string(forKey: "pageKeys").flatMap(PageKeys.init) ?? .both
        traditional = defaults.bool(forKey: "traditional")
        emoji = defaults.object(forKey: "emoji") as? Bool ?? true
        prediction = defaults.object(forKey: "prediction") as? Bool ?? true
        predictionDepth = Self.clamp(defaults.object(forKey: "predictionDepth") as? Int ?? 3, Self.predictionDepths)
        candidateKeys = defaults.data(forKey: "candidateKeys").flatMap { try? JSONDecoder().decode(CandidateKeys.self, from: $0) } ?? CandidateKeys()
        voiceShortcut = defaults.object(forKey: "voiceShortcut") == nil ? .defaultBinding :
            defaults.data(forKey: "voiceShortcut").flatMap { try? JSONDecoder().decode(VoiceShortcut.self, from: $0) }
        voiceLanguage = defaults.string(forKey: "voiceLanguage") == "en-US" ? "en-US" : "zh-CN"
        pinyinHint = defaults.string(forKey: "pinyinHint").flatMap(PinyinHint.init) ?? .off
        handPause = defaults.string(forKey: "handPause").flatMap(HandPause.init) ?? .medium
        fuzzy = Set(defaults.stringArray(forKey: "fuzzy") ?? [])
        orientation = defaults.string(forKey: "orientation").flatMap(CandidateOrientation.init) ?? .horizontal
        fontSize = Self.clamp(defaults.object(forKey: "fontSize") as? Int ?? 16, Self.fontSizes)
        appearance = defaults.string(forKey: "appearance").flatMap(AppearanceMode.init) ?? .system
        linkEnabled = defaults.bool(forKey: "linkEnabled")
        linkClipSync = defaults.object(forKey: "linkClipSync") as? Bool ?? true
        linkName = defaults.string(forKey: "linkName") ?? ""
        linkReceiveDirectory = defaults.string(forKey: "linkReceiveDirectory") ?? ""
        linkPublicAddress = defaults.string(forKey: "linkPublicAddress") ?? ""
    }

    public func isFuzzy(_ key: String) -> Bool { fuzzy.contains(key) }

    public func setFuzzy(_ key: String, _ on: Bool) {
        if on { fuzzy.insert(key) } else { fuzzy.remove(key) }
    }

    /// 写给内核的全部开关。 Every switch the engine should receive.
    public var engineOptions: [(String, Bool)] {
        FuzzyPair.all.map { ($0.id, fuzzy.contains($0.id)) }
            + [("output.traditional", traditional), ("candidates.emoji", emoji),
               ("candidates.prediction", prediction),
               ("candidates.pinyin", pinyinHint != .off), ("candidates.pinyin_tones", pinyinHint != .plain)]
    }

    private func save(_ value: Any, _ key: String) {
        d.set(value, forKey: key)
        NotificationCenter.default.post(name: Self.didChange, object: self)
    }

    private static func clamp(_ v: Int, _ r: ClosedRange<Int>) -> Int { min(max(v, r.lowerBound), r.upperBound) }
}
