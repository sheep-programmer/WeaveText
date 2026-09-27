//! 联想词：上屏之后、还没打字时，按上文猜下一个词。
//! Next-word prediction: after a commit and before any key, guess the next word from what was just written.
//!
//! 来源（按档排序，档内按分数）/ Sources, in tiers (then by score):
//! 1. 用户二元组：你自己在这个词后面常接的词。 User bigrams: what you usually write after this word.
//! 2. 接续表：词库里以上文结尾 2–4 字开头的长词，剩下的部分（今天 → 早上、晚上）。
//!    The follow table: the rest of lexicon phrases that begin with the last 2–4 chars (今天 → 早上, 晚上).
//! 3. 字符搭配模型：上文之后最常接的字，接成词库里确有的词。 The collocation model: the chars that most often come
//!    next, grown into words the lexicon confirms (looked up by the chars' readings).
//! 4. 只凭上文最后一个字的接续。 Follow-table continuations of the last char alone.
//!
//! 评测（`cargo run --release --example predict -- --eval`，400 句、在词边界处预测）：前 8 个里命中 9.5%，
//! 每处平均省 0.115 个字；接续表 0.47 MB。
//! Benchmark (400 sentences, predicting at word boundaries): 9.5% hit within the top 8, 0.115 chars saved per
//! position; the follow table is 0.47 MB.

use std::collections::HashMap;

use weave_dict::gram::Gram;
use weave_dict::lexicon::{Lexicon, ROOT};
use weave_dict::syllable::SyllableId;

use crate::userdict::UserDict;

/// 默认给出的联想数。 Default number of predictions.
pub const PREDICTIONS: usize = 8;
/// 从搭配模型里最多取多少个延续再去查词库。 Continuations checked against the lexicon at most.
const POOL: usize = 80;
/// 下一个字取前几个、每个字再接前几个。 Top next chars, and top followers of each.
const FIRSTS: usize = 24;
const SECONDS: usize = 12;
const MAX_SINGLES: usize = 3;
/// 各来源的档位间隔（档内再按分数排）。 Gap between source tiers; within a tier, by score.
const TIER: i64 = 1_000_000;
/// 接续表每多匹配一个上文字的奖励。 Bonus per context char matched in the follow table.
const FOLLOW_CONTEXT_BONUS: i64 = 800;
const LEX_WEIGHT: f32 = 1.0;
const LM_WEIGHT: f32 = 1000.0;

/// 预测结果。 A prediction.
#[derive(Clone, Debug, PartialEq)]
pub struct Prediction {
    pub text: String,
    pub cost: i64,
    /// 来自用户二元组。 From the user's own bigrams.
    pub user: bool,
}

/// 单字 → 读音（由词库的单字条目建立，首次联想时构建）。 Char → readings, built from the lexicon's single chars.
/// 每个字最多记 4 个读音（定长数组，省内存）。 Up to four readings per char, in a fixed array to save memory.
#[derive(Default)]
pub struct Readings {
    map: HashMap<char, [SyllableId; 4]>,
}

const NO_READING: SyllableId = SyllableId::MAX;

impl Readings {
    pub fn build(lex: &Lexicon) -> Readings {
        let mut map: HashMap<char, [SyllableId; 4]> = HashMap::new();
        for node in lex.children(ROOT) {
            let syl = lex.sym(node);
            for e in lex.entries(node) {
                let t = lex.text(e.text_id, &[syl]);
                let mut it = t.chars();
                if let (Some(c), None) = (it.next(), it.next()) {
                    let v = map.entry(c).or_insert([NO_READING; 4]);
                    if !v.contains(&syl) {
                        if let Some(slot) = v.iter_mut().find(|r| **r == NO_READING) {
                            *slot = syl;
                        }
                    }
                }
            }
        }
        map.shrink_to_fit();
        Readings { map }
    }

    pub fn len(&self) -> usize {
        self.map.len()
    }

    pub fn is_empty(&self) -> bool {
        self.map.is_empty()
    }

    /// `word` 在词库里的最低 cost（试遍各字读音的组合），不是词则为 None。
    /// The lowest cost of `word` in the lexicon over all reading combinations; None if it isn't a word.
    pub fn word_cost(&self, lex: &Lexicon, word: &str) -> Option<u16> {
        let chars: Vec<char> = word.chars().collect();
        if chars.is_empty() || chars.len() > 4 {
            return None;
        }
        let mut best: Option<u16> = None;
        let mut key: Vec<SyllableId> = Vec::with_capacity(chars.len());
        self.walk(lex, &chars, word, &mut key, ROOT, &mut best);
        best
    }

    fn walk(&self, lex: &Lexicon, chars: &[char], word: &str, key: &mut Vec<SyllableId>, node: u32, best: &mut Option<u16>) {
        let i = key.len();
        if i == chars.len() {
            if let Some(e) = lex.find_entry(node, key, word) {
                *best = Some(best.map_or(e.cost, |b| b.min(e.cost)));
            }
            return;
        }
        let Some(rs) = self.map.get(&chars[i]) else { return };
        for &r in rs.iter().filter(|&&r| r != NO_READING) {
            if let Some(n) = lex.child(node, r) {
                key.push(r);
                self.walk(lex, chars, word, key, n, best);
                key.pop();
            }
        }
    }
}

