import AppKit
import AVFoundation
import Speech
import SwiftUI
import WeaveCore

/// 录音与窗口分开：停止识别只停话筒，关闭窗口才取消整个会话。
/// Recording and window lifetimes are separate: stopping the microphone never dismisses the panel.
final class VoiceModel: ObservableObject {
    enum State { case idle, authorizing, listening, finishing }
    @Published private(set) var state = State.idle
    @Published var text = ""
    @Published var status = "点话筒开始，停止后可确认文字再上屏"
    @Published var language: String { didSet { prefs.voiceLanguage = language } }
    @Published var results:[VoiceResult]=[]
    @Published var selectedEngine="system"
    private let prefs: Preferences
    private var generation = 0
    private var audio: AVAudioEngine?
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var recognizer: SFSpeechRecognizer?
    private var hasTap = false
    private var pluginSessions:[String:PluginSpeech]=[:]
    private var systemRequested=false
    private var prefix=""

    init(prefs: Preferences = .shared) {
        self.prefs = prefs
        language = prefs.voiceLanguage
    }

    func start() {
        guard state == .idle else { return }
        releaseRecording()
        generation += 1
        let token = generation
        state = .authorizing
        status = "正在准备语音引擎与麦克风…"
        prefix=text
        let engines=Array(prefs.voiceEngines.prefix(3))
        systemRequested=engines.contains("system")
        results=engines.map {id in VoiceResult(id:id,name:id=="system" ? "系统离线语音" : PluginCenter.shared.plugins.first{$0.id==id}?.name ?? id)}
        selectedEngine=engines.first ?? "system"
        if !systemRequested {authorizeMicrophone(token:token);return}
        SFSpeechRecognizer.requestAuthorization { [weak self] authorization in
            DispatchQueue.main.async {
                guard let self, self.generation == token, self.state == .authorizing else { return }
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
                guard let self,self.generation==token,self.state == .authorizing else {return}
                guard allowed else {self.fail("请在系统设置 → 隐私与安全性 → 麦克风中允许织文");return}
                self.preparePlugins(token:token)
            }
        }
    }
    private func preparePlugins(token:Int) {
        let ids=results.map(\.id).filter{$0 != "system"},host=PluginCenter.shared.host
        Task { @MainActor in
            let sessions=await Task.detached { [weak self] () -> [String:PluginSpeech] in
                var sessions:[String:PluginSpeech]=[:]
                for id in ids {
                    guard let host,host.configured(id) else {
                        DispatchQueue.main.async {self?.receive(["event":"error","text":"请先完成插件配置"],id:id,token:token);self?.receive(["event":"end"],id:id,token:token)}
                        continue
                    }
                    if let session=host.speech(id,event:{[weak self] event in
                        DispatchQueue.main.async {self?.receive(event,id:id,token:token)}
                    }) {sessions[id]=session}
                }
                return sessions
            }.value
            guard generation==token,state == .authorizing else {for s in sessions.values {s.cancel()};return}
            pluginSessions=sessions;beginRecording(token:token)
        }
    }

