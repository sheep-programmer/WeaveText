import AppKit
import ServiceManagement
import SwiftUI
import WeaveCore

/// 登录时启动（SMAppService）。 Launch at login via SMAppService.
final class LoginItem: ObservableObject {
    @Published private(set) var enabled = SMAppService.mainApp.status == .enabled
    @Published private(set) var error: String?

    func set(_ on: Bool) {
        do {
            if on { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
            error = nil
        } catch {
            self.error = "无法更改：\(error.localizedDescription)"
        }
        enabled = SMAppService.mainApp.status == .enabled
        if SMAppService.mainApp.status == .requiresApproval {
            error = "需要在「系统设置 → 通用 → 登录项」里允许织文。"
        }
    }
}

/// 录制一个快捷键：点按钮后按下想用的键；被输入占用的键会提示原因。
/// Record a shortcut: click the button, then press the key; keys the input needs are refused with a reason.
final class KeyRecorder: ObservableObject {
    @Published var recording: CandidateKeySlot?
    @Published var message = ""
    private var monitor: Any?

    func start(_ slot: CandidateKeySlot, prefs: Preferences) {
        stop()
        recording = slot
        message = "请按下要用的键，Esc 取消"
        monitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            guard let self else { return event }
            if event.keyCode == KeyCode.escape { self.stop(); return nil }
            let flags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
            let key = KeyInput(keyCode: event.keyCode, characters: event.characters ?? "", shift: flags.contains(.shift),
                               control: flags.contains(.control), option: flags.contains(.option), command: flags.contains(.command),
                               capsLock: flags.contains(.capsLock))
            let made = KeyBinding.make(from: key)
            if let binding = made.binding {
                var keys = prefs.candidateKeys
                keys.assign(binding, to: slot)
                prefs.candidateKeys = keys
                self.stop()
            } else {
                self.message = (made.refusal ?? "这个键不能用") + "，换一个键"
            }
            return nil
        }
    }

    func stop() {
        if let monitor { NSEvent.removeMonitor(monitor) }
        monitor = nil
        recording = nil
        message = ""
    }

    deinit { if let monitor { NSEvent.removeMonitor(monitor) } }
}

final class VoiceKeyRecorder: ObservableObject {
    @Published var recording = false
    @Published var message = ""
    private var monitor: Any?

    func start(prefs: Preferences) {
        stop(); recording = true; message = "按下组合键，Esc 取消"
        monitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { [weak self] event in
            guard let self else { return event }
            if event.keyCode == KeyCode.escape { self.stop(); return nil }
            let flags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
            let key = KeyInput(keyCode: event.keyCode, characters: event.charactersIgnoringModifiers ?? "",
                               shift: flags.contains(.shift), control: flags.contains(.control),
                               option: flags.contains(.option), command: flags.contains(.command))
            if let shortcut = VoiceShortcut.make(from: key) {
                prefs.voiceShortcut = shortcut; self.stop()
            } else {
                self.message = "请使用带 ⌃、⌥ 或 ⌘ 的组合键"
            }
            return nil
        }
    }

    func stop() {
        if let monitor { NSEvent.removeMonitor(monitor) }
        monitor = nil; recording = false; message = ""
    }
    deinit { if let monitor { NSEvent.removeMonitor(monitor) } }
}

struct ShortcutRow: View {
    let slot: CandidateKeySlot
    @ObservedObject var prefs: Preferences
    @ObservedObject var recorder: KeyRecorder

    var body: some View {
        LabeledContent {
            HStack(spacing: 6) {
                Button(recorder.recording == slot ? "按下按键…" : (prefs.candidateKeys[slot]?.label ?? "未设置")) {
                    recorder.recording == slot ? recorder.stop() : recorder.start(slot, prefs: prefs)
                }
                .frame(minWidth: 96)
                if prefs.candidateKeys[slot] != nil {
                    Button {
                        var keys = prefs.candidateKeys
                        keys[slot] = nil
                        prefs.candidateKeys = keys
                    } label: { Image(systemName: "xmark.circle.fill") }
                    .buttonStyle(.plain).foregroundStyle(.secondary).help("清除，恢复默认键")
                }
            }
        } label: {
            TitleAndNote(slot.title, recorder.recording == slot ? recorder.message : slot.defaultKeys,
                         noteColor: recorder.recording == slot ? .orange : .secondary)
        }
    }
}

