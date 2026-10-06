import Foundation

public struct ContentItem: Codable, Equatable, Identifiable, Sendable {
    public var id: String = UUID().uuidString
    public var text: String
    public var time: Date
    public var pinned = false
    public var files: [String] = []
    public var bytes: Int64 = 0
    public init(id:String=UUID().uuidString,text:String,time:Date,pinned:Bool=false,files:[String]=[],bytes:Int64=0) {
        self.id=id;self.text=text;self.time=time;self.pinned=pinned;self.files=files;self.bytes=bytes
    }
}

/// Caller serializes operations on its I/O queue. Text/JSON cap 1 MiB, media cap 256 MiB.
public final class ContentHistory: @unchecked Sendable {
    public let directory: URL
    private let maximum: Int
    private let ttl: TimeInterval
    private var items: [ContentItem] = []
    public init(directory: URL, maximum: Int = 50, ttl: TimeInterval = 86400) {
        self.directory=directory;self.maximum=maximum;self.ttl=ttl
        let url=directory.appendingPathComponent("history.json")
        if let size=try? url.resourceValues(forKeys:[.fileSizeKey]).fileSize,size<=2*1024*1024,
           let data=try? Data(contentsOf:url),let list=try? JSONDecoder().decode([ContentItem].self,from:data) {
            items=list.filter {$0.text.utf8.count<=10240 && $0.files.allSatisfy(GitHubRepository.safePath)}
        }
    }
    public func list(now:Date=Date()) throws -> [ContentItem] { prune(now);try save();return sorted }
    public var sorted:[ContentItem] { items.sorted { $0.pinned != $1.pinned ? $0.pinned : $0.time > $1.time } }
    public func add(_ text:String,now:Date=Date()) throws {
        guard !text.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty,text.utf8.count<=10240 else {return}
        if let i=items.firstIndex(where:{$0.files.isEmpty && $0.text==text}) {items[i].time=now}
        else {items.append(ContentItem(text:text,time:now))}
        prune(now);try save()
    }
    public func addFiles(_ urls:[URL],now:Date=Date()) throws {
        guard !urls.isEmpty,urls.count<=20 else {return}
        let id=UUID().uuidString;let destination=directory.appendingPathComponent(id)
        var names:[String]=[];var total:Int64=0
        do {
            for (i,url) in urls.enumerated() {
                let v=try url.resourceValues(forKeys:[.isRegularFileKey,.isSymbolicLinkKey,.fileSizeKey])
                guard v.isRegularFile==true,v.isSymbolicLink != true,let size=v.fileSize,size<=64*1024*1024 else {throw PluginFailure("剪贴板文件无法保存或超过 64 MB")}
                total += Int64(size);guard total<=256*1024*1024 else {throw PluginFailure("剪贴板文件总量超过限制")}
                try FileManager.default.createDirectory(at:destination,withIntermediateDirectories:true)
                let name="\(i)-"+url.lastPathComponent
                try FileManager.default.copyItem(at:url,to:destination.appendingPathComponent(name));names.append(id+"/"+name)
            }
            items.append(ContentItem(id:id,text:urls.map(\.lastPathComponent).joined(separator:"、"),time:now,files:names,bytes:total))
            prune(now);try save()
        } catch {try? FileManager.default.removeItem(at:destination);throw error}
    }
    public func pin(_ id:String) throws {if let i=items.firstIndex(where:{$0.id==id}) {items[i].pinned.toggle();try save()}}
    public func delete(_ id:String) throws {items.removeAll {$0.id==id};try save()}
    public func clear(unpinnedOnly:Bool=false) throws {items.removeAll {!unpinnedOnly || !$0.pinned};try save()}
    private func prune(_ now:Date) {
        items.removeAll {ttl>0 && !$0.pinned && now.timeIntervalSince($0.time)>ttl}
        let extras=items.filter{!$0.pinned}.sorted{$0.time>$1.time}.dropFirst(maximum).map(\.id)
        items.removeAll{extras.contains($0.id)}
        var textSize=items.reduce(0){$0+$1.text.utf8.count},mediaSize=items.reduce(Int64(0)){$0+$1.bytes}
        for item in sorted.reversed() where textSize>1024*1024 || mediaSize>256*1024*1024 {
            items.removeAll {$0.id==item.id};textSize-=item.text.utf8.count;mediaSize-=item.bytes
        }
    }
    private func save() throws {
        try FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true)
        try JSONEncoder().encode(items).write(to:directory.appendingPathComponent("history.json"),options:.atomic)
        let keep=Set(items.flatMap(\.files).compactMap {$0.components(separatedBy:"/").first})
        for url in try FileManager.default.contentsOfDirectory(at:directory,includingPropertiesForKeys:[.isDirectoryKey])
            where (try? url.resourceValues(forKeys:[.isDirectoryKey]).isDirectory)==true && !keep.contains(url.lastPathComponent) {
            try FileManager.default.removeItem(at:url)
        }
    }
}
