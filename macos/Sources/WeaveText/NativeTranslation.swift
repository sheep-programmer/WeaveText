import AppKit
import Combine
import Foundation
import NaturalLanguage
import SwiftUI
import Translation
import WeaveCore

/// Testable session boundary. Production receives the real Apple session only inside translationTask.
@MainActor protocol NativeTranslationSessionHandling {
    var hasExplicitSource: Bool { get }
    func prepare() async throws
    func translate(_ text: String) async throws -> TranslationResult
}

@available(macOS 15.0, *)
@MainActor private struct AppleTranslationSessionAdapter: NativeTranslationSessionHandling {
    let session: TranslationSession
    var hasExplicitSource: Bool { session.sourceLanguage != nil }
    func prepare() async throws { try await session.prepareTranslation() }
    func translate(_ text: String) async throws -> TranslationResult {
        let response = try await session.translate(text)
        return TranslationResult(text: response.targetText, detectedLanguage: response.sourceLanguage.languageCode?.identifier)
    }
}

/// The caller awaits this runner inside the view's structured translationTask. It never saves a session
/// for use after that task/configuration/view is gone. Stale/cancelled jobs make no further session calls.
@MainActor enum NativeTranslationRunner {
    static func run(_ job: NativeTranslationJob, model: TranslationModel, session: any NativeTranslationSessionHandling) async {
        func current() -> Bool {
            guard !Task.isCancelled, model.isCurrent(job) else {
                model.failNative(job, failure: .cancelled)
                return false
            }
            return true
        }
        guard current() else { return }
        do {
            if job.action == .prepareLanguages {
                guard session.hasExplicitSource else { model.failNative(job, failure: .sourceDetectionFailed); return }
                try await session.prepare()
                guard current() else { return }
                model.finishNativePreparation(job)
                return
            }
            // prepareTranslation() cannot identify a nil source without sample text. Automatic
            // sessions instead let translate(sourceText) detect the language and request any models.
            if session.hasExplicitSource { try await session.prepare() }
            guard current() else { return }
            let result = try await session.translate(job.input.text)
            guard current() else { return }
            model.finishNative(job, result: result)
        } catch {
            model.failNative(job, failure: error is CancellationError || Task.isCancelled ? .cancelled : .nativeFailed)
        }
    }
}

/// macOS 15 APIs only. init(installedSource:target:) and TranslationSession.cancel() require macOS 26.
/// https://developer.apple.com/documentation/translation/translationsession
/// https://developer.apple.com/documentation/swiftui/view/translationtask(_:action:)
@available(macOS 15.0, *)
@MainActor final class NativeTranslationCoordinator: ObservableObject {
    @Published private(set) var configuration: TranslationSession.Configuration?
    private(set) var job: NativeTranslationJob?
    private let model: TranslationModel
    private let detectLanguage: (String) -> String?

    init(model: TranslationModel, detectLanguage: @escaping (String) -> String? = { NLLanguageRecognizer.dominantLanguage(for: $0)?.rawValue }) {
        self.model = model; self.detectLanguage = detectLanguage
    }

    func configure(_ job: NativeTranslationJob?) {
        self.job = job
        guard let job else { configuration = nil; return }
        let automatic = job.input.source.lowercased() == "auto"
        // Detection is only for the same-language shortcut, after the user's button press. Leave
        // source=nil for Apple to perform authoritative automatic detection during translation.
        let detectedSource = automatic ? detectLanguage(job.input.text) : job.input.source
        if job.action == .translate, let source = detectedSource, Self.sameLanguage(source, job.input.target) {
            configuration = nil
            model.finishNativeAlreadyInTargetLanguage(job, sourceLanguage: source)
            return
        }
        let sourceLanguage = automatic ? nil : Locale.Language(identifier: job.input.source)
        let targetLanguage = Locale.Language(identifier: job.input.target)
        if var next = configuration, next.source == sourceLanguage, next.target == targetLanguage {
            next.invalidate() // Repeat translations of the same language pair still create a fresh task.
            configuration = next
        } else {
            configuration = TranslationSession.Configuration(source: sourceLanguage, target: targetLanguage)
        }
    }

    static func sameLanguage(_ source: String, _ target: String) -> Bool {
        let from = Locale.Language(identifier: source), to = Locale.Language(identifier: target)
        guard let fromCode = from.languageCode, let toCode = to.languageCode, fromCode == toCode else { return false }
        if let fromScript = from.script, let toScript = to.script { return fromScript == toScript }
        return true
    }

    func run(_ session: TranslationSession, job: NativeTranslationJob) async {
        guard model.isCurrent(job), !Task.isCancelled else {
            model.failNative(job, failure: .cancelled); return
        }
        let status: LanguageAvailability.Status
        do {
            let availability = LanguageAvailability()
            if let source = session.sourceLanguage { status = await availability.status(from: source, to: session.targetLanguage) }
            else { status = try await availability.status(for: job.input.text, to: session.targetLanguage) }
        } catch { model.failNative(job, failure: .nativeFailed); return }
        guard model.isCurrent(job), !Task.isCancelled else { model.failNative(job, failure: .cancelled); return }
        switch status {
        case .unsupported:
            model.failNative(job, failure: .nativeFailed); return
        case .supported:
            // The system may present a download authorization dialog. Activating this app must
            // never leave a capability to write into the document that previously had focus.
            guard model.prepareNativeSystemUI(job) else { return }
            NSApp.activate(ignoringOtherApps: true)
        case .installed:
            break
        @unknown default:
            model.failNative(job, failure: .nativeFailed); return
        }
        await NativeTranslationRunner.run(job, model: model, session: AppleTranslationSessionAdapter(session: session))
    }
}

@available(macOS 15.0, *)
@MainActor struct NativeTranslationView: View {
    @ObservedObject var model: TranslationModel
    @StateObject private var coordinator: NativeTranslationCoordinator

    init(model: TranslationModel) {
        self.model = model
        _coordinator = StateObject(wrappedValue: NativeTranslationCoordinator(model: model))
    }

    var body: some View {
        // Capture the job belonging to this rendered configuration. A late callback from a previous
        // configuration must not consume a newer job, even when the languages happen to match.
        let job = coordinator.job
        TranslationContentView(model: model)
            .translationTask(coordinator.configuration) { session in
                guard let job else { return }
                await coordinator.run(session, job: job)
            }
            .onChange(of: model.nativeJob) { coordinator.configure($0) }
            .onAppear { coordinator.configure(model.nativeJob) }
            .onDisappear { coordinator.configure(nil); model.cancel() }
    }
}
