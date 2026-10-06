import Combine
import Foundation

public struct TranslationPluginOS: Codable, Equatable, Comparable, Sendable {
    public let major: Int
    public let minor: Int
    public let patch: Int
    public init(major: Int, minor: Int = 0, patch: Int = 0) { self.major = major; self.minor = minor; self.patch = patch }
    public static var current: Self {
        let version = ProcessInfo.processInfo.operatingSystemVersion
        return Self(major: version.majorVersion, minor: version.minorVersion, patch: version.patchVersion)
    }
    public static func < (lhs: Self, rhs: Self) -> Bool {
        (lhs.major, lhs.minor, lhs.patch) < (rhs.major, rhs.minor, rhs.patch)
    }
    public var label: String { patch == 0 ? "\(major).\(minor)" : "\(major).\(minor).\(patch)" }
}

/// A descriptor for the built-in adapter, not a model archive or executable SDK.
public struct TranslationPluginDescriptor: Equatable, Sendable {
    public static let appleID = "com.weavetext.translation.apple"
    public let id: String
    public let name: String
    public let version: String
    public let engine: String
    public let minimumOS: TranslationPluginOS
    public init(id: String = TranslationPluginDescriptor.appleID, name: String = "Apple 离线翻译适配插件", version: String,
                engine: String = "apple-system", minimumOS: TranslationPluginOS = .init(major: 15)) {
        self.id = id; self.name = name; self.version = version; self.engine = engine; self.minimumOS = minimumOS
    }
}

public enum TranslationPluginState: Equatable, Sendable {
    case notInstalled, installed, enabled, unsupported(minimumOS: String), invalid
    public var label: String {
        switch self {
        case .notInstalled: return "未安装"
        case .installed: return "已安装 · 未启用"
        case .enabled: return "已安装 · 已启用"
        case .unsupported(let os): return "当前系统不支持 · 需要 macOS \(os)+"
        case .invalid: return "描述包无效，请重新导入"
        }
    }
}

public struct TranslationPluginRecord: Identifiable, Equatable, Sendable {
    public var id: String { TranslationPluginDescriptor.appleID }
    public let descriptor: TranslationPluginDescriptor?
    public let state: TranslationPluginState
}

public enum TranslationPluginFailure: Error, LocalizedError, Equatable {
    case invalidPackage, invalidDescriptor, tooLarge, unsupported, notInstalled, hostUnavailable
    public var errorDescription: String? {
        switch self {
        case .invalidPackage: return "请选择含 manifest.yaml 和 main.lua 的翻译描述包 ZIP、目录或原始清单。"
        case .invalidDescriptor: return "此包不是受支持的 Apple 系统翻译适配描述包。"
        case .tooLarge: return "翻译描述包过大；适配包不应包含语言模型。"
        case .unsupported: return "当前 macOS 版本不满足此插件的最低系统要求。"
        case .notInstalled: return "请先手动安装 Apple 离线翻译适配插件。"
        case .hostUnavailable: return "翻译插件包管理不可用。"
        }
    }
}

public protocol TranslationPluginRepository: Sendable {
    func installed() throws -> TranslationPluginDescriptor?
    func install(_ source: URL) throws -> TranslationPluginDescriptor
    func uninstall() throws
}

/// Uses the existing content-based Rust ZIP inspector/installer in a dedicated pool. It never
/// preloads or executes Lua. All candidates are unpacked/validated in a private staging directory
/// before the real pool is touched. Rust enforces safe paths, 4096 entries, 64 MiB/entry, 256 MiB total;
/// this descriptor layer additionally caps input/package size, manifest and main.lua.
public final class FileTranslationPluginRepository: TranslationPluginRepository, @unchecked Sendable {
    public static let maxPackageBytes = 2 * 1024 * 1024
    public static let maxManifestBytes = 32 * 1024
    public static let maxEntryBytes = 64 * 1024
    private let directory: URL
    private let lock = NSLock()

    /// directory is the independent translation-plugins pool, not the general speech-plugin root.
    public init(directory: URL) { self.directory = directory }
    private var installedDirectory: URL { directory.appendingPathComponent("plugins").appendingPathComponent(TranslationPluginDescriptor.appleID) }

    public func installed() throws -> TranslationPluginDescriptor? {
        try lock.withLock {
            guard FileManager.default.fileExists(atPath: installedDirectory.path) else { return nil }
            return try Self.validate(directory: installedDirectory)
        }
    }

