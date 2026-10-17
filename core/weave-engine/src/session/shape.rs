//! 字形筛选（辅助码）：拼音打完后按 ` 再打字形码，只留下首字或末字对得上的候选，次序不变。字形码可以是笔画
//! （h 横 s 竖 p 撇 n 点 z 折），也可以是部件读音的开头（时 = 日寸：r、rc）；大写的 HSPNZ 只算笔画（笔画键发的就是它）。
//! 字形码不进拼音，退格先删它；选词、移动光标都会结束筛选。
//! The shape filter: after the pinyin, a ` starts a shape code and only candidates whose first or last char fits stay,
//! in their original order.
//! The code is strokes (h s p n z) or the starts of component readings (时 = 日寸: r, rc); capital HSPNZ count as
//! strokes only, which is what the stroke keys send. The code stays out of the pinyin; backspace removes it first, and a pick or a caret move ends
//! the filter.
//!
//! 同一张表也用来拆字查字（u 模式）：全拼里 u 开头（u 不是声母），后面打部件读音或笔画，列出对得上的字并注出读音
//! （u + rirri = 晶，ukoukoukou = 品，uhs = 十）。
//! The same table looks chars up by their parts (the u mode): in full pinyin, a u first (u is no initial) and then
//! component readings or strokes list the fitting chars with their readings (u + ririri = 晶, ukoukoukou = 品, uhs = 十).
use super::*;

/// 开始字形码的键。 The key that starts a shape code.
pub(super) const SHAPE_KEY: char = '`';
/// 字形码最长几个字母（再长也不会更准）。 Longest shape code; more letters would not narrow it further.
const MAX_SHAPE: usize = 8;

impl Engine {
    /// 字形码相关的按键；None 表示交给方案本身处理。 Keys of the shape code; None leaves the key to the schema.
    pub(super) fn shape_key(&mut self, c: char) -> Option<bool> {
        if !self.schema.is_pinyin_family() || self.shapes.is_none() {
            return None;
        }
        if let Some(code) = self.shape.as_mut() {
            if c.is_ascii_alphabetic() {
                if code.len() < MAX_SHAPE {
                    code.push(if "HSPNZ".contains(c) { c } else { c.to_ascii_lowercase() });
                    self.refresh();
                }
                return Some(true);
            }
            if c == SHAPE_KEY {
                return Some(true);
            }
            // 九键接着按数字：结束筛选、继续打拼音；其他字符（标点）照常交给界面。
            // More keypad digits end the filter and go on with the pinyin; anything else (punctuation) is the UI's.
            self.shape = None;
            if matches!(self.schema, Schema::Keypad(_)) {
                return None;
            }
            self.refresh();
            return Some(false);
        }
        // 有拼音候选时才开始（v 模式的计算结果、联想词不筛）。 Only with pinyin candidates (not v mode or predictions).
        let has_pinyin = self.cands.iter().any(|c| matches!(c.action, Action::Pinyin(_)));
        if c != SHAPE_KEY || self.predicting || !has_pinyin {
            return None;
        }
        self.shape = Some(String::new());
        self.caret = None;
        self.refresh();
        Some(true)
    }

    /// 退格先删字形码，删空后再按一次退出筛选。 Backspace removes the code first, then leaves the filter.
    pub(super) fn shape_backspace(&mut self) -> bool {
        let Some(code) = self.shape.as_mut() else { return false };
        if code.pop().is_none() {
            self.shape = None;
        }
        self.refresh();
        true
    }

    /// 结束筛选（编辑光标前）；返回之前是否在筛选。 End the filter before a caret edit; whether it was on.
    pub(super) fn end_shape(&mut self) -> bool {
        if self.shape.take().is_none() {
            return false;
        }
        self.refresh();
        true
    }

    /// u 模式下 u 之后的字形码；不在 u 模式为 None。单独一个 u 仍照常解码（纠成 wu、yu）。
    /// The shape code after the u in the u mode, None otherwise. A lone u still decodes as usual (to wu, yu).
    pub(super) fn u_code(&self) -> Option<&str> {
        if self.schema != Schema::Pinyin || self.consumed != 0 || !self.selected.is_empty() || self.shapes.is_none() {
            return None;
        }
        self.raw.strip_prefix('u').filter(|c| !c.is_empty() && c.bytes().all(|b| b.is_ascii_lowercase()))
    }

