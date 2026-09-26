//! 九键与 14 键拼音：键码串 → 音节图。 T9 / 14-key pinyin: key-code string → syllable graph.
//!
//! 九键：数字 2-9 对应手机键盘字母；14 键：每键两个相邻字母（qw er ty ui op / as df gh jk l / zx cv bn m），
//! 键码为 `A`-`N`。两者共用同一套歧义解码，`1` 作为分隔符。用户可以在左侧拼音栏里把最前面一段
//! 锁定为某个音节（或只锁定声母字母），锁定段作为固定边参与解码。
//! Digits 2-9 map to phone letters and `1` is a separator. The user may lock the leading
//! part to a syllable (or a bare letter) from the pinyin column; locked parts become fixed edges.

use std::collections::HashMap;
use std::sync::OnceLock;

use weave_dict::syllable::{self, SyllableId};

use crate::graph::{penalty, Edge, EdgeKind, SyllableGraph};

pub fn letter_digit(c: u8) -> u8 {
    match c {
        b'a'..=b'c' => b'2',
        b'd'..=b'f' => b'3',
        b'g'..=b'i' => b'4',
        b'j'..=b'l' => b'5',
        b'm'..=b'o' => b'6',
        b'p'..=b's' => b'7',
        b't'..=b'v' => b'8',
        _ => b'9',
    }
}

pub fn digit_letters(d: u8) -> &'static str {
    match d {
        b'2' => "abc",
        b'3' => "def",
        b'4' => "ghi",
        b'5' => "jkl",
        b'6' => "mno",
        b'7' => "pqrs",
        b'8' => "tuv",
        b'9' => "wxyz",
        _ => "",
    }
}

/// 14 键的字母分组（键码 `A` 起依次编号）。 14-key letter groups, coded from `A`.
const FOURTEEN: [&str; 14] = [
    "qw", "er", "ty", "ui", "op", "as", "df", "gh", "jk", "l", "zx", "cv", "bn", "m",
];

/// 按键分组：一个键对应哪些字母。 Key grouping: which letters share a key.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Grouping {
    /// 九键（2-9）。 Phone keypad.
    Nine,
    /// 14 键（A-N，每键两个字母）。 14 keys of two letters each.
    Fourteen,
}

impl Grouping {
    /// 字母 → 键码。 Letter → key code.
    pub fn code(self, c: u8) -> u8 {
        match self {
            Grouping::Nine => letter_digit(c),
            Grouping::Fourteen => FOURTEEN
                .iter()
                .position(|g| g.as_bytes().contains(&c))
                .map_or(b'A', |i| b'A' + i as u8),
        }
    }

    /// 键码 → 字母。 Key code → letters.
    pub fn letters(self, code: u8) -> &'static str {
        match self {
            Grouping::Nine => digit_letters(code),
            Grouping::Fourteen => code
                .checked_sub(b'A')
                .and_then(|i| FOURTEEN.get(i as usize))
                .copied()
                .unwrap_or(""),
        }
    }

    /// 是否为本分组的键码。 Whether `c` is a key code of this grouping.
    pub fn is_code(self, c: char) -> bool {
        match self {
            Grouping::Nine => ('2'..='9').contains(&c),
            Grouping::Fourteen => ('A'..='N').contains(&c),
        }
    }

    fn codes_of(self, s: &str) -> String {
        s.bytes().map(|c| self.code(c) as char).collect()
    }

    fn index(self) -> &'static Index {
        static NINE: OnceLock<Index> = OnceLock::new();
        static FOURTEEN_IDX: OnceLock<Index> = OnceLock::new();
        let cell = match self {
            Grouping::Nine => &NINE,
            Grouping::Fourteen => &FOURTEEN_IDX,
        };
        cell.get_or_init(|| Index::build(self))
    }
}

struct Index {
    /// 数字串 → 完整音节。 Digit string → syllables.
    exact: HashMap<String, Vec<SyllableId>>,
    /// 数字前缀 → 以之开头的音节。 Digit prefix → syllables.
    prefix: HashMap<String, Vec<SyllableId>>,
}

impl Index {
    fn build(g: Grouping) -> Index {
        let mut exact: HashMap<String, Vec<SyllableId>> = HashMap::new();
        let mut prefix: HashMap<String, Vec<SyllableId>> = HashMap::new();
        for sy in syllable::all_ids() {
            let d = g.codes_of(syllable::spelling(sy));
            exact.entry(d.clone()).or_default().push(sy);
            for l in 1..=d.len() {
                prefix.entry(d[..l].to_string()).or_default().push(sy);
            }
        }
        Index { exact, prefix }
    }
}

