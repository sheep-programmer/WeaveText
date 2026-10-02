import AppKit
import SwiftUI
import WeaveCore

final class HandwritingModel: ObservableObject {
    @Published var candidates:[Candidate]=[]
    @Published var message="写一个字，点选候选上屏"
    @Published var clearTick=0
    @Published var multi=false
    weak var owner:WeaveInputController?
    private var generation=0
    private let worker=DispatchQueue(label:"WeaveText.handwriting",qos:.userInitiated)
    func beginStroke() {generation += 1;candidates=[];message="正在书写…"}
    func recognize(_ strokes:[[[Float]]]) {
        guard let engine=EngineHost.shared.engine,let owner,EngineHost.shared.activeController === owner else{return}
        generation += 1;let token=generation
        message="正在识别…";candidates=[]
        worker.async {[weak self] in
            let result=engine.features(["op":"handRecognize","strokes":strokes])
            DispatchQueue.main.async {
                guard let self,self.generation==token,self.owner === owner,EngineHost.shared.activeController === owner else{return}
                let ok=result.bool("ok") && engine.features(["op":"handApply","strokes":strokes,"codes":result["codes"] ?? []]).bool("ok")
                self.candidates=ok ? engine.candidates(offset:0,limit:12) : []
                self.message=self.candidates.isEmpty ? "请继续书写，或清空重写" : "点选候选上屏"
            }
        }
    }
    func choose(_ candidate:Candidate,index:Int) {
        guard let owner,EngineHost.shared.activeController === owner,let engine=EngineHost.shared.engine,engine.candidates(offset:index,limit:1).first?.text==candidate.text else{return}
        owner.commitHandCandidate(index)
        clear()
    }
    func clear() {
        generation += 1;clearTick += 1;candidates=[]
        message=multi ? "从左到右写 2–4 个字，字间留空；点选候选上屏" : "写一个字，点选候选上屏"
        EngineHost.shared.engine?.clear()
    }
    func changeMode() {
        clear();EngineHost.shared.engine?.features(["op":"setHandLine","on":multi])
    }
}
final class HandwritingWindow:NSObject,NSWindowDelegate {
    static let shared=HandwritingWindow()
    private var window:NSPanel?
    private let model=HandwritingModel()
    private var previousSchema="pinyin"
    func show(owner:WeaveInputController) {
        guard EngineHost.shared.engine?.hasSchema("hand")==true else{return}
        if window?.isVisible == true {dismiss()}
        owner.finishComposition()
        model.owner=owner
        previousSchema=EngineHost.shared.chinese ? EngineHost.shared.scheme.id : "english"
        if window==nil {
            let panel=NSPanel(contentRect:NSRect(x:0,y:0,width:520,height:360),styleMask:[.titled,.closable,.nonactivatingPanel,.resizable],backing:.buffered,defer:false)
            panel.title="织文手写";panel.level = .floating;panel.hidesOnDeactivate=false;panel.isReleasedWhenClosed=false
            panel.contentView=NSHostingView(rootView:HandwritingView(model:model));panel.delegate=self
            window=panel;panel.center()
        }
        model.clear();EngineHost.shared.engine?.setSchema("hand")
        EngineHost.shared.engine?.features(["op":"setHandLine","on":model.multi])
        window?.orderFrontRegardless()
    }
    func dismiss(owner:WeaveInputController? = nil) {
        guard model.owner != nil,owner == nil || model.owner === owner else{return}
        model.clear();model.owner=nil;window?.orderOut(nil)
        EngineHost.shared.engine?.setSchema(previousSchema)
    }
    func windowWillClose(_ notification:Notification) {dismiss()}
}
struct HandwritingView:View {
    @ObservedObject var model:HandwritingModel
    var body:some View {
        VStack(spacing:10) {
            HStack {
                Toggle("多字连写",isOn:$model.multi).onChange(of:model.multi) {_ in model.changeMode()}
                Spacer();Button("清空") {model.clear()}
            }
            HandCanvas(clearTick:model.clearTick,onBegin:model.beginStroke,onStrokes:model.recognize)
                .frame(minHeight:210).background(Color(nsColor:.textBackgroundColor))
                .overlay(RoundedRectangle(cornerRadius:8).stroke(Color.secondary.opacity(0.4)))
            ScrollView(.horizontal) {
                HStack {ForEach(Array(model.candidates.enumerated()),id:\.offset) {index,c in
                    Button(c.text){model.choose(c,index:index)}.font(.title2)
                }}
            }
            Text(model.message).font(.callout).foregroundStyle(.secondary)
        }.padding(16).background(Color(nsColor:.windowBackgroundColor))
    }
}
private struct HandCanvas:NSViewRepresentable {
    var clearTick:Int
    var onBegin:()->Void
    var onStrokes:([[[Float]]])->Void
    func makeNSView(context:Context)->InkView {let view=InkView();view.onStrokes=onStrokes;view.onBegin=onBegin;return view}
    func updateNSView(_ view:InkView,context:Context) {view.onStrokes=onStrokes;view.onBegin=onBegin;if view.clearTick != clearTick {view.clearTick=clearTick;view.strokes=[];view.needsDisplay=true}}
}
private final class InkView:NSView {
    override var isFlipped:Bool {true}
    var clearTick=0
    var strokes:[[[Float]]]=[]
    var onBegin:()->Void={}
    var onStrokes:([[[Float]]])->Void={_ in}
    override func acceptsFirstMouse(for event:NSEvent?)->Bool {true}
    override func mouseDown(with event:NSEvent) {guard strokes.count<128 else{return};onBegin();strokes.append([]);append(event)}
    override func mouseDragged(with event:NSEvent) {append(event)}
    override func mouseUp(with event:NSEvent) {append(event);onStrokes(strokes)}
    private func append(_ event:NSEvent) {
        guard !strokes.isEmpty,strokes.last!.count<4096 else{return}
        let point=convert(event.locationInWindow,from:nil)
        strokes[strokes.count-1].append([Float(point.x),Float(point.y)]);needsDisplay=true
    }
    override func draw(_ dirtyRect:NSRect) {
        NSColor.labelColor.setStroke()
        for stroke in strokes where !stroke.isEmpty {
            let path=NSBezierPath();path.lineWidth=3;path.lineCapStyle = .round;path.lineJoinStyle = .round
            path.move(to:NSPoint(x:CGFloat(stroke[0][0]),y:CGFloat(stroke[0][1])))
            for p in stroke.dropFirst(){path.line(to:NSPoint(x:CGFloat(p[0]),y:CGFloat(p[1])))}
            path.stroke()
        }
    }
}
