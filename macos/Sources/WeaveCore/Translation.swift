import Combine
import Foundation

public enum TranslationProvider: String, CaseIterable, Sendable {
    case appleSystem, googleWeb, libreTranslate
    public var title: String {
        switch self {
        case .appleSystem: return "Apple 系统翻译（设备端）"
        case .googleWeb: return "Google 官方翻译网页"
        case .libreTranslate: return "LibreTranslate（高级自定义服务）"
        }
    }
    public static var systemTranslationAvailable: Bool {
        if #available(macOS 15.0, *) { return true }
        return false
    }
    public static func defaultProvider(systemAvailable: Bool) -> Self { .googleWeb }
}

/// Separate from input preferences; opening a tool or editing settings never contacts a service.
@MainActor public final class TranslationSettings: ObservableObject {
    public static let shared = TranslationSettings()
    public enum Keys {
        public static let enabled = "weave.translation.onlineEnabled"
        public static let provider = "weave.translation.provider"
        public static let endpoint = "weave.translation.endpoint"
        public static let apiKey = "weave.translation.apiKey"
        public static let timeout = "weave.translation.timeoutSeconds"
        public static let source = "weave.translation.sourceLanguage"
        public static let target = "weave.translation.targetLanguage"
    }
    private let defaults: UserDefaults
    public let systemTranslationAvailable: Bool
    @Published public var onlineEnabled: Bool { didSet { defaults.set(onlineEnabled, forKey: Keys.enabled) } }
    @Published public var provider: TranslationProvider { didSet { defaults.set(provider.rawValue, forKey: Keys.provider) } }
    @Published public var endpoint: String { didSet { defaults.set(endpoint, forKey: Keys.endpoint) } }
    @Published public var apiKey: String { didSet { defaults.set(apiKey, forKey: Keys.apiKey) } }
    @Published public var timeoutSeconds: Double { didSet { defaults.set(TranslationConfiguration.boundedTimeout(timeoutSeconds), forKey: Keys.timeout) } }
    @Published public var sourceLanguage: String { didSet { defaults.set(sourceLanguage, forKey: Keys.source) } }
    @Published public var targetLanguage: String { didSet { defaults.set(targetLanguage, forKey: Keys.target) } }

    public init(defaults: UserDefaults = .standard, systemTranslationAvailable: Bool = TranslationProvider.systemTranslationAvailable) {
        self.defaults = defaults
        self.systemTranslationAvailable = systemTranslationAvailable
        let savedProvider = defaults.string(forKey: Keys.provider)
        let savedEndpoint = defaults.string(forKey: Keys.endpoint) ?? ""
        let enabled = defaults.bool(forKey: Keys.enabled)
        let trimmedEndpoint = savedEndpoint.trimmingCharacters(in: .whitespacesAndNewlines)
        let oldHost = URLComponents(string: trimmedEndpoint)?.host?.lowercased() ?? ""
        let savedChoice = savedProvider.flatMap(TranslationProvider.init(rawValue:))
        let defaultChoice = TranslationProvider.defaultProvider(systemAvailable: systemTranslationAvailable)
        // Retired public-service credentials must never be reused by a new provider. Explicit
        // LibreTranslate settings, including older configurations without a provider key, survive.
        let legacyProvider = savedProvider != nil && savedChoice == nil
        let emptyLegacyPreset = savedProvider == nil && trimmedEndpoint.isEmpty && enabled
        let legacyPublicURL = oldHost == "mymemory.translated.net" || oldHost.hasSuffix(".mymemory.translated.net")
        let resetPreset = legacyProvider || emptyLegacyPreset || legacyPublicURL
        let choice: TranslationProvider
        if resetPreset { choice = defaultChoice }
        else if let savedChoice { choice = savedChoice == .appleSystem && !systemTranslationAvailable ? .googleWeb : savedChoice }
        else { choice = trimmedEndpoint.isEmpty ? defaultChoice : .libreTranslate }
        provider = choice
        onlineEnabled = resetPreset ? false : enabled
        endpoint = resetPreset ? "" : savedEndpoint
        apiKey = resetPreset ? "" : (defaults.string(forKey: Keys.apiKey) ?? "")
        timeoutSeconds = TranslationConfiguration.boundedTimeout(defaults.object(forKey: Keys.timeout) as? Double ?? 15)
        sourceLanguage = resetPreset ? "auto" : (defaults.string(forKey: Keys.source) ?? "auto")
        targetLanguage = defaults.string(forKey: Keys.target) ?? "en"
        // These writes apply only to the injected defaults; tests use isolated suites. Removing the
        // old endpoint/key ensures neither can be inherited when the user later adds a local service.
        if resetPreset {
            defaults.set(false, forKey: Keys.enabled)
            defaults.removeObject(forKey: Keys.endpoint)
            defaults.removeObject(forKey: Keys.apiKey)
            defaults.set("auto", forKey: Keys.source)
        }
        if savedProvider != choice.rawValue {
            defaults.set(choice.rawValue, forKey: Keys.provider)
        }
    }

