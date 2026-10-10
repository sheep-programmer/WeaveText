import Foundation
import Testing
@testable import WeaveCore

@MainActor @Suite struct OfficialMarketTests {
    private func item(_ id:String="custom")->[String:Any] {
        ["id":id,"kind":"theme","name":"新主题","summary":"测试","platforms":["mac","android"],"source":"remote",
         "file":"themes/theme-\(id).json","url":OfficialMarket.raw+"/themes/theme-\(id).json","sha256":String(repeating:"a",count:64),"bytes":1024]
    }
    private func catalog(_ items:[[String:Any]]) throws -> Data {
        try JSONSerialization.data(withJSONObject:["version":1,"items":items])
    }
    @Test func onlyOfficialPlatformDataIsAllowedAndBasesStayProtected() throws {
        let data=try catalog([item()])
        #expect(try OfficialMarket.parse(data).map(\.key) == ["theme:custom"])
        for name in ["fresh","ink","paper","violet"] {
            #expect(throws:ExtensionError.self) {try OfficialMarket.parse(catalog([item(name)]))}
        }
        var foreign=item();foreign["url"]="https://example.org/theme.json"
        #expect(throws:ExtensionError.self) {try OfficialMarket.parse(catalog([foreign]))}
        var huge=item();huge["bytes"]=256*1024+1
        #expect(throws:ExtensionError.self) {try OfficialMarket.parse(catalog([huge]))}
    }
    @Test func rawAndGitHubEnvelopeDecodeIdentically() throws {
        let data=try catalog([item()])
        let wrapper=try JSONSerialization.data(withJSONObject:["encoding":"base64","content":data.base64EncodedString()])
        #expect(try OfficialMarket.unwrap(wrapper) == data)
        #expect(try OfficialMarket.unwrap(data) == data)
    }
    @Test func failedRemoteRefreshPreservesTheLastValidCatalog() async throws {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent("weave-market-\(UUID().uuidString)")
        defer {try? FileManager.default.removeItem(at:root)}
        let good=try catalog([item()])
        try FileManager.default.createDirectory(at:root,withIntermediateDirectories:true)
        try good.write(to:root.appendingPathComponent("market-index.json"))
        let store=ExtensionStore(root:root)
        #expect(store.items.contains{$0.key=="theme:custom"})
        do {try await store.refreshMarket(fetcher:FailedMarketFetcher());Issue.record("refresh must fail")} catch {}
        #expect(store.items.contains{$0.key=="theme:custom"})
        #expect(try Data(contentsOf:root.appendingPathComponent("market-index.json")) == good)
    }
}
private struct FailedMarketFetcher:HTTPFetching {
    func get(_ url:URL,etag:String?,maxBytes:Int,progress:(@Sendable(Int64)->Void)?) async throws -> HTTPResult {throw FetchError.http(503)}
}
