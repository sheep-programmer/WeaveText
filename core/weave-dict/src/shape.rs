//! 字形表：辅助码筛选用的笔画序列与部件读音。 Shape table: stroke orders and component readings for the shape filter.

use std::collections::BTreeMap;
use std::io;

use crate::blob::{Blob, Source};

const HEADER: usize = 16;

/// 字形表：每个字的笔画序列（h 横 s 竖 p 撇 n 点 z 折）与若干种拆分（部件的拼音，如 时 = ri'cun）。
/// Shape table: each char's stroke order (h s p n z) and a few splits into components spelled in pinyin
/// (时 = ri'cun).
///
/// 布局 / Layout: `"WVSH" | version u32 = 1 | n u32 | pad`，`Index [(code point u32, offset u32); n]`，`end u32`，
/// 记录 / record: `strokes_len u8 | strokes | count u8 | (len u8 | parts joined by ') × count`，按码位升序。
#[derive(Default)]
pub struct ShapeBuilder {
    rows: BTreeMap<char, (String, Vec<String>)>,
}

impl ShapeBuilder {
    /// 记录一个字；同一个字多次加入时合并。 Add a char; repeated chars are merged.
    pub fn add(&mut self, ch: char, strokes: Option<&str>, split: Option<&str>) {
        let row = self.rows.entry(ch).or_default();
        if let Some(s) = strokes {
            row.0 = s.to_ascii_lowercase();
        }
        if let Some(s) = split {
            if !row.1.iter().any(|x| x == s) {
                row.1.push(s.to_string());
            }
        }
    }

    pub fn build(self) -> Vec<u8> {
        let n = self.rows.len();
        let base = HEADER + n * 8 + 4;
        let mut index = Vec::with_capacity(n * 8 + 4);
        let mut data = Vec::new();
        for (ch, (strokes, splits)) in &self.rows {
            index.extend_from_slice(&(*ch as u32).to_le_bytes());
            index.extend_from_slice(&((base + data.len()) as u32).to_le_bytes());
            let s = &strokes.as_bytes()[..strokes.len().min(255)];
            data.push(s.len() as u8);
            data.extend_from_slice(s);
            let splits: Vec<&String> = splits.iter().filter(|s| s.len() <= 255).take(255).collect();
            data.push(splits.len() as u8);
            for s in splits {
                data.push(s.len() as u8);
                data.extend_from_slice(s.as_bytes());
            }
        }
        index.extend_from_slice(&((base + data.len()) as u32).to_le_bytes());
        let mut out = Vec::with_capacity(base + data.len());
        out.extend_from_slice(b"WVSH");
        out.extend_from_slice(&1u32.to_le_bytes());
        out.extend_from_slice(&(n as u32).to_le_bytes());
        out.extend_from_slice(&0u32.to_le_bytes());
        out.extend_from_slice(&index);
        out.extend_from_slice(&data);
        out
    }
}

/// 只读字形表。 Read-only shape table.
pub struct Shapes {
    blob: Blob,
    n: usize,
}

impl Shapes {
    pub fn open_source(src: &Source) -> io::Result<Shapes> {
        Self::parse(Blob::open(src)?)
    }

    pub fn from_bytes(b: Vec<u8>) -> io::Result<Shapes> {
        Self::parse(Blob::from_bytes(b))
    }

    fn parse(blob: Blob) -> io::Result<Shapes> {
        let bad = || io::Error::new(io::ErrorKind::InvalidData, "bad shape table");
        if blob.len() < HEADER || !blob.with(0, 4, |h| h == b"WVSH") || blob.u32(4) != 1 {
            return Err(bad());
        }
        let n = blob.u32(8) as usize;
        if blob.len() < HEADER + n * 8 + 4 {
            return Err(bad());
        }
        Ok(Shapes { blob, n })
    }

    /// 整个表读进内存（拆字查字要逐条扫描，不能每次都解压）。 The whole table in memory, for full scans.
    pub fn into_owned(self) -> Shapes {
        if !self.blob.is_packed() {
            return self;
        }
        Shapes { blob: Blob::from_bytes(self.blob.to_vec()), n: self.n }
    }

    pub fn is_packed(&self) -> bool {
        self.blob.is_packed()
    }

    /// 第 `i` 条记录的字节。 The bytes of record `i`.
    fn record_at<R>(&self, i: usize, f: impl FnOnce(&[u8]) -> R) -> Option<R> {
        let at = self.blob.u32(HEADER + i * 8 + 4) as usize;
        // 下一条记录的起点；最后一条之后是结尾偏移。 The next record's start; the end offset after the last one.
        let end = if i + 1 == self.n { HEADER + self.n * 8 } else { HEADER + (i + 1) * 8 + 4 };
        let end = self.blob.u32(end) as usize;
        if at >= end || end > self.blob.len() {
            return None;
        }
        Some(self.blob.with(at, end - at, f))
    }

