import AppKit
import SwiftUI

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
