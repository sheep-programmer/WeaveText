import Foundation
import Testing
@testable import WeaveCore

private final class VoiceTestClock {
    var time: TimeInterval = 0
    func lifecycle() -> VoiceLifecycle { VoiceLifecycle(now: { self.time }) }
}

private final class VoiceTestSession: VoiceSessionHandle, @unchecked Sendable {
    private let lock = NSLock()
    private var calls = (feeds: 0, stops: 0, cancels: 0)
    var counts: (feeds: Int, stops: Int, cancels: Int) {
        lock.lock(); defer { lock.unlock() }; return calls
    }
    func feed(_ data: Data) { lock.lock(); calls.feeds += 1; lock.unlock() }
    func stop() { lock.lock(); calls.stops += 1; lock.unlock() }
    func cancel() { lock.lock(); calls.cancels += 1; lock.unlock() }
}

@Suite struct VoiceLifecycleTests {
    @Test func cancelAuthorizationRejectsPermissionAndPluginCallbacksAndKeepsDraft() throws {
        let clock = VoiceTestClock()
        var voice = clock.lifecycle()
        let started = voice.begin(draft: "保留草稿")
        let token = try #require(started)
        #expect(voice.begin(draft: "重复开始") == nil)
        #expect(voice.stop() == .cancel)
        #expect(voice.state == .idle && voice.draft == "保留草稿")
        #expect(voice.preparePlugins(token: token) == false)
        #expect(voice.didStartRecording(token: token) == false)
        #expect(voice.updateTranscript("迟到权限回调", token: token) == nil)
        #expect(voice.remainingTime == nil)
    }

    @Test func anOverduePermissionReplyCannotRenewTheDeadlineBeforeTheTimerRuns() throws {
        let clock = VoiceTestClock()
        var voice = clock.lifecycle()
        let started = voice.begin(draft: "")
        let token = try #require(started)
        clock.time = 60
        #expect(!voice.accepts(token))
        #expect(voice.preparePlugins(token: token) == false)
        #expect(voice.expire(token: token) == .authorization)
        #expect(voice.state == .idle)
    }

    @Test func pluginPreparationHasItsOwnBoundAndCancelsSessionsArrivingAfterTimeout() throws {
        let clock = VoiceTestClock()
        var voice = clock.lifecycle()
        let started = voice.begin(draft: "原文")
        let token = try #require(started)
        clock.time = 59
        #expect(voice.preparePlugins(token: token) == true)
        #expect(voice.preparePlugins(token: token) == false)
        #expect(voice.remainingTime == 15)
        let scope = VoiceSessionScope(), ready = VoiceTestSession(), late = VoiceTestSession()
        #expect(scope.register(ready, id: "ready"))
        clock.time = 74
        #expect(voice.didStartRecording(token: token) == false)
        #expect(voice.expire(token: token) == .plugins)
        scope.cancel()
        #expect(!scope.register(late, id: "late"))
        #expect(ready.counts.cancels == 1 && late.counts.cancels == 1)
        #expect(voice.draft == "原文" && scope.isEmpty && !scope.isOpen)
    }

    @Test func stoppingWaitsForFinalTextWithoutWritingAndTimeoutFreezesTheDraft() throws {
        let clock = VoiceTestClock()
        var voice = clock.lifecycle()
        let started = voice.begin(draft: "上一段")
        let token = try #require(started)
        #expect(voice.preparePlugins(token: token) && voice.didStartRecording(token: token))
        clock.time = 100 // Listening has no preparation deadline.
        #expect(voice.accepts(token) && voice.remainingTime == nil)
        #expect(voice.updateTranscript("临时", token: token) == "上一段\n临时")
        #expect(voice.stop() == .finishRecording)
        var writes = 0
        #expect(!voice.commit { _ in writes += 1; return true })
        clock.time = 114
        #expect(voice.updateTranscript("终稿", token: token) == "上一段\n终稿")
        #expect(voice.expire(token: token) == nil)
        clock.time = 115
        #expect(voice.expire(token: token) == .finishing)
        #expect(voice.updateTranscript("迟到替换", token: token) == nil)
        #expect(voice.draft == "上一段\n终稿" && writes == 0 && voice.state == .idle)
    }

