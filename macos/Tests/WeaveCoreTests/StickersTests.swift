import Foundation
import Testing
@testable import WeaveCore

@Suite struct StickersTests {
    private var root:URL {URL(fileURLWithPath:#filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()}
    private func fixture(_ name:String)throws->Data {try Data(contentsOf:root.appendingPathComponent("tests/fixtures/stickers/"+name))}
    @Test func originalsDedupMetadataAndPortableAndroidBackup() throws {
        let dir=try tempDir("stickers");defer {try? FileManager.default.removeItem(at:dir)}
        let store=try StickerStore(directory:dir)
        for (name,mime) in [("sample.png","image/png"),("animated.gif","image/gif"),("animated.webp","image/webp")] {
            let bytes=try fixture(name);let (item,fresh)=try store.importData(bytes,name:"wrong.txt")
            #expect(fresh && item.mime==mime && item.width==80)
            #expect(try Data(contentsOf:store.file(item))==bytes)
            #expect(try !store.importData(bytes,name:"duplicate").1)
            try store.edit(item.id,name:"快乐",group:"日常",tags:["开心","happy","开心"],favorite:true);try store.used(item.id)
        }
        let reopened=try StickerStore(directory:dir)
        #expect(reopened.list(query:"HAPPY",filter:"favorites").count==3)
        #expect(reopened.list(filter:"recent").count==3 && reopened.groups()==["日常"])
        #expect(reopened.list().filter {$0.animated}.count==2)
        try reopened.group(Set(reopened.list().map(\.id)),name:"工作")
        #expect(reopened.list(filter:"group:工作").count==3)
        let shared=root.appendingPathComponent(".ref/sticker-interop");try FileManager.default.createDirectory(at:shared,withIntermediateDirectories:true)
        let zip=shared.appendingPathComponent("mac.zip");try reopened.export(to:zip)
        let imported=try StickerStore(directory:dir.appendingPathComponent("roundtrip"));let counts=try imported.importArchive(zip)
        #expect(counts.0==3 && counts.1==0 && imported.list()==reopened.list())
        let duplicates=try imported.importArchive(zip);#expect(duplicates.0==0 && duplicates.1==3)
        let android=shared.appendingPathComponent("android.zip")
        if FileManager.default.fileExists(atPath:android.path) {
            let separate=try StickerStore(directory:dir.appendingPathComponent("android"))
            #expect(try separate.importArchive(android).0==1)
            let gif=try #require(separate.list().first)
            #expect(gif.name=="晚安" && gif.tags==["睡觉"] && gif.favorite && gif.lastUsed>0)
            #expect(try Data(contentsOf:separate.file(gif))==fixture("animated.gif"))
        }
        let deleted=try #require(reopened.list().first);try reopened.delete([deleted.id])
        #expect(try !FileManager.default.fileExists(atPath:reopened.file(deleted).path))
    }
    @Test func badImagesTruncatedWebPAndHashMismatchAreRejected() throws {
        let dir=try tempDir("sticker-errors");defer {try? FileManager.default.removeItem(at:dir)}
        let store=try StickerStore(directory:dir)
        #expect(throws:Error.self){try store.importData(Data("RIFF123456".utf8),name:"x.webp")}
        #expect(throws:Error.self){try store.importData(Data("not an image".utf8),name:"x.png")}
        #expect(throws:Error.self){try store.importData(Data(repeating:0,count:StickerStore.maxBytes+1),name:"huge.png")}
        #expect(throws:Error.self){try store.importData(fixture("sample.png"),name:"x",expectedID:String(repeating:"0",count:64))}
        #expect(store.list().isEmpty)
    }
    @Test func invalidPathsAndCorruptCatalogAreRejectedButBackupSurvives() throws {
        let dir=try tempDir("sticker-catalog");defer {try? FileManager.default.removeItem(at:dir)}
        let store=try StickerStore(directory:dir);let (item,_)=try store.importData(fixture("sample.png"),name:"原名")
        try store.edit(item.id,name:"新名",group:"",tags:[],favorite:false)
        let index=dir.appendingPathComponent("catalog.json");try Data("broken".utf8).write(to:index)
        #expect(try StickerStore(directory:dir).list().single?.name=="原名")
        try FileManager.default.removeItem(at:dir.appendingPathComponent("catalog.json.bak"))
        #expect(throws:Error.self){try StickerStore(directory:dir)}
        let invalid=item; // Decode a catalog with an attacker-controlled filename, never use it as a path.
        var object=try #require(JSONSerialization.jsonObject(with:JSONEncoder().encode(invalid)) as? [String:Any]);object["file"]="../../escape.png"
        try JSONSerialization.data(withJSONObject:["format":StickerStore.format,"items":[object]]).write(to:index)
        #expect(throws:Error.self){try StickerStore(directory:dir)}
        #expect(!FileManager.default.fileExists(atPath:dir.deletingLastPathComponent().appendingPathComponent("escape.png").path))
    }
}
private extension Array {var single:Element? {count==1 ? first : nil}}
