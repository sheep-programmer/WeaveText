/// 与 AppKit 无关的按键描述（由 NSEvent 转来）。 An AppKit-free key description, converted from NSEvent.
public struct KeyInput: Equatable, Sendable {
    public var keyCode: UInt16
    /// 含修饰键效果的字符（Shift+1 = "!"）。 Characters with modifiers applied.
    public var characters: String
    public var shift = false
    public var control = false
    public var option = false
    public var command = false
    public var capsLock = false

    public init(keyCode: UInt16, characters: String, shift: Bool = false, control: Bool = false,
                option: Bool = false, command: Bool = false, capsLock: Bool = false) {
        self.keyCode = keyCode
        self.characters = characters
        self.shift = shift
        self.control = control
        self.option = option
        self.command = command
        self.capsLock = capsLock
    }

    public var character: Character? { characters.count == 1 ? characters.first : nil }
}

/// 物理键码（与布局无关的 kVK_*）。 Layout-independent virtual key codes (kVK_*).
public enum KeyCode {
    public static let returnKey: UInt16 = 36
    public static let tab: UInt16 = 48
    public static let space: UInt16 = 49
    public static let delete: UInt16 = 51
    public static let escape: UInt16 = 53
    public static let keypadEnter: UInt16 = 76
    public static let home: UInt16 = 115
    public static let pageUp: UInt16 = 116
    public static let forwardDelete: UInt16 = 117
    public static let end: UInt16 = 119
    public static let pageDown: UInt16 = 121
    public static let left: UInt16 = 123
    public static let right: UInt16 = 124
    public static let down: UInt16 = 125
    public static let up: UInt16 = 126
}

/// 按键时的输入法状态。 IME state when a key arrives.
public struct KeyContext: Equatable, Sendable {
    public var composing: Bool
    /// 中文模式（false 为英文直通）。 Chinese mode; false = English pass-through.
    public var chinese: Bool
    public var pageSize: Int
    public var pageKeys: PageKeys
    /// 拼音 v 模式（v1234、v(1+2)*3）：数字与运算符进组合串，不选词。
    /// Pinyin v mode: digits and operators go into the composition instead of picking candidates.
    public var vMode: Bool

    public init(composing: Bool, chinese: Bool = true, pageSize: Int = 7, pageKeys: PageKeys = .both,
                vMode: Bool = false) {
        self.composing = composing
        self.vMode = vMode
        self.chinese = chinese
        self.pageSize = pageSize
        self.pageKeys = pageKeys
    }
}

/// 一次按键要做的事。 What a key press should do.
public enum KeyAction: Equatable, Sendable {
    /// 交给应用。 Hand the key to the app.
    case pass
    /// 吞掉，什么也不做。 Swallow it, do nothing.
    case swallow
    /// 送给内核的字母（v 模式里也包括数字与运算符）。 A letter for the engine (in v mode also digits and operators).
    case letter(Character)
    /// 标点：组合中先试着送内核（' ;），不收再上屏首选并输出标点。
    /// Punctuation: while composing try the engine first (' ;), else commit and emit the mark.
    case punctuation(Character)
    case backspace
    case clear
    /// 回车：原样上屏字母。 Return: commit the typed letters.
    case commitRaw
    /// 空格：上屏高亮候选。 Space: commit the highlighted candidate.
    case commitHighlighted
    /// 选当前页第 n 个（从 0 起）。 Pick the n-th candidate of the page (0-based).
    case select(Int)
    case pagePrevious
    case pageNext
    case highlightPrevious
    case highlightNext
}

/// 联想词显示时一次按键要做的事。 What a key does while predictions show.
public enum PredictionAction: Equatable, Sendable {
    /// 选当前页第 n 个联想词（上屏后接着联想）。 Pick the n-th prediction (0-based); predicting continues.
    case select(Int)
    /// 只收起联想（Esc）。 Just dismiss the predictions (Esc).
    case dismiss
    /// 收起联想，再把这个键照常处理（空格、回车、方向键、标点、字母、快捷键…）。
    /// Dismiss, then handle the key as usual (space, return, arrows, punctuation, letters, shortcuts…).
    case dismissAndHandle
}

/// 不在组合时交给应用的键对学习的影响。 What a key handed to the app while idle means for learning.
public enum IdlePass: Equatable, Sendable {
    /// 退格：先交给内核（刚上屏又没写别的字时内核撤销学到的词），再由应用删字。
    /// Backspace: the engine first (it undoes what the last commit learned when nothing was written since),
    /// then the app deletes.
    case backspace
    /// 应用会写字或挪光标（标点、空格、数字、符号、回车、方向键、快捷键…）：断开与上一次上屏的联系。
    /// The app writes text or moves the caret (punctuation, space, digits, symbols, return, arrows, shortcuts…):
    /// break the chain with the previous commit.
    case breakChain
    /// 什么也不写（Esc）。 Writes nothing (Esc).
    case none
}

