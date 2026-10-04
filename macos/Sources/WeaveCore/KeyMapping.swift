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

/// 用户自定义的候选快捷键（一个键）。字符键按字符匹配（-、=、[、]、, 等，换键盘布局也一样），功能键按键码匹配。
/// A user-chosen candidate shortcut (one key). Printable keys match by character, so they keep working across keyboard
/// layouts; function keys match by key code.
public struct KeyBinding: Codable, Equatable, Sendable {
    public var keyCode: UInt16
    /// 可打印字符；功能键为 nil。 The printable character; nil for function keys.
    public var character: String?
    /// 界面上显示的名字（← → ⇥ …）。 The name shown in the UI.
    public var label: String

    public init(keyCode: UInt16, character: String?, label: String) {
        self.keyCode = keyCode
        self.character = character
        self.label = label
    }

    public func matches(_ key: KeyInput) -> Bool {
        if let c = character { return key.characters == c }
        return key.keyCode == keyCode
    }

    /// 从一次按键生成绑定；被输入占用的键（字母、数字、空格、回车、Esc、退格）返回 nil 及原因。
    /// Make a binding from a key press; keys the input needs (letters, digits, space, return, Esc, delete) are refused.
    public static func make(from key: KeyInput) -> (binding: KeyBinding?, refusal: String?) {
        if key.command || key.control || key.option { return (nil, "不能带 ⌘ ⌃ ⌥") }
        switch key.keyCode {
        case KeyCode.returnKey, KeyCode.keypadEnter: return (nil, "回车已用于上屏字母")
        case KeyCode.escape: return (nil, "Esc 已用于取消输入")
        case KeyCode.delete, KeyCode.forwardDelete: return (nil, "退格已用于删除")
        case KeyCode.space: return (nil, "空格已用于选词")
        case KeyCode.home, KeyCode.end: return (nil, "这个键已被占用")
        default: break
        }
        if let named = functionName(key.keyCode) {
            return (KeyBinding(keyCode: key.keyCode, character: nil, label: named), nil)
        }
        guard let c = key.character, c.isASCII else { return (nil, "请按一个标点或方向键") }
        if c.isLetter { return (nil, "字母要用来打字") }
        if c.isNumber { return (nil, "数字要用来选词") }
        return (KeyBinding(keyCode: key.keyCode, character: String(c), label: String(c)), nil)
    }

    static func functionName(_ code: UInt16) -> String? {
        switch code {
        case KeyCode.left: return "←"
        case KeyCode.right: return "→"
        case KeyCode.up: return "↑"
        case KeyCode.down: return "↓"
        case KeyCode.tab: return "⇥ Tab"
        case KeyCode.pageUp: return "Page Up"
        case KeyCode.pageDown: return "Page Down"
        default: return nil
        }
    }
}

/// 语音窗口的组合键，只在织文处于当前输入源时生效。
/// A modified shortcut for the voice window, active only while WeaveText is the input source.
public struct VoiceShortcut: Codable, Equatable, Sendable {
    public var keyCode: UInt16
    public var shift: Bool
    public var control: Bool
    public var option: Bool
    public var command: Bool
    public var label: String

    public static let defaultBinding = VoiceShortcut(keyCode: 9, shift: false, control: true, option: true, command: false, label: "⌃⌥V")

    public func matches(_ key: KeyInput) -> Bool {
        key.keyCode == keyCode && key.shift == shift && key.control == control && key.option == option && key.command == command
    }

    public static func make(from key: KeyInput) -> VoiceShortcut? {
        guard key.control || key.option || key.command else { return nil }
        let name = KeyBinding.functionName(key.keyCode)
            ?? (key.keyCode == KeyCode.space ? "Space" : key.character.flatMap { $0.isASCII && !$0.isWhitespace ? String($0).uppercased() : nil })
        guard let name, key.keyCode != KeyCode.escape else { return nil }
        let modifiers = (key.control ? "⌃" : "") + (key.option ? "⌥" : "") + (key.shift ? "⇧" : "") + (key.command ? "⌘" : "")
        return VoiceShortcut(keyCode: key.keyCode, shift: key.shift, control: key.control, option: key.option, command: key.command, label: modifiers + name)
    }
}

/// 可以自定义快捷键的候选操作。 The candidate actions that can have a custom key.
public enum CandidateKeySlot: String, CaseIterable, Sendable {
    case pagePrevious, pageNext, highlightPrevious, highlightNext, expand

    public var title: String {
        switch self {
        case .pagePrevious: return "上一页"
        case .pageNext: return "下一页"
        case .highlightPrevious: return "高亮左移"
        case .highlightNext: return "高亮右移"
        case .expand: return "展开／收起全部候选"
        }
    }

    /// 没自定义时用的键。 The keys used when nothing is set.
    public var defaultKeys: String {
        switch self {
        case .pagePrevious: return "默认 -  ,  Page Up"
        case .pageNext: return "默认 =  .  Page Down"
        case .highlightPrevious: return "默认 ←  ↑"
        case .highlightNext: return "默认 →  ↓  Tab"
        case .expand: return "默认没有快捷键，可点候选窗右侧的下拉按钮"
        }
    }
}

