import AppKit
import SwiftUI
import UniformTypeIdentifiers
import WeaveCore

/// All installation/configuration work stays off the input controller's thread.
final class PluginCenter: ObservableObject {
    static let shared = PluginCenter()
    let host: PluginHost?
    private let defaults: UserDefaults
    private let credentials: GitHubCredentials
    private let prefs: Preferences
    private var isPreview = false
    @Published var plugins: [PluginInfo] = []
    @Published var repositories: [GitHubRepository] = []
    @Published var catalogs: [String:GitHubCatalog] = [:]
    @Published var errors: [String:String] = [:]
    @Published var counts: [String:Int] = [:]
    @Published var loading = Set<String>()
    @Published var expanded = Set<String>()
    @Published var ready = Set<String>()
    @Published var config: [String:[String:String]] = [:]
    @Published var search = ""
    @Published var filter = "all"
    @Published var message = ""
    @Published var busy = false
    @Published var importInfo: PluginInfo?
    private var importURL: URL?
    private var temporaryImport = false
    @Published var repositorySheet = false
    @Published var address = ""
    @Published var branch = ""
    @Published var directory = ""
    @Published var addressError = ""
    @Published var loginSheet = false
    @Published var token = ""
    @Published var account = ""
    @Published var loginError = ""
    @Published var uninstallID: String?
    private var accountGeneration = 0

