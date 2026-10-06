import AppKit
import SwiftUI
import WeaveCore

final class ExpressionsModel:ObservableObject {
    @Published var kind="emoji" {didSet{group="全部";tip.dismiss()}}
    @Published var group="全部" {didSet{tip.dismiss()}}
    @Published var message="点击输入，长按或悬停查看表情名称"
    let catalog:ExpressionCatalog?
    let tip=ExpressionTip()
    init(catalog:ExpressionCatalog? = nil) {
        self.catalog=catalog ?? Bundle.main.resourceURL.flatMap {try? ExpressionCatalog.load($0.appendingPathComponent("expressions/catalog.json"))}
    }
    var entries:[NamedExpression] {kind=="emoji" ? catalog?.emoji ?? [] : catalog?.kaomoji ?? []}
    var groups:[String] {var seen=Set<String>();return ["全部"]+entries.map(\.group).filter{seen.insert($0).inserted}}
    var visible:[NamedExpression] {entries.filter{group=="全部" || $0.group==group}}
    func insert(_ entry:NamedExpression) {
        tip.dismiss()
        if EngineHost.shared.activeController?.commitVoiceText(entry.text)==true {message="已输入 \(entry.name)"}
        else {NSPasteboard.general.clearContents();NSPasteboard.general.setString(entry.text,forType:.string);message="已复制 \(entry.name)，可在目标应用粘贴"}
    }
}
final class ExpressionsWindow:NSObject,NSWindowDelegate {
    static let shared=ExpressionsWindow()
    private var panel:InputPanel?
    let model=ExpressionsModel()
    func show(owner:WeaveInputController? = nil) {
        owner?.finishComposition()
        if panel==nil {
            let p=InputPanel(contentRect:NSRect(x:0,y:0,width:580,height:420),styleMask:[.titled,.closable,.resizable,.nonactivatingPanel],backing:.buffered,defer:false)
            p.title="Emoji 与颜文字";p.level = .floating;p.hidesOnDeactivate=false;p.isReleasedWhenClosed=false
            p.collectionBehavior=[.canJoinAllSpaces,.fullScreenAuxiliary,.ignoresCycle];p.contentMinSize=NSSize(width:400,height:260)
            p.contentView=ClickThroughHostingView(rootView:ExpressionsView(model:model));p.delegate=self;p.center();panel=p
            WindowAppearance.shared.track(p)
        }
        panel?.orderFrontRegardless()
    }
    func windowWillClose(_ notification:Notification) {model.tip.dismiss()}
}
struct ExpressionsView:View {
    @ObservedObject var model:ExpressionsModel
    @ObservedObject var prefs:Preferences = .shared
    var body:some View {
        VStack(alignment:.leading,spacing:12) {
            WindowHeading(title:"表情库",subtitle:"Emoji 与颜文字，点击即可输入",symbol:"face.smiling",prefs:prefs)
            HStack {
                Picker("内容",selection:$model.kind) {Text("Emoji").tag("emoji");Text("颜文字").tag("kaomoji")}.pickerStyle(.segmented).frame(width:200)
                Spacer()
                Picker("分类",selection:$model.group) {ForEach(model.groups,id:\.self) {Text($0).tag($0)}}.frame(width:160)
            }
            ScrollView {
                LazyVGrid(columns:[GridItem(.adaptive(minimum:model.kind=="emoji" ? 48 : 180),spacing:6)],spacing:6) {
                    ForEach(model.visible) {entry in
                        ExpressionCell(entry:entry,emoji:model.kind=="emoji",theme:prefs.colorTheme,tip:model.tip,insert:{model.insert(entry)})
                            .frame(height:model.kind=="emoji" ? 44 : 50)
                    }
                }.padding(4)
            }
            HStack {Text(model.message).font(.caption).foregroundStyle(.secondary);Spacer();Text("\(model.visible.count) 项").font(.caption).foregroundStyle(.secondary)}
        }.padding(20).background(Theme.palette(prefs.colorTheme).surface).weaveStyle(prefs)
    }
}

