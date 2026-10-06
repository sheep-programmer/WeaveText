import AppKit
import Carbon
import Combine
import InputMethodKit
import SwiftUI
import WeaveCore

/// Keeps the original client weakly. The global controller is used only to validate ownership,
/// never to choose a write destination. All document access stays on the main thread.
@MainActor private final class IMKTranslationEditor: TranslationEditor {
    private weak var owner: WeaveInputController?
    private weak var capturedClient: AnyObject?
    private let capturedEpoch: Int
    init?(owner: WeaveInputController) {
        guard EngineHost.shared.activeController === owner, let client = owner.client() else { return nil }
        self.owner = owner; capturedClient = client as AnyObject; capturedEpoch = owner.translationEpoch
    }
    var ownerIdentity: AnyObject? { owner }
    var clientIdentity: AnyObject? { owner?.client() as AnyObject? }
    var isActive: Bool {
        guard let owner, let capturedClient else { return false }
        return EngineHost.shared.activeController === owner && owner.translationEpoch == capturedEpoch &&
            owner.client() as AnyObject? === capturedClient
    }
    var isSecure: Bool { IsSecureEventInputEnabled() }
    var epoch: Int { owner?.translationEpoch ?? -1 }
    private var target: IMKTextInput? { capturedClient as? IMKTextInput }
    var selectedRange: NSRange { target?.selectedRange() ?? NSRange(location: NSNotFound, length: 0) }
    var markedRange: NSRange { target?.markedRange() ?? NSRange(location: NSNotFound, length: 0) }
    func text(in range: NSRange) -> String? {
        guard !isSecure, isActive else { return nil }
        return target?.attributedSubstring(from: range)?.string
    }
    func insert(_ text: String, replacementRange: NSRange) -> Bool {
        guard !isSecure, isActive, let owner, let target else { return false }
        // IMKTextInput.insertText has no acknowledgement; success means dispatched to the validated client.
        return owner.commitTranslation(text, client: target, replacementRange: replacementRange, epoch: capturedEpoch)
    }
}

struct NativeTranslationJob: Equatable {
    enum Action: Equatable { case translate, prepareLanguages }
    let generation: Int
    let input: TranslationRequest
    let action: Action
    init(generation: Int, input: TranslationRequest, action: Action = .translate) {
        self.generation = generation; self.input = input; self.action = action
    }
}

@MainActor private enum TranslationPluginAccess {
    static let shared = TranslationPlugins(directory: EngineHost.userDirectory().appendingPathComponent("translation-plugins", isDirectory: true), defaults: .standard)
}

@MainActor final class TranslationModel: ObservableObject {
    let settings: TranslationSettings
    let plugins: TranslationPlugins
    @Published var sourceText = "" { didSet { if sourceText != oldValue { invalidateResult() } } }
    @Published private(set) var result: TranslationResult?
    @Published private(set) var isTranslating = false
    @Published private(set) var status = "读取选区或粘贴源文，点翻译后确认结果。"
    @Published private(set) var hasDestination = false
    @Published private(set) var hasSelection = false
    @Published private(set) var nativeJob: NativeTranslationJob?
    private let service: any TranslationServing
    private let secureInput: () -> Bool
    private let openURL: (URL) -> Bool
    private var destination: TranslationWriteback?
    private var requestTask: Task<Void, Never>?
    private var generation = 0
    private var settingsObserver: AnyCancellable?
    private var pluginsObserver: AnyCancellable?

    init(settings: TranslationSettings? = nil, plugins: TranslationPlugins? = nil, service: any TranslationServing = HTTPTranslationService(),
         secureInput: @escaping () -> Bool = { IsSecureEventInputEnabled() },
         openURL: @escaping (URL) -> Bool = { NSWorkspace.shared.open($0) }) {
        let settings = settings ?? .shared
        let plugins = plugins ?? TranslationPluginAccess.shared
        self.settings = settings; self.service = service; self.secureInput = secureInput
        self.plugins = plugins
        self.openURL = openURL
        settingsObserver = settings.objectWillChange.sink { [weak self] _ in self?.invalidateResult() }
        pluginsObserver = plugins.objectWillChange.sink { [weak self] _ in
            if self?.settings.provider == .appleSystem { self?.invalidateResult() }
        }
    }

