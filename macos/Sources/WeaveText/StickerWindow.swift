import AppKit
import SwiftUI
import UniformTypeIdentifiers
import ApplicationServices
import WeaveCore

struct StickerDeletionRequest: Identifiable, Equatable {
    let id = UUID()
    let ids: Set<String>
    var count: Int { ids.count }
}

final class StickerCollectionModel:ObservableObject {
    @Published var query="" {didSet {reload()}}
    @Published var filter="all" {didSet {reload()}}
    @Published var items:[Sticker]=[]
    @Published var groups:[String]=[]
    @Published var message="拖入图片收纳；点击复制并粘贴，或拖出到聊天框"
    @Published var editing:Sticker?
    @Published var selected=Set<String>()
    @Published var selecting=false
    @Published var highlighted=false
    @Published var compact=false
    @Published private(set) var deletionRequest: StickerDeletionRequest?
    @Published var confirmDelete = false
    @Published private(set) var deleting = false
    private let queue=DispatchQueue(label:"WeaveText.stickers",qos:.userInitiated)
    private let store:StickerStore?
    weak var owner:WeaveInputController?
    var target:NSRunningApplication?
    init(directory:URL=EngineHost.userDirectory().appendingPathComponent("stickers",isDirectory:true)) {
        do {store=try StickerStore(directory:directory)}catch {store=nil;message=error.localizedDescription}
        reload()
    }
    init(store: StickerStore) { self.store = store; reload() }
    func reload() {items=store?.list(query:query,filter:filter) ?? [];groups=store?.groups() ?? [];selected.formIntersection(Set(items.map(\.id)))}
    func file(_ item:Sticker)->URL? {try? store?.file(item)}
    func collect(_ urls:[URL]) {
        guard let store else{return};message="正在收纳…"
        queue.async {[weak self] in
            var added=0;var duplicates=0;var failed=0
            for url in urls.prefix(100){do {let (_,fresh)=try store.importFile(url);if fresh {added+=1}else{duplicates+=1}}catch {failed+=1}}
            DispatchQueue.main.async {self?.message="已收纳 \(added) 张 · 重复 \(duplicates) 张"+(failed>0 ? " · 无法读取 \(failed) 张，请保存或分享原图后再导入" : "");self?.reload()}
        }
    }
    func importPictures() {
        let panel=NSOpenPanel();panel.allowsMultipleSelection=true;panel.canChooseDirectories=false
        panel.allowedContentTypes=[.png,.jpeg,.gif,.webP,.bmp];panel.prompt="收纳"
        if panel.runModal() == .OK {collect(panel.urls)}
    }
    func fromClipboard() {
        let pb=NSPasteboard.general
        if let urls=pb.readObjects(forClasses:[NSURL.self],options:[.urlReadingFileURLsOnly:true]) as? [URL],!urls.isEmpty {collect(urls);return}
        // Prefer original representations; converting an animated sticker to PNG would discard frames.
        let types:[NSPasteboard.PasteboardType]=[.init(UTType.gif.identifier),.init(UTType.webP.identifier),.png,.init(UTType.jpeg.identifier),.tiff]
        for type in types {if let data=pb.data(forType:type),let store {
            queue.async {[weak self] in do {
                let bytes=type == .tiff ? NSBitmapImageRep(data:data)?.representation(using:.png,properties:[:]) ?? data : data
                let (_,fresh)=try store.importData(bytes,name:"剪贴板表情");DispatchQueue.main.async {self?.message=fresh ? "已收纳剪贴板表情" : "已经收纳过这张表情";self?.reload()}}
                catch {DispatchQueue.main.async {self?.message=error.localizedDescription}}};return
        }}
        message="剪贴板没有可读取的原图，请从原应用保存或拖入"
    }
    func edit(_ item:Sticker,name:String,group:String,tags:String,favorite:Bool) {
        guard let store else{return}
        queue.async {[weak self] in do {try store.edit(item.id,name:name,group:group,tags:tags.components(separatedBy:CharacterSet(charactersIn:",，")),favorite:favorite)
            DispatchQueue.main.async {self?.message="已保存";self?.reload()}}
        catch {DispatchQueue.main.async {self?.message=error.localizedDescription}}}
    }
    func deleteSelected() {
        requestDeletion(selected)
    }
    func requestDeletion(_ ids: Set<String>) {
        guard let store, !deleting, deletionRequest == nil else { return }
        let targets = ids.intersection(Set(store.list().map(\.id)))
        guard !targets.isEmpty else { return }
        deletionRequest = StickerDeletionRequest(ids: targets)
        confirmDelete = true
    }
    func cancelDeletion() {
        guard !deleting else { return }
        confirmDelete = false; deletionRequest = nil
    }
    func confirmDeletion(_ request: StickerDeletionRequest) {
        guard let store, !deleting, deletionRequest?.id == request.id else { return }
        // Consume the request before scheduling IO. A second click or a late alert cannot delete twice.
        deleting = true; confirmDelete = false; deletionRequest = nil
        let ids = request.ids
        queue.async { [weak self] in
            do {
                try store.delete(ids)
                DispatchQueue.main.async {
                    guard let self else { return }
                    self.selected.subtract(ids); self.reload(); self.deleting = false
                    self.message = "已删除 \(request.count) 张表情"
                }
            } catch {
                DispatchQueue.main.async { self?.deleting = false; self?.message = error.localizedDescription }
            }
        }
    }
    func groupSelected() {
        guard let store,!selected.isEmpty else{return}
        let alert=NSAlert();alert.messageText="批量分组";alert.informativeText="输入分组名称，留空移到未分组。"
        let field=NSTextField(frame:NSRect(x:0,y:0,width:260,height:24));alert.accessoryView=field
        alert.addButton(withTitle:"保存");alert.addButton(withTitle:"取消")
        guard alert.runModal() == .alertFirstButtonReturn else{return}
        let ids=selected;let group=field.stringValue
        queue.async {[weak self] in do {try store.group(ids,name:group);DispatchQueue.main.async {self?.reload();self?.message="已保存分组"}}
        catch {DispatchQueue.main.async {self?.message=error.localizedDescription}}}
    }
    func exportArchive() {
        guard let store else{return};let panel=NSSavePanel();panel.nameFieldStringValue="织文表情备份.zip";panel.allowedContentTypes=[.zip]
        guard panel.runModal() == .OK,let url=panel.url else{return}
        message="正在导出表情备份…"
        queue.async {[weak self] in do {try store.export(to:url);DispatchQueue.main.async {self?.message="已导出表情备份"}}
        catch {DispatchQueue.main.async {self?.message=error.localizedDescription}}}
    }
    func importArchive() {
        guard let store else{return};let panel=NSOpenPanel();panel.allowedContentTypes=[.zip]
        guard panel.runModal() == .OK,let url=panel.url else{return}
        message="正在导入表情备份…"
        queue.async {[weak self] in do {let (added,duplicate)=try store.importArchive(url);DispatchQueue.main.async {self?.reload();self?.message="已收纳 \(added) 张 · 重复 \(duplicate) 张"}}
        catch {DispatchQueue.main.async {self?.message=error.localizedDescription;self?.reload()}}}
    }
    func accept(_ providers:[NSItemProvider])->Bool {
        guard let store else{return false}
        let supported=[UTType.fileURL.identifier,UTType.gif.identifier,UTType.webP.identifier,UTType.png.identifier,UTType.jpeg.identifier]
        var accepted=false
        for provider in providers.prefix(100) {
            guard let type=supported.first(where:provider.hasItemConformingToTypeIdentifier) else{continue};accepted=true
            if type==UTType.fileURL.identifier {
                provider.loadItem(forTypeIdentifier:type,options:nil){[weak self] value,error in
                    let url=(value as? URL) ?? (value as? Data).flatMap {URL(dataRepresentation:$0,relativeTo:nil)}
                    guard let url else{DispatchQueue.main.async {self?.message="原应用没有提供可读取的文件"};return}
                    do {let (_,fresh)=try store.importFile(url);DispatchQueue.main.async {self?.reload();self?.message=fresh ? "已收纳拖入的图片" : "这张表情已经收纳过"}}
                    catch {DispatchQueue.main.async {self?.message=error.localizedDescription}}
                }
            } else {
                provider.loadFileRepresentation(forTypeIdentifier:type){[weak self] url,error in
                    guard let url else{DispatchQueue.main.async {self?.message="原应用没有提供原图，请保存后再导入"};return}
                    // The provider's temporary file is only valid during this callback.
                    do {let (_,fresh)=try store.importFile(url,name:provider.suggestedName ?? "拖入的表情");DispatchQueue.main.async {self?.reload();self?.message=fresh ? "已收纳" : "已收纳过"}}
                    catch {DispatchQueue.main.async {self?.message=error.localizedDescription}}
                }
            }
        };return accepted
    }
    func use(_ item:Sticker) {
        if let current=NSWorkspace.shared.frontmostApplication,current.processIdentifier != ProcessInfo.processInfo.processIdentifier {target=current}
        guard let url=file(item),let data=try? Data(contentsOf:url) else{message="表情原文件丢失，请重新导入";return}
        guard StickerPasteboard.write(item,file:url,data:data,to:.general) else{message="无法复制表情，请重试";return}
        queue.async {[weak self] in try? self?.store?.used(item.id);DispatchQueue.main.async {self?.reload()}}
        guard let target,!target.isTerminated,target.processIdentifier != ProcessInfo.processInfo.processIdentifier else{message="已复制原图，回聊天框粘贴；也可以直接拖出";return}
        guard AXIsProcessTrusted() else{message="已复制原图。自动粘贴需要在系统「辅助功能」中允许织文；也可直接拖出发送";return}
        StickerWindow.shared.hideForPaste()
        target.activate(options:.activateIgnoringOtherApps)
        DispatchQueue.main.asyncAfter(deadline:.now()+0.12) {[weak self] in
            guard NSWorkspace.shared.frontmostApplication?.processIdentifier==target.processIdentifier else{self?.message="已复制，请回到聊天框粘贴";return}
            let down=CGEvent(keyboardEventSource:nil,virtualKey:9,keyDown:true);down?.flags = .maskCommand
            let up=CGEvent(keyboardEventSource:nil,virtualKey:9,keyDown:false);up?.flags = .maskCommand
            down?.post(tap:.cghidEventTap);up?.post(tap:.cghidEventTap)
            self?.message="已尝试粘贴，请在聊天应用中确认发送"
        }
    }
}

