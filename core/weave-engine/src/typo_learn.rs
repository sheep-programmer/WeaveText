//! 接受过的纠错：用户选中了一个「拼错」输入对应的词，就记下「打的字母 → 意图的音节」，下次同样的打法罚分变低。
//! 只学整段输入一次选中、字母与音节只差一两个字母的情况；简拼与完全正确的拼写不记。
//!
//! Accepted corrections: when the user picks the word a mistyped input was meant to be, the typed letters and the
//! intended syllables are remembered, and the same typing costs less next time. Only a whole-input pick where the
//! letters differ by one or two edits counts; abbreviations and correct spellings are not recorded.

use crate::graph::penalty;

/// 个人数据里的键前缀（随互联同步）。 Personal-store key prefix (synced with the other devices).
pub const KEY_PREFIX: &str = "typo:";
/// 折扣上限：学到的纠错最多比基础纠错便宜这么多，仍高于拼对的读法。
/// The discount cap: a learned correction is at most this much cheaper than a base correction.
pub const MAX_DISCOUNT: u16 = 1_200;

pub fn key(typed: &str) -> String {
    format!("{KEY_PREFIX}{typed}")
}

/// 判断 `typed`（打的字母）是否是 `spelled`（意图的拼音，无空格）的一次拼错：长度相近，编辑距离不超过 2，且不相等。
/// True when `typed` is a one- or two-edit mistype of `spelled` (no spaces): similar length and not equal.
pub fn is_typo(typed: &str, spelled: &str) -> bool {
    if typed.len() < 3 || spelled.is_empty() || typed == spelled {
        return false;
    }
    if typed.len().abs_diff(spelled.len()) > 1 {
        return false;
    }
    // 打的字母本身就能拼成合法读音（xiuba = 修 + 吧）时，它不是拼错，不能学成别的读法。
    // Letters that already spell valid readings (xiuba = xiu + ba) are not a mistype and must not be learned as one.
    if spells_validly(typed) {
        return false;
    }
    edit_distance(typed.as_bytes(), spelled.as_bytes()) <= 2
}

/// 整串字母能否切成合法音节。Whether the whole string splits into valid syllables.
pub fn spells_validly(typed: &str) -> bool {
    if !typed.is_ascii() {
        return false;
    }
    let n = typed.len();
    let mut reachable = vec![false; n + 1];
    reachable[0] = true;
    for i in 0..n {
        if !reachable[i] {
            continue;
        }
        for j in i + 1..=n.min(i + 6) {
            if weave_dict::syllable::id_of(&typed[i..j]).is_some() {
                reachable[j] = true;
            }
        }
    }
    reachable[n]
}

/// 一条记录：(接受次数, 意图的音节，空格分隔)。 One record: (accepted count, intended syllables, space separated).
pub fn decode(value: &str) -> Vec<(u32, String)> {
    value
        .lines()
        .filter_map(|line| {
            let (count, intended) = line.split_once('\t')?;
            Some((count.parse().ok()?, intended.to_string()))
        })
        .collect()
}

pub fn encode(records: &[(u32, String)]) -> String {
    records.iter().map(|(count, intended)| format!("{count}\t{intended}\n")).collect()
}

/// 记一次接受：已有相同意图则计数加一，否则新增。 Count one acceptance: add to an existing intent, or add a new one.
pub fn record(records: &mut Vec<(u32, String)>, intended: &str) {
    match records.iter_mut().find(|(_, i)| i == intended) {
        Some(r) => r.0 = r.0.saturating_add(1),
        None => records.push((1, intended.to_string())),
    }
}

/// 罚分折扣 = min(ln(1 + 次数) × 600, 上限)。 Discount = min(ln(1 + count) × 600, cap).
pub fn discount(count: u32) -> u16 {
    let d = (1.0 + f64::from(count)).ln() * 600.0;
    (d as u16).min(MAX_DISCOUNT)
}

/// 学到的纠错边的罚分：基础纠正罚分减去折扣，不低于 0。
/// Penalty of a learned correction edge: the base correction penalty minus the discount, never below zero.
pub fn penalty_for(count: u32) -> u16 {
    penalty::CORRECTION.saturating_sub(discount(count))
}

/// 把打的字母按意图的音节切开：每段长度取音节长度，最后一段取剩下的字母；字母必须正好用完。
/// 返回 (起点, 终点, 音节, 这一段是否拼对)。Split the typed letters by the intended syllables: each piece takes the
/// syllable's length and the last takes the rest; every letter must be used. Returns (start, end, syllable, spelled right).
pub fn align(typed: &str, words: &[&str]) -> Option<Vec<(usize, usize, weave_dict::syllable::SyllableId, bool)>> {
    let mut out = Vec::with_capacity(words.len());
    let mut pos = 0;
    for (i, word) in words.iter().enumerate() {
        let want = if i + 1 == words.len() { typed.len().saturating_sub(pos) } else { word.len() };
        let end = (pos + want).min(typed.len());
        if end == pos {
            return None;
        }
        let id = weave_dict::syllable::id_of(word)?;
        out.push((pos, end, id, &typed[pos..end] == *word));
        pos = end;
    }
    (pos == typed.len()).then_some(out)
}

fn edit_distance(a: &[u8], b: &[u8]) -> usize {
    let mut prev: Vec<usize> = (0..=b.len()).collect();
    for (i, &ca) in a.iter().enumerate() {
        let mut cur = vec![i + 1; b.len() + 1];
        for (j, &cb) in b.iter().enumerate() {
            let sub = prev[j] + usize::from(ca != cb);
            cur[j + 1] = sub.min(prev[j + 1] + 1).min(cur[j] + 1);
        }
        prev = cur;
    }
    prev[b.len()]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn one_or_two_edits_of_a_real_spelling_are_typos() {
        assert!(is_typo("zhiwne", "zhiwen"));
        assert!(is_typo("nihoa", "nihao"));
        assert!(is_typo("zhiwn", "zhiwen"));
        assert!(!is_typo("zhiwen", "zhiwen"), "a correct spelling is not a typo");
        assert!(!is_typo("jt", "jintian"), "an abbreviation is not a typo");
        assert!(!is_typo("ab", "ba"), "too short to judge");
        assert!(!is_typo("qqqqq", "zhiwen"), "far apart is not a typo");
        assert!(!is_typo("xiuba", "xiuban"), "xiuba spells 修吧 by itself");
    }

    #[test]
    fn records_round_trip_and_count_repeats() {
        let mut records = Vec::new();
        record(&mut records, "zhi wen");
        record(&mut records, "zhi wen");
        record(&mut records, "ni hao");
        let back = decode(&encode(&records));
        assert_eq!(back, vec![(2, "zhi wen".to_string()), (1, "ni hao".to_string())]);
    }

    #[test]
    fn letters_split_by_syllable_and_mark_the_mistyped_piece() {
        let pieces = align("nihoa", &["ni", "hao"]).unwrap();
        assert_eq!(pieces.iter().map(|p| (p.0, p.1, p.3)).collect::<Vec<_>>(), vec![(0, 2, true), (2, 5, false)]);
        assert!(align("nihoa", &["ni", "hao", "ma"]).is_none(), "letters left over or syllables short");
    }

    #[test]
    fn discount_grows_with_use_and_is_capped() {
        assert_eq!(discount(0), 0);
        assert!(discount(1) > 0);
        assert!(discount(2) > discount(1));
        assert_eq!(discount(1_000_000), MAX_DISCOUNT);
        assert!(penalty_for(1_000_000) >= penalty::CORRECTION - MAX_DISCOUNT);
        assert!(penalty_for(1) < penalty::CORRECTION);
    }
}
