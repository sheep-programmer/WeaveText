import Foundation
import Testing
@testable import WeaveCore

@Suite struct ContentHistoryTests {
    @Test func historiesExpireDeduplicatePinAndPersist() throws {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent("weave-history-"+UUID().uuidString)
        defer {try? FileManager.default.removeItem(at:root)}
        let store=ContentHistory(directory:root,maximum:2,ttl:30)
        let now=Date(timeIntervalSince1970:100)
        try store.add("常用",now:now);let pinned=try #require(store.sorted.first)
        try store.pin(pinned.id)
        for i in 1...4 {try store.add("文字\(i)",now:now.addingTimeInterval(Double(i)))}
        #expect(store.sorted.count==3 && store.sorted[0].pinned)
        try store.add("文字4",now:now.addingTimeInterval(5));#expect(store.sorted.count==3)
        #expect(try ContentHistory(directory:root,maximum:2,ttl:30).list(now:now.addingTimeInterval(6)).count==3)
        #expect(try store.list(now:now.addingTimeInterval(50)).map(\.text)==["常用"])
        try store.add(String(repeating:"中",count:4000));#expect(store.sorted.count==1)
        try store.clear();#expect(store.sorted.isEmpty)
    }
    @Test func copiedFilesAreKeptUntilTheirHistoryEntryIsDeleted() throws {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent("weave-file-history-"+UUID().uuidString)
        defer {try? FileManager.default.removeItem(at:root)}
        try FileManager.default.createDirectory(at:root,withIntermediateDirectories:true)
        let file=root.appendingPathComponent("original.txt");try Data("file content".utf8).write(to:file)
        let store=ContentHistory(directory:root.appendingPathComponent("history"))
        try store.addFiles([file]);let item=try #require(store.sorted.first)
        try FileManager.default.removeItem(at:file)
        let copy=store.directory.appendingPathComponent(try #require(item.files.first))
        #expect(try String(contentsOf:copy,encoding:.utf8)=="file content")
        try store.delete(item.id);#expect(!FileManager.default.fileExists(atPath:copy.path))
    }
}