enum StickerPasteboard {
    static func write(_ item:Sticker,file:URL,data:Data,to pb:NSPasteboard)->Bool {
        let representation=NSPasteboardItem()
        representation.setString(file.absoluteString,forType:.fileURL)
        if let type=UTType(mimeType:item.mime){representation.setData(data,forType:NSPasteboard.PasteboardType(type.identifier))}
        pb.clearContents();return pb.writeObjects([representation])
    }
}

enum StickerWindowLayout {
    static let minimumContentSize = NSSize(width: 340, height: 300)
    static func usesNarrowHeader(width: CGFloat, compact: Bool) -> Bool { compact || width < 560 }
}

final class StickerWindow: NSObject, NSWindowDelegate {
    static let shared = StickerWindow()
    static let frameKey = "weave.stickers.windowFrame"
    static let compactKey = "weave.stickers.compact"
    let model: StickerCollectionModel
    private let defaults: UserDefaults
    private let prefs: Preferences
    private let appearance: WindowAppearance
    private var panel: NSPanel?
    private var restoringFrame = false

    init(model: StickerCollectionModel? = nil, defaults: UserDefaults = .standard, prefs: Preferences? = nil) {
        self.model = model ?? StickerCollectionModel()
        self.defaults = defaults
        let prefs = prefs ?? .shared
        self.prefs = prefs; appearance = WindowAppearance(prefs: prefs)
        super.init()
        self.model.compact = defaults.bool(forKey: Self.compactKey)
    }

