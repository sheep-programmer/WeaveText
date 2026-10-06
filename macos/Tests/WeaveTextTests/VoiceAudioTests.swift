import AppKit
import AVFoundation
import Testing
import SwiftUI
import WeaveCore
@testable import WeaveText

@MainActor @Suite struct VoiceAudioTests {
    @Test func microphoneSamplesConvertToTheSharedPluginsPCMFormat() throws {
        let input=try #require(AVAudioFormat(commonFormat:.pcmFormatFloat32,sampleRate:48000,channels:2,interleaved:false))
        let converter=try #require(VoicePCMConverter(input:input))
        let buffer=try #require(AVAudioPCMBuffer(pcmFormat:input,frameCapacity:4800));buffer.frameLength=4800
        let samples=try #require(buffer.floatChannelData)
        for c in 0..<2 {for i in 0..<4800 {samples[c][i]=0.1}}
        let pcm=try converter.convert(buffer)
        #expect(pcm.count>2000 && pcm.count<=3400 && pcm.count%2==0)
    }
    @Test func streamingResultsAndLateReplacementsKeepTheRightDraft() {
        var r=VoiceResult(id:"test",name:"测试")
        r.apply(["event":"partial","text":"临时"]);#expect(r.text=="临时")
        r.apply(["event":"final","text":"你好"]);#expect(r.text=="你好")
        r.apply(["event":"final","text":"世界"])
        r.apply(["event":"end"])
        r.apply(["event":"replace","old":"世界","text":"织文"])
        #expect(r.ended && r.text=="你好织文")
        r.apply(["event":"replace","old":"不匹配","text":"不能插入"]);#expect(r.text=="你好织文")
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
