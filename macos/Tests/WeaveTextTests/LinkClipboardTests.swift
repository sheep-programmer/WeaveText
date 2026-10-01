import AppKit
import Testing
import WeaveCore
@testable import WeaveText

@MainActor @Suite struct LinkClipboardTests {
    @Test func copiedImageWithTextKeepsPixelsAndFileURLsTakePriority() throws {
        let pb = NSPasteboard.withUniqueName()
        defer { pb.releaseGlobally() }
        let bitmap = try #require(NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 2, pixelsHigh: 2,
            bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0))
        let png = try #require(bitmap.representation(using: .png, properties: [:]))
        pb.declareTypes([.png, .string], owner: nil)
        pb.setData(png, forType: .png); pb.setString("https://example.com/photo", forType: .string)
        guard case .image(let result) = LinkClipboard.read(pb) else { Issue.record("图片被文字表示覆盖"); return }
        #expect(result == png)
        pb.clearContents(); pb.writeObjects([URL(fileURLWithPath: "/tmp/剪贴板测试.pdf") as NSURL])
        pb.setData(png, forType: .png)
        guard case .files(let urls) = LinkClipboard.read(pb) else { Issue.record("文件被图标表示覆盖"); return }
        #expect(urls.first?.lastPathComponent == "剪贴板测试.pdf")
        pb.setData(Data(), forType: NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType"))
        guard case .empty = LinkClipboard.read(pb) else { Issue.record("敏感剪贴板被读取"); return }
    }
    @Test func directEndpointsIncludeIPv6Brackets() {
        #expect(LinkService.endpoint("2001:db8::1") == "[2001:db8::1]:47811")
        #expect(LinkService.endpoint("[2001:db8::1]:40000") == "[2001:db8::1]:40000")
        #expect(LinkService.endpoint("192.168.1.2") == "192.168.1.2:47811")
    }
}