/// 九键输入中的一个单位。 One unit of T9 input.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum T9Unit {
    Digit(u8),
    /// 分隔符 `1`。 Separator.
    Sep,
    /// 用户锁定的音节，占 `digits` 个数字。 A syllable locked by the user.
    Syllable {
        id: SyllableId,
        digits: usize,
    },
    /// 用户锁定的单个字母（声母/简拼）。 A single locked letter.
    Letter(u8),
}

/// 展开后的九键键序：每个位置一个数字（锁定段也展开成数字，便于统一建图）。
/// Flattened: one digit per position; locked units are expanded too.
pub struct T9Input {
    pub grouping: Grouping,
    pub digits: Vec<u8>,
    /// (start, end, 锁定内容)。 Locked spans.
    pub locks: Vec<(usize, usize, T9Unit)>,
    pub boundary: Vec<bool>,
}

impl T9Input {
    pub fn from_units(units: &[T9Unit], grouping: Grouping) -> Self {
        let mut digits = Vec::new();
        let mut locks = Vec::new();
        let mut boundary = vec![false];
        for u in units {
            match u {
                T9Unit::Digit(d) => {
                    digits.push(*d);
                    boundary.push(false);
                }
                T9Unit::Sep => {
                    if let Some(b) = boundary.last_mut() {
                        *b = true;
                    }
                }
                T9Unit::Syllable { id, .. } => {
                    let start = digits.len();
                    for c in syllable::spelling(*id).bytes() {
                        digits.push(grouping.code(c));
                        boundary.push(false);
                    }
                    locks.push((start, digits.len(), u.clone()));
                    *boundary.last_mut().unwrap() = true;
                    boundary[start] = true;
                }
                T9Unit::Letter(c) => {
                    let start = digits.len();
                    digits.push(grouping.code(*c));
                    boundary.push(false);
                    locks.push((start, start + 1, u.clone()));
                    *boundary.last_mut().unwrap() = true;
                    boundary[start] = true;
                }
            }
        }
        T9Input {
            grouping,
            digits,
            locks,
            boundary,
        }
    }

    /// 已锁定部分之后第一个自由位置。 First position after the locked prefix.
    pub fn free_start(&self) -> usize {
        let mut p = 0;
        for (s, e, _) in &self.locks {
            if *s == p {
                p = *e;
            }
        }
        p
    }
}

const MAX_SPELLING: usize = 6;

/// 句中单个数字当简拼的惩罚（九键每个数字已含 3~4 个字母，歧义比全拼大）。
/// Mid-input single-digit abbreviation penalty (each T9 digit already covers 3–4 letters).
/// 取 2500：整句准确率不变，而深搜剪枝使九键解码快约 7 倍；4 个数字的纯简拼（3×2500+400）仍在单词惩罚上限内。
/// 2500 keeps accuracy while pruning cuts T9 decode time ~7×; a 4-digit pure abbreviation still fits.
const T9_ABBREV: u16 = 1800;

/// 14 键每键只有两个字母，句中简拼更少见：惩罚取 4500，准确率与 1800 相同而解码快约 2.7 倍。
/// 14 keys hold two letters each, so mid-input abbreviations are rarer: 4500 matches 1800's accuracy and
/// decodes ~2.7× faster.
const T14_ABBREV: u16 = 4500;

fn t9_abbrev_penalty(g: Grouping) -> u16 {
    match g {
        Grouping::Nine => T9_ABBREV,
        Grouping::Fourteen => T14_ABBREV,
    }
}

