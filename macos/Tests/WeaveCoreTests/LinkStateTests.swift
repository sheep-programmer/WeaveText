import Foundation
import Testing
@testable import WeaveCore

@Suite struct LinkEventTests {
    @Test func directTransportEventsAreRecognized() {
        #expect(LinkEvent.parse(#"{"type":"directReady","ticket":"code","expiresIn":300,"public":true}"#)
            == .directReady(ticket: "code", expiresIn: 300, publicMapping: true))
        #expect(LinkEvent.parse(#"{"type":"directFailed","reason":"no relay"}"#) == .directFailed(reason: "no relay"))
        let event = LinkEvent.parse(#"{"type":"connected","transport":"direct-udp"}"#)
        #expect(event == .directConnected)
        var state = LinkState()
        #expect(state.apply(event) == [.refreshPeers])
    }
    @Test func parsesEveryEventKind() {
        #expect(LinkEvent.parse(#"{"type":"idle"}"#) == .idle)
        for t in ["peerFound", "peerLost", "connected", "disconnected"] {
            #expect(LinkEvent.parse(#"{"type":"\#(t)","id":"x"}"#) == .peersChanged)
        }
        #expect(LinkEvent.parse(#"{"type":"paired","id":"p","name":"Pixel","platform":"android"}"#)
            == .paired(id: "p", name: "Pixel"))
        #expect(LinkEvent.parse(#"{"type":"pairAttempt","ok":false,"reason":"wrong code","stillOpen":true}"#)
            == .pairAttempt(ok: false, stillOpen: true))
        #expect(LinkEvent.parse(#"{"type":"text","from":"p","fromName":"Pixel","text":"你好","clip":true}"#)
            == .text(text: "你好", clip: true, from: "Pixel"))
        #expect(LinkEvent.parse(#"{"type":"fileStart","id":"f","to":"p","name":"a.pdf","size":10,"mime":"application/pdf","incoming":false,"clip":false}"#)
            == .fileStart(id: "f", name: "a.pdf", size: 10, incoming: false, peer: "p", clip: false))
        #expect(LinkEvent.parse(#"{"type":"fileProgress","id":"f","done":4,"size":10,"incoming":true}"#)
            == .fileProgress(id: "f", done: 4, size: 10))
        #expect(LinkEvent.parse(#"{"type":"fileDone","id":"f","to":"p","name":"a.pdf","incoming":false,"clip":false}"#)
            == .fileDone(id: "f", name: "a.pdf", incoming: false, path: nil, mime: "application/octet-stream",
                         clip: false, from: ""))
        #expect(LinkEvent.parse(#"{"type":"fileFailed","id":"f","name":"a","incoming":true,"reason":"checksum"}"#)
            == .fileFailed(id: "f", incoming: true, reason: "checksum"))
        #expect(LinkEvent.parse(#"{"type":"error","message":"boom"}"#) == .error("boom"))
        #expect(LinkEvent.parse("not json") == .unknown("not json"))
    }
}

@Suite struct LinkStateTests {
    private func sample() -> LinkState {
        var s = LinkState()
        s.setPeers(json: [
            "trusted": [["id": "p", "name": "Pixel", "platform": "android", "connected": true, "nearby": true],
                        ["id": "q", "name": "", "platform": "android", "connected": false, "nearby": false]],
            "nearby": [["id": "n", "name": "Tab", "platform": "android", "addrs": ["10.0.0.2:47811"]]],
        ])
        return s
    }

    @Test func peersAndStatus() {
        let s = sample()
        #expect(s.connected.map(\.id) == ["p"])
        #expect(s.trusted[1].displayName == "Android")
        #expect(LinkText.peerStatus(s.trusted[0]) == "已连接 · Android")
        #expect(LinkText.peerStatus(s.trusted[1]) == "不在线")
        #expect(s.nearby.first?.name == "Tab")
    }

    @Test func connectionEventsAskForAPeerRefresh() {
        var s = sample()
        let r1 = s.apply(.peersChanged)
        #expect(r1 == [.refreshPeers])
        let r2 = s.apply(.idle)
        #expect(r2 == [])
    }