/// 候选窗的自定义快捷键；没设的动作沿用默认键。 Custom candidate shortcuts; an unset action keeps its default keys.
public struct CandidateKeys: Codable, Equatable, Sendable {
    public var pagePrevious: KeyBinding?
    public var pageNext: KeyBinding?
    public var highlightPrevious: KeyBinding?
    public var highlightNext: KeyBinding?
    /// 展开／收起全部候选。 Expand or collapse the full candidate list.
    public var expand: KeyBinding?

    public init(pagePrevious: KeyBinding? = nil, pageNext: KeyBinding? = nil, highlightPrevious: KeyBinding? = nil,
                highlightNext: KeyBinding? = nil, expand: KeyBinding? = nil) {
        self.pagePrevious = pagePrevious
        self.pageNext = pageNext
        self.highlightPrevious = highlightPrevious
        self.highlightNext = highlightNext
        self.expand = expand
    }

    public var isDefault: Bool { self == CandidateKeys() }

    public subscript(slot: CandidateKeySlot) -> KeyBinding? {
        get {
            switch slot {
            case .pagePrevious: return pagePrevious
            case .pageNext: return pageNext
            case .highlightPrevious: return highlightPrevious
            case .highlightNext: return highlightNext
            case .expand: return expand
            }
        }
        set {
            switch slot {
            case .pagePrevious: pagePrevious = newValue
            case .pageNext: pageNext = newValue
            case .highlightPrevious: highlightPrevious = newValue
            case .highlightNext: highlightNext = newValue
            case .expand: expand = newValue
            }
        }
    }

    /// 给某个操作设键；同一个键原先给了别的操作就从那里拿掉。 Assign a key; another action holding it loses it.
    public mutating func assign(_ binding: KeyBinding, to slot: CandidateKeySlot) {
        for other in CandidateKeySlot.allCases where other != slot && self[other] == binding { self[other] = nil }
        self[slot] = binding
    }

    /// 这个键对应的动作；自定义的优先于默认键。 The action for a key; custom bindings beat the default keys.
    func action(for key: KeyInput) -> KeyAction? {
        if let b = pagePrevious, b.matches(key) { return .pagePrevious }
        if let b = pageNext, b.matches(key) { return .pageNext }
        if let b = highlightPrevious, b.matches(key) { return .highlightPrevious }
        if let b = highlightNext, b.matches(key) { return .highlightNext }
        if let b = expand, b.matches(key) { return .toggleExpand }
        return nil
    }
}

/// 按键时的输入法状态。 IME state when a key arrives.
public struct KeyContext: Equatable, Sendable {
    public var composing: Bool
    /// 中文模式；false 为英文单词补全。 Chinese mode; false = English word completion.
    public var chinese: Bool
    public var pageSize: Int
    public var pageKeys: PageKeys
    /// 拼音 v 模式（v1234、v(1+2)*3）：数字与运算符进组合串，不选词。
    /// Pinyin v mode: digits and operators go into the composition instead of picking candidates.
    public var vMode: Bool
    /// 自定义的候选快捷键。 Custom candidate shortcuts.
    public var bindings = CandidateKeys()

    public init(composing: Bool, chinese: Bool = true, pageSize: Int = 7, pageKeys: PageKeys = .both,
                vMode: Bool = false, bindings: CandidateKeys = CandidateKeys()) {
        self.bindings = bindings
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
    /// English space accepts the word and inserts the separator explicitly pressed by the user.
    case commitEnglishWord
    case finishEnglishAndPass
    /// 选当前页第 n 个（从 0 起）。 Pick the n-th candidate of the page (0-based).
    case select(Int)
    case pagePrevious
    case pageNext
    case highlightPrevious
    case highlightNext
    /// 展开／收起全部候选。 Expand or collapse the full candidate list.
    case toggleExpand
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
        // 用户自定义的候选键优先于默认键，只在组合中生效。 Custom candidate keys beat the defaults, while composing only.
        if ctx.composing, let custom = ctx.bindings.action(for: key) { return custom }
        if !ctx.chinese { return englishAction(key, ctx) }
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

    private static func englishAction(_ key: KeyInput, _ ctx: KeyContext) -> KeyAction {
        if let c = key.character, c.isASCII, c.isLetter { return .letter(c) }
        guard ctx.composing else { return .pass }
        switch key.keyCode {
        case KeyCode.space: return .commitEnglishWord
        case KeyCode.returnKey, KeyCode.keypadEnter: return .commitRaw
        case KeyCode.delete: return .backspace
        case KeyCode.escape: return .clear
        case KeyCode.tab, KeyCode.down: return .highlightNext
        case KeyCode.up: return .highlightPrevious
        case KeyCode.left, KeyCode.right, KeyCode.home, KeyCode.end, KeyCode.forwardDelete: return .finishEnglishAndPass
        default: break
        }
        if let c = key.character, c.isASCII, !c.isWhitespace { return .punctuation(c) }
        return .finishEnglishAndPass
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
