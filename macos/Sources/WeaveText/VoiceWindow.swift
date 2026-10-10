import AppKit
import AVFoundation
import Speech
import SwiftUI
import WeaveCore

/// 停止录音保留窗口与草稿；识别资源在完成、取消或关闭时释放。
/// Stopping retains the panel and draft; completion or cancellation releases recognition resources.
final class VoiceModel: ObservableObject {
    typealias State = VoiceLifecycle.State
    @Published private(set) var state = State.idle
    @Published var text = "" {
        didSet {
            if !updatingDraft {
                lifecycle.editDraft(text)
                if state == .listening || state == .finishing {
                    status = "草稿已手动编辑，后续识别不会覆盖；停止后可上屏"
                }
            }
        }
    }
    @Published var status = "点话筒开始，停止后可确认文字再上屏"
    @Published var language: String { didSet { prefs.voiceLanguage = language } }
    @Published var results:[VoiceResult]=[]
    @Published var selectedEngine="system"
    private let prefs: Preferences
    private var lifecycle = VoiceLifecycle()
    private var updatingDraft = false
    private var draftEditedInWindow = false
    private var deadline: DispatchWorkItem?
    private var audio: AVAudioEngine?
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var recognizer: SFSpeechRecognizer?
    private var hasTap = false
    private var audioInput: VoiceAudioInput?
    private var pluginScope: VoiceSessionScope?
    private var systemRequested=false

    init(prefs: Preferences = .shared) {
        self.prefs = prefs
        language = prefs.voiceLanguage
    }

    deinit { releaseResources() }

    func start() {
        guard prefs.extensionEnabled("feature:voice") else { return }
        guard state == .idle else { return }
        guard !VoiceDraftWindow.shared.isVisible else { status = "请先完成草稿编辑，再开始说话"; return }
        releaseResources()
        guard let token = lifecycle.begin(draft: text) else { return }
        state = lifecycle.state
        status = "正在准备语音引擎与麦克风…"
        var seen = Set<String>()
        let engines = Array(prefs.voiceEngines.filter { !$0.isEmpty && seen.insert($0).inserted }.prefix(3))
        systemRequested=engines.contains("system")
        results=engines.map {id in VoiceResult(id:id,name:id=="system" ? "系统离线语音" : PluginCenter.shared.plugins.first{$0.id==id}?.name ?? id)}
        selectedEngine=engines.first ?? "system"
        guard !engines.isEmpty else { fail("请先在引擎设置中启用语音引擎"); return }
        scheduleDeadline(token: token)
        if !systemRequested {authorizeMicrophone(token:token);return}
        SFSpeechRecognizer.requestAuthorization { [weak self] authorization in
            DispatchQueue.main.async {
                guard let self, self.lifecycle.accepts(token), self.state == .authorizing else { return }
                if authorization != .authorized {
                    self.receive(["event":"error","text":"请在系统设置 → 隐私与安全性 → 语音识别中允许织文"],id:"system",token:token)
                    self.systemRequested=false
                    self.receive(["event":"end"],id:"system",token:token)
                    if engines.count==1 {self.fail("请在系统设置 → 隐私与安全性 → 语音识别中允许织文");return}
                }
                self.authorizeMicrophone(token:token)
            }
        }
    }