    public var configuration: TranslationConfiguration {
        TranslationConfiguration(enabled: onlineEnabled, provider: provider, endpoint: endpoint, apiKey: apiKey, timeoutSeconds: timeoutSeconds)
    }
}

public struct TranslationConfiguration: Sendable, Equatable {
    public var enabled: Bool
    public var provider: TranslationProvider
    /// Complete /translate URL, including any self-hosted path prefix.
    public var endpoint: String
    public var apiKey: String
    public var timeoutSeconds: Double
    public init(enabled: Bool = false, provider: TranslationProvider = TranslationProvider.defaultProvider(systemAvailable: TranslationProvider.systemTranslationAvailable), endpoint: String = "", apiKey: String = "", timeoutSeconds: Double = 15) {
        self.enabled = enabled; self.provider = provider; self.endpoint = endpoint; self.apiKey = apiKey
        self.timeoutSeconds = Self.boundedTimeout(timeoutSeconds)
    }
    public static func boundedTimeout(_ value: Double) -> Double {
        value.isFinite ? min(30, max(0.5, value)) : 15
    }
}

public struct TranslationRequest: Sendable, Equatable {
    public var text: String
    public var source: String
    public var target: String
    public init(text: String, source: String = "auto", target: String = "en") {
        self.text = text; self.source = source; self.target = target
    }
    /// Shared validation is local; it never constructs a request to an online service.
    public func validate(maxBytes: Int = LibreTranslateService.maxInputBytes) throws {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw TranslationFailure.emptyInput }
        guard text.utf8.count <= maxBytes else { throw TranslationFailure.inputTooLarge }
        try validateLanguages()
    }
    public func validateLanguages() throws {
        guard validTranslationLanguage(source), validTranslationLanguage(target), target.lowercased() != "auto" else {
            throw TranslationFailure.invalidLanguage
        }
    }
}

/// Only builds the official website URL. Opening it requires a separate, explicit UI action.
/// This is not a Google API client and cannot retrieve a translated result for the app.
public enum GoogleTranslationWeb {
    public static let maxInputBytes = 5_000
    public static func url(for input: TranslationRequest) throws -> URL {
        guard input.text.utf8.count <= maxInputBytes else { throw TranslationFailure.webInputTooLarge }
        try input.validate(maxBytes: maxInputBytes)
        func language(_ code: String) -> String {
            if code.lowercased() == "auto" { return "auto" }
            return code.lowercased() == "zh" ? "zh-CN" : code
        }
        var url = URLComponents(string: "https://translate.google.com/")!
        url.queryItems = [URLQueryItem(name: "sl", value: language(input.source)),
                          URLQueryItem(name: "tl", value: language(input.target)),
                          URLQueryItem(name: "text", value: input.text), URLQueryItem(name: "op", value: "translate")]
        url.percentEncodedQuery = url.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
        guard let result = url.url else { throw TranslationFailure.webOpenFailed }
        return result
    }
}

public struct TranslationResult: Sendable, Equatable {
    public let text: String
    public let detectedLanguage: String?
    public init(text: String, detectedLanguage: String? = nil) {
        self.text = text; self.detectedLanguage = detectedLanguage
    }
}

