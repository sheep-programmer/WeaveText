/// 候选分页与高亮。 Candidate paging and highlight.
///
/// 内核报的总数被截断时只是上限，所以往后翻以实际取到的为准。
/// The engine's total is only a cap when cut, so paging forward trusts what a fetch actually returns.
public struct Pager: Equatable, Sendable {
    public private(set) var pageSize: Int
    /// 当前页首个候选的全局序号。 Global index of the page's first candidate.
    public private(set) var offset = 0
    public private(set) var page: [Candidate] = []
    /// 页内高亮位置。 Highlight within the page.
    public private(set) var highlight = 0
    public private(set) var total = 0

    public init(pageSize: Int) {
        self.pageSize = max(1, pageSize)
    }

    public typealias Fetch = (_ offset: Int, _ limit: Int) -> [Candidate]

    /// 新的一次输入：回到第一页。 New input: back to page one.
    public mutating func reset(total: Int, pageSize: Int, fetch: Fetch) {
        self.pageSize = max(1, pageSize)
        self.total = total
        offset = 0
        highlight = 0
        page = total > 0 ? fetch(0, self.pageSize) : []
    }

    public var hasPrevious: Bool { offset > 0 }
    public var hasNext: Bool { offset + page.count < total && page.count == pageSize }
    public var highlightedIndex: Int { offset + highlight }
    public var pageNumber: Int { offset / pageSize + 1 }

    @discardableResult
    public mutating func next(fetch: Fetch) -> Bool {
        guard hasNext else { return false }
        let more = fetch(offset + pageSize, pageSize)
        guard !more.isEmpty else {
            // 截断的总数：到头了。 A capped total: this was the last page.
            total = offset + page.count
            return false
        }
        offset += pageSize
        page = more
        highlight = 0
        return true
    }

    @discardableResult
    public mutating func previous(fetch: Fetch) -> Bool {
        guard hasPrevious else { return false }
        offset = max(0, offset - pageSize)
        page = fetch(offset, pageSize)
        highlight = 0
        return true
    }

    /// 高亮后移，越过页尾翻到下一页。 Move the highlight on, turning the page past the end.
    public mutating func highlightNext(fetch: Fetch) {
        if highlight + 1 < page.count {
            highlight += 1
        } else {
            next(fetch: fetch)
        }
    }

    /// 高亮前移，越过页首翻到上一页末尾。 Move the highlight back, to the previous page's end past the start.
    public mutating func highlightPrevious(fetch: Fetch) {
        if highlight > 0 {
            highlight -= 1
        } else if previous(fetch: fetch) {
            highlight = max(0, page.count - 1)
        }
    }
}