    func begin(editor: (any TranslationEditor)?) {
        close()
        guard !secureInput(), let editor, let destination = TranslationWriteback(editor: editor) else {
            sourceText = ""
            status = secureInput() ? "安全输入已开启，无法读取或翻译。" : "未绑定输入框，可粘贴、翻译并复制结果。"
            return
        }
        self.destination = destination
        hasDestination = true; hasSelection = destination.selection.length > 0
        sourceText = destination.selectedText
        status = hasSelection ? "已读取原输入框选区；翻译后可替换选区或插入。" : "已绑定原光标位置；请粘贴源文。"
    }

    func invalidate(owner: AnyObject) {
        guard destination?.belongs(to: owner) == true else { return }
        destination?.invalidate(); destination = nil
        hasDestination = false; hasSelection = false
        if nativeJob == nil { cancel() }
        status = "原输入会话已结束；系统翻译结果仅可复制，重新打开后才能写回。"
    }

    func readSelection() {
        guard !secureInput(), let destination, destination.isValid else {
            status = "原选区已变化，请从原输入框重新打开翻译。"
            hasDestination = false; hasSelection = false
            return
        }
        sourceText = destination.selectedText
        status = sourceText.isEmpty ? "原输入框没有选中文字，请粘贴源文。" : "已读取原选区文字。"
    }

    private func invalidateResult() {
        generation += 1
        requestTask?.cancel(); requestTask = nil
        nativeJob = nil; isTranslating = false; result = nil
        status = "读取选区或粘贴源文，点翻译后确认结果。"
    }

    func translate() {
        if settings.provider == .googleWeb { openGoogleWeb(); return }
        invalidateResult()
        guard !secureInput() else { status = "安全输入已开启，无法翻译。"; return }
        let input = TranslationRequest(text: sourceText, source: settings.sourceLanguage, target: settings.targetLanguage)
        let configuration = settings.configuration
        do { try input.validate() }
        catch { status = error.localizedDescription; return }
        let token = generation
        if settings.provider == .appleSystem {
            guard requireNativePlugin() else { return }
            isTranslating = true; status = "正在准备 Apple 系统翻译…"
            nativeJob = NativeTranslationJob(generation: token, input: input)
            return
        }
        do { _ = try HTTPTranslationService.makeRequest(input, configuration: configuration) }
        catch { status = error.localizedDescription; return }
        isTranslating = true; status = "正在翻译…"
        let service = service
        requestTask = Task { [weak self] in
            do {
                let result = try await service.translate(input, configuration: configuration)
                guard let self, self.generation == token, !Task.isCancelled else { return }
                guard !self.secureInput() else { self.cancel(); self.status = "安全输入已开启，译文已丢弃。"; return }
                self.result = result; self.isTranslating = false; self.requestTask = nil
                self.status = result.detectedLanguage.map { "翻译完成（检测语言：\($0)），请确认后复制或写回。" }
                    ?? "翻译完成，请确认后复制或写回。"
            } catch {
                guard let self, self.generation == token, !Task.isCancelled else { return }
                self.isTranslating = false; self.requestTask = nil
                // Never display an arbitrary provider error body, which might include source text or API keys.
                self.status = (error as? TranslationFailure)?.localizedDescription ?? TranslationFailure.network.localizedDescription
            }
        }
    }

    func cancel() {
        generation += 1
        requestTask?.cancel(); requestTask = nil; nativeJob = nil; isTranslating = false
        status = "翻译已取消。"
    }

    func close() {
        cancel()
        destination?.invalidate(); destination = nil
        hasDestination = false; hasSelection = false
        sourceText = ""; result = nil
    }

    func paste(_ pasteboard: NSPasteboard = .general) {
        guard !secureInput() else { status = "安全输入已开启，无法粘贴。"; return }
        guard !(pasteboard.types ?? []).contains(where: { ClipboardGuard.skippedTypes.contains($0.rawValue) }) else {
            status = "剪贴板标记为敏感内容，无法读取。"; return
        }
        guard let text = pasteboard.string(forType: .string) else { status = "剪贴板里没有文字。"; return }
        guard text.utf8.count <= LibreTranslateService.maxInputBytes else { status = TranslationFailure.inputTooLarge.localizedDescription; return }
        sourceText = text
    }