    @Test func transfersProgressAndFinish() {
        var s = sample()
        _ = s.apply(.fileStart(id: "f", name: "a.pdf", size: 100, incoming: false, peer: "p", clip: false))
        #expect(s.transfers.first?.peer == "Pixel")
        _ = s.apply(.fileProgress(id: "f", done: 42, size: 100))
        #expect(s.transfer("f")?.fraction == 0.42)
        #expect(s.transfer("f")?.status == "发送中 · 42 B / 100 B")
        let fx = s.apply(.fileDone(id: "f", name: "a.pdf", incoming: false, path: nil, mime: "", clip: false, from: ""))
        #expect(fx == [.outgoingFinished(id: "f", ok: true)])
        #expect(s.transfer("f")?.state == .done)
        #expect(s.transfer("f")?.status == "已发送 · 100 B")
    }

    @Test func receivedFilesAndClipboardImages() {
        var s = sample()
        _ = s.apply(.fileStart(id: "g", name: "照片.jpg", size: 5, incoming: true, peer: "Pixel", clip: false))
        let file = s.apply(.fileDone(id: "g", name: "照片.jpg", incoming: true, path: "/tmp/照片.jpg", mime: "image/jpeg",
                                     clip: false, from: "Pixel"))
        #expect(file == [.receivedFile(path: "/tmp/照片.jpg", name: "照片.jpg", from: "Pixel")])
        // 剪贴板图片不进传输列表，结束时放上剪贴板。 Clipboard images stay out of the list and go to the pasteboard.
        _ = s.apply(.fileStart(id: "c", name: "clip.png", size: 5, incoming: true, peer: "Pixel", clip: true))
        #expect(s.transfer("c") == nil)
        let img = s.apply(.fileDone(id: "c", name: "clip.png", incoming: true, path: "/tmp/c.png", mime: "image/png",
                                    clip: true, from: "Pixel"))
        #expect(img == [.receivedClipImage(path: "/tmp/c.png")])
        let generic = s.apply(.fileDone(id: "d", name: "report.pdf", incoming: true, path: "/tmp/report.pdf", mime: "application/pdf", clip: true, from: "Pixel"))
        #expect(generic == [.receivedClipFile(path: "/tmp/report.pdf", name: "report.pdf", mime: "application/pdf")])
    }

    @Test func outgoingFailuresReleaseTheQueue() {
        var s = sample()
        // 发出之前就失败（没有 fileStart）也要通知队列。 A failure before any fileStart still frees the queue.
        let r3 = s.apply(.fileFailed(id: "x", incoming: false, reason: "io"))
        #expect(r3 == [.outgoingFinished(id: "x", ok: false)])
        let r4 = s.apply(.fileFailed(id: "y", incoming: true, reason: "checksum"))
        #expect(r4 == [])
    }

    @Test func textAndPairing() {
        var s = sample()
        let r5 = s.apply(.text(text: "hi", clip: false, from: "Pixel"))
        #expect(r5 == [.receivedText(text: "hi", clip: false, from: "Pixel")])
        let r6 = s.apply(.text(text: "", clip: true, from: "Pixel"))
        #expect(r6 == [])
        let now = Date()
        s.pairing = LinkPairing(json: ["code": "123456", "uri": "weavelink://pair?c=123456", "expiresIn": 120,
                                       "addrs": ["192.168.1.8:47811"]], now: now)
        #expect(s.pairing?.remaining(at: now.addingTimeInterval(30)) == 90)
        #expect(s.pairing?.expired(at: now.addingTimeInterval(121)) == true)
        _ = s.apply(.pairAttempt(ok: false, stillOpen: true))
        #expect(s.pairing?.message != nil)
        #expect(s.pairing?.closed == false)
        _ = s.apply(.pairAttempt(ok: false, stillOpen: false))
        #expect(s.pairing?.expired(at: now) == true)
        let r7 = s.apply(.paired(id: "z", name: "新手机"))
        #expect(r7 == [.refreshPeers])
        #expect(s.pairing?.pairedWith == "新手机")
    }

    @Test func stoppingTakesEverythingOffline() {
        var s = sample()
        s.running = true
        _ = s.apply(.fileStart(id: "f", name: "a", size: 9, incoming: true, peer: "Pixel", clip: false))
        s.stopped()
        #expect(!s.running)
        #expect(s.connected.isEmpty)
        #expect(s.nearby.isEmpty)
        #expect(s.transfer("f")?.state == .failed)
    }

    @Test func transferListIsCapped() {
        var s = LinkState()
        for i in 0..<30 { _ = s.apply(.fileStart(id: "\(i)", name: "f", size: 1, incoming: true, peer: "", clip: false)) }
        #expect(s.transfers.count == LinkState.maxTransfers)
        #expect(s.transfers.first?.id == "29")
    }

