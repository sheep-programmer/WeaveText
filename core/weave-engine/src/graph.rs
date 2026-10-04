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
    /// 模糊音。多组同时开启时互相叠加，取得偏重：拼写正确的输入几乎不受影响（常用 7 组全开，整句评测只降 0.5 个点），
    /// 只有模糊读法才成词时它才会排上来。
    /// Fuzzy-sound substitution. Several pairs compound, so the cost is high: correct spelling is barely affected (all
    /// seven common pairs on cost 0.5 points on the benchmark) and a fuzzy reading wins only when it alone makes a word.
    pub const FUZZY: u16 = 3000;
    /// 常见错拼纠正（zhogn → zhong）与相邻字母颠倒。 Common misspellings and swapped adjacent letters.
    pub const CORRECTION: u16 = 1800;
    /// 漏打一个字母（zhng → zhong）或多打一个（zhoong → zhong）。 One letter missing or one too many.
    pub const TYPO_EDIT: u16 = 2600;
    /// 按成了左右相邻的键（整键按错，不在交界处，所以没有触点信息）：xhong → zhong。
    /// A whole neighbouring key was hit (not near the border, so no touch info): xhong → zhong.
    pub const TYPO_NEIGHBOUR: u16 = 3200;
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
    /// 按在两键交界处、换成邻键才成音节：最低惩罚（正压在交界线上）与随远离交界增加的部分。
    /// A tap near a key border read as the neighbour: the cost right on the border, plus the part that grows
    /// as the tap moves away from it.
    pub const TOUCH_NEAR: u16 = 600;
    pub const TOUCH_SPREAD: u16 = 1200;
    /// 原拼写本身就是完整音节时，换邻键的额外惩罚（打对的字不轻易被改）。
    /// Extra cost when the typed spelling is already a full syllable, so correct typing is rarely overridden.
    pub const TOUCH_VALID: u16 = 1200;
}

/// 一次按键的邻键：触点靠近两键交界时，另一侧的字母与换成它的惩罚；`alt == 0` 表示没有。
/// The neighbour of one tap: when it lands near a key border, the letter on the other side and the cost
/// of reading it instead; `alt == 0` means none.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Near {
    pub alt: u8,
    pub cost: u16,
}

impl Near {
    pub const NONE: Near = Near { alt: 0, cost: 0 };