    func copy(_ pasteboard: NSPasteboard = .general) {
        guard let result, !secureInput() else { return }
        pasteboard.clearContents(); pasteboard.setString(result.text, forType: .string)
        status = "译文已复制。"
    }

    func copySource(_ pasteboard: NSPasteboard = .general) {
        guard !sourceText.isEmpty, !secureInput() else { return }
        pasteboard.clearContents(); pasteboard.setString(sourceText, forType: .string)
        status = "源文已复制，可粘贴到 Google 官方网页。"
    }

    /// Explicit browser action; there is no web-result scraping or automatic HTTP fallback.
    func openGoogleWeb() {
        invalidateResult()
        guard !secureInput() else { status = "安全输入已开启，无法打开翻译网页。"; return }
        let input = TranslationRequest(text: sourceText, source: settings.sourceLanguage, target: settings.targetLanguage)
        do {
            let url = try GoogleTranslationWeb.url(for: input)
            revokeDestination()
            status = openURL(url) ? "已打开 Google 官方网页。请在网页复制译文，再回原应用粘贴；本窗口不会自动取回结果。"
                : TranslationFailure.webOpenFailed.localizedDescription
        } catch { status = error.localizedDescription }
    }

    func isCurrent(_ job: NativeTranslationJob) -> Bool {
        generation == job.generation && nativeJob == job && isTranslating && !secureInput() && plugins.appleReady
    }

    private func requireNativePlugin() -> Bool {
        guard settings.systemTranslationAvailable else { status = TranslationFailure.nativeUnavailable.localizedDescription; return false }
        if let failure = plugins.nativeFailure { status = failure.localizedDescription; return false }
        return true
    }

    func prepareLanguagePackages() {
        invalidateResult()
        guard !secureInput(), requireNativePlugin() else { return }
        guard settings.sourceLanguage.lowercased() != "auto" else {
            status = "准备语言包前请明确选择源语言；自动检测可在实际翻译时按需请求语言包。"; return
        }
        let input = TranslationRequest(text: "", source: settings.sourceLanguage, target: settings.targetLanguage)
        do { try input.validateLanguages() }
        catch { status = error.localizedDescription; return }
        isTranslating = true; status = "正在向 Apple 请求准备系统语言包…"
        nativeJob = NativeTranslationJob(generation: generation, input: input, action: .prepareLanguages)
    }

    func finishNativePreparation(_ job: NativeTranslationJob) {
        guard isCurrent(job) else { failNative(job, failure: .cancelled); return }
        nativeJob = nil; isTranslating = false; result = nil
        status = "系统语言包准备请求已完成；下载和安装状态由 Apple 管理，适配 ZIP 不含模型。"
    }

    func finishNative(_ job: NativeTranslationJob, result: TranslationResult) {
        guard generation == job.generation, nativeJob == job else { return }
        guard !secureInput() else { failNative(job, failure: .cancelled); return }
        guard !result.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { failNative(job, failure: .invalidResponse); return }
        guard result.text.utf8.count <= LibreTranslateService.maxResponseBytes else { failNative(job, failure: .responseTooLarge); return }
        self.result = result; isTranslating = false; nativeJob = nil
        status = hasDestination ? "Apple 系统翻译完成，请确认后复制或写回。" : "Apple 系统翻译完成；原目标已失效，仅可复制译文。"
    }

    func finishNativeAlreadyInTargetLanguage(_ job: NativeTranslationJob, sourceLanguage: String) {
        guard isCurrent(job) else { return }
        finishNative(job, result: TranslationResult(text: job.input.text, detectedLanguage: sourceLanguage))
        if result != nil { status = "原文已是目标语言，无需翻译；保留原文供复制或确认写回。" }
    }

    func failNative(_ job: NativeTranslationJob, failure: TranslationFailure) {
        guard generation == job.generation, nativeJob == job else { return }
        isTranslating = false; nativeJob = nil; result = nil; status = failure.localizedDescription
    }

