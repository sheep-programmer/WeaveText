import Foundation
import Testing
@testable import WeaveCore

private let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../../..").standardized
private let modelCatalog = root.appendingPathComponent("android/app/src/main/assets/models/catalog.json")

@Suite struct MirrorsTests {
    private let raw = URL(string: "https://raw.githubusercontent.com/o/r/dist/hotwords.tsv")!

    /// 与 Android 同一份镜像表。 The same mirror list as Android's.
    @Test func parsesAndroidsCatalog() throws {
        let m = Mirrors.parse(try Data(contentsOf: modelCatalog))
        #expect(m.templates.count >= 3)
        #expect(m.templates.allSatisfy { $0.contains("{url}") })
        let sources = m.sources(for: raw)
        // 直连在最前，且只出现一次（目录里第一个模板就是 {url}）。 Direct first, once (the catalog lists {url} too).
        #expect(sources.first == raw)
        #expect(sources.filter { $0 == raw }.count == 1)
        #expect(sources.count == Set(sources).count)
        #expect(sources.dropFirst().allSatisfy { $0.absoluteString.hasSuffix(raw.absoluteString) })
    }

    @Test func acceptsTheBareArrayTheBuildShips() {
        let json = #"[{"id":"a","template":"https://a.invalid/{url}"},{"id":"bad","template":"https://b.invalid/"},{"id":"c"}]"#
        let m = Mirrors.parse(Data(json.utf8))
        #expect(m.templates == ["https://a.invalid/{url}"])
        #expect(m.sources(for: raw).map(\.absoluteString) == [raw.absoluteString, "https://a.invalid/" + raw.absoluteString])
    }

    @Test func emptyOrBrokenMeansDirectOnly() {
        #expect(Mirrors.parse(Data("nope".utf8)) == Mirrors())
        #expect(Mirrors().sources(for: raw) == [raw])
    }
}