// SwiftUI 的 @State 在新 SDK 里是宏，命令行工具不带它的插件，所以页面状态都放在 ObservableObject 里。
// @State is a macro in the newer SDK and the command-line tools lack its plugin, so page state lives in ObservableObjects.

struct GeneralPage: View {
    @ObservedObject var prefs: Preferences
    @StateObject private var login = LoginItem()
    @StateObject private var recorder = KeyRecorder()
    @StateObject private var voiceRecorder = VoiceKeyRecorder()

    var body: some View {
        Form {
            Section {
                Toggle("登录时启动", isOn: Binding(get: { login.enabled }, set: { login.set($0) }))
            } header: {
                Text("启动")
            } footer: {
                if let error = login.error { Footnote(error) } else {
                    Footnote("切换到织文后，从系统输入法菜单里的「织文设置…」打开设置。")
                }
            }
            Section("中英切换") {
                Picker("切换键", selection: $prefs.toggleKey) {
                    Text("单击 Shift").tag(ToggleKey.shift)
                    Text("关闭").tag(ToggleKey.none)
                }
            }
            Section("候选") {
                Picker("每页候选个数", selection: $prefs.pageSize) {
                    ForEach(Array(Preferences.pageSizes), id: \.self) { Text("\($0)").tag($0) }
                }
                Picker("翻页键", selection: $prefs.pageKeys) {
                    Text("- = 与 , .").tag(PageKeys.both)
                    Text("- =").tag(PageKeys.minusEqual)
                    Text(", .").tag(PageKeys.commaPeriod)
                    Text("[ ]").tag(PageKeys.brackets)
                }
            }
            Section {
                ForEach(CandidateKeySlot.allCases, id: \.self) { slot in
                    ShortcutRow(slot: slot, prefs: prefs, recorder: recorder)
                }
                if !prefs.candidateKeys.isDefault {
                    Button("全部恢复默认") { prefs.candidateKeys = CandidateKeys() }
                }
            } header: {
                Text("候选快捷键")
            } footer: {
                Footnote("点按钮后按下想用的键，比如 [ 和 ] 翻页，或 Tab 展开全部候选。自定义的键优先于上面的默认翻页键；字母、数字、空格、回车、Esc 要用来打字，不能设置。")
            }
            Section {
                if prefs.extensionEnabled("feature:voice") {
                LabeledContent("打开语音悬浮窗") {
                    HStack(spacing: 6) {
                        Button(voiceRecorder.recording ? "按下组合键…" : (prefs.voiceShortcut?.label ?? "关闭")) {
                            if voiceRecorder.recording { voiceRecorder.stop() }
                            else { recorder.stop(); voiceRecorder.start(prefs: prefs) }
                        }
                        Button("恢复默认") { voiceRecorder.stop(); prefs.voiceShortcut = .defaultBinding }
                        Button("关闭快捷键") { voiceRecorder.stop(); prefs.voiceShortcut = nil }
                    }
                }
                if voiceRecorder.recording { Text(voiceRecorder.message).foregroundStyle(.orange) }
                }
                if prefs.extensionEnabled("feature:stickers") {Button("打开表情收纳袋…") { StickerWindow.shared.show() }}
                Button("Emoji 与颜文字…") { ExpressionsWindow.shared.show() }
            } header: {
                Text("输入工具")
            } footer: {
                Footnote("切换到织文后生效，也可从输入法菜单打开。悬浮窗可拖动，点关闭按钮才关闭；点击话筒后才开始录音。系统占用的组合键无法传给输入法。")
            }
        }
        .formStyle(.grouped)
        .onDisappear { recorder.stop(); voiceRecorder.stop() }
    }
}

struct SchemesPage: View {
    @ObservedObject var prefs: Preferences

    private var pinyinFamily: Bool { prefs.schema == "pinyin" || InputScheme.named(prefs.schema).isShuangpin }

