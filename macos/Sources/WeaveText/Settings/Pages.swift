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

// SwiftUI 的 @State 在新 SDK 里是宏，命令行工具不带它的插件，所以页面状态都放在 ObservableObject 里。
// @State is a macro in the newer SDK and the command-line tools lack its plugin, so page state lives in ObservableObjects.

struct GeneralPage: View {
    @ObservedObject var prefs: Preferences
    @StateObject private var login = LoginItem()

    var body: some View {
        Form {
            Section {
                Toggle("登录时启动", isOn: Binding(get: { login.enabled }, set: { login.set($0) }))
                Toggle("在菜单栏显示图标", isOn: $prefs.showStatusItem)
            } header: {
                Text("启动与菜单栏")
            } footer: {
                if let error = login.error { Footnote(error) } else {
                    Footnote("隐藏图标后，可从系统输入法菜单里的「设置…」回到这里。")
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
                }
            }
        }
        .formStyle(.grouped)
    }
}

struct SchemesPage: View {
    @ObservedObject var prefs: Preferences

    private var pinyinFamily: Bool { prefs.schema == "pinyin" || InputScheme.named(prefs.schema).isShuangpin }

    var body: some View {
        Form {
            Section("输入方案") {
                Picker("方案", selection: $prefs.schema) {
                    ForEach(InputScheme.all) { scheme in
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
                Toggle("联想词", isOn: $prefs.prediction)
            } footer: {
                Footnote("上屏后推荐下一个词，越用越懂你的搭配。按数字选，空格、回车或 Esc 收起。")
            }
            Section("输出") {
                Toggle("繁体输出", isOn: $prefs.traditional)
                Toggle("表情候选", isOn: $prefs.emoji)
            }
        }
        .formStyle(.grouped)
    }
}

struct AppearancePage: View {
    @ObservedObject var prefs: Preferences

    var body: some View {
        Form {
            Section("预览") {
                HStack {
                    Spacer()
                    CandidateBar(state: sample, pick: { _ in })
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
                Picker("深浅色", selection: $prefs.appearance) {
                    Text("跟随系统").tag(AppearanceMode.system)
                    Text("浅色").tag(AppearanceMode.light)
                    Text("深色").tag(AppearanceMode.dark)
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
        return CandidateState(preedit: "zhi wen", candidates: words.prefix(prefs.pageSize).map { Candidate(text: $0) },
                              highlight: 0, hasPrevious: false, hasNext: true, orientation: prefs.orientation,
                              fontSize: CGFloat(prefs.fontSize))
    }
}
