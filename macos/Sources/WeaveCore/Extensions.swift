import Combine
import Foundation

public enum ExtensionRegistry {
    public static let defaults: Set<String> = ["feature:voice", "feature:translate", "feature:stickers", "feature:phrases", "feature:link", "feature:cloudwords", "feature:calc", "scheme:hand", "scheme:wubi86"]
    public static func enabled(_ key: String, defaults: UserDefaults = .standard) -> Bool {
        Set(defaults.stringArray(forKey: "extensionsEnabled") ?? Array(Self.defaults)).contains(key)
    }
    public static func scheme(_ id: String, enabled: Set<String>) -> Bool {
        !["hand", "wubi86"].contains(id) || enabled.contains("scheme:" + id)
    }
}

public struct ExtensionItem: Codable, Identifiable, Equatable, Sendable {
    public let id: String
    public let kind: String
    public let name: String
    public let summary: String
    public let platforms: [String]
    public let source: String
    public let file: String?
    public let url: String?
    public let sha256: String?
    public let bytes: Int?
    public init(id:String,kind:String,name:String,summary:String,platforms:[String],source:String,file:String?,url:String?=nil,sha256:String?=nil,bytes:Int?=nil) {
        self.id=id;self.kind=kind;self.name=name;self.summary=summary;self.platforms=platforms;self.source=source;self.file=file
        self.url=url;self.sha256=sha256;self.bytes=bytes
    }
    public var key: String { kind + ":" + id }
    public var builtin: Bool { source == "builtin" }
    public var base: Bool { source == "base" }
}

public struct ExtensionCatalog: Codable, Sendable {
    public let version: Int
    public let items: [ExtensionItem]
    public static func parse(_ data: Data) throws -> ExtensionCatalog {
        let catalog = try JSONDecoder().decode(Self.self, from: data)
        guard catalog.version == 1, Set(catalog.items.map(\.key)).count == catalog.items.count,
              catalog.items.allSatisfy({ ExtensionStore.safeID($0.id) }) else { throw ExtensionError.invalid }
        return catalog
    }
}

public enum ExtensionError: LocalizedError {
    case invalid, required
    public var errorDescription: String? {
        switch self { case .invalid: return "扩展文件无效或不受支持"; case .required: return "基础主题不能卸载" }
    }
}