/// 猜 `context`（刚上屏的文字）之后的词。 Predict the words after `context` (the text just committed).
#[allow(clippy::too_many_arguments)]
pub fn predict(
    context: &str,
    last_word: Option<&str>,
    lex: &Lexicon,
    readings: &Readings,
    follow: Option<&weave_dict::follow::Follow>,
    gram: Option<&Gram>,
    user: &UserDict,
    limit: usize,
) -> Vec<Prediction> {
    let mut scored: HashMap<String, (i64, bool)> = HashMap::new();
    // 1. 用户二元组。 User bigrams.
    if let Some(w) = last_word.filter(|w| !w.is_empty()) {
        for (next, st) in user.bigrams_after(w) {
            let bonus = (2200.0 * (1.0 + st.count as f64).ln()).min(6500.0) as i64;
            let base = readings.word_cost(lex, &next).map(|c| (c as f32 * LEX_WEIGHT) as i64).unwrap_or(4000);
            scored.insert(next, (TIER * 0 + base - bonus, true));
        }
    }
    // 2. 接续表：词库长词里接在上文后面的部分（上文越长越可靠）。
    //    The follow table: what comes after the context inside the lexicon's phrases (longer contexts are more reliable).
    if let Some(f) = follow {
        let chars: Vec<char> = context.chars().collect();
        let mut got: Vec<(String, u16)> = Vec::new();
        let mut found_long = false;
        for k in (1..=chars.len().min(4)).rev() {
            if k == 1 && found_long {
                break;
            }
            let key: String = chars[chars.len() - k..].iter().collect();
            f.after(&key, &mut got);
            found_long |= k >= 2 && !got.is_empty();
            for (rest, cost) in &got {
                // 两字以上上文的接续排在搭配模型前面；只凭一个字的接续排在最后。
                // Continuations of a 2+ char context rank above the collocation guesses; single-char ones last.
                let tier = if k >= 2 { 1 } else { 3 };
                let s = TIER * tier + *cost as i64 - FOLLOW_CONTEXT_BONUS * k as i64;
                let e = scored.entry(rest.clone()).or_insert((i64::MAX, false));
                if s < e.0 {
                    e.0 = s;
                }
            }
        }
    }
    // 3. 搭配模型的延续。 Continuations of the collocation model.
    if let Some(g) = gram {
        let tail: Vec<char> = context.chars().rev().take(2).collect::<Vec<_>>().into_iter().rev().collect();
        let ids = g.tail_ids(&tail);
        let mut pool: HashMap<String, f32> = HashMap::new();
        let name = |rest: &[u16]| -> Option<String> { rest.iter().map(|&i| g.char_of(i)).collect() };
        // 下一个字：两字上文 (c1,c2,x) 最可靠；只有一个字时用 (c,x)。
        // The next char: (c1,c2,x) with a two-char context, else (c,x).
        let mut firsts: Vec<(u16, f32)> = Vec::new();
        match ids.as_slice() {
            [c1, c2] => g.with_prefix(&[*c1, *c2], 3, |rest, v| firsts.push((rest[0], v))),
            [c] => g.with_prefix(&[*c], 2, |rest, v| firsts.push((rest[0], v))),
            _ => {}
        }
        firsts.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
        firsts.truncate(FIRSTS);
        for &(x, v1) in &firsts {
            // 单字也可以是下一个词（的、了、是）。 A single char can be the next word too.
            if let Some(w) = name(&[x]) {
                pool.entry(w).or_insert(v1);
            }
            // 把 x 接成两字词：(x, y) 的常见搭配，按上文与整词的搭配打分。
            // Grow x into a two-char word via common (x, y) pairs, scored by the context's collocation with it.
            let mut seconds: Vec<(u16, f32)> = Vec::new();
            g.with_prefix(&[x], 2, |rest, v| seconds.push((rest[0], v)));
            seconds.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
            for &(y, _) in seconds.iter().take(SECONDS) {
                if let Some(w) = name(&[x, y]) {
                    let v = g.collocation(context, &w).map(|c| c + 12.0).unwrap_or(v1 - 1.0);
                    let e = pool.entry(w).or_insert(f32::MIN);
                    *e = e.max(v);
                }
            }
        }
        let mut top: Vec<(String, f32)> = pool.into_iter().collect();
        top.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
        for (w, v) in top.into_iter().take(POOL) {
            let Some(cost) = readings.word_cost(lex, &w) else { continue };
            let len_bonus = match w.chars().count() {
                1 => -200,
                2 => 300,
                3 => 700,
                _ => 0,
            };
            let s = TIER * 2 + (cost as f32 * LEX_WEIGHT - LM_WEIGHT * v) as i64 - len_bonus;
            let e = scored.entry(w).or_insert((i64::MAX, false));
            if s < e.0 {
                e.0 = s;
            }
        }
    }
    let mut out: Vec<Prediction> = scored.into_iter().map(|(text, (cost, user))| Prediction { text, cost, user }).collect();
    out.sort_by(|a, b| a.cost.cmp(&b.cost).then_with(|| a.text.cmp(&b.text)));
    // 单字最多占 3 个位置，其余留给词。 Single chars take at most three slots; the rest go to words.
    let mut singles = 0;
    out.retain(|p| {
        let one = p.text.chars().count() == 1;
        singles += one as usize;
        !one || singles <= MAX_SINGLES
    });
    out.truncate(limit);
    out
}
