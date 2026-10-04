import Foundation
import Testing
@testable import WeaveText

/// 源码里的 Info.plist 与两份 InfoPlist.strings：输入法在任何系统语言下都能按「织文」找到。
/// The source Info.plist and both InfoPlist.strings: the input method can be found as 织文 in any system language.
@Suite struct InfoPlistTests {
    private static let resources = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        .appendingPathComponent("Resources")

    private func strings(_ lproj: String) throws -> [String: String] {
        let url = Self.resources.appendingPathComponent("\(lproj).lproj/InfoPlist.strings")
        return try #require(NSDictionary(contentsOf: url) as? [String: String], "\(lproj)")
    }

    private func info() throws -> [String: Any] {
        let data = try Data(contentsOf: Self.resources.appendingPathComponent("Info.plist"))
        return try #require(try PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any])
    }

    @Test func englishNamesStillSay织文() throws {
        let en = try strings("en")
        #expect(en[Registration.modeID] == "织文拼音 WeaveText")
        #expect(en[Registration.bundleID] == "织文输入法 WeaveText")
    }

    @Test func chineseNames() throws {
        let zh = try strings("zh-Hans")
        #expect(zh[Registration.modeID] == "织文拼音")
        #expect(zh[Registration.bundleID] == "织文输入法")
    }

    @Test func bothLanguagesNameTheSameKeys() throws {
        #expect(Set(try strings("en").keys) == Set(try strings("zh-Hans").keys))
    }

    @Test func voicePermissionsAreExplainedInBothLanguages() throws {
        let info = try info()
        for key in ["NSMicrophoneUsageDescription", "NSSpeechRecognitionUsageDescription"] {
            #expect((info[key] as? String)?.isEmpty == false)
            #expect(try strings("en")[key]?.isEmpty == false)
            #expect(try strings("zh-Hans")[key]?.isEmpty == false)
        }
    }

    @Test func developmentRegionAndModeIDs() throws {
        let info = try info()
        #expect(info["CFBundleDevelopmentRegion"] as? String == "zh-Hans")
        #expect(info["CFBundleIdentifier"] as? String == Registration.bundleID)
        let modes = (info["ComponentInputModeDict"] as? [String: Any])?["tsInputModeListKey"] as? [String: Any]
        #expect(modes?.keys.contains(Registration.modeID) == true)
    }
}
