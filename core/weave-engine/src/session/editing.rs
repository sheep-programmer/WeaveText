//! 组合串编辑：在拼音中间移动光标、插入与删除，退格撤回刚选的词，以词取字。
//! 光标位置按「第几个按键之后」理解：全拼里分隔符 `'` 不算按键，双拼与英文里每个字节都是按键。
//! 解码仍然针对整串输入，光标只决定新按键插在哪里、退格删哪一个。
//! Composition editing: moving the caret inside the pinyin, inserting and deleting there, backspace undoing the last
//! pick, and taking one character of a word. The caret is "after the n-th key": in full pinyin the `'` separator is
//! not a key; in double pinyin and English every byte is. Decoding still covers the whole input; the caret only
//! decides where a new key goes and which one backspace removes.
use super::*;

impl Engine {
    /// 当前方案与状态下能否在组合串里移动光标（九键、五笔、手写、v 模式、u 模式不支持）。
    /// Whether the caret can move inside the composition (not for keypad, wubi, handwriting, the v or u mode).
    fn caret_editable(&self) -> bool {
        let raw_schema = matches!(self.schema, Schema::Pinyin | Schema::Shuangpin(_) | Schema::English);
        let v_mode = self.schema == Schema::Pinyin && self.options.calculator && self.consumed == 0
            && self.raw.strip_prefix('v').is_some_and(|b| b.chars().next().is_some_and(crate::special::v_accepts));
        raw_schema && !v_mode && self.u_code().is_none() && !self.predicting
    }

    fn is_key(&self, b: u8) -> bool {
        self.schema != Schema::Pinyin || b != b'\''
    }

    /// (光标前的按键数, 未选部分的按键总数)。 (Keys before the caret, keys in the unselected rest.)
    fn caret_keys(&self) -> (usize, usize) {
        let start = self.consumed.min(self.raw.len());
        let end = self.caret.unwrap_or(self.raw.len()).clamp(start, self.raw.len());
        let count = |r: &[u8]| r.iter().filter(|&&b| self.is_key(b)).count();
        (count(&self.raw.as_bytes()[start..end]), count(&self.raw.as_bytes()[start..]))
    }

    /// 第 `keys` 个按键之后的字节位置（0 = 未选部分开头）。 Byte position right after the `keys`-th key (0 = start of the rest).
    fn raw_pos_after(&self, keys: usize) -> usize {
        let start = self.consumed.min(self.raw.len());
        if keys == 0 {
            return start;
        }
        let mut seen = 0;
        for (i, &b) in self.raw.as_bytes().iter().enumerate().skip(start) {
            if self.is_key(b) {
                seen += 1;
                if seen == keys {
                    return i + 1;
                }
            }
        }
        self.raw.len()
    }

    /// 把光标放到第 `keys` 个按键之后；到了末尾就记为 None。 Put the caret after the `keys`-th key; None at the end.
    fn place_caret(&mut self, keys: usize) {
        let (_, total) = self.caret_keys();
        self.caret = (keys < total).then(|| self.raw_pos_after(keys));
    }

    /// 在光标处放入一个按键（光标在末尾时就是追加）。 Put a key at the caret (an append when it is at the end).
    pub(super) fn insert_key(&mut self, c: char) {
        match self.caret {
            Some(at) if at < self.raw.len() => {
                self.raw.insert(at, c);
                if self.near.len() > at {
                    self.near.insert(at, Near::NONE);
                }
                self.caret = Some(at + 1);
            }
            _ => {
                self.caret = None;
                self.raw.push(c);
            }
        }
    }

    /// 选定一段：整串选完就上屏，否则吃掉这一段继续拼。光标回到末尾，之后的退格先撤回这次选择。
    /// Record a pick: commit when it covers everything, else consume the span and keep composing. The caret returns
    /// to the end and the next backspace undoes this pick first.
    pub(super) fn push_selection(&mut self, sel: Selection, covers_all: bool, end: usize) {
        self.caret = None;
        self.shape = None;
        if covers_all {
            self.selected.push(sel);
            self.commit_selected();
            return;
        }
        self.consumed += self.units_for(end);
        self.selected.push(sel);
        // 跳过紧跟着的分隔符。 Skip a following separator.
        self.skip_separators();
        self.undo_picks = true;
        self.refresh();
    }

    /// 撤回最近一次选词，把它吃掉的按键还给组合串。 Undo the latest pick, returning its keys to the composition.
    fn unpick(&mut self) -> bool {
        let Some(sel) = self.selected.pop() else { return false };
        self.consumed = sel.consumed_before;
        self.refresh();
        true
    }

