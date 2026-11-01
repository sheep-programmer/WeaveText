import CoreGraphics
import Foundation
import Vision

/// 拍照识字：用系统 Vision 框架识别图片里的文字。系统自带模型，不需要下载。
/// Image text recognition with the system Vision framework. The model ships with macOS; nothing to download.
public enum TextRecognition {
    /// 识别图片中的文字，按 Vision 给出的阅读顺序（自上而下）用换行连接。
    /// Recognize the text in an image, joined by newlines in Vision's reading order (top to bottom).
    public static func recognize(_ image: CGImage, languages: [String] = ["zh-Hans", "zh-Hant", "en-US"]) throws -> String {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = true
        request.recognitionLanguages = languages
        try VNImageRequestHandler(cgImage: image, options: [:]).perform([request])
        let observations = request.results ?? []
        let lines = observations.sorted {
            // Vision 的坐标原点在左下：纵坐标大的在上面。 Vision's origin is bottom-left, so larger y is higher.
            if abs($0.boundingBox.midY - $1.boundingBox.midY) > 0.02 { return $0.boundingBox.midY > $1.boundingBox.midY }
            return $0.boundingBox.minX < $1.boundingBox.minX
        }.compactMap { $0.topCandidates(1).first?.string }
        return lines.joined(separator: "\n")
    }
}