public enum TranslationFailure: Error, LocalizedError, Equatable {
    case disabled, invalidEndpoint, invalidLanguage, emptyInput, inputTooLarge
    case timedOut, cancelled, network, invalidResponse, responseTooLarge, redirect, http(Int)
    case nativeUnavailable, nativeFailed, sourceDetectionFailed, webInputTooLarge, webOpenFailed, wrongService
    case nativePluginMissing, nativePluginDisabled
    public var errorDescription: String? {
        switch self {
        case .disabled: return "在线翻译未开启，请先在翻译设置中开启。"
        case .invalidEndpoint: return "请配置完整的 HTTP/HTTPS 翻译地址（以 /translate 结尾）。"
        case .invalidLanguage: return "请填写有效的语言代码；目标语言不能是 auto。"
        case .emptyInput: return "请先输入或粘贴要翻译的文字。"
        case .inputTooLarge: return "源文过长，请分段翻译（最多 32 KB）。"
        case .timedOut: return "翻译请求超时，请重试。"
        case .cancelled: return "翻译已取消。"
        case .network: return "无法连接翻译服务，请检查地址和网络。"
        case .invalidResponse: return "翻译服务未返回有效译文。"
        case .responseTooLarge: return "翻译服务响应过大，已停止接收。"
        case .redirect: return "翻译地址发生重定向，请配置最终的 /translate 地址。"
        case .http(let status): return "翻译服务返回 HTTP \(status)，请检查服务配置。"
        case .nativeUnavailable: return "Apple 系统翻译需要 macOS 15 或更新版本，可选择 Google 官方网页。"
        case .nativeFailed: return "系统翻译未完成，请检查语言包或选择 Google 官方网页。"
        case .sourceDetectionFailed: return "无法检测源语言，请手动选择源语言后重试。"
        case .webInputTooLarge: return "Google 网页源文最多 5000 UTF-8 字节，请分段打开。"
        case .webOpenFailed: return "无法打开 Google 官方网页，请检查默认浏览器。"
        case .wrongService: return "此通道应由系统翻译或官方网页处理，不会调用自定义 HTTP 服务。"
        case .nativePluginMissing: return "请在翻译设置中手动安装 Apple 离线翻译适配插件，再启用它。"
        case .nativePluginDisabled: return "Apple 离线翻译适配插件尚未启用，请在翻译设置中开启。"
        }
    }
}

public protocol TranslationServing: Sendable {
    func translate(_ request: TranslationRequest, configuration: TranslationConfiguration) async throws -> TranslationResult
}

/// LibreTranslate self-hosted service with a cancellable, bounded HTTP transport. No public default.
public struct HTTPTranslationService: TranslationServing, @unchecked Sendable {
    private let configuration: URLSessionConfiguration
    public init(sessionConfiguration: URLSessionConfiguration = .ephemeral) {
        configuration = sessionConfiguration.copy() as! URLSessionConfiguration
    }
    public static func makeRequest(_ input: TranslationRequest, configuration: TranslationConfiguration) throws -> URLRequest {
        try LibreTranslateService.makeRequest(input, configuration: configuration)
    }
    public func translate(_ input: TranslationRequest, configuration: TranslationConfiguration) async throws -> TranslationResult {
        let request = try Self.makeRequest(input, configuration: configuration)
        return try await runTranslationHTTP(request, configuration: self.configuration)
    }
}

private func validTranslationLanguage(_ code: String) -> Bool {
    !code.isEmpty && code.utf8.count <= 32 && code.utf8.allSatisfy {
        (65...90).contains($0) || (97...122).contains($0) || $0 == 45
    }
}

/// LibreTranslate's documented JSON protocol. No echo/dictionary fallback on failure.
/// https://docs.libretranslate.com/guides/api_usage/
/// https://docs.libretranslate.com/api/operations/translate/
public struct LibreTranslateService: TranslationServing, @unchecked Sendable {
    public static let maxInputBytes = 32 * 1024
    public static let maxResponseBytes = 256 * 1024
    private let sessionConfiguration: URLSessionConfiguration

    /// A copied configuration also lets tests use an isolated URLProtocol without changing global sessions.
    public init(sessionConfiguration: URLSessionConfiguration = .ephemeral) {
        self.sessionConfiguration = sessionConfiguration.copy() as! URLSessionConfiguration
    }

    public static func makeRequest(_ input: TranslationRequest, configuration: TranslationConfiguration) throws -> URLRequest {
        guard configuration.provider == .libreTranslate else { throw TranslationFailure.wrongService }
        guard configuration.enabled else { throw TranslationFailure.disabled }
        try input.validate()
        let endpoint = configuration.endpoint.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let components = URLComponents(string: endpoint),
              ["http", "https"].contains(components.scheme?.lowercased() ?? ""),
              let host = components.host, !host.isEmpty,
              components.user == nil, components.password == nil,
              components.query == nil, components.fragment == nil,
              components.path.hasSuffix("/translate"), let url = components.url else {
            throw TranslationFailure.invalidEndpoint
        }
        guard validTranslationLanguage(input.source), validTranslationLanguage(input.target), input.target.lowercased() != "auto" else {
            throw TranslationFailure.invalidLanguage
        }
        var body = ["q": input.text, "source": input.source, "target": input.target, "format": "text"]
        if !configuration.apiKey.isEmpty { body["api_key"] = configuration.apiKey }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData,
                                 timeoutInterval: TranslationConfiguration.boundedTimeout(configuration.timeoutSeconds))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        return request
    }

    public func translate(_ input: TranslationRequest, configuration: TranslationConfiguration) async throws -> TranslationResult {
        let request = try Self.makeRequest(input, configuration: configuration)
        return try await runTranslationHTTP(request, configuration: sessionConfiguration)
    }
}

