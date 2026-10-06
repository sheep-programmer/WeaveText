/// 中文模式的全角标点，引号成对交替。 Full-width punctuation for Chinese mode, with alternating quotes.
public struct Punctuation: Sendable {
    public static let pairs: [String:String] = ["（":"）", "《":"》", "【":"】", "“":"”", "‘":"’"]
    static let table: [Character: String] = [
        ",": "，", ".": "。", "?": "？", "!": "！", ":": "：", ";": "；",
        "(": "（", ")": "）", "<": "《", ">": "》", "[": "【", "]": "】",
        "\\": "、", "^": "……", "_": "——", "$": "￥", "~": "～", "`": "·",
    ]

    private var doubleOpen = true
    private var singleOpen = true

    public init() {}

    public static func isMapped(_ c: Character) -> Bool { table[c] != nil || c == "\"" || c == "'" }

    /// 转换一个 ASCII 标点；afterDigit 时句点、逗号、冒号保持半角（3.14、1,000、12:30）。
    /// Convert one ASCII mark; after a digit `.`, `,` and `:` stay half-width (3.14, 1,000, 12:30).
    public mutating func convert(_ c: Character, afterDigit: Bool = false) -> String {
        if afterDigit, c == "." || c == "," || c == ":" { return String(c) }
        switch c {
        case "\"":
            defer { doubleOpen.toggle() }
            return doubleOpen ? "“" : "”"
        case "'":
            defer { singleOpen.toggle() }
            return singleOpen ? "‘" : "’"
        default:
            return Self.table[c] ?? String(c)
        }
    }

    /// 换输入框时引号重新从左引号开始。 A new text field starts quotes afresh.
    public mutating func reset() {
        doubleOpen = true
        singleOpen = true
    }
}