    /// 退格的编辑部分：刚选过词就撤回选择；光标在中间就删光标前的按键。返回 false 时按原来的末尾退格处理。
    /// The editing part of backspace: right after a pick it undoes the pick; with the caret inside it deletes the key
    /// before the caret. False leaves the ordinary end-of-input backspace to the caller.
    pub(super) fn backspace_edit(&mut self) -> bool {
        if self.undo_picks && self.caret.is_none() && self.unpick() {
            return true;
        }
        self.undo_picks = false;
        if self.caret.is_none() || !self.caret_editable() {
            return false;
        }
        let (before, _) = self.caret_keys();
        if before == 0 {
            // 光标在未选部分开头：撤回前面选的词，光标留在原处。 At the start of the rest: undo the previous pick.
            self.unpick();
            return true;
        }
        let key = self.raw_pos_after(before) - 1;
        self.raw.remove(key);
        if self.near.len() > key {
            self.near.remove(key);
        }
        self.place_caret(before - 1);
        self.after_edit();
        true
    }

    /// 删除光标后的一个按键（Delete 键）；没有组合时返回 false。 Delete the key after the caret (the Delete key).
    pub fn delete_forward(&mut self) -> bool {
        if !self.is_composing() {
            return false;
        }
        self.undo_picks = false;
        self.end_shape();
        let Some(at) = self.caret.filter(|_| self.caret_editable()) else { return true };
        let Some(key) = (at..self.raw.len()).find(|&i| self.is_key(self.raw.as_bytes()[i])) else { return true };
        let (before, _) = self.caret_keys();
        self.raw.remove(key);
        if self.near.len() > key {
            self.near.remove(key);
        }
        self.place_caret(before);
        self.after_edit();
        true
    }

    fn after_edit(&mut self) {
        if self.raw.len() <= self.consumed && self.selected.is_empty() {
            self.clear();
        } else {
            self.refresh();
        }
    }

    /// 光标左右移动 `delta` 个按键；返回 false 表示这里不能移动（界面照常处理方向键）。`delta` 为 0 只做询问。
    /// Move the caret by `delta` keys; false when it can't move here (the UI handles the arrow as usual). A zero
    /// `delta` only asks.
    pub fn move_caret(&mut self, delta: i32) -> bool {
        if !self.is_composing() || !self.caret_editable() {
            return false;
        }
        if delta == 0 {
            return true;
        }
        self.end_shape();
        self.undo_picks = false;
        let (before, total) = self.caret_keys();
        let to = (before as i64 + delta as i64).clamp(0, total as i64) as usize;
        self.place_caret(to);
        true
    }

    /// 音节边界（按键数）：开头、末尾，以及显示串里紧跟分隔符 `'` 的位置。纠错与双拼全拼同样按显示的音节走。
    /// Syllable stops in keys: the start, the end, and every key shown right after a `'`. Corrected and double
    /// pinyin pieces step by their shown syllables too.
    fn syllable_stops(&self) -> Vec<usize> {
        let (_, total) = self.caret_keys();
        let shown: Vec<char> = self.preedit.chars().collect();
        (0..=total)
            .filter(|&k| {
                k == 0 || k == total
                    || self.caret_map.get(k).is_some_and(|&at| at > 0 && shown.get(at - 1) == Some(&'\''))
            })
            .collect()
    }

    /// 按音节移动光标 `delta` 次（Ctrl／Option + 方向键）；不能移动时返回 false。
    /// Move the caret by `delta` syllables (Ctrl / Option + arrows); false where the caret can't move.
    pub fn move_caret_syllable(&mut self, delta: i32) -> bool {
        if !self.is_composing() || !self.caret_editable() {
            return false;
        }
        self.end_shape();
        self.undo_picks = false;
        let stops = self.syllable_stops();
        let (mut at, _) = self.caret_keys();
        for _ in 0..delta.unsigned_abs() {
            at = if delta < 0 {
                stops.iter().rev().find(|&&k| k < at).copied().unwrap_or(0)
            } else {
                stops.iter().find(|&&k| k > at).copied().unwrap_or(at)
            };
        }
        self.place_caret(at);
        true
    }

