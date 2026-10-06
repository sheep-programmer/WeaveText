import Foundation
import Testing
@testable import WeaveCore

@Suite struct ExpressionCatalogTests {
    @Test func theSharedCatalogHasNamesAndNoDuplicateEntries() throws {
        let url=URL(fileURLWithPath:#filePath).deletingLastPathComponent().appendingPathComponent("../../../data/expressions/catalog.json").standardized
        let catalog=try ExpressionCatalog.load(url)
        #expect(catalog.emoji.count==1898 && catalog.kaomoji.count==132)
        #expect(Set(catalog.emoji.map(\.text)).count==catalog.emoji.count)
        #expect(Set(catalog.kaomoji.map(\.text)).count==catalog.kaomoji.count)
        #expect((catalog.emoji+catalog.kaomoji).allSatisfy{!$0.name.isEmpty && !$0.text.isEmpty})
        #expect(catalog.emoji.first{$0.text=="😀"}?.name=="嘿嘿")
        #expect(catalog.kaomoji.first{$0.text=="(＾▽＾)"}?.name=="开心笑")
    }
}
