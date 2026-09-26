//! 音节图：把按键序列切成所有可能的音节路径。
//! Syllable graph: every way to cut the key sequence into syllables.
//!
//! 图的顶点是按键位置 0..=n，边 `(start, end)` 表示 keys[start..end] 可以读作
//! `syls` 中任意一个音节，并带一个惩罚 `penalty`（cost 单位，1000 = 1 nat）。
//! Vertices are key positions 0..=n; an edge covers keys[start..end], may be read as any
//! syllable in `syls`, and carries a penalty in cost units (1000 = 1 nat).
//!
//! 全拼、双拼、九键都产出同一种图，后续解码完全共用。
//! Full pinyin, shuangpin and T9 all produce this graph; decoding downstream is shared.

use weave_dict::syllable::{self, SyllableId};

/// 惩罚常量（cost 单位）。 Penalties in cost units.
pub mod penalty {
    /// 模糊音。 Fuzzy-sound substitution.
    pub const FUZZY: u16 = 1200;
    /// 常见错拼纠正（zhogn → zhong）。 Common misspelling correction.
    pub const CORRECTION: u16 = 1800;
    /// 句中简拼（只打声母）。 Abbreviation (initial only) inside the input.
    pub const ABBREV: u16 = 1200;
    /// 末尾简拼：用户可能还没打完。 Abbreviation at the end: user is still typing.
    pub const ABBREV_END: u16 = 400;
    /// 末尾不完整音节（zhon → zhong）。 Incomplete syllable at the end.
    pub const PARTIAL_END: u16 = 300;
    /// 末尾已完整的音节又被当作前缀补全（xian → xiang）。 Completing an already full syllable.
    pub const EXTEND_END: u16 = 4000;
    /// 无法识别的按键原样保留。 Keys that cannot form any syllable.
    pub const RAW: u16 = 20000;
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum EdgeKind {
    Full,
    Fuzzy,
    Correction,
    Abbrev,
    Partial,
    /// 不能组成音节的按键，原样输出。 Raw keys, emitted literally.
    Raw,
}

#[derive(Clone, Debug)]
pub struct Edge {
    pub start: usize,
    pub end: usize,
    pub syls: Vec<SyllableId>,
    pub penalty: u16,
    pub kind: EdgeKind,
    /// 大集合（简拼等）的位图，便于按子节点扫描；小集合为空。 Bitset for large sets, else empty.
    pub bits: Vec<u64>,
}

/// 用位图扫描子节点的阈值。 Set size above which children are scanned against a bitset.
pub const BITSET_MIN: usize = 12;

impl Edge {
    #[inline]
    pub fn contains(&self, s: SyllableId) -> bool {
        let i = s as usize;
        self.bits
            .get(i / 64)
            .is_some_and(|w| w >> (i % 64) & 1 == 1)
    }
}

/// 音节图。 The syllable graph.
#[derive(Clone, Debug, Default)]
pub struct SyllableGraph {
    /// 顶点数 - 1，即按键数。 Number of keys.
    pub len: usize,
    /// out[i] = 从位置 i 出发的边。 Edges leaving position i.
    pub out: Vec<Vec<Edge>>,
}

impl SyllableGraph {
    pub fn new(len: usize) -> Self {
        SyllableGraph {
            len,
            out: vec![Vec::new(); len + 1],
        }
    }

    pub fn push(&mut self, e: Edge) {
        // 同一 (start,end,syls) 只保留惩罚最小的。 Keep the cheapest duplicate.
        let list = &mut self.out[e.start];
        if let Some(old) = list.iter_mut().find(|o| o.end == e.end && o.syls == e.syls) {
            if e.penalty < old.penalty {
                *old = e;
            }
            return;
        }
        list.push(e);
    }