    /// A download authorization UI can activate this app and change the client's focus. Revoke first.
    func prepareNativeSystemUI(_ job: NativeTranslationJob) -> Bool {
        guard isCurrent(job) else { return false }
        revokeDestination()
        status = "请在系统提示中确认语言包下载。原输入目标已解绑，翻译完成后可复制译文。"
        return true
    }

    private func revokeDestination() {
        destination?.invalidate(); destination = nil
        hasDestination = false; hasSelection = false
    }

    @discardableResult func commit(_ mode: TranslationWriteback.Mode) -> Bool {
        guard let result, !isTranslating, !secureInput(), let destination, destination.commit(result.text, mode: mode) else {
            status = "原输入框、选区或内容已变化，无法写回；请复制译文。"
            hasDestination = false; hasSelection = false
            self.destination?.invalidate()
            return false
        }
        self.destination = nil; hasDestination = false; hasSelection = false
        status = "已向原输入框发送译文。"
        return true
    }
}

@MainActor final class TranslationWindow: NSObject, NSWindowDelegate {
    // IMK callbacks run on the main thread but its Objective-C declarations lack actor annotations.
    // Keep the public IMK entry synchronous so selection/epoch capture cannot move to a later event.
    nonisolated static let shared = MainActor.assumeIsolated { TranslationWindow() }
    let model: TranslationModel
    private var window: NSPanel?
    init(model: TranslationModel? = nil) { self.model = model ?? TranslationModel(); super.init() }

    /// Main-thread integration entry. Reopening always cancels the old request and captures a new target.
    nonisolated func show(owner: WeaveInputController) {
        MainActor.assumeIsolated { showOnMainActor(owner: owner) }
    }

    private func showOnMainActor(owner: WeaveInputController) {
        guard !IsSecureEventInputEnabled() else { return }
        owner.prepareTranslation()
        model.begin(editor: IMKTranslationEditor(owner: owner))
        if window == nil {
            window = Self.panel(model: model)
            window?.delegate = self; window?.center()
        }
        window?.orderFrontRegardless()
    }

    /// Call from the original controller's deactivateServer before changing the active controller.
    /// Keeps the draft window available for copying, but irrevocably revokes document access.
    nonisolated func invalidate(owner: WeaveInputController) {
        MainActor.assumeIsolated { model.invalidate(owner: owner) }
    }
    nonisolated func prepareLanguagePackages() {
        MainActor.assumeIsolated {
            model.begin(editor: nil)
            if window == nil { window = Self.panel(model: model); window?.delegate = self; window?.center() }
            window?.orderFrontRegardless()
            model.prepareLanguagePackages()
        }
    }
    func windowWillClose(_ notification: Notification) { model.close() }

    static func panel(model: TranslationModel) -> NSPanel {
        let panel = InputPanel(contentRect: NSRect(x: 0, y: 0, width: 640, height: 620),
                                     styleMask: [.titled, .closable, .resizable, .nonactivatingPanel], backing: .buffered, defer: false)
        panel.title = "织文翻译"; panel.level = .floating
        panel.hidesOnDeactivate = false; panel.isReleasedWhenClosed = false
        panel.becomesKeyOnlyIfNeeded = true
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .ignoresCycle]
        panel.contentMinSize = NSSize(width: 520, height: 480)
        panel.contentView = NSHostingView(rootView: TranslationView(model: model).weaveStyle())
        WindowAppearance.shared.track(panel)
        return panel
    }
}