    /// u 模式：先放刚好拼出的英文词（us、use），再按贴合程度、常用程度列出拆字查到的字，注上带声调的读音。
    /// The u mode: an English word the letters spell (us, use) first, then the chars found by shape, by how closely
    /// they fit and how common they are, each noted with its toned reading.
    pub(super) fn refresh_u_mode(&mut self) -> bool {
        let Some(code) = self.u_code().map(str::to_owned) else { return false };
        if self.shapes.as_ref().is_some_and(|s| s.is_packed()) {
            self.shapes = self.shapes.take().map(|s| s.into_owned());
        }
        self.preedit = self.raw.clone();
        let table = |text: String, comment: String| Cand {
            view: CandidateView { pinyin: String::new(), cloud: false, text: text.clone(), comment, user: false },
            action: Action::Table { text },
        };
        if let Some(en) = self.english.as_ref() {
            let word = table::code_key(&self.raw).and_then(|key| {
                let node = en.find(&key)?;
                en.entries(node).map(|e| en.text(e.text_id, &key)).find(|t| *t == self.raw)
            });
            self.cands.extend(word.map(|w| table(w, String::new())));
        }
        let mut found = Vec::new();
        if let Some(shapes) = self.shapes.as_ref() {
            shapes.search(&code, |c, tier| found.push((tier, c)));
        }
        if let Some(lex) = self.pinyin.as_ref() {
            let readings = self.readings.get_or_insert_with(|| crate::predict::Readings::build(lex));
            let mut buf = [0u8; 4];
            let mut ranked: Vec<(u8, u16, char)> = found
                .into_iter()
                .map(|(tier, c)| (tier, readings.word_cost(lex, c.encode_utf8(&mut buf)).unwrap_or(u16::MAX), c))
                .collect();
            ranked.sort_unstable();
            found = ranked.into_iter().map(|(tier, _, c)| (tier, c)).collect();
        }
        self.cands_more = found.len() > self.cand_cap;
        for (_, c) in found.into_iter().take(self.cand_cap) {
            let text = c.to_string();
            let comment = crate::tones::pinyin(&text, None, true).unwrap_or_default();
            self.cands.push(table(text, comment));
        }
        true
    }

    pub(super) fn shape_filtering(&self) -> bool {
        self.shapes.is_some() && self.shape.as_deref().is_some_and(|c| !c.is_empty())
    }

