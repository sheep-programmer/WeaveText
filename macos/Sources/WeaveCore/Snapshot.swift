import Foundation

/// 一个候选。 One candidate.
public struct Candidate: Decodable, Equatable, Sendable {
    public var text: String
    public var comment: String
    /// 来自用户词库（可删除）。 Learned from the user (deletable).
    public var user: Bool

    public init(text: String, comment: String = "", user: Bool = false) {
        self.text = text
        self.comment = comment
        self.user = user
    }

    private enum CodingKeys: String, CodingKey { case text, comment, user }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        text = try c.decode(String.self, forKey: .text)
        comment = try c.decodeIfPresent(String.self, forKey: .comment) ?? ""
        user = try c.decodeIfPresent(Bool.self, forKey: .user) ?? false
    }
}

/// 预编辑里的一处纠错标记；位置按 Unicode 标量计，左闭右开。
/// One auto-correction mark in the preedit; positions count Unicode scalars, half-open.
public struct PreeditMark: Decodable, Equatable, Sendable {
    public enum Kind: String, Decodable, Sendable { case swap, insert, replace, delete }
    public var start: Int
    public var end: Int
    public var kind: Kind
    /// 被去掉的多打字母（仅 delete）。 The dropped extra letter (delete only).
    public var removed: String

    public init(start: Int, end: Int, kind: Kind, removed: String = "") {
        self.start = start
        self.end = end
        self.kind = kind
        self.removed = removed
    }

    private enum CodingKeys: String, CodingKey { case start, end, kind, removed }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        start = try c.decode(Int.self, forKey: .start)
        end = try c.decode(Int.self, forKey: .end)
        kind = try c.decodeIfPresent(Kind.self, forKey: .kind) ?? .replace
        removed = try c.decodeIfPresent(String.self, forKey: .removed) ?? ""
    }

    /// 预编辑里需要标红的 UTF-16 范围（删除类没有可标的字母）。
    /// The UTF-16 ranges of `preedit` to draw in red (a deletion has no letter left to mark).
    public static func ranges(_ marks: [PreeditMark], in preedit: String) -> [NSRange] {
        let scalars = Array(preedit.unicodeScalars)
        var utf16 = [0]
        for s in scalars { utf16.append(utf16.last! + String(s).utf16.count) }
        return marks.compactMap { m in
            guard m.kind != .delete else { return nil }
            let a = max(0, m.start), b = min(scalars.count, m.end)
            return a < b ? NSRange(location: utf16[a], length: utf16[b] - utf16[a]) : nil
        }
    }
}

/// 一次操作后的内核状态（weave_snapshot_json）。 Engine state after an operation (weave_snapshot_json).
public struct Snapshot: Decodable, Equatable, Sendable {
    /// 需要上屏的文字。 Text to commit.
    public var commit: String
    public var preedit: String
    public var composing: Bool
    /// 候选栏里是上屏后的联想词（composing 为 false）。 The candidates are predictions after a commit (not composing).
    public var predicting: Bool
    /// 候选总数；被截断时是上限，界面翻到取不到为止。 Total candidates; a cap when cut, page until empty.
    public var total: Int
    /// 开头一批候选。 The first batch of candidates.
    public var candidates: [Candidate]
    public var schema: String
    /// 预编辑里的纠错标记。 Auto-correction marks in the preedit.
    public var marks: [PreeditMark]

    public init(commit: String = "", preedit: String = "", composing: Bool = false, predicting: Bool = false,
                total: Int = 0, candidates: [Candidate] = [], schema: String = "pinyin", marks: [PreeditMark] = []) {
        self.commit = commit
        self.preedit = preedit
        self.composing = composing
        self.predicting = predicting
        self.total = total
        self.candidates = candidates
        self.schema = schema
        self.marks = marks
    }

    private enum CodingKeys: String, CodingKey { case commit, preedit, composing, predicting, total, candidates, schema, marks }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        commit = try c.decodeIfPresent(String.self, forKey: .commit) ?? ""
        preedit = try c.decodeIfPresent(String.self, forKey: .preedit) ?? ""
        composing = try c.decodeIfPresent(Bool.self, forKey: .composing) ?? false
        predicting = try c.decodeIfPresent(Bool.self, forKey: .predicting) ?? false
        total = try c.decodeIfPresent(Int.self, forKey: .total) ?? 0
        candidates = try c.decodeIfPresent([Candidate].self, forKey: .candidates) ?? []
        schema = try c.decodeIfPresent(String.self, forKey: .schema) ?? "pinyin"
        marks = try c.decodeIfPresent([PreeditMark].self, forKey: .marks) ?? []
    }

    public static func decode(_ json: String) -> Snapshot? {
        try? JSONDecoder().decode(Snapshot.self, from: Data(json.utf8))
    }
}

/// 用户词（词库管理）。 A learned user word.
public struct UserWord: Decodable, Equatable, Hashable, Sendable {
    public var pinyin: String
    public var text: String
    public var count: Int

    public init(pinyin: String, text: String, count: Int) {
        self.pinyin = pinyin
        self.text = text
        self.count = count
    }
}