private func runTranslationHTTP(_ request: URLRequest, configuration: URLSessionConfiguration) async throws -> TranslationResult {
    guard !Task.isCancelled else { throw TranslationFailure.cancelled }
    let call = TranslationHTTPCall(configuration: configuration, timeout: request.timeoutInterval)
    return try await withTaskCancellationHandler(operation: {
        try await withCheckedThrowingContinuation { call.start(request, continuation: $0) }
    }, onCancel: { call.complete(.failure(TranslationFailure.cancelled)) })
}

/// Buffering is bounded during reception, including chunked/decompressed bodies; a deadline bounds the
/// entire call even if a server continually trickles bytes. Completion and cancellation race exactly once.
private final class TranslationHTTPCall: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private let configuration: URLSessionConfiguration
    private let timeout: Double
    private var session: URLSession?
    private var task: URLSessionDataTask?
    private var deadline: DispatchWorkItem?
    private var continuation: CheckedContinuation<TranslationResult, Error>?
    private var completed = false
    private var body = Data()

    init(configuration: URLSessionConfiguration, timeout: Double) {
        self.configuration = configuration.copy() as! URLSessionConfiguration
        self.timeout = timeout
    }

    func start(_ request: URLRequest, continuation: CheckedContinuation<TranslationResult, Error>) {
        lock.lock()
        guard !completed else { lock.unlock(); continuation.resume(throwing: TranslationFailure.cancelled); return }
        self.continuation = continuation
        configuration.timeoutIntervalForRequest = timeout
        configuration.timeoutIntervalForResource = timeout
        configuration.urlCache = nil; configuration.httpCookieStorage = nil; configuration.urlCredentialStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.waitsForConnectivity = false
        let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
        self.session = session
        let task = session.dataTask(with: request)
        self.task = task
        let deadline = DispatchWorkItem { [weak self] in self?.complete(.failure(TranslationFailure.timedOut)) }
        self.deadline = deadline
        DispatchQueue.global().asyncAfter(deadline: .now() + timeout, execute: deadline)
        task.resume()
        lock.unlock()
    }

    func complete(_ result: Result<TranslationResult, Error>) {
        lock.lock()
        guard !completed else { lock.unlock(); return }
        completed = true
        let continuation = continuation, session = session, deadline = deadline
        self.continuation = nil; self.session = nil; self.task = nil; self.deadline = nil
        body.removeAll(keepingCapacity: false)
        lock.unlock()
        deadline?.cancel()
        session?.invalidateAndCancel()
        continuation?.resume(with: result)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let http = response as? HTTPURLResponse else {
            completionHandler(.cancel); complete(.failure(TranslationFailure.invalidResponse)); return
        }
        guard (200..<300).contains(http.statusCode) else {
            completionHandler(.cancel)
            complete(.failure(TranslationFailure.http(http.statusCode)))
            return
        }
        guard response.expectedContentLength <= Int64(LibreTranslateService.maxResponseBytes) else {
            completionHandler(.cancel); complete(.failure(TranslationFailure.responseTooLarge)); return
        }
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        guard !completed else { lock.unlock(); return }
        guard data.count <= LibreTranslateService.maxResponseBytes - body.count else {
            lock.unlock(); complete(.failure(TranslationFailure.responseTooLarge)); return
        }
        body.append(data)
        lock.unlock()
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error {
            let code = (error as? URLError)?.code
            complete(.failure(code == .timedOut ? TranslationFailure.timedOut :
                              code == .cancelled ? TranslationFailure.cancelled : TranslationFailure.network))
            return
        }
        lock.lock()
        guard !completed else { lock.unlock(); return }
        let data = body
        lock.unlock()
        guard let value = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            complete(.failure(TranslationFailure.invalidResponse)); return
        }
        guard let text = value["translatedText"] as? String,
              !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, value["error"] == nil else {
            complete(.failure(TranslationFailure.invalidResponse)); return
        }
        let language = (value["detectedLanguage"] as? [String: Any])?["language"] as? String
        complete(.success(TranslationResult(text: text, detectedLanguage: language)))
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
        complete(.failure(TranslationFailure.redirect))
    }
}

