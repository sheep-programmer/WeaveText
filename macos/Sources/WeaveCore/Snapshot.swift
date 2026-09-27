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

    public init(commit: String = "", preedit: String = "", composing: Bool = false, predicting: Bool = false,
                total: Int = 0, candidates: [Candidate] = [], schema: String = "pinyin") {
        self.commit = commit
        self.preedit = preedit
        self.composing = composing
        self.predicting = predicting
        self.total = total
        self.candidates = candidates
        self.schema = schema
    }

    private enum CodingKeys: String, CodingKey { case commit, preedit, composing, predicting, total, candidates, schema }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        commit = try c.decodeIfPresent(String.self, forKey: .commit) ?? ""
        preedit = try c.decodeIfPresent(String.self, forKey: .preedit) ?? ""
        composing = try c.decodeIfPresent(Bool.self, forKey: .composing) ?? false
        predicting = try c.decodeIfPresent(Bool.self, forKey: .predicting) ?? false
        total = try c.decodeIfPresent(Int.self, forKey: .total) ?? 0
        candidates = try c.decodeIfPresent([Candidate].self, forKey: .candidates) ?? []
        schema = try c.decodeIfPresent(String.self, forKey: .schema) ?? "pinyin"
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