    private func authorizeMicrophone(token:Int) {
        AVCaptureDevice.requestAccess(for:.audio) {[weak self] allowed in
            DispatchQueue.main.async {
                guard let self,self.lifecycle.accepts(token),self.state == .authorizing else {return}
                guard allowed else {self.fail("请在系统设置 → 隐私与安全性 → 麦克风中允许织文");return}
                self.preparePlugins(token:token)
            }
        }
    }
    private func preparePlugins(token:Int) {
        guard lifecycle.preparePlugins(token: token) else { return }
        scheduleDeadline(token: token)
        let ids=results.map(\.id).filter{$0 != "system"},host=PluginCenter.shared.host
        let scope = VoiceSessionScope()
        pluginScope = scope
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            for id in ids {
                guard scope.isOpen else { return }
                guard let host, host.configured(id) else {
                    DispatchQueue.main.async { [weak self] in
                        self?.receive(["event":"error","text":"请先完成插件配置"],id:id,token:token)
                        self?.receive(["event":"end"],id:id,token:token)
                    }
                    continue
                }
                guard scope.isOpen else { return }
                if let session = host.speech(id, event: { [weak self] event in
                    DispatchQueue.main.async { self?.receive(event,id:id,token:token) }
                }) {
                    scope.register(VoicePluginSession(session), id: id)
                } else {
                    DispatchQueue.main.async { [weak self] in
                        // The host normally emits error/end on failure; this also
                        // handles a nil session without callbacks.
                        self?.receive(["event":"error","text":"语音插件无法启动"],id:id,token:token)
                        self?.receive(["event":"end"],id:id,token:token)
                    }
                }
            }
            DispatchQueue.main.async { [weak self] in
                guard let self, self.lifecycle.accepts(token), self.state == .authorizing else { return }
                self.beginRecording(token: token)
            }
        }
    }

    private func beginRecording(token: Int) {
        guard lifecycle.accepts(token), state == .authorizing else { return }
        if systemRequested {
            if let r=SFSpeechRecognizer(locale:Locale(identifier:language)),r.supportsOnDeviceRecognition,r.isAvailable {recognizer=r}
            else {systemRequested=false;receive(["event":"error","text":"系统离线语音资源不可用，请在系统听写中准备相应语言"],id:"system",token:token);receive(["event":"end"],id:"system",token:token)}
        }
        guard systemRequested || pluginScope?.isEmpty == false else {
            let errors = results.map(\.error).filter { !$0.isEmpty }.joined(separator: "；")
            fail(errors.isEmpty ? "语音引擎已结束，请重新开始" : errors); return
        }
        if results.first(where: { $0.id == selectedEngine })?.ended == true,
           let active = results.first(where: { !$0.ended }) {
            selectedEngine = active.id; updateSelectedText()
        }
        let audio = AVAudioEngine()
        let input = audio.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else {
            fail("没有可用的麦克风，请检查系统声音输入设置")
            return
        }
        let request:SFSpeechAudioBufferRecognitionRequest?=systemRequested ? SFSpeechAudioBufferRecognitionRequest() : nil
        request?.shouldReportPartialResults = true
        request?.requiresOnDeviceRecognition = true
        request?.taskHint = .dictation
        request?.addsPunctuation = true
        self.audio = audio
        self.request = request
        let hasPlugins = pluginScope?.isEmpty == false
        let converter = hasPlugins ? VoicePCMConverter(input: format) : nil
        if hasPlugins && converter==nil {fail("麦克风音频无法转换为插件需要的格式");return}
        let audioInput = VoiceAudioInput(request: request, converter: converter, plugins: pluginScope)
        self.audioInput = audioInput
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
            do { try audioInput.append(buffer) }
            catch {
                DispatchQueue.main.async {
                    guard let self, self.lifecycle.accepts(token), self.state == .listening else { return }
                    self.fail("麦克风音频转换失败")
                }
            }
        }
        hasTap = true
        if let request,let recognizer {task = recognizer.recognitionTask(with: request) { [weak self] result, error in
            DispatchQueue.main.async {
                guard let self, self.lifecycle.accepts(token) else { return }
                guard self.results.first(where:{$0.id=="system"})?.ended != true else {return}
                if let result {
                    self.receive(["event":result.isFinal ? "final" : "partial","text":result.bestTranscription.formattedString],id:"system",token:token)
                }
                if result?.isFinal == true || error != nil {
                    if error != nil && result==nil {self.receive(["event":"error","text":"系统识别已停止，可确认已有文字"],id:"system",token:token)}
                    self.receive(["event":"end"],id:"system",token:token)
                }
            }
        }}
        do {
            audio.prepare()
            try audio.start()
            guard lifecycle.didStartRecording(token: token) else { fail("语音准备超时，请重新开始"); return }
            deadline?.cancel(); deadline = nil
            state = lifecycle.state
            status = "正在听 · 点停止结束录音，窗口仍会保留"
        } catch {
            fail("麦克风无法启动：\(error.localizedDescription)")
        }
    }

    private func receive(_ event:[String:Any],id:String,token:Int) {
        guard lifecycle.accepts(token),let i=results.firstIndex(where:{$0.id==id}), !results[i].ended else {return}
        results[i].apply(event)
        if selectedEngine==id {updateSelectedText()}
        if event["event"] as? String=="error" {status=results[i].name+"："+results[i].error}
        if results[i].ended {
            pluginScope?.retire(id)
            if id == "system" {
                systemRequested = false
                audioInput?.finishSystem()
                task?.cancel(); task = nil; request = nil; recognizer = nil
            }
        }
        if results.allSatisfy(\.ended),state == .listening || state == .finishing {complete(token: token)}
    }
    func selectResult(_ id:String) {
        guard let result = results.first(where: { $0.id == id }) else { return }
        selectedEngine = id
        lifecycle.selectTranscript(result.text)
        syncDraft()
    }
    private func updateSelectedText() {
        guard let r=results.first(where:{$0.id==selectedEngine}) else {return}
        guard lifecycle.updateTranscript(r.text, token: lifecycle.token) != nil else { return }
        syncDraft()
    }
    private func syncDraft() {
        updatingDraft = true; text = lifecycle.draft; updatingDraft = false
    }
    private func complete(token: Int) {
        guard lifecycle.finish(token: token) else { return }
        releaseResources()
        state = lifecycle.state
        let errors = results.map(\.error).filter { !$0.isEmpty }.joined(separator: "；")
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            status = errors.isEmpty ? "没有识别到文字，请重新开始" : errors
        } else {
            status = errors.isEmpty ? "识别完成，可编辑文字后上屏" : "识别已结束，可编辑已有文字后上屏"
        }
    }

    /// 结束音频并等待终稿，保留窗口和已经识别的文字。
    /// End audio and wait for the final text, retaining both the panel and its draft.
    func stop() {
        let previous = state
        switch lifecycle.stop() {
        case .none: return
        case .cancel:
            releaseResources()
            state = lifecycle.state
            status = previous == .authorizing ? "已取消准备，可重新开始" : "已停止，文字留在窗口，可确认后上屏"
        case .finishRecording:
            state = lifecycle.state
            scheduleDeadline(token: lifecycle.token)
            stopAudio()
            pluginScope?.stop()
            status = "正在整理文字… · 可结束等待并保留已有文字"
        }
    }

    func close() {
        VoiceDraftWindow.shared.dismiss()
        lifecycle.close()
        releaseResources()
        state = lifecycle.state
        status = "录音已停止，文字仅保留在此窗口，未自动发送"
    }

    func clear() {
        guard state == .idle else { return }
        VoiceDraftWindow.shared.dismiss()
        lifecycle.clearDraft(); syncDraft()
        draftEditedInWindow = false
        releaseResources();results=[]
        status = "点话筒开始，停止后可确认文字再上屏"
    }

    func editDraft() {
        guard state == .idle, !text.isEmpty else { return }
        draftEditedInWindow = true
        VoiceDraftWindow.shared.show(model: self)
    }

    @discardableResult func commit() -> Bool {
        guard state == .idle, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return false }
        guard !VoiceDraftWindow.shared.isVisible else { status = "请先完成草稿编辑，再点选输入位置上屏"; return false }
        guard !draftEditedInWindow || !NSApp.isActive else {
            status = "请先点选其他应用中的输入位置，再回这里上屏"; return false
        }
        guard lifecycle.commit(using: { EngineHost.shared.activeController?.commitVoiceText($0) == true }) else {
            status = "请先点选要输入文字的位置，再点上屏"
            return false
        }
        syncDraft()
        draftEditedInWindow = false
        releaseResources();results=[]
        status = "已上屏，可以继续说话"
        return true
    }

    private func fail(_ message: String) {
        lifecycle.close()
        releaseResources()
        state = lifecycle.state
        status = message
    }

    private func stopAudio() {
        audioInput?.finish()
        audio?.stop()
        if hasTap { audio?.inputNode.removeTap(onBus: 0); hasTap = false }
    }

    private func scheduleDeadline(token: Int) {
        deadline?.cancel()
        guard let delay = lifecycle.remainingTime else { deadline = nil; return }
        let work = DispatchWorkItem { [weak self] in
            guard let self, self.lifecycle.token == token, self.state != .idle else { return }
            guard let reason = self.lifecycle.expire(token: token) else { self.scheduleDeadline(token: token); return }
            self.releaseResources()
            self.state = self.lifecycle.state
            switch reason {
            case .authorization: self.status = "等待权限超时，请确认系统权限后重新开始"
            case .plugins: self.status = "语音插件准备超时，请检查插件配置后重新开始"
            case .finishing: self.status = "整理文字超时，已有文字保留，可编辑后上屏"
            }
        }
        deadline = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }

    private func releaseResources() {
        deadline?.cancel(); deadline = nil
        stopAudio()
        task?.cancel()
        task = nil; request = nil; audio = nil; recognizer = nil; audioInput = nil
        pluginScope?.cancel(); pluginScope = nil
        systemRequested = false
        for i in results.indices { results[i].ended = true }
    }
}

