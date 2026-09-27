import AppKit
import SwiftUI
import WeaveCore

enum SettingsPage: String, CaseIterable, Identifiable {
    case general, schemes, appearance, dictionary, link, about

    var id: String { rawValue }

    var title: String {
        switch self {
        case .general: return "常规"
        case .schemes: return "输入方案"
        case .appearance: return "外观"
        case .dictionary: return "词库"
        case .link: return "互联"
        case .about: return "关于"
        }
    }

    var symbol: String {
        switch self {
        case .general: return "gearshape"
        case .schemes: return "keyboard"
        case .appearance: return "paintbrush"
        case .dictionary: return "character.book.closed"
        case .link: return "iphone.and.arrow.forward"
        case .about: return "info.circle"
        }
    }
}

final class SettingsNavigation: ObservableObject {
    @Published var page: SettingsPage? = .general
}

/// 设置窗口：只建一个，再次打开时提到最前。 The settings window: created once, brought to front when reopened.
final class SettingsWindow: NSObject, NSWindowDelegate {
    static let shared = SettingsWindow()

    private var window: NSWindow?
    private let navigation = SettingsNavigation()

    func show(page: SettingsPage? = nil) {
        if let page { navigation.page = page }
        if window == nil {
            let root = SettingsRoot(prefs: .shared, navigation: navigation).tint(Theme.accent)
            let w = NSWindow(contentViewController: NSHostingController(rootView: root))
            w.title = "织文输入法设置"
            w.styleMask = [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView]
            w.setContentSize(NSSize(width: 720, height: 520))
            w.contentMinSize = NSSize(width: 620, height: 420)
            w.isReleasedWhenClosed = false
            w.delegate = self
            w.center()
            w.setFrameAutosaveName("WeaveTextSettings")
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

    var body: some View {
        NavigationSplitView {
            List(SettingsPage.allCases, selection: $navigation.page) { page in
                Label(page.title, systemImage: page.symbol).tag(page)
            }
            .navigationSplitViewColumnWidth(min: 150, ideal: 170, max: 220)
        } detail: {
            Group {
                switch navigation.page ?? .general {
                case .general: GeneralPage(prefs: prefs)
                case .schemes: SchemesPage(prefs: prefs)
                case .appearance: AppearancePage(prefs: prefs)
                case .dictionary: DictionaryPage()
                case .link: LinkPage(prefs: prefs, link: .shared)
                case .about: AboutPage()
                }
            }
            .navigationTitle((navigation.page ?? .general).title)
        }
        .preferredColorScheme(scheme)
    }

    private var scheme: ColorScheme? {
        switch prefs.appearance {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
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
