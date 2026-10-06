import CryptoKit
import Foundation
import Security

public struct GitHubRepository: Codable, Hashable, Identifiable, Sendable {
    public let owner: String
    public let name: String
    public let ref: String
    public let path: String
    public var fullName: String { owner + "/" + name }
    public var id: String { fullName + "@" + ref + ":" + path }
    public var apiPath: String { "/repos/\(Self.component(owner))/\(Self.component(name))" }

    public static func parse(_ input: String, ref: String = "", path: String = "") throws -> Self {
        var address = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if address.lowercased().hasPrefix("github.com/") || address.lowercased().hasPrefix("www.github.com/") { address = "https://" + address }
        let parts: [String]
        if address.contains("://") {
            guard let u = URLComponents(string: address), u.scheme?.lowercased() == "https",
                  ["github.com","www.github.com"].contains(u.host?.lowercased() ?? ""), u.user == nil, u.password == nil,
                  u.port == nil || u.port == 443 else { throw PluginFailure("请输入 GitHub 的 HTTPS 仓库地址") }
            parts = u.percentEncodedPath.trimmingCharacters(in: CharacterSet(charactersIn:"/"))
                .components(separatedBy:"/").map { $0.removingPercentEncoding ?? $0 }
        } else { parts = address.trimmingCharacters(in: CharacterSet(charactersIn:"/")).components(separatedBy:"/") }
        guard parts.count >= 2, matches(parts[0], "[A-Za-z0-9][A-Za-z0-9-]{0,38}") else { throw PluginFailure("请输入 owner/repository 或完整 GitHub 链接") }
        let name = parts[1].lowercased().hasSuffix(".git") ? String(parts[1].dropLast(4)) : parts[1]
        guard matches(name,"[A-Za-z0-9_.-]{1,100}"), ![".",".."].contains(name) else { throw PluginFailure("仓库名称无效") }
        let linked = parts.count > 3 && ["tree","blob"].contains(parts[2])
        guard parts.count == 2 || linked else { throw PluginFailure("请选择仓库、分支目录或插件文件链接") }
        let chosen = ref.trimmingCharacters(in: .whitespacesAndNewlines)
        let branch = chosen.isEmpty ? (linked ? parts[3] : "") : chosen
        var directory = linked ? parts.dropFirst(4).joined(separator:"/") : ""
        if linked && !chosen.isEmpty {
            let tail = parts.dropFirst(3).joined(separator:"/")
            if tail == chosen { directory = "" }
            else if tail.hasPrefix(chosen + "/") { directory = String(tail.dropFirst(chosen.count + 1)) }
        }
        if linked && parts[2] == "blob" && path.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty {
            if directory.components(separatedBy:"/").last == "manifest.yaml" { directory = directory.components(separatedBy:"/").dropLast().joined(separator:"/") }
        }
        let override = path.trimmingCharacters(in:.whitespacesAndNewlines).trimmingCharacters(in:CharacterSet(charactersIn:"/"))
        if !override.isEmpty { directory = override }
        guard branch.utf8.count <= 200, !branch.unicodeScalars.contains(where:CharacterSet.controlCharacters.contains),
              directory.isEmpty || safePath(directory) else { throw PluginFailure("分支或插件目录无效") }
        return Self(owner:parts[0],name:name,ref:branch,path:directory)
    }
    public static func component(_ value: String) -> String { value.addingPercentEncoding(withAllowedCharacters: .alphanumerics.union(CharacterSet(charactersIn:"-._~"))) ?? "" }
    public static func safePath(_ value: String) -> Bool {
        !value.isEmpty && !value.contains("\\") && !value.unicodeScalars.contains(where:CharacterSet.controlCharacters.contains) &&
            value.components(separatedBy:"/").allSatisfy { !$0.isEmpty && $0 != "." && $0 != ".." }
    }
    public static func validSHA(_ value: String) -> Bool { matches(value,"[0-9a-fA-F]{40}|[0-9a-fA-F]{64}") }
    private static func matches(_ value:String,_ pattern:String)->Bool { value.range(of:"^(?:"+pattern+")$",options:.regularExpression) != nil }
}