/// Presets and installed data have the same format on both platforms.
public final class ExtensionStore: ObservableObject {
    public static let shared = ExtensionStore()
    @Published public private(set) var revision = 0
    public let catalog: ExtensionCatalog
    public let root: URL
    private let market: URL
    private let styles: URL
    private var themeCache: [String: ThemeDefinition] = [:]
    private var localItems: [ExtensionItem] = []
    private var remoteItems: [ExtensionItem] = []
    public static var resourcesRoot: URL {
        let source = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        if let bundled = Bundle.main.resourceURL, FileManager.default.fileExists(atPath: bundled.appendingPathComponent("market/catalog.json").path) { return bundled }
        return source
    }
    public init(root: URL? = nil, resources: URL = ExtensionStore.resourcesRoot) {
        let user = ProcessInfo.processInfo.environment["WEAVETEXT_USER_DIR"].map { URL(fileURLWithPath: $0) }
            ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("WeaveText")
        self.root = root ?? user.appendingPathComponent("extensions")
        let bundled = resources.appendingPathComponent("market")
        market = FileManager.default.fileExists(atPath: bundled.path) ? bundled : resources.appendingPathComponent("data/market")
        let base = resources.appendingPathComponent("styles")
        styles = FileManager.default.fileExists(atPath: base.path) ? base : resources.appendingPathComponent("android/app/src/main/assets/styles")
        catalog = (try? ExtensionCatalog.parse(Data(contentsOf: market.appendingPathComponent("catalog.json")))) ?? ExtensionCatalog(version: 1, items: [])
        remoteItems=(try? OfficialMarket.parse(Data(contentsOf:self.root.appendingPathComponent("market-index.json")))) ?? []
        reloadLocalItems()
    }
    public var items: [ExtensionItem] {
        let base=catalog.items.filter{$0.platforms.contains("mac")}
        return base.map{item in remoteItems.first{$0.key==item.key} ?? item}+remoteItems.filter{remote in !base.contains{$0.key==remote.key}}+localItems
    }
    public func refreshMarket(fetcher:HTTPFetching=URLSessionFetcher(),progress:(@Sendable(FetchProgress)->Void)?=nil) async throws {
        let data=try await OfficialMarket.read(file:"catalog.json",fetcher:fetcher,progress:progress)
        let parsed=try OfficialMarket.parse(data)
        try Task.checkCancellation()
        try FileManager.default.createDirectory(at:root,withIntermediateDirectories:true)
        try data.write(to:root.appendingPathComponent("market-index.json"),options:.atomic)
        remoteItems=parsed;revision += 1
    }
    public func updateAvailable(_ item:ExtensionItem)->Bool {
        guard item.source=="remote",let sha=item.sha256,let file=try? destination(item),let data=try? Data(contentsOf:file) else {return false}
        return OfficialMarket.digest(data) != sha
    }
    public func installFromMarket(_ item:ExtensionItem,fetcher:HTTPFetching=URLSessionFetcher(),progress:(@Sendable(FetchProgress)->Void)?=nil) async throws {
        if item.source != "remote" {try install(item);return}
        guard item.kind=="theme",let file=item.file,let count=item.bytes,let sha=item.sha256 else {throw ExtensionError.invalid}
        let data=try await OfficialMarket.read(file:file,expectedBytes:count,sha:sha,fetcher:fetcher,progress:progress)
        _ = try ThemeDefinition.parse(data,expectedID:item.id)
        let target=try destination(item)
        try Task.checkCancellation()
        try FileManager.default.createDirectory(at:target.deletingLastPathComponent(),withIntermediateDirectories:true)
        try data.write(to:target,options:.atomic)
        themeCache.removeAll();reloadLocalItems();revision += 1
    }

