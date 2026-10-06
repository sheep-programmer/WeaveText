import Foundation
import Testing
@testable import WeaveCore

/// One URLProtocol fixture per unique host, so tests never need a real translation account/network.
/// Only tests return fixture translations; production always calls the configured LibreTranslate endpoint.
private final class TranslationHTTPFixture: @unchecked Sendable {
    let url: URL
    let provider: TranslationProvider
    let input: TranslationRequest
    let lock = NSLock()
    var status = 200
    var chunks = [Data(#"{"translatedText":"你好 & 世界","detectedLanguage":{"language":"en","confidence":90}}"#.utf8)]
    var declaredLength: Int?
    var stalled = false
    private var seenRequest: URLRequest?
    private var stopped = false
    var request: URLRequest? { lock.withLock { seenRequest } }
    var wasStopped: Bool { lock.withLock { stopped } }
    func receive(_ request: URLRequest) { lock.withLock { seenRequest = request } }
    func stop() { lock.withLock { stopped = true } }
    var configuration: TranslationConfiguration { TranslationConfiguration(enabled: true, provider: provider, endpoint: url.absoluteString, apiKey: "test-key") }
    init() {
        provider = .libreTranslate
        input = TranslationRequest(text: "fixture-\(UUID().uuidString)", source: "en", target: "zh")
        url = URL(string: "https://\(UUID().uuidString.lowercased()).translation.test/translate")!
        TranslationURLProtocol.register(self)
    }
    deinit { TranslationURLProtocol.unregister(url) }
    func service() -> HTTPTranslationService {
        let session = URLSessionConfiguration.ephemeral
        session.protocolClasses = [TranslationURLProtocol.self]
        return HTTPTranslationService(sessionConfiguration: session)
    }
}

private final class TranslationURLProtocol: URLProtocol, @unchecked Sendable {
    private static let lock = NSLock()
    private static var fixtures: [URL: TranslationHTTPFixture] = [:]
    static func register(_ fixture: TranslationHTTPFixture) { lock.withLock { fixtures[fixture.url] = fixture } }
    static func unregister(_ url: URL) { lock.withLock { _ = fixtures.removeValue(forKey: url) } }
    override class func canInit(with request: URLRequest) -> Bool {
        request.url?.host?.hasSuffix(".translation.test") == true
    }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        guard let url = request.url, let fixture = Self.lock.withLock({ Self.fixtures[url] }) else { return }
        fixture.receive(request)
        if fixture.stalled { return }
        var headers = ["Content-Type": "application/json"]
        if let length = fixture.declaredLength { headers["Content-Length"] = String(length) }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: url, statusCode: fixture.status, httpVersion: "HTTP/1.1", headerFields: headers)!, cacheStoragePolicy: .notAllowed)
        for chunk in fixture.chunks { client?.urlProtocol(self, didLoad: chunk) }
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {
        if let url = request.url { Self.lock.withLock { Self.fixtures[url]?.stop() } }
    }
}

@Suite struct TranslationHTTPTests {
    @Test func documentedProtocolPreservesExactTextAndOptionalKey() throws {
        let input = TranslationRequest(text: " <tag> 你好 & +\n", source: "auto", target: "en")
        let configuration = TranslationConfiguration(enabled: true, provider: .libreTranslate, endpoint: "http://localhost:5000/prefix/translate", apiKey: "secret")
        let request = try LibreTranslateService.makeRequest(input, configuration: configuration)
        let requestBody = try #require(request.httpBody)
        let parsedRequest = try JSONSerialization.jsonObject(with: requestBody)
        let json = try #require(parsedRequest as? [String: String])
        #expect(request.httpMethod == "POST" && request.value(forHTTPHeaderField: "Content-Type") == "application/json")
        #expect(json == ["q": input.text, "source": "auto", "target": "en", "format": "text", "api_key": "secret"])
        let noKey = try LibreTranslateService.makeRequest(input, configuration: TranslationConfiguration(enabled: true, provider: .libreTranslate, endpoint: configuration.endpoint))
        let noKeyBody = try #require(noKey.httpBody)
        let parsedNoKey = try JSONSerialization.jsonObject(with: noKeyBody)
        let body = try #require(parsedNoKey as? [String: String])
        #expect(body["api_key"] == nil)
    }