/// The service receives only text. A separate editor adapter owns document access on the main thread.
@MainActor public protocol TranslationEditor: AnyObject {
    var ownerIdentity: AnyObject? { get }
    var clientIdentity: AnyObject? { get }
    var isActive: Bool { get }
    var isSecure: Bool { get }
    var epoch: Int { get }
    var selectedRange: NSRange { get }
    var markedRange: NSRange { get }
    func text(in range: NSRange) -> String?
    @discardableResult func insert(_ text: String, replacementRange: NSRange) -> Bool
}

/// A single-use capability for the original selection/caret. Once invalid it cannot become valid again
/// by switching back. Integrators must invalidate on owner deactivation, even for a reused IMK client proxy.
@MainActor public final class TranslationWriteback {
    public enum Mode { case replaceSelection, insertAfterSelection }
    private let editor: any TranslationEditor
    private weak var owner: AnyObject?
    private weak var client: AnyObject?
    public let selection: NSRange
    public let selectedText: String
    private let epoch: Int
    private let prefixRange: NSRange
    private let prefix: String
    private let suffixRange: NSRange
    private let suffix: String?
    private var invalid = false

    public init?(editor: any TranslationEditor) {
        guard !editor.isSecure, editor.isActive, let owner = editor.ownerIdentity, let client = editor.clientIdentity else { return nil }
        let epoch = editor.epoch
        let range = editor.selectedRange
        guard range.location != NSNotFound, range.location >= 0, range.length >= 0,
              range.length <= LibreTranslateService.maxInputBytes,
              range.location <= Int.max - range.length,
              editor.markedRange.location == NSNotFound || editor.markedRange.length == 0 else { return nil }
        let selected = range.length == 0 ? "" : editor.text(in: range)
        let prefixRange = NSRange(location: max(0, range.location - 32), length: min(32, range.location))
        let prefix = prefixRange.length == 0 ? "" : editor.text(in: prefixRange)
        // A one-unit right anchor also protects a caret at offset zero. nil (end of document or an
        // unavailable substring) is compared again; the epoch still guards reused IMK field proxies.
        let suffixRange = NSRange(location: range.location + range.length, length: 1)
        let suffix = editor.text(in: suffixRange)
        guard let selected, let prefix, (selected as NSString).length == range.length,
              selected.utf8.count <= LibreTranslateService.maxInputBytes,
              !editor.isSecure, editor.isActive, editor.epoch == epoch,
              editor.ownerIdentity === owner, editor.clientIdentity === client,
              editor.selectedRange == range else { return nil }
        self.editor = editor; self.owner = owner; self.client = client; self.epoch = epoch
        selection = range; selectedText = selected; self.prefixRange = prefixRange; self.prefix = prefix
        self.suffixRange = suffixRange; self.suffix = suffix
    }

    public func belongs(to owner: AnyObject) -> Bool { self.owner === owner }
    public func invalidate() { invalid = true }
    public var isValid: Bool {
        guard !invalid, let owner, let client, !editor.isSecure, editor.isActive,
              editor.ownerIdentity === owner, editor.clientIdentity === client,
              editor.epoch == epoch,
              editor.selectedRange == selection,
              editor.markedRange.location == NSNotFound || editor.markedRange.length == 0,
              (selection.length == 0 || editor.text(in: selection) == selectedText),
              (prefixRange.length == 0 || editor.text(in: prefixRange) == prefix),
              editor.text(in: suffixRange) == suffix else { invalid = true; return false }
        return true
    }

    @discardableResult public func commit(_ text: String, mode: Mode) -> Bool {
        guard !text.isEmpty, text.utf8.count <= LibreTranslateService.maxResponseBytes, isValid else { return false }
        let range: NSRange
        switch mode {
        case .replaceSelection:
            guard selection.length > 0 else { return false }
            range = selection
        case .insertAfterSelection:
            range = NSRange(location: selection.location + selection.length, length: 0)
        }
        invalid = true
        return editor.insert(text, replacementRange: range)
    }
}