    /// 删掉光标前（`forward` 为 true 时为光标后）的一个音节；光标在开头时向前删就撤回前一次选词。
    /// Delete the syllable before the caret (after it when `forward`); backwards at the start undoes the previous pick.
    pub fn delete_syllable(&mut self, forward: bool) -> bool {
        if !self.is_composing() {
            return false;
        }
        if !forward && self.undo_picks && self.caret.is_none() && self.unpick() {
            return true;
        }
        self.undo_picks = false;
        // 筛选中向前删：去掉整个字形码。 Backwards during the filter: drop the whole shape code.
        if self.end_shape() && !forward {
            return true;
        }
        if !self.caret_editable() {
            return false;
        }
        let stops = self.syllable_stops();
        let (before, _) = self.caret_keys();
        let (from, to) = if forward {
            (before, stops.iter().find(|&&k| k > before).copied().unwrap_or(before))
        } else {
            (stops.iter().rev().find(|&&k| k < before).copied().unwrap_or(0), before)
        };
        if from == to {
            // 开头向前删：与退格一样撤回前一次选词，光标留在原处。 At the start: undo the previous pick, like backspace.
            if !forward {
                self.unpick();
            }
            return true;
        }
        let (a, b) = (self.raw_pos_after(from), self.raw_pos_after(to));
        self.raw.replace_range(a..b, "");
        if self.near.len() > a {
            self.near.drain(a..b.min(self.near.len()));
        }
        self.place_caret(from);
        self.after_edit();
        true
    }

    /// 把光标放到显示串第 `index` 个字符附近最近的按键边界（点按组合串、Home/End 用）。
    /// Put the caret at the key boundary nearest display character `index` (taps on the preedit, Home/End).
    pub fn set_caret(&mut self, index: usize) -> bool {
        if !self.is_composing() || !self.caret_editable() || self.caret_map.is_empty() {
            return false;
        }
        self.undo_picks = false;
        self.end_shape();
        let (_, total) = self.caret_keys();
        let best = (0..=total.min(self.caret_map.len() - 1))
            .min_by_key(|&k| (self.caret_map[k].abs_diff(index), std::cmp::Reverse(k)))
            .unwrap_or(total);
        self.place_caret(best);
        true
    }

    /// 快照里的光标：显示串中的字符下标，在末尾时为 None。 The snapshot caret: display index, None at the end.
    pub(super) fn caret_shown(&self) -> Option<usize> {
        self.caret?;
        let (before, _) = self.caret_keys();
        self.caret_map.get(before).copied()
    }