final class VoiceWindow: NSObject, NSWindowDelegate {
    static let shared = VoiceWindow()
    private var window: InputPanel?
    let model = VoiceModel()
    var isVisible: Bool { window?.isVisible == true }

    func show(owner: WeaveInputController) {
        guard Preferences.shared.extensionEnabled("feature:voice") else { return }
        HandwritingWindow.shared.dismiss()
        owner.finishComposition()
        if window == nil { window = Self.panel(model: model); window?.delegate = self; window?.center() }
        window?.orderFrontRegardless()
    }

    static func panel(model: VoiceModel) -> InputPanel {
        let panel = InputPanel(contentRect: NSRect(x: 0, y: 0, width: 540, height: 420),
                               styleMask: [.titled, .closable, .resizable, .nonactivatingPanel], backing: .buffered, defer: false)
        panel.title = "织文语音"
        panel.level = .floating
        panel.hidesOnDeactivate = false
        panel.isReleasedWhenClosed = false
        panel.becomesKeyOnlyIfNeeded = true
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .ignoresCycle]
        panel.contentMinSize = NSSize(width: 380, height: 260)
        panel.contentView = ClickThroughHostingView(rootView: VoiceView(model: model))
        WindowAppearance.shared.track(panel, extensionKey:"feature:voice")
        return panel
    }

    func windowWillClose(_ notification: Notification) { model.close() }
}