    func show(owner: WeaveInputController? = nil) {
        model.owner = owner
        let front = NSWorkspace.shared.frontmostApplication
        if front?.processIdentifier != ProcessInfo.processInfo.processIdentifier { model.target = front }
        let panel = makePanel()
        model.reload(); panel.orderFrontRegardless()
    }

    /// Hosting's default intrinsic/min/max sizes can otherwise override a resizable AppKit window.
    /// Keep the only minimum here, then let all edges/corners resize the hosting view with the panel.
    func makePanel() -> NSPanel {
        if let panel { return panel }
        let p = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 620, height: 510),
                        styleMask: [.titled, .closable, .resizable, .nonactivatingPanel], backing: .buffered, defer: false)
        p.title = "织文表情收纳袋"; p.level = .floating
        p.hidesOnDeactivate = false; p.isReleasedWhenClosed = false
        p.contentMinSize = StickerWindowLayout.minimumContentSize
        p.isMovableByWindowBackground = true
        let hosting = NSHostingView(rootView: StickerCollectionView(model: model, prefs: prefs, toggleCompact: { [weak self] in self?.toggleCompact() }))
        hosting.sizingOptions = []
        hosting.autoresizingMask = [.width, .height]
        hosting.translatesAutoresizingMaskIntoConstraints = true
        p.contentView = hosting
        appearance.track(p)
        panel = p
        restoringFrame = true
        if let saved = defaults.dictionary(forKey: Self.frameKey) as? [String: Double],
           let x = saved["x"], let y = saved["y"], let width = saved["width"], let height = saved["height"],
           [x, y, width, height].allSatisfy({ $0.isFinite }), width >= 340, height >= 300 {
            p.setFrame(NSRect(x: x, y: y, width: width, height: height), display: false)
        } else { p.center() }
        restoringFrame = false
        p.delegate = self
        return p
    }

    func hideForPaste() { panel?.orderOut(nil) }
    /// Compact is a density preference, not a width constraint or a repeated frame preset.
    func toggleCompact() {
        model.compact.toggle()
        defaults.set(model.compact, forKey: Self.compactKey)
    }
    private func saveFrame(_ window: NSWindow) {
        guard !restoringFrame else { return }
        let frame = window.frame
        defaults.set(["x": Double(frame.origin.x), "y": Double(frame.origin.y),
                      "width": Double(frame.width), "height": Double(frame.height)], forKey: Self.frameKey)
    }
    func windowDidResize(_ notification: Notification) { if let window = notification.object as? NSWindow { saveFrame(window) } }
    func windowDidMove(_ notification: Notification) { if let window = notification.object as? NSWindow { saveFrame(window) } }
    func windowWillClose(_ notification: Notification) {
        if let window = notification.object as? NSWindow { saveFrame(window) }
        model.cancelDeletion()
    }
}

