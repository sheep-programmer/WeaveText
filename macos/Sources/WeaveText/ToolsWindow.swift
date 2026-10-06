import AppKit
import Carbon
import SwiftUI
import UniformTypeIdentifiers
import WeaveCore

final class ToolsModel: ObservableObject {
    static let shared = ToolsModel()
    @Published var history: [ContentItem] = []
    @Published var phrases: [ContentItem] = []
    @Published var snippets: [(String,String)] = []
    @Published var current = ""
    @Published var tab = "clipboard"
    @Published var draft = ""
    @Published var query = ""
    @Published var message = ""
    @Published var confirmClear = false
    private let queue=DispatchQueue(label:"weave.content-history",qos:.utility)
    private let store:ContentHistory
    private let phraseStore:ContentHistory
    private var timer:Timer?
    private var lastChange=NSPasteboard.general.changeCount
    private var recording=false
    private var observer:NSObjectProtocol?
    private var isPreview=false
    init(directory:URL=EngineHost.userDirectory()) {
        store=ContentHistory(directory:directory.appendingPathComponent("clipboard"))
        phraseStore=ContentHistory(directory:directory.appendingPathComponent("phrases"),maximum:500,ttl:0)
    }
    var visibleHistory:[ContentItem] {history.filter{query.isEmpty || $0.text.localizedCaseInsensitiveContains(query)}}
    func start() {
        guard timer==nil else {return}
        updateRecording()
        observer=NotificationCenter.default.addObserver(forName:Preferences.didChange,object:nil,queue:.main) {[weak self] _ in self?.updateRecording()}
        timer=Timer.scheduledTimer(withTimeInterval:0.75,repeats:true) {[weak self] _ in self?.tick()}
    }
    private func updateRecording() {
        let on=Preferences.shared.clipboardRecord
        if on && !recording {lastChange=NSPasteboard.general.changeCount-1}
        recording=on
        tick()
    }
    private func safeText()->String? {
        guard !IsSecureEventInputEnabled(),!(NSPasteboard.general.types ?? []).contains(where:{ClipboardGuard.skippedTypes.contains($0.rawValue)}) else {return nil}
        return NSPasteboard.general.string(forType:.string)
    }
    private func tick() {
        let pb=NSPasteboard.general
        guard pb.changeCount != lastChange else {return}
        lastChange=pb.changeCount
        guard recording,!IsSecureEventInputEnabled(),!(pb.types ?? []).contains(where:{ClipboardGuard.skippedTypes.contains($0.rawValue)}) else {return}
        if let urls=pb.readObjects(forClasses:[NSURL.self],options:[.urlReadingFileURLsOnly:true]) as? [URL],!urls.isEmpty {perform {try $0.addFiles(urls)}}
        else if let data=pb.data(forType:.png) ?? pb.data(forType:.tiff) {
            guard data.count<=64*1024*1024 else {return}
            let ext=pb.availableType(from:[.png])==nil ? "tiff" : "png"
            perform {store in
                let temp=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString+"."+ext)
                defer {try? FileManager.default.removeItem(at:temp)}
                try data.write(to:temp);try store.addFiles([temp])
            }
        } else if let text=pb.string(forType:.string) {perform {try $0.add(text)}}
    }
    func reload() {
        guard !isPreview else {return}
        current=safeText() ?? ""
        snippets=(EngineHost.shared.engine?.features(["op":"snippets"]).objects("items") ?? []).map {($0.str("code"),$0.str("text"))}
        perform {_ in}
    }
    func preview(history:[ContentItem],phrases:[ContentItem]) {
        isPreview=true;self.history=history;self.phrases=phrases
        snippets=[("dz","今天 {date}，收到后请回复。")]
    }
    private func perform(_ operation:@escaping(ContentHistory)throws->Void,phrases:Bool=false) {
        let historyStore=store,phrasesStore=phraseStore,target=phrases ? phraseStore : store
        queue.async {[weak self] in
            do {
                try operation(target)
                let h=try historyStore.list(),p=try phrasesStore.list()
                DispatchQueue.main.async {self?.history=h;self?.phrases=p}
            } catch {DispatchQueue.main.async {self?.message=error.localizedDescription}}
        }
    }
    func addPhrase() {let value=draft;draft="";perform({try $0.add(value)},phrases:true)}
    func phraseFrom(_ text:String) {perform({try $0.add(text)},phrases:true)}
    func pin(_ item:ContentItem,phrase:Bool=false) {perform({try $0.pin(item.id)},phrases:phrase)}
    func delete(_ item:ContentItem,phrase:Bool=false) {perform({try $0.delete(item.id)},phrases:phrase)}
    func clear() {perform {try $0.clear()}}
    func copy(_ item:ContentItem) {
        let pb=NSPasteboard.general;pb.clearContents()
        if item.files.isEmpty {pb.setString(item.text,forType:.string)}
        else {pb.writeObjects(item.files.map{store.directory.appendingPathComponent($0) as NSURL})}
        message="已复制"
    }
    func use(_ item:ContentItem) {
        if item.files.isEmpty {insert(item.text)} else {copy(item);message="已复制图片或文件，在目标应用按 ⌘V 粘贴"}
    }
    func insert(_ text:String) {
        guard EngineHost.shared.activeController?.commitVoiceText(text)==true else {message="请先点选输入位置，再从输入法菜单打开输入工具";return}
        message="已上屏"
    }
    func insertSnippet(_ text:String) {
        let expanded=EngineHost.shared.engine?.features(["op":"expandSnippet","text":text]).str("text") ?? text
        insert(expanded)
    }
    func exportPersonal() {
        let picker=NSSavePanel();picker.allowedContentTypes=[.json];picker.nameFieldStringValue="织文个人资料.json"
        picker.begin {[weak self] response in
            guard response == .OK,let url=picker.url,let data=EngineHost.shared.engine?.features(["op":"exportPersonal"])["data"] as? String else {return}
            self?.queue.async {do {try Data(data.utf8).write(to:url,options:.atomic);DispatchQueue.main.async {self?.message="已导出用户词、候选习惯与快捷模板"}}
                catch {DispatchQueue.main.async {self?.message=error.localizedDescription}}}
        }
    }
    func importPersonal() {
        let picker=NSOpenPanel();picker.allowedContentTypes=[.json];picker.allowsMultipleSelection=false
        picker.begin {[weak self] response in
            guard response == .OK,let url=picker.url else {return}
            self?.queue.async {
                do {
                    let size=try url.resourceValues(forKeys:[.fileSizeKey]).fileSize ?? 0
                    guard size<=8*1024*1024 else {throw PluginFailure("个人资料超过大小限制")}
                    let text=try String(contentsOf:url,encoding:.utf8)
                    DispatchQueue.main.async {
                        let result=EngineHost.shared.engine?.features(["op":"importPersonal","data":text]) ?? [:]
                        self?.message=result.bool("ok") ? "已合并个人资料" : result.str("error").isEmpty ? "个人资料格式无效或当前输入尚未完成" : result.str("error")
                        self?.reload()
                    }
                } catch {DispatchQueue.main.async {self?.message=error.localizedDescription}}
            }
        }
    }
}

