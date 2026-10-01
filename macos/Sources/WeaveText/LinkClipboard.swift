import AppKit
import WeaveCore

/// Representation priority is shared by manual sends and automatic sync.
enum LinkClipboard {
    case files([URL]), image(Data), text(String), empty
    static func read(_ pb: NSPasteboard) -> LinkClipboard {
        let types = pb.types?.map(\.rawValue) ?? []
        guard !types.contains(where: ClipboardGuard.skippedTypes.contains) else { return .empty }
        if let urls = pb.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL], !urls.isEmpty { return .files(urls) }
        if let png = LinkService.pasteboardPNG(pb) { return .image(png.data) }
        if let text = pb.string(forType: .string), !text.isEmpty { return .text(text) }
        return .empty
    }
}
