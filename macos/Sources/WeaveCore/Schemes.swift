/// 可选输入方案（键与内核 Schema::from_key 一致）。 Input schemes; keys match the engine's schema keys.
public struct InputScheme: Identifiable, Equatable, Sendable {
    public let id: String
    public let name: String

    /// 九键／14 键双拼的方案前缀，与内核 Schema::from_key 一致。 Keypad shuangpin prefixes, matching the engine's Schema::from_key.
    public static let keypadNinePrefix = "shuangpin9:"
    public static let keypadFourteenPrefix = "shuangpin14:"

    public static let pinyin = InputScheme(id: "pinyin", name: "全拼")
    public static let all: [InputScheme] = [
        pinyin,
        InputScheme(id: "shuangpin:xiaohe", name: "小鹤双拼"),
        InputScheme(id: "shuangpin:ziranma", name: "自然码双拼"),
        InputScheme(id: "shuangpin:microsoft", name: "微软双拼"),
        InputScheme(id: "shuangpin:sogou", name: "搜狗双拼"),
        InputScheme(id: "shuangpin:shoudao", name: "首道双拼"),
        InputScheme(id: "shuangpin:znabc", name: "智能ABC双拼"),
        InputScheme(id: "shuangpin:jiajia", name: "拼音加加"),
        InputScheme(id: keypadNinePrefix + "xiaohe", name: "小鹤九键双拼"),
        InputScheme(id: keypadFourteenPrefix + "xiaohe", name: "小鹤14键双拼"),
        InputScheme(id: "wubi86", name: "五笔 86"),
        InputScheme(id: "cangjie", name: "仓颉"),
        InputScheme(id: "bopomofo", name: "注音"),
        InputScheme(id: "romaji", name: "日语罗马字"),
        InputScheme(id: "english", name: "English"),
    ]

    public static func named(_ id: String) -> InputScheme { all.first { $0.id == id } ?? pinyin }

    /// 中文方案才用全角标点。 Only Chinese schemes use full-width punctuation.
    public var isChinese: Bool { id != "english" }
    public var isShuangpin: Bool { id.hasPrefix("shuangpin:") }
    /// 九键／14 键双拼：每个按键是一个字母组。 Keypad shuangpin: each tap is a letter group.
    public var isKeypadShuangpin: Bool { id.hasPrefix(Self.keypadNinePrefix) || id.hasPrefix(Self.keypadFourteenPrefix) }
    /// 双拼类（含九键、14 键）共用拼音的模糊音与设置。 Shuangpin family, keypad variants included, shares the pinyin settings.
    public var isShuangpinFamily: Bool { isShuangpin || isKeypadShuangpin }

    /// 键盘上的字符本身就是输入（注音的符号键、九键双拼的数字键）：优先送内核，不当作选词、翻页或标点。
    /// Keys that are themselves input (bopomofo symbols, keypad digits): sent to the engine before paging or picking.
    public var rawKeys: Set<Character> {
        switch id {
        case "bopomofo": return Self.bopomofoKeys
        case _ where id.hasPrefix(Self.keypadNinePrefix): return Self.keypadNineKeys
        default: return []
        }
    }
    private static let bopomofoKeys: Set<Character> = Set("1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/-3467")
    private static let keypadNineKeys: Set<Character> = Set("23456789")
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
