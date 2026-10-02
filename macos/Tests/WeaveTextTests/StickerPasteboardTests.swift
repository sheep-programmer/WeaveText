import AppKit
import Testing
import UniformTypeIdentifiers
import WeaveCore
@testable import WeaveText

@MainActor @Suite struct StickerPasteboardTests {
    @Test func animatedOriginalAndFileURLAreBothOfferedToChat() throws {
        let root=URL(fileURLWithPath:#filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let dir=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer {try? FileManager.default.removeItem(at:dir)}
        let store=try StickerStore(directory:dir);let pb=NSPasteboard.withUniqueName();defer {pb.releaseGlobally()}
        for (name,type) in [("animated.gif",UTType.gif),("animated.webp",UTType.webP)] {
            let original=try Data(contentsOf:root.appendingPathComponent("tests/fixtures/stickers/"+name))
            let (item,_)=try store.importData(original,name:"动画");let file=try store.file(item)
            #expect(StickerPasteboard.write(item,file:file,data:original,to:pb))
            #expect(pb.data(forType:.init(type.identifier))==original)
            #expect(pb.string(forType:.fileURL)==file.absoluteString)
            #expect(pb.data(forType:.png)==nil)
        }
    }
}
