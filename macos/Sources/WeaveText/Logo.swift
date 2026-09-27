import AppKit

/// 织文的标志：圆环里一道 W 形折线（与 Android ic_logo 相同的 24 单位画布）。
/// The WeaveText mark: a W-shaped stroke in a ring (same 24-unit canvas as Android's ic_logo).
enum Logo {
    /// W 折线，y 向上。 The W stroke, y up.
    static let zigzag: [CGPoint] = [(7.5, 9.5), (9.75, 15), (12, 10.5), (14.25, 15), (16.5, 9.5)]
        .map { CGPoint(x: $0.0, y: 24 - $0.1) }

    /// 菜单栏模板图（黑色描边，系统自动适配深浅色）。 Menu bar template image; the system tints it.
    static func statusImage(size: CGFloat = 18) -> NSImage {
        let image = NSImage(size: NSSize(width: size, height: size), flipped: false) { rect in
            let s = rect.width / 24
            NSColor.black.setStroke()
            let ring = NSBezierPath(ovalIn: NSRect(x: 3 * s, y: 3 * s, width: 18 * s, height: 18 * s))
            ring.lineWidth = 1.75 * s
            ring.stroke()
            let w = polyline(zigzag.map { CGPoint(x: $0.x * s, y: $0.y * s) })
            w.lineWidth = 1.75 * s
            w.stroke()
            return true
        }
        image.isTemplate = true
        return image
    }

    /// 应用图标：蓝底圆角方块上的白色 W。 App icon: a white W on a blue rounded square.
    static func drawAppIcon(in rect: NSRect) {
        let side = rect.width
        // macOS 图标网格：内容占 824/1024。 macOS icon grid: the tile is 824/1024 of the canvas.
        let inset = side * 100 / 1024
        let tile = rect.insetBy(dx: inset, dy: inset)
        let path = NSBezierPath(roundedRect: tile, xRadius: tile.width * 0.225, yRadius: tile.width * 0.225)
        NSGraphicsContext.saveGraphicsState()
        let shadow = NSShadow()
        shadow.shadowColor = NSColor.black.withAlphaComponent(0.25)
        shadow.shadowBlurRadius = side * 0.02
        shadow.shadowOffset = NSSize(width: 0, height: -side * 0.01)
        shadow.set()
        Theme.rgb(0x2E6CF6).setFill()
        path.fill()
        NSGraphicsContext.restoreGraphicsState()
        NSGradient(starting: Theme.rgb(0x5A8CFA), ending: Theme.rgb(0x2257D1))?.draw(in: path, angle: -90)
        // 与 Android 启动图标相同的 W（108 单位画布）。 The W of the Android launcher icon (108-unit canvas).
        let s = tile.width / 108 * 1.25
        let cx = tile.midX, cy = tile.midY
        let pts: [CGPoint] = [(36, 42), (44, 68), (54, 48), (64, 68), (72, 42)].map {
            CGPoint(x: cx + ($0.0 - 54) * s, y: cy - ($0.1 - 55) * s)
        }
        NSColor.white.setStroke()
        let w = polyline(pts)
        w.lineWidth = 5 * s
        w.stroke()
    }

    /// 输入法菜单里的图标：圆角框里一个「织」。 Input menu icon: 织 in a rounded box.
    static func drawInputModeIcon(in rect: NSRect) {
        let box = rect.insetBy(dx: rect.width * 0.06, dy: rect.width * 0.06)
        let path = NSBezierPath(roundedRect: box, xRadius: rect.width * 0.18, yRadius: rect.width * 0.18)
        path.lineWidth = max(1, rect.width / 16)
        NSColor.black.setStroke()
        path.stroke()
        let font = NSFont.systemFont(ofSize: rect.width * 0.62, weight: .medium)
        let text = NSAttributedString(string: "织", attributes: [.font: font, .foregroundColor: NSColor.black])
        let size = text.size()
        text.draw(at: NSPoint(x: rect.midX - size.width / 2, y: rect.midY - size.height / 2 + rect.width * 0.02))
    }

    private static func polyline(_ pts: [CGPoint]) -> NSBezierPath {
        let p = NSBezierPath()
        p.lineCapStyle = .round
        p.lineJoinStyle = .round
        p.move(to: pts[0])
        for q in pts.dropFirst() { p.line(to: q) }
        return p
    }
}

/// 构建时生成图标文件（build-app.sh 调用 `WeaveText --render-icons <目录>`）。
/// Renders icon files at build time (build-app.sh runs `WeaveText --render-icons <dir>`).
enum IconRenderer {
    static func render(into dir: URL) throws {
        let fm = FileManager.default
        let iconset = dir.appendingPathComponent("AppIcon.iconset")
        try fm.createDirectory(at: iconset, withIntermediateDirectories: true)
        for base in [16, 32, 128, 256, 512] {
            for scale in [1, 2] {
                let name = scale == 1 ? "icon_\(base)x\(base).png" : "icon_\(base)x\(base)@2x.png"
                try png(pixels: base * scale, draw: Logo.drawAppIcon).write(to: iconset.appendingPathComponent(name))
            }
        }
        // 输入法菜单图标：16 pt 的 1x 与 2x 合成一个 TIFF。 Input menu icon: 16 pt at 1x and 2x in one TIFF.
        let image = NSImage(size: NSSize(width: 16, height: 16))
        for px in [16, 32] { image.addRepresentation(bitmap(pixels: px, points: 16, draw: Logo.drawInputModeIcon)) }
        guard let tiff = image.tiffRepresentation(using: .lzw, factor: 0) else { throw CocoaError(.fileWriteUnknown) }
        try tiff.write(to: dir.appendingPathComponent("InputMode.tiff"))
    }

    private static func bitmap(pixels: Int, points: Int? = nil, draw: (NSRect) -> Void) -> NSBitmapImageRep {
        let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: pixels, pixelsHigh: pixels, bitsPerSample: 8,
                                   samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
                                   bytesPerRow: 0, bitsPerPixel: 0)!
        let pt = CGFloat(points ?? pixels)
        rep.size = NSSize(width: pt, height: pt)
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
        draw(NSRect(x: 0, y: 0, width: pt, height: pt))
        NSGraphicsContext.restoreGraphicsState()
        return rep
    }

    private static func png(pixels: Int, draw: (NSRect) -> Void) throws -> Data {
        guard let data = bitmap(pixels: pixels, draw: draw).representation(using: .png, properties: [:]) else {
            throw CocoaError(.fileWriteUnknown)
        }
        return data
    }
}
