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
    private let prefs: Preferences
    private var generation = 0
    private var audio: AVAudioEngine?
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var recognizer: SFSpeechRecognizer?
    private var hasTap = false

    init(prefs: Preferences = .shared) {
        self.prefs = prefs
        language = prefs.voiceLanguage
    }

    func start() {
        guard state == .idle else { return }
        generation += 1
        let token = generation
        state = .authorizing
        status = "正在准备麦克风与系统离线语音…"
        SFSpeechRecognizer.requestAuthorization { [weak self] authorization in
            DispatchQueue.main.async {
                guard let self, self.generation == token, self.state == .authorizing else { return }
                guard authorization == .authorized else {
                    self.fail("请在系统设置 → 隐私与安全性 → 语音识别中允许织文")
                    return
                }
                AVCaptureDevice.requestAccess(for: .audio) { [weak self] allowed in
                    DispatchQueue.main.async {
                        guard let self, self.generation == token, self.state == .authorizing else { return }
                        guard allowed else {
                            self.fail("请在系统设置 → 隐私与安全性 → 麦克风中允许织文")
                            return
                        }
                        self.beginRecording(token: token)
                    }
                }
            }
        }
    }

    private func beginRecording(token: Int) {
        guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: language)),
              recognizer.supportsOnDeviceRecognition, recognizer.isAvailable else {
            fail("系统离线语音暂不可用，请在系统听写中准备相应语言后重试；不会改用云端识别")
            return
        }
        let audio = AVAudioEngine()
        let input = audio.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else {
            fail("没有可用的麦克风，请检查系统声音输入设置")
            return
        }
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        request.requiresOnDeviceRecognition = true
        request.taskHint = .dictation
        let prefix = text
        self.audio = audio
        self.request = request
        self.recognizer = recognizer
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in request.append(buffer) }
        hasTap = true
        task = recognizer.recognitionTask(with: request) { [weak self] result, error in
            DispatchQueue.main.async {
                guard let self, self.generation == token else { return }
                if let result {
                    let next = result.bestTranscription.formattedString
                    self.text = prefix.isEmpty ? next : prefix + "\n" + next
                }
                if result?.isFinal == true || error != nil {
                    self.releaseRecording()
                    self.state = .idle
                    self.status = error != nil && result == nil ? "识别已停止，请确认文字；可点话筒重试" : "识别完成，点上屏写入当前输入框"
                }
            }
        }
        do {
            audio.prepare()
            try audio.start()
            state = .listening
            status = "正在听 · 点停止结束录音，窗口仍会保留"
        } catch {
            fail("麦克风无法启动：\(error.localizedDescription)")
        }
    }

    /// 结束音频并等待终稿，保留窗口和已经识别的文字。
    /// End audio and wait for the final text, retaining both the panel and its draft.
    func stop() {
        guard state == .listening else { return }
        stopAudio()
        request?.endAudio()
        state = .finishing
        status = "正在整理文字…"
        let token = generation
        DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [weak self] in
            guard let self, self.generation == token, self.state == .finishing else { return }
            self.releaseRecording()
            self.state = .idle
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
        status = "点话筒开始，停止后可确认文字再上屏"
    }

    @discardableResult func commit() -> Bool {
        guard state == .idle, !text.isEmpty else { return false }
        guard EngineHost.shared.activeController?.commitVoiceText(text) == true else {
            status = "请先点选要输入文字的位置，再点上屏"
            return false
        }
        text = ""
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
        let panel = InputPanel(contentRect: NSRect(x: 0, y: 0, width: 500, height: 320),
                               styleMask: [.titled, .closable, .resizable, .nonactivatingPanel], backing: .buffered, defer: false)
        panel.title = "织文语音"
        panel.level = .floating
        panel.hidesOnDeactivate = false
        panel.isReleasedWhenClosed = false
        panel.becomesKeyOnlyIfNeeded = true
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .ignoresCycle]
        panel.contentMinSize = NSSize(width: 380, height: 260)
        panel.contentView = ClickThroughHostingView(rootView: VoiceView(model: model))
        return panel
    }

    func windowWillClose(_ notification: Notification) { model.close() }
}

struct VoiceView: View {
    @ObservedObject var model: VoiceModel
    var body: some View {
        VStack(spacing: 14) {
            HStack {
                Picker("识别语言", selection: $model.language) {
                    Text("中文").tag("zh-CN")
                    Text("English").tag("en-US")
                }.pickerStyle(.segmented).labelsHidden().frame(width: 220).disabled(model.state != .idle)
                Spacer()
                Text("系统离线语音").font(.caption).foregroundStyle(.secondary)
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
                }.disabled(model.state == .authorizing || model.state == .finishing)
                Button("上屏") { model.commit() }.disabled(model.text.isEmpty || model.state != .idle)
            }
        }
        .padding(16)
        .background(Color(nsColor: .windowBackgroundColor))
    }
}