    /// 给「悬空」的句中简拼加重惩罚：该位置落在某个完整音节内部，或从该位置起有更长的完整音节。
    /// 只打声母的输入（zgrm）没有完整音节覆盖，不受影响。
    /// Penalise mid-input abbreviations whose key lies inside a full syllable, or where a longer
    /// full syllable starts. Pure-initial input (zgrm) has no such cover and is unaffected.
    pub fn drop_dangling_abbrev(&mut self) {
        let n = self.len;
        let mut inside = vec![false; n + 1];
        let mut longer_full = vec![false; n + 1];
        for list in &self.out {
            for e in list {
                if e.kind == EdgeKind::Full {
                    for p in e.start + 1..e.end {
                        inside[p] = true;
                    }
                    if e.end > e.start + 1 {
                        longer_full[e.start] = true;
                    }
                }
            }
        }
        // 直接删掉悬空简拼：它们几乎赢不了，却让深搜多走好几倍的路（评测准确率不变，CPU 降约 5 倍）。
        // Drop dangling abbreviations outright: they practically never win but multiply the DFS work
        // (same benchmark accuracy, ~5× less CPU).
        for list in &mut self.out {
            list.retain(|e| {
                !(e.kind == EdgeKind::Abbrev && e.end < n && (inside[e.start] || longer_full[e.start]))
            });
        }
    }

    /// 保证每个位置都能往后走：没有出边的位置补一条 Raw 边。
    /// Make sure every reachable position has a way forward by adding raw edges.
    pub fn ensure_connected(&mut self) {
        let mut reach = vec![false; self.len + 1];
        reach[0] = true;
        for i in 0..self.len {
            if !reach[i] {
                continue;
            }
            if self.out[i].is_empty() {
                self.out[i].push(Edge {
                    start: i,
                    end: i + 1,
                    syls: Vec::new(),
                    penalty: penalty::RAW,
                    kind: EdgeKind::Raw,
                    bits: Vec::new(),
                });
            }
            for e in &self.out[i] {
                reach[e.end] = true;
            }
        }
        // 便宜的边先搜：深搜预算有限时，完整音节不会被简拼挤掉。
        // Cheapest edges first so a limited DFS budget never starves full syllables.
        let words = weave_dict::syllable::count() / 64 + 1;
        for list in &mut self.out {
            list.sort_by_key(|e| (e.penalty, std::cmp::Reverse(e.end)));
            for e in list.iter_mut() {
                if e.syls.len() >= BITSET_MIN {
                    let mut bits = vec![0u64; words];
                    for &sy in &e.syls {
                        bits[sy as usize / 64] |= 1 << (sy as usize % 64);
                    }
                    e.bits = bits;
                }
            }
        }
    }
}

/// 模糊音开关。 Fuzzy-sound switches.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct FuzzyOptions {
    pub z_zh: bool,
    pub c_ch: bool,
    pub s_sh: bool,
    pub n_l: bool,
    pub f_h: bool,
    pub r_l: bool,
    pub an_ang: bool,
    pub en_eng: bool,
    pub in_ing: bool,
    pub ian_iang: bool,
    pub uan_uang: bool,
}

impl FuzzyOptions {
    pub fn any(&self) -> bool {
        *self != FuzzyOptions::default()
    }

    fn initial_pairs(&self) -> Vec<(&'static str, &'static str)> {
        let mut v = Vec::new();
        let flags = [
            (self.z_zh, ("z", "zh")),
            (self.c_ch, ("c", "ch")),
            (self.s_sh, ("s", "sh")),
            (self.n_l, ("n", "l")),
            (self.f_h, ("f", "h")),
            (self.r_l, ("r", "l")),
        ];
        for (on, p) in flags {
            if on {
                v.push(p);
            }
        }
        v
    }

    fn final_pairs(&self) -> Vec<(&'static str, &'static str)> {
        let mut v = Vec::new();
        // 注意顺序：长的在前，避免 ian 被当成 an。 Longer finals first.
        let flags = [
            (self.ian_iang, ("ian", "iang")),
            (self.uan_uang, ("uan", "uang")),
            (self.an_ang, ("an", "ang")),
            (self.en_eng, ("en", "eng")),
            (self.in_ing, ("in", "ing")),
        ];
        for (on, p) in flags {
            if on {
                v.push(p);
            }
        }
        v
    }

