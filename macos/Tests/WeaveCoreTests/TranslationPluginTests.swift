import Foundation
import Testing
@testable import WeaveCore

private func translationPluginFixture(_ root: URL, engine: String = "apple-system", minimumOS: String = "15",
                                      kind: String = "translation", entry: String = "main.lua", version: String = "1.0.0") throws -> URL {
    let directory = root.appendingPathComponent("source-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    let manifest = """
    id: com.weavetext.translation.apple
    name: Apple 离线翻译
    description: 适配描述；语言包由 macOS 管理。
    version: \(version)
    type: \(kind)
    entry: \(entry)
    engine: \(engine)
    min_os_version: \(minimumOS)
    permissions: []
    """
    try manifest.write(to: directory.appendingPathComponent("manifest.yaml"), atomically: true, encoding: .utf8)
    try "return { describe = function() return {engine='apple-system',offline=true,modelManager='macos-system'} end }"
        .write(to: directory.appendingPathComponent("main.lua"), atomically: true, encoding: .utf8)
    return directory
}

private func translationPluginTemp() throws -> URL {
    let root = FileManager.default.temporaryDirectory.appendingPathComponent("weave-translation-plugin-test-\(UUID().uuidString)", isDirectory: true)
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    return root
}

@Suite struct TranslationPluginPackageTests {
    @Test func contentBasedZIPRawManifestAndDirectoryImportsUseOnlyTheIndependentPool() throws {
        let root = try translationPluginTemp()
        defer { try? FileManager.default.removeItem(at: root) }
        let source = try translationPluginFixture(root)
        let archive = root.appendingPathComponent("apple-without-zip-extension")
        try PluginHost.package(source: source, output: archive)
        let voice = root.appendingPathComponent("plugins/voice-marker")
        try FileManager.default.createDirectory(at: voice.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data("keep existing voice plugins".utf8).write(to: voice)
        let repository = FileTranslationPluginRepository(directory: root.appendingPathComponent("translation-plugins"))
        #expect(try repository.installed() == nil)
        for input in [archive, source, source.appendingPathComponent("manifest.yaml")] {
            let descriptor = try repository.install(input)
            #expect(descriptor.id == TranslationPluginDescriptor.appleID && descriptor.engine == "apple-system")
            #expect(descriptor.version == "1.0.0" && descriptor.minimumOS == TranslationPluginOS(major: 15))
            #expect(try repository.installed() == descriptor)
        }
        try repository.uninstall()
        #expect(try repository.installed() == nil)
        #expect(try Data(contentsOf: voice) == Data("keep existing voice plugins".utf8))
    }

    @Test func invalidCandidateCannotOverwriteAValidatedInstalledDescriptor() throws {
        let root = try translationPluginTemp()
        defer { try? FileManager.default.removeItem(at: root) }
        let good = try translationPluginFixture(root)
        let repository = FileTranslationPluginRepository(directory: root.appendingPathComponent("translation-plugins"))
        let original = try repository.install(good)
        for source in [try translationPluginFixture(root, engine: "unknown"),
                       try translationPluginFixture(root, minimumOS: "14"),
                       try translationPluginFixture(root, kind: "speech")] {
            #expect(throws: (any Error).self) { try repository.install(source) }
            #expect(try repository.installed() == original)
        }
        let traversal = try translationPluginFixture(root, entry: "../main.lua")
        #expect(throws: (any Error).self) { try repository.install(traversal) }
        #expect(try repository.installed() == original)
        let oversized = root.appendingPathComponent("large-package")
        try Data(repeating: 32, count: FileTranslationPluginRepository.maxPackageBytes + 1).write(to: oversized)
        #expect(throws: TranslationPluginFailure.tooLarge) { try repository.install(oversized) }
        #expect(try repository.installed() == original)
    }
}

@MainActor @Suite struct TranslationPluginStateTests {
    @Test func manualInstallEnableDisableUninstallAndMinimumOSAreIndependent() async throws {
        let root = try translationPluginTemp()
        let suite = "weave-translation-plugin-defaults-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { try? FileManager.default.removeItem(at: root); defaults.removePersistentDomain(forName: suite) }
        defaults.set(["voice-a"], forKey: "voiceEngines")
        let source = try translationPluginFixture(root)
        let repository = FileTranslationPluginRepository(directory: root.appendingPathComponent("translation-plugins"))
        let plugins = TranslationPlugins(repository: repository, defaults: defaults, operatingSystem: .init(major: 27))
        #expect(plugins.apple.state == .notInstalled && !plugins.appleReady)
        await plugins.install(source)
        #expect(plugins.apple.state == .installed && !plugins.appleReady)
        try plugins.setEnabled(true)
        #expect(plugins.apple.state == .enabled && plugins.appleReady)
        let oldMac = TranslationPlugins(repository: repository, defaults: defaults, operatingSystem: .init(major: 14))
        #expect(oldMac.apple.state == .unsupported(minimumOS: "15.0") && !oldMac.appleReady)
        #expect(throws: TranslationPluginFailure.unsupported) { try oldMac.setEnabled(true) }
        try plugins.setEnabled(false)
        #expect(plugins.apple.state == .installed && !plugins.appleReady)
        await plugins.uninstall()
        #expect(plugins.apple.state == .notInstalled && !plugins.appleReady)
        #expect(defaults.object(forKey: TranslationPlugins.enabledKey) == nil)
        #expect(defaults.stringArray(forKey: "voiceEngines") == ["voice-a"])
    }
}