    /// 只留下首字或末字对得上的候选。按原来的次序：常用词（中国，末字对上）不该排在生僻单字之后。
    /// Keep candidates whose first or last char fits, in the original order: a common word fitting by its last char
    /// (中国) should not fall behind rare single chars.
    pub(super) fn filter_by_shape(&mut self) {
        if !self.shape_filtering() {
            return;
        }
        let (Some(shapes), Some(code)) = (self.shapes.as_ref(), self.shape.as_deref()) else { return };
        let fits = |c: Option<char>| c.is_some_and(|c| shapes.matches(c, code));
        self.cands.retain(|c| {
            let text = match &c.action {
                Action::Pinyin(p) => p.text.as_str(),
                Action::Table { text } => text.as_str(),
            };
            fits(text.chars().next()) || fits(text.chars().last())
        });
        self.cands_more = false;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use weave_dict::lexicon::{Builder, Kind};
    use weave_dict::shape::{ShapeBuilder, Shapes};

    fn engine() -> Engine {
        let mut b = Builder::new(Kind::Pinyin);
        let k = |s: &str| weave_dict::syllable::parse_seq(s).unwrap();
        b.insert(&k("shi"), "是", 10);
        b.insert(&k("shi"), "事", 20);
        b.insert(&k("shi"), "时", 30);
        b.insert(&k("shi"), "十", 40);
        b.insert(&k("shi shi"), "事实", 15);
        b.insert(&k("shi shi"), "实时", 25);
        b.insert(&k("shi hou"), "时候", 10);
        b.insert(&k("hou"), "后", 10);
        let mut e = Engine::with_lexicons(Some(Lexicon::from_bytes(b.build()).unwrap()), None, None);
        e.set_schema(Schema::Pinyin);
        e.options.emoji = false;
        e.options.prediction = false;
        let mut s = ShapeBuilder::default();
        s.add('是', Some("SZHHHSHPN"), Some("ri'pi"));
        s.add('时', Some("SZHHHSN"), Some("ri'cun"));
        s.add('事', Some("HSZHZHHS"), Some("heng'kou'xue"));
        s.add('十', Some("HS"), Some("heng'shu"));
        s.add('实', Some("NNZHHPN"), Some("bao'tou"));
        e.shapes = Some(Shapes::from_bytes(s.build()).unwrap());
        e
    }

    fn typing(e: &mut Engine, s: &str) -> Snapshot {
        for c in s.chars() {
            e.input_char(c);
        }
        e.snapshot()
    }

    fn texts(s: &Snapshot) -> Vec<String> {
        s.candidates.iter().map(|c| c.text.clone()).collect()
    }

    #[test]
    fn a_shape_code_after_the_pinyin_narrows_the_candidates() {
        let mut e = engine();
        let s = typing(&mut e, "shi`");
        assert_eq!(s.preedit, "shi`");
        assert!(texts(&s).contains(&"十".to_string()), "a bare ` filters nothing yet");
        let s = typing(&mut e, "r");
        assert_eq!(s.preedit, "shi`r");
        assert_eq!(texts(&s), ["是", "时"]);
        assert_eq!(texts(&typing(&mut e, "c")), ["时"]);
        // 退格先删字形码，再退出筛选，最后才删拼音。 Backspace: the code, then the filter, then the pinyin.
        e.backspace();
        assert_eq!(texts(&e.snapshot()), ["是", "时"]);
        e.backspace();
        e.backspace();
        let s = e.snapshot();
        assert_eq!(s.preedit, "shi");
        assert!(texts(&s).contains(&"十".to_string()));
        e.backspace();
        assert_eq!(e.snapshot().preedit, "sh");
    }

    #[test]
    fn strokes_filter_too_and_picks_commit_the_filtered_candidate() {
        let mut e = engine();
        assert_eq!(texts(&typing(&mut e, "shi`hs")), ["事", "十"]);
        assert!(e.select(1));
        assert_eq!(e.snapshot().commit, "十");
        assert!(!e.is_composing());
        // 不在组合中时 ` 交给界面。 Not composing: the ` is the UI's.
        assert!(!e.input_char('`'));
    }

    #[test]
    fn words_match_by_their_first_or_last_char_in_their_usual_order() {
        let mut e = engine();
        let s = typing(&mut e, "shishi`bt");
        // 事实 末字对上，实时 首字对上。 事实 fits by its last char, 实时 by its first.
        assert_eq!(texts(&s), ["事实", "实时"]);
        // 部分选词后筛选结束，剩下的拼音照常。 A partial pick ends the filter and the rest composes as usual.
        let mut e = engine();
        typing(&mut e, "shihou`rc");
        let i = e.snapshot().candidates.iter().position(|c| c.text == "时").unwrap();
        e.select(i);
        let s = e.snapshot();
        assert_eq!(s.preedit, "时hou");
        assert_eq!(texts(&s)[0], "后");
    }

    #[test]
    fn the_keypad_takes_strokes_and_more_digits_end_the_filter() {
        let mut e = engine();
        e.set_schema(Schema::Keypad(crate::t9::Grouping::Nine));
        let s = typing(&mut e, "744`H");
        assert_eq!(texts(&s), ["事", "十"]);
        assert_eq!(s.preedit, "shi`H");
        // 接着按数字：结束筛选，数字照常进拼音。 More digits end the filter and go to the pinyin.
        let s = typing(&mut e, "468");
        assert!(!s.preedit.contains('`'), "{}", s.preedit);
        assert_eq!(texts(&s)[0], "时候");
    }

    #[test]
    fn the_u_mode_looks_chars_up_by_their_parts_or_strokes() {
        let mut e = engine();
        e.options.emoji = true;
        // 单独的 u 照常解码。 A lone u decodes as usual.
        typing(&mut e, "u");
        assert!(e.cands.iter().all(|c| c.view.comment.is_empty()));
        let s = typing(&mut e, "ric");
        assert_eq!(s.preedit, "uric");
        assert_eq!(texts(&s), ["时"]);
        assert_eq!(s.candidates[0].comment, "shí");
        // 打全读音的排在只打开头的前面，同一档按常用程度。 Full readings first, then by frequency.
        let mut e = engine();
        e.shapes = Some({
            let mut s = ShapeBuilder::default();
            s.add('是', None, Some("ri'pi"));
            s.add('是', None, Some("ri'ri'pi"));
            s.add('时', None, Some("ri'cun"));
            s.add('昌', None, Some("ri'ri"));
            Shapes::from_bytes(s.build()).unwrap()
        });
        assert_eq!(texts(&typing(&mut e, "uri")), ["是", "时", "昌"]);
        assert_eq!(texts(&typing(&mut e, "ri")), ["昌", "是"], "昌 spelled out beats the more common 是");
        assert!(!e.move_caret(-1), "no caret inside the u mode");
        assert!(e.select(0));
        assert_eq!(e.snapshot().commit, "昌");
        // 双拼、五笔里 u 另有用处。 Other schemas keep their u.
        let mut e = engine();
        e.set_schema(Schema::Shuangpin(crate::shuangpin::SchemeId::Xiaohe));
        assert!(texts(&typing(&mut e, "uric")) != ["时"]);
    }

    #[test]
    fn caret_moves_and_punctuation_end_the_filter() {
        let mut e = engine();
        typing(&mut e, "shi`r");
        assert!(e.move_caret(-1));
        let s = e.snapshot();
        assert_eq!((s.preedit.as_str(), s.caret), ("shi", Some(2)));
        typing(&mut e, "`r");
        assert_eq!(e.snapshot().preedit, "shi`r", "starting the filter puts the caret back at the end");
        assert!(!e.input_char(','));
        let s = e.snapshot();
        assert_eq!(s.preedit, "shi");
        assert!(s.composing);
    }
}