/// 建音节图。 Build the graph.
pub fn build_graph(input: &T9Input) -> SyllableGraph {
    let idx = input.grouping.index();
    let n = input.digits.len();
    let mut g = SyllableGraph::new(n);
    let locked_at: HashMap<usize, &(usize, usize, T9Unit)> =
        input.locks.iter().map(|l| (l.0, l)).collect();
    let in_lock = |p: usize| input.locks.iter().any(|(s, e, _)| p > *s && p < *e);

    for start in 0..n {
        if in_lock(start) {
            continue;
        }
        if let Some((s, e, unit)) = locked_at.get(&start).copied() {
            let (syls, kind, pen) = match unit {
                T9Unit::Syllable { id, .. } => (vec![*id], EdgeKind::Full, 0),
                T9Unit::Letter(c) => {
                    let pre = (*c as char).to_string();
                    let syls: Vec<_> = syllable::with_prefix(&pre).collect();
                    (syls, EdgeKind::Abbrev, penalty::ABBREV_END)
                }
                _ => unreachable!(),
            };
            g.push(Edge {
                start: *s,
                end: *e,
                syls,
                penalty: pen,
                kind,
                bits: Vec::new(),
            });
            continue;
        }
        for end in start + 1..=(start + MAX_SPELLING).min(n) {
            if (start + 1..end).any(|i| input.boundary[i]) || in_lock(end - 1) {
                break;
            }
            let d = std::str::from_utf8(&input.digits[start..end]).unwrap();
            let at_end = end == n || input.boundary[end];
            if let Some(syls) = idx.exact.get(d) {
                g.push(Edge {
                    start,
                    end,
                    syls: syls.clone(),
                    penalty: 0,
                    kind: EdgeKind::Full,
                    bits: Vec::new(),
                });
            }
            if end - start == 1 {
                // 单个数字作简拼：以这些字母开头的全部音节。 One digit as an abbreviation.
                if let Some(syls) = idx.prefix.get(d) {
                    let exact = idx.exact.get(d);
                    let syls: Vec<_> = syls
                        .iter()
                        .copied()
                        .filter(|s| !exact.is_some_and(|e| e.contains(s)))
                        .collect();
                    let pen = if at_end { penalty::ABBREV_END } else { t9_abbrev_penalty(input.grouping) };
                    g.push(Edge {
                        start,
                        end,
                        syls,
                        penalty: pen,
                        kind: EdgeKind::Abbrev,
                        bits: Vec::new(),
                    });
                }
            } else if at_end && end == n {
                if let Some(syls) = idx.prefix.get(d) {
                    let exact = idx.exact.get(d);
                    let syls: Vec<_> = syls
                        .iter()
                        .copied()
                        .filter(|s| !exact.is_some_and(|e| e.contains(s)))
                        .collect();
                    if !syls.is_empty() {
                        // 数字已能拼成完整音节时，再当前缀补全就要付出更高代价（ni → nian）。
                        // Completing digits that already spell a full syllable costs more.
                        let penalty = if exact.is_some() {
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
    }
    g.drop_dangling_abbrev();
    g.ensure_connected();
    g
}

/// 左侧拼音栏：第一个自由位置可选的拼写（长的在前）以及首数字的单个字母。
/// Pinyin column: spellings available at the first free position (longest first) plus the
/// bare letters of its first digit.
pub fn pinyin_options(input: &T9Input) -> Vec<T9Unit> {
    let idx = input.grouping.index();
    let start = input.free_start();
    let n = input.digits.len();
    if start >= n {
        return Vec::new();
    }
    let mut out = Vec::new();
    let mut end = (start + MAX_SPELLING).min(n);
    if let Some(b) = (start + 1..end).find(|&i| input.boundary[i]) {
        end = b;
    }
    for e in (start + 1..=end).rev() {
        let d = std::str::from_utf8(&input.digits[start..e]).unwrap();
        if let Some(syls) = idx.exact.get(d) {
            for &s in syls {
                out.push(T9Unit::Syllable {
                    id: s,
                    digits: e - start,
                });
            }
        }
    }
    for c in input.grouping.letters(input.digits[start]).bytes() {
        out.push(T9Unit::Letter(c));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn units(s: &str) -> Vec<T9Unit> {
        s.bytes()
            .map(|b| {
                if b == b'1' {
                    T9Unit::Sep
                } else {
                    T9Unit::Digit(b)
                }
            })
            .collect()
    }

    #[test]
    fn zhongguo() {
        // zhong=94664 guo=486
        let inp = T9Input::from_units(&units("94664486"), Grouping::Nine);
        let g = build_graph(&inp);
        let zhong = syllable::id_of("zhong").unwrap();
        assert!(g.out[0]
            .iter()
            .any(|e| e.end == 5 && e.syls.contains(&zhong)));
        let opts = pinyin_options(&inp);
        assert!(opts.contains(&T9Unit::Syllable {
            id: zhong,
            digits: 5
        }));
        assert!(opts.contains(&T9Unit::Letter(b'w')));
    }

    #[test]
    fn lock_syllable() {
        let xiong = syllable::id_of("xiong").unwrap();
        let mut u = vec![T9Unit::Syllable {
            id: xiong,
            digits: 5,
        }];
        u.extend(units("486"));
        let inp = T9Input::from_units(&u, Grouping::Nine);
        assert_eq!(inp.free_start(), 5);
        let g = build_graph(&inp);
        assert_eq!(g.out[0].len(), 1);
        assert_eq!(g.out[0][0].syls, vec![xiong]);
    }

    #[test]
    fn fourteen_keys() {
        let g = Grouping::Fourteen;
        // 26 个字母各属于且只属于一个键。 Every letter belongs to exactly one key.
        for c in b'a'..=b'z' {
            assert!(g.letters(g.code(c)).as_bytes().contains(&c));
        }
        // zhong guo → zx gh op bn gh  gh ui op
        let codes: Vec<T9Unit> = g.codes_of("zhongguo").bytes().map(T9Unit::Digit).collect();
        let inp = T9Input::from_units(&codes, g);
        let graph = build_graph(&inp);
        let zhong = syllable::id_of("zhong").unwrap();
        assert!(graph.out[0].iter().any(|e| e.end == 5 && e.syls.contains(&zhong)));
        assert!(pinyin_options(&inp).contains(&T9Unit::Letter(b'x')));
    }
}