    @Test func sizes() {
        #expect(LinkText.size(512) == "512 B")
        #expect(LinkText.size(3 << 10) == "3 KB")
        #expect(LinkText.size(Int64(7.8 * Double(1 << 20))) == "7.8 MB")
    }
}

@Suite struct ClipboardGuardTests {
    @Test func onlyNewForeignChangesCount() {
        var g = ClipboardGuard(changeCount: 5)
        let r8 = g.changed(5)
        #expect(!r8)
        let r9 = g.changed(6)
        #expect(r9)
        let r10 = g.changed(6)
        #expect(!r10)
        g.wroteRemote(text: "来自手机", changeCount: 7)
        let r11 = g.changed(7)
        #expect(!r11)
        let r12 = g.changed(8)
        #expect(r12)
    }

    @Test func neverEchoesWhatCameFromThePhone() {
        var g = ClipboardGuard(changeCount: 0)
        g.wroteRemote(text: "手机上的字", changeCount: 1)
        let r13 = g.shouldSend(text: "手机上的字", types: ["public.utf8-plain-text"])
        #expect(!r13)
        let r14 = g.shouldSend(text: "Mac 上的字", types: ["public.utf8-plain-text"])
        #expect(r14)
        // 同一段不重复发。 The same text isn't sent twice.
        let r15 = g.shouldSend(text: "Mac 上的字", types: [])
        #expect(!r15)
    }

    @Test func skipsConcealedEmptyAndHugeText() {
        var g = ClipboardGuard(changeCount: 0)
        let r16 = g.shouldSend(text: "hunter2", types: ["public.utf8-plain-text", "org.nspasteboard.ConcealedType"])
        #expect(!r16)
        let r17 = g.shouldSend(text: "otp", types: ["org.nspasteboard.TransientType"])
        #expect(!r17)
        let r18 = g.shouldSend(text: "  \n", types: [])
        #expect(!r18)
        let r19 = g.shouldSend(text: String(repeating: "字", count: ClipboardGuard.maxChars + 1), types: [])
        #expect(!r19)
        let r20 = g.shouldSend(text: String(repeating: "字", count: ClipboardGuard.maxChars), types: [])
        #expect(r20)
    }

    @Test func imagesByDigest() {
        var g = ClipboardGuard(changeCount: 0)
        g.wroteRemote(imageDigest: "abc", changeCount: 1)
        let r21 = g.shouldSend(imageDigest: "abc", bytes: 10, types: [])
        #expect(!r21)
        let r22 = g.shouldSend(imageDigest: "def", bytes: 10, types: [])
        #expect(r22)
        let r23 = g.shouldSend(imageDigest: "big", bytes: ClipboardGuard.maxImageBytes + 1, types: [])
        #expect(!r23)
    }
}

@Suite struct SendQueueTests {
    @Test func sendsOneAfterAnother() {
        var q = SendQueue()
        #expect(q.title(fraction: 0) == nil)
        q.add(["/a", "/b", "/c", "/d", "/e"])
        let r24 = q.next()
        #expect(r24 == "/a")
        let r25 = q.next()
        #expect(r25 == nil)
        q.finish(ok: true)
        q.finish(ok: true)
        let r26 = q.next()
        #expect(r26 == "/b")
        q.finish(ok: true)
        let r27 = q.next()
        #expect(r27 == "/c")
        #expect(q.title(fraction: 0.42) == "正在发送 3/5 · 42%")
        q.finish(ok: false)
        #expect(q.failed == 1)
        q.cancelAll()
        #expect(!q.isActive)
    }

    @Test func aSingleFileHasNoCounter() {
        var q = SendQueue()
        q.add(["/a"])
        _ = q.next()
        #expect(q.title(fraction: 0.5) == "正在发送 · 50%")
        q.finish(ok: true)
        let r28 = q.next()
        #expect(r28 == nil)
        #expect(!q.isActive)
        q.add(["/b"])
        #expect(q.total == 1)
    }
}

@Suite struct QRCodeTests {
    @Test func makesASquareImage() throws {
        let img = try #require(QRCode.image(for: "weavelink://pair?v=1&id=abc&c=123456", scale: 4, margin: 2))
        #expect(img.width == img.height)
        #expect(img.width % 4 == 0)
        #expect(img.width >= (21 + 4) * 4)
    }
}
