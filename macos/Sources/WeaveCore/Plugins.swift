import CWeave
import Foundation

public struct PluginField: Decodable, Identifiable, Sendable {
    public var key: String
    public var label: String?
    public var type: String?
    public var description: String?
    public var helpText: String?
    public var section: String?
    public var required: Bool?
    public var defaultValue: String?
    public var options: [String]?
    public var id: String { key }
}
public struct PluginInfo: Decodable, Identifiable, Sendable {
    public var id: String
    public var name: String
    public var description: String
    public var version: String
    public var kind: String
    public var configSchema: [PluginField]
    public var networkHosts: [String]
    public var unrestrictedNetwork: Bool
    public var networkNote: String {
        unrestrictedNetwork ? "可连接自定义服务。使用语音时，录音会发送给插件配置的服务。" :
            (networkHosts.isEmpty ? "未声明网络访问。" : "可连接：" + networkHosts.joined(separator: "、") + "。使用语音时，录音会交给该插件。")
    }
}
public struct PluginFailure: LocalizedError, Sendable {
    public let message: String
    public init(_ message: String) { self.message = message }
    public var errorDescription: String? { message }
}

/// Host calls serialize separately from typing. Speech feed/release are serialized per session.
public final class PluginHost: @unchecked Sendable {
    private let handle: OpaquePointer
    private let lock = NSLock()
    public init?(directory: URL) {
        guard let h = weave_plugin_create(directory.appendingPathComponent("plugins").path,
                                          directory.appendingPathComponent("plugin-config").path) else { return nil }
        handle = h
    }
    deinit { weave_plugin_destroy(handle) }
    public static func inspect(_ url: URL) throws -> PluginInfo {
        try plugin(from: consume(weave_plugin_inspect(url.path)))
    }
    public static func package(source: URL, output: URL) throws {
        if let error = weave_plugin_package(source.path, output.path) {
            defer { weave_string_free(error) }; throw PluginFailure(String(cString: error))
        }
    }
    public func scan() throws -> [PluginInfo] {
        let result = try command(["op":"scan"])
        return try JSONDecoder().decode([PluginInfo].self, from: JSONSerialization.data(withJSONObject: result["plugins"] ?? []))
    }
    public func install(_ url: URL) throws -> PluginInfo { try Self.plugin(from: command(["op":"install", "path":url.path])) }
    public func uninstall(_ id: String) throws { _ = try command(["op":"uninstall", "id":id]) }
    public func getConfig(_ id: String, key: String) throws -> String { try command(["op":"getConfig", "id":id, "key":key])["value"] as? String ?? "" }
    public func setConfig(_ id: String, key: String, value: String) throws { _ = try command(["op":"setConfig", "id":id, "key":key, "value":value]) }
    public func configured(_ id: String) -> Bool { (try? command(["op":"configured", "id":id])["ok"] as? Bool) ?? false }
    public func speech(_ id: String, event: @escaping @Sendable ([String:Any]) -> Void) -> PluginSpeech? {
        let box = Unmanaged.passRetained(SpeechSink(event)).toOpaque()
        lock.lock(); defer { lock.unlock() }
        guard let s = weave_plugin_speech(handle, id, box, { context, json in
            guard let context, let json else { return }
            let sink = Unmanaged<SpeechSink>.fromOpaque(context).takeUnretainedValue()
            if let value = try? JSONSerialization.jsonObject(with: Data(String(cString: json).utf8)) as? [String:Any] { sink.event(value) }
        }, { context in
            if let context { Unmanaged<SpeechSink>.fromOpaque(context).release() }
        }) else { return nil }
        return PluginSpeech(s)
    }
    private func command(_ value: [String:Any]) throws -> [String:Any] {
        let data = try JSONSerialization.data(withJSONObject: value)
        lock.lock(); defer { lock.unlock() }
        let result = try Self.consume(weave_plugin_command(handle, String(decoding: data, as: UTF8.self)))
        if let error = result["error"] as? String { throw PluginFailure(error) }
        return result
    }
    private static func plugin(from value: [String:Any]) throws -> PluginInfo {
        if let error = value["error"] as? String { throw PluginFailure(error) }
        guard let plugin = value["plugin"] else { throw PluginFailure("插件信息不可用") }
        return try JSONDecoder().decode(PluginInfo.self, from: JSONSerialization.data(withJSONObject: plugin))
    }
    private static func consume(_ p: UnsafeMutablePointer<CChar>?) throws -> [String:Any] {
        guard let p else { throw PluginFailure("插件宿主没有返回结果") }
        defer { weave_string_free(p) }
        return try JSONSerialization.jsonObject(with: Data(String(cString:p).utf8)) as? [String:Any] ?? [:]
    }
}
private final class SpeechSink: @unchecked Sendable {
    let event: @Sendable ([String:Any]) -> Void
    init(_ event: @escaping @Sendable ([String:Any]) -> Void) { self.event = event }
}
public final class PluginSpeech: @unchecked Sendable {
    private var handle: OpaquePointer?
    private let lock = NSLock()
    fileprivate init(_ handle: OpaquePointer) { self.handle = handle }
    deinit { cancel() }
    public func feed(_ data: Data) {
        lock.lock(); defer { lock.unlock() }
        guard let handle else { return }
        data.withUnsafeBytes { weave_speech_feed(handle, $0.bindMemory(to: UInt8.self).baseAddress, data.count) }
    }
    public func stop() { lock.lock(); defer { lock.unlock() }; if let handle { weave_speech_stop(handle) } }
    public func cancel() {
        lock.lock(); let old = handle; handle = nil; lock.unlock()
        if let old { weave_speech_destroy(old) }
    }
}