    @Test func endingTheWaitAllowsImmediateRestartAndOldTimersCannotEndIt() throws {
        let clock = VoiceTestClock()
        var voice = clock.lifecycle()
        let started = voice.begin(draft: "")
        let old = try #require(started)
        #expect(voice.preparePlugins(token: old) && voice.didStartRecording(token: old))
        voice.updateTranscript("已有文字", token: old)
        #expect(voice.stop() == .finishRecording)
        #expect(voice.stop() == .cancel)
        #expect(voice.stop() == .none)
        let restarted = voice.begin(draft: voice.draft)
        let next = try #require(restarted)
        #expect(next != old)
        #expect(voice.preparePlugins(token: next) && voice.didStartRecording(token: next))
        clock.time = 100
        #expect(voice.expire(token: old) == nil)
        #expect(voice.finish(token: old) == false)
        #expect(voice.updateTranscript("旧会话", token: old) == nil)
        #expect(voice.updateTranscript("新会话", token: next) == "已有文字\n新会话")
        #expect(voice.state == .listening)
    }

    @Test(arguments: [0, 1, 2, 3])
    func closeDuringEveryPhaseReleasesHandlesAndInvalidatesAllCallbacks(phase: Int) throws {
        var voice = VoiceTestClock().lifecycle()
        let started = voice.begin(draft: "关闭前的草稿")
        let token = try #require(started)
        if phase >= 1 { #expect(voice.preparePlugins(token: token) == true) }
        if phase >= 2 { #expect(voice.didStartRecording(token: token) == true) }
        if phase >= 3 { #expect(voice.stop() == .finishRecording) }
        let scope = VoiceSessionScope(), session = VoiceTestSession(), late = VoiceTestSession()
        #expect(scope.register(session, id: "one"))
        voice.close(); scope.cancel(); scope.cancel()
        #expect(!scope.register(late, id: "two"))
        #expect(session.counts.cancels == 1 && late.counts.cancels == 1)
        #expect(voice.state == .idle && voice.draft == "关闭前的草稿")
        #expect(!voice.accepts(token) && !voice.finish(token: token))
        #expect(voice.updateTranscript("已关闭", token: token) == nil)
    }

    @Test func completionReleasesPluginsAndAnEndedEngineCannotBeAdoptedAgain() throws {
        var voice = VoiceTestClock().lifecycle()
        let started = voice.begin(draft: "")
        let token = try #require(started)
        #expect(voice.preparePlugins(token: token) && voice.didStartRecording(token: token))
        let scope = VoiceSessionScope(), one = VoiceTestSession(), two = VoiceTestSession()
        #expect(scope.register(one, id: "one") && scope.register(two, id: "two"))
        voice.updateTranscript("已完成", token: token)
        scope.retire("one")
        scope.feed(Data([1, 2]))
        #expect(one.counts.cancels == 1 && one.counts.feeds == 0 && two.counts.feeds == 1)
        #expect(voice.finish(token: token) == true)
        scope.cancel()
        #expect(one.counts.cancels == 1 && two.counts.cancels == 1)
        #expect(voice.updateTranscript("晚到 replace", token: token) == nil)
        #expect(voice.draft == "已完成")
    }

    @Test func editingDuringRecordingOrFinishingProtectsTheDraftWhileResultsContinue() throws {
        var voice = VoiceTestClock().lifecycle()
        let started = voice.begin(draft: "")
        let token = try #require(started)
        #expect(voice.preparePlugins(token: token) && voice.didStartRecording(token: token))
        voice.updateTranscript("初稿", token: token)
        voice.editDraft("手动修正")
        #expect(voice.updateTranscript("自动更新", token: token) == nil)
        #expect(voice.draft == "手动修正" && voice.accepts(token))
        #expect(voice.stop() == .finishRecording)
        voice.editDraft("最终手动修正")
        #expect(voice.updateTranscript("终稿", token: token) == nil)
        #expect(voice.finish(token: token) == true)
        #expect(voice.draft == "最终手动修正")
        #expect(voice.selectTranscript("明确选择另一个引擎") == "明确选择另一个引擎")
    }

    @Test func confirmationPreservesTextWhenTheEditorIsUnavailableAndWritesExactlyOnce() throws {
        var voice = VoiceTestClock().lifecycle()
        let started = voice.begin(draft: "")
        let token = try #require(started)
        #expect(voice.preparePlugins(token: token) && voice.didStartRecording(token: token))
        voice.updateTranscript("确认文字", token: token)
        #expect(voice.finish(token: token) == true)
        var attempted: [String] = []
        #expect(!voice.commit { attempted.append($0); return false })
        #expect(voice.draft == "确认文字")
        #expect(voice.commit { attempted.append($0); return true })
        #expect(!voice.commit { attempted.append($0); return true })
        #expect(attempted == ["确认文字", "确认文字"] && voice.draft.isEmpty)
        #expect(voice.updateTranscript("提交后迟到", token: token) == nil)
        let restarted = voice.begin(draft: voice.draft)
        let next = try #require(restarted)
        #expect(voice.updateTranscript("第二段", token: next) == "第二段")
    }

    @Test(arguments: [0, 1, 2, 3])
    func confirmationCannotWriteWhilePermissionPreparationRecordingOrFinishingIsActive(phase: Int) throws {
        var voice = VoiceTestClock().lifecycle()
        let started = voice.begin(draft: "待确认")
        let token = try #require(started)
        if phase >= 1 { #expect(voice.preparePlugins(token: token) == true) }
        if phase >= 2 { #expect(voice.didStartRecording(token: token) == true) }
        if phase >= 3 { #expect(voice.stop() == .finishRecording) }
        var writes = 0
        #expect(!voice.commit { _ in writes += 1; return true })
        #expect(writes == 0 && voice.draft == "待确认")
    }

    @Test func clearAndWhitespaceDraftsNeverWriteOrResurrectAnOldResult() throws {
        var voice = VoiceTestClock().lifecycle()
        let started = voice.begin(draft: "旧草稿")
        let token = try #require(started)
        voice.close(); voice.clearDraft()
        #expect(voice.draft.isEmpty && voice.updateTranscript("旧结果", token: token) == nil)
        voice.editDraft(" \n\t")
        var called = false
        #expect(!voice.commit { _ in called = true; return true })
        #expect(!called)
    }
}

@Suite struct VoiceSessionScopeTests {
    @Test func droppingTheScopeReleasesAnAdoptedHandle() {
        let session = VoiceTestSession()
        var scope: VoiceSessionScope? = VoiceSessionScope()
        #expect(scope?.register(session, id: "one") == true)
        scope = nil
        #expect(session.counts.cancels == 1)
    }

    @Test func endBeforeThePreparationWorkerReturnsCancelsItsHandle() {
        let scope = VoiceSessionScope(), session = VoiceTestSession()
        scope.retire("early-end")
        #expect(!scope.register(session, id: "early-end"))
        scope.cancel()
        #expect(session.counts.cancels == 1)
    }

    @Test func stoppingKeepsHandlesForFinalCallbacksAndCancellationStopsFurtherFeeds() {
        let scope = VoiceSessionScope(), session = VoiceTestSession()
        #expect(scope.register(session, id: "one"))
        scope.stop()
        #expect(session.counts.stops == 1 && session.counts.cancels == 0 && scope.isOpen)
        scope.cancel(); scope.feed(Data([1])); scope.stop()
        #expect(session.counts.cancels == 1 && session.counts.feeds == 0 && session.counts.stops == 1)
    }

    @Test func concurrentPreparationAndCloseCancelEveryHandleExactlyOnce() {
        let scope = VoiceSessionScope()
        let sessions = (0..<64).map { _ in VoiceTestSession() }
        DispatchQueue.concurrentPerform(iterations: sessions.count + 1) { index in
            if index == sessions.count { scope.cancel() }
            else { scope.register(sessions[index], id: String(index)) }
        }
        scope.cancel()
        #expect(sessions.allSatisfy { $0.counts.cancels == 1 })
        #expect(scope.isEmpty && !scope.isOpen)
    }
}