@MainActor struct TranslationSettingsView: View {
    @ObservedObject var settings: TranslationSettings
    @ObservedObject private var plugins: TranslationPlugins
    @StateObject private var importState = TranslationImportState()
    private let prepareLanguages: () -> Void
    init(settings: TranslationSettings? = nil, plugins: TranslationPlugins? = nil,
         prepareLanguages: @escaping () -> Void = { TranslationWindow.shared.prepareLanguagePackages() }) {
        self.settings = settings ?? .shared; self.plugins = plugins ?? TranslationPluginAccess.shared
        self.prepareLanguages = prepareLanguages
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Picker("翻译方式", selection: $settings.provider) {
                ForEach(TranslationProvider.allCases.filter { settings.systemTranslationAvailable || $0 != .appleSystem }, id: \.self) {
                    Text($0.title).tag($0)
                }
            }
            switch settings.provider {
            case .appleSystem:
                Text("安装并启用下方适配插件后，可使用 macOS 15 起的 Apple 系统设备端翻译，无需 API key。适配包不携带模型；语言包下载和安装由 Apple 管理。Apple 通道不是开源引擎。")
                    .font(.caption).foregroundStyle(.secondary)
            case .googleWeb:
                Text("点击按钮打开免费的 Google 官方翻译网页，源文会发送给 Google。请在网页手动复制译文，再回原应用粘贴；本窗口不会自动取回网页结果，无需 API key。")
                    .font(.caption).foregroundStyle(.secondary)
            case .libreTranslate:
                Text("高级选项：自行配置兼容 LibreTranslate 的服务；默认不启用，没有预设公共服务地址。")
                    .font(.caption).foregroundStyle(.secondary)
                Toggle("启用自定义 HTTP 服务", isOn: $settings.onlineEnabled)
                TextField("完整服务地址，例如 http://localhost:5000/translate", text: $settings.endpoint)
                    .textFieldStyle(.roundedBorder).accessibilityLabel("翻译服务地址")
                SecureField("API key（可选的服务鉴权）", text: $settings.apiKey).textFieldStyle(.roundedBorder)
                Stepper(value: $settings.timeoutSeconds, in: 0.5...30, step: 0.5) {
                    Text("HTTP 请求时限：\(settings.timeoutSeconds, specifier: "%.1f") 秒")
                }
            }
            pluginCard
            HStack {
                TextField("源语言代码（auto 自动检测）", text: $settings.sourceLanguage).textFieldStyle(.roundedBorder)
                TextField("目标语言代码（例如 en）", text: $settings.targetLanguage).textFieldStyle(.roundedBorder)
            }
            Text("仅在点击翻译或打开网页时处理源文，不自动翻译选区。").font(.caption).foregroundStyle(.secondary)
        }
    }

    private var pluginCard: some View {
        GroupBox("离线翻译适配插件") {
            VStack(alignment: .leading, spacing: 10) {
                Text("Apple 离线翻译适配插件").font(.headline)
                Text(plugins.apple.state.label).font(.caption).foregroundStyle(.secondary)
                if let descriptor = plugins.apple.descriptor {
                    Text("描述包版本：\(descriptor.version) · macOS \(descriptor.minimumOS.label)+").font(.caption)
                }
                Text("此 ZIP 仅安装系统引擎适配描述，不会安装 Apple 语言模型。卸载适配插件也不会删除系统语言包。")
                    .font(.caption).foregroundStyle(.secondary)
                HStack {
                    Button(plugins.apple.descriptor == nil ? "手动导入/安装…" : "导入更新…") { importPlugin() }.disabled(plugins.busy)
                    Toggle("启用", isOn: Binding(get: { plugins.apple.state == .enabled }, set: { enabled in
                        do { try plugins.setEnabled(enabled) } catch { importState.message = error.localizedDescription }
                    })).disabled(plugins.busy || plugins.apple.descriptor == nil || isUnsupported)
                    Button("卸载") { Task { await plugins.uninstall() } }.disabled(plugins.busy || plugins.apple.state == .notInstalled)
                }
                Button("准备 Apple 系统语言包…") { prepareLanguages() }
                    .disabled(!plugins.appleReady || settings.provider != .appleSystem)
                if plugins.busy { ProgressView().controlSize(.small) }
                Text(importState.message.isEmpty ? plugins.message : importState.message).font(.caption).foregroundStyle(.secondary)
            }.frame(maxWidth: .infinity, alignment: .leading).padding(6)
        }
    }

    private var isUnsupported: Bool {
        if case .unsupported = plugins.apple.state { return true }
        return false
    }
    private func importPlugin() {
        let panel = NSOpenPanel()
        panel.title = "导入 Apple 离线翻译适配描述包"
        panel.message = "选择 ZIP、描述包目录或原始 manifest。按内容检查，不依赖文件扩展名。"
        panel.canChooseFiles = true; panel.canChooseDirectories = true; panel.allowsMultipleSelection = false
        guard panel.runModal() == .OK, let url = panel.url else { return }
        importState.message = ""
        Task { await plugins.install(url) }
    }
}