    /// 某字的记录字节。 The record bytes of a char.
    fn record<R>(&self, ch: char, f: impl FnOnce(&[u8]) -> R) -> Option<R> {
        let target = ch as u32;
        let (mut lo, mut hi) = (0usize, self.n);
        while lo < hi {
            let mid = (lo + hi) / 2;
            if self.blob.u32(HEADER + mid * 8) < target {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        if lo >= self.n || self.blob.u32(HEADER + lo * 8) != target {
            return None;
        }
        self.record_at(lo, f)
    }

    /// 某字的笔画序列与拆分（拆分里的部件以 `'` 分开）。 A char's strokes and splits (parts separated by `'`).
    pub fn get(&self, ch: char) -> Option<(String, Vec<String>)> {
        self.record(ch, |b| {
            let mut i = 0;
            let take = |i: &mut usize| -> Option<String> {
                let l = *b.get(*i)? as usize;
                let s = std::str::from_utf8(b.get(*i + 1..*i + 1 + l)?).ok()?.to_string();
                *i += 1 + l;
                Some(s)
            };
            let strokes = take(&mut i).unwrap_or_default();
            let count = b.get(i).copied().unwrap_or(0);
            i += 1;
            let splits = (0..count).map_while(|_| take(&mut i)).collect();
            (strokes, splits)
        })
    }

    /// 字形码是否对得上这个字：只含 hspnz 时可以是笔画序列的开头；也可以是某种拆分里从第一个部件起、
    /// 每个部件各取读音开头若干个字母连起来（时 = ri'cun：r、rc、ric、ricun 都算）。含大写字母的码只按笔画比
    /// （笔画键发的是 HSPNZ：点 N 不该因为 世 = 廿乚 的 nian 也算对上）。
    /// Whether a shape code fits the char: a prefix of its stroke order (codes of h s p n z only), or, for some
    /// split, a run of non-empty reading prefixes of consecutive parts from the first (时 = ri'cun: r, rc, ric, ricun).
    /// A code with capitals compares strokes only: stroke keys send HSPNZ, and a dot N must not fit 世 by its nian.
    pub fn matches(&self, ch: char, code: &str) -> bool {
        if code.is_empty() {
            return true;
        }
        let (lower, strokes_only) = (code.to_ascii_lowercase(), code.bytes().any(|b| b.is_ascii_uppercase()));
        self.record(ch, |b| rank(lower.as_bytes(), strokes_only, false, b)).flatten().is_some()
    }

    /// 拆字查字：对得上 `code`（小写，规则同 [`Shapes::matches`]，但不看只有一个部件的拆分）的每个字及贴合程度，
    /// 按码位顺序。0 = 打全了笔画或每个部件的读音（品 = koukoukou），1 = 笔画或读音连起来的开头（kouk、hs），
    /// 2 = 每个部件都打到了（kkk），3 = 其他。逐条扫描整个表；分块压缩的来源先用 [`Shapes::into_owned`]。
    /// Look chars up by shape: every char fitting `code` (lowercase, rules as in [`Shapes::matches`] but skipping
    /// one-part splits) with how closely, in code point order. 0 = all strokes or every part's full reading
    /// (品 = koukoukou), 1 = a start of the strokes or of the readings run together (kouk, hs), 2 = every part reached
    /// (kkk), 3 = the rest. Scans the whole table; use [`Shapes::into_owned`] on packed sources first.
    pub fn search(&self, code: &str, mut f: impl FnMut(char, u8)) {
        if code.is_empty() {
            return;
        }
        let code = code.as_bytes();
        for i in 0..self.n {
            let Some(ch) = char::from_u32(self.blob.u32(HEADER + i * 8)) else { continue };
            if let Some(Some(tier)) = self.record_at(i, |b| rank(code, false, true, b)) {
                f(ch, tier);
            }
        }
    }
}

/// 一条记录对字形码（小写）的贴合程度，见 [`Shapes::search`]；对不上为 None。查字时不看只有一个部件的拆分
/// （梁 = mu 只是辅助码，不是拆字）。
/// How closely a record fits a lowercase shape code, see [`Shapes::search`]; None when it does not. Lookups skip
/// one-part splits (梁 = mu is an aux code, not a breakdown).
fn rank(code: &[u8], strokes_only: bool, lookup: bool, rec: &[u8]) -> Option<u8> {
    let (&sl, rest) = rec.split_first()?;
    let strokes = rest.get(..sl as usize)?;
    let mut best = None;
    if code.iter().all(|b| b"hspnz".contains(b)) && strokes.starts_with(code) {
        best = Some(if strokes.len() == code.len() { 0 } else { 1 });
    }
    if strokes_only || best == Some(0) {
        return best;
    }
    let mut i = 1 + sl as usize;
    let count = *rec.get(i)?;
    i += 1;
    for _ in 0..count {
        let l = *rec.get(i)? as usize;
        let split = rec.get(i + 1..i + 1 + l)?;
        i += 1 + l;
        let mut parts: [&[u8]; 8] = [&[]; 8];
        let mut n = 0;
        for p in split.split(|&b| b == b'\'').take(8) {
            parts[n] = p;
            n += 1;
        }
        if lookup && n < 2 {
            continue;
        }
        if let Some(t) = fits(code, &parts[..n], true, true) {
            best = Some(best.map_or(t, |b: u8| b.min(t)));
        }
    }
    best
}

/// 从第一个部件起，每个部件取读音开头若干个字母，能否连成 `code`；能的话给出贴合程度（见 [`Shapes::search`]）。
/// `before`：之前的部件都打全了；`last`：刚用到的部件打全了。
/// Whether `code` is a run of non-empty reading prefixes of consecutive parts from the first, and how closely.
/// `before`: the parts before the latest were spelled out; `last`: the latest one was.
fn fits(code: &[u8], parts: &[&[u8]], before: bool, last: bool) -> Option<u8> {
    if code.is_empty() {
        return Some(match (parts.is_empty(), before) {
            (true, true) if last => 0,
            (_, true) => 1,
            (true, false) => 2,
            _ => 3,
        });
    }
    let (first, rest) = parts.split_first()?;
    (1..=code.len().min(first.len()))
        .rev()
        .filter(|&k| code[..k] == first[..k])
        .filter_map(|k| fits(&code[k..], rest, before && last, k == first.len()))
        .min()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn table() -> Shapes {
        let mut b = ShapeBuilder::default();
        b.add('时', Some("SZHHHSN"), Some("ri'cun"));
        b.add('是', Some("SZHHHSHPN"), Some("ri'pi"));
        b.add('是', None, Some("yue'pi"));
        b.add('事', Some("HSZHZHHS"), Some("heng'kou'xue"));
        Shapes::from_bytes(b.build()).unwrap()
    }

    #[test]
    fn codes_match_stroke_prefixes_and_component_readings() {
        let t = table();
        assert_eq!(t.get('是').unwrap(), ("szhhhshpn".to_string(), vec!["ri'pi".to_string(), "yue'pi".to_string()]));
        for code in ["r", "rc", "ric", "ricun", "sz", "szhhh"] {
            assert!(t.matches('时', code), "{code}");
        }
        for code in ["c", "rcx", "riu", "zs"] {
            assert!(!t.matches('时', code), "{code}");
        }
        assert!(t.matches('是', "yp") && t.matches('是', "rp") && !t.matches('是', "rc"));
        assert!(t.matches('事', "hkx") && t.matches('事', "hs"));
        // 拆字查字的贴合程度。 Lookup tiers.
        let mut found = Vec::new();
        t.search("ricun", |c, tier| found.push((c, tier)));
        assert_eq!(found, [('时', 0)]);
        found.clear();
        t.search("ric", |c, tier| found.push((c, tier)));
        assert_eq!(found, [('时', 1)]);
        found.clear();
        t.search("rc", |c, tier| found.push((c, tier)));
        assert_eq!(found, [('时', 2)]);
        found.clear();
        t.search("r", |c, tier| found.push((c, tier)));
        assert_eq!(found, [('时', 1), ('是', 1)]);
        found.clear();
        t.search("hkx", |c, tier| found.push((c, tier)));
        assert_eq!(found, [('事', 2)]);
        found.clear();
        t.search("hk", |c, tier| found.push((c, tier)));
        assert_eq!(found, [('事', 3)]);
        found.clear();
        table().into_owned().search("hs", |c, tier| found.push((c, tier)));
        assert_eq!(found, [('事', 1)], "事 by its strokes; 十 = HS but is not in this table");
        // 大写只比笔画。 Capitals compare strokes only.
        assert!(t.matches('时', "SZ") && !t.matches('时', "R") && !t.matches('事', "HKX"));
        assert!(!t.matches('他', "t"), "unknown chars never match");
        assert!(t.matches('他', ""), "an empty code filters nothing");
    }
}