    /// `closeness`：1 = 正压在交界线上，0 = 刚进入交界带。 1 = right on the border, 0 = at the band's edge.
    pub fn new(alt: u8, closeness: f32) -> Near {
        if !alt.is_ascii_lowercase() {
            return Near::NONE;
        }
        let c = closeness.clamp(0.0, 1.0);
        Near {
            alt,
            cost: penalty::TOUCH_NEAR + ((1.0 - c) * penalty::TOUCH_SPREAD as f32) as u16,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum EdgeKind {
    Full,
    Fuzzy,
    Correction,
    /// 按到邻键的纠正。 A tap read as the neighbouring key.
    Touch,
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

    /// 只保留实际打出的完整音节，给补全/纠错候选保留原拼写的选择。
    /// Only fully typed syllables, used to keep the literal reading available beside completion/correction.
    pub fn full_reading(&self) -> Self {
        let mut graph = self.clone();
        for list in &mut graph.out {
            list.retain(|edge| edge.kind == EdgeKind::Full);
        }
        graph
    }

    /// 按键能否整串读成实际打出的完整音节。 Whether the keys read through as fully typed syllables.
    pub fn spells_fully(&self) -> bool {
        let mut reach = vec![false; self.len + 1];
        reach[0] = true;
        for at in 0..self.len {
            if reach[at] {
                for edge in self.out[at].iter().filter(|e| e.kind == EdgeKind::Full) {
                    reach[edge.end] = true;
                }
            }
        }
        reach[self.len]
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
                    inside[e.start + 1..e.end].fill(true);
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
                !(e.kind == EdgeKind::Abbrev && (inside[e.start] || (e.end < n && longer_full[e.start])))
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

/// 漏打时最可能漏的字母。 Letters most likely to be left out.
const MISSABLE: &[u8] = b"aeiouvghn";

/// 全键盘上每个字母左右相邻的键。 The keys left and right of each letter on the QWERTY layout.
fn neighbours(c: u8) -> &'static [u8] {
    const ROWS: [&[u8]; 3] = [b"qwertyuiop", b"asdfghjkl", b"zxcvbnm"];
    for row in ROWS {
        if let Some(i) = row.iter().position(|&k| k == c) {
            let lo = i.saturating_sub(1);
            let hi = (i + 2).min(row.len());
            return &row[lo..hi];
        }
    }
    &[]
}

/// 打错的一个音节可能想打的音节：相邻两字母颠倒、漏一个字母、多一个字母、按成左右相邻的键；原拼写本身已是音节或
/// （在末尾时）还能接着打成音节就不猜。返回 (音节, 惩罚)。
/// 已是音节的拼写也按打错来猜（shang → shuang）在整句评测上没有帮助，反而拖累颠倒纠错，所以不做。
/// Syllables a mistyped spelling may have meant: two adjacent letters swapped, one letter missing, one extra, or a
/// neighbouring key hit. Nothing is guessed when the spelling is already a syllable or, at the end, can still grow
/// into one. Returns (syllable, penalty). Guessing inside spellings that are already syllables (shang → shuang) did
/// not help on the sentence benchmark and hurt swap correction, so it is not done.
pub fn typo_variants(s: &str, at_end: bool) -> Vec<(SyllableId, u16)> {
    let b = s.as_bytes();
    let mut out: Vec<(SyllableId, u16)> = Vec::new();
    if !(1..=6).contains(&b.len()) || (b.len() == 1 && (at_end || !b"iu".contains(&b[0])))
        || syllable::id_of(s).is_some() || (at_end && syllable::is_prefix(s)) {
        return out;
    }
    let mut add = |t: &[u8], pen: u16| {
        if let Some(id) = std::str::from_utf8(t).ok().and_then(syllable::id_of) {
            match out.iter_mut().find(|(i, _)| *i == id) {
                Some(e) => e.1 = e.1.min(pen),
                None => out.push((id, pen)),
            }
        }
    };
    let mut t = b.to_vec();
    for p in 0..b.len() - 1 {
        if b[p] != b[p + 1] {
            t.swap(p, p + 1);
            add(&t, penalty::CORRECTION);
            t.swap(p, p + 1);
        }
    }
    if b.len() >= 3 {
        // 句中不删末尾字母：它多半是下一个音节的声母（haox 不读成 hao）；连按的字母从下一个音节开头删。
        // Mid-input the last letter is not dropped: it is most likely the next syllable's initial (haox is not
        // hao); a doubled letter is dropped from the start of the next syllable instead.
        for p in 0..b.len() - (!at_end) as usize {
            let mut d = b.to_vec();
            d.remove(p);
            add(&d, penalty::TYPO_EDIT);
        }
    }
    // 按错邻键：首字母也可能按错（xhong → zhong）。 A neighbouring key, the first letter included.
    for p in 0..b.len() {
        for &c in neighbours(b[p]) {
            if c != b[p] {
                t[p] = c;
                add(&t, penalty::TYPO_NEIGHBOUR);
            }
        }
        t[p] = b[p];
    }
    // 漏打零声母的 y、w（ing → ying，u → wu/yu）。 A dropped zero-initial y or w.
    if b"iuv".contains(&b[0]) {
        for &c in b"yw" {
            let mut ins = b.to_vec();
            ins.insert(0, c);
            add(&ins, penalty::TYPO_EDIT);
        }
    }
    for p in 1..=b.len() {
        for &c in MISSABLE {
            // 声母、鼻韵母的字母只在它们该在的位置补。 Initial / nasal letters only where they belong.
            let fits = match c {
                b'h' => p == 1 && b"zcs".contains(&b[0]),
                b'g' => b[p - 1] == b'n',
                b'n' => b.get(p) == Some(&b'g'),
                _ => true,
            };
            if fits {
                let mut ins = b.to_vec();
                ins.insert(p, c);
                add(&ins, penalty::TYPO_EDIT);
            }
        }
    }
    out
}

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
                b'A'..=b'Z' => {keys.push(b.to_ascii_lowercase());boundary.push(false);}
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
    build_full_pinyin_near(letters, fuzzy, &[], false)
}

/// 同上，并按触点邻键补纠正边：一个音节内至多换一个键。[near] 与 `letters.keys` 一一对应（可为空）。
/// As above, plus correction edges from the taps' neighbours: at most one key replaced per syllable.
/// [near] parallels `letters.keys` (may be empty).
pub fn build_full_pinyin_near(letters: &Letters, fuzzy: &FuzzyOptions, near: &[Near], typos: bool) -> SyllableGraph {
    let n = letters.keys.len();
    let near = if near.len() == n { near } else { &[] };
    let mut g = SyllableGraph::new(n);
    // 能用完整（或已纠正的）音节走到的位置；打错只在这些位置之后猜，否则每个子串都要猜一遍。
    // Positions reachable through whole (or corrected) syllables; typos are only guessed from these, not from every substring.
    let mut spelled = vec![false; n + 1];
    spelled[0] = true;
    for start in 0..n {
        spelled[start] |= letters.boundary[start];
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
                if typos && spelled[start] {
                    for (id, pen) in typo_variants(s, at_end) {
                        g.push(Edge { start, end, syls: vec![id], penalty: pen, kind: EdgeKind::Correction, bits: Vec::new() });
                    }
                }
            }
            if !near.is_empty() {
                touch_edges(&mut g, letters, near, start, end, full);
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
        if typos {
            for e in &g.out[start] {
                if matches!(e.kind, EdgeKind::Full | EdgeKind::Fuzzy | EdgeKind::Correction | EdgeKind::Touch) {
                    spelled[e.end] |= spelled[start];
                }
            }
        }
    }
    g.drop_dangling_abbrev();
    g.ensure_connected();
    g
}

/// 把 keys[start..end] 中某一个有邻键的位置换成邻键，能成完整音节（或末尾的音节前缀）就加一条纠正边。
/// Replace one key of keys[start..end] that has a neighbour; add a correction edge when that spells a full
/// syllable (or, at the end of the input, a syllable prefix).
fn touch_edges(g: &mut SyllableGraph, letters: &Letters, near: &[Near], start: usize, end: usize, full: Option<SyllableId>) {
    let at_end = end == letters.keys.len();
    let mut buf = [0u8; MAX_SPELLING];
    for p in start..end {
        let nb = near[p];
        if nb.alt == 0 {
            continue;
        }
        let len = end - start;
        buf[..len].copy_from_slice(&letters.keys[start..end]);
        buf[p - start] = nb.alt;
        let s = std::str::from_utf8(&buf[..len]).unwrap();
        let extra = if full.is_some() { penalty::TOUCH_VALID } else { 0 };
        if let Some(id) = syllable::id_of(s) {
            if Some(id) != full {
                g.push(Edge {
                    start,
                    end,
                    syls: vec![id],
                    penalty: nb.cost + extra,
                    kind: EdgeKind::Touch,
                    bits: Vec::new(),
                });
            }
        } else if at_end && full.is_none() && len > 1 && syllable::is_prefix(s) && !syllable::is_initial(s) {
            // 正在打的最后一个音节：邻键能接上前缀时照常给出补全。 The syllable being typed: complete via the neighbour.
            let syls: Vec<_> = syllable::with_prefix(s).collect();
            if !syls.is_empty() {
                g.push(Edge {
                    start,
                    end,
                    syls,
                    penalty: nb.cost + penalty::PARTIAL_END,
                    kind: EdgeKind::Touch,
                    bits: Vec::new(),
                });
            }
        }
    }
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
    fn a_neighbouring_key_is_corrected_but_a_real_syllable_is_not() {
        // xhong：z 按成了旁边的 x。 xhong: z was hit as its neighbour x.
        let v = typo_variants("xhong", false);
        assert!(v.iter().any(|&(id, _)| syllable::spelling(id) == "zhong"), "{v:?}");
        // 本身就是音节的拼写不改。 A spelling that is already a syllable is left alone.
        assert!(typo_variants("zhong", false).is_empty());
        assert!(typo_variants("shang", false).is_empty());
        assert_eq!(neighbours(b'q'), b"qw");
        assert_eq!(neighbours(b'g'), b"fgh");
    }

    #[test]
    fn a_missing_zero_initial_can_be_restored_inside_input() {
        for (typed, expected) in [("u", "wu"), ("u", "yu"), ("i", "yi"), ("ing", "ying")] {
            let variants = typo_variants(typed, false);
            assert!(variants.iter().any(|&(id, _)| syllable::spelling(id) == expected), "{typed} → {expected}: {variants:?}");
        }
        assert!(typo_variants("u", true).is_empty());
        assert!(typo_variants("i", true).is_empty());
        assert!(typo_variants("n", false).is_empty());
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
    fn touch_neighbour_fixes_a_border_tap() {
        // xhong：x 按在 z 的交界处。 x landed on the border with z.
        let l = Letters::parse("xhong");
        let mut near = vec![Near::NONE; 5];
        near[0] = Near::new(b'z', 0.8);
        let g = build_full_pinyin_near(&l, &FuzzyOptions::default(), &near, false);
        let e = g.out[0].iter().find(|e| e.kind == EdgeKind::Touch && e.end == 5).unwrap();
        assert_eq!(e.syls, vec![syllable::id_of("zhong").unwrap()]);
        assert!(e.penalty < penalty::TOUCH_NEAR + penalty::TOUCH_SPREAD);
        // 没有邻键信息时不纠正。 Without neighbour info nothing changes.
        let g = build_full_pinyin(&l, &FuzzyOptions::default());
        assert!(!g.out[0].iter().any(|e| e.kind == EdgeKind::Touch));
        // 原拼写已是音节时代价更高。 Costlier when the typed spelling is already a syllable.
        let l = Letters::parse("mi");
        let near = [Near::new(b'n', 0.8), Near::NONE];
        let g = build_full_pinyin_near(&l, &FuzzyOptions::default(), &near, false);
        let e = g.out[0].iter().find(|e| e.kind == EdgeKind::Touch).unwrap();
        assert_eq!(e.syls, vec![syllable::id_of("ni").unwrap()]);
        assert!(e.penalty >= penalty::TOUCH_VALID + penalty::TOUCH_NEAR);
    }

    #[test]
    fn raw_fallback() {
        let g = build_full_pinyin(&Letters::parse("iv"), &FuzzyOptions::default());
        assert_eq!(g.out[0][0].kind, EdgeKind::Raw);
        assert_eq!(g.out[1][0].kind, EdgeKind::Raw);
    }
}
