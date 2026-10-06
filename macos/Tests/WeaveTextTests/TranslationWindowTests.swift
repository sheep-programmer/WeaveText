import AppKit
import Foundation
import SwiftUI
import Testing
import WeaveCore
@testable import WeaveText

private final class DeferredTranslationService: TranslationServing, @unchecked Sendable {
    private let lock = NSLock()
    private var pending: [CheckedContinuation<TranslationResult, Error>] = []
    var count: Int { lock.withLock { pending.count } }
    func translate(_ request: TranslationRequest, configuration: TranslationConfiguration) async throws -> TranslationResult {
        try await withCheckedThrowingContinuation { continuation in lock.withLock { pending.append(continuation) } }
    }
    func complete(_ index: Int, text: String) {
        let continuation = lock.withLock { pending[index] }
        continuation.resume(returning: TranslationResult(text: text))
    }
}

private struct EmptyTranslationWindowPluginRepository: TranslationPluginRepository {
    func installed() throws -> TranslationPluginDescriptor? { nil }
    func install(_ source: URL) throws -> TranslationPluginDescriptor { throw TranslationPluginFailure.invalidPackage }
    func uninstall() throws {}
}

@MainActor @Suite struct TranslationWindowTests {
    final class Editor: TranslationEditor {
        var ownerIdentity: AnyObject? = NSObject()
        var clientIdentity: AnyObject? = NSObject()
        var isActive = true
        var isSecure = false
        var epoch = 5
        var selectedRange = NSRange(location: 1, length: 2)
        var markedRange = NSRange(location: NSNotFound, length: 0)
        var document = "A你好B"
        var inserted = ""
        func text(in range: NSRange) -> String? { (document as NSString).substring(with: range) }
        func insert(_ text: String, replacementRange: NSRange) -> Bool {
            inserted = text; document = (document as NSString).replacingCharacters(in: replacementRange, with: text)
            return true
        }
    }

    private func settings() throws -> (TranslationSettings, UserDefaults, String) {
        let suite = "weave-translation-window-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        let settings = TranslationSettings(defaults: defaults)
        settings.provider = .libreTranslate
        settings.endpoint = "https://translation.example/translate"
        return (settings, defaults, suite)
    }
    private func plugins(defaults: UserDefaults) -> TranslationPlugins {
        TranslationPlugins(repository: EmptyTranslationWindowPluginRepository(), defaults: defaults, operatingSystem: .init(major: 27))
    }
    private func waitFor(_ condition: () -> Bool) async {
        let deadline = Date().addingTimeInterval(2)
        while !condition() && Date() < deadline { await Task.yield() }
        #expect(condition())
    }

    @Test func openingAndDisabledTranslateDoNotContactTheService() throws {
        let (settings, defaults, suite) = try settings()
        defer { defaults.removePersistentDomain(forName: suite) }
        let service = DeferredTranslationService()
        let model = TranslationModel(settings: settings, plugins: plugins(defaults: defaults), service: service, secureInput: { false })
        model.begin(editor: Editor())
        #expect(model.sourceText == "你好" && model.hasSelection)
        model.translate()
        #expect(service.count == 0 && !model.isTranslating && model.result == nil)
        #expect(model.status.contains("未开启"))
    }

    @Test func officialWebsiteIsOpenedOnlyOnClickAndDoesNotCreateAnEmbeddedResult() throws {
        let suite = "weave-google-window-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let settings = TranslationSettings(defaults: defaults, systemTranslationAvailable: false)
        settings.apiKey = "must-not-leak"
        let service = DeferredTranslationService()
        var opened: [URL] = []
        let model = TranslationModel(settings: settings, plugins: plugins(defaults: defaults), service: service, secureInput: { false }, openURL: { opened.append($0); return true })
        let editor = Editor()
        model.begin(editor: editor)
        #expect(settings.provider == .googleWeb && !settings.onlineEnabled && opened.isEmpty)
        model.translate()
        let url = try #require(opened.first)
        #expect(opened.count == 1 && url.host == "translate.google.com" && !url.absoluteString.contains("must-not-leak"))
        #expect(model.result == nil && service.count == 0 && !model.hasDestination)
        #expect(!model.commit(.replaceSelection) && editor.inserted.isEmpty)
        #expect(model.status.contains("无法写回"))
    }

    @Test func oversizedWebsiteSourceAndSecureInputCannotOpenABrowser() throws {
        let (settings, defaults, suite) = try settings()
        defer { defaults.removePersistentDomain(forName: suite) }
        settings.provider = .googleWeb
        var secure = false
        var opens = 0
        let model = TranslationModel(settings: settings, plugins: plugins(defaults: defaults), secureInput: { secure }, openURL: { _ in opens += 1; return true })
        model.sourceText = String(repeating: "字", count: 2000)
        model.openGoogleWeb()
        #expect(opens == 0 && model.status.contains("5000"))
        model.sourceText = "你好"
        secure = true; model.openGoogleWeb()
        #expect(opens == 0)
    }

