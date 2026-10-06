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
    var accentHex:(UInt32,UInt32) {
        switch style {case .fresh:return(0x316CE8,0x8AB0FF);case .paper:return(0x886044,0xD1AE88);case .mint:return(0x087E69,0x6DD6BA);case .violet:return(0x7653C7,0xBEA6FF)}
    }
    var canvasHex:(UInt32,UInt32) {
        switch style {case .fresh:return(0xF5F7FB,0x171A21);case .paper:return(0xF7F4ED,0x24211C);case .mint:return(0xF1F7F4,0x172520);case .violet:return(0xF5F2FA,0x221D2D)}
    }
    var accent:Color {Theme.dynamic(light:accentHex.0,dark:accentHex.1)}
    var accentSoft:Color {accent.opacity(0.12)}
    var candidate:Color {accent}
    var label:Color {Color(nsColor:.labelColor)}
    var secondary:Color {Color(nsColor:.secondaryLabelColor)}
    var hint:Color {Color(nsColor:.secondaryLabelColor)}
    var canvas:Color {Theme.dynamic(light:canvasHex.0,dark:canvasHex.1)}
    var surface:Color {Theme.dynamic(light:0xFFFFFF,dark:0x242830)}
    var sidebar:Color {canvas}
    var divider:Color {Theme.dynamic(light:0xDCE1E8,dark:0x343A42)}
}

/// AppKit windows and their title bars must receive the same preference as SwiftUI content.
final class WindowAppearance {
    static let shared=WindowAppearance(prefs:.shared)
    private let prefs:Preferences
    private let windows=NSHashTable<NSWindow>.weakObjects()
    private var observer:NSObjectProtocol?
    init(prefs:Preferences) {
        self.prefs=prefs
        observer=NotificationCenter.default.addObserver(forName:Preferences.didChange,object:prefs,queue:.main) {[weak self] _ in self?.refresh()}
    }
    deinit {if let observer {NotificationCenter.default.removeObserver(observer)}}
    func track(_ window:NSWindow) {windows.add(window);apply(window)}
    private func refresh() {for window in windows.allObjects {apply(window)}}
    private func apply(_ window:NSWindow) {
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