struct StickerCollectionView: View {
    @ObservedObject var model: StickerCollectionModel
    @ObservedObject var prefs: Preferences
    private let toggleCompact: () -> Void
    init(model: StickerCollectionModel, prefs: Preferences = .shared, toggleCompact: (() -> Void)? = nil) {
        self.model = model; self.prefs = prefs
        self.toggleCompact = toggleCompact ?? { model.compact.toggle() }
    }
    private var palette: ThemePalette { Theme.palette(prefs.colorTheme) }
    var body: some View {
        GeometryReader { geometry in
            let narrow = StickerWindowLayout.usesNarrowHeader(width: geometry.size.width, compact: model.compact)
            VStack(spacing: 8) {
                header(narrow: narrow)
                HStack(spacing: 8) {
                    TextField("搜索名称、标签或分组", text: $model.query)
                        .textFieldStyle(.roundedBorder).frame(minWidth: 0, maxWidth: .infinity)
                        .accessibilityLabel("搜索表情")
                    Picker("分组", selection: $model.filter) {
                        Text("全部").tag("all"); Text("收藏").tag("favorites"); Text("最近").tag("recent"); Text("未分组").tag("ungrouped")
                        ForEach(model.groups, id: \.self) { Text($0).tag("group:" + $0) }
                    }.pickerStyle(.menu).labelsHidden().frame(width: narrow ? 112 : 160)
                        .accessibilityLabel("表情分组")
                }
                if model.selecting {
                    HStack(spacing: 8) {
                        Text("已选 \(model.selected.count) 张").font(.caption).lineLimit(1)
                        Spacer(minLength: 0)
                        Button("全选") { model.selected = Set(model.items.map(\.id)) }
                        Button("分组") { model.groupSelected() }.disabled(model.selected.isEmpty || model.deleting)
                        Button("删除…", role: .destructive) { model.deleteSelected() }
                            .disabled(model.selected.isEmpty || model.deleting || model.deletionRequest != nil)
                    }.controlSize(.small)
                }
                ScrollView {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: model.compact ? 88 : 110), spacing: 10)], spacing: 10) {
                        ForEach(model.items) { item in sticker(item) }
                    }.padding(.bottom, 4)
                    if model.items.isEmpty {
                        Text("拖入图片收纳，或从文件／剪贴板导入")
                            .font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center)
                            .frame(maxWidth: .infinity).padding(.vertical, 24)
                    }
                }.frame(minHeight: 0, maxHeight: .infinity)
                HStack(spacing: 6) {
                    if model.deleting { ProgressView().controlSize(.small) }
                    Text(model.message).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                        .frame(maxWidth: .infinity, alignment: .leading).help(model.message)
                }
            }.padding(12).frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(palette.surface)
        }.frame(minWidth: 0, maxWidth: .infinity, minHeight: 0, maxHeight: .infinity).weaveStyle(prefs)
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(model.highlighted ? palette.accent : .clear, lineWidth: 2))
        .onDrop(of: [UTType.fileURL.identifier, UTType.png.identifier, UTType.jpeg.identifier, UTType.gif.identifier, UTType.webP.identifier],
                isTargeted: $model.highlighted, perform: model.accept)
        .sheet(item: $model.editing) { item in
            StickerEditView(item: item, save: { name, group, tags in
                model.edit(item, name: name, group: group, tags: tags, favorite: item.favorite); model.editing = nil
            }, cancel: { model.editing = nil })
        }
        .alert("删除 \(model.deletionRequest?.count ?? 0) 张表情？", isPresented: $model.confirmDelete, presenting: model.deletionRequest) { request in
            Button("取消", role: .cancel) { model.cancelDeletion() }
            Button("删除", role: .destructive) { model.confirmDeletion(request) }
        } message: { request in
            Text("仅删除这次确认的 \(request.count) 张收纳图片。原应用中的图片不受影响。")
        }
        .onDisappear { model.cancelDeletion() }
    }

    private func header(narrow: Bool) -> some View {
        VStack(spacing: 8) {
            HStack(spacing: 8) {
                WindowHeading(title: "表情收纳袋 · \(model.items.count)", subtitle: "原图收藏 · 拖入收纳 · 拖出发送", symbol: "bag", prefs: prefs)
                    .lineLimit(1).frame(minWidth: 0, maxWidth: .infinity)
                if narrow {
                    Menu("操作") { collectionActions; Button("导入图片…") { model.importPictures() } }
                        .frame(width: 72).accessibilityLabel("收纳袋操作")
                }
            }
            if !narrow {
                HStack(spacing: 8) {
                    Button("导入图片…") { model.importPictures() }
                    Menu("收纳与备份") { backupActions }.fixedSize()
                    Spacer(minLength: 0)
                    Button(model.compact ? "舒展布局" : "紧凑布局", action: toggleCompact)
                    Toggle("整理", isOn: $model.selecting).toggleStyle(.button)
                }
            }
        }.buttonStyle(.bordered).controlSize(.small)
    }
    @ViewBuilder private var backupActions: some View {
        Button("收纳剪贴板") { model.fromClipboard() }
        Button("导出表情备份…") { model.exportArchive() }
        Button("导入表情备份…") { model.importArchive() }
    }
    @ViewBuilder private var collectionActions: some View {
        backupActions
        Divider()
        Button(model.compact ? "舒展布局" : "紧凑布局", action: toggleCompact)
        Button(model.selecting ? "结束整理" : "整理／多选") { model.selecting.toggle() }
    }
    private func sticker(_ item: Sticker) -> some View {
        VStack(spacing: 5) {
            StickerThumbnail(url: model.file(item)).frame(height: model.compact ? 72 : 90)
            Text((item.favorite ? "★ " : "") + item.name).font(.callout).lineLimit(2)
            if item.animated { Text("动图 · 原文件保留").font(.caption2).foregroundStyle(.secondary) }
        }.padding(8).frame(maxWidth: .infinity, minHeight: model.compact ? 116 : 140)
            .background(RoundedRectangle(cornerRadius: 12).fill(model.selected.contains(item.id) ? palette.accentSoft : Color.primary.opacity(0.06)))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(palette.divider, lineWidth: 1))
            .contentShape(Rectangle()).onTapGesture {
                if model.selecting { if !model.selected.insert(item.id).inserted { model.selected.remove(item.id) } }
                else { model.use(item) }
            }
            .onDrag { model.file(item).flatMap { NSItemProvider(contentsOf: $0) } ?? NSItemProvider() }
            .contextMenu {
                Button(item.favorite ? "取消收藏" : "收藏") { model.edit(item, name: item.name, group: item.group, tags: item.tags.joined(separator: "，"), favorite: !item.favorite) }
                Button("编辑名称、标签和分组") { model.editing = item }
                Button("复制并粘贴原图") { model.use(item) }
                Button("预览原图／动图") { if let url = model.file(item) { NSWorkspace.shared.open(url) } }
                Button("删除…", role: .destructive) { model.requestDeletion([item.id]) }.disabled(model.deleting || model.deletionRequest != nil)
            }
    }
}
private final class StickerEditModel:ObservableObject {
    @Published var name="";@Published var group="";@Published var tags=""
}
private struct StickerEditView:View {
    var item:Sticker;var save:(String,String,String)->Void;var cancel:()->Void
    @StateObject private var fields=StickerEditModel()
    var body:some View {VStack {Text("编辑表情").font(.title3);TextField("名称",text:$fields.name);TextField("分组",text:$fields.group);TextField("标签，用逗号分隔",text:$fields.tags)
        HStack {Button("取消",action:cancel);Button("保存"){save(fields.name,fields.group,fields.tags)}}}.padding(20).frame(minWidth:280,idealWidth:320,maxWidth:340).onAppear {fields.name=item.name;fields.group=item.group;fields.tags=item.tags.joined(separator:"，")}}
}
private final class StickerThumbnailModel:ObservableObject {@Published var image:NSImage?}
private struct StickerThumbnail:View {
    var url:URL?
    @StateObject private var thumbnailModel=StickerThumbnailModel()
    var body:some View {
        Group {if let image=thumbnailModel.image {Image(nsImage:image).resizable().scaledToFit()}else {Image(systemName:"photo").font(.largeTitle).foregroundStyle(.secondary)}}
            .task(id:url) {thumbnailModel.image=nil;guard let url else{return};let thumbnail=await Task.detached(priority:.utility) {()->Data? in
                guard let source=CGImageSourceCreateWithURL(url as CFURL,nil),let cg=CGImageSourceCreateThumbnailAtIndex(source,0,[kCGImageSourceCreateThumbnailFromImageAlways:true,kCGImageSourceThumbnailMaxPixelSize:160,kCGImageSourceCreateThumbnailWithTransform:true] as CFDictionary) else{return nil}
                return NSBitmapImageRep(cgImage:cg).representation(using:.png,properties:[:])
            }.value
                if !Task.isCancelled {thumbnailModel.image=thumbnail.flatMap(NSImage.init(data:))}
            }
    }
}