    /// 把一个拼写按模糊规则展开成其它合法音节。 Expand a spelling into fuzzy variants.
    pub fn variants(&self, s: &str) -> Vec<SyllableId> {
        if !self.any() {
            return Vec::new();
        }
        let (ini, fin) = syllable::split(s);
        let mut inis = vec![ini.to_string()];
        for (a, b) in self.initial_pairs() {
            if ini == a {
                inis.push(b.to_string());
            } else if ini == b {
                inis.push(a.to_string());
            }
        }
        let mut fins = vec![fin.to_string()];
        for (a, b) in self.final_pairs() {
            if fin == a {
                fins.push(b.to_string());
            } else if fin == b {
                fins.push(a.to_string());
            }
        }
        let mut out = Vec::new();
        for i in &inis {
            for f in &fins {
                let cand = format!("{i}{f}");
                if cand == s {
                    continue;
                }
                if let Some(id) = syllable::id_of(&cand) {
                    if !out.contains(&id) {
                        out.push(id);
                    }
                }
            }
        }
        out
    }
}

/// 常见错拼：韵母写错的纠正规则。 Common final misspellings.
const CORRECTIONS: &[(&str, &str)] = &[
    ("gn", "ng"),  // zhogn → zhong
    ("mg", "ng"),  // zhomg → zhong
    ("uei", "ui"), // guei → gui
    ("uen", "un"), // luen → lun
    ("iou", "iu"), // liou → liu
    ("ioing", "iong"),
    ("iogn", "iong"),
];

fn corrected(s: &str) -> Option<SyllableId> {
    for (bad, good) in CORRECTIONS {
        if let Some(stem) = s.strip_suffix(bad) {
            if let Some(id) = syllable::id_of(&format!("{stem}{good}")) {
                return Some(id);
            }
        }
    }
    None
}

/// 全拼输入的预处理结果：字母与强制分隔（'）。
/// Pre-processed full pinyin: letters plus forced boundaries from apostrophes.
#[derive(Clone, Debug, Default)]
pub struct Letters {
    pub keys: Vec<u8>,
    /// boundary[i] = 位置 i 处有用户输入的分隔符。 A user separator sits at position i.
    pub boundary: Vec<bool>,
}

impl Letters {
    pub fn parse(raw: &str) -> Self {
        let mut keys = Vec::new();
        let mut boundary = vec![false];
        for b in raw.bytes() {
            match b {
                b'a'..=b'z' => {
                    keys.push(b);
                    boundary.push(false);
                }
                b'\'' => {
                    if let Some(last) = boundary.last_mut() {
                        *last = true;
                    }
                }
                _ => {}
            }
        }
        Letters { keys, boundary }
    }

    fn crosses_boundary(&self, start: usize, end: usize) -> bool {
        (start + 1..end).any(|i| self.boundary[i])
    }
}

const MAX_SPELLING: usize = 7;

/// 为全拼输入建音节图。 Build the syllable graph for full pinyin.
pub fn build_full_pinyin(letters: &Letters, fuzzy: &FuzzyOptions) -> SyllableGraph {
    let n = letters.keys.len();
    let mut g = SyllableGraph::new(n);
    for start in 0..n {
        for end in start + 1..=(start + MAX_SPELLING).min(n) {
            if letters.crosses_boundary(start, end) {
                break;
            }
            // SAFETY-free: keys 只含 a-z。 keys are ASCII a-z only.
            let s = std::str::from_utf8(&letters.keys[start..end]).unwrap();
            let at_end = end == n;
            let full = syllable::id_of(s);
            if let Some(id) = full {
                g.push(Edge {
                    start,
                    end,
                    syls: vec![id],
                    penalty: 0,
                    kind: EdgeKind::Full,
                    bits: Vec::new(),
                });
            }
            for id in fuzzy.variants(s) {
                g.push(Edge {
                    start,
                    end,
                    syls: vec![id],
                    penalty: penalty::FUZZY,
                    kind: EdgeKind::Fuzzy,
                    bits: Vec::new(),
                });
            }
            if full.is_none() {
                if let Some(id) = corrected(s) {
                    g.push(Edge {
                        start,
                        end,
                        syls: vec![id],
                        penalty: penalty::CORRECTION,
                        kind: EdgeKind::Correction,
                        bits: Vec::new(),
                    });
                }
            }
            if syllable::is_initial(s) && full.is_none() {
                let syls: Vec<_> = syllable::with_prefix(s).collect();
                let penalty = if at_end {
                    penalty::ABBREV_END
                } else {
                    penalty::ABBREV
                };
                g.push(Edge {
                    start,
                    end,
                    syls,
                    penalty,
                    kind: EdgeKind::Abbrev,
                    bits: Vec::new(),
                });
            } else if at_end && syllable::is_prefix(s) {
                let syls: Vec<_> = syllable::with_prefix(s)
                    .filter(|&id| Some(id) != full)
                    .collect();
                if !syls.is_empty() {
                    let penalty = if full.is_some() {
                        penalty::EXTEND_END
                    } else {
                        penalty::PARTIAL_END
                    };
                    g.push(Edge {
                        start,
                        end,
                        syls,
                        penalty,
                        kind: EdgeKind::Partial,
                        bits: Vec::new(),
                    });
                }
            }
        }
    }
    g.drop_dangling_abbrev();
    g.ensure_connected();
    g
}

