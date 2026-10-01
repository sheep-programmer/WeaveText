import Foundation
import Testing
import WeaveCore
@testable import WeaveText

@MainActor @Suite struct LinkBonjourTests {
    @Test(.enabled(if: ProcessInfo.processInfo.environment["WEAVE_TEST_BONJOUR"] == "1"))
    func systemBonjourResolvesBothNativeListeners() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("weave-bonjour-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        func native(_ name: String) throws -> LinkHandle {
            try #require(LinkHandle(config: ["name": name, "platform": "mac", "mdns": false, "port": 1,
                "stateDir": dir.appendingPathComponent(name + "/state").path, "inboxDir": dir.appendingPathComponent(name + "/inbox").path]))
        }
        let a = try native("A"), b = try native("B")
        let ai = LinkInfo(json: a.call(["op":"info"])), bi = LinkInfo(json: b.call(["op":"info"]))
        let ad = LinkBonjour(), bd = LinkBonjour()
        ad.command = { a.call($0) }; bd.command = { b.call($0) }
        ad.start(info: ai); bd.start(info: bi)
        defer { ad.stop(); bd.stop(); a.stop(); b.stop() }
        let end = Date().addingTimeInterval(20)
        var seenA = false, seenB = false
        while Date() < end && !(seenA && seenB) {
            RunLoop.main.run(until: Date().addingTimeInterval(0.05))
            seenA = a.call(["op":"peers"]).objects("nearby").contains { $0.str("id") == bi.id }
            seenB = b.call(["op":"peers"]).objects("nearby").contains { $0.str("id") == ai.id }
        }
        #expect(seenA && seenB)
        let candidates = a.call(["op":"peers"]).objects("nearby").first { $0.str("id") == bi.id }?.strings("addrs") ?? []
        #expect(!candidates.isEmpty)
    }
}