public struct GitHubCredentials: Sendable {
    private let service: String
    public init(service: String = "com.weavetext.github") { self.service = service }
    private var query: [String:Any] { [kSecClass as String:kSecClassGenericPassword,kSecAttrService as String:service,kSecAttrAccount as String:"github"] }
    public func read() -> (user:String,token:String)? {
        var q=query; q[kSecReturnData as String]=true; q[kSecMatchLimit as String]=kSecMatchLimitOne
        var item:CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary,&item)==errSecSuccess, let d=item as? Data,
              let v=try? JSONSerialization.jsonObject(with:d) as? [String:String],let user=v["user"],let token=v["token"] else { return nil }
        return (user,token)
    }
    public func save(user:String,token:String) throws {
        let data=try JSONSerialization.data(withJSONObject:["user":user,"token":token])
        let status=SecItemUpdate(query as CFDictionary,[kSecValueData as String:data] as CFDictionary)
        if status==errSecItemNotFound {
            var q=query; q[kSecValueData as String]=data; q[kSecAttrAccessible as String]=kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            guard SecItemAdd(q as CFDictionary,nil)==errSecSuccess else {throw PluginFailure("无法将 GitHub 登录保存到钥匙串")}
        } else if status != errSecSuccess {throw PluginFailure("无法更新 GitHub 登录")}
    }
    public func clear() throws {
        let s=SecItemDelete(query as CFDictionary)
        if s != errSecSuccess && s != errSecItemNotFound {throw PluginFailure("无法清除 GitHub 登录")}
    }
}

