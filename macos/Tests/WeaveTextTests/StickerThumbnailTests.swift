import AppKit
import Testing
import WeaveCore
@testable import WeaveText

/**
 * 收纳窗的缩略图读原文件，动图必须保留全部帧，否则网格里只会看到第一帧。
 * The shelf thumbnail reads the stored file; an animation must keep every frame or the grid only shows the first one.
 */
@MainActor @Suite struct StickerThumbnailTests {
    private var fixtures: URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent().appendingPathComponent("tests/fixtures/stickers")
    }

    private func frames(_ url: URL) -> Int {
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return 0 }
        return CGImageSourceGetCount(source)
    }

    @Test func animatedThumbnailKeepsEveryFrame() throws {
        let url = fixtures.appendingPathComponent("animated.gif")
        #expect(frames(url) > 1)
        let image = try #require(NSImage(contentsOf: url))
        let rep = try #require(image.representations.first as? NSBitmapImageRep)
        #expect(rep.value(forProperty: .frameCount) as? Int == frames(url))
        let view = NSImageView()
        view.animates = true
        view.image = image
        #expect(view.animates)
    }

    @Test func stillThumbnailLoadsAsASingleImage() throws {
        let url = fixtures.appendingPathComponent("sample.png")
        #expect(frames(url) == 1)
        #expect(NSImage(contentsOf: url) != nil)
    }
}