    @Test func disabledAndInvalidInputsCannotSendARequest() async throws {
        let fixture = TranslationHTTPFixture()
        defer { TranslationURLProtocol.unregister(fixture.url) }
        var configuration = fixture.configuration; configuration.enabled = false
        await #expect(throws: TranslationFailure.disabled) {
            try await fixture.service().translate(TranslationRequest(text: "hello"), configuration: configuration)
        }
        #expect(fixture.request == nil)
        for endpoint in ["", "file:///translate", "https://user:key@example.com/translate", "https://example.com/translate?key=secret", "https://example.com", "https://example.com/translate#fragment"] {
            #expect(throws: TranslationFailure.invalidEndpoint) {
                try LibreTranslateService.makeRequest(TranslationRequest(text: "hello"), configuration: TranslationConfiguration(enabled: true, provider: .libreTranslate, endpoint: endpoint))
            }
        }
        #expect(throws: TranslationFailure.inputTooLarge) {
            try LibreTranslateService.makeRequest(TranslationRequest(text: String(repeating: "字", count: 12_000)), configuration: fixture.configuration)
        }
        #expect(throws: TranslationFailure.invalidLanguage) {
            try LibreTranslateService.makeRequest(TranslationRequest(text: "hello", target: "auto"), configuration: fixture.configuration)
        }
    }

    @Test func realHTTPImplementationParsesTheDocumentedResponse() async throws {
        let fixture = TranslationHTTPFixture()
        defer { TranslationURLProtocol.unregister(fixture.url) }
        let result = try await fixture.service().translate(TranslationRequest(text: "Hello & world", source: "en", target: "zh"), configuration: fixture.configuration)
        #expect(result == TranslationResult(text: "你好 & 世界", detectedLanguage: "en"))
        let request = try #require(fixture.request)
        #expect(request.httpMethod == "POST" && request.url == fixture.url)
    }

    @Test func rejectsStatusMalformedAndOversizedResponsesWithoutEchoingServerErrors() async throws {
        for scenario in 0..<4 {
            let fixture = TranslationHTTPFixture()
            defer { TranslationURLProtocol.unregister(fixture.url) }
            let failure: TranslationFailure
            switch scenario {
            case 0: fixture.status = 403; fixture.chunks = [Data(#"{"error":"secret-api-key"}"#.utf8)]; failure = .http(403)
            case 1: fixture.chunks = [Data(#"{"translation":"not-LibreTranslate"}"#.utf8)]; failure = .invalidResponse
            case 2: fixture.declaredLength = LibreTranslateService.maxResponseBytes + 1; failure = .responseTooLarge
            default:
                fixture.chunks = [Data(repeating: 32, count: LibreTranslateService.maxResponseBytes), Data([32])]
                failure = .responseTooLarge
            }
            await #expect(throws: failure) {
                try await fixture.service().translate(TranslationRequest(text: "hello"), configuration: fixture.configuration)
            }
            #expect(!failure.localizedDescription.contains("secret-api-key"))
        }
    }

    @Test func cancellationAndDeadlineStopAnUnfinishedHTTPCall() async throws {
        let fixture = TranslationHTTPFixture(); fixture.stalled = true
        defer { TranslationURLProtocol.unregister(fixture.url) }
        let task = Task { try await fixture.service().translate(fixture.input, configuration: fixture.configuration) }
        let startedDeadline = Date().addingTimeInterval(2)
        while fixture.request == nil && Date() < startedDeadline { await Task.yield() }
        #expect(fixture.request != nil)
        task.cancel()
        await #expect(throws: TranslationFailure.cancelled) { try await task.value }
        let timeoutFixture = TranslationHTTPFixture(); timeoutFixture.stalled = true
        defer { TranslationURLProtocol.unregister(timeoutFixture.url) }
        var configuration = timeoutFixture.configuration; configuration.timeoutSeconds = 0.5
        let started = Date()
        await #expect(throws: TranslationFailure.timedOut) {
            try await timeoutFixture.service().translate(timeoutFixture.input, configuration: configuration)
        }
        #expect(Date().timeIntervalSince(started) < 2)
    }
}

@Suite struct GoogleTranslationWebTests {
    @Test func officialWebsiteURLPreservesTextAndCannotCarryServiceCredentials() throws {
        let input = TranslationRequest(text: "原文 + & = # ? / %\nHello 👋", source: "auto", target: "zh")
        let url = try GoogleTranslationWeb.url(for: input)
        let components = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false))
        let items = try #require(components.queryItems)
        #expect(components.scheme == "https" && components.host == "translate.google.com" && components.path == "/")
        #expect(items.first(where: { $0.name == "text" })?.value == input.text)
        #expect(items.first(where: { $0.name == "sl" })?.value == "auto")
        #expect(items.first(where: { $0.name == "tl" })?.value == "zh-CN")
        #expect(items.first(where: { $0.name == "op" })?.value == "translate")
        #expect(items.count == 4 && !items.contains(where: { ["api_key", "key", "endpoint"].contains($0.name) }))
        #expect(components.user == nil && components.password == nil && components.fragment == nil)
    }

    @Test func validatesUTF8ByteCountAndLanguageBeforeOpeningThePage() throws {
        let boundary = String(repeating: "字", count: 1666) + "ab"
        #expect(boundary.utf8.count == GoogleTranslationWeb.maxInputBytes)
        _ = try GoogleTranslationWeb.url(for: TranslationRequest(text: boundary))
        #expect(throws: TranslationFailure.webInputTooLarge) {
            try GoogleTranslationWeb.url(for: TranslationRequest(text: boundary + "a"))
        }
        #expect(throws: TranslationFailure.invalidLanguage) {
            try GoogleTranslationWeb.url(for: TranslationRequest(text: "hello", target: "auto"))
        }
        #expect(throws: TranslationFailure.invalidLanguage) {
            try GoogleTranslationWeb.url(for: TranslationRequest(text: "hello", source: "en&key=secret"))
        }
    }
}