public protocol GitHubFetching: Sendable {
    func get(_ path:String,token:String?,accept:String,limit:Int) async throws -> Data
    func prefix(_ path:String,token:String?,accept:String,count:Int) async throws -> Data
}
public extension GitHubFetching {
    func prefix(_ path:String,token:String?,accept:String,count:Int) async throws -> Data {
        Data(try await get(path,token:token,accept:accept,limit:GitHubPluginClient.entryLimit).prefix(count))
    }
}
private final class GitHubRedirect: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session:URLSession,task:URLSessionTask,willPerformHTTPRedirection response:HTTPURLResponse,
                    newRequest request:URLRequest,completionHandler:@escaping(URLRequest?)->Void) { completionHandler(nil) }
}
public final class GitHubHTTP: GitHubFetching, @unchecked Sendable {
    private let session: URLSession
    public init() {
        let config=URLSessionConfiguration.ephemeral
        config.urlCache=nil;config.httpCookieStorage=nil;config.timeoutIntervalForRequest=30
        session=URLSession(configuration:config,delegate:GitHubRedirect(),delegateQueue:nil)
    }
    public static func authorizationAllowed(_ url:URL)->Bool {
        url.scheme=="https" && url.host=="api.github.com" && url.user==nil && url.password==nil && (url.port==nil || url.port==443)
    }
    public func get(_ path:String,token:String?,accept:String,limit:Int) async throws -> Data {
        try await read(path,token:token,accept:accept,limit:limit,prefixOnly:false)
    }
    public func prefix(_ path:String,token:String?,accept:String,count:Int) async throws -> Data {
        try await read(path,token:token,accept:accept,limit:count,prefixOnly:true)
    }
    private func read(_ path:String,token:String?,accept:String,limit:Int,prefixOnly:Bool) async throws -> Data {
        guard path.hasPrefix("/"), !path.hasPrefix("//"), let initial=URL(string:"https://api.github.com"+path), Self.authorizationAllowed(initial) else {throw PluginFailure("GitHub 接口地址无效")}
        var url=initial
        for hop in 0..<5 {
            try Task.checkCancellation()
            guard url.scheme=="https",url.user==nil,url.password==nil,url.port==nil || url.port==443 else {throw PluginFailure("GitHub 下载重定向无效")}
            var request=URLRequest(url:url)
            request.setValue(accept,forHTTPHeaderField:"Accept")
            request.setValue("2026-03-10",forHTTPHeaderField:"X-GitHub-Api-Version")
            request.setValue("WeaveText-macOS",forHTTPHeaderField:"User-Agent")
            // Only the original API request receives authorization, never a redirect.
            if hop==0,Self.authorizationAllowed(url),let token {request.setValue("Bearer "+token,forHTTPHeaderField:"Authorization")}
            let (bytes,response)=try await session.bytes(for:request)
            defer {bytes.task.cancel()}
            guard let r=response as? HTTPURLResponse else {throw PluginFailure("GitHub 响应无效")}
            if [301,302,303,307,308].contains(r.statusCode),let location=r.value(forHTTPHeaderField:"Location"),let next=URL(string:location,relativeTo:url)?.absoluteURL {url=next;continue}
            guard r.statusCode==200 else {
                throw PluginFailure(r.statusCode==401 ? "令牌无效或已过期" : r.statusCode==404 ? "仓库不存在或令牌无权读取" : r.statusCode==403 ? "GitHub 拒绝访问，请检查只读权限、组织批准或请求额度" : "GitHub 请求失败（\(r.statusCode)）")
            }
            guard prefixOnly || response.expectedContentLength <= Int64(limit) else {throw PluginFailure("GitHub 下载内容过大")}
            var data=Data()
            for try await byte in bytes {data.append(byte);if prefixOnly && data.count==limit {return data};if data.count>limit {throw PluginFailure("GitHub 下载内容过大")}}
            return data
        }
        throw PluginFailure("GitHub 下载重定向次数过多")
    }
}
public struct GitHubBlob: Sendable { public let path:String; public let sha:String; public let size:Int; public let mode:String }
public enum GitHubPlugin: Identifiable, Sendable {
    case package(GitHubBlob), source(String,[GitHubBlob]), release(Int,String,String,Int,String?)
    public var label:String {
        switch self {case .package(let b):return b.path;case .source(let p,_):return p.isEmpty ? "仓库根目录插件" : p;case .release(_,let n,let t,_,_):return n+" · "+t}
    }
    public var id:String {switch self {case .package(let b):return "blob:"+b.sha;case .source(let p,_):return "source:"+p;case .release(let id,_,_,_,_):return "release:\(id)"}}
}
public struct GitHubCatalog: Sendable {
    public let repository:GitHubRepository
    public let isPrivate:Bool
    public let plugins:[GitHubPlugin]
    public init(repository:GitHubRepository,isPrivate:Bool,plugins:[GitHubPlugin]) {self.repository=repository;self.isPrivate=isPrivate;self.plugins=plugins}
}
public struct GitHubPluginClient: Sendable {
    public static let entryLimit=64*1024*1024
    public let http: any GitHubFetching
    public let token: String?
    public init(http:any GitHubFetching=GitHubHTTP(),token:String?=nil) {self.http=http;self.token=token}
    public func login(_ input:String) async throws -> String {
        let t=input.trimmingCharacters(in:.whitespacesAndNewlines)
        guard t.utf8.count>=20,t.utf8.count<=255,t.utf8.allSatisfy({($0>=48 && $0<=57)||($0>=65 && $0<=90)||($0>=97 && $0<=122)||$0==95}) else {throw PluginFailure("请粘贴有效的 GitHub 访问令牌")}
        let data=try await http.get("/user",token:t,accept:"application/vnd.github+json",limit:8*1024*1024)
        guard let o=try JSONSerialization.jsonObject(with:data) as? [String:Any],let user=o["login"] as? String,
              user.range(of:"^[A-Za-z0-9][A-Za-z0-9-]{0,38}$",options:.regularExpression) != nil else {throw PluginFailure("GitHub 账号信息无效")}
        return user
    }
    private func json(_ path:String) async throws -> [String:Any] {
        let data=try await http.get(path,token:token,accept:"application/vnd.github+json",limit:8*1024*1024)
        guard let o=try JSONSerialization.jsonObject(with:data) as? [String:Any] else {throw PluginFailure("GitHub 数据无效")};return o
    }
    public func catalog(_ repo:GitHubRepository) async throws -> GitHubCatalog {
        let metadata=try await json(repo.apiPath)
        guard let branch=repo.ref.isEmpty ? metadata["default_branch"] as? String : repo.ref else {throw PluginFailure("仓库没有默认分支")}
        let commit=try await json(repo.apiPath+"/commits/"+GitHubRepository.component(branch))
        guard let c=commit["commit"] as? [String:Any],let tree=c["tree"] as? [String:Any],var sha=tree["sha"] as? String,GitHubRepository.validSHA(sha) else {throw PluginFailure("GitHub 版本信息无效")}
        var files:[GitHubBlob]=[]
        if !repo.path.isEmpty {
            let segments=repo.path.components(separatedBy:"/")
            for (i,name) in segments.enumerated() {
                let t=try await json(repo.apiPath+"/git/trees/"+sha)
                guard let entries=t["tree"] as? [[String:Any]],let e=entries.first(where:{$0["path"] as? String==name}),let next=e["sha"] as? String,GitHubRepository.validSHA(next) else {throw PluginFailure("找不到指定插件目录或文件")}
                sha=next
                if e["type"] as? String=="blob" {guard i==segments.count-1 else {throw PluginFailure("插件目录无效")};files=[try blob(e,path:repo.path)];break}
                guard e["type"] as? String=="tree" else {throw PluginFailure("不支持子模块，请选择实际目录")}
            }
        }
        if files.isEmpty {
            let t=try await json(repo.apiPath+"/git/trees/"+sha+"?recursive=1")
            guard t["truncated"] as? Bool != true,let entries=t["tree"] as? [[String:Any]] else {throw PluginFailure("仓库过大，请指定较小的插件目录")}
            files=try entries.filter {$0["type"] as? String=="blob"}.map {try blob($0,path:[repo.path,$0["path"] as? String ?? ""].filter{!$0.isEmpty}.joined(separator:"/"))}
        }
        let candidates=files.filter {$0.size>=4 && $0.size<=Self.entryLimit && ["100644","100755"].contains($0.mode)}
        guard candidates.count<=512 else {throw PluginFailure("仓库文件较多，请指定插件所在目录后读取")}
        var plugins:[GitHubPlugin]=[]
        for blob in candidates {
            if try await archive(repo,.package(blob)) {plugins.append(.package(blob))}
        }
        for manifest in files where manifest.path=="manifest.yaml" || manifest.path.hasSuffix("/manifest.yaml") {
            let prefix=manifest.path.components(separatedBy:"/").dropLast().joined(separator:"/")
            plugins.append(.source(prefix,files.filter {prefix.isEmpty || $0.path.hasPrefix(prefix+"/")}))
        }
        let data=try await http.get(repo.apiPath+"/releases?per_page=20",token:token,accept:"application/vnd.github+json",limit:8*1024*1024)
        guard let releases=try JSONSerialization.jsonObject(with:data) as? [[String:Any]] else {throw PluginFailure("GitHub 发布信息无效")}
        for r in releases where r["draft"] as? Bool != true {
            for a in r["assets"] as? [[String:Any]] ?? [] {
                if let id=a["id"] as? Int,let name=a["name"] as? String,let size=a["size"] as? Int,size>=4,size<=Self.entryLimit {
                    let plugin=GitHubPlugin.release(id,name,r["tag_name"] as? String ?? "",size,a["digest"] as? String)
                    if try await archive(repo,plugin) {plugins.append(plugin)}
                }
            }
        }
        return GitHubCatalog(repository:repo,isPrivate:metadata["private"] as? Bool ?? false,plugins:plugins)
    }
    private func archive(_ repo:GitHubRepository,_ plugin:GitHubPlugin) async throws -> Bool {
        let path:String,accept:String
        switch plugin {
        case .package(let blob):path=repo.apiPath+"/git/blobs/"+blob.sha;accept="application/vnd.github.raw+json"
        case .release(let id,_,_,_,_):path=repo.apiPath+"/releases/assets/\(id)";accept="application/octet-stream"
        case .source:return false
        }
        guard try await http.prefix(path,token:token,accept:accept,count:4)==Data([80,75,3,4]) else {return false}
        let catalog=GitHubCatalog(repository:repo,isPrivate:false,plugins:[])
        let url=try await download(catalog,plugin:plugin)
        defer {try? FileManager.default.removeItem(at:url.deletingLastPathComponent())}
        return (try? PluginHost.inspect(url)) != nil
    }
    private func blob(_ o:[String:Any],path:String) throws -> GitHubBlob {
        guard GitHubRepository.safePath(path),let sha=o["sha"] as? String,GitHubRepository.validSHA(sha),let size=o["size"] as? Int,size>=0,let mode=o["mode"] as? String else {throw PluginFailure("GitHub 文件信息无效")}
        return GitHubBlob(path:path,sha:sha,size:size,mode:mode)
    }
    public func download(_ catalog:GitHubCatalog,plugin:GitHubPlugin) async throws -> URL {
        let temp=FileManager.default.temporaryDirectory.appendingPathComponent("weave-repository-"+UUID().uuidString)
        try FileManager.default.createDirectory(at:temp,withIntermediateDirectories:true)
        let output=temp.appendingPathComponent("plugin.archive")
        do {
            switch plugin {
            case .package(let blob):try await fetch(catalog.repository,blob).write(to:output)
            case .release(let id,_,_,let size,let digest):
                let data=try await http.get(catalog.repository.apiPath+"/releases/assets/\(id)",token:token,accept:"application/octet-stream",limit:Self.entryLimit)
                guard data.count==size else {throw PluginFailure("插件下载未完成")}
                if let digest,digest.hasPrefix("sha256:"),digest != "sha256:"+hex(SHA256.hash(data:data)) {throw PluginFailure("插件下载校验失败")}
                try data.write(to:output)
            case .source(let prefix,let files):
                guard files.count>0,files.count<=4096,files.reduce(Int64(0),{$0+Int64($1.size)})<=256*1024*1024 else {throw PluginFailure("插件文件过多或体积过大")}
                let source=temp.appendingPathComponent("source")
                for file in files {
                    let name=prefix.isEmpty ? file.path : String(file.path.dropFirst(prefix.count+1))
                    guard GitHubRepository.safePath(name) else {throw PluginFailure("插件路径无效")}
                    let to=source.appendingPathComponent(name);try FileManager.default.createDirectory(at:to.deletingLastPathComponent(),withIntermediateDirectories:true)
                    try await fetch(catalog.repository,file).write(to:to)
                }
                try PluginHost.package(source:source,output:output)
            }
            return output
        } catch {try? FileManager.default.removeItem(at:temp);throw error}
    }
    private func fetch(_ repo:GitHubRepository,_ blob:GitHubBlob) async throws -> Data {
        guard ["100644","100755"].contains(blob.mode),blob.size<=Self.entryLimit else {throw PluginFailure("不支持符号链接或超大插件文件")}
        let data=try await http.get(repo.apiPath+"/git/blobs/"+blob.sha,token:token,accept:"application/vnd.github.raw+json",limit:Self.entryLimit)
        guard data.count==blob.size else {throw PluginFailure("插件文件大小不符")}
        var git=Data("blob \(data.count)\0".utf8);git.append(data)
        let digest=blob.sha.count==40 ? hex(Insecure.SHA1.hash(data:git)) : hex(SHA256.hash(data:git))
        guard digest.lowercased()==blob.sha.lowercased() else {throw PluginFailure("插件文件校验失败，请刷新仓库")}
        return data
    }
    private func hex<S:Sequence>(_ bytes:S)->String where S.Element==UInt8 {bytes.map{String(format:"%02x",$0)}.joined()}
}
