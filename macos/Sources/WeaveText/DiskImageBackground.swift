import AppKit

/// 磁盘映像窗口的背景图（make-dmg.sh 调用 `WeaveText --render-dmg-background <目录>`）。
/// 坐标以窗口左上角为原点，与 make-dmg.sh 里 Finder 的图标位置一致。
/// The disk image window's background (make-dmg.sh runs `WeaveText --render-dmg-background <dir>`). Coordinates start
/// at the window's top left, matching the Finder icon positions in make-dmg.sh.
enum DiskImageBackground {
    static let size = NSSize(width: 600, height: 400)
    /// 安装包图标的中心。 The centre of the installer package icon.
    static let packageIcon = NSPoint(x: 190, y: 190)

    static func render(into dir: URL) throws {
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        for scale in [1, 2] {
            let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(size.width) * scale,
                                       pixelsHigh: Int(size.height) * scale, bitsPerSample: 8, samplesPerPixel: 4,
                                       hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0,
                                       bitsPerPixel: 0)!
            rep.size = size
            NSGraphicsContext.saveGraphicsState()
            // 翻转成左上角原点（文字也要按翻转的上下文来画）。 Flip to a top-left origin, as a flipped context so text
            // draws the right way up.
            let cg = NSGraphicsContext(bitmapImageRep: rep)!.cgContext
            cg.translateBy(x: 0, y: size.height)
            cg.scaleBy(x: 1, y: -1)
            NSGraphicsContext.current = NSGraphicsContext(cgContext: cg, flipped: true)
            draw()
            NSGraphicsContext.restoreGraphicsState()
            guard let png = rep.representation(using: .png, properties: [:]) else { throw CocoaError(.fileWriteUnknown) }
            try png.write(to: dir.appendingPathComponent(scale == 1 ? "background.png" : "background@2x.png"))
        }
    }

    private static func draw() {
        let bounds = NSRect(origin: .zero, size: size)
        NSGradient(starting: Theme.rgb(0xFBFCFF), ending: Theme.rgb(0xE8EFFD))?.draw(in: bounds, angle: 90)

        // 安装包图标后面一圈淡淡的光。 A soft glow behind the package icon.
        let glow = NSGradient(colors: [Theme.rgb(0x2E6CF6).withAlphaComponent(0.16), Theme.rgb(0x2E6CF6).withAlphaComponent(0)])
        glow?.draw(fromCenter: packageIcon, radius: 0, toCenter: packageIcon, radius: 110, options: [])

        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
        text("织文输入法", size: 22, weight: .semibold, color: Theme.rgb(0x1F2937), centerY: 44)
        text("v\(version) · macOS 13 及以上", size: 12, weight: .regular, color: Theme.rgb(0x6B7280), centerY: 72)

        // 提示条。 The hint pill.
        let hint = attributed("双击安装包，按提示完成安装", size: 15, weight: .medium, color: Theme.rgb(0x2257D1))
        let hs = hint.size()
        let pill = NSRect(x: (size.width - hs.width) / 2 - 18, y: 318 - hs.height / 2 - 8, width: hs.width + 36,
                          height: hs.height + 16)
        Theme.rgb(0x2E6CF6).withAlphaComponent(0.1).setFill()
        NSBezierPath(roundedRect: pill, xRadius: pill.height / 2, yRadius: pill.height / 2).fill()
        hint.draw(at: NSPoint(x: pill.midX - hs.width / 2, y: pill.midY - hs.height / 2))
        text("macOS 拦下安装包时：系统设置 › 隐私与安全性 › 仍要打开（详见「使用说明」）", size: 11, weight: .regular, color: Theme.rgb(0x8A94A6),
             centerY: 362)
    }

    private static func attributed(_ s: String, size: CGFloat, weight: NSFont.Weight, color: NSColor) -> NSAttributedString {
        NSAttributedString(string: s, attributes: [.font: NSFont.systemFont(ofSize: size, weight: weight), .foregroundColor: color])
    }

    private static func text(_ s: String, size: CGFloat, weight: NSFont.Weight, color: NSColor, centerY: CGFloat) {
        let a = attributed(s, size: size, weight: weight, color: color)
        let sz = a.size()
        a.draw(at: NSPoint(x: (self.size.width - sz.width) / 2, y: centerY - sz.height / 2))
    }
}