    init(directory:URL = EngineHost.userDirectory(), defaults:UserDefaults = .standard, prefs:Preferences = .shared) {
        self.defaults=defaults
        self.prefs=prefs
        credentials=GitHubCredentials(service:ProcessInfo.processInfo.environment["WEAVETEXT_USER_DIR"] == nil ? "com.weavetext.github" : "com.weavetext.github.testing")
        host=PluginHost(directory:directory)
        if let data=defaults.data(forKey:"pluginRepositories"),let repos=try? JSONDecoder().decode([GitHubRepository].self,from:data) {
            repositories=repos.compactMap {try? GitHubRepository.parse($0.fullName,ref:$0.ref,path:$0.path)}
        }
        counts=defaults.dictionary(forKey:"pluginRepositoryCounts") as? [String:Int] ?? [:]
        account=credentials.read()?.user ?? ""
    }
    var filteredPlugins: [PluginInfo] {
        plugins.filter {
            (search.isEmpty || ($0.name+" "+$0.id+" "+$0.description).localizedCaseInsensitiveContains(search)) &&
            (filter=="all" || (filter=="active" ? prefs.voiceEngines.contains($0.id) : !ready.contains($0.id)))
        }
    }
    func refresh() {
        guard !isPreview else {return}
        guard let host else {message="插件宿主不可用";return}
        Task { @MainActor in
            do {
                let (list,config,ready)=try await Task.detached { () -> ([PluginInfo],[String:[String:String]],Set<String>) in
                    let list=try host.scan().filter {$0.kind=="speech" && $0.id != "system"}
                    var config:[String:[String:String]]=[:];var ready=Set<String>()
                    for p in list {
                        var fields:[String:String]=[:]
                        for f in p.configSchema {fields[f.key]=try host.getConfig(p.id,key:f.key)}
                        config[p.id]=fields
                        if p.configSchema.allSatisfy({$0.required != true || !(fields[$0.key] ?? $0.defaultValue ?? "").trimmingCharacters(in:.whitespacesAndNewlines).isEmpty}) {ready.insert(p.id)}
                    }
                    return (list,config,ready)
                }.value
                self.plugins=list;self.config=config;self.ready=ready
                let available=Set(list.map(\.id)).union(["system"])
                let next=self.prefs.voiceEngines.filter {available.contains($0)}
                if next != self.prefs.voiceEngines {self.prefs.voiceEngines=next.isEmpty ? ["system"] : next}
            } catch {message=error.localizedDescription}
        }
    }
    func toggleEngine(_ id:String) {
        var values=prefs.voiceEngines
        if values.contains(id) {values.removeAll {$0==id};if values.isEmpty {message="至少保留一个语音引擎";return}}
        else {guard id=="system" || ready.contains(id) else {message="请先完成插件配置";return};guard values.count<3 else {message="最多同时使用三个语音引擎";return};values.append(id)}
        prefs.voiceEngines=values;objectWillChange.send();message="已更新语音引擎"
    }
    func useOnly(_ id:String) {guard id=="system" || ready.contains(id) else {message="请先完成插件配置";return};prefs.voiceEngines=[id];objectWillChange.send()}
    func saveConfig(_ p:PluginInfo) {
        guard !busy,let host else {return}
        let fields=config[p.id] ?? [:];busy=true
        Task { @MainActor in
            do {
                try await Task.detached {for f in p.configSchema {try host.setConfig(p.id,key:f.key,value:fields[f.key] ?? f.defaultValue ?? "")}}.value
                message="已保存 \(p.name) 的配置";refresh()
            } catch {message=error.localizedDescription}
            busy=false
        }
    }
    func addRepository() {
        do {
            let repo=try GitHubRepository.parse(address,ref:branch,path:directory)
            repositories.removeAll {$0.id==repo.id};repositories.append(repo);repositories=Array(repositories.suffix(50))
            defaults.set(try JSONEncoder().encode(repositories),forKey:"pluginRepositories")
            repositorySheet=false;address="";branch="";directory="";addressError=""
            expanded.insert(repo.id);refreshRepository(repo)
        } catch {addressError=error.localizedDescription}
    }
    func removeRepository(_ repo:GitHubRepository) {
        repositories.removeAll {$0.id==repo.id};catalogs.removeValue(forKey:repo.id);counts.removeValue(forKey:repo.id)
        defaults.set(try? JSONEncoder().encode(repositories),forKey:"pluginRepositories")
        defaults.set(counts,forKey:"pluginRepositoryCounts")
    }
    func refreshRepository(_ repo:GitHubRepository) {
        guard !isPreview else {return}
        guard !loading.contains(repo.id) else {return}
        loading.insert(repo.id);errors.removeValue(forKey:repo.id)
        let client=GitHubPluginClient(token:credentials.read()?.token),epoch=accountGeneration
        Task { @MainActor in
            defer {loading.remove(repo.id)}
            do {
                let value=try await client.catalog(repo)
                guard epoch==accountGeneration,repositories.contains(repo) else {return}
                catalogs[repo.id]=value;counts[repo.id]=value.plugins.count;defaults.set(counts,forKey:"pluginRepositoryCounts")
            } catch {if epoch==accountGeneration {errors[repo.id]=error.localizedDescription}}
        }
    }
    func login() {
        guard !busy else {return};busy=true
        let input=token.trimmingCharacters(in:.whitespacesAndNewlines)
        Task { @MainActor in
            defer {busy=false}
            do {
                let user=try await GitHubPluginClient().login(input)
                try credentials.save(user:user,token:input)
                accountGeneration += 1;account=user;token="";loginSheet=false;loginError="";catalogs=[:]
                for r in repositories {refreshRepository(r)}
            } catch {loginError=error.localizedDescription}
        }
    }
    func logout() {
        do {try credentials.clear();accountGeneration += 1;account="";catalogs=[:];message="已退出 GitHub"}
        catch {message=error.localizedDescription}
    }
    func choosePackage() {
        let picker=NSOpenPanel();picker.canChooseFiles=true;picker.canChooseDirectories=false;picker.allowsMultipleSelection=false
        picker.begin { [weak self] response in if response == .OK,let url=picker.url {self?.preview(url,temporary:false)} }
    }
    func download(_ catalog:GitHubCatalog,_ plugin:GitHubPlugin) {
        guard !busy else {return};busy=true;message="正在下载 \(plugin.label)…"
        let client=GitHubPluginClient(token:credentials.read()?.token),epoch=accountGeneration
        Task { @MainActor in
            do {
                let url=try await client.download(catalog,plugin:plugin)
                guard epoch==accountGeneration else {try? FileManager.default.removeItem(at:url.deletingLastPathComponent());busy=false;message="GitHub 账号已变更，请刷新仓库";return}
                busy=false;preview(url,temporary:true)
            } catch {busy=false;message=error.localizedDescription}
        }
    }
    func preview(_ url:URL,temporary:Bool) {
        guard !busy else {return};busy=true
        Task { @MainActor in
            do {
                let info=try await Task.detached {try PluginHost.inspect(url)}.value
                guard info.kind=="speech",info.id != "system" else {throw PluginFailure("目前支持语音插件，不能覆盖内置引擎标识")}
                importURL=url;temporaryImport=temporary;importInfo=info;message=""
            } catch {message=error.localizedDescription;if temporary {try? FileManager.default.removeItem(at:url.deletingLastPathComponent())}}
            busy=false
        }
    }
    func discardImport() {
        if temporaryImport,let url=importURL {try? FileManager.default.removeItem(at:url.deletingLastPathComponent())}
        importInfo=nil;importURL=nil;temporaryImport=false
    }
    func install() {
        guard !busy,let url=importURL,let host else {return};busy=true
        Task { @MainActor in
            do {let p=try await Task.detached {try host.install(url)}.value;message="已安装 \(p.name)";discardImport();refresh()}
            catch {message=error.localizedDescription}
            busy=false
        }
    }
    func uninstall(_ id:String) {
        guard !busy,let host else {return};busy=true
        Task { @MainActor in
            do {try await Task.detached {try host.uninstall(id)}.value;refresh();message="已卸载插件"} catch {message=error.localizedDescription}
            busy=false
        }
    }
    func preview(_ items:[PluginInfo],catalogs:[GitHubCatalog]) {
        isPreview=true;plugins=items;ready=Set(items.prefix(1).map(\.id));account="示例账号"
        repositories=catalogs.map(\.repository)
        for catalog in catalogs {self.catalogs[catalog.repository.id]=catalog;counts[catalog.repository.id]=catalog.plugins.count}
        expanded=Set(repositories.prefix(1).map(\.id)+items.prefix(1).map(\.id))
    }
}

