import AppKit
import UniformTypeIdentifiers
import SwiftUI
import WeaveCore

private final class MarketFilters: ObservableObject {
    @Published var kind = "all"
    @Published var query = ""
    @Published var onlyInstalled = false
    @Published var error: String?
    @Published var busy = false
    @Published var progress:FetchProgress?
    @Published var stage = ""
    var task:Task<Void,Never>?
    var generation = 0

}

struct MarketPage: View {
    @ObservedObject var prefs: Preferences
    @ObservedObject var store: ExtensionStore = .shared
    @ObservedObject var navigation: SettingsNavigation
    @StateObject private var filters = MarketFilters()
    private var kind:String { get {filters.kind} nonmutating set {filters.kind=newValue} }
    private var query:String {filters.query}
    private var onlyInstalled:Bool {filters.onlyInstalled}
    private var error:String? { get {filters.error} nonmutating set {filters.error=newValue} }
    private let kinds = [("all", "全部"), ("feature", "功能"), ("scheme", "输入方案"), ("theme", "主题"), ("speech", "语音引擎"), ("translation", "翻译"), ("dict", "词库")]
    private var palette: ThemePalette { Theme.palette(prefs.colorTheme) }
    private var items: [ExtensionItem] {
        store.items.filter {
            (kind == "all" || $0.kind == kind) &&
            (query.isEmpty || ($0.name + $0.summary).localizedCaseInsensitiveContains(query)) &&
            (!onlyInstalled || ($0.builtin ? prefs.extensionEnabled($0.key) : store.installed($0)))
        }
    }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HStack(spacing: 12) {
                    Image(systemName: "puzzlepiece.extension.fill").font(.system(size:28)).foregroundStyle(palette.accent)
                    VStack(alignment: .leading, spacing: 6) {
                        Text("搭配你的输入方式").font(.system(size:22,weight:.semibold))
                        Text("添加喜欢的工具和配色，随时启用或移除。").foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button("刷新") {download {progress in try await store.refreshMarket(progress:progress)}}.disabled(filters.busy)
                    Button("导入主题…") {importTheme()}
                }.padding(22).frame(maxWidth:.infinity,alignment:.leading)
                    .background(palette.accentSoft,in:RoundedRectangle(cornerRadius:20))
                if filters.busy {
                    VStack(alignment:.leading,spacing:8) {
                        HStack {Text(filters.stage);Spacer();Button("取消") {filters.task?.cancel()}}
                        if let progress=filters.progress,let total=progress.totalBytes {
                            ProgressView(value:Double(progress.receivedBytes),total:Double(total))
                            Text("\(ByteCountFormatter.string(fromByteCount:progress.receivedBytes,countStyle:.file)) / \(ByteCountFormatter.string(fromByteCount:total,countStyle:.file))").font(.caption).foregroundStyle(.secondary)
                        } else {
                            ProgressView()
                            if let progress=filters.progress,progress.receivedBytes>0 {Text("已下载 \(ByteCountFormatter.string(fromByteCount:progress.receivedBytes,countStyle:.file))").font(.caption)}
                        }
                    }.padding(14).background(palette.surface,in:RoundedRectangle(cornerRadius:12))
                }
                Link("官方扩展仓库",destination:OfficialMarket.repository).font(.caption)
                HStack {
                    Image(systemName:"magnifyingglass").foregroundStyle(.secondary)
                    TextField("搜索功能或主题",text:$filters.query).textFieldStyle(.plain)
                    Toggle("已添加",isOn:$filters.onlyInstalled).toggleStyle(.checkbox)
                }.padding(12).background(palette.surface,in:RoundedRectangle(cornerRadius:10))
                ScrollView(.horizontal,showsIndicators:false) {
                    HStack(spacing:8) {
                        ForEach(kinds,id:\.0) { id,title in
                            Button {kind=id} label: {
                                Text(title).font(.system(size:12,weight:kind==id ? .semibold : .regular)).padding(.horizontal,12).padding(.vertical,8)
                                    .foregroundStyle(kind==id ? palette.accent : palette.label)
                                    .background(kind==id ? palette.accentSoft : palette.surface,in:Capsule())
                            }.buttonStyle(.plain)
                        }
                    }
                }
                if kind == "all" || ["speech", "translation", "dict"].contains(kind) {
                    HStack(spacing:10) {
                        if (kind == "all" || kind == "speech") && prefs.extensionEnabled("feature:voice") { destination("语音引擎与仓库",symbol:"waveform",page:.plugins) }
                        if (kind == "all" || kind == "translation") && prefs.extensionEnabled("feature:translate") { destination("翻译服务与插件",symbol:"character.bubble",page:.translation) }
                        if kind == "all" || kind == "dict" { destination("专业词库",symbol:"character.book.closed",page:.dictionary) }
                        if kind == "speech" && !prefs.extensionEnabled("feature:voice") { Button("先启用语音输入") {kind="feature"} }
                        if kind == "translation" && !prefs.extensionEnabled("feature:translate") { Button("先启用翻译") {kind="feature"} }
                    }.frame(maxWidth:.infinity,alignment:.leading)
                }
                if (kind == "all" || kind == "speech") && prefs.extensionEnabled("feature:voice") {
                    Button("加入官方语音仓库") {
                        let model=PluginCenter.shared
                        model.address=OfficialMarket.repository.absoluteString+"/tree/main/plugins"
                        model.branch="";model.directory=""
                        model.addRepository();navigation.page = .plugins
                    }
                }
                LazyVGrid(columns:[GridItem(.adaptive(minimum:240),spacing:14)],spacing:14) {
                    ForEach(items,id:\.key) { item in card(item) }
                }
                if items.isEmpty && !["speech","translation","dict"].contains(kind) { Text("没有匹配的扩展").foregroundStyle(.secondary).padding(20) }
            }.padding(.horizontal,26).padding(.bottom,26)
        }
        .onDisappear {filters.task?.cancel()}
        .alert("未能完成",isPresented:Binding(get:{error != nil},set:{if !$0 {error=nil}})) {Button("知道了") {error=nil}} message:{Text(error ?? "")}
    }
    private func destination(_ title:String,symbol:String,page:SettingsPage)->some View {
        Button {navigation.page=page} label:{Label(title,systemImage:symbol).font(.system(size:12)).padding(12).frame(maxWidth:.infinity).background(palette.surface,in:RoundedRectangle(cornerRadius:10))}.buttonStyle(.plain)
    }
    @ViewBuilder private func card(_ item:ExtensionItem)->some View {
        VStack(alignment:.leading,spacing:12) {
            HStack {
                VStack(alignment:.leading,spacing:4) {
                    Text(item.name).font(.system(size:15,weight:.semibold))
                    Text((kinds.first{$0.0==item.kind}?.1 ?? item.kind) + (item.base ? " · 基础内置" : item.builtin ? " · 功能模块" : " · 扩展"))
                        .font(.system(size:10)).foregroundStyle(palette.accent)
                }
                Spacer()
                if item.builtin { Toggle(item.name,isOn:Binding(get:{prefs.extensionEnabled(item.key)},set:{prefs.setExtension(item.key,enabled:$0)})).labelsHidden().toggleStyle(.switch).controlSize(.small) }
            }
            if item.kind == "theme", let definition = store.preview(item) {
                HStack(spacing:7) {
                    ForEach(["background","accent","key"],id:\.self) { key in
                        let pair=definition.colors(key,fallback:(0x808080,0x808080))
                        RoundedRectangle(cornerRadius:6).fill(Color(nsColor:Theme.rgb(pair.0))).frame(width:22,height:22)
                        RoundedRectangle(cornerRadius:6).fill(Color(nsColor:Theme.rgb(pair.1))).frame(width:22,height:22)
                    }
                }
            }
            Text(item.summary).font(.system(size:12)).foregroundStyle(.secondary).frame(maxWidth:.infinity,alignment:.leading).fixedSize(horizontal:false,vertical:true)
            Spacer(minLength:0)
            HStack {
                Spacer()
                if item.builtin {
                    if prefs.extensionEnabled(item.key), let page = configuration(item) { Button("设置") {navigation.page=page} }
                } else if !store.installed(item) {
                    Button("安装") {download {progress in try await store.installFromMarket(item,progress:progress)}}.buttonStyle(.borderedProminent).disabled(filters.busy)
                } else {
                    if !item.base { Button("卸载") {do {try store.uninstall(item,prefs:prefs)} catch {self.error=error.localizedDescription}} }
                    if store.updateAvailable(item) {Button("更新") {download {progress in try await store.installFromMarket(item,progress:progress);prefs.colorTheme=prefs.colorTheme}}.disabled(filters.busy)}
                    Button(prefs.colorTheme.rawValue == item.id ? "使用中" : "使用") {if let theme=ColorTheme(rawValue:item.id) {prefs.colorTheme=theme}}
                        .disabled(prefs.colorTheme.rawValue == item.id)
                }
            }.controlSize(.small)
        }.padding(18).frame(maxWidth:.infinity,minHeight:152,alignment:.topLeading)
            .background(palette.surface,in:RoundedRectangle(cornerRadius:16))
            .overlay(RoundedRectangle(cornerRadius:16).stroke(palette.divider.opacity(0.7),lineWidth:1))
    }
    private func download(_ action:@escaping(@escaping @Sendable(FetchProgress)->Void) async throws->Void) {
        guard !filters.busy else {return}
        filters.generation += 1
        let generation=filters.generation
        filters.busy=true;filters.progress=nil;filters.stage="正在连接官方仓库"
        filters.task=Task {@MainActor in
            do {
                try await action {progress in Task {@MainActor in
                    if filters.busy && filters.generation==generation {filters.progress=progress;filters.stage=progress.receivedBytes > 0 ? "正在下载" : "正在连接官方仓库"}
                }}
            } catch is CancellationError {} catch {self.error="官方仓库读取或校验失败，请重试："+error.localizedDescription}
            filters.busy=false;filters.task=nil
        }
    }
    private func importTheme() {
        let panel=NSOpenPanel();panel.allowedContentTypes=[.json];panel.allowsMultipleSelection=false
        guard panel.runModal() == .OK,let url=panel.url else {return}
        do {
            let handle=try FileHandle(forReadingFrom:url);defer {try? handle.close()}
            let data=try handle.read(upToCount:256*1024+1) ?? Data()
            prefs.colorTheme=try store.importTheme(data)
            kind="theme"
        } catch {self.error=error.localizedDescription}
    }
    private func configuration(_ item:ExtensionItem)->SettingsPage? {
        switch item.key {
        case "feature:voice":return .plugins
        case "feature:translate":return .translation
        case "feature:link":return .link
        case "feature:phrases":return .tools
        case "feature:cloudwords":return .dictionary
        case "scheme:hand","scheme:wubi86":return .schemes
        default:return nil
        }
    }
}