    public func install(_ source: URL) throws -> TranslationPluginDescriptor {
        try lock.withLock {
            let fm = FileManager.default
            let staging = fm.temporaryDirectory.appendingPathComponent("weave-translation-import-\(UUID().uuidString)", isDirectory: true)
            try fm.createDirectory(at: staging, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
            defer { try? fm.removeItem(at: staging) }
            let archive = staging.appendingPathComponent("candidate-package")
            let values = try source.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
            guard values.isSymbolicLink != true else { throw TranslationPluginFailure.invalidPackage }
            if values.isDirectory == true {
                try Self.snapshotManifest(source.appendingPathComponent("manifest.yaml"), into: staging, archive: archive)
            } else {
                let data = try Self.readRegular(source, limit: Self.maxPackageBytes)
                if data.starts(with: [0x50, 0x4b, 0x03, 0x04]) {
                    try data.write(to: archive, options: .atomic)
                } else {
                    // A raw manifest may have any filename; use its contents and sibling main.lua.
                    try Self.snapshotManifest(source, into: staging, archive: archive)
                }
            }
            let inspected = try PluginHost.inspect(archive)
            try Self.validateInfo(inspected)
            guard let candidateHost = PluginHost(directory: staging.appendingPathComponent("check")) else { throw TranslationPluginFailure.hostUnavailable }
            let candidate = try candidateHost.install(archive)
            try Self.validateInfo(candidate)
            let unpacked = staging.appendingPathComponent("check/plugins").appendingPathComponent(candidate.id)
            let descriptor = try Self.validate(directory: unpacked)
            guard let enumerator = fm.enumerator(at: unpacked, includingPropertiesForKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey]) else {
                throw TranslationPluginFailure.invalidPackage
            }
            var total = 0, count = 0
            for case let file as URL in enumerator {
                let metadata = try file.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
                guard metadata.isSymbolicLink != true else { throw TranslationPluginFailure.invalidPackage }
                if metadata.isRegularFile == true {
                    count += 1; total += metadata.fileSize ?? 0
                    guard count <= 64, total <= Self.maxPackageBytes else { throw TranslationPluginFailure.tooLarge }
                }
            }
            // The private snapshot cannot change between validation and the final atomic Rust install.
            guard let host = PluginHost(directory: directory) else { throw TranslationPluginFailure.hostUnavailable }
            let installed = try host.install(archive)
            try Self.validateInfo(installed)
            guard installed.version == descriptor.version else { throw TranslationPluginFailure.invalidDescriptor }
            return descriptor
        }
    }

    public func uninstall() throws {
        try lock.withLock {
            guard FileManager.default.fileExists(atPath: installedDirectory.path) else { return }
            if (try? Self.validate(directory: installedDirectory)) == nil {
                // A damaged descriptor may not register in PluginHost.scan; removal is still confined
                // to the single fixed id inside this dedicated pool.
                try FileManager.default.removeItem(at: installedDirectory)
                try? FileManager.default.removeItem(at: directory.appendingPathComponent("plugin-config/\(TranslationPluginDescriptor.appleID).json"))
                return
            }
            // This host owns only the isolated pool; no speech/plugin FFI configuration is modified.
            guard let host = PluginHost(directory: directory) else { throw TranslationPluginFailure.hostUnavailable }
            try host.uninstall(TranslationPluginDescriptor.appleID)
        }
    }

    private static func snapshotManifest(_ manifest: URL, into staging: URL, archive: URL) throws {
        let data = try readRegular(manifest, limit: maxManifestBytes)
        _ = try fields(data)
        let script = try readRegular(manifest.deletingLastPathComponent().appendingPathComponent("main.lua"), limit: maxEntryBytes)
        guard !script.isEmpty, String(data: script, encoding: .utf8) != nil else { throw TranslationPluginFailure.invalidDescriptor }
        let snapshot = staging.appendingPathComponent("source", isDirectory: true)
        try FileManager.default.createDirectory(at: snapshot, withIntermediateDirectories: false)
        try data.write(to: snapshot.appendingPathComponent("manifest.yaml"), options: .atomic)
        try script.write(to: snapshot.appendingPathComponent("main.lua"), options: .atomic)
        try PluginHost.package(source: snapshot, output: archive)
    }

