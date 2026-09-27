import Foundation

/// 敲等号时从光标前的文字里取出算式。 Pull the expression out of the text before the cursor when `=` is typed.
public enum Calc {
    static let chars: Set<Character> = Set(".+-*/×÷%^()（）")

    /// before：光标前的文字（不含刚敲的等号）。 before: the text before the caret, without the `=` just typed.
    public static func expression(before: String) -> String? {
        var body = before
        if body.hasSuffix("=") || body.hasSuffix("＝") { body.removeLast() }
        var tail = String(body.reversed().prefix { $0.isASCII && $0.isNumber || chars.contains($0) }.reversed())
        while let f = tail.first, f == ")" || f == "）" { tail.removeFirst() }
        return tail.count >= 3 ? tail : nil
    }

    /// v 模式：组合串以 v 开头，后面是数字或运算符（或只有 v）。
    /// The v mode: the preedit starts with `v` followed by digits or operators (or is just `v`).
    public static func isVMode(preedit: String, scheme: String) -> Bool {
        guard scheme == "pinyin", preedit.first == "v" else { return false }
        guard let second = preedit.dropFirst().first else { return true }
        return vAccepts(second)
    }

    /// v 模式里送给内核的字符（与内核 special::v_accepts 一致）。 Characters the v mode takes (as the engine's v_accepts).
    public static func vAccepts(_ c: Character) -> Bool {
        c.isASCII && (c.isNumber || ".+-*/()%^".contains(c))
    }
}
