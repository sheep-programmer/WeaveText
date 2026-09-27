import Testing
@testable import WeaveCore

private func source(_ n: Int) -> Pager.Fetch {
    let all = (0..<n).map { Candidate(text: "c\($0)") }
    return { offset, limit in Array(all.dropFirst(offset).prefix(limit)) }
}

@Suite struct PagerTests {
    @Test func pagesForwardAndBack() {
        let f = source(12)
        var p = Pager(pageSize: 5)
        p.reset(total: 12, pageSize: 5, fetch: f)
        #expect(p.page.map(\.text) == ["c0", "c1", "c2", "c3", "c4"])
        #expect(!p.hasPrevious && p.hasNext)
        let r525 = p.next(fetch: f); #expect(r525)
        #expect(p.offset == 5 && p.pageNumber == 2)
        let r611 = p.next(fetch: f); #expect(r611)
        #expect(p.page.map(\.text) == ["c10", "c11"])
        #expect(!p.hasNext)
        let r727 = p.next(fetch: f); #expect(!r727)
        let r762 = p.previous(fetch: f); #expect(r762)
        #expect(p.offset == 5)
    }

    @Test func aCappedTotalStopsWhereTheFetchRunsDry() {
        let f = source(10)
        var p = Pager(pageSize: 5)
        p.reset(total: 999, pageSize: 5, fetch: f)
        let r1008 = p.next(fetch: f); #expect(r1008)
        #expect(p.hasNext)
        let r1069 = p.next(fetch: f); #expect(!r1069)
        #expect(p.offset == 5 && !p.hasNext)
    }

    @Test func highlightWrapsAcrossPages() {
        let f = source(8)
        var p = Pager(pageSize: 5)
        p.reset(total: 8, pageSize: 5, fetch: f)
        for _ in 0..<4 { p.highlightNext(fetch: f) }
        #expect(p.highlightedIndex == 4)
        p.highlightNext(fetch: f)
        #expect(p.offset == 5 && p.highlight == 0 && p.highlightedIndex == 5)
        p.highlightPrevious(fetch: f)
        #expect(p.offset == 0 && p.highlight == 4)
        p.highlightPrevious(fetch: f)
        #expect(p.highlight == 3)
        for _ in 0..<10 { p.highlightPrevious(fetch: f) }
        #expect(p.highlightedIndex == 0)
    }

    @Test func emptyInput() {
        var p = Pager(pageSize: 5)
        p.reset(total: 0, pageSize: 5, fetch: source(0))
        #expect(p.page.isEmpty && !p.hasNext && !p.hasPrevious)
    }
}
