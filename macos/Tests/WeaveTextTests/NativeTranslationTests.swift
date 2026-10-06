import Foundation
import Testing
import WeaveCore
@testable import WeaveText

private final class NativeTestPluginRepository: TranslationPluginRepository, @unchecked Sendable {
    private let lock = NSLock()
    private var descriptor: TranslationPluginDescriptor?
    init(installed: Bool) { descriptor = installed ? TranslationPluginDescriptor(version: "1.0.0") : nil }
    func installed() throws -> TranslationPluginDescriptor? { lock.withLock { descriptor } }
    func install(_ source: URL) throws -> TranslationPluginDescriptor {
        lock.withLock { let value = TranslationPluginDescriptor(version: "1.0.0"); descriptor = value; return value }
    }
    func uninstall() throws { lock.withLock { descriptor = nil } }
}

@MainActor @Suite struct NativeTranslationTests {
    private final class Session: NativeTranslationSessionHandling {
        var hasExplicitSource = true
        var prepares = 0
        var translated: [String] = []
        var pausePreparation = false
        var pending: CheckedContinuation<Void, Error>?
        func prepare() async throws {
            prepares += 1
            if pausePreparation { try await withCheckedThrowingContinuation { pending = $0 } }
        }
        func translate(_ text: String) async throws -> TranslationResult {
            translated.append(text)
            return TranslationResult(text: "Hello, world.", detectedLanguage: "zh")
        }
    }

    private func setup(installed: Bool = true, enabled: Bool = true) throws -> (TranslationSettings, UserDefaults, String, TranslationModel) {
        let suite = "weave-native-translation-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        let settings = TranslationSettings(defaults: defaults, systemTranslationAvailable: true)
        settings.provider = .appleSystem
        let plugins = TranslationPlugins(repository: NativeTestPluginRepository(installed: installed), defaults: defaults, operatingSystem: .init(major: 27))
        if installed && enabled { try plugins.setEnabled(true) }
        let model = TranslationModel(settings: settings, plugins: plugins, secureInput: { false })
        return (settings, defaults, suite, model)
    }

    @Test func clickCreatesANativeRequestWithoutOnlineSwitchKeyOrHTTPService() throws {
        let (_, defaults, suite, model) = try setup()
        defer { defaults.removePersistentDomain(forName: suite) }
        model.sourceText = "你好世界"
        #expect(model.nativeJob == nil && model.result == nil)
        model.translate()
        let job = try #require(model.nativeJob)
        #expect(job.input.source == "auto" && job.input.target == "en" && model.isTranslating)
        #expect(!model.settings.onlineEnabled && model.settings.apiKey.isEmpty && model.settings.endpoint.isEmpty)
    }

    @Test func missingOrDisabledOfflinePluginDoesNotCreateANativeJob() throws {
        for installed in [false, true] {
            let (_, defaults, suite, model) = try setup(installed: installed, enabled: false)
            defer { defaults.removePersistentDomain(forName: suite) }
            model.sourceText = "你好世界"; model.translate()
            #expect(model.nativeJob == nil && !model.isTranslating && model.result == nil)
            #expect(model.status.contains(installed ? "尚未启用" : "手动安装"))
            model.prepareLanguagePackages()
            #expect(model.nativeJob == nil)
        }
    }

    @Test func languagePreparationDoesNotCallTranslationOrPretendAModelWasBundled() async throws {
        let (settings, defaults, suite, model) = try setup()
        defer { defaults.removePersistentDomain(forName: suite) }
        model.prepareLanguagePackages()
        #expect(model.nativeJob == nil && model.status.contains("明确选择源语言"))
        settings.sourceLanguage = "zh"
        model.prepareLanguagePackages()
        let job = try #require(model.nativeJob)
        #expect(job.action == .prepareLanguages && job.input.text.isEmpty)
        let session = Session()
        await NativeTranslationRunner.run(job, model: model, session: session)
        #expect(session.prepares == 1 && session.translated.isEmpty && model.result == nil)
        #expect(model.status.contains("Apple 管理") && model.status.contains("不含模型"))
    }