    /// 以词取字：第 `index` 个候选只取首字（`last` 为 false）或末字，吃掉这个词对应的全部输入。
    /// 单字候选等同普通选择；英文与联想词不适用，返回 false。
    /// Take one character of a word: the first (`last` false) or the last char of candidate `index`, consuming all
    /// the input the word covers. A single-char candidate is an ordinary pick; English and predictions return false.
    pub fn select_char(&mut self, index: usize, last: bool) -> bool {
        if self.predicting || self.schema == Schema::English {
            return false;
        }
        let Some(c) = self.cands.get(index).cloned() else { return false };
        let text = match &c.action {
            Action::Pinyin(p) => p.text.clone(),
            Action::Table { text } => text.clone(),
        };
        let pick = if last { text.chars().last() } else { text.chars().next() };
        let Some(ch) = pick.filter(|_| text.chars().count() > 1) else {
            return self.select(index);
        };
        self.drop_predictions();
        match c.action {
            Action::Pinyin(cand) => {
                let total = self.graph_len();
                // 取出的字按它自己的读音学习（我们 取「们」记 men = 们）。 The taken char is learned under its own syllable.
                let syllable = if text.chars().count() == cand.key.len() {
                    if last { cand.key.last() } else { cand.key.first() }
                } else {
                    None
                };
                let sel = Selection {
                    text: ch.to_string(),
                    words: syllable.map(|&s| vec![(vec![s], ch.to_string())]).unwrap_or_default(),
                    consumed_before: self.consumed,
                    raw_before: self.raw.len(),
                    intentional: true,
                };
                self.push_selection(sel, cand.end >= total, cand.end);
            }
            Action::Table { .. } => {
                let mut all: String = self.selected.iter().map(|s| s.text.as_str()).collect();
                all.push(ch);
                let out = self.out(&all);
                self.commit.push_str(&out);
                self.finish_composition();
                self.predict_depth = 0;
                self.predict_next(&all);
            }
        }
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use weave_dict::lexicon::{Builder, Kind};

    fn engine() -> Engine {
        let mut b = Builder::new(Kind::Pinyin);
        let k = |s: &str| weave_dict::syllable::parse_seq(s).unwrap();
        b.insert(&k("wo men"), "我们", 10);
        b.insert(&k("zou le"), "走了", 30);
        b.insert(&k("zou"), "走", 20);
        b.insert(&k("le"), "了", 20);
        b.insert(&k("wo"), "我", 20);
        b.insert(&k("men"), "们", 40);
        b.insert(&k("xi an"), "西安", 10);
        b.insert(&k("xian"), "现", 10);
        b.insert(&k("xin"), "新", 10);
        b.insert(&k("ni hao"), "你好", 10);
        b.insert(&k("ni"), "你", 20);
        b.insert(&k("hao"), "好", 20);
        b.insert(&k("zhong guo"), "中国", 10);
        b.insert(&k("guo"), "国", 10);
        let mut e = Engine::with_lexicons(Some(Lexicon::from_bytes(b.build()).unwrap()), None, None);
        e.set_schema(Schema::Pinyin);
        e.options.emoji = false;
        e.options.prediction = false;
        e
    }

    fn typing(e: &mut Engine, s: &str) {
        for c in s.chars() {
            e.input_char(c);
        }
    }

    fn pick(e: &mut Engine, text: &str) {
        let i = e.candidates(0, 50).iter().position(|c| c.text == text).unwrap_or_else(|| panic!("no {text}"));
        assert!(e.select(i));
    }

    #[test]
    fn backspace_after_a_partial_pick_returns_it_to_pinyin() {
        let mut e = engine();
        typing(&mut e, "womenzoule");
        pick(&mut e, "我们");
        assert!(e.snapshot().preedit.starts_with("我们"));
        assert!(e.backspace());
        let s = e.snapshot();
        assert_eq!(s.preedit, "wo'men'zou'le");
        assert_eq!(s.candidates[0].text, "我们走了");
        // 再退格才删字母。 The next backspace deletes a letter.
        assert!(e.backspace());
        assert_eq!(e.snapshot().preedit, "wo'men'zou'l");
    }

    #[test]
    fn consecutive_backspaces_undo_picks_in_reverse_order() {
        let mut e = engine();
        typing(&mut e, "womenzoule");
        pick(&mut e, "我们");
        pick(&mut e, "走");
        assert_eq!(e.snapshot().preedit, "我们走le");
        e.backspace();
        assert_eq!(e.snapshot().preedit, "我们zou'le");
        e.backspace();
        assert_eq!(e.snapshot().preedit, "wo'men'zou'le");
    }

    #[test]
    fn typing_after_a_pick_makes_backspace_delete_letters_again() {
        let mut e = engine();
        typing(&mut e, "womenzou");
        pick(&mut e, "我们");
        typing(&mut e, "le");
        e.backspace();
        assert_eq!(e.snapshot().preedit, "我们zou'l");
    }

    #[test]
    fn caret_moves_inside_the_pinyin_and_inserts_there() {
        let mut e = engine();
        typing(&mut e, "nhao");
        assert_eq!(e.snapshot().caret, None);
        assert!(e.move_caret(-3));
        // 「n'|hao」：第一个按键之后，显示在分隔符后面。 After the first key, shown past the separator.
        assert_eq!(e.snapshot().caret, Some(2));
        e.input_char('i');
        let s = e.snapshot();
        assert_eq!(s.preedit, "ni'hao");
        assert_eq!(s.caret, Some(3), "caret follows the inserted key past the separator");
        assert_eq!(s.candidates[0].text, "你好");
        // 移到末尾就回到 None。 Moving to the end gives None.
        assert!(e.move_caret(10));
        assert_eq!(e.snapshot().caret, None);
    }

    #[test]
    fn backspace_and_delete_work_around_the_caret() {
        let mut e = engine();
        typing(&mut e, "nihaoo");
        e.move_caret(-1);
        assert!(e.delete_forward());
        assert_eq!(e.snapshot().preedit, "ni'hao");
        assert_eq!(e.snapshot().caret, None, "nothing after the caret any more");
        e.move_caret(-3);
        // 「ni|hao」退格删 i。 Backspace at ni|hao deletes the i.
        e.backspace();
        let s = e.snapshot();
        assert!(s.preedit.starts_with('n') && s.preedit.ends_with("hao"), "{}", s.preedit);
        assert_eq!(s.caret, Some(2), "after the separator that follows n");
        e.input_char('i');
        assert_eq!(e.snapshot().candidates[0].text, "你好");
    }

    #[test]
    fn moving_left_stops_at_the_start_and_backspace_there_undoes_a_pick() {
        let mut e = engine();
        typing(&mut e, "womenzoule");
        pick(&mut e, "我们");
        assert!(e.move_caret(-100));
        assert_eq!(e.snapshot().caret, Some(2), "right after the picked text");
        assert!(e.backspace());
        let s = e.snapshot();
        assert_eq!(s.preedit, "wo'men'zou'le");
        assert_eq!(s.caret, Some(7), "the caret stays before zou");
    }

    #[test]
    fn tapping_the_preedit_places_the_caret_at_the_nearest_key() {
        let mut e = engine();
        typing(&mut e, "zhongguo");
        assert_eq!(e.snapshot().preedit, "zhong'guo");
        assert!(e.set_caret(3));
        assert_eq!(e.snapshot().caret, Some(3));
        assert!(e.set_caret(6));
        assert_eq!(e.snapshot().caret, Some(6), "after the separator, before g");
        assert!(e.set_caret(99));
        assert_eq!(e.snapshot().caret, None);
    }

    #[test]
    fn the_caret_jumps_and_deletes_by_syllable() {
        let mut e = engine();
        typing(&mut e, "womenzoule");
        assert_eq!(e.snapshot().preedit, "wo'men'zou'le");
        assert!(e.move_caret_syllable(-1));
        assert_eq!(e.snapshot().caret, Some(11), "before le");
        assert!(e.move_caret_syllable(-2));
        assert_eq!(e.snapshot().caret, Some(3), "before men");
        assert!(e.move_caret_syllable(-5));
        assert_eq!(e.snapshot().caret, Some(0));
        assert!(e.move_caret_syllable(1));
        assert!(e.delete_syllable(true));
        let s = e.snapshot();
        assert_eq!(s.preedit, "wo'zou'le");
        assert_eq!(s.caret, Some(3), "stays between wo and zou");
        assert!(e.delete_syllable(false));
        assert_eq!(e.snapshot().preedit, "zou'le");
        assert_eq!(e.snapshot().caret, Some(0));
        assert!(e.move_caret_syllable(9));
        assert_eq!(e.snapshot().caret, None);
        assert!(e.delete_syllable(false));
        assert_eq!(e.snapshot().preedit, "zou");
        assert!(e.delete_syllable(false));
        assert!(!e.is_composing(), "the last syllable empties the composition");
    }

    #[test]
    fn double_pinyin_steps_by_the_shown_full_spellings() {
        let mut e = engine();
        e.set_schema(Schema::from_key("shuangpin:xiaohe").unwrap());
        typing(&mut e, "vsgo");
        assert_eq!(e.snapshot().preedit, "zhong'guo");
        assert!(e.move_caret_syllable(-1));
        assert_eq!(e.snapshot().caret, Some(6), "before guo");
        assert!(e.delete_syllable(false));
        assert_eq!(e.snapshot().preedit, "guo");
    }

    #[test]
    fn deleting_a_syllable_right_after_a_pick_undoes_the_pick() {
        let mut e = engine();
        typing(&mut e, "womenzoule");
        pick(&mut e, "我们");
        assert!(e.delete_syllable(false));
        assert_eq!(e.snapshot().preedit, "wo'men'zou'le");
        assert!(e.delete_syllable(false));
        assert_eq!(e.snapshot().preedit, "wo'men'zou");
        // 光标在未选部分开头时向前删同样撤回选词。 Backwards at the start of the rest undoes a pick too.
        pick(&mut e, "我们");
        e.move_caret_syllable(-9);
        assert!(e.delete_syllable(false));
        assert_eq!(e.snapshot().preedit, "wo'men'zou");
    }

    #[test]
    fn caret_is_refused_where_editing_does_not_apply() {
        let mut e = engine();
        assert!(!e.move_caret(-1), "nothing composing");
        e.set_schema(Schema::Keypad(crate::t9::Grouping::Nine));
        e.input_char('9');
        assert!(!e.move_caret(-1));
    }

    #[test]
    fn select_char_takes_the_first_or_last_char_and_consumes_the_word() {
        let mut e = engine();
        typing(&mut e, "womenzoule");
        let i = e.candidates(0, 50).iter().position(|c| c.text == "我们").unwrap();
        assert!(e.select_char(i, true));
        assert_eq!(e.snapshot().preedit, "们zou'le");
        let i = e.candidates(0, 50).iter().position(|c| c.text == "走了").unwrap();
        assert!(e.select_char(i, false));
        assert_eq!(e.snapshot().commit, "们走");
        assert!(!e.is_composing());
        // 取出的字按自己的读音学到了。 Each taken char is learned under its own syllable.
        let key = |s: &str| weave_dict::syllable::parse_seq(s).unwrap();
        assert!(e.user_pinyin.get(&key("men"), "们").is_some() && e.user_pinyin.get(&key("zou"), "走").is_some());
    }

    #[test]
    fn select_char_on_a_single_char_is_an_ordinary_pick() {
        let mut e = engine();
        typing(&mut e, "ni");
        let i = e.candidates(0, 50).iter().position(|c| c.text == "你").unwrap();
        assert!(e.select_char(i, true));
        assert_eq!(e.snapshot().commit, "你");
    }
}