/// A native cell distinguishes a held press from insertion without taking the editor's focus.
private struct ExpressionCell:NSViewRepresentable {
    let entry:NamedExpression
    let emoji:Bool
    let theme:ColorTheme
    let tip:ExpressionTip
    let insert:()->Void
    func makeNSView(context:Context)->ExpressionCellView {let view=ExpressionCellView();update(view);return view}
    func updateNSView(_ view:ExpressionCellView,context:Context) {update(view)}
    private func update(_ view:ExpressionCellView) {
        view.text=entry.text;view.name=entry.name;view.emoji=emoji;view.theme=theme;view.tip=tip;view.insert=insert
        view.toolTip=entry.name;view.setAccessibilityLabel(entry.name);view.setAccessibilityRole(.button);view.needsDisplay=true
    }
}
final class ExpressionCellView:NSView {
    var text="",name=""
    var emoji=true
    var theme:ColorTheme = .fresh
    weak var tip:ExpressionTip?
    var insert:()->Void={}
    private var held:DispatchWorkItem?
    private var down:NSPoint?
    private var longFired=false
    private var hovering=false
    private var tracking:NSTrackingArea?
    override var acceptsFirstResponder:Bool {false}
    override func acceptsFirstMouse(for event:NSEvent?)->Bool {true}
    override func updateTrackingAreas() {
        super.updateTrackingAreas();if let tracking {removeTrackingArea(tracking)}
        let area=NSTrackingArea(rect:.zero,options:[.mouseEnteredAndExited,.activeAlways,.inVisibleRect],owner:self,userInfo:nil)
        addTrackingArea(area);tracking=area
    }
    override func mouseEntered(with event:NSEvent) {hovering=true;needsDisplay=true}
    override func mouseExited(with event:NSEvent) {hovering=false;needsDisplay=true}
    override func mouseDown(with event:NSEvent) {
        held?.cancel();down=convert(event.locationInWindow,from:nil);longFired=false
        needsDisplay=true
        let work=DispatchWorkItem {[weak self] in
            guard let self,self.down != nil,let window=self.window else{return}
            self.longFired=true
            let font=NSFont.systemFont(ofSize:self.emoji ? 28 : 16)
            let height=(self.text as NSString).size(withAttributes:[.font:font]).height
            let glyph=self.bounds.insetBy(dx:0,dy:max(0,(self.bounds.height-height)/2))
            self.tip?.show(self.name,anchor:window.convertToScreen(self.convert(glyph,to:nil)))
        }
        held=work;DispatchQueue.main.asyncAfter(deadline:.now()+0.4,execute:work)
    }
    override func mouseDragged(with event:NSEvent) {
        let position=convert(event.locationInWindow,from:nil)
        if let down,abs(position.x-down.x)>8 || abs(position.y-down.y)>8 {cancel()}
    }
    override func mouseUp(with event:NSEvent) {
        held?.cancel();held=nil
        let tapped=down != nil && !longFired && bounds.contains(convert(event.locationInWindow,from:nil))
        down=nil;needsDisplay=true;tip?.dismiss();if tapped {insert()}
    }
    override func viewWillMove(toWindow newWindow:NSWindow?) {if newWindow==nil {cancel()};super.viewWillMove(toWindow:newWindow)}
    private func cancel() {held?.cancel();held=nil;down=nil;longFired=false;tip?.dismiss()}
    override func accessibilityPerformPress()->Bool {insert();return true}
    override func draw(_ dirtyRect:NSRect) {
        if hovering || down != nil || !emoji {
            let palette=Theme.palette(theme)
            let dark=effectiveAppearance.bestMatch(from:[.aqua,.darkAqua]) == .darkAqua
            (emoji ? Theme.rgb(dark ? palette.accentHex.1 : palette.accentHex.0).withAlphaComponent(0.12) : NSColor.labelColor.withAlphaComponent(0.04)).setFill()
            NSBezierPath(roundedRect:bounds,xRadius:8,yRadius:8).fill()
        }
        let string=text as NSString
        var font=NSFont.systemFont(ofSize:emoji ? 28 : 16)
        let width=string.size(withAttributes:[.font:font]).width
        if width>bounds.width-8 {font = .systemFont(ofSize:max(9,font.pointSize*(bounds.width-8)/max(1,width)))}
        let paragraph=NSMutableParagraphStyle();paragraph.alignment = .center
        let h=string.size(withAttributes:[.font:font]).height
        string.draw(in:NSRect(x:4,y:(bounds.height-h)/2,width:bounds.width-8,height:h+2),withAttributes:[.font:font,.foregroundColor:NSColor.labelColor,.paragraphStyle:paragraph])
    }
}
final class ExpressionTip {
    private var panel:InputPanel?
    private(set) var text=""
    func show(_ text:String,anchor:NSRect) {
        self.text=text
        let content=Text(text).font(.system(size:13)).padding(.horizontal,12).padding(.vertical,8)
            .background(Color(nsColor:.windowBackgroundColor)).clipShape(RoundedRectangle(cornerRadius:8))
        let hosting=NSHostingView(rootView:content)
        if panel==nil {
            let p=InputPanel(contentRect:.zero,styleMask:[.borderless,.nonactivatingPanel],backing:.buffered,defer:false)
            p.level = .popUpMenu;p.isOpaque=false;p.backgroundColor = .clear;p.hasShadow=true;p.ignoresMouseEvents=true;p.hidesOnDeactivate=false
            p.collectionBehavior=[.canJoinAllSpaces,.fullScreenAuxiliary,.ignoresCycle];panel=p
        }
        guard let panel else{return}
        panel.contentView=hosting
        WindowAppearance.shared.track(panel)
        let size=hosting.fittingSize,screen=NSScreen.screens.first{$0.visibleFrame.intersects(anchor)}?.visibleFrame ?? NSScreen.main?.visibleFrame ?? anchor
        let x=min(max(screen.minX,anchor.midX-size.width/2),screen.maxX-size.width)
        let y=min(max(screen.minY,anchor.maxY+3),screen.maxY-size.height)
        panel.setFrame(NSRect(x:x,y:y,width:size.width,height:size.height),display:true);panel.orderFrontRegardless()
    }
    func dismiss() {text="";panel?.orderOut(nil)}
}
