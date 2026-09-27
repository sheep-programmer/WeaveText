import CWeave
import Foundation

/// 织文内核（C 接口）的 Swift 包装；一个进程一个会话，只在主线程使用。
/// Swift wrapper over the engine's C ABI; one session per process, used on the main thread.
public final class WeaveSession {
    private let handle: OpaquePointer

    /// 数据目录放 .wvz / 原始词库，用户目录放学习数据。 Data dir holds dictionaries; user dir holds learned data.
    public init?(dataDir: String, userDir: String) {
        guard let h = weave_create(dataDir, userDir) else { return nil }
        handle = h
    }

    deinit { weave_destroy(handle) }

    @discardableResult
    public func setSchema(_ key: String) -> Bool { weave_set_schema(handle, key) }

    @discardableResult
    public func setOption(_ key: String, _ on: Bool) -> Bool { weave_set_option(handle, key, on) }

    /// 送一个字符；内核不收时返回 false。 Feed one character; false when the engine does not take it.
    public func input(_ c: Character) -> Bool {
        guard let scalar = c.unicodeScalars.first, c.unicodeScalars.count == 1 else { return false }
        return weave_input_char(handle, scalar.value)
    }

    @discardableResult
    public func backspace() -> Bool { weave_backspace(handle) }

    /// 选第 index 个候选（全局序号）。 Pick the candidate at a global index.
    @discardableResult
    public func select(_ index: Int) -> Bool { weave_select(handle, UInt32(max(0, index))) }

    @discardableResult
    public func forget(_ index: Int) -> Bool { weave_forget(handle, UInt32(max(0, index))) }

    public func commitFirst() { weave_commit_first(handle) }
    public func commitRaw() { weave_commit_raw(handle) }
    public func clear() { weave_clear(handle) }
    public func flush() { weave_flush(handle) }
    public var isComposing: Bool { weave_is_composing(handle) }
    public func setLearning(_ on: Bool) { weave_set_learning(handle, on) }

    public func setContext(_ previousWord: String?) {
        if let w = previousWord { weave_set_context(handle, w) } else { weave_set_context(handle, nil) }
    }

    /// 读取状态；commit 读后即清空。 Read the state; `commit` is drained.
    public func snapshot() -> Snapshot {
        take(weave_snapshot_json(handle)).flatMap(Snapshot.decode) ?? Snapshot()
    }

    public func candidates(offset: Int, limit: Int) -> [Candidate] {
        decode([Candidate].self, weave_candidates_json(handle, UInt32(offset), UInt32(limit))) ?? []
    }

    public var userWordCount: Int { Int(weave_user_word_count(handle)) }

    public func userWords(query: String, offset: Int = 0, limit: Int = 500) -> [UserWord] {
        decode([UserWord].self, weave_user_words_json(handle, query, UInt32(offset), UInt32(limit))) ?? []
    }

    @discardableResult
    public func deleteUserWord(_ w: UserWord) -> Bool { weave_delete_user_word(handle, w.pinyin, w.text) }

    @discardableResult
    public func clearUserWords() -> Bool { weave_clear_user_words(handle) }

    private func take(_ p: UnsafeMutablePointer<CChar>?) -> String? {
        guard let p else { return nil }
        defer { weave_string_free(p) }
        return String(cString: p)
    }

    private func decode<T: Decodable>(_ type: T.Type, _ p: UnsafeMutablePointer<CChar>?) -> T? {
        take(p).flatMap { try? JSONDecoder().decode(type, from: Data($0.utf8)) }
    }
}
