import CoreGraphics

/// 候选窗放在哪（Cocoa 屏幕坐标，原点在左下）。 Where the candidate panel goes (Cocoa screen coordinates, origin bottom-left).
public enum PanelPlacement {
    /// 与光标的间距。 Gap to the caret.
    public static let gap: CGFloat = 4

    /// 默认放在光标下方，下方放不下就翻到上方；左右夹在屏幕可见区内。
    /// Below the caret by default, flipped above when it would leave the screen; clamped horizontally.
    /// - Parameters:
    ///   - caret: 光标所在行的矩形。 The caret's line rectangle.
    ///   - screens: 各屏幕的 visibleFrame。 Every screen's visibleFrame.
    public static func origin(size: CGSize, caret: CGRect, screens: [CGRect]) -> CGPoint {
        let visible = screen(for: caret, in: screens)
        var x = caret.minX
        var y = caret.minY - gap - size.height
        if y < visible.minY {
            let above = caret.maxY + gap
            // 上方也放不下时贴着可见区底边。 If above does not fit either, sit on the bottom edge.
            y = above + size.height <= visible.maxY ? above : visible.minY
        }
        if x + size.width > visible.maxX { x = visible.maxX - size.width }
        if x < visible.minX { x = visible.minX }
        return CGPoint(x: x, y: y)
    }

    /// 光标所在的屏；不在任何屏上时取最近的。 The screen holding the caret, else the nearest one.
    public static func screen(for caret: CGRect, in screens: [CGRect]) -> CGRect {
        let p = CGPoint(x: caret.minX, y: caret.midY)
        if let hit = screens.first(where: { $0.contains(p) }) { return hit }
        return screens.min { distance(p, $0) < distance(p, $1) } ?? CGRect(x: 0, y: 0, width: 1440, height: 900)
    }

    private static func distance(_ p: CGPoint, _ r: CGRect) -> CGFloat {
        let dx = max(r.minX - p.x, 0, p.x - r.maxX)
        let dy = max(r.minY - p.y, 0, p.y - r.maxY)
        return dx * dx + dy * dy
    }
}
