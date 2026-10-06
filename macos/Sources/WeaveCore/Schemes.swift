/// 可选输入方案（键与内核 Schema::from_key 一致）。 Input schemes; keys match the engine's schema keys.
public struct InputScheme: Identifiable, Equatable, Sendable {
    public let id: String
    public let name: String

    public static let pinyin = InputScheme(id: "pinyin", name: "全拼")
    public static let all: [InputScheme] = [
        pinyin,
        InputScheme(id: "shuangpin:xiaohe", name: "小鹤双拼"),
        InputScheme(id: "shuangpin:ziranma", name: "自然码双拼"),
        InputScheme(id: "shuangpin:microsoft", name: "微软双拼"),
        InputScheme(id: "shuangpin:sogou", name: "搜狗双拼"),
        InputScheme(id: "wubi86", name: "五笔 86"),
        InputScheme(id: "english", name: "English"),
    ]

    public static func named(_ id: String) -> InputScheme { all.first { $0.id == id } ?? pinyin }

    /// 中文方案才用全角标点。 Only Chinese schemes use full-width punctuation.
    public var isChinese: Bool { id != "english" }
    public var isShuangpin: Bool { id.hasPrefix("shuangpin:") }
}

/// 模糊音开关（键与内核 fuzzy.* 选项一致）。 Fuzzy-sound switches; keys match the engine's fuzzy.* options.
public struct FuzzyPair: Identifiable, Equatable, Sendable {
    public let id: String
    public let label: String

    public static let all: [FuzzyPair] = [
        ("z_zh", "z = zh"), ("c_ch", "c = ch"), ("s_sh", "s = sh"), ("n_l", "n = l"), ("f_h", "f = h"),
        ("r_l", "r = l"), ("an_ang", "an = ang"), ("en_eng", "en = eng"), ("in_ing", "in = ing"),
        ("ian_iang", "ian = iang"), ("uan_uang", "uan = uang"),
    ].map { FuzzyPair(id: "fuzzy." + $0.0, label: $0.1) }
}
