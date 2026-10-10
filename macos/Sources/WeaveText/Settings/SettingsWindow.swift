import AppKit
import SwiftUI
import WeaveCore

enum SettingsPage: String, CaseIterable, Identifiable {
    case home, market, general, schemes, appearance, dictionary, plugins, translation, tools, link, about

    var id: String { rawValue }

    var title: String {
        switch self {
        case .home: return "概览"
        case .market: return "插件市场"
        case .general: return "常规"
        case .schemes: return "输入方案"
        case .appearance: return "外观"
        case .dictionary: return "词库"
        case .plugins: return "语音引擎"
        case .translation: return "翻译"
        case .tools: return "输入工具"
        case .link: return "互联"
        case .about: return "关于"
        }
    }

    var symbol: String {
        switch self {
        case .home: return "square.grid.2x2"
        case .market: return "puzzlepiece.extension"
        case .general: return "gearshape"
        case .schemes: return "keyboard"
        case .appearance: return "paintbrush"
        case .dictionary: return "character.book.closed"
        case .plugins: return "puzzlepiece.extension"
        case .translation: return "character.bubble"
        case .tools: return "tray.full"
        case .link: return "iphone.and.arrow.forward"
        case .about: return "info.circle"
        }
    }
    var subtitle:String {
        switch self {
        case .home:return "你的键盘、主题与输入工具"
        case .market:return "按需添加功能，自由搭配主题与引擎"
        case .general:return "按你的习惯调整切换方式与快捷键"
        case .schemes:return "选择方案，管理注音、纠错与学习"
        case .appearance:return "选择主题与明暗模式，改动立即生效"
        case .dictionary:return "管理常用词、快捷短语与专业词库"
        case .plugins:return "添加插件仓库，配置你的语音引擎"
        case .translation:return "配置翻译服务，选中文字后翻译并确认写回"
        case .tools:return "整理剪贴板、常用语与个人资料"
        case .link:return "连接手机，直接传送文字与文件"
        case .about:return "版本信息、隐私说明与开源许可"
        }
    }
}

final class SettingsNavigation: ObservableObject {
    @Published var page: SettingsPage? = .home
}

/// 设置窗口：只建一个，再次打开时提到最前。 The settings window: created once, brought to front when reopened.
final class SettingsWindow: NSObject, NSWindowDelegate {
    static let shared = SettingsWindow()

    private var window: NSWindow?
    private let navigation = SettingsNavigation()

    func show(page: SettingsPage? = nil) {
        if let page { navigation.page = page }
        if window == nil {
            let root = SettingsRoot(prefs: .shared, navigation: navigation)
            let w = NSWindow(contentViewController: NSHostingController(rootView: root))
            w.title = "织文输入法设置"
            w.styleMask = [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView]
            w.setContentSize(NSSize(width: 880, height: 660))
            w.contentMinSize = NSSize(width: 780, height: 560)
            w.isReleasedWhenClosed = false
            w.delegate = self
            w.center()
            w.setFrameAutosaveName("WeaveTextSettings")
            WindowAppearance.shared.track(w)
            window = w
        }
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
        window?.orderFrontRegardless()
    }
}

struct SettingsRoot: View {
    @ObservedObject var prefs: Preferences
    @ObservedObject var navigation: SettingsNavigation
    var extensionStore:ExtensionStore = .shared
    var pluginModel:PluginCenter? = nil
    var toolsModel:ToolsModel? = nil