@MainActor @Suite struct TranslationSettingsTests {
    private func isolatedDefaults() throws -> (UserDefaults, String) {
        let suite = "weave-translation-tests-\(UUID().uuidString)"
        return (try #require(UserDefaults(suiteName: suite)), suite)
    }

    @Test func newUsersChooseOfficialWebsiteWithoutInstallingAnOfflinePlugin() throws {
        for systemAvailable in [true, false] {
            let (defaults, suite) = try isolatedDefaults()
            defer { defaults.removePersistentDomain(forName: suite) }
            let settings = TranslationSettings(defaults: defaults, systemTranslationAvailable: systemAvailable)
            #expect(settings.provider == .googleWeb)
            #expect(!settings.onlineEnabled && settings.endpoint.isEmpty && settings.apiKey.isEmpty)
            #expect(settings.sourceLanguage == "auto" && settings.targetLanguage == "en")
            #expect(throws: TranslationFailure.wrongService) {
                try HTTPTranslationService.makeRequest(TranslationRequest(text: "hello"), configuration: settings.configuration)
            }
        }
    }

    @Test func explicitCustomServiceSettingsSurviveAndNativeWebIgnoreTheOnlineSwitch() throws {
        let (defaults, suite) = try isolatedDefaults()
        defer { defaults.removePersistentDomain(forName: suite) }
        let settings = TranslationSettings(defaults: defaults, systemTranslationAvailable: true)
        settings.provider = .libreTranslate
        settings.onlineEnabled = true; settings.endpoint = "http://localhost:5000/translate"
        settings.apiKey = "self-host-key"; settings.timeoutSeconds = 90
        settings.sourceLanguage = "ja"; settings.targetLanguage = "zh"
        let restored = TranslationSettings(defaults: defaults, systemTranslationAvailable: true)
        #expect(restored.provider == .libreTranslate && restored.onlineEnabled)
        #expect(restored.endpoint == settings.endpoint && restored.apiKey == settings.apiKey)
        #expect(restored.timeoutSeconds == 30 && restored.sourceLanguage == "ja" && restored.targetLanguage == "zh")
        #expect(defaults.object(forKey: "onlineEnabled") == nil)
        #expect(TranslationConfiguration.boundedTimeout(.nan) == 15)
    }

    @Test func retiredPublicPresetClearsItsURLKeyAndPermissionWithoutContactingAService() throws {
        for systemAvailable in [true, false] {
            let (defaults, suite) = try isolatedDefaults()
            defer { defaults.removePersistentDomain(forName: suite) }
            defaults.set("myMemory", forKey: TranslationSettings.Keys.provider)
            defaults.set(true, forKey: TranslationSettings.Keys.enabled)
            defaults.set("https://api.mymemory.translated.net/get", forKey: TranslationSettings.Keys.endpoint)
            defaults.set("obsolete-key", forKey: TranslationSettings.Keys.apiKey)
            defaults.set("zh", forKey: TranslationSettings.Keys.source)
            let settings = TranslationSettings(defaults: defaults, systemTranslationAvailable: systemAvailable)
            #expect(settings.provider == .googleWeb)
            #expect(!settings.onlineEnabled && settings.endpoint.isEmpty && settings.apiKey.isEmpty && settings.sourceLanguage == "auto")
            #expect(defaults.object(forKey: TranslationSettings.Keys.endpoint) == nil)
            #expect(defaults.object(forKey: TranslationSettings.Keys.apiKey) == nil)
            settings.provider = .libreTranslate; settings.endpoint = "http://localhost:5000/translate"; settings.onlineEnabled = true
            let request = try HTTPTranslationService.makeRequest(TranslationRequest(text: "hello"), configuration: settings.configuration)
            let body = try #require(request.httpBody)
            let parsed = try JSONSerialization.jsonObject(with: body)
            let json = try #require(parsed as? [String: String])
            #expect(json["api_key"] == nil && request.url?.host == "localhost")
        }
    }