struct PluginsPage: View {
    @ObservedObject var model:PluginCenter
    @ObservedObject var prefs:Preferences
    var body:some View {
        Form {
            Section {
                HStack {
                    Button("手动导入插件…") {model.choosePackage()}
                    Button("加入 GitHub 仓库…") {model.repositorySheet=true}
                    Spacer()
                    if model.account.isEmpty {Button("登录 GitHub…") {model.loginSheet=true}}
                    else {Text(model.account);Button("退出登录") {model.logout()}}
                }
                if model.busy {ProgressView().controlSize(.small)}
                if !model.message.isEmpty {Text(model.message).font(.callout).textSelection(.enabled)}
            } header: {Text("导入插件")}
            Section {
                if model.repositories.isEmpty {Text("加入仓库后可展开查看插件数量与可导入项目。").foregroundStyle(.secondary)}
                ForEach(model.repositories) {repo in
                    DisclosureGroup(isExpanded:Binding(get:{model.expanded.contains(repo.id)},set:{if $0 {model.expanded.insert(repo.id)} else {model.expanded.remove(repo.id)}})) {
                        HStack {
                            Text([repo.ref.isEmpty ? "默认分支" : repo.ref,repo.path].filter{!$0.isEmpty}.joined(separator:" · ")).foregroundStyle(.secondary)
                            Spacer();Button("刷新") {model.refreshRepository(repo)}.disabled(model.loading.contains(repo.id))
                            Button("移除") {model.removeRepository(repo)}
                        }
                        if model.loading.contains(repo.id) {ProgressView("正在读取插件列表…").controlSize(.small)}
                        if let error=model.errors[repo.id] {Text(error).foregroundStyle(.red).textSelection(.enabled)}
                        if let catalog=model.catalogs[repo.id] {
                            ForEach(catalog.plugins) {plugin in
                                HStack {Text(plugin.label).lineLimit(2);Spacer();Button("导入") {model.download(catalog,plugin)}.disabled(model.busy)}
                            }
                            if catalog.plugins.isEmpty {Text("此分支和目录没有可导入的插件。")}
                        }
                    } label: {
                        HStack {Text(repo.fullName);Spacer();Text(model.counts[repo.id].map{"\($0) 个插件"} ?? "未读取").foregroundStyle(.secondary)
                            if let catalog=model.catalogs[repo.id] {Text(catalog.isPrivate ? "私有" : "公开").font(.caption).foregroundStyle(.secondary)}
                        }
                    }
                }
            } header: {Text("插件仓库")}
            Section {
                HStack {Text("系统离线语音");Spacer();Button("单独使用") {model.useOnly("system")}
                    Toggle("同时使用",isOn:Binding(get:{prefs.voiceEngines.contains("system")},set:{_ in model.toggleEngine("system")})).toggleStyle(.checkbox)
                }
                Text("离线识别使用 macOS 系统语言资源；录音前检查资源是否可用。").foregroundStyle(.secondary).font(.callout)
                TextField("搜索插件",text:$model.search)
                Picker("显示",selection:$model.filter) {Text("全部").tag("all");Text("已启用").tag("active");Text("待配置").tag("unconfigured")}.pickerStyle(.segmented)
                ForEach(model.filteredPlugins) {p in
                    DisclosureGroup(isExpanded:Binding(get:{model.expanded.contains(p.id)},set:{if $0 {model.expanded.insert(p.id)} else {model.expanded.remove(p.id)}})) {
                        Text(p.description).foregroundStyle(.secondary).textSelection(.enabled)
                        Text(p.networkNote).font(.callout).foregroundStyle(.secondary)
                        ForEach(p.configSchema) {f in PluginConfigRow(field:f,value:Binding(get:{model.config[p.id]?[f.key] ?? f.defaultValue ?? ""},set:{model.config[p.id,default:[:]][f.key]=$0}))}
                        HStack {
                            Button("保存配置") {model.saveConfig(p)}.disabled(model.busy)
                            Button("单独使用") {model.useOnly(p.id)}.disabled(!model.ready.contains(p.id))
                            Toggle("同时使用",isOn:Binding(get:{prefs.voiceEngines.contains(p.id)},set:{_ in model.toggleEngine(p.id)})).toggleStyle(.checkbox)
                            Spacer();Button("卸载…",role:.destructive) {model.uninstallID=p.id}.disabled(model.busy)
                        }
                    } label: {
                        HStack {Text(p.name);Text(p.version).foregroundStyle(.secondary);Spacer();Text("\(p.configSchema.count) 项设置").foregroundStyle(.secondary)
                            Text(prefs.voiceEngines.contains(p.id) ? "已启用" : model.ready.contains(p.id) ? "已配置" : "待配置").foregroundStyle(.secondary)
                        }
                    }
                }
                if model.filteredPlugins.isEmpty {Text(model.plugins.isEmpty ? "还没有导入语音插件。" : "没有匹配的插件。").foregroundStyle(.secondary)}
            } header: {Text("语音引擎")} footer: {Footnote("最多同时使用三个引擎，共享一次录音；每个结果分别展示，确认后上屏。")}
        }.formStyle(.grouped)
        .onAppear {model.refresh();for r in model.repositories where model.catalogs[r.id]==nil {model.refreshRepository(r)}}
        .sheet(isPresented:$model.repositorySheet) {
            VStack(alignment:.leading,spacing:14) {
                Text("加入插件仓库").font(.title2)
                TextField("完整 GitHub 地址或 owner/repository",text:$model.address)
                TextField("分支 / 标签（可选）",text:$model.branch)
                TextField("插件目录（可选）",text:$model.directory)
                Text("支持仓库、tree 目录和插件文件链接，按内容识别插件。分支名包含 / 时请填写完整分支名。").font(.callout).foregroundStyle(.secondary)
                if !model.addressError.isEmpty {Text(model.addressError).foregroundStyle(.red)}
                HStack {Button("取消") {model.repositorySheet=false};Spacer();Button("加入") {model.addRepository()}}
            }.padding(22).frame(width:480)
        }
        .sheet(isPresented:$model.loginSheet,onDismiss:{model.token=""}) {
            VStack(alignment:.leading,spacing:14) {
                Text("GitHub 访问令牌登录").font(.title2)
                Text("私有仓库使用只授权指定仓库的令牌，Contents 权限设为 Read-only。").fixedSize(horizontal:false,vertical:true)
                Link("在 GitHub 创建只读令牌",destination:URL(string:"https://github.com/settings/personal-access-tokens/new")!)
                SecureField("访问令牌",text:$model.token)
                Text("令牌保存到 Mac 钥匙串，不会交给插件或随互联同步。").font(.callout).foregroundStyle(.secondary)
                if !model.loginError.isEmpty {Text(model.loginError).foregroundStyle(.red)}
                HStack {Button("取消") {model.token="";model.loginSheet=false}.disabled(model.busy);Spacer();Button("登录") {model.login()}.disabled(model.busy)}
            }.padding(22).frame(width:460)
        }
        .sheet(isPresented:Binding(get:{model.importInfo != nil},set:{if !$0 {model.discardImport()}})) {
            if let p=model.importInfo {
                VStack(alignment:.leading,spacing:14) {
                    Text("导入 \(p.name)").font(.title2);Text(p.id+" · "+p.version).foregroundStyle(.secondary)
                    Text(p.description);Text(p.networkNote).fixedSize(horizontal:false,vertical:true)
                    HStack {Button("取消") {model.discardImport()}.disabled(model.busy);Spacer();Button("确认安装") {model.install()}.disabled(model.busy)}
                }.padding(22).frame(width:460)
            }
        }
        .alert("卸载语音插件？",isPresented:Binding(get:{model.uninstallID != nil},set:{if !$0 {model.uninstallID=nil}})) {
            Button("卸载",role:.destructive) {if let id=model.uninstallID {model.uninstall(id)};model.uninstallID=nil}
            Button("取消",role:.cancel) {model.uninstallID=nil}
        } message:{Text("会删除插件及其配置；仓库地址仍会保留。")}
    }
}

private struct PluginConfigRow:View {
    let field:PluginField
    @Binding var value:String
    var body:some View {
        VStack(alignment:.leading,spacing:4) {
            if field.type=="switch" {
                Toggle(field.label ?? field.key,isOn:Binding(get:{value=="true"},set:{value=$0 ? "true" : "false"}))
            } else if field.type=="select",let options=field.options {
                Picker(field.label ?? field.key,selection:$value) {ForEach(options,id:\.self) {Text($0).tag($0)}}
            } else if field.type=="password" || field.type=="secret" || field.key.lowercased().contains("key") || field.key.lowercased().contains("token") {
                SecureField(field.label ?? field.key,text:$value)
            } else {TextField(field.label ?? field.key,text:$value)}
            if let help=field.helpText ?? field.description {Text(help).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal:false,vertical:true)}
        }
    }
}
