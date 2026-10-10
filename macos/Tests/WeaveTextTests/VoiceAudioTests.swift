import AppKit
import AVFoundation
import Testing
import SwiftUI
import WeaveCore
@testable import WeaveText

private final class VoiceAudioProbe: VoiceSessionHandle, @unchecked Sendable {
    private let lock = NSLock()
    private var feeds = 0
    var feedCount: Int { lock.lock(); defer { lock.unlock() }; return feeds }
    func feed(_ data: Data) { lock.lock(); feeds += 1; lock.unlock() }
    func stop() {}
    func cancel() {}
}

@MainActor @Suite struct VoiceAudioTests {
    @Test func draftEditingUsesAKeyWindowWhileRecordingPanelKeepsTheInputFocus() {
        _ = NSApplication.shared
        let model = VoiceModel()
        model.text = "可以修改的语音草稿"
        let recording = VoiceWindow.panel(model: model)
        let editing = VoiceDraftWindow.makeWindow(model: model)
        #expect(!recording.canBecomeKey && !recording.canBecomeMain)
        #expect(editing.canBecomeKey)
        #expect(!editing.styleMask.contains(.nonactivatingPanel))
        #expect(editing.contentView is NSHostingView<VoiceDraftView>)
        editing.close(); recording.close()
    }
    @Test func microphoneSamplesConvertToTheSharedPluginsPCMFormat() throws {
        let input=try #require(AVAudioFormat(commonFormat:.pcmFormatFloat32,sampleRate:48000,channels:2,interleaved:false))
        let converter=try #require(VoicePCMConverter(input:input))
        let buffer=try #require(AVAudioPCMBuffer(pcmFormat:input,frameCapacity:4800));buffer.frameLength=4800
        let samples=try #require(buffer.floatChannelData)
        for c in 0..<2 {for i in 0..<4800 {samples[c][i]=0.1}}
        let pcm=try converter.convert(buffer)
        #expect(pcm.count>2000 && pcm.count<=3400 && pcm.count%2==0)
    }
    @Test func endingAudioRejectsBuffersDeliveredByAnOldTapWhileSystemEndKeepsPluginsRunning() throws {
        let format = try #require(AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 48000, channels: 1, interleaved: false))
        let converter = try #require(VoicePCMConverter(input: format))
        let buffer = try #require(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 4800))
        buffer.frameLength = 4800
        let samples = try #require(buffer.floatChannelData)
        for i in 0..<4800 { samples[0][i] = 0.1 }
        let scope = VoiceSessionScope(), probe = VoiceAudioProbe()
        #expect(scope.register(probe, id: "test"))
        let input = VoiceAudioInput(request: nil, converter: converter, plugins: scope)
        try input.append(buffer)
        #expect(probe.feedCount == 1)
        input.finishSystem()
        try input.append(buffer)
        #expect(probe.feedCount == 2)
        input.finish(); input.finish()
        try input.append(buffer)
        #expect(probe.feedCount == 2)
        scope.cancel()
    }
    @Test func streamingReplacementsWorkBeforeEndAndAnEndedResultIsFrozen() {
        var r=VoiceResult(id:"test",name:"测试")
        r.apply(["event":"partial","text":"临时"]);#expect(r.text=="临时")
        r.apply(["event":"final","text":"你好"]);#expect(r.text=="你好")
        r.apply(["event":"final","text":"世界"])
        r.apply(["event":"replace","old":"世界","text":"织文"])
        #expect(!r.ended && r.text=="你好织文")
        r.apply(["event":"replace","old":"不匹配","text":"不能插入"]);#expect(r.text=="你好织文")
        r.apply(["event":"end"])
        let ended = r
        for event in [["event":"replace","old":"织文","text":"迟到"],
                      ["event":"partial","text":"迟到"], ["event":"final","text":"迟到"],
                      ["event":"error","text":"迟到错误"]] { r.apply(event) }
        #expect(r == ended)
    }
    @Test func documentRangeInsertionCanPlaceTheCaretBetweenPairedPunctuation() {
        _=NSApplication.shared
        let text=NSTextView(frame:NSRect(x:0,y:0,width:300,height:100));text.string="AB";text.setSelectedRange(NSRange(location:1,length:0))
        text.insertText("（）",replacementRange:NSRange(location:NSNotFound,length:0))
        #expect(text.string=="A（）B")
        text.setMarkedText("",selectedRange:NSRange(location:0,length:0),replacementRange:NSRange(location:2,length:0))
        #expect(text.selectedRange()==NSRange(location:2,length:0))
        text.insertText("字",replacementRange:NSRange(location:NSNotFound,length:0))
        #expect(text.string=="A（字）B")
    }
    @Test func longCandidatesFitTheDisplayWidthWithoutLosingPageControls() {
        _=NSApplication.shared
        let state=CandidateState(preedit:String(repeating:"suhju",count:40),candidates:(0..<9).map {_ in Candidate(text:String(repeating:"长句候选",count:20))},highlight:0,hasPrevious:true,hasNext:true,orientation:.vertical,fontSize:28)
        let view=NSHostingView(rootView:CandidateBar(state:state,pick:{_ in},widthLimit:600))
        view.layoutSubtreeIfNeeded()
        #expect(view.fittingSize.width<=601)
        #expect(view.fittingSize.height>200 && view.fittingSize.height<700)
    }
}
