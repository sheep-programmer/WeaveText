import CryptoKit
import Foundation

public enum OfficialMarket {
    public static let repository = URL(string:"https://github.com/sheep-programmer/weavetext-market")!
    public static let raw = "https://raw.githubusercontent.com/sheep-programmer/weavetext-market/main"
    public static let api = "https://api.github.com/repos/sheep-programmer/weavetext-market/contents"
    public static func digest(_ data:Data)->String { SHA256.hash(data:data).map{String(format:"%02x",$0)}.joined() }
    public static func unwrap(_ data:Data) throws -> Data {
        guard let json=try JSONSerialization.jsonObject(with:data) as? [String:Any] else {throw ExtensionError.invalid}
        if json["encoding"] as? String == "base64",let content=json["content"] as? String {
            guard let bytes=Data(base64Encoded:content,options:.ignoreUnknownCharacters) else {throw ExtensionError.invalid}
            return bytes
        }
        return data
    }
    public static func parse(_ data:Data) throws -> [ExtensionItem] {
        guard data.count <= 512*1024 else {throw ExtensionError.invalid}
        let catalog=try ExtensionCatalog.parse(data)
        guard catalog.items.count <= 256 else {throw ExtensionError.invalid}
        let protected=Set(ColorTheme.allCases.map{"theme:"+$0.rawValue}+[
            "theme:dynamic","theme:paper","theme:violet","layout:fresh","layout:classic"])
        for item in catalog.items where item.source == "remote" {
            guard ["theme","layout"].contains(item.kind),!protected.contains(item.key),
                  item.file == "\(item.kind)s/\(item.kind)-\(item.id).json",item.url == raw+"/"+item.file!,
                  let sha=item.sha256,sha.range(of:"^[a-f0-9]{64}$",options:.regularExpression) != nil,
                  let bytes=item.bytes,(1...256*1024).contains(bytes) else {throw ExtensionError.invalid}
        }
        return catalog.items.filter{$0.source == "remote" && $0.platforms.contains("mac") && $0.kind == "theme"}
    }
    public static func read(file:String,expectedBytes:Int?=nil,sha:String?=nil,
                            fetcher:HTTPFetching=URLSessionFetcher(),
                            progress:(@Sendable(FetchProgress)->Void)?=nil) async throws -> Data {
        guard file == "catalog.json" || file.range(of:"^themes/theme-[a-z][a-z0-9_-]{0,63}\\.json$",options:.regularExpression) != nil else {throw ExtensionError.invalid}
        let resource=ExtensionStore.resourcesRoot.appendingPathComponent("mirrors.json")
        let mirrors=(try? Data(contentsOf:resource)).map(Mirrors.parse) ?? Mirrors()
        let primary=URL(string:raw+"/"+file)!
        var urls=[primary,URL(string:api+"/"+file+"?ref=main")!]
        urls += mirrors.sources(for:primary).filter{!urls.contains($0)}
        var failure:Error=ExtensionError.invalid
        for url in urls {
            try Task.checkCancellation()
            do {
                progress?(FetchProgress(receivedBytes:0,totalBytes:expectedBytes.map(Int64.init)))
                let result=try await fetcher.get(url,etag:nil,maxBytes:768*1024,downloadProgress:progress)
                guard result.status==200 else {throw FetchError.http(result.status)}
                let bytes=try unwrap(result.body)
                if let expectedBytes,bytes.count != expectedBytes {throw ExtensionError.invalid}
                if let sha,digest(bytes) != sha {throw ExtensionError.invalid}
                try Task.checkCancellation()
                return bytes
            } catch is CancellationError {throw CancellationError()}
            catch {try Task.checkCancellation();failure=error}
        }
        throw failure
    }
}
