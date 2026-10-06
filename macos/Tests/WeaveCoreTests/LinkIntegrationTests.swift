import Foundation
import Testing
@testable import WeaveCore

/// 经 C 接口起两个互联实例（不开 mDNS），在 127.0.0.1 上用配对码配对，再互传文字与文件。
/// Two links through the C ABI (mDNS off) pair over 127.0.0.1 with the code, then exchange text and a file.
@Suite(.serialized) struct LinkIntegrationTests {
    private func start(_ name: String, platform: String, in dir: URL) throws -> LinkHandle {
        try #require(LinkHandle(config: [
            "name": name, "platform": platform, "mdns": false,
            "stateDir": dir.appendingPathComponent("\(platform)/state").path,
            "inboxDir": dir.appendingPathComponent("\(platform)/inbox").path,
        ]))
    }

    /// 等某类事件（其他事件跳过）。 Wait for an event of one kind, skipping others.
    private func wait(_ link: LinkHandle, _ type: String, seconds: Double = 10) -> [String: Any]? {
        let end = Date().addingTimeInterval(seconds)
        var observed: [String] = []
        while Date() < end {
            guard let json = link.poll(timeoutMs: 200) else { return nil }
            if let o = LinkJSON.object(json), o.str("type") == type { return o }
            if !json.contains("idle") { observed.append(json) }
        }
        print("WeaveLink timeout awaiting \(type): \(observed)")
        return nil
    }

    @Test func directUdpPairAndFileThroughTheCABI() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("weave-mac-direct-\(getpid())")
        try? FileManager.default.removeItem(at: dir)
        defer { try? FileManager.default.removeItem(at: dir) }
        let a = try start("UDP Mac", platform: "mac-a", in: dir)
        let b = try start("UDP phone", platform: "mac-b", in: dir)
        defer { a.stop(); b.stop() }
        #expect(a.call(["op":"openDirect", "stun": [String]()]).bool("ok"))
        #expect(b.call(["op":"openDirect", "stun": [String]()]).bool("ok"))
        let ta = try #require(wait(a, "directReady")).str("ticket")
        let tb = try #require(wait(b, "directReady")).str("ticket")
        #expect(a.call(["op":"joinDirect", "ticket":tb]).bool("ok"))
        #expect(b.call(["op":"joinDirect", "ticket":ta]).bool("ok"))
        #expect(try #require(wait(a, "connected")).str("transport") == "direct-udp")
        #expect(try #require(wait(b, "connected")).str("transport") == "direct-udp")
        let data = Data((0..<200_000).map { UInt8($0 % 251) })
        let file = dir.appendingPathComponent("direct.bin")
        try data.write(to: file)
        #expect(a.call(["op":"sendFile", "path":file.path, "name":"direct.bin"]).bool("ok"))
        let done = try #require(wait(b, "fileDone"))
        #expect(try Data(contentsOf: URL(fileURLWithPath: done.str("path"))) == data)
    }

    @Test func pairSendTextAndFile() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("weave-mac-link-\(getpid())")
        try? FileManager.default.removeItem(at: dir)
        defer { try? FileManager.default.removeItem(at: dir) }
        let mac = try start("测试 Mac", platform: "mac", in: dir)
        let phone = try start("测试手机", platform: "android", in: dir)
        defer {
            mac.stop()
            phone.stop()
        }

        let info = LinkInfo(json: mac.call(["op": "info"]))
        #expect(info.name == "测试 Mac")
        #expect(!info.fingerprint.isEmpty)
        #expect(info.port > 0)

        let open = mac.call(["op": "openPairing"])
        let pairing = LinkPairing(json: open)
        #expect(pairing.code.count == 6)
        #expect(pairing.uri.hasPrefix("weavelink://pair?"))
        #expect(QRCode.image(for: pairing.uri) != nil)

        let addr = "127.0.0.1:\(info.port)"
        #expect(phone.call(["op": "pair", "addrs": [addr], "code": pairing.code]).bool("ok"))
        let paired = try #require(wait(mac, "paired"))
        #expect(LinkEvent.parse(LinkJSON.string(paired)!) == .paired(id: paired.str("id"), name: "测试手机"))
        #expect(wait(phone, "paired") != nil)

        var state = LinkState()
        state.setPeers(json: mac.call(["op": "peers"]))
        #expect(state.connected.map(\.name) == ["测试手机"])

        // 手机 → Mac 的剪贴板文字。 Clipboard text from the phone to the Mac.
        #expect(phone.call(["op": "sendText", "text": "你好，电脑", "clip": true]).bool("ok"))
        let text = try #require(wait(mac, "text"))
        #expect(state.apply(LinkEvent.parse(LinkJSON.string(text)!))
            == [.receivedText(text: "你好，电脑", clip: true, from: "测试手机")])

        // Mac → 手机的文件。 A file from the Mac to the phone.
        let src = dir.appendingPathComponent("报告.bin")
        let body = Data((0..<150_000).map { UInt8($0 % 251) })
        try body.write(to: src)
        let sent = mac.call(["op": "sendFile", "path": src.path, "name": "报告.bin", "mime": "application/octet-stream",
                             "clip": false])
        #expect(sent.bool("ok"))
        let done = try #require(wait(phone, "fileDone"))
        let got = URL(fileURLWithPath: done.str("path"))
        #expect(got.lastPathComponent == "报告.bin")
        #expect(try Data(contentsOf: got) == body)
        let macDone = try #require(wait(mac, "fileDone"))
        #expect(macDone.str("id") == sent.str("id"))

        // 停止后 poll 返回 nil。 poll returns nil once stopped.
        mac.stop()
        var drained = 0
        while mac.poll(timeoutMs: 100) != nil, drained < 100 { drained += 1 }
        #expect(mac.poll(timeoutMs: 10) == nil)
    }
}
