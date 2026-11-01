import AppKit
import WeaveCore

/// 剪贴板图片的拍照识字：读图、Vision 识别、把文字交回调用方。 Clipboard-image text recognition: read the image, run Vision, hand the text back.
enum ClipboardOCR {
    static func image(from pasteboard: NSPasteboard = .general) -> CGImage? {
        guard let image = (pasteboard.readObjects(forClasses: [NSImage.self], options: nil) as? [NSImage])?.first else { return nil }
        var rect = CGRect(origin: .zero, size: image.size)
        return image.cgImage(forProposedRect: &rect, context: nil, hints: nil)
    }

    /// 后台识别，结果回到主线程。 Recognize off the main thread; deliver the result on the main thread.
    static func recognize(_ image: CGImage, completion: @escaping (String?) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            let text = try? TextRecognition.recognize(image)
            DispatchQueue.main.async { completion(text) }
        }
    }
}
