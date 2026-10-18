import Foundation

/// 表情名称搜索（离线，与 Android 端 [ExpressionSearch] 同一套规则）。
/// Offline name search over emoji and kaomoji, with the same rules as the Android build's ExpressionSearch.
///
/// 关键词按空格拆开，每个词都要命中；命中范围是中文名、英文名、两字以上的分类与名字的拼音（无声调：weixiao、
/// wei'xiao、weix 都能找到「微笑」）。拆开的词连起来也算一个词。打分：直接打出表情本身 > 名字等于关键词 >
/// 名字或拼音音节以关键词开头 > 名字里某个词以关键词开头 > 包含（含拼音中间部分）> 只有英文名或分类命中；
/// 同档按名字长短、再按目录顺序。
/// The query is split on spaces and every term has to match against the Chinese name, the English name, groups of
/// two or more characters, or the name's pinyin (toneless). Ranking bands match Android so both platforms order the
/// same query the same way.
public enum ExpressionSearch {
    /// 一条可搜索的表情。 A searchable expression.
    public struct Row: Equatable, Sendable {
        public let text:String
        public let name:String
        public let english:String
        public let group:String
        public let pinyin:String
        public init(text:String,name:String,english:String,group:String,pinyin:String) {
            self.text=text;self.name=name;self.english=english;self.group=group;self.pinyin=pinyin
        }
        public init(_ entry:NamedExpression) {
            self.init(text:entry.text,name:entry.name,english:entry.english,group:entry.group,pinyin:entry.pinyin)
        }
    }

    /// 预建索引：目录加载后建一次，之后每次输入只走匹配。 Index built once; each keystroke only matches.
    public struct Index: Sendable {
        private struct Entry: Sendable {
            let row:Row
            let name:String
            let english:String
            let group:String
            let pinyin:String
            let joined:String
            let starts:[Int]
        }

        public let rows:[Row]
        private let entries:[Entry]

        public init(rows:[Row]) {
            self.rows=rows
            entries=rows.map { row in
                let pinyin=row.pinyin.lowercased()
                var starts:[Int]=[], at=0
                for part in pinyin.split(separator:" ",omittingEmptySubsequences:true) {
                    starts.append(at);at+=part.count
                }
                return Entry(row:row,name:fold(row.name),english:fold(row.english),group:fold(row.group),
                             pinyin:pinyin,joined:pinyin.replacingOccurrences(of:" ",with:""),starts:starts)
            }
        }

        /// 查询为空时原样返回（保持目录顺序）。 An empty query keeps everything in catalogue order.
        public func rank(_ query:String)->[Row] {
            let terms=ExpressionSearch.terms(query)
            if terms.isEmpty {return rows}
            let trimmed=query.trimmingCharacters(in:.whitespacesAndNewlines)
                .replacingOccurrences(of:"\\s+",with:" ",options:.regularExpression)
            let joinedTerm=terms.joined()
            var hits:[(Int,Int,Row)]=[]
            for (i,e) in entries.enumerated() {
                if let score=score(e,terms:terms,trimmed:trimmed,joinedTerm:joinedTerm) {hits.append((score,i,e.row))}
            }
            return hits.sorted { $0.0==$1.0 ? $0.1<$1.1 : $0.0<$1.0 }.map(\.2)
        }

        /// 名字或拼音以关键词开头的条数。 Names or pinyin starting with the query.
        public func prefixHits(_ query:String)->Int {
            // 先折叠再清洗，和 Android 一致：WEI 与 wei 命中数相同。
            // Fold before cleaning, as Android does: WEI and wei must hit the same entries.
            let q=clean(fold(query.trimmingCharacters(in:.whitespacesAndNewlines)))
            if q.isEmpty {return 0}
            // 名字与拼音已预先折叠。 Names and pinyin are already folded.
            return entries.filter {$0.name.hasPrefix(q) || $0.pinyin.hasPrefix(q) || $0.joined.hasPrefix(q)}.count
        }

        private func hit(_ e:Entry,_ term:String)->Bool {
            if e.name.contains(term) || e.english.contains(term) {return true}
            if e.group.count>=2 && e.group.contains(term) {return true}
            let p=clean(term)
            return !p.isEmpty && e.joined.contains(p)
        }

        private func score(_ e:Entry,terms:[String],trimmed:String,joinedTerm:String)->Int? {
            if !trimmed.isEmpty && e.row.text==trimmed {return -1}
            guard terms.allSatisfy({hit(e,$0)}) || hit(e,joinedTerm) else {return nil}
            let q=hit(e,terms[0]) ? terms[0] : joinedTerm
            let qJoined=clean(q)
            let band:Int
            if e.name==trimmed {band=0}
            else if e.name.hasPrefix(q) {band=1}
            else if e.starts.contains(where:{prefix(e.joined,from:$0,qJoined)}) {band=2}
            else if e.name.split(whereSeparator:{" ·，,、".contains($0)}).contains(where:{$0.trimmingCharacters(in:.whitespaces).hasPrefix(q)}) {band=3}
            else if e.name.contains(q) || e.joined.contains(qJoined) {band=4}
            else {band=5}
            return band*10_000+min(e.name.count,9_999)
        }
    }

    /// 查询词：空格分开。 Query terms, split on spaces.
    public static func terms(_ query:String)->[String] {
        query.trimmingCharacters(in:.whitespacesAndNewlines)
            .split(whereSeparator:\.isWhitespace).map{fold(String($0))}
    }

    /// 中文不需要大小写折叠；拉丁部分统一小写。 Chinese keeps its case; Latin parts are lower-cased.
    static func fold(_ s:String)->String {s.lowercased()}

    /// [joined] 从 [offset] 起是否以 [term] 开头。 Whether [joined] starts with [term] at [offset].
    static func prefix(_ joined:String,from offset:Int,_ term:String)->Bool {
        guard offset>=0,offset<=joined.count else {return false}
        return String(joined.dropFirst(offset)).hasPrefix(term)
    }

    /// 拼音比较前去掉隔音符与空白。 Syllable dividers and spaces are dropped before pinyin comparison.
    static func clean(_ s:String)->String {
        s.replacingOccurrences(of:" ",with:"").replacingOccurrences(of:"'",with:"").replacingOccurrences(of:"’",with:"")
    }
}
