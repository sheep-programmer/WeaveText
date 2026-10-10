import AVFoundation
import Foundation
import Speech
import WeaveCore

final class VoicePluginSession: VoiceSessionHandle {
    private let session: PluginSpeech
    init(_ session: PluginSpeech) { self.session = session }
    func feed(_ data: Data) { session.feed(data) }
    func stop() { session.stop() }
    func cancel() { session.cancel() }
}

/// Serialize the tap with endAudio so an in-flight buffer cannot append after stop.
final class VoiceAudioInput {
    private let lock = NSLock()
    private var accepting = true
    private var systemAccepting = true
    private let request: SFSpeechAudioBufferRecognitionRequest?
    private let converter: VoicePCMConverter?
    private let plugins: VoiceSessionScope?
    init(request: SFSpeechAudioBufferRecognitionRequest?, converter: VoicePCMConverter?, plugins: VoiceSessionScope?) {
        self.request = request; self.converter = converter; self.plugins = plugins
    }
    func append(_ buffer: AVAudioPCMBuffer) throws {
        lock.lock(); defer { lock.unlock() }
        guard accepting else { return }
        if systemAccepting { request?.append(buffer) }
        if let converter, let plugins, !plugins.isEmpty {
            let data = try converter.convert(buffer)
            if !data.isEmpty { plugins.feed(data) }
        }
    }
    func finish() {
        lock.lock(); defer { lock.unlock() }
        guard accepting else { return }
        accepting = false; endSystemAudio()
    }
    func finishSystem() {
        lock.lock(); defer { lock.unlock() }
        endSystemAudio()
    }
    private func endSystemAudio() {
        guard systemAccepting else { return }
        systemAccepting = false; request?.endAudio()
    }
}

/// AVAudioConverter preserves resampling state between microphone chunks.
final class VoicePCMConverter {
    private let converter: AVAudioConverter
    private let output: AVAudioFormat
    init?(input:AVAudioFormat) {
        guard let output=AVAudioFormat(commonFormat:.pcmFormatInt16,sampleRate:16000,channels:1,interleaved:true),
              let converter=AVAudioConverter(from:input,to:output) else {return nil}
        self.output=output;self.converter=converter;converter.downmix=true
    }
    func convert(_ input:AVAudioPCMBuffer) throws -> Data {
        let capacity=AVAudioFrameCount(ceil(Double(input.frameLength)*16000/input.format.sampleRate)+64)
        guard let out=AVAudioPCMBuffer(pcmFormat:output,frameCapacity:capacity) else {throw PluginFailure("无法创建语音缓冲区")}
        var provided=false,error:NSError?
        let status=converter.convert(to:out,error:&error) {_,state in
            if provided {state.pointee = .noDataNow;return nil}
            provided=true;state.pointee = .haveData;return input
        }
        if let error {throw error}
        guard status != .error else {throw PluginFailure("麦克风音频转换失败")}
        guard let pointer=out.int16ChannelData?.pointee else {return Data()}
        return Data(bytes:pointer,count:Int(out.frameLength)*2)
    }
}

struct VoiceResult: Identifiable, Equatable {
    let id:String
    let name:String
    var finalText=""
    var partialText=""
    var error=""
    var ended=false
    var text:String {finalText+partialText}
    mutating func apply(_ event:[String:Any]) {
        guard !ended else { return }
        switch event["event"] as? String {
        case "partial":partialText=event["text"] as? String ?? ""
        case "final":finalText += event["text"] as? String ?? "";partialText=""
        case "replace":
            let old=event["old"] as? String ?? "",new=event["text"] as? String ?? ""
            if !old.isEmpty,finalText.hasSuffix(old) {finalText=String(finalText.dropLast(old.count))+new}
        case "error":error=event["text"] as? String ?? "语音插件出错"
        case "end":ended=true
        default:break
        }
    }
}
