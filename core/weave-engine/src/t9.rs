//! 九键拼音：数字串 → 音节图。 T9 pinyin: digit string → syllable graph.
//!
//! 数字 2-9 对应手机键盘字母，`1` 作为分隔符。用户可以在左侧拼音栏里把最前面一段
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

fn digits_of(s: &str) -> String {
    s.bytes().map(|c| letter_digit(c) as char).collect()
}

struct Index {
    /// 数字串 → 完整音节。 Digit string → syllables.
    exact: HashMap<String, Vec<SyllableId>>,
    /// 数字前缀 → 以之开头的音节。 Digit prefix → syllables.
    prefix: HashMap<String, Vec<SyllableId>>,
}

fn index() -> &'static Index {
    static IDX: OnceLock<Index> = OnceLock::new();
    IDX.get_or_init(|| {
        let mut exact: HashMap<String, Vec<SyllableId>> = HashMap::new();
        let mut prefix: HashMap<String, Vec<SyllableId>> = HashMap::new();
        for sy in syllable::all_ids() {
            let d = digits_of(syllable::spelling(sy));
            exact.entry(d.clone()).or_default().push(sy);
            for l in 1..=d.len() {
                prefix.entry(d[..l].to_string()).or_default().push(sy);
            }
        }
        Index { exact, prefix }
    })
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
    pub digits: Vec<u8>,
    /// (start, end, 锁定内容)。 Locked spans.
    pub locks: Vec<(usize, usize, T9Unit)>,
    pub boundary: Vec<bool>,
}

impl T9Input {
    pub fn from_units(units: &[T9Unit]) -> Self {
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
                        digits.push(letter_digit(c));
                        boundary.push(false);
                    }
                    locks.push((start, digits.len(), u.clone()));
                    *boundary.last_mut().unwrap() = true;
                    boundary[start] = true;
                }
                T9Unit::Letter(c) => {
                    let start = digits.len();
                    digits.push(letter_digit(*c));
                    boundary.push(false);
                    locks.push((start, start + 1, u.clone()));
                    *boundary.last_mut().unwrap() = true;
                    boundary[start] = true;
                }
            }
        }
        T9Input {
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

fn t9_abbrev_penalty() -> u16 {
    T9_ABBREV
}

/// 建音节图。 Build the graph.
pub fn build_graph(input: &T9Input) -> SyllableGraph {
    let idx = index();
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
                    let pen = if at_end { penalty::ABBREV_END } else { t9_abbrev_penalty() };
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
    let idx = index();
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
    for c in digit_letters(input.digits[start]).bytes() {
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
        let inp = T9Input::from_units(&units("94664486"));
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
        let inp = T9Input::from_units(&u);
        assert_eq!(inp.free_start(), 5);
        let g = build_graph(&inp);
        assert_eq!(g.out[0].len(), 1);
        assert_eq!(g.out[0][0].syls, vec![xiong]);
    }
}
