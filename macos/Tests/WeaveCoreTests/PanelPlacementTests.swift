import CoreGraphics
import Testing
@testable import WeaveCore

private let main = CGRect(x: 0, y: 0, width: 1440, height: 875)
/// 左边一块副屏，y 偏移。 A secondary screen to the left, shifted in y.
private let left = CGRect(x: -1920, y: -200, width: 1920, height: 1055)
private let size = CGSize(width: 300, height: 40)

@Suite struct PanelPlacementTests {
    @Test func belowTheCaret() {
        let caret = CGRect(x: 100, y: 500, width: 2, height: 18)
        let o = PanelPlacement.origin(size: size, caret: caret, screens: [main, left])
        #expect(o == CGPoint(x: 100, y: 500 - PanelPlacement.gap - 40))
    }

    @Test func flipsAboveAtTheBottomEdge() {
        let caret = CGRect(x: 100, y: 20, width: 2, height: 18)
        let o = PanelPlacement.origin(size: size, caret: caret, screens: [main])
        #expect(o.y == 38 + PanelPlacement.gap)
    }

    @Test func clampsAtTheRightEdge() {
        let caret = CGRect(x: 1400, y: 500, width: 2, height: 18)
        let o = PanelPlacement.origin(size: size, caret: caret, screens: [main])
        #expect(o.x == CGFloat(1140))
    }

    @Test func staysOnTheCaretsScreen() {
        let caret = CGRect(x: -100, y: -180, width: 2, height: 18)
        let o = PanelPlacement.origin(size: size, caret: caret, screens: [main, left])
        // 靠副屏右缘、并在其底边上翻。 Clamped to the left screen's right edge and flipped at its bottom.
        #expect(o.x == CGFloat(-300))
        #expect(o.y == -162 + PanelPlacement.gap)
        #expect(PanelPlacement.screen(for: caret, in: [main, left]) == left)
    }

    @Test func offScreenCaretUsesTheNearestScreen() {
        let caret = CGRect(x: 200, y: 2000, width: 2, height: 18)
        #expect(PanelPlacement.screen(for: caret, in: [left, main]) == main)
    }

    @Test func tooTallForEitherSideSitsOnTheBottom() {
        let caret = CGRect(x: 10, y: 20, width: 2, height: 18)
        let o = PanelPlacement.origin(size: CGSize(width: 100, height: 900), caret: caret, screens: [main])
        #expect(o.y == 0)
    }
}