    var body: some View {
        HStack(spacing:0) {
            VStack(alignment:.leading,spacing:16) {
                HStack(spacing:9) {
                    Image(systemName:"character.cursor.ibeam").font(.system(size:21,weight:.medium))
                        .foregroundStyle(palette.accent).frame(width:40,height:40).background(palette.accentSoft,in:RoundedRectangle(cornerRadius:12))
                    VStack(alignment:.leading,spacing:3) {Text("织文").font(.system(size:20,weight:.semibold));Text("输入法设置").font(.caption).foregroundStyle(.secondary)}
                }.padding(.top,12)
                ScrollView(showsIndicators:false) {
                VStack(alignment:.leading,spacing:3) {
                    ForEach(visiblePages) {page in
                        if page == .schemes || page == .market || page == .about {
                            Text(page == .schemes ? "键盘" : page == .market ? "扩展与工具" : "应用").font(.system(size:10,weight:.medium)).foregroundStyle(.secondary).padding(.top,12).padding(.bottom,3).padding(.leading,12)
                        }
                        Button {navigation.page=page} label:{
                            HStack(spacing:10) {
                                Image(systemName:page.symbol).font(.system(size:14,weight:.medium)).frame(width:20)
                                Text(page.title).font(.system(size:13,weight:selection==page ? .semibold : .regular))
                                Spacer()
                            }.padding(.horizontal,12).padding(.vertical,8)
                                .foregroundStyle(selection==page ? palette.accent : palette.label)
                                .background(selection==page ? palette.accentSoft : Color.clear,in:RoundedRectangle(cornerRadius:9))
                        }.buttonStyle(.plain).accessibilityValue(selection==page ? "已选择" : "")
                    }
                }
                }
                Spacer(minLength:8)
                VStack(alignment:.leading,spacing:8) {
                    Text("明暗模式").font(.caption).foregroundStyle(.secondary)
                    Picker("明暗模式",selection:$prefs.appearance) {
                        Image(systemName:"circle.lefthalf.filled").tag(AppearanceMode.system)
                        Image(systemName:"sun.max").tag(AppearanceMode.light)
                        Image(systemName:"moon").tag(AppearanceMode.dark)
                    }.pickerStyle(.segmented).labelsHidden().help("跟随系统 / 浅色 / 深色")
                    Button("主题与外观") {navigation.page = .appearance}.font(.caption).buttonStyle(.plain).foregroundStyle(palette.accent)
                }.padding(.bottom,12)
            }.padding(.horizontal,18).frame(width:202).background(palette.sidebar)
            Rectangle().fill(palette.divider).frame(width:1)
            VStack(alignment:.leading,spacing:0) {
                VStack(alignment:.leading,spacing:5) {
                    Text(selection.title).font(.system(size:25,weight:.semibold))
                    Text(selection.subtitle).font(.system(size:12)).foregroundStyle(.secondary)
                }.frame(maxWidth:.infinity,alignment:.leading).padding(.horizontal,26).padding(.top,28).padding(.bottom,20)
                detail.frame(maxWidth:.infinity,maxHeight:.infinity)
                    .scrollContentBackground(.hidden)
            }
            .background(palette.canvas)
        }.weaveStyle(prefs)
    }
    private var visiblePages:[SettingsPage] {
        [.home,.schemes,.appearance,.general,.dictionary,.market,.plugins,.translation,.tools,.link,.about].filter { page in
            switch page {case .plugins:return prefs.extensionEnabled("feature:voice");case .translation:return prefs.extensionEnabled("feature:translate");case .link:return prefs.extensionEnabled("feature:link");default:return true}
        }
    }
    private var selection:SettingsPage {let page=navigation.page ?? .home;return visiblePages.contains(page) ? page : .market}
    private var palette:ThemePalette {Theme.palette(prefs.colorTheme)}
    @ViewBuilder private var detail:some View {
            Group {
                switch selection {
                case .home: SettingsHomePage(prefs:prefs,navigation:navigation)
                case .market: MarketPage(prefs:prefs,store:extensionStore,navigation:navigation)
                case .general: GeneralPage(prefs: prefs)
                case .schemes: SchemesPage(prefs: prefs)
                case .appearance: AppearancePage(prefs: prefs)
                case .dictionary: DictionaryPage(prefs:prefs,packs: EngineHost.shared.packs, cloud: EngineHost.shared.cloud)
                case .plugins: PluginsPage(model: pluginModel ?? .shared, prefs: prefs)
                case .translation: Form { TranslationSettingsView() }.formStyle(.grouped)
                case .tools: ToolsPage(model: toolsModel ?? .shared, prefs: prefs)
                case .link: LinkPage(prefs: prefs, link: .shared)
                case .about: AboutPage()
                }
            }
    }
}

/// 卡片分组里的说明文字。 Explanatory footnote under a card group.
struct Footnote: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(.footnote)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}