    private static func readRegular(_ url: URL, limit: Int) throws -> Data {
        let metadata = try url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
        guard metadata.isRegularFile == true, metadata.isSymbolicLink != true else { throw TranslationPluginFailure.invalidPackage }
        guard (metadata.fileSize ?? 0) <= limit else { throw TranslationPluginFailure.tooLarge }
        let file = try FileHandle(forReadingFrom: url)
        defer { try? file.close() }
        let data = try file.read(upToCount: limit + 1) ?? Data()
        guard data.count <= limit else { throw TranslationPluginFailure.tooLarge }
        return data
    }

    private static func validateInfo(_ info: PluginInfo) throws {
        guard info.id == TranslationPluginDescriptor.appleID, info.kind == "translation",
              !info.version.isEmpty, info.version.utf8.count <= 128,
              info.networkHosts.isEmpty, !info.unrestrictedNetwork else { throw TranslationPluginFailure.invalidDescriptor }
    }

    private static func validate(directory: URL) throws -> TranslationPluginDescriptor {
        guard try directory.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey]).isSymbolicLink != true else {
            throw TranslationPluginFailure.invalidPackage
        }
        let info = try PluginHost.inspect(directory)
        try validateInfo(info)
        let metadata = try fields(readRegular(directory.appendingPathComponent("manifest.yaml"), limit: maxManifestBytes))
        let script = try readRegular(directory.appendingPathComponent("main.lua"), limit: maxEntryBytes)
        guard !script.isEmpty, String(data: script, encoding: .utf8) != nil,
              metadata["id"] == info.id, metadata["type"] == info.kind,
              metadata["version"] == info.version else { throw TranslationPluginFailure.invalidDescriptor }
        let os = try minimumOS(metadata["min_os_version"] ?? metadata["minOS"] ?? "")
        return TranslationPluginDescriptor(name: info.name, version: info.version, minimumOS: os)
    }

    /// The descriptor contract uses top-level scalar fields. Rust remains the canonical YAML/id/entry
    /// validator; this narrow parser validates the extra engine/minOS fields absent from PluginInfo.
    private static func fields(_ data: Data) throws -> [String: String] {
        guard let yaml = String(data: data, encoding: .utf8) else { throw TranslationPluginFailure.invalidDescriptor }
        let keys: Set<String> = ["id", "type", "version", "entry", "engine", "minOS", "min_os_version"]
        var result: [String: String] = [:]
        for line in yaml.components(separatedBy: .newlines) {
            guard line.first?.isWhitespace != true, let colon = line.firstIndex(of: ":") else { continue }
            let key = String(line[..<colon]).trimmingCharacters(in: .whitespaces)
            guard keys.contains(key) else { continue }
            guard result[key] == nil else { throw TranslationPluginFailure.invalidDescriptor }
            var value = String(line[line.index(after: colon)...]).trimmingCharacters(in: .whitespaces)
            if value.first == "\"" || value.first == "'" {
                let quote = value.removeFirst()
                guard let end = value.firstIndex(of: quote) else { throw TranslationPluginFailure.invalidDescriptor }
                let rest = value[value.index(after: end)...].trimmingCharacters(in: .whitespaces)
                guard rest.isEmpty || rest.hasPrefix("#") else { throw TranslationPluginFailure.invalidDescriptor }
                value = String(value[..<end])
            } else {
                value = String(value.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false).first ?? "")
                    .trimmingCharacters(in: .whitespaces)
            }
            result[key] = value
        }
        guard result["id"] == TranslationPluginDescriptor.appleID, result["type"] == "translation",
              result["engine"] == "apple-system", (result["entry"] ?? "main.lua") == "main.lua",
              let version = result["version"], !version.isEmpty else { throw TranslationPluginFailure.invalidDescriptor }
        let os = try minimumOS(result["min_os_version"] ?? result["minOS"] ?? "")
        if let alternate = result["minOS"] { guard try minimumOS(alternate) == os else { throw TranslationPluginFailure.invalidDescriptor } }
        return result
    }

    private static func minimumOS(_ value: String) throws -> TranslationPluginOS {
        let components = value.split(separator: ".", omittingEmptySubsequences: false)
        guard (1...3).contains(components.count), components.allSatisfy({ !$0.isEmpty && $0.allSatisfy({ $0.isASCII && $0.isNumber }) }) else {
            throw TranslationPluginFailure.invalidDescriptor
        }
        let numbers = components.compactMap { Int($0) }
        guard numbers.count == components.count, numbers.allSatisfy({ $0 <= 999 }), numbers[0] >= 15 else {
            throw TranslationPluginFailure.invalidDescriptor
        }
        return TranslationPluginOS(major: numbers[0], minor: numbers.count > 1 ? numbers[1] : 0, patch: numbers.count > 2 ? numbers[2] : 0)
    }
}