    var body: some View {
        Form {
            Section("输入方案") {
                Picker("方案", selection: $prefs.schema) {
                    ForEach(InputScheme.all.filter {ExtensionRegistry.scheme($0.id,enabled:prefs.extensionsEnabled)}) { scheme in
                        let missing = EngineHost.shared.engine?.hasSchema(scheme.id) == false
                        Text(missing ? scheme.name + "（缺少词库）" : scheme.name).tag(scheme.id).disabled(missing)
                    }
                }
                .pickerStyle(.radioGroup)
            }
            Section {
                ForEach(FuzzyPair.all) { pair in
                    Toggle(pair.label, isOn: Binding(get: { prefs.isFuzzy(pair.id) },
                                                     set: { prefs.setFuzzy(pair.id, $0) }))
                }
            } header: {
                Text("模糊音")
            } footer: {
                Footnote(pinyinFamily ? "打开后两种读音互相通用。" : "模糊音只对全拼与双拼生效。")
            }
            .disabled(!pinyinFamily)
            Section {
                Toggle("自动纠错", isOn: $prefs.autocorrect)
            } header: {
                Text("拼音纠错")
            } footer: {
                Footnote("默认开启。全拼的换位、漏字母、多字母会自动纠正，改动标红；候选窗同时提示已纠错。回车仍可提交原始输入。")
            }
            .disabled(prefs.schema != "pinyin")
            Section {
                Toggle("联想词（默认关闭）", isOn: $prefs.prediction)
                Picker("联想深度", selection: $prefs.predictionDepth) {
                    ForEach(Array(Preferences.predictionDepths), id: \.self) { Text($0 == 3 ? "\($0) 次（默认）" : "\($0) 次").tag($0) }
                }
                .disabled(!prefs.prediction)
                Toggle("英文单词补全（默认关闭）", isOn: $prefs.englishCompletion)
            } footer: {
                Footnote("联想与英文补全默认关闭，可按需开启。英文补全关闭时，输入什么就保留什么，空格、数字和标点也原样输入。开启联想后按数字选词，空格、回车或 Esc 收起；联想深度决定最多连续推荐几次。")
            }
            Section {
                Toggle("高亮候选拼音注音（默认关闭）", isOn: Binding(get: {prefs.pinyinHint != .off}, set: {prefs.pinyinHint = $0 ? .toned : .off}))
            } header: {
                Text("拼音提示")
            } footer: {
                Footnote("打开后只在当前高亮的候选词后面用括号标出完整带声调拼音，例如 银行(yín háng)，其他候选不显示。轻声按规范不添加声调符号。")
            }
            Section("输出") {
                Toggle("自动学习用户词", isOn: $prefs.learning)
                Toggle("中文全角标点", isOn: $prefs.fullWidthPunctuation)
                Toggle("成对符号", isOn: $prefs.autoPair).disabled(!prefs.fullWidthPunctuation)
                Toggle("繁体输出", isOn: $prefs.traditional)
                Toggle("表情候选", isOn: $prefs.emoji)
            }
            if prefs.extensionEnabled("scheme:wubi86") {
            Section {
                Toggle("四码唯一时自动上屏", isOn: $prefs.wubiAutoCommit)
                Toggle("z 键拼音反查", isOn: $prefs.wubiPinyinLookup)
                Toggle("编码补全提示", isOn: $prefs.wubiCompletion)
            } header: { Text("五笔") } footer: {
                Footnote("输入 z 后接拼音，可查不熟悉的字；编码补全显示尚未输入的编码。")
            }.disabled(prefs.schema != "wubi86")
            }
            if prefs.extensionEnabled("scheme:hand") {
            Section {
                Toggle("多字连写", isOn: $prefs.handLine)
                Picker("停笔自动上屏", selection: $prefs.handPause) {
                    Text("快").tag(HandPause.fast)
                    Text("中").tag(HandPause.medium)
                    Text("慢").tag(HandPause.slow)
                }
            } header: {
                Text("手写")
            } footer: {
                Footnote("写完停笔多久，把第一个候选上屏；写得慢选「慢」。连写模式等待时间加倍。从系统输入法菜单打开「织文手写」。")
            }
            }
        }
        .formStyle(.grouped)
    }
}

struct AppearancePage: View {
    @ObservedObject var prefs: Preferences
    @ObservedObject private var store: ExtensionStore = .shared

