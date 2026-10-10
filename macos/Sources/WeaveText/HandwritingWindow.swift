import AppKit
import SwiftUI
import WeaveCore

final class HandwritingModel: ObservableObject {
    @Published var candidates:[Candidate]=[]
    @Published var message="点选上屏，或停笔等待自动上屏"
    @Published var clearTick=0
    @Published var undoTick=0
    @Published var hasInk=false
    @Published var multi=Preferences.shared.handLine {didSet {Preferences.shared.handLine=multi}}
    weak var owner:WeaveInputController?
    private var generation=0
    private var idle:DispatchWorkItem?
    private let worker=DispatchQueue(label:"WeaveText.handwriting",qos:.userInitiated)
    private var observer:NSObjectProtocol?
    init() {
        observer=NotificationCenter.default.addObserver(forName:Preferences.didChange,object:nil,queue:.main) {[weak self] _ in
            guard let self,self.multi != Preferences.shared.handLine else {return}
            self.multi=Preferences.shared.handLine
        }
    }
    deinit {if let observer {NotificationCenter.default.removeObserver(observer)}}
    func beginStroke() {idle?.cancel();generation += 1;candidates=[];hasInk=true;message="正在书写…"}
    func undoStroke() {idle?.cancel();undoTick += 1}
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
                self.message=self.candidates.isEmpty ? "没认出来，请继续书写或清空重写" : "点选上屏，或停笔等待自动上屏"
                self.scheduleIdleCommit(token)
            }
        }
    }
    /// 停笔后自动上屏首选：时间来自设置（单字 0.6–1.4 秒），连写字间要留空，等两倍。 Commit the top candidate after the pen rests.
    private func scheduleIdleCommit(_ token:Int) {
        idle?.cancel()
        guard !candidates.isEmpty else{return}
        let work=DispatchWorkItem {[weak self] in
            guard let self,self.generation==token,let first=self.candidates.first else{return}
            self.choose(first,index:0)
        }
        idle=work
        let delay=Preferences.shared.handPause.seconds
        DispatchQueue.main.asyncAfter(deadline:.now()+(multi ? delay*2 : delay),execute:work)
    }
    func choose(_ candidate:Candidate,index:Int) {
        guard let owner,EngineHost.shared.activeController === owner,let engine=EngineHost.shared.engine,engine.candidates(offset:index,limit:1).first?.text==candidate.text else{return}
        owner.commitHandCandidate(index)
        clear()
    }
    func clear() {
        idle?.cancel();generation += 1;clearTick += 1;candidates=[];hasInk=false
        message=multi ? "从左到右写 2–4 个字，字间留空，写完停笔自动上屏" : "写一个字，停笔自动上屏"
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
    func show(owner:WeaveInputController) {
        guard Preferences.shared.extensionEnabled("scheme:hand") else { return }
        guard EngineHost.shared.engine?.hasSchema("hand")==true else{return}
        if window?.isVisible == true {dismiss()}
        owner.finishComposition()
        model.owner=owner
        if window==nil {
            let panel=InputPanel(contentRect:NSRect(x:0,y:0,width:520,height:390),styleMask:[.titled,.closable,.nonactivatingPanel,.resizable],backing:.buffered,defer:false)
            panel.title="织文手写";panel.level = .floating;panel.hidesOnDeactivate=false;panel.isReleasedWhenClosed=false
            panel.becomesKeyOnlyIfNeeded=true
            panel.collectionBehavior=[.canJoinAllSpaces,.fullScreenAuxiliary,.ignoresCycle]
            panel.contentView=ClickThroughHostingView(rootView:HandwritingView(model:model));panel.delegate=self
            WindowAppearance.shared.track(panel, extensionKey:"scheme:hand")
            window=panel;panel.center()
        }
        model.clear();EngineHost.shared.engine?.setSchema("hand")
        EngineHost.shared.engine?.features(["op":"setHandLine","on":model.multi])
        window?.orderFrontRegardless()
    }
    func dismiss(owner:WeaveInputController? = nil) {
        guard model.owner != nil,owner == nil || model.owner === owner else{return}
        model.clear();model.owner=nil;window?.orderOut(nil)
        let host=EngineHost.shared
        host.engine?.setSchema(host.chinese ? host.scheme.id : "english")
    }
    func windowWillClose(_ notification:Notification) {dismiss()}
}
struct HandwritingView:View {
    @ObservedObject var model:HandwritingModel
    @ObservedObject var prefs:Preferences = .shared
    var body:some View {
        VStack(spacing:12) {
            HStack(spacing:10) {
                Picker("",selection:$model.multi) {
                    Text("单字").tag(false)
                    Text("连写").tag(true)
                }
                .pickerStyle(.segmented).labelsHidden().frame(width:132)
                .onChange(of:model.multi) {_ in model.changeMode()}
                Spacer()
                Button {model.undoStroke()} label:{Label("撤销一笔",systemImage:"arrow.uturn.backward")}
                    .disabled(!model.hasInk)
                Button {model.clear()} label:{Label("清空",systemImage:"trash")}
                    .disabled(!model.hasInk && model.candidates.isEmpty)
            }
            ZStack {
                HandCanvas(model:model)
                    .background(Color(nsColor:.textBackgroundColor))
                    .clipShape(RoundedRectangle(cornerRadius:12))
                    .overlay(RoundedRectangle(cornerRadius:12).stroke(Color.secondary.opacity(0.28),lineWidth:1))
                if !model.hasInk {
                    VStack(spacing:6) {
                        Image(systemName:"pencil.and.scribble").font(.system(size:30,weight:.light))
                        Text(model.multi ? "从左到右写 2–4 个字，字间留空" : "在这里写字").font(.callout)
                    }
                    .foregroundStyle(.secondary.opacity(0.55)).allowsHitTesting(false)
                }
            }
            .frame(minHeight:200)
            VStack(alignment:.leading,spacing:8) {
                HStack {
                    Text("识别结果").font(.subheadline.weight(.semibold))
                    Spacer()
                    Text(model.message).font(.caption).foregroundStyle(.secondary)
                }
                if model.candidates.isEmpty {
                    Text("写完后这里出现候选字").font(.callout).foregroundStyle(.tertiary)
                        .frame(maxWidth:.infinity,minHeight:44,alignment:.leading)
                } else {
                    ScrollView(.horizontal,showsIndicators:false) {
                        HStack(spacing:8) {
                            ForEach(Array(model.candidates.enumerated()),id:\.offset) {index,c in
                                CandidateChip(text:c.text,pinyin:c.pinyin,index:index+1,primary:index==0) {model.choose(c,index:index)}
                            }
                        }.padding(.vertical,2)
                    }.frame(minHeight:48)
                }
            }
        }.padding(20).background(Theme.palette(prefs.colorTheme).surface).weaveStyle(prefs)
    }
}
/// 候选字：第一个高亮（停笔后自动上屏的就是它），点选即上屏。 A candidate; the first is highlighted — the one a pause commits.
private struct CandidateChip:View {
    let text:String
    let pinyin:String
    let index:Int
    let primary:Bool
    let action:()->Void
    var body:some View {
        Button(action:action) {
            HStack(alignment:.lastTextBaseline,spacing:6) {
                Text("\(index)").font(.caption2).foregroundStyle(primary ? Color.accentColor : .secondary)
                Text(text).font(.system(size:26))
                if primary && !pinyin.isEmpty {Text("(\(pinyin))").font(.system(size:13)).foregroundStyle(.secondary)}
            }
            .padding(.horizontal,14).padding(.vertical,6).frame(minHeight:44)
            .background(RoundedRectangle(cornerRadius:10).fill(primary ? Color.accentColor.opacity(0.16) : Color.secondary.opacity(0.12)))
            .overlay(RoundedRectangle(cornerRadius:10).stroke(primary ? Color.accentColor.opacity(0.55) : Color.clear,lineWidth:1))
            .contentShape(RoundedRectangle(cornerRadius:10))
        }.buttonStyle(.plain)
    }
}
private struct HandCanvas:NSViewRepresentable {
    @ObservedObject var model:HandwritingModel
    func makeNSView(context:Context)->InkView {
        let view=InkView();wire(view);return view
    }
    private func wire(_ view:InkView) {
        view.onStrokes=model.recognize;view.onBegin=model.beginStroke
        view.onEmpty={[model] in model.clear()}
    }
    func updateNSView(_ view:InkView,context:Context) {
        wire(view)
        if view.clearTick != model.clearTick {view.clearTick=model.clearTick;view.strokes=[];view.needsDisplay=true}
        if view.undoTick != model.undoTick {
            view.undoTick=model.undoTick
            // 视图更新中不能改 @Published：放到下一轮。 Published state can't change mid-update.
            DispatchQueue.main.async {view.undoLast()}
        }
    }
}
private final class InkView:NSView {
    override var isFlipped:Bool {true}
    var clearTick=0
    var undoTick=0
    var strokes:[[[Float]]]=[]
    var onBegin:()->Void={}
    var onStrokes:([[[Float]]])->Void={_ in}
    var onEmpty:()->Void={}
    override func acceptsFirstMouse(for event:NSEvent?)->Bool {true}
    override func mouseDown(with event:NSEvent) {guard strokes.count<128 else{return};onBegin();strokes.append([]);append(event)}
    override func mouseDragged(with event:NSEvent) {append(event)}
    override func mouseUp(with event:NSEvent) {append(event);onStrokes(strokes)}
    func undoLast() {
        guard !strokes.isEmpty else{return}
        strokes.removeLast();needsDisplay=true
        if strokes.isEmpty {onEmpty()} else {onStrokes(strokes)}
    }
    private func append(_ event:NSEvent) {
        guard !strokes.isEmpty,strokes.last!.count<4096 else{return}
        let point=convert(event.locationInWindow,from:nil)
        strokes[strokes.count-1].append([Float(point.x),Float(point.y)]);needsDisplay=true
    }
    override func draw(_ dirtyRect:NSRect) {
        // 米字格：淡淡的虚线，帮助把字写正、写在中间。 A faint dashed grid to keep characters centred.
        let guide=NSBezierPath();guide.lineWidth=1;guide.setLineDash([5,5],count:2,phase:0)
        let r=bounds.insetBy(dx:0.5,dy:0.5)
        guide.move(to:NSPoint(x:r.midX,y:r.minY));guide.line(to:NSPoint(x:r.midX,y:r.maxY))
        guide.move(to:NSPoint(x:r.minX,y:r.midY));guide.line(to:NSPoint(x:r.maxX,y:r.midY))
        NSColor.secondaryLabelColor.withAlphaComponent(0.16).setStroke();guide.stroke()
        NSColor.labelColor.setStroke()
        for stroke in strokes where !stroke.isEmpty {
            let path=NSBezierPath();path.lineWidth=4.5;path.lineCapStyle = .round;path.lineJoinStyle = .round
            let pts=stroke.map{NSPoint(x:CGFloat($0[0]),y:CGFloat($0[1]))}
            path.move(to:pts[0])
            if pts.count==1 {path.line(to:NSPoint(x:pts[0].x+0.01,y:pts[0].y))}
            // 经过相邻点的中点画二次曲线：笔迹平滑，不再是折线。 Quadratic curves through midpoints smooth the polyline.
            for i in 1..<max(pts.count,1) {
                let mid=NSPoint(x:(pts[i-1].x+pts[i].x)/2,y:(pts[i-1].y+pts[i].y)/2)
                path.curve(to:mid,controlPoint1:NSPoint(x:(pts[i-1].x+mid.x)/2,y:(pts[i-1].y+mid.y)/2),controlPoint2:NSPoint(x:(mid.x+pts[i-1].x)/2,y:(mid.y+pts[i-1].y)/2))
                if i==pts.count-1 {path.line(to:pts[i])}
            }
            path.stroke()
        }
    }
}