@MainActor public final class TranslationPlugins: ObservableObject {
    public static let enabledKey = "weave.translation.plugins.appleEnabled"
    @Published public private(set) var plugins: [TranslationPluginRecord] = []
    @Published public private(set) var busy = false
    @Published public private(set) var message = ""
    private let repository: any TranslationPluginRepository
    private let defaults: UserDefaults
    private let operatingSystem: TranslationPluginOS

    public init(repository: any TranslationPluginRepository, defaults: UserDefaults, operatingSystem: TranslationPluginOS = .current) {
        self.repository = repository; self.defaults = defaults; self.operatingSystem = operatingSystem
        refresh()
    }
    public convenience init(directory: URL, defaults: UserDefaults, operatingSystem: TranslationPluginOS = .current) {
        self.init(repository: FileTranslationPluginRepository(directory: directory), defaults: defaults, operatingSystem: operatingSystem)
    }
    public var apple: TranslationPluginRecord { plugins.first ?? TranslationPluginRecord(descriptor: nil, state: .notInstalled) }
    public var appleReady: Bool { !busy && apple.state == .enabled }
    public var nativeFailure: TranslationFailure? {
        switch apple.state {
        case .enabled: return busy ? .nativePluginDisabled : nil
        case .installed: return .nativePluginDisabled
        case .unsupported: return .nativeUnavailable
        case .notInstalled, .invalid: return .nativePluginMissing
        }
    }

    public func refresh() {
        do {
            let descriptor = try repository.installed()
            let state: TranslationPluginState
            if let descriptor {
                guard descriptor.id == TranslationPluginDescriptor.appleID, descriptor.engine == "apple-system",
                      descriptor.minimumOS.major >= 15, !descriptor.version.isEmpty else { throw TranslationPluginFailure.invalidDescriptor }
                if operatingSystem < descriptor.minimumOS { state = .unsupported(minimumOS: descriptor.minimumOS.label) }
                else { state = defaults.bool(forKey: Self.enabledKey) ? .enabled : .installed }
            } else { state = .notInstalled }
            plugins = [TranslationPluginRecord(descriptor: descriptor, state: state)]
        } catch {
            plugins = [TranslationPluginRecord(descriptor: nil, state: .invalid)]
            message = "翻译描述包无效，请卸载后重新导入。"
        }
    }

    public func setEnabled(_ enabled: Bool) throws {
        guard !busy else { return }
        if enabled {
            guard let descriptor = apple.descriptor else { throw TranslationPluginFailure.notInstalled }
            guard operatingSystem >= descriptor.minimumOS else { throw TranslationPluginFailure.unsupported }
        }
        defaults.set(enabled, forKey: Self.enabledKey)
        refresh()
        message = enabled ? "适配插件已启用；系统语言包仍由 Apple 管理。" : "适配插件已停用。"
    }

    public func install(_ source: URL) async {
        guard !busy else { return }
        busy = true; message = "正在检查并安装翻译适配描述包…"
        defer { busy = false }
        let repository = repository
        do {
            _ = try await Task.detached { try repository.install(source) }.value
            defaults.set(false, forKey: Self.enabledKey)
            refresh()
            message = "适配描述包已安装，请手动启用；ZIP 不含系统语言模型。"
        } catch { message = (error as? TranslationPluginFailure)?.localizedDescription ?? "翻译描述包安装失败，请检查清单和入口。" }
    }

    public func uninstall() async {
        guard !busy else { return }
        busy = true; message = "正在卸载翻译适配插件…"
        defer { busy = false }
        let repository = repository
        do {
            try await Task.detached { try repository.uninstall() }.value
            defaults.removeObject(forKey: Self.enabledKey)
            refresh()
            message = "适配插件已卸载；不会删除 Apple 管理的系统语言包。"
        } catch { message = "翻译适配插件卸载失败，请重试。" }
    }
}