struct VoiceView: View {
    @ObservedObject var model: VoiceModel
    @ObservedObject var prefs:Preferences = .shared
    var body: some View {
        VStack(spacing: 14) {
            WindowHeading(title:"语音输入",subtitle:"说完后确认文字，再写入当前输入框",symbol:"waveform",prefs:prefs)
            HStack {
                Picker("识别语言", selection: $model.language) {
                    Text("中文").tag("zh-CN")
                    Text("English").tag("en-US")
                }.pickerStyle(.segmented).labelsHidden().frame(width: 220).disabled(model.state != .idle)
                Spacer()
                Button("引擎设置…") {SettingsWindow.shared.show(page:.plugins)}.disabled(model.state != .idle)
            }
            if model.results.count>1 {
                HStack {
                    ForEach(model.results) {result in
                        Button {model.selectResult(result.id)} label:{VStack(alignment:.leading,spacing:3) {Text(result.name).lineLimit(1);Text(result.error.isEmpty ? String(result.text.prefix(28)) : result.error).font(.caption).lineLimit(2)}.padding(6).background(model.selectedEngine==result.id ? Color.accentColor.opacity(0.12) : Color.clear,in:RoundedRectangle(cornerRadius:6))}
                    }
                }
            }
            ScrollView {
                Text(model.text.isEmpty ? "识别文字会显示在这里，停止后可编辑" : model.text)
                    .font(.system(size: 22))
                    .foregroundStyle(model.text.isEmpty ? .secondary : .primary)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .topLeading)
                    .padding(14)
                }
                .frame(maxWidth: .infinity, minHeight: 100)
                .accessibilityLabel("语音草稿")
                .background(Color(nsColor: .textBackgroundColor), in: RoundedRectangle(cornerRadius: 12))
            Text(model.status).font(.caption).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading)
            HStack {
                Menu("文字") {
                    Button("编辑草稿…") { model.editDraft() }.disabled(model.state != .idle || model.text.isEmpty)
                    Button("复制全文") { NSPasteboard.general.clearContents(); NSPasteboard.general.setString(model.text, forType: .string) }.disabled(model.text.isEmpty)
                    Divider()
                    Button("清空") { model.clear() }.disabled(model.text.isEmpty || model.state != .idle)
                }.fixedSize()
                Spacer()
                Button { model.state == .idle ? model.start() : model.stop() } label: {
                    Label(recordingAction, systemImage: model.state == .idle ? "mic.fill" : "stop.fill")
                }.buttonStyle(.borderedProminent)
                Button("上屏") { model.commit() }.disabled(model.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || model.state != .idle)
            }
        }
        .padding(20)
        .background(Theme.palette(prefs.colorTheme).surface).weaveStyle(prefs)
    }

    private var recordingAction: String {
        switch model.state {
        case .idle: return "开始说话"
        case .authorizing: return "取消准备"
        case .listening: return "停止录音"
        case .finishing: return "结束等待"
        }
    }
}