struct SettingsHomePage:View {
    @ObservedObject var prefs:Preferences
    @ObservedObject var navigation:SettingsNavigation
    private var palette:ThemePalette {Theme.palette(prefs.colorTheme)}
    var body:some View {
        ScrollView {
            VStack(alignment:.leading,spacing:22) {
                VStack(alignment:.leading,spacing:10) {
                    Text("让输入顺手一点").font(.system(size:27,weight:.semibold))
                    Text("\(InputScheme.named(prefs.schema).name) · \(prefs.colorTheme.title)").foregroundStyle(.secondary)
                    HStack(spacing:8) {
                        Label("本机输入",systemImage:"keyboard")
                        Text("·")
                        Text("按你的习惯自由搭配")
                    }.font(.system(size:11)).foregroundStyle(palette.accent)
                }.padding(26).frame(maxWidth:.infinity,alignment:.leading).background(palette.accentSoft,in:RoundedRectangle(cornerRadius:22))
                LazyVGrid(columns:[GridItem(.flexible()),GridItem(.flexible())],spacing:14) {
                    tile(.schemes,note:"全拼、双拼与输入选项")
                    tile(.appearance,note:"配色、候选窗与明暗模式")
                    tile(.dictionary,note:"用户词、学习记录与词库")
                    tile(.general,note:"切换方式与快捷键")
                }
                Button {navigation.page = .market} label:{
                    HStack(spacing:14) {
                        Image(systemName:"puzzlepiece.extension.fill").font(.system(size:27)).foregroundStyle(palette.accent)
                        VStack(alignment:.leading,spacing:5) {Text("插件市场").font(.system(size:19,weight:.semibold));Text("添加工具与主题，组合自己的织文").foregroundStyle(.secondary)}
                        Spacer();Image(systemName:"arrow.right")
                    }.padding(22).background(palette.surface,in:RoundedRectangle(cornerRadius:18))
                        .overlay(RoundedRectangle(cornerRadius:18).stroke(palette.divider,lineWidth:1))
                }.buttonStyle(.plain)
                HStack(spacing:12) {
                    Button("剪贴板与输入工具") {navigation.page = .tools}
                    if prefs.extensionEnabled("feature:voice") {Button("语音输入") {navigation.page = .plugins}}
                    if prefs.extensionEnabled("feature:translate") {Button("翻译") {navigation.page = .translation}}
                    if prefs.extensionEnabled("feature:link") {Button("互联") {navigation.page = .link}}
                }.controlSize(.small)
            }.padding(.horizontal,26).padding(.bottom,26)
        }
    }
    private func tile(_ page:SettingsPage,note:String)->some View {
        Button {navigation.page=page} label:{
            HStack(alignment:.top,spacing:12) {
                Image(systemName:page.symbol).foregroundStyle(palette.accent).frame(width:32,height:32).background(palette.accentSoft,in:RoundedRectangle(cornerRadius:9))
                VStack(alignment:.leading,spacing:5) {Text(page.title).font(.system(size:15,weight:.semibold));Text(note).font(.system(size:11)).foregroundStyle(.secondary)}
                Spacer(minLength:0)
            }.padding(18).frame(maxWidth:.infinity,minHeight:88,alignment:.leading).background(palette.surface,in:RoundedRectangle(cornerRadius:15))
        }.buttonStyle(.plain)
    }
}
