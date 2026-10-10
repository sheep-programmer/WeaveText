import Foundation

/// Platform-independent session and draft ownership. The UI supplies scheduling;
/// an injected monotonic clock makes deadlines testable without sleeping.
public struct VoiceLifecycle {
    public enum State: Equatable { case idle, authorizing, listening, finishing }
    public enum StopAction: Equatable { case none, cancel, finishRecording }
    public enum Timeout: Equatable { case authorization, plugins, finishing }
    public struct Timeouts {
        public var authorization: TimeInterval
        public var plugins: TimeInterval
        public var finishing: TimeInterval
        public init(authorization: TimeInterval = 60, plugins: TimeInterval = 15, finishing: TimeInterval = 15) {
            self.authorization = authorization; self.plugins = plugins; self.finishing = finishing
        }
    }

    public private(set) var state = State.idle
    public private(set) var token = 0
    public private(set) var draft = ""
    private let now: () -> TimeInterval
    private let timeouts: Timeouts
    private var deadline: TimeInterval?
    private var preparation = Timeout.authorization
    private var prefix = ""
    private var edited = false

    public init(timeouts: Timeouts = .init(), now: @escaping () -> TimeInterval = { ProcessInfo.processInfo.systemUptime }) {
        self.timeouts = timeouts; self.now = now
    }

    public var remainingTime: TimeInterval? { deadline.map { max(0, $0 - now()) } }
    public func accepts(_ token: Int) -> Bool {
        self.token == token && state != .idle && (deadline.map { now() < $0 } ?? true)
    }

    @discardableResult public mutating func begin(draft: String) -> Int? {
        guard state == .idle else { return nil }
        token += 1; state = .authorizing; preparation = .authorization
        self.draft = draft; prefix = draft; edited = false
        deadline = now() + timeouts.authorization
        return token
    }

    @discardableResult public mutating func preparePlugins(token: Int) -> Bool {
        guard accepts(token), state == .authorizing, preparation == .authorization else { return false }
        preparation = .plugins; deadline = now() + timeouts.plugins
        return true
    }

    @discardableResult public mutating func didStartRecording(token: Int) -> Bool {
        guard accepts(token), state == .authorizing, preparation == .plugins else { return false }
        state = .listening; deadline = nil
        return true
    }

    public mutating func stop() -> StopAction {
        switch state {
        case .idle: return .none
        case .authorizing, .finishing: close(); return .cancel
        case .listening:
            state = .finishing; deadline = now() + timeouts.finishing
            return .finishRecording
        }
    }

    @discardableResult public mutating func finish(token: Int) -> Bool {
        guard accepts(token) else { return false }
        close(); return true
    }

    public mutating func close() {
        token += 1; state = .idle; deadline = nil
    }

    public mutating func expire(token: Int) -> Timeout? {
        guard self.token == token, state != .idle, let deadline, now() >= deadline else { return nil }
        let reason: Timeout = state == .finishing ? .finishing : preparation
        close(); return reason
    }

    public mutating func editDraft(_ text: String) { draft = text; edited = true }
    @discardableResult public mutating func updateTranscript(_ text: String, token: Int) -> String? {
        guard accepts(token), !edited else { return nil }
        draft = combined(text); return draft
    }
    /// Selecting a result is an explicit user action, including after completion.
    @discardableResult public mutating func selectTranscript(_ text: String) -> String {
        draft = combined(text); edited = false; return draft
    }
    private func combined(_ text: String) -> String {
        prefix.isEmpty ? text : text.isEmpty ? prefix : prefix + "\n" + text
    }

    public mutating func clearDraft() {
        guard state == .idle else { return }
        close(); draft = ""; prefix = ""; edited = false
    }

    /// Only explicit confirmation can write text; a failed writer retains the draft.
    @discardableResult public mutating func commit(using writer: (String) -> Bool) -> Bool {
        guard state == .idle, !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
        guard writer(draft) else { return false }
        clearDraft(); return true
    }
}

public protocol VoiceSessionHandle: AnyObject, Sendable {
    func feed(_ data: Data)
    func stop()
    func cancel()
}

/// Shared with the preparation worker. Closing releases every adopted handle;
/// handles arriving after cancellation (or an engine's end) are cancelled at once.
public final class VoiceSessionScope: @unchecked Sendable {
    private let lock = NSLock()
    private var open = true
    private var handles: [String: any VoiceSessionHandle] = [:]
    private var ended: Set<String> = []
    public init() {}
    deinit { cancel() }
    public var isOpen: Bool { lock.lock(); defer { lock.unlock() }; return open }
    public var isEmpty: Bool { lock.lock(); defer { lock.unlock() }; return handles.isEmpty }

    @discardableResult public func register(_ handle: any VoiceSessionHandle, id: String) -> Bool {
        lock.lock()
        guard open, !ended.contains(id), handles[id] == nil else {
            lock.unlock(); handle.cancel(); return false
        }
        handles[id] = handle; lock.unlock(); return true
    }
    public func retire(_ id: String) {
        lock.lock(); ended.insert(id); let handle = handles.removeValue(forKey: id); lock.unlock()
        handle?.cancel()
    }
    private func snapshot() -> [any VoiceSessionHandle] {
        lock.lock(); defer { lock.unlock() }; return Array(handles.values)
    }
    public func feed(_ data: Data) { for handle in snapshot() { handle.feed(data) } }
    public func stop() { for handle in snapshot() { handle.stop() } }
    public func cancel() {
        lock.lock(); open = false; let handles = Array(handles.values); self.handles.removeAll(); lock.unlock()
        for handle in handles { handle.cancel() }
    }
}
