import SwiftUI
import WeaveCore

/// 用户词列表。 The user-word list.
final class UserWordsModel: ObservableObject {
    @Published var query = "" { didSet { reload() } }
    @Published private(set) var words: [UserWord] = []
    @Published private(set) var count = 0
    @Published var confirmClear = false
    @Published var confirmHandClear = false

    private var engine: WeaveSession? { EngineHost.shared.engine }

    func reload() {
        count = engine?.userWordCount ?? 0
        words = engine?.userWords(query: query.trimmingCharacters(in: .whitespaces)) ?? []
    }

    func delete(_ w: UserWord) {
        engine?.deleteUserWord(w)
        reload()
    }

    func clearAll() {
        engine?.clearUserWords()
        reload()
    }
}

/// 词库：用户词、系统词库、专业词库与云端热词（与 Android 的「词库」页同一结构）。
/// Dictionaries: user words, the built-in lexicon, domain dictionaries and cloud hot words, laid out as on Android.
struct DictionaryPage: View {
    @ObservedObject var packs: DictPackStore
    @ObservedObject var cloud: CloudWords
    @StateObject private var model = UserWordsModel()

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    NavigationLink {
                        UserWordsPage(model: model)
                    } label: {
                        LabeledContent("用户词", value: "\(model.count) 个")
                    }
                    NavigationLink("快捷短语与模板") {ShortcutsPage()}
                    LabeledContent("系统词库", value: "随应用内置")
                    NavigationLink {
                        DictPacksPage(store: packs)
                    } label: {
                        LabeledContent {
                            Text(packs.installed.isEmpty ? "" : "\(packs.installed.count) 个")
                        } label: {
                            TitleAndNote("专业词库", packs.installed.isEmpty
                                ? "医学、法律、IT、地名等，按需下载"
                                : packs.installed.map(\.name).joined(separator: "、"))
                        }
                    }
                }
                CloudWordsSection(cloud: cloud)
                Section("手写学习") {
                    Button("清空个人手写字形…",role:.destructive) {model.confirmHandClear=true}
                    Text("清空后恢复内置识别，也取消手写候选的固定与降权。")
                }
                Section {
                    HStack {
                        Spacer()
                        Button("清空全部用户词…", role: .destructive) { model.confirmClear = true }
                            .disabled(model.count == 0)
                    }
                } footer: {
                    Footnote("用户词、专业词库与热词只保存在本机：~/Library/Application Support/WeaveText/")
                }
            }
            .formStyle(.grouped)
        }
        .onAppear { model.reload() }
        .alert("清空个人手写字形？",isPresented:$model.confirmHandClear) {
            Button("清空",role:.destructive) {EngineHost.shared.engine?.features(["op":"clearHand"])}
            Button("取消",role:.cancel) {}
        }
        .alert("清空全部用户词？", isPresented: $model.confirmClear) {
            Button("清空", role: .destructive) {
                model.clearAll()
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("学到的 \(model.count) 个词会全部删除，无法恢复。")
        }
    }
}

/// 标题下一行小字说明。 A title with a small note below it.
struct TitleAndNote: View {
    let title: String
    let note: String
    var noteColor: Color = .secondary