    @Test func disablingOrUninstallingThePluginRevokesAnInFlightNativeRequest() async throws {
        for uninstall in [false, true] {
            let (_, defaults, suite, model) = try setup()
            defer { defaults.removePersistentDomain(forName: suite) }
            model.sourceText = "你好世界"; model.translate()
            let job = try #require(model.nativeJob)
            if uninstall { await model.plugins.uninstall() }
            else { try model.plugins.setEnabled(false) }
            let session = Session()
            await NativeTranslationRunner.run(job, model: model, session: session)
            #expect(session.prepares == 0 && session.translated.isEmpty && model.result == nil && model.nativeJob == nil)
        }
    }

    @Test func autoSessionTranslatesWithoutCallingPrepareWithANilSource() async throws {
        let (_, defaults, suite, model) = try setup()
        defer { defaults.removePersistentDomain(forName: suite) }
        model.sourceText = "你好世界"; model.translate()
        let job = try #require(model.nativeJob)
        let session = Session(); session.hasExplicitSource = false
        await NativeTranslationRunner.run(job, model: model, session: session)
        #expect(session.prepares == 0 && session.translated == ["你好世界"])
        #expect(model.result?.text == "Hello, world." && !model.isTranslating)
    }

    @Test func cancelledPreparationCannotStartTranslationOrPublishLateResults() async throws {
        let (settings, defaults, suite, model) = try setup()
        defer { defaults.removePersistentDomain(forName: suite) }
        settings.sourceLanguage = "zh"
        model.sourceText = "你好世界"; model.translate()
        let job = try #require(model.nativeJob)
        let session = Session(); session.pausePreparation = true
        let task = Task { await NativeTranslationRunner.run(job, model: model, session: session) }
        let deadline = Date().addingTimeInterval(2)
        while session.pending == nil && Date() < deadline { await Task.yield() }
        let continuation = try #require(session.pending)
        model.cancel(); continuation.resume()
        await task.value
        #expect(session.prepares == 1 && session.translated.isEmpty && model.result == nil)
    }

    @Test func languagePackageAuthorizationRevokesTheOriginalDestination() async throws {
        let (_, defaults, suite, model) = try setup()
        defer { defaults.removePersistentDomain(forName: suite) }
        let editor = TranslationWindowTests.Editor()
        model.begin(editor: editor); model.translate()
        let job = try #require(model.nativeJob)
        #expect(model.hasDestination && model.prepareNativeSystemUI(job))
        #expect(!model.hasDestination && model.isCurrent(job))
        model.invalidate(owner: try #require(editor.ownerIdentity))
        await NativeTranslationRunner.run(job, model: model, session: Session())
        #expect(model.result?.text == "Hello, world.")
        #expect(!model.commit(.replaceSelection) && editor.inserted.isEmpty)
    }

    @Test func automaticAndExplicitSameLanguageKeepOriginalWithAnExplanation() throws {
        guard #available(macOS 15.0, *) else { return }
        for source in ["auto", "en"] {
            let (settings, defaults, suite, model) = try setup()
            defer { defaults.removePersistentDomain(forName: suite) }
            settings.sourceLanguage = source
            model.sourceText = "Hello, world."; model.translate()
            let job = try #require(model.nativeJob)
            let coordinator = NativeTranslationCoordinator(model: model, detectLanguage: { _ in "en" })
            #expect(coordinator.configuration == nil)
            coordinator.configure(job)
            #expect(coordinator.configuration == nil && model.nativeJob == nil && !model.isTranslating)
            #expect(model.result?.text == "Hello, world." && model.status.contains("已是目标语言"))
        }
    }

    @Test func defaultAutoConfigurationKeepsNilSourceForAppleDetection() throws {
        guard #available(macOS 15.0, *) else { return }
        let (_, defaults, suite, model) = try setup()
        defer { defaults.removePersistentDomain(forName: suite) }
        var detections = 0
        let coordinator = NativeTranslationCoordinator(model: model, detectLanguage: { _ in detections += 1; return "zh-Hans" })
        coordinator.configure(nil)
        #expect(detections == 0 && coordinator.configuration == nil)
        model.sourceText = "你好世界"; model.translate()
        coordinator.configure(try #require(model.nativeJob))
        let configuration = try #require(coordinator.configuration)
        #expect(detections == 1 && configuration.source == nil)
        #expect(configuration.target?.languageCode?.identifier == "en")
    }
}
