import CryptoKit
import Foundation
import Testing
@testable import WeaveCore

private func pluginFixture(_ root:URL) throws -> URL {
    let source=root.appendingPathComponent("source")
    try FileManager.default.createDirectory(at:source,withIntermediateDirectories:true)
    try Data("""
    id: org.weavetext.parity
    name: 测试语音
    type: speech
    version: 1.0
    entry: main.lua
    configSchema:
      - key: prefix
        type: text
        label: 前缀
        required: true
        defaultValue: ready
      - key: mode
        type: select
        options: [one, two]
    """.utf8).write(to:source.appendingPathComponent("manifest.yaml"))
    try Data("""
    local p = {}
    function p.isConfigured() return host.config.get('prefix') ~= '' end
    function p.start() return true end
    function p.processAudioChunk(pcm) host.asr.emitPartial(tostring(#pcm)) end
    function p.stop() host.asr.emitFinal(host.config.get('prefix'));host.asr.emitEnd() end
    function p.cancel() end
    return p
    """.utf8).write(to:source.appendingPathComponent("main.lua"))
    return source
}
private final class PluginEvents: @unchecked Sendable {
    let lock=NSLock()
    var values:[[String:Any]]=[]
    func add(_ event:[String:Any]) {lock.lock();values.append(event);lock.unlock()}
    func contains(_ name:String,text:String?=nil)->Bool {
        lock.lock();defer{lock.unlock()}
        return values.contains {$0["event"] as? String==name && (text==nil || $0["text"] as? String==text)}
    }
}
private struct RepoFixture:GitHubFetching {
    let values:[String:Data]
    func get(_ path:String,token:String?,accept:String,limit:Int) async throws -> Data {
        guard let value=values[path] else {throw PluginFailure("missing fixture: "+path)}
        guard value.count<=limit else {throw PluginFailure("fixture too large")};return value
    }
}
@Suite struct PluginParityTests {
    @Test func githubCredentialsPersistAndClearInAnIsolatedKeychainItem() throws {
        let credentials=GitHubCredentials(service:"com.weavetext.github.test."+UUID().uuidString)
        defer {try? credentials.clear()}
        let token="github_pat_"+String(repeating:"t",count:60)
        try credentials.save(user:"isolated-test",token:token)
        #expect(credentials.read()?.user=="isolated-test")
        #expect(credentials.read()?.token==token)
        try credentials.save(user:"isolated-test-2",token:token+"2")
        #expect(credentials.read()?.user=="isolated-test-2")
        try credentials.clear();#expect(credentials.read()==nil)
    }
    @Test func repositoryAddressesAcceptAnyFileExtensionAndKeepPathSafety() throws {
        for name in ["voice.zip","voice.bundle","voice","voice.xipk"] {
            #expect(try GitHubRepository.parse("https://github.com/demo/plugins/blob/main/asr/"+name).path=="asr/"+name)
        }
        #expect(try GitHubRepository.parse("https://github.com/demo/plugins/blob/main/asr/manifest.yaml").path=="asr")
        #expect(try GitHubRepository.parse("https://github.com/demo/plugins/tree/feature/custom/asr",ref:"feature/custom").path=="asr")
        #expect(throws:PluginFailure.self) {try GitHubRepository.parse("https://github.com.evil.example/demo/plugins")}
        #expect(throws:PluginFailure.self) {try GitHubRepository.parse("https://github.com/demo/plugins/blob/main/%2e%2e/private")}
        #expect(GitHubHTTP.authorizationAllowed(URL(string:"https://api.github.com/user")!))
        #expect(!GitHubHTTP.authorizationAllowed(URL(string:"https://release-assets.githubusercontent.com/file")!))
    }
    @Test func arbitraryPackageNamesInstallConfigureAndRecognizeWithTheSharedLuaHost() async throws {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent("weave-plugin-parity-"+UUID().uuidString)
        defer {try? FileManager.default.removeItem(at:root)}
        let source=try pluginFixture(root)
        let host=try #require(PluginHost(directory:root.appendingPathComponent("host")))
        for name in ["speech.zip","speech.bundle","speech","speech.xipk"] {
            let package=root.appendingPathComponent(name)
            try PluginHost.package(source:source,output:package)
            #expect(try PluginHost.inspect(package).id=="org.weavetext.parity")
            let info=try host.install(package)
            #expect(info.configSchema[1].options==["one","two"])
        }
        #expect(try host.scan().count==1)
        try host.setConfig("org.weavetext.parity",key:"prefix",value:"Mac 插件运行成功")
        #expect(host.configured("org.weavetext.parity"))
        let events=PluginEvents()
        let session=try #require(host.speech("org.weavetext.parity") {events.add($0)})
        session.feed(Data(repeating:0,count:640));session.stop()
        for _ in 0..<100 {if events.contains("end") {break};try await Task.sleep(nanoseconds:20_000_000)}
        #expect(events.contains("partial",text:"640"))
        #expect(events.contains("final",text:"Mac 插件运行成功"))
        #expect(events.contains("end"))
        session.cancel()
        try host.uninstall("org.weavetext.parity")
        #expect(try host.scan().isEmpty)
        let fake=root.appendingPathComponent("fake.xipk");try Data("ordinary text".utf8).write(to:fake)
        #expect(throws:PluginFailure.self) {try PluginHost.inspect(fake)}
    }
    @Test func catalogCountsArchiveContentsAndRejectsMisleadingNames() async throws {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent("weave-repository-parity-"+UUID().uuidString)
        defer {try? FileManager.default.removeItem(at:root)}
        let source=try pluginFixture(root),package=root.appendingPathComponent("package.bundle")
        try PluginHost.package(source:source,output:package)
        let repo=try GitHubRepository.parse("demo/plugins"),bytes=try Data(contentsOf:package),ordinary=Data("ordinary text".utf8)
        func sha(_ d:Data)->String {var header=Data("blob \(d.count)\0".utf8);header.append(d);return Insecure.SHA1.hash(data:header).map{String(format:"%02x",$0)}.joined()}
        let valid=sha(bytes),invalid=sha(ordinary),tree=String(repeating:"a",count:40)
        var fixtures:[String:Data]=[:]
        func json(_ path:String,_ o:Any) throws {fixtures[path]=try JSONSerialization.data(withJSONObject:o)}
        try json(repo.apiPath,["default_branch":"main","private":true])
        try json(repo.apiPath+"/commits/main",["commit":["tree":["sha":tree]]])
        let files=["voice.bundle","voice.zip","voice"].map {["path":$0,"mode":"100644","type":"blob","size":bytes.count,"sha":valid] as [String:Any]} +
            [["path":"fake.xipk","mode":"100644","type":"blob","size":ordinary.count,"sha":invalid]]
        try json(repo.apiPath+"/git/trees/"+tree+"?recursive=1",["tree":files,"truncated":false])
        try json(repo.apiPath+"/releases?per_page=20",[["tag_name":"v1","assets":[["id":42,"name":"speech.custom","size":bytes.count]]]])
        fixtures[repo.apiPath+"/git/blobs/"+valid]=bytes;fixtures[repo.apiPath+"/git/blobs/"+invalid]=ordinary
        fixtures[repo.apiPath+"/releases/assets/42"]=bytes
        let client=GitHubPluginClient(http:RepoFixture(values:fixtures),token:"fake_token_for_isolated_test")
        let catalog=try await client.catalog(repo)
        #expect(catalog.isPrivate)
        #expect(Set(catalog.plugins.map(\.label))==["voice.bundle","voice.zip","voice","speech.custom · v1"])
        let output=try await client.download(catalog,plugin:catalog.plugins[0]);defer {try? FileManager.default.removeItem(at:output.deletingLastPathComponent())}
        #expect(try PluginHost.inspect(output).name=="测试语音")
    }
}
