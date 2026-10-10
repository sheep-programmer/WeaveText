import AppKit
import SwiftUI
import WeaveCore

/// 与 Android「清爽」主题一致的配色（styles/theme-fresh.json）。 Colours of the Android "fresh" theme.
enum Theme {
    static let accent = dynamic(light: 0x2E6CF6, dark: 0x3B6FE6)
    static let accentSoft = dynamic(light: 0xDCE6FD, dark: 0x1F2B47)
    /// 高亮候选文字。 Highlighted candidate text.
    static let candidate = dynamic(light: 0x255FE0, dark: 0x7FA6FF)
    static let label = dynamic(light: 0x1B1E23, dark: 0xE9EBEF)
    static let hint = dynamic(light: 0x737983, dark: 0x9AA0AA)
    static let secondary = dynamic(light: 0x5E6570, dark: 0xA2A8B2)
    static let divider = dynamic(light: 0xD5DAE1, dark: 0x2A2C31)
    static func palette(_ style:ColorTheme)->ThemePalette {ThemePalette(style:style)}

    static func dynamic(light: UInt32, dark: UInt32) -> Color {
        Color(nsColor: NSColor(name: nil) { appearance in
            let isDark = appearance.bestMatch(from: [.aqua, .darkAqua]) == .darkAqua
            return rgb(isDark ? dark : light)
        })
    }

    static func rgb(_ v: UInt32) -> NSColor {
        NSColor(srgbRed: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255,
                blue: CGFloat(v & 0xFF) / 255, alpha: 1)
    }
}

struct ThemePalette {
    let style:ColorTheme
    private var definition: ThemeDefinition? { ExtensionStore.shared.theme(style.rawValue) }
    var accentHex:(UInt32,UInt32) {
        let accent = definition?.colors("accent", fallback:(0x2E6CF6,0x7FA6FF)) ?? (0x2E6CF6,0x7FA6FF)
        // The keyboard's dark accent fills keys; its candidate colour is legible for small UI labels.
        let text = definition?.colors("candidate",fallback:accent) ?? accent
        return (accent.0,text.1)
    }
    var canvasHex:(UInt32,UInt32) { definition?.colors("background", fallback:(0xF5F7FB,0x171A21)) ?? (0xF5F7FB,0x171A21) }
    private func color(_ key: String, _ fallback: (UInt32,UInt32)) -> Color {
        let values = definition?.colors(key, fallback: fallback) ?? fallback
        return Theme.dynamic(light:values.0,dark:values.1)
    }
    var accent:Color {Theme.dynamic(light:accentHex.0,dark:accentHex.1)}
    var accentSoft:Color {color("accentSoft",(0xDCE6FD,0x1F2B47))}
    var candidate:Color {color("candidate",(accentHex.0,accentHex.1))}
    var label:Color {color("label",(0x1B1E23,0xE9EBEF))}
    var secondary:Color {color("labelSecondary",(0x5E6570,0xA2A8B2))}
    var hint:Color {color("labelHint",(0x737983,0x9AA0AA))}
    var canvas:Color {Theme.dynamic(light:canvasHex.0,dark:canvasHex.1)}
    var surface:Color {color("card",(0xFFFFFF,0x242830))}
    var sidebar:Color {canvas}
    var divider:Color {color("divider",(0xDCE1E8,0x343A42))}
}

/// AppKit windows and their title bars must receive the same preference as SwiftUI content.
final class WindowAppearance {
    static let shared=WindowAppearance(prefs:.shared)
    private let prefs:Preferences
    private let windows=NSHashTable<NSWindow>.weakObjects()
    private var observer:NSObjectProtocol?
    private var extensionKeys:[ObjectIdentifier:String] = [:]
    init(prefs:Preferences) {
        self.prefs=prefs
        observer=NotificationCenter.default.addObserver(forName:Preferences.didChange,object:prefs,queue:.main) {[weak self] _ in self?.refresh()}
    }
    deinit {if let observer {NotificationCenter.default.removeObserver(observer)}}
    func track(_ window:NSWindow, extensionKey:String? = nil) {
        if let extensionKey { extensionKeys[ObjectIdentifier(window)] = extensionKey }
        windows.add(window);apply(window)
    }
    private func refresh() {for window in windows.allObjects {apply(window)}}
    private func apply(_ window:NSWindow) {
        if let key = extensionKeys[ObjectIdentifier(window)], !prefs.extensionEnabled(key), window.isVisible { window.close() }
        window.appearance=CandidatePanel.appearance(prefs.appearance)
        window.titlebarAppearsTransparent=true
        window.contentView?.needsDisplay=true
        window.invalidateShadow()
    }
}

private struct WeaveStyle:ViewModifier {
    @ObservedObject var prefs:Preferences
    func body(content:Content)->some View {
        content.tint(Theme.palette(prefs.colorTheme).accent).accentColor(Theme.palette(prefs.colorTheme).accent)
            .preferredColorScheme(prefs.appearance == .system ? nil : prefs.appearance == .dark ? .dark : .light)
    }
}
extension View {
    func weaveStyle(_ prefs:Preferences = .shared)->some View {modifier(WeaveStyle(prefs:prefs))}
}

struct WindowHeading:View {
    let title:String
    let subtitle:String
    let symbol:String
    @ObservedObject var prefs:Preferences = .shared
    var body:some View {
        HStack(spacing:10) {
            Image(systemName:symbol).font(.system(size:17,weight:.medium)).foregroundStyle(Theme.palette(prefs.colorTheme).accent)
                .frame(width:38,height:38).background(Theme.palette(prefs.colorTheme).accentSoft,in:RoundedRectangle(cornerRadius:11))
            VStack(alignment:.leading,spacing:3) {Text(title).font(.system(size:17,weight:.semibold));Text(subtitle).font(.caption).foregroundStyle(.secondary)}
            Spacer(minLength:0)
        }
    }
}