    @Test func newSourceAndOutOfOrderRepliesCannotRestoreTheOldTranslation() async throws {
        let (settings, defaults, suite) = try settings(); settings.onlineEnabled = true
        defer { defaults.removePersistentDomain(forName: suite) }
        let service = DeferredTranslationService(); let editor = Editor()
        let model = TranslationModel(settings: settings, plugins: plugins(defaults: defaults), service: service, secureInput: { false })
        model.begin(editor: editor); model.translate()
        await waitFor { service.count == 1 }
        model.sourceText = "新的源文"; model.translate()
        await waitFor { service.count == 2 }
        service.complete(0, text: "stale")
        await Task.yield()
        #expect(model.result == nil && model.isTranslating)
        service.complete(1, text: "New translation")
        await waitFor { !model.isTranslating }
        #expect(model.result?.text == "New translation")
        #expect(model.commit(.replaceSelection) && editor.document == "ANew translationB")
        #expect(!model.commit(.replaceSelection))
    }

    @Test func cancelledClosedAndReconfiguredRequestsIgnoreLateCompletion() async throws {
        for action in 0..<3 {
            let (settings, defaults, suite) = try settings(); settings.onlineEnabled = true
            defer { defaults.removePersistentDomain(forName: suite) }
            let service = DeferredTranslationService()
            let model = TranslationModel(settings: settings, plugins: plugins(defaults: defaults), service: service, secureInput: { false })
            model.begin(editor: Editor()); model.translate()
            await waitFor { service.count == 1 }
            switch action {
            case 0: model.cancel()
            case 1: model.close()
            default: settings.apiKey = "changed"
            }
            service.complete(0, text: "late")
            await Task.yield()
            #expect(model.result == nil && !model.isTranslating)
        }
    }

    @Test func epochChangeBeforeConfirmationRefusesWriteback() async throws {
        let (settings, defaults, suite) = try settings(); settings.onlineEnabled = true
        defer { defaults.removePersistentDomain(forName: suite) }
        let service = DeferredTranslationService(); let editor = Editor()
        let model = TranslationModel(settings: settings, plugins: plugins(defaults: defaults), service: service, secureInput: { false })
        model.begin(editor: editor); model.translate()
        await waitFor { service.count == 1 }
        service.complete(0, text: "Hello")
        await waitFor { model.result != nil }
        editor.epoch += 1
        #expect(!model.commit(.replaceSelection) && editor.inserted.isEmpty)
        #expect(model.result?.text == "Hello" && !model.hasDestination)
    }

    @Test func safeInputPreventsReadingAndLateResultsButPasteAndCopyWorkInNormalInput() async throws {
        let (settings, defaults, suite) = try settings(); settings.onlineEnabled = true
        defer { defaults.removePersistentDomain(forName: suite) }
        var secure = true
        let service = DeferredTranslationService(); let editor = Editor()
        let model = TranslationModel(settings: settings, plugins: plugins(defaults: defaults), service: service, secureInput: { secure })
        model.begin(editor: editor); model.translate()
        #expect(model.sourceText.isEmpty && !model.hasDestination && service.count == 0)
        secure = false; model.begin(editor: editor)
        let pasteboard = NSPasteboard.withUniqueName()
        defer { pasteboard.releaseGlobally() }
        pasteboard.setString("Bonjour", forType: .string)
        model.paste(pasteboard); #expect(model.sourceText == "Bonjour")
        model.translate(); await waitFor { service.count == 1 }
        service.complete(0, text: "Hello"); await waitFor { model.result != nil }
        model.copy(pasteboard); #expect(pasteboard.string(forType: .string) == "Hello")
        model.translate(); await waitFor { service.count == 2 }
        secure = true; service.complete(1, text: "must discard")
        await waitFor { !model.isTranslating }
        #expect(model.result == nil && editor.inserted.isEmpty)
    }

    @Test func panelNeverTakesKeyFocusAndSettingsCanBeHostedSeparately() throws {
        _ = NSApplication.shared
        let (settings, defaults, suite) = try settings()
        defer { defaults.removePersistentDomain(forName: suite) }
        let model = TranslationModel(settings: settings, plugins: plugins(defaults: defaults), secureInput: { false })
        let panel = TranslationWindow.panel(model: model)
        #expect(!panel.canBecomeKey && !panel.canBecomeMain)
        #expect(panel.styleMask.contains(.nonactivatingPanel))
        let view = NSHostingView(rootView: TranslationSettingsView(settings: settings, plugins: model.plugins, prepareLanguages: {}))
        view.frame = NSRect(x: 0, y: 0, width: 600, height: 380)
        view.layoutSubtreeIfNeeded()
        #expect(view.fittingSize.height > 100)
        panel.close()
    }
}