    var body: some View {
        Form {
            Section {
                LazyVGrid(columns:[GridItem(.adaptive(minimum:125),spacing:12)],spacing:12) {
                    ForEach(store.themes,id:\.self) {theme in
                        ThemeChoice(theme:theme,selected:prefs.colorTheme==theme) {prefs.colorTheme=theme}
                    }
                }.padding(.vertical,6)
                Button("在插件市场添加主题…") {SettingsWindow.shared.show(page:.market)}
                Picker("明暗模式",selection:$prefs.appearance) {
                    Text("跟随系统").tag(AppearanceMode.system)
                    Text("浅色").tag(AppearanceMode.light)
                    Text("深色").tag(AppearanceMode.dark)
                }.pickerStyle(.segmented)
            } header: {Text("主题")} footer: {
                Footnote("配色和明暗模式会立即应用到设置、候选窗及各个工具窗口。")
            }
            Section("预览") {
                HStack {
                    Spacer()
                    CandidateBar(state: sample, pick: { _ in },theme:prefs.colorTheme)
                        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: CandidatePanel.cornerRadius))
                        .shadow(color: .black.opacity(0.12), radius: 6, y: 2)
                        .environment(\.colorScheme, previewScheme ?? .light)
                    Spacer()
                }
                .padding(.vertical, 8)
            }
            Section("候选窗") {
                Picker("排列", selection: $prefs.orientation) {
                    Text("横排").tag(CandidateOrientation.horizontal)
                    Text("竖排").tag(CandidateOrientation.vertical)
                }
                .pickerStyle(.segmented)
                Stepper(value: $prefs.fontSize, in: Preferences.fontSizes) {
                    LabeledContent("字号", value: "\(prefs.fontSize) pt")
                }
            }
        }
        .formStyle(.grouped)
    }

    @Environment(\.colorScheme) private var systemScheme

    private var previewScheme: ColorScheme? {
        switch prefs.appearance {
        case .system: return systemScheme
        case .light: return .light
        case .dark: return .dark
        }
    }

    private var sample: CandidateState {
        let words = ["织文", "知闻", "之文", "只闻", "支文", "职位", "直闻", "至文", "止闻"]
        let readings=["zhī wén","zhī wén","zhī wén","zhǐ wén","zhī wén","zhí wèi","zhí wén","zhì wén","zhǐ wén"]
        return CandidateState(preedit: "zhi'wen", candidates: Array(words.prefix(min(prefs.pageSize,5)).enumerated()).map { Candidate(text: $0.element,pinyin:prefs.pinyinHint == .off ? "" : readings[$0.offset]) },
                              highlight: 0, hasPrevious: false, hasNext: true, orientation: prefs.orientation,
                              fontSize: CGFloat(prefs.fontSize))
    }
}

private struct ThemeChoice:View {
    let theme:ColorTheme
    let selected:Bool
    let choose:()->Void
    private var palette:ThemePalette {Theme.palette(theme)}
    var body:some View {
        Button(action:choose) {
            VStack(alignment:.leading,spacing:10) {
                VStack(alignment:.leading,spacing:7) {
                    HStack(spacing:3) {ForEach(0..<3) {_ in Circle().fill(palette.secondary.opacity(0.3)).frame(width:4,height:4)};Spacer()}
                    HStack(spacing:5) {
                        Text("织文").font(.system(size:14,weight:.medium)).foregroundStyle(palette.accent)
                            .padding(.horizontal,6).padding(.vertical,4).background(palette.accentSoft,in:RoundedRectangle(cornerRadius:5))
                        Text("你好").font(.system(size:13)).foregroundStyle(palette.label)
                    }
                }.padding(10).frame(maxWidth:.infinity).background(palette.canvas,in:RoundedRectangle(cornerRadius:8))
                HStack {Text(theme.title).font(.system(size:13,weight:.medium));Spacer();Image(systemName:selected ? "checkmark.circle.fill" : "circle").foregroundStyle(selected ? palette.accent : palette.secondary.opacity(0.35))}
            }.padding(10).background(palette.surface,in:RoundedRectangle(cornerRadius:12))
                .overlay(RoundedRectangle(cornerRadius:12).stroke(selected ? palette.accent : palette.divider,lineWidth:selected ? 1.5 : 1))
        }.buttonStyle(.plain).help("使用\(theme.title)主题").accessibilityLabel("\(theme.title)主题").accessibilityValue(selected ? "已选择" : "")
    }
}