    @Test func legacyNoProviderPresetIsDisabledButConfiguredLibreTranslateIsPreserved() throws {
        for endpoint in ["", "  ", "http://localhost:5000/translate"] {
            let (defaults, suite) = try isolatedDefaults()
            defer { defaults.removePersistentDomain(forName: suite) }
            defaults.set(true, forKey: TranslationSettings.Keys.enabled)
            defaults.set(endpoint, forKey: TranslationSettings.Keys.endpoint)
            defaults.set("legacy-key", forKey: TranslationSettings.Keys.apiKey)
            let settings = TranslationSettings(defaults: defaults, systemTranslationAvailable: true)
            if endpoint.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                #expect(settings.provider == .googleWeb && !settings.onlineEnabled && settings.apiKey.isEmpty)
            } else {
                #expect(settings.provider == .libreTranslate && settings.onlineEnabled && settings.endpoint == endpoint && settings.apiKey == "legacy-key")
            }
        }
    }
}

@MainActor @Suite struct TranslationWritebackTests {
    final class Editor: TranslationEditor {
        var ownerIdentity: AnyObject? = NSObject()
        var clientIdentity: AnyObject? = NSObject()
        var isActive = true
        var isSecure = false
        var epoch = 7
        var selectedRange = NSRange(location: 2, length: 2)
        var markedRange = NSRange(location: NSNotFound, length: 0)
        var document = "AB你好CD"
        var insertions: [(String, NSRange)] = []
        func text(in range: NSRange) -> String? {
            let text = document as NSString
            guard range.location >= 0, range.location <= text.length, range.length <= text.length - range.location else { return nil }
            return text.substring(with: range)
        }
        func insert(_ text: String, replacementRange: NSRange) -> Bool {
            insertions.append((text, replacementRange))
            document = (document as NSString).replacingCharacters(in: replacementRange, with: text)
            return true
        }
    }

    @Test func replacementInsertionAndCaretUseExplicitUTF16RangesOnlyOnce() throws {
        let editor = Editor(); editor.document = "😀你好CD"
        let replace = try #require(TranslationWriteback(editor: editor))
        #expect(replace.selectedText == "你好")
        #expect(replace.commit("Hello", mode: .replaceSelection))
        #expect(editor.document == "😀HelloCD" && editor.insertions[0].1 == NSRange(location: 2, length: 2))
        #expect(!replace.commit("twice", mode: .replaceSelection))
        let another = Editor(); let insert = try #require(TranslationWriteback(editor: another))
        #expect(insert.commit("Hello", mode: .insertAfterSelection))
        #expect(another.document == "AB你好HelloCD" && another.insertions[0].1 == NSRange(location: 4, length: 0))
        let caretEditor = Editor(); caretEditor.selectedRange = NSRange(location: 2, length: 0)
        let caret = try #require(TranslationWriteback(editor: caretEditor))
        #expect(caret.selectedText.isEmpty && !caret.commit("Hello", mode: .replaceSelection))
        #expect(caret.commit("Hello", mode: .insertAfterSelection))
        #expect(caretEditor.document == "ABHello你好CD")
    }

    @Test func changedOwnerClientEpochRangeTextContextOrSecureInputRevokesTheTarget() throws {
        for change in 0..<9 {
            let editor = Editor(); let target = try #require(TranslationWriteback(editor: editor))
            switch change {
            case 0: editor.ownerIdentity = NSObject()
            case 1: editor.clientIdentity = NSObject()
            case 2: editor.epoch += 1 // Same proxy/range/text but a different input session.
            case 3: editor.selectedRange = NSRange(location: 4, length: 0)
            case 4: editor.document = "AB世界CD"
            case 5: editor.document = "XY你好CD"
            case 6: editor.isSecure = true
            case 7: editor.document = "AB你好XY"
            default: editor.markedRange = NSRange(location: 2, length: 1)
            }
            #expect(!target.commit("Hello", mode: .replaceSelection))
            #expect(editor.insertions.isEmpty)
        }
    }

    @Test func movingAwayAndBackOrExplicitInvalidationCannotRestorePermission() throws {
        let editor = Editor(); let target = try #require(TranslationWriteback(editor: editor))
        editor.isActive = false; #expect(!target.isValid)
        editor.isActive = true; #expect(!target.commit("Hello", mode: .replaceSelection))
        let next = try #require(TranslationWriteback(editor: editor))
        next.invalidate(); #expect(!next.commit("Hello", mode: .replaceSelection))
        editor.isSecure = true; #expect(TranslationWriteback(editor: editor) == nil)
    }
}