final class ToolsWindow {
    static let shared=ToolsWindow()
    private var panel:InputPanel?
    func show(owner:WeaveInputController,tab:String="clipboard") {
        owner.finishComposition();let model=ToolsModel.shared;model.tab=tab;model.reload()
        if panel==nil {
            let p=InputPanel(contentRect:NSRect(x:0,y:0,width:540,height:420),styleMask:[.titled,.closable,.resizable,.nonactivatingPanel],backing:.buffered,defer:false)
            p.title="织文输入工具";p.level = .floating;p.hidesOnDeactivate=false;p.isReleasedWhenClosed=false
            p.collectionBehavior=[.canJoinAllSpaces,.fullScreenAuxiliary,.ignoresCycle]
            p.contentMinSize=NSSize(width:380,height:280)
            p.contentView=ClickThroughHostingView(rootView:ToolsView(model:model,prefs:.shared))
            WindowAppearance.shared.track(p)
            p.center();panel=p
        }
        panel?.orderFrontRegardless()
    }
}
struct ToolsView:View {
    @ObservedObject var model:ToolsModel
    @ObservedObject var prefs:Preferences
    var body:some View {
        VStack(alignment:.leading,spacing:12) {
            WindowHeading(title:"输入工具",subtitle:"常用内容随手取用",symbol:"tray.full",prefs:prefs)
            Picker("工具",selection:$model.tab) {Text("剪贴板").tag("clipboard");Text("常用语").tag("phrases");Text("快捷模板").tag("snippets")}.pickerStyle(.segmented)
            ScrollView {
                LazyVStack(alignment:.leading,spacing:10) {
                    if model.tab=="clipboard" {
                        if !prefs.clipboardRecord {
                            Text("历史记录未开启，仅显示当前剪贴板。").font(.callout).foregroundStyle(.secondary)
                            if !model.current.isEmpty {Button {model.insert(model.current)} label:{Text(model.current).lineLimit(4).frame(maxWidth:.infinity,alignment:.leading)}}
                        } else {
                            ForEach(model.visibleHistory) {ContentRow(item:$0,model:model)}
                            if model.history.isEmpty {Text("还没有剪贴板记录").foregroundStyle(.secondary)}
                        }
                    } else if model.tab=="phrases" {
                        ForEach(model.phrases) {ContentRow(item:$0,model:model,phrase:true)}
                        if model.phrases.isEmpty {Text("在「输入工具」设置中添加常用语，也可将剪贴板文字收藏为常用语。").foregroundStyle(.secondary)}
                    } else {
                        ForEach(Array(model.snippets.enumerated()),id:\.offset) {_,item in
                            Button {model.insertSnippet(item.1)} label:{VStack(alignment:.leading) {Text(item.0).font(.caption).foregroundStyle(.secondary);Text(item.1).lineLimit(4)}.frame(maxWidth:.infinity,alignment:.leading)}
                        }
                        if model.snippets.isEmpty {Text("在「词库 → 快捷短语与模板」设置输入码和内容。").foregroundStyle(.secondary)}
                    }
                }.padding(4)
            }
            Text(model.message).font(.caption).foregroundStyle(.secondary)
            HStack {Button("刷新") {model.reload()};Spacer();Button("管理…") {SettingsWindow.shared.show(page:.tools)}}
        }.padding(20).background(Theme.palette(prefs.colorTheme).surface).weaveStyle(prefs)
    }
}
private struct ContentRow:View {
    let item:ContentItem
    @ObservedObject var model:ToolsModel
    var phrase=false
    var body:some View {
        HStack(spacing:8) {
            Button {model.use(item)} label:{HStack {if !item.files.isEmpty {Image(systemName:"doc.on.clipboard")};Text(item.text).lineLimit(3).frame(maxWidth:.infinity,alignment:.leading)}}.buttonStyle(.plain)
            Button {model.copy(item)} label:{Image(systemName:"doc.on.doc")}.help("复制")
            Button {model.pin(item,phrase:phrase)} label:{Image(systemName:item.pinned ? "pin.fill" : "pin")}.help("固定 / 取消固定")
            if !phrase && item.files.isEmpty {Button {model.phraseFrom(item.text)} label:{Image(systemName:"text.badge.plus")}.help("收藏为常用语")}
            Button {model.delete(item,phrase:phrase)} label:{Image(systemName:"trash")}.help("删除")
        }.padding(10).background(Color.secondary.opacity(0.08),in:RoundedRectangle(cornerRadius:8))
    }
}
struct ToolsPage:View {
    @ObservedObject var model:ToolsModel
    @ObservedObject var prefs:Preferences
    var body:some View {
        Form {
            Section {
                Toggle("记录剪贴板历史",isOn:$prefs.clipboardRecord)
                TextField("搜索历史",text:$model.query)
                ForEach(model.visibleHistory) {ContentRow(item:$0,model:model)}
                Button("清空全部历史…",role:.destructive) {model.confirmClear=true}.disabled(model.history.isEmpty)
            } header:{Text("剪贴板")} footer:{Footnote("默认不记录。开启后保留 50 条未固定内容，24 小时过期；固定内容保留。敏感标记与安全输入期间的内容不会记录。文本单条最多 10 KB，图片和文件总量最多 256 MB。")}
            Section {
                TextEditor(text:$model.draft).frame(minHeight:70)
                Button("添加常用语") {model.addPhrase()}.disabled(model.draft.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty)
                ForEach(model.phrases) {ContentRow(item:$0,model:model,phrase:true)}
            } header:{Text("常用语")}
            Section {
                Button("导出个人资料…") {model.exportPersonal()}
                Button("导入并合并个人资料…") {model.importPersonal()}
            } header:{Text("个人资料备份")} footer:{Footnote("包含用户词、候选习惯、手写学习与快捷模板；两端可导入同一格式。")}
            if !model.message.isEmpty {Text(model.message).textSelection(.enabled)}
        }.formStyle(.grouped).onAppear{model.reload()}
        .alert("清空剪贴板历史？",isPresented:$model.confirmClear) {Button("清空",role:.destructive) {model.clear()};Button("取消",role:.cancel) {}} message:{Text("固定项和保存的图片、文件也会删除。常用语不受影响。")}
    }
}