@MainActor private final class TranslationImportState: ObservableObject { @Published var message = "" }

@MainActor struct TranslationView: View {
    @ObservedObject var model: TranslationModel
    var body: some View {
        if #available(macOS 15.0, *) { NativeTranslationView(model: model) }
        else { TranslationContentView(model: model) }
    }
}

@MainActor struct TranslationContentView: View {
    @ObservedObject var model: TranslationModel
    @ObservedObject private var settings: TranslationSettings
    init(model: TranslationModel) { self.model = model; settings = model.settings }
    private let languages = [("auto", "自动检测"), ("zh", "中文"), ("en", "English"), ("ja", "日本語"),
                             ("ko", "한국어"), ("fr", "Français"), ("de", "Deutsch"), ("es", "Español")]
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                WindowHeading(title: "翻译", subtitle: "读取选区或粘贴源文，翻译后确认译文", symbol: "character.bubble")
                Button("翻译设置") { SettingsWindow.shared.show(page: .translation) }
            }
                HStack {
                    languagePicker("源语言", value: $settings.sourceLanguage, includeAuto: true)
                    Image(systemName: "arrow.right").foregroundStyle(.secondary)
                    languagePicker("目标语言", value: $settings.targetLanguage, includeAuto: false)
                }
                HStack {
                    Text("源文").font(.headline)
                    Spacer()
                    Button("读取原选区") { model.readSelection() }
                    Button("粘贴") { model.paste() }
                    Button("复制源文") { model.copySource() }.disabled(model.sourceText.isEmpty)
                    Button("清空") { model.sourceText = "" }
                }
                ScrollView {
                    Text(model.sourceText.isEmpty ? "读取原选区或粘贴待翻译文字" : model.sourceText)
                        .font(.system(size: 16)).textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading).padding(8)
                }.frame(minHeight: 100).border(Color.secondary.opacity(0.3)).accessibilityLabel("待翻译源文")
                HStack {
                    Button(settings.provider == .googleWeb ? "打开 Google 官方网页" : "翻译") { model.translate() }.disabled(model.isTranslating)
                    if model.isTranslating { ProgressView().controlSize(.small); Button("取消") { model.cancel() } }
                    if settings.provider == .libreTranslate && !settings.onlineEnabled {
                        Text("自定义 HTTP 服务未启用").font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                }
                if settings.provider == .googleWeb {
                    Text("在 Google 网页查看并复制译文，再回原应用粘贴；本窗口不会读取网页结果。")
                        .font(.caption).foregroundStyle(.secondary)
                } else {
                    Button("改用 Google 官方网页…") { model.openGoogleWeb() }
                    if settings.provider == .appleSystem {
                        Text("系统语言包需要授权时，译文完成后仅可复制，以免写入已经变化的输入框。")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
                Text("译文").font(.headline)
                ScrollView {
                    Text(model.result?.text ?? (settings.provider == .googleWeb ? "请在 Google 官方网页查看和复制译文" : "译文会显示在这里"))
                        .font(.system(size: 16)).textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading).padding(8)
                }.frame(minHeight: 100).border(Color.secondary.opacity(0.3))
                HStack {
                    Button("复制译文") { model.copy() }.disabled(model.result == nil)
                    Spacer()
                    Button("插入原选区之后") { model.commit(.insertAfterSelection) }
                        .disabled(model.result == nil || !model.hasDestination)
                    Button("替换原选区") { model.commit(.replaceSelection) }
                        .disabled(model.result == nil || !model.hasDestination || !model.hasSelection)
                }
            Text(model.status).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
        }.padding(18)
    }

    private func languagePicker(_ title: String, value: Binding<String>, includeAuto: Bool) -> some View {
        Picker(title, selection: value) {
                ForEach(languages.filter { includeAuto || $0.0 != "auto" }, id: \.0) { item in Text(item.1).tag(item.0) }
                if !languages.contains(where: { $0.0 == value.wrappedValue }) { Text(value.wrappedValue).tag(value.wrappedValue) }
        }
    }
}