#[cfg(test)]
mod tests {
    use super::*;

    fn spell(g: &SyllableGraph, start: usize) -> Vec<(usize, String, EdgeKind)> {
        g.out[start]
            .iter()
            .map(|e| {
                let s = e
                    .syls
                    .first()
                    .map(|&id| syllable::spelling(id))
                    .unwrap_or("");
                (e.end, s.to_string(), e.kind)
            })
            .collect()
    }

    #[test]
    fn xian_is_ambiguous() {
        let g = build_full_pinyin(&Letters::parse("xian"), &FuzzyOptions::default());
        let from0 = spell(&g, 0);
        assert!(from0.contains(&(4, "xian".into(), EdgeKind::Full)));
        assert!(from0.contains(&(2, "xi".into(), EdgeKind::Full)));
        assert!(spell(&g, 2).contains(&(4, "an".into(), EdgeKind::Full)));
    }

    #[test]
    fn apostrophe_forces_boundary() {
        let g = build_full_pinyin(&Letters::parse("xi'an"), &FuzzyOptions::default());
        assert!(!spell(&g, 0).iter().any(|(end, _, _)| *end == 4));
    }

    #[test]
    fn abbreviation_and_partial() {
        let g = build_full_pinyin(&Letters::parse("zgzho"), &FuzzyOptions::default());
        let e = g.out[0].iter().find(|e| e.end == 1).unwrap();
        assert_eq!(e.kind, EdgeKind::Abbrev);
        assert!(e.syls.contains(&syllable::id_of("zhong").unwrap()));
        let p = g.out[2].iter().find(|e| e.end == 5).unwrap();
        assert_eq!(p.kind, EdgeKind::Partial);
        assert!(p.syls.contains(&syllable::id_of("zhong").unwrap()));
    }

    #[test]
    fn fuzzy_and_correction() {
        let f = FuzzyOptions {
            z_zh: true,
            in_ing: true,
            ..Default::default()
        };
        let g = build_full_pinyin(&Letters::parse("zin"), &f);
        let spells: Vec<_> = spell(&g, 0).into_iter().map(|x| x.1).collect();
        assert!(!spells.contains(&"zhin".to_string())); // zhin is not a syllable
        let g = build_full_pinyin(&Letters::parse("xin"), &f);
        assert!(spell(&g, 0)
            .iter()
            .any(|x| x.1 == "xing" && x.2 == EdgeKind::Fuzzy));
        let g = build_full_pinyin(&Letters::parse("zhogn"), &FuzzyOptions::default());
        assert!(spell(&g, 0)
            .iter()
            .any(|x| x.1 == "zhong" && x.2 == EdgeKind::Correction));
    }

    #[test]
    fn raw_fallback() {
        let g = build_full_pinyin(&Letters::parse("iv"), &FuzzyOptions::default());
        assert_eq!(g.out[0][0].kind, EdgeKind::Raw);
        assert_eq!(g.out[1][0].kind, EdgeKind::Raw);
    }
}
