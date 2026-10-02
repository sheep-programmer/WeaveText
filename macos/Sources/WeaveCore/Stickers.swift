import Foundation
import CryptoKit
import ImageIO

public struct Sticker:Codable,Identifiable,Equatable,Sendable {
    public let id:String
    public let file:String
    public let mime:String
    public let bytes:Int64
    public let width:Int
    public let height:Int
    public let animated:Bool
    public var name:String
    public var group:String
    public var tags:[String]
    public var favorite:Bool
    public var created:Int64
    public var lastUsed:Int64
}
private struct StickerCatalog:Codable {let format:String;let items:[Sticker]}

/// Original assets are independent of clipboard retention, with a portable Android/Mac catalog.
public final class StickerStore {
    public static let format="weavetext-stickers-1"
    public static let maxBytes=20*1024*1024
    private let directory:URL
    private let originals:URL
    private let index:URL
    private let lock=NSRecursiveLock()
    private var items:[Sticker]
    public init(directory:URL) throws {
        self.directory=directory;originals=directory.appendingPathComponent("originals",isDirectory:true);index=directory.appendingPathComponent("catalog.json")
        try FileManager.default.createDirectory(at:originals,withIntermediateDirectories:true)
        if FileManager.default.fileExists(atPath:index.path) {
            do {items=try Self.read(Data(contentsOf:index))}
            catch {items=try Self.read(Data(contentsOf:directory.appendingPathComponent("catalog.json.bak")))}
        } else {items=[]}
    }
    public func list(query:String="",filter:String="all")->[Sticker] {
        lock.withLock {
            let q=query.trimmingCharacters(in:.whitespacesAndNewlines)
            return items.filter {s in
                (q.isEmpty || ([s.name,s.group]+s.tags).contains {$0.localizedCaseInsensitiveContains(q)}) &&
                (filter=="all" || (filter=="favorites" && s.favorite) || (filter=="recent" && s.lastUsed>0) || (filter=="ungrouped" && s.group.isEmpty) || filter=="group:"+s.group)
            }.sorted {a,b in
                let x=filter=="recent" ? a.lastUsed : a.created;let y=filter=="recent" ? b.lastUsed : b.created
                return x==y ? a.id<b.id : x>y
            }
        }
    }
    public func groups()->[String] {lock.withLock {Array(Set(items.map(\.group).filter {!$0.isEmpty})).sorted()}}
    public func file(_ item:Sticker) throws->URL {
        guard Self.valid(item) else{throw Self.bad("表情文件记录无效")}
        return originals.appendingPathComponent(item.file)
    }
    public func importFile(_ url:URL,name:String?=nil,group:String="") throws->(Sticker,Bool) {
        let access=url.startAccessingSecurityScopedResource();defer {if access {url.stopAccessingSecurityScopedResource()}}
        let source=try FileHandle(forReadingFrom:url);defer {try? source.close()}
        var bytes=Data()
        while let block=try source.read(upToCount:65536),!block.isEmpty {guard bytes.count+block.count<=Self.maxBytes else{throw Self.bad("表情超过 20 MB，请先压缩")};bytes.append(block)}
        return try importData(bytes,name:name ?? url.deletingPathExtension().lastPathComponent,group:group)
    }
    public func importData(_ bytes:Data,name:String,group:String="",expectedID:String?=nil) throws->(Sticker,Bool) {
        guard bytes.count<=Self.maxBytes,let type=Self.type(bytes),let image=CGImageSourceCreateWithData(bytes as CFData,nil),
              let properties=CGImageSourceCopyPropertiesAtIndex(image,0,nil) as? [CFString:Any],
              let width=properties[kCGImagePropertyPixelWidth] as? Int,let height=properties[kCGImagePropertyPixelHeight] as? Int,
              width>0,height>0,width<=8192,height<=8192,width*height<=32_000_000 else{throw Self.bad("没有收到有效图片，或图片尺寸过大")}
        let hash=SHA256.hash(data:bytes).map {String(format:"%02x",$0)}.joined()
        guard expectedID==nil || expectedID==hash else{throw Self.bad("备份中的图片校验失败")}
        return try lock.withLock {
            if let old=items.first(where:{$0.id==hash}) {
                let path=try file(old);if !FileManager.default.fileExists(atPath:path.path){try bytes.write(to:path,options:.atomic)}
                return (old,false)
            }
            guard items.count<5000 else{throw Self.bad("已收纳 5000 张，请先整理")}
            let item=Sticker(id:hash,file:hash+"."+type.1,mime:type.0,bytes:Int64(bytes.count),width:width,height:height,
                animated:CGImageSourceGetCount(image)>1,name:Self.clean(name).isEmpty ? "表情" : Self.clean(name),group:Self.clean(group),tags:[],favorite:false,
                created:Int64(Date().timeIntervalSince1970*1000),lastUsed:0)
            let path=try file(item);try bytes.write(to:path,options:.atomic)
            do {try save(items+[item])}catch {try? FileManager.default.removeItem(at:path);throw error}
            return (item,true)
        }
    }
    public func edit(_ id:String,name:String,group:String,tags:[String],favorite:Bool) throws {
        try lock.withLock {
            guard let position=items.firstIndex(where:{$0.id==id})else{return}
            var next=items;next[position].name=Self.clean(name).isEmpty ? items[position].name : Self.clean(name)
            next[position].group=Self.clean(group);next[position].tags=Array(Set(tags.map(Self.clean).filter {!$0.isEmpty})).sorted().prefix(32).map {$0}
            next[position].favorite=favorite;try save(next)
        }
    }
    public func used(_ id:String) throws {try lock.withLock {
        var next=items;if let i=next.firstIndex(where:{$0.id==id}){next[i].lastUsed=Int64(Date().timeIntervalSince1970*1000);try save(next)}
    }}
    public func group(_ ids:Set<String>,name:String) throws {try lock.withLock {
        var next=items;for i in next.indices where ids.contains(next[i].id){next[i].group=Self.clean(name)};try save(next)
    }}
    public func delete(_ ids:Set<String>) throws {try lock.withLock {
        let removed=items.filter {ids.contains($0.id)};try save(items.filter {!ids.contains($0.id)})
        for item in removed {try? FileManager.default.removeItem(at:file(item))}
    }}
    public func export(to url:URL) throws {
        let temp=FileManager.default.temporaryDirectory.appendingPathComponent("weave-stickers-\(UUID().uuidString)",isDirectory:true)
        defer {try? FileManager.default.removeItem(at:temp)}
        let assets=temp.appendingPathComponent("originals",isDirectory:true);try FileManager.default.createDirectory(at:assets,withIntermediateDirectories:true)
        try lock.withLock {
            guard items.reduce(Int64(0),{$0+$1.bytes})<=512*1024*1024 else{throw Self.bad("表情备份超过 512 MB，请分批整理")}
            try JSONEncoder().encode(StickerCatalog(format:Self.format,items:items)).write(to:temp.appendingPathComponent("catalog.json"))
            for item in items {let source=try file(item);try FileManager.default.copyItem(at:source,to:assets.appendingPathComponent(item.file))}
        }
        let access=url.startAccessingSecurityScopedResource();defer {if access {url.stopAccessingSecurityScopedResource()}}
        let process=Process();process.executableURL=URL(fileURLWithPath:"/usr/bin/ditto");process.arguments=["-c","-k",temp.path,url.path]
        process.standardError=FileHandle.nullDevice;try process.run();process.waitUntilExit()
        guard process.terminationStatus==0 else{throw Self.bad("无法导出表情备份")}
    }
    public func importArchive(_ url:URL) throws->(Int,Int) {
        let access=url.startAccessingSecurityScopedResource();defer {if access {url.stopAccessingSecurityScopedResource()}}
        guard (try url.resourceValues(forKeys:[.fileSizeKey]).fileSize ?? Int.max)<=512*1024*1024 else{throw Self.bad("表情备份超过 512 MB")}
        let records=try Self.read(Self.zipEntry(url,"catalog.json",limit:4*1024*1024))
        var added=0;var skipped=0;var total=0
        for item in records {
            let data=try Self.zipEntry(url,"originals/"+item.file,limit:Self.maxBytes)
            total+=data.count;guard total<=512*1024*1024 else{throw Self.bad("备份解包超过 512 MB")}
            let (collected,isNew)=try importData(data,name:item.name,group:item.group,expectedID:item.id)
            if isNew {try lock.withLock {
                var next=items;if let i=next.firstIndex(where:{$0.id==collected.id}) {
                    next[i].tags=item.tags;next[i].favorite=item.favorite;next[i].created=item.created;next[i].lastUsed=item.lastUsed;try save(next)
                }
            };added+=1}else{skipped+=1}
        };return (added,skipped)
    }
    private static func zipEntry(_ archive:URL,_ path:String,limit:Int)throws->Data {
        // Stream only validated names; never extract arbitrary paths or follow archived symlinks.
        let process=Process();let pipe=Pipe();process.executableURL=URL(fileURLWithPath:"/usr/bin/unzip");process.arguments=["-p",archive.path,path]
        process.standardOutput=pipe;process.standardError=FileHandle.nullDevice;try process.run()
        var output=Data()
        do {
            while let block=try pipe.fileHandleForReading.read(upToCount:65536),!block.isEmpty {
                guard output.count+block.count<=limit else{process.terminate();throw Self.bad("表情备份超过大小限制")};output.append(block)
            }
            process.waitUntilExit();guard process.terminationStatus==0 else{throw Self.bad("备份文件不完整")}
            return output
        }catch {if process.isRunning {process.terminate()};throw error}
    }
    private func save(_ next:[Sticker])throws {
        let data=try JSONEncoder().encode(StickerCatalog(format:Self.format,items:next))
        guard data.count<=4*1024*1024 else{throw Self.bad("表情信息过多，请减少标签或整理后再保存")}
        if FileManager.default.fileExists(atPath:index.path){try JSONEncoder().encode(StickerCatalog(format:Self.format,items:items)).write(to:directory.appendingPathComponent("catalog.json.bak"),options:.atomic)}
        try data.write(to:index,options:.atomic);items=next
    }
    private static func read(_ data:Data)throws->[Sticker] {
        guard data.count<=4*1024*1024 else{throw Self.bad("表情索引过大")}
        let catalog=try JSONDecoder().decode(StickerCatalog.self,from:data)
        guard catalog.format==format,catalog.items.count<=5000,catalog.items.allSatisfy(valid) else{throw Self.bad("不是有效的织文表情备份")}
        var seen=Set<String>()
        return catalog.items.filter {seen.insert($0.id).inserted}.map {s in
            var s=s;s.name=clean(s.name);s.group=clean(s.group);s.tags=s.tags.prefix(32).map(clean);return s
        }
    }
    private static func valid(_ s:Sticker)->Bool {s.id.range(of:"^[a-f0-9]{64}$",options:.regularExpression) != nil &&
        s.file.range(of:"^"+s.id+"\\.(png|jpg|gif|webp|bmp)$",options:.regularExpression) != nil}
    private static func clean(_ s:String)->String {String(s.filter {!$0.unicodeScalars.contains(where:CharacterSet.controlCharacters.contains)}.trimmingCharacters(in:.whitespacesAndNewlines).prefix(120))}
    private static func bad(_ message:String)->NSError {NSError(domain:"WeaveStickers",code:1,userInfo:[NSLocalizedDescriptionKey:message])}
    private static func type(_ bytes:Data)->(String,String)? {
        let b=[UInt8](bytes.prefix(32));guard b.count>=12 else{return nil}
        if b.starts(with:[137,80,78,71,13,10,26,10]){return ("image/png","png")}
        if b.starts(with:[255,216,255]){return ("image/jpeg","jpg")}
        if String(bytes:b.prefix(6),encoding:.ascii)?.hasPrefix("GIF8")==true{return ("image/gif","gif")}
        if String(bytes:b.prefix(4),encoding:.ascii)=="RIFF",String(bytes:b[8..<12],encoding:.ascii)=="WEBP"{return ("image/webp","webp")}
        if b.starts(with:[66,77]){return ("image/bmp","bmp")};return nil
    }
}