    private func reloadLocalItems() {
        let files = (try? FileManager.default.contentsOfDirectory(at: root.appendingPathComponent("theme"), includingPropertiesForKeys:nil)) ?? []
        localItems = files.filter { $0.pathExtension == "json" }.compactMap { url in
            let id = url.deletingPathExtension().lastPathComponent
            guard Self.safeID(id), !catalog.items.contains(where:{$0.kind == "theme" && $0.id == id && $0.platforms.contains("mac")}),!remoteItems.contains(where:{$0.id==id}),
                  let data = try? Data(contentsOf:url), (try? ThemeDefinition.parse(data,expectedID:id)) != nil,
                  let json = try? JSONSerialization.jsonObject(with:data) as? [String:Any] else {return nil}
            return ExtensionItem(id:id,kind:"theme",name:String((json["name"] as? String ?? id).prefix(80)),summary:String((json["description"] as? String ?? "导入的主题").prefix(512)),platforms:["mac"],source:"local",file:nil)
        }
    }
    public var themes: [ColorTheme] {
        items.filter { $0.kind == "theme" && installed($0) }.compactMap { ColorTheme(rawValue: $0.id) }
    }
    public static func safeID(_ id: String) -> Bool { id.range(of: "^[a-z][a-z0-9_-]{0,63}$", options: .regularExpression) != nil }
    private func destination(_ item: ExtensionItem) throws -> URL {
        guard item.kind == "theme", Self.safeID(item.id) else { throw ExtensionError.invalid }
        return root.appendingPathComponent("theme/" + item.id + ".json")
    }
    public func installed(_ item: ExtensionItem) -> Bool {
        item.builtin || item.base || ((try? destination(item)).map { FileManager.default.fileExists(atPath: $0.path) } ?? false)
    }
    public func install(_ item: ExtensionItem) throws {
        guard item.source == "bundled", item.platforms.contains("mac"), item.file == "themes/theme-" + item.id + ".json" else { throw ExtensionError.invalid }
        let data = try Data(contentsOf: market.appendingPathComponent(item.file!))
        _ = try ThemeDefinition.parse(data, expectedID: item.id)
        let target = try destination(item)
        try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: target, options: .atomic)
        themeCache.removeAll()
        reloadLocalItems()
        revision += 1
    }
    @discardableResult public func importTheme(_ data:Data) throws -> ColorTheme {
        guard data.count <= 256 * 1024, let json = try JSONSerialization.jsonObject(with:data) as? [String:Any],
              let id = json["id"] as? String, let theme = ColorTheme(rawValue:id), id == theme.rawValue,
              !ColorTheme.allCases.contains(theme) else {throw ExtensionError.invalid}
        _ = try ThemeDefinition.parse(data,expectedID:id)
        let directory = root.appendingPathComponent("theme")
        try FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true)
        try data.write(to:directory.appendingPathComponent(id+".json"),options:.atomic)
        themeCache.removeAll();reloadLocalItems();revision += 1
        return theme
    }
    public func uninstall(_ item: ExtensionItem, prefs: Preferences) throws {
        guard !item.base, !item.builtin else { throw ExtensionError.required }
        let url = try destination(item)
        if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
        if prefs.colorTheme.rawValue == item.id { prefs.colorTheme = .fresh }
        themeCache.removeAll()
        reloadLocalItems()
        revision += 1
    }
    public func theme(_ id: String) -> ThemeDefinition? {
        guard Self.safeID(id) else { return nil }
        if let cached = themeCache[id] { return cached }
        let file = ColorTheme.allCases.contains(where: { $0.rawValue == id }) ? styles.appendingPathComponent("theme-\(id).json") : root.appendingPathComponent("theme/\(id).json")
        let definition = (try? Data(contentsOf: file)).flatMap { try? ThemeDefinition.parse($0, expectedID: id) }
        themeCache[id] = definition
        return definition
    }
    public func name(_ id: String) -> String { items.first { $0.kind == "theme" && $0.id == id }?.name ?? id }
    public func preview(_ item: ExtensionItem) -> ThemeDefinition? {
        if item.kind == "theme" && installed(item) { return theme(item.id) }
        guard item.kind == "theme", item.file == "themes/theme-" + item.id + ".json" else { return nil }
        return (try? Data(contentsOf: market.appendingPathComponent(item.file!))).flatMap { try? ThemeDefinition.parse($0, expectedID: item.id) }
    }
}

public struct ThemeDefinition {
    public let light: [String: Any]
    public let dark: [String: Any]
    public static func parse(_ data: Data, expectedID: String) throws -> ThemeDefinition {
        guard data.count <= 256 * 1024,
              let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              json["version"] as? Int == 1, json["kind"] as? String == "theme", json["id"] as? String == expectedID,
              let light = json["light"] as? [String: Any], let dark = json["dark"] as? [String: Any] else { throw ExtensionError.invalid }
        let definition = ThemeDefinition(light: light, dark: dark)
        guard [light, dark].allSatisfy({ $0["accent"] as? String != nil && Self.color($0["accent"]) != nil }) else { throw ExtensionError.invalid }
        return definition
    }
    private static func color(_ value: Any?) -> UInt32? {
        if let gradient = value as? [String: Any], let colors = gradient["colors"] as? [String] { return color(colors.first) }
        guard let hex = value as? String, hex.hasPrefix("#"), hex.count == 7 else { return nil }
        return UInt32(hex.dropFirst(), radix: 16)
    }
    public func colors(_ key: String, fallback: (UInt32, UInt32)) -> (UInt32, UInt32) {
        (Self.color(light[key]) ?? fallback.0, Self.color(dark[key]) ?? fallback.1)
    }
}