    private func beginRecording(token: Int) {
        if systemRequested {
            if let r=SFSpeechRecognizer(locale:Locale(identifier:language)),r.supportsOnDeviceRecognition,r.isAvailable {recognizer=r}
            else {systemRequested=false;receive(["event":"error","text":"系统离线语音资源不可用，请在系统听写中准备相应语言"],id:"system",token:token);receive(["event":"end"],id:"system",token:token)}
        }
        guard systemRequested || !pluginSessions.isEmpty else {fail(results.map(\.error).filter{!$0.isEmpty}.joined(separator:"；"));return}
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
        let sessions=Array(pluginSessions.values)
        let converter=VoicePCMConverter(input:format)
        if !sessions.isEmpty && converter==nil {fail("麦克风音频无法转换为插件需要的格式");return}
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
            request?.append(buffer)
            if !sessions.isEmpty,let converter {
                do {let data=try converter.convert(buffer);for s in sessions {s.feed(data)}}
                catch {DispatchQueue.main.async {guard let self,self.generation==token else{return};self.fail("麦克风音频转换失败")}}
            }
        }
        hasTap = true
        if let request,let recognizer {task = recognizer.recognitionTask(with: request) { [weak self] result, error in
            DispatchQueue.main.async {
                guard let self, self.generation == token else { return }
                guard self.results.first(where:{$0.id=="system"})?.ended != true else {return}
                if let result {
                    let next = result.bestTranscription.formattedString
                    if let i=self.results.firstIndex(where:{$0.id=="system"}) {self.results[i].finalText="";self.results[i].partialText=next;self.updateSelectedText()}
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
            state = .listening
            status = "正在听 · 点停止结束录音，窗口仍会保留"
        } catch {
            fail("麦克风无法启动：\(error.localizedDescription)")
        }
    }

    private func receive(_ event:[String:Any],id:String,token:Int) {
        guard generation==token,let i=results.firstIndex(where:{$0.id==id}) else {return}
        if results[i].ended,event["event"] as? String != "replace" {return}
        results[i].apply(event)
        if selectedEngine==id {updateSelectedText()}
        if event["event"] as? String=="error" {status=results[i].name+"："+results[i].error}
        if results.allSatisfy(\.ended),state == .listening || state == .finishing {complete()}
    }
    func selectResult(_ id:String) {selectedEngine=id;updateSelectedText()}
    private func updateSelectedText() {
        guard let r=results.first(where:{$0.id==selectedEngine}) else {return}
        text=prefix.isEmpty ? r.text : r.text.isEmpty ? prefix : prefix+"\n"+r.text
    }
    private func complete() {
        stopAudio();request?.endAudio();task?.cancel();task=nil;request=nil;audio=nil;recognizer=nil
        state = .idle;status="识别完成，选择结果后上屏"
        // Keep callbacks alive for late replacements while the draft still belongs to this recording.
    }

    /// 结束音频并等待终稿，保留窗口和已经识别的文字。
    /// End audio and wait for the final text, retaining both the panel and its draft.
    func stop() {
        guard state == .listening else { return }
        stopAudio()
        request?.endAudio()
        for s in pluginSessions.values {s.stop()}
        state = .finishing
        status = "正在整理文字…"
        let token = generation
        // A final decode can take longer than five seconds on older Macs. Keep the draft
        // visible while waiting, instead of cancelling the recognizer before its tail arrives.
        DispatchQueue.main.asyncAfter(deadline: .now() + 15) { [weak self] in
            guard let self, self.generation == token, self.state == .finishing else { return }
            self.complete()
            self.status = "已停止，文字留在窗口，可确认后上屏"
        }
    }

    func close() {
        releaseRecording()
        state = .idle
        status = "录音已停止，文字仅保留在此窗口，未自动发送"
    }

    func clear() {
        guard state == .idle else { return }
        text = ""
        releaseRecording();results=[];prefix=""
        status = "点话筒开始，停止后可确认文字再上屏"
    }

    @discardableResult func commit() -> Bool {
        guard state == .idle, !text.isEmpty else { return false }
        guard EngineHost.shared.activeController?.commitVoiceText(text) == true else {
            status = "请先点选要输入文字的位置，再点上屏"
            return false
        }
        text = ""
        releaseRecording();results=[];prefix=""
        status = "已上屏，可以继续说话"
        return true
    }

    private func fail(_ message: String) {
        releaseRecording()
        state = .idle
        status = message
    }

    private func stopAudio() {
        audio?.stop()
        if hasTap { audio?.inputNode.removeTap(onBus: 0); hasTap = false }
    }

    private func releaseRecording() {
        generation += 1
        stopAudio()
        request?.endAudio()
        task?.cancel()
        task = nil; request = nil; audio = nil; recognizer = nil
        for session in pluginSessions.values {session.cancel()};pluginSessions=[:]
    }
}

final class VoiceWindow: NSObject, NSWindowDelegate {
    static let shared = VoiceWindow()
    private var window: InputPanel?
    let model = VoiceModel()
    var isVisible: Bool { window?.isVisible == true }

    func show(owner: WeaveInputController) {
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
        WindowAppearance.shared.track(panel)
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
                Text(model.text.isEmpty ? "识别出的文字会显示在这里" : model.text)
                    .font(.system(size: 22))
                    .foregroundStyle(model.text.isEmpty ? .secondary : .primary)
                    .frame(maxWidth: .infinity, minHeight: 100, alignment: .topLeading)
                    .padding(14)
            }
            .background(Color(nsColor: .textBackgroundColor), in: RoundedRectangle(cornerRadius: 12))
            Text(model.status).font(.caption).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading)
            HStack {
                Button("清空") { model.clear() }.disabled(model.text.isEmpty || model.state != .idle)
                Button("复制") { NSPasteboard.general.clearContents(); NSPasteboard.general.setString(model.text, forType: .string) }
                    .disabled(model.text.isEmpty)
                Spacer()
                Button { model.state == .listening ? model.stop() : model.start() } label: {
                    Label(model.state == .listening ? "停止录音" : "开始说话", systemImage: model.state == .listening ? "stop.fill" : "mic.fill")
                }.buttonStyle(.borderedProminent).disabled(model.state == .authorizing || model.state == .finishing)
                Button("上屏") { model.commit() }.disabled(model.text.isEmpty || model.state != .idle)
            }
        }
        .padding(20)
        .background(Theme.palette(prefs.colorTheme).surface).weaveStyle(prefs)
    }
}