public enum KeyMapper {
    /// 不在组合时交给应用的键（纯函数）。 A key handed to the app while idle, a pure function.
    public static func idlePass(for key: KeyInput) -> IdlePass {
        switch key.keyCode {
        case KeyCode.delete: return .backspace
        case KeyCode.escape: return .none
        default: return .breakChain
        }
    }

    /// 联想词显示时的按键（纯函数）：数字选词，Esc 收起，其余收起后照常处理。
    /// Keys while predictions show, a pure function: digits pick, Esc dismisses, anything else dismisses and goes on.
    public static func predictionAction(for key: KeyInput, count: Int, pageSize: Int) -> PredictionAction {
        if key.command || key.control || key.option { return .dismissAndHandle }
        if key.keyCode == KeyCode.escape { return .dismiss }
        if let c = key.character, c.isASCII, let d = c.wholeNumberValue, d >= 1, d <= min(count, pageSize) {
            return .select(d - 1)
        }
        return .dismissAndHandle
    }

    /// 按键 → 动作（纯函数）。 Key → action, a pure function.
    public static func action(for key: KeyInput, in ctx: KeyContext) -> KeyAction {
        // 快捷键一律放行。 Shortcuts always go to the app.
        if key.command || key.control || key.option { return .pass }
        if !ctx.chinese { return .pass }
        if ctx.composing { return composingAction(key, ctx) }
        // 大写锁定：直通输入大写。 Caps Lock: pass through, the app types capitals.
        if key.capsLock { return .pass }
        guard let c = key.character, !isFunctionKey(key.keyCode) else { return .pass }
        if c.isASCII, c.isLetter {
            // Shift+字母直接出大写。 Shift+letter types a capital directly.
            return key.shift ? .pass : .letter(c)
        }
        if Punctuation.isMapped(c) { return .punctuation(c) }
        return .pass
    }

    private static func composingAction(_ key: KeyInput, _ ctx: KeyContext) -> KeyAction {
        switch key.keyCode {
        case KeyCode.returnKey, KeyCode.keypadEnter: return .commitRaw
        case KeyCode.escape: return .clear
        case KeyCode.delete: return .backspace
        case KeyCode.space: return .commitHighlighted
        case KeyCode.left, KeyCode.up: return .highlightPrevious
        case KeyCode.right, KeyCode.down, KeyCode.tab: return .highlightNext
        case KeyCode.pageUp: return .pagePrevious
        case KeyCode.pageDown: return .pageNext
        case KeyCode.home, KeyCode.end, KeyCode.forwardDelete: return .swallow
        default: break
        }
        guard let c = key.character else { return .swallow }
        if c.isASCII, c.isLetter { return .letter(c) }
        if ctx.vMode, Calc.vAccepts(c) { return .letter(c) }
        if let d = c.wholeNumberValue, c.isASCII {
            return d >= 1 && d <= ctx.pageSize ? .select(d - 1) : .swallow
        }
        if ctx.pageKeys.pagesBack(c) { return .pagePrevious }
        if ctx.pageKeys.pagesForward(c) { return .pageNext }
        if c.isASCII, !c.isWhitespace, c.asciiValue.map({ $0 >= 0x21 && $0 < 0x7f }) == true {
            return .punctuation(c)
        }
        return .swallow
    }

    private static func isFunctionKey(_ code: UInt16) -> Bool {
        switch code {
        case KeyCode.returnKey, KeyCode.keypadEnter, KeyCode.tab, KeyCode.space, KeyCode.delete, KeyCode.escape,
             KeyCode.home, KeyCode.end, KeyCode.pageUp, KeyCode.pageDown, KeyCode.forwardDelete,
             KeyCode.left, KeyCode.right, KeyCode.up, KeyCode.down:
            return true
        default:
            return false
        }
    }
}

/// 单击 Shift 切换中英：按下到松开之间没有别的键、也没按太久。
/// Tapping Shift alone toggles Chinese/English: no other key in between and not held too long.
public struct ShiftTapDetector: Sendable {
    public var maxHold: Double = 0.5
    private var downAt: Double?

    public init() {}

    /// 修饰键变化；返回 true 表示应切换。 A modifier change; true means toggle now.
    public mutating func flagsChanged(shift: Bool, otherModifiers: Bool, at time: Double) -> Bool {
        if otherModifiers {
            downAt = nil
            return false
        }
        if shift {
            downAt = time
            return false
        }
        defer { downAt = nil }
        guard let t = downAt else { return false }
        return time - t <= maxHold
    }

    /// Shift 按住时来了别的键：这次不算单击。 Another key while Shift is held: not a tap.
    public mutating func keyDown() { downAt = nil }
}