    init(_ title: String, _ note: String, noteColor: Color = .secondary) {
        self.title = title
        self.note = note
        self.noteColor = noteColor
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
            if !note.isEmpty {
                Text(note).font(.callout).foregroundStyle(noteColor).lineLimit(3)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

/// 云端热词开关与状态。 The cloud hot-words switch and status.
struct CloudWordsSection: View {
    @ObservedObject var cloud: CloudWords

    var body: some View {
        Section {
            Toggle(isOn: Binding(get: { cloud.status.enabled }, set: { cloud.setEnabled($0) })) {
                TitleAndNote("云端热词", "每天从公开的织文热词库下载一次新词、热词。只下载，不上传：你打的字不会离开这台 Mac")
            }
            if cloud.status.enabled {
                HStack {
                    TitleAndNote("热词", cloud.status.summary(),
                                 noteColor: cloud.status.needsRetry ? .red : .secondary)
                    Spacer()
                    if cloud.status.updating { ProgressView().controlSize(.small) }
                    Button(cloud.status.needsRetry ? "重试" : "立即更新") { cloud.refreshNow() }
                        .disabled(cloud.status.updating)
                    Link("词库来源与许可",destination:URL(string:"https://github.com/sheep-programmer/weavetext-hotwords/blob/main/SOURCES.md")!)
                }
            }
        }
    }
}

/// 用户词：搜索、逐个删除。 User words: search and delete one by one.
struct UserWordsPage: View {
    @ObservedObject var model: UserWordsModel

    var body: some View {
        Form {
            Section {
                TextField("搜索", text: $model.query, prompt: Text("搜索拼音或汉字"))
                    .textFieldStyle(.roundedBorder)
                    .labelsHidden()
                if model.words.isEmpty {
                    Text(model.query.isEmpty ? "还没有用户词，打字时会自动学习。" : "没有匹配的词。")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(model.words, id: \.self) { w in
                        HStack {
                            Text(w.text)
                            Text(w.pinyin).foregroundStyle(.secondary).font(.callout)
                            Spacer()
                            Text("使用 \(w.count) 次").foregroundStyle(.secondary).font(.callout.monospacedDigit())
                            Button {
                                model.delete(w)
                            } label: {
                                Image(systemName: "minus.circle")
                            }
                            .buttonStyle(.borderless)
                            .help("删除这个词")
                        }
                    }
                }
            } header: {
                Text("已学会 \(model.count) 个词")
            }
        }
        .formStyle(.grouped)
        .navigationTitle("用户词")
        .onAppear { model.reload() }
    }
}

/// 删除确认的状态（命令行工具里没有 @State 宏）。 Removal confirmation state; the CLT lack the @State macro.
final class PackRemoval: ObservableObject {
    @Published var pack: DictPack?
    var shown: Bool {
        get { pack != nil }
        set { if !newValue { pack = nil } }
    }
}

/// 专业词库：逐个下载或删除，立即生效。 Domain dictionaries: download or remove each, effective at once.
struct DictPacksPage: View {
    @ObservedObject var store: DictPackStore
    @StateObject private var removal = PackRemoval()

    var body: some View {
        Form {
            Section {
                ForEach(store.packs) { p in
                    PackRow(pack: p, state: store.state(p.id), install: { store.install(p.id) },
                            cancel: { store.cancel(p.id) }, remove: { removal.pack = p })
                }
            } header: {
                Text("装上后，这些领域的词会出现在候选里，但不会排到常用词前面；选过一次后会自动靠前。")
                    .font(.callout).foregroundStyle(.secondary).textCase(nil)
                    .fixedSize(horizontal: false, vertical: true)
            } footer: {
                Footnote("词表来自万象拼音（CC BY 4.0）与 THUOCL 清华开放中文词库（MIT），详见「关于」页的开源许可。")
            }
        }
        .formStyle(.grouped)
        .navigationTitle("专业词库")
        .alert("删除「\(removal.pack?.name ?? "")」？", isPresented: $removal.shown, presenting: removal.pack) { p in
            Button("删除", role: .destructive) { store.remove(p.id) }
            Button("取消", role: .cancel) {}
        } message: { _ in
            Text("删除后这些词不再出现在候选里；你选过的词仍保留在用户词里。")
        }
    }
}

struct PackRow: View {
    let pack: DictPack
    let state: PackState
    let install: () -> Void
    let cancel: () -> Void
    let remove: () -> Void

    private var note: String {
        switch state {
        case .downloading(let done):
            return done.map { "下载中 · \(PackFormat.size($0)) / \(PackFormat.size(pack.bytes))" } ?? "准备下载…"
        case .failed(let message):
            return message
        default:
            return pack.summary
        }
    }

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                TitleAndNote(pack.name, note, noteColor: {
                    if case .failed = state { return .red }
                    return .secondary
                }())
                if case .downloading(let done) = state {
                    ProgressView(value: Double(done ?? 0), total: Double(max(pack.bytes, 1)))
                        .controlSize(.small)
                }
            }
            Spacer(minLength: 8)
            switch state {
            case .installed:
                Button("删除", role: .destructive, action: remove).buttonStyle(.borderless).foregroundStyle(.red)
            case .downloading:
                Button("取消", action: cancel).buttonStyle(.borderless)
            case .failed:
                Button("重试", action: install).buttonStyle(.borderless)
            case .notInstalled:
                Button("下载", action: install).buttonStyle(.borderless)
            }
        }
        .padding(.vertical, 2)
    }
}

final class ShortcutsModel: ObservableObject {
    @Published var code=""
    @Published var text=""
    @Published var message=""
    @Published var items:[(String,String)]=[]
    func reload() {
        items=(EngineHost.shared.engine?.features(["op":"snippets"]).objects("items") ?? []).map {($0.str("code"),$0.str("text"))}
    }
    func save() {
        let ok=EngineHost.shared.engine?.features(["op":"setSnippet","code":code,"text":text]).bool("ok") ?? false
        message=ok ? "已保存" : "输入码只支持 1–24 个字母";reload()
    }
}
struct ShortcutsPage: View {
    @StateObject private var model=ShortcutsModel()
    var body: some View {
        Form {
            Section("已有短语") {
                ForEach(Array(model.items.enumerated()),id:\.offset) { _,item in
                    Button(item.0+" · "+String(item.1.prefix(40))) {model.code=item.0;model.text=item.1}
                }
            }
            Section("新增或修改") {
                TextField("输入码，例如 dz",text:$model.code)
                TextEditor(text:$model.text).frame(minHeight:100)
                Text("{date} 自动填日期；清空内容并保存即可删除。点击候选只插入内容，不添加空格。")
                Button("保存") {model.save()}
                if !model.message.isEmpty {Text(model.message)}
            }
        }.formStyle(.grouped).navigationTitle("快捷短语与模板").onAppear {model.reload()}
    }
}
