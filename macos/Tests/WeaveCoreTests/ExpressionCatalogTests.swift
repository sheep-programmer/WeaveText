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
        // 中英文名与拼音都齐：离线按英文或拼音都能搜到。 Every entry carries both names and pinyin.
        #expect(catalog.emoji.allSatisfy{!$0.english.isEmpty})
        #expect((catalog.emoji+catalog.kaomoji).allSatisfy{!$0.pinyin.isEmpty})
        #expect(catalog.emoji.first{$0.text=="🐶"}?.pinyin=="gou lian")
    }

    @Test func searchRanksNamesPinyinAndEnglish() throws {
        let url=URL(fileURLWithPath:#filePath).deletingLastPathComponent().appendingPathComponent("../../../data/expressions/catalog.json").standardized
        let catalog=try ExpressionCatalog.load(url)
        let index=ExpressionSearch.Index(rows:catalog.emoji.map(ExpressionSearch.Row.init))
        func names(_ q:String)->[String] {index.rank(q).map(\.name)}
        // 与 Android 端同一套档位：打全拼、音节前缀、隔音符都能找到「微笑」，且以关键词开头的名字在前。
        #expect(names("weixiao").first=="微笑")
        #expect(names("weix").first=="微笑")
        #expect(names("wei'xiao").first=="微笑")
        #expect(names("dog").contains{ $0.contains("狗") })
        #expect(names("动物").contains("猫脸"))
        #expect(names("😀").first=="嘿嘿")
        #expect(names("zzzz").isEmpty)
        #expect(index.rank("   ").count==catalog.emoji.count)
        #expect(index.prefixHits("wei") >= 1)
    }
}
