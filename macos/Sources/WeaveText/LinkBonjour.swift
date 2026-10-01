import Foundation
import Darwin
import WeaveCore

/// Uses the system Bonjour broker, which follows interfaces and macOS local-network permission.
final class LinkBonjour: NSObject, NetServiceBrowserDelegate, NetServiceDelegate {
    private var browser: NetServiceBrowser?
    private var publisher: NetService?
    private var services: [String: NetService] = [:]
    private var ids: [String: String] = [:]
    private var ownID = ""
    private var generation = 0
    var command: (([String: Any]) -> Void)?
    var status: ((Bool, String?) -> Void)?

    func start(info: LinkInfo) {
        stop()
        ownID = info.id
        generation += 1
        let token = generation
        status?(true, nil)
        let service = NetService(domain: "local.", type: "_weavelink._tcp.", name: info.id, port: Int32(info.port))
        service.delegate = self
        service.setTXTRecord(NetService.data(fromTXTRecord: ["id": Data(info.id.utf8), "n": Data(info.name.utf8), "p": Data("mac".utf8), "v": Data("1".utf8)]))
        publisher = service
        service.publish()
        let finder = NetServiceBrowser()
        finder.delegate = self
        browser = finder
        finder.searchForServices(ofType: "_weavelink._tcp.", inDomain: "local.")
        DispatchQueue.main.asyncAfter(deadline: .now() + 12) { [weak self] in
            guard let self, self.generation == token else { return }
            self.status?(false, nil)
        }
    }
    func stop() {
        generation += 1
        browser?.stop(); browser?.delegate = nil; browser = nil
        publisher?.stop(); publisher?.delegate = nil; publisher = nil
        for service in services.values { service.stop(); service.delegate = nil }
        for id in ids.values { command?(["op": "discoveryLost", "id": id]) }
        services = [:]; ids = [:]
    }
    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        guard service.name != ownID else { return }
        services[service.name] = service
        service.delegate = self
        service.resolve(withTimeout: 8)
    }
    func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
        services.removeValue(forKey: service.name)?.stop()
        if let id = ids.removeValue(forKey: service.name) { command?(["op": "discoveryLost", "id": id]) }
    }
    func netServiceDidResolveAddress(_ service: NetService) {
        guard services[service.name] === service else { return }
        let attrs = service.txtRecordData().map(NetService.dictionary(fromTXTRecord:)) ?? [:]
        func attr(_ key: String) -> String { attrs[key].flatMap { String(data: $0, encoding: .utf8) } ?? "" }
        let id = attr("id")
        guard !id.isEmpty, id != ownID else { return }
        let endpoints = (service.addresses ?? []).compactMap(Self.endpoint)
        guard !endpoints.isEmpty else { return }
        ids[service.name] = id
        command?(["op": "discovered", "id": id, "name": attr("n"), "platform": attr("p"), "addrs": endpoints])
        status?(false, nil)
    }
    static func endpoint(_ data: Data) -> String? {
        data.withUnsafeBytes { buffer in
            guard let base = buffer.baseAddress, data.count >= MemoryLayout<sockaddr>.size else { return nil }
            let address = base.assumingMemoryBound(to: sockaddr.self)
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            var port = [CChar](repeating: 0, count: Int(NI_MAXSERV))
            guard getnameinfo(address, socklen_t(data.count), &host, socklen_t(host.count), &port, socklen_t(port.count), NI_NUMERICHOST | NI_NUMERICSERV) == 0 else { return nil }
            var ip = String(cString: host)
            if address.pointee.sa_family == sa_family_t(AF_INET6), data.count >= MemoryLayout<sockaddr_in6>.size {
                let scope = base.assumingMemoryBound(to: sockaddr_in6.self).pointee.sin6_scope_id
                ip = ip.components(separatedBy: "%")[0]
                if scope != 0 { ip += "%" + String(scope) }
            }
            let number = String(cString: port)
            return address.pointee.sa_family == sa_family_t(AF_INET6) ? "[\(ip)]:\(number)" : "\(ip):\(number)"
        }
    }
    func netServiceBrowser(_ browser: NetServiceBrowser, didNotSearch error: [String: NSNumber]) {
        generation += 1
        status?(false, "发现失败，请检查系统设置 › 隐私与安全性 › 本地网络，然后重新扫描（\(error)）")
    }
    func netService(_ sender: NetService, didNotPublish error: [String: NSNumber]) {
        generation += 1
        status?(false, "本机广播失败，请检查本地网络权限（\(error)）")
    }
    func netService(_ sender: NetService, didNotResolve error: [String: NSNumber]) {
        status?(false, "设备地址解析失败，可重新扫描或用地址配对")
    }
}
