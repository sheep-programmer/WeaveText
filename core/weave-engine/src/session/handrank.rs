//! 手写候选的上文重排：识别器只看笔迹，这里再加上「这个字接在刚写的字后面像不像」。
//! 单字：笔迹分数加上搭配模型对上文的评分。连写（2–4 个字）：在各字组的前几名里穷举组合，
//! 笔迹分数加相邻字的搭配分，再给词库里确有的词一个奖励。
//! Context re-ranking of handwriting candidates: the recogniser sees only the ink; this adds how well a char follows
//! what was just written. One char: ink score plus the collocation score against the context. Spaced line (2–4
//! chars): enumerate combinations of each group's best few, scoring ink plus neighbouring collocations, with a bonus
//! for words the lexicon knows.
use super::*;

/// 连写时每组参与组合的候选数。 Candidates per group taken into the combinations.
const COMBINE: usize = 4;

impl Engine {
    /// 字 `c` 接在 `ctx` 之后的搭配分（nat）；没有搭配模型或没有上文时为 0，没命中为基线。
    /// Collocation score of `c` after `ctx` (nats): 0 without a model or context, the baseline on a miss.
    fn hand_lm(&self, ctx: &str, c: char) -> f32 {
        let Some(g) = self.gram.as_ref() else { return 0.0 };
        if ctx.is_empty() {
            return 0.0;
        }
        let mut buf = [0u8; 4];
        g.collocation(ctx, c.encode_utf8(&mut buf)).unwrap_or(-self.options.lm_baseline)
    }

    /// 把识别结果（按字组）排成最终候选：单字模式给出字序列；连写模式给出以 `'\0'` 结尾的词序列。
    /// Turn a recognition (per group) into the final candidates: chars for one group, `'\0'`-terminated words for a line.
    pub(super) fn rank_hand(&mut self, groups: Vec<Vec<(char, f32)>>) -> Vec<char> {
        let w = self.options.hand_lm_weight;
        let context = self.recent.clone();
        if groups.len() < 2 {
            let mut list: Vec<(char, f32)> = groups.into_iter().next().unwrap_or_default();
            if w > 0.0 && !context.is_empty() && self.gram.is_some() {
                for item in &mut list {
                    item.1 += w * self.hand_lm(&context, item.0);
                }
                list.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
            }
            return list.into_iter().map(|x| x.0).collect();
        }
        if groups.iter().any(|g| g.is_empty()) {
            return Vec::new();
        }
        let mut words: Vec<(String, f32)> = vec![(String::new(), 0.0)];
        for group in &groups {
            let mut next = Vec::with_capacity(words.len() * COMBINE);
            for (word, score) in &words {
                for &(c, s) in group.iter().take(COMBINE) {
                    let lm = if w > 0.0 { self.hand_lm(&format!("{context}{word}"), c) } else { 0.0 };
                    let mut grown = word.clone();
                    grown.push(c);
                    next.push((grown, score + s + w * lm));
                }
            }
            words = next;
        }
        let bonus = self.options.hand_word_bonus;
        if bonus > 0.0 {
            for item in &mut words {
                if self.known_word(&item.0) {
                    item.1 += bonus;
                }
            }
        }
        words.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
        let mut out = Vec::new();
        for (word, _) in words.into_iter().take(HAND_CANDIDATES) {
            out.extend(word.chars());
            out.push('\0');
        }
        out
    }

    /// 词库里有没有这个词（任意读音组合）。 Whether the lexicon has this word under any reading.
    fn known_word(&mut self, word: &str) -> bool {
        let Some(lex) = self.pinyin.as_ref() else { return false };
        let readings = self.readings.get_or_insert_with(|| crate::predict::Readings::build(lex));
        readings.word_cost(lex, word).is_some()
    }

    /// 交回别处识别好的结果（传输格式见 [`weave_dict::handnet::encode_wire`]）；旧格式（只有字码位）照旧处理。
    /// Hand back a recognition done elsewhere (wire format: see `encode_wire`); the old format (code points only) still works.
    pub fn hand_apply_wire(&mut self, strokes: Vec<weave_dict::hand::Stroke>, wire: &[u32]) -> bool {
        if self.schema != Schema::Hand {
            return false;
        }
        match weave_dict::handnet::decode_wire(wire) {
            Some(groups) => {
                let cands = self.rank_hand(groups);
                self.hand_apply(strokes, cands)
            }
            None => self.hand_apply(strokes, wire.iter().copied().filter_map(char::from_u32).collect()),
        }
    }
}