/// Editing is an explicit action in a regular window; the recording panel remains nonactivating.
final class VoiceDraftWindow {
    static let shared = VoiceDraftWindow()
    private var window: NSWindow?
    var isVisible: Bool { window?.isVisible == true }
    func dismiss() { window?.close() }

    func show(model: VoiceModel) {
        guard model.state == .idle else { return }
        if window == nil { window = Self.makeWindow(model: model) }
        window?.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    static func makeWindow(model: VoiceModel) -> NSWindow {
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 520, height: 360),
            styleMask: [.titled, .closable, .resizable], backing: .buffered, defer: false)
        window.title = "编辑语音草稿"
        window.isReleasedWhenClosed = false
        window.contentMinSize = NSSize(width: 360, height: 260)
        window.contentView = NSHostingView(rootView: VoiceDraftView(model: model, done: { [weak window] in window?.close() }))
        WindowAppearance.shared.track(window, extensionKey: "feature:voice")
        window.center()
        return window
    }
}

struct VoiceDraftView: View {
    @ObservedObject var model: VoiceModel
    let done: () -> Void
    var body: some View {
        VStack(spacing: 12) {
            TextEditor(text: $model.text).font(.system(size: 20)).accessibilityLabel("编辑语音草稿")
                .disabled(model.state != .idle)
            HStack {
                Text("完成后点选输入位置，再回语音窗口上屏").font(.caption).foregroundStyle(.secondary)
                Spacer()
                Button("完成", action: done).keyboardShortcut(.defaultAction)
            }
        }.padding(16).weaveStyle(Preferences.shared)
    }
}
