//! 输入会话：界面层唯一需要对接的状态机。
//! Input session: the single state machine the UI talks to.
//!
//! 界面把按键交给 [`Engine`]，然后读取 [`Snapshot`]（上屏文字、组合串、候选）。
//! The UI feeds keys to [`Engine`] and reads back a [`Snapshot`] (commit text, preedit,
//! candidates).

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use weave_dict::lexicon::Lexicon;
use weave_dict::syllable::{self, SyllableId};

use crate::convert::{Emoji, Traditional};
use crate::decoder::{CandKind, Candidate, Decoder, Lattice, LmParams};
use crate::graph::{self, FuzzyOptions, Letters, SyllableGraph};
use crate::shuangpin::{self, SchemeId};
use crate::t9::{self, T9Input, T9Unit};
use crate::table;
use crate::userdict::UserDict;
use weave_dict::gram::Gram;

/// 输入方案。 Input schema.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Schema {
    Pinyin,
    Shuangpin(SchemeId),
    T9,
    Wubi86,
    English,
}

impl Schema {
    /// 方案标识："pinyin" / "shuangpin:xiaohe" / "t9" / "wubi86" / "english"。
    pub fn from_key(k: &str) -> Option<Self> {
        match k {
            "pinyin" => Some(Schema::Pinyin),
            "t9" => Some(Schema::T9),
            "wubi86" => Some(Schema::Wubi86),
            "english" => Some(Schema::English),
            _ => k
                .strip_prefix("shuangpin:")
                .and_then(SchemeId::from_key)
                .map(Schema::Shuangpin),
        }
    }

    pub fn key(&self) -> String {
        match self {
            Schema::Pinyin => "pinyin".into(),
            Schema::T9 => "t9".into(),
            Schema::Wubi86 => "wubi86".into(),
            Schema::English => "english".into(),
            Schema::Shuangpin(s) => format!("shuangpin:{}", s.key()),
        }
    }

    fn is_pinyin_family(&self) -> bool {
        matches!(self, Schema::Pinyin | Schema::Shuangpin(_) | Schema::T9)
    }
}

/// 可调选项。 Tunable options.
#[derive(Clone, Debug)]
pub struct Options {
    pub fuzzy: FuzzyOptions,
    /// 五笔四码唯一时自动上屏。 Wubi: auto-commit a unique 4-code match.
    pub wubi_auto_commit: bool,
    /// 五笔 z 键拼音反查。 Wubi: `z` + pinyin reverse lookup.
    pub wubi_pinyin_lookup: bool,
    /// 五笔显示编码补全。 Wubi: show completions with remaining code.
    pub wubi_completion: bool,
    /// 繁体输出。 Output traditional Chinese.
    pub traditional: bool,
    /// 表情联想。 Emoji suggestions after matching words.
    pub emoji: bool,
    /// 搭配模型权重（0 关闭）与未命中基线（nat）。 Collocation model weight (0 = off) and miss baseline.
    pub lm_weight: f32,
    pub lm_baseline: f32,
}

impl Default for Options {
    fn default() -> Self {
        Options {
            fuzzy: FuzzyOptions::default(),
            wubi_auto_commit: true,
            wubi_pinyin_lookup: true,
            wubi_completion: true,
            traditional: false,
            emoji: true,
            lm_weight: 0.25,
            lm_baseline: 12.0,
        }
    }
}

/// 用户词（管理界面）。 A user word, for the management UI.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct UserWord {
    pub pinyin: String,
    pub text: String,
    pub count: u32,
}

fn spell_key(k: &[SyllableId]) -> String {
    k.iter()
        .map(|&s| syllable::spelling(s))
        .collect::<Vec<_>>()
        .join(" ")
}

/// 候选（界面可见部分）。 A candidate as seen by the UI.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CandidateView {
    pub text: String,
    pub comment: String,
    /// 是否来自用户词库（可删除）。 Learned from the user (deletable).
    pub user: bool,
}

/// 一次按键后的完整状态。 Full state after an operation.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Snapshot {
    /// 需要上屏的文字（读取后清空）。 Text to commit (drained on read).
    pub commit: String,
    /// 组合串显示。 Preedit display.
    pub preedit: String,
    pub composing: bool,
    pub candidates: Vec<CandidateView>,
    pub total_candidates: usize,
    /// 九键左侧拼音栏。 T9 pinyin column.
    pub pinyin_options: Vec<String>,
    pub schema: String,
}

#[derive(Clone, Debug)]
enum Action {
    Pinyin(Candidate),
    Table { text: String },
}

#[derive(Clone, Debug)]
struct Cand {
    view: CandidateView,
    action: Action,
}

#[derive(Clone, Debug)]
struct Selection {
    text: String,
    words: Vec<(Vec<SyllableId>, String)>,
    consumed_before: usize,
}

/// 资源文件路径。 Resource file locations.
#[derive(Clone, Debug, Default)]
pub struct Paths {
    pub pinyin_lexicon: Option<PathBuf>,
    pub wubi_lexicon: Option<PathBuf>,
    pub english_lexicon: Option<PathBuf>,
    /// 简繁与表情数据目录（STPhrases.txt / STCharacters.txt / emoji.txt）。
    /// Directory holding conversion data.
    pub convert_dir: Option<PathBuf>,
    /// 字符搭配模型（.wvg）。 Character collocation model.
    pub gram_model: Option<PathBuf>,
    /// 用户数据目录。 User data directory.
    pub user_dir: Option<PathBuf>,
}

/// 输入法引擎。 The IME engine.
pub struct Engine {
    pinyin: Option<Lexicon>,
    wubi: Option<Lexicon>,
    english: Option<Lexicon>,
    user_pinyin: UserDict,
    schema: Schema,
    pub options: Options,

    raw: String,
    t9_units: Vec<T9Unit>,
    consumed: usize,
    selected: Vec<Selection>,
    cands: Vec<Cand>,
    preedit: String,
    t9_options: Vec<T9Unit>,
    commit: String,
    last_word: Option<String>,
    wubi_reverse: Option<HashMap<String, String>>,
    convert_dir: Option<PathBuf>,
    traditional: Option<Traditional>,
    emoji: Emoji,
    gram: Option<Gram>,
}

fn open_lex(p: &Option<PathBuf>) -> Option<Lexicon> {
    p.as_ref().and_then(|p| Lexicon::open(p).ok())
}

const PAGE_INITIAL: usize = 60;

impl Engine {
    pub fn new(paths: &Paths) -> Self {
        let user_pinyin = paths
            .user_dir
            .as_ref()
            .and_then(|d| UserDict::open(&d.join("pinyin.userdb")).ok())
            .unwrap_or_default();
        Engine {
            pinyin: open_lex(&paths.pinyin_lexicon),
            wubi: open_lex(&paths.wubi_lexicon),
            english: open_lex(&paths.english_lexicon),
            user_pinyin,
            schema: Schema::Pinyin,
            options: Options::default(),
            raw: String::new(),
            t9_units: Vec::new(),
            consumed: 0,
            selected: Vec::new(),
            cands: Vec::new(),
            preedit: String::new(),
            t9_options: Vec::new(),
            commit: String::new(),
            last_word: None,
            wubi_reverse: None,
            emoji: paths
                .convert_dir
                .as_ref()
                .map(|d| Emoji::load(&d.join("emoji.txt")))
                .unwrap_or_default(),
            convert_dir: paths.convert_dir.clone(),
            traditional: None,
            gram: paths.gram_model.as_ref().and_then(|p| Gram::open(p).ok()),
        }
    }

    /// 输出前的转换（繁体）。 Output conversion (traditional).
    fn out(&mut self, s: &str) -> String {
        if !self.options.traditional {
            return s.to_owned();
        }
        if self.traditional.is_none() {
            let t = match &self.convert_dir {
                Some(d) => Traditional::load(&d.join("STPhrases.txt"), &d.join("STCharacters.txt")),
                None => Traditional::default(),
            };
            self.traditional = Some(t);
        }
        self.traditional
            .as_ref()
            .map(|t| t.convert(s))
            .unwrap_or_else(|| s.to_owned())
    }

    /// 刷新后处理：插入表情、繁体显示。 Post-process candidates: emoji, traditional display.
    fn decorate(&mut self) {
        if self.options.emoji && self.schema != Schema::English && self.schema != Schema::Wubi86 {
            let mut i = 0;
            let mut inserted = 0;
            while i < self.cands.len().min(6 + inserted) && inserted < 3 {
                let emojis: Vec<String> = self
                    .emoji
                    .lookup(&self.cands[i].view.text)
                    .iter()
                    .take(2)
                    .cloned()
                    .collect();
                for (k, e) in emojis.into_iter().enumerate() {
                    if self.cands.iter().any(|c| c.view.text == e) {
                        continue;
                    }
                    let c = Cand {
                        view: CandidateView {
                            text: e.clone(),
                            comment: String::new(),
                            user: false,
                        },
                        action: Action::Table { text: e },
                    };
                    self.cands.insert(i + 1 + k, c);
                    inserted += 1;
                }
                i += 1;
            }
        }
        if self.options.traditional {
            for idx in 0..self.cands.len() {
                let t = self.out(&self.cands[idx].view.text.clone());
                self.cands[idx].view.text = t;
            }
            let p = self.preedit.clone();
            self.preedit = self.out(&p);
        }
    }

    /// 直接用内存中的词库构建（测试用）。 Build from in-memory lexicons (tests).
    pub fn with_lexicons(
        pinyin: Option<Lexicon>,
        wubi: Option<Lexicon>,
        english: Option<Lexicon>,
    ) -> Self {
        let mut e = Engine::new(&Paths::default());
        e.pinyin = pinyin;
        e.wubi = wubi;
        e.english = english;
        e
    }

    pub fn has_lexicon(&self, schema: Schema) -> bool {
        match schema {
            Schema::Pinyin | Schema::Shuangpin(_) | Schema::T9 => self.pinyin.is_some(),
            Schema::Wubi86 => self.wubi.is_some(),
            Schema::English => self.english.is_some(),
        }
    }

    pub fn schema(&self) -> Schema {
        self.schema
    }

    pub fn set_schema(&mut self, s: Schema) {
        if s != self.schema {
            self.clear();
            self.schema = s;
        }
    }

    /// 关闭学习（密码框、无痕模式）。 Disable learning (password fields, incognito).
    pub fn set_learning(&mut self, on: bool) {
        self.user_pinyin.learning = on;
    }

    /// 换了输入框：清空上下文。 New editor: forget context.
    pub fn reset_context(&mut self) {
        self.last_word = None;
    }

    pub fn set_context(&mut self, prev_word: Option<String>) {
        self.last_word = prev_word.filter(|w| !w.is_empty());
    }

    pub fn is_composing(&self) -> bool {
        !self.raw.is_empty() || !self.t9_units.is_empty() || !self.selected.is_empty()
    }

    // ------------------------------------------------------------ keys

    /// 输入一个字符；返回 false 表示引擎不处理（界面应直接上屏）。
    /// Feed a character; false means "not mine", the UI should insert it directly.
    pub fn input_char(&mut self, c: char) -> bool {
        match self.schema {
            Schema::Pinyin => {
                let c = c.to_ascii_lowercase();
                if c.is_ascii_lowercase() || (c == '\'' && !self.rest_raw().is_empty()) {
                    if c == '\'' && self.raw.ends_with('\'') {
                        return true;
                    }
                    self.raw.push(c);
                    self.refresh();
                    return true;
                }
                false
            }
            Schema::Shuangpin(_) => {
                let c = c.to_ascii_lowercase();
                if c.is_ascii_lowercase() || (c == ';' && !self.raw.is_empty()) {
                    self.raw.push(c);
                    self.refresh();
                    return true;
                }
                false
            }
            Schema::T9 => {
                if ('2'..='9').contains(&c) {
                    self.t9_units.push(T9Unit::Digit(c as u8));
                } else if c == '1' || c == '\'' {
                    if self.t9_units.len() <= self.consumed
                        || matches!(self.t9_units.last(), Some(T9Unit::Sep))
                    {
                        return !self.t9_units.is_empty();
                    }
                    self.t9_units.push(T9Unit::Sep);
                } else {
                    return false;
                }
                self.refresh();
                true
            }
            Schema::Wubi86 => {
                let lc = c.to_ascii_lowercase();
                if !lc.is_ascii_lowercase() {
                    return false;
                }
                let pinyin_mode = self.options.wubi_pinyin_lookup && self.raw.starts_with('z');
                if !pinyin_mode && self.raw.len() >= 4 {
                    // 顶屏：满四码后再敲一码，先把首选上屏；空码则直接丢弃，不把字母上屏。
                    // Push: a 5th key commits the top choice; an empty code is dropped, not committed.
                    if self.cands.is_empty() {
                        self.clear();
                    } else {
                        self.commit_first();
                    }
                }
                self.raw.push(lc);
                self.refresh();
                if self.options.wubi_auto_commit
                    && !self.raw.starts_with('z')
                    && self.raw.len() == 4
                    && self.cands.len() == 1
                {
                    self.select(0);
                }
                true
            }
            Schema::English => {
                if c.is_ascii_alphabetic() || (c == '\'' && !self.raw.is_empty()) {
                    self.raw.push(c);
                    self.refresh();
                    return true;
                }
                false
            }
        }
    }

    /// 退格；返回 false 表示没有组合中的内容（界面应删除编辑器里的字）。
    /// Backspace; false when nothing is composing (the UI should delete in the editor).
    pub fn backspace(&mut self) -> bool {
        if !self.is_composing() {
            return false;
        }
        let rest_empty = match self.schema {
            Schema::T9 => self.t9_units.len() <= self.consumed,
            _ => self.raw.len() <= self.consumed,
        };
        if rest_empty {
            if let Some(sel) = self.selected.pop() {
                self.consumed = sel.consumed_before;
            }
        } else if self.schema == Schema::T9 {
            if let Some(T9Unit::Syllable { id, .. }) = self.t9_units.pop() {
                // 退掉锁定的拼音：恢复数字（去掉最后一个）。 Unlock and drop one digit.
                let spelling = syllable::spelling(id);
                for b in spelling.bytes().take(spelling.len() - 1) {
                    self.t9_units.push(T9Unit::Digit(t9::letter_digit(b)));
                }
            }
        } else {
            self.raw.pop();
        }
        if self.raw.len() <= self.consumed
            && self.t9_units.len() <= self.consumed
            && self.selected.is_empty()
        {
            self.clear();
        } else {
            self.refresh();
        }
        true
    }

    /// 选第 i 个候选。 Select the i-th candidate.
    pub fn select(&mut self, index: usize) -> bool {
        let Some(c) = self.cands.get(index).cloned() else {
            return false;
        };
        match c.action {
            Action::Table { text } => {
                // 已部分选定的字词先上屏。 Earlier partial selections go first.
                let mut all: String = self.selected.iter().map(|s| s.text.as_str()).collect();
                all.push_str(&text);
                let out = self.out(&all);
                self.commit.push_str(&out);
                self.finish_composition();
            }
            Action::Pinyin(cand) => {
                let total = self.graph_len();
                let sel = Selection {
                    text: cand.text.clone(),
                    words: cand.words.clone(),
                    consumed_before: self.consumed,
                };
                if cand.end >= total {
                    self.selected.push(sel);
                    self.commit_selected();
                } else {
                    self.consumed += self.units_for(cand.end);
                    self.selected.push(sel);
                    // 跳过紧跟着的分隔符。 Skip a following separator.
                    self.skip_separators();
                    self.refresh();
                }
            }
        }
        true
    }

    /// 以首选上屏（遇到标点时）。 Commit the top choice (e.g. before punctuation).
    pub fn commit_first(&mut self) {
        let mut guard = 0;
        while self.is_composing() && guard < 64 {
            guard += 1;
            if self.cands.is_empty() {
                self.commit_raw();
                return;
            }
            self.select(0);
        }
    }

    /// 回车：原样上屏输入码。 Enter: commit the raw keys as typed.
    pub fn commit_raw(&mut self) {
        let sel: String = self.selected.iter().map(|s| s.text.as_str()).collect();
        let mut text = self.out(&sel);
        match self.schema {
            Schema::T9 => {
                for u in &self.t9_units[self.consumed.min(self.t9_units.len())..] {
                    match u {
                        T9Unit::Digit(d) => text.push(*d as char),
                        T9Unit::Syllable { id, .. } => text.push_str(syllable::spelling(*id)),
                        T9Unit::Letter(c) => text.push(*c as char),
                        T9Unit::Sep => {}
                    }
                }
            }
            _ => text.extend(self.rest_raw().chars().filter(|&c| c != '\'')),
        }
        self.commit.push_str(&text);
        self.finish_composition();
    }

    /// 九键：选择左侧拼音栏第 i 项。 T9: pick the i-th entry of the pinyin column.
    pub fn select_pinyin_option(&mut self, index: usize) -> bool {
        if self.schema != Schema::T9 {
            return false;
        }
        let Some(opt) = self.t9_options.get(index).cloned() else {
            return false;
        };
        // 把自由段开头的若干数字替换成锁定单元。 Replace leading free digits with the lock.
        let digits = match &opt {
            T9Unit::Syllable { digits, .. } => *digits,
            T9Unit::Letter(_) => 1,
            _ => return false,
        };
        let rest = self.t9_units.split_off(self.consumed);
        let mut i = 0;
        // 已锁定的前缀单元原样保留。 Keep already locked leading units.
        while i < rest.len() && matches!(rest[i], T9Unit::Syllable { .. } | T9Unit::Letter(_)) {
            self.t9_units.push(rest[i].clone());
            i += 1;
        }
        let mut taken = 0;
        while i < rest.len() && taken < digits {
            if let T9Unit::Digit(_) = rest[i] {
                taken += 1;
            }
            i += 1;
        }
        self.t9_units.push(opt);
        self.t9_units.extend_from_slice(&rest[i..]);
        self.refresh();
        true
    }

    /// 删除用户学到的候选。 Forget a learned candidate.
    pub fn forget_candidate(&mut self, index: usize) -> bool {
        let Some(Cand {
            action: Action::Pinyin(c),
            view,
        }) = self.cands.get(index).cloned()
        else {
            return false;
        };
        if !view.user {
            return false;
        }
        self.user_pinyin.forget(&c.key, &c.text);
        self.refresh();
        true
    }

    pub fn clear(&mut self) {
        self.raw.clear();
        self.t9_units.clear();
        self.consumed = 0;
        self.selected.clear();
        self.cands.clear();
        self.preedit.clear();
        self.t9_options.clear();
    }

    // ------------------------------------------------------------ user dictionary management

    /// 用户词（最近使用在前），可按文字或拼音过滤。 User words, most recent first, optional filter.
    pub fn user_words(&self, query: &str, offset: usize, limit: usize) -> Vec<UserWord> {
        let q = query.trim();
        self.user_pinyin
            .all_entries()
            .into_iter()
            .map(|(k, e)| UserWord {
                pinyin: spell_key(&k),
                text: e.text,
                count: e.count,
            })
            .filter(|w| {
                q.is_empty()
                    || w.text.contains(q)
                    || w.pinyin.replace(' ', "").contains(&q.replace(' ', ""))
            })
            .skip(offset)
            .take(limit)
            .collect()
    }

    pub fn user_word_count(&self) -> usize {
        self.user_pinyin.entry_count()
    }

    /// 删除用户词；pinyin 为空格分隔的音节。 Delete a user word.
    pub fn delete_user_word(&mut self, pinyin: &str, text: &str) -> bool {
        let Some(key) = syllable::parse_seq(pinyin) else {
            return false;
        };
        let had = self.user_pinyin.get(&key, text).is_some();
        self.user_pinyin.forget(&key, text);
        had
    }

    /// 导出为文本，每行 `词<TAB>拼音<TAB>次数`。 Export as `text<TAB>pinyin<TAB>count` lines.
    pub fn export_user_words(&self) -> String {
        let mut out = String::from("# WeaveText user dictionary v1\n");
        for (k, e) in self.user_pinyin.all_entries() {
            out.push_str(&format!("{}\t{}\t{}\n", e.text, spell_key(&k), e.count));
        }
        out
    }

    /// 从文本导入（格式同导出；也接受无次数的两列）。返回导入条数。
    /// Import from text (export format; two columns accepted). Returns imported rows.
    pub fn import_user_words(&mut self, text: &str) -> usize {
        let mut n = 0;
        for line in text.lines() {
            if line.starts_with('#') || line.trim().is_empty() {
                continue;
            }
            let mut cols = line.split('\t');
            let (Some(word), Some(py)) = (cols.next(), cols.next()) else {
                continue;
            };
            let count = cols
                .next()
                .and_then(|c| c.trim().parse().ok())
                .unwrap_or(1u32)
                .max(1);
            if let Some(key) = syllable::parse_seq(py) {
                self.user_pinyin.import(&key, word.trim(), count);
                n += 1;
            }
        }
        self.user_pinyin.flush();
        n
    }

    pub fn clear_user_words(&mut self) -> bool {
        self.user_pinyin.clear().is_ok()
    }

    /// 持久化用户数据。 Flush user data to disk.
    pub fn flush(&mut self) {
        self.user_pinyin.flush();
    }

    // ------------------------------------------------------------ output

    /// 取快照；`commit` 在读取后清空。 Take a snapshot; `commit` is drained.
    pub fn snapshot(&mut self) -> Snapshot {
        let page = PAGE_INITIAL.min(self.cands.len());
        Snapshot {
            commit: std::mem::take(&mut self.commit),
            preedit: self.preedit.clone(),
            composing: self.is_composing(),
            candidates: self.cands[..page].iter().map(|c| c.view.clone()).collect(),
            total_candidates: self.cands.len(),
            pinyin_options: self.t9_options.iter().map(t9_option_label).collect(),
            schema: self.schema.key(),
        }
    }

    /// 分页取候选。 Page through candidates.
    pub fn candidates(&self, offset: usize, limit: usize) -> Vec<CandidateView> {
        self.cands
            .iter()
            .skip(offset)
            .take(limit)
            .map(|c| c.view.clone())
            .collect()
    }

    // ------------------------------------------------------------ internals

    fn rest_raw(&self) -> &str {
        &self.raw[self.consumed.min(self.raw.len())..]
    }

    fn skip_separators(&mut self) {
        match self.schema {
            Schema::T9 => {
                while matches!(self.t9_units.get(self.consumed), Some(T9Unit::Sep)) {
                    self.consumed += 1;
                }
            }
            _ => {
                while self.raw.as_bytes().get(self.consumed) == Some(&b'\'') {
                    self.consumed += 1;
                }
            }
        }
    }

    fn finish_composition(&mut self) {
        self.clear();
    }

    fn commit_selected(&mut self) {
        let sels = std::mem::take(&mut self.selected);
        let text: String = sels.iter().map(|s| s.text.as_str()).collect();
        // 学习：每个词、相邻词对；多段拼出来的整体作为新词。
        // Learn each word, adjacent pairs, and the multi-step phrase as a new word.
        let mut words: Vec<(Vec<SyllableId>, String)> = Vec::new();
        for s in &sels {
            words.extend(s.words.iter().cloned());
        }
        let mut prev = self.last_word.clone();
        for (k, w) in &words {
            self.user_pinyin.learn(k, w);
            if let Some(p) = &prev {
                self.user_pinyin.learn_bigram(p, w);
            }
            prev = Some(w.clone());
        }
        if sels.len() >= 2 {
            let key: Vec<SyllableId> = words.iter().flat_map(|(k, _)| k.iter().copied()).collect();
            if key.len() <= 8 && text.chars().all(is_cjk) {
                self.user_pinyin.learn(&key, &text);
            }
        }
        self.last_word = words.last().map(|(_, w)| w.clone());
        let out = self.out(&text);
        self.commit.push_str(&out);
        self.clear();
    }

    /// 当前解码图的长度（按键位置数）。 Length of the current graph.
    fn graph_len(&self) -> usize {
        match self.schema {
            Schema::Pinyin => Letters::parse(self.rest_raw()).keys.len(),
            Schema::Shuangpin(_) => self.rest_raw().len(),
            Schema::T9 => T9Input::from_units(&self.t9_units[self.consumed..])
                .digits
                .len(),
            _ => 0,
        }
    }

    /// 图上位置 `end` 对应消耗多少输入单元。 How many input units graph position `end` spans.
    fn units_for(&self, end: usize) -> usize {
        match self.schema {
            Schema::Pinyin => {
                let mut letters = 0;
                for (i, b) in self.rest_raw().bytes().enumerate() {
                    if letters == end {
                        return i;
                    }
                    if b != b'\'' {
                        letters += 1;
                    }
                }
                self.rest_raw().len()
            }
            Schema::Shuangpin(_) => end,
            Schema::T9 => {
                let mut pos = 0;
                for (i, u) in self.t9_units[self.consumed..].iter().enumerate() {
                    if pos >= end {
                        return i;
                    }
                    pos += match u {
                        T9Unit::Digit(_) | T9Unit::Letter(_) => 1,
                        T9Unit::Syllable { id, .. } => syllable::spelling(*id).len(),
                        T9Unit::Sep => 0,
                    };
                }
                self.t9_units.len() - self.consumed
            }
            _ => 0,
        }
    }

    fn build_graph(&self) -> (SyllableGraph, Vec<u8>) {
        match self.schema {
            Schema::Pinyin => {
                let l = Letters::parse(self.rest_raw());
                (graph::build_full_pinyin(&l, &self.options.fuzzy), l.keys)
            }
            Schema::Shuangpin(id) => {
                let keys = self.rest_raw().as_bytes().to_vec();
                (shuangpin::build_graph(id, &keys, &self.options.fuzzy), keys)
            }
            Schema::T9 => {
                let inp = T9Input::from_units(&self.t9_units[self.consumed..]);
                (t9::build_graph(&inp), inp.digits)
            }
            _ => (SyllableGraph::new(0), Vec::new()),
        }
    }

    fn refresh(&mut self) {
        self.cands.clear();
        self.t9_options.clear();
        let selected: String = self.selected.iter().map(|s| s.text.as_str()).collect();
        match self.schema {
            s if s.is_pinyin_family() => self.refresh_pinyin(selected),
            Schema::Wubi86 => self.refresh_wubi(),
            Schema::English => self.refresh_english(),
            _ => {}
        }
        self.decorate();
    }

    fn refresh_pinyin(&mut self, selected: String) {
        let (g, keys) = self.build_graph();
        if g.len == 0 {
            self.preedit = selected;
            return;
        }
        let dec = Decoder {
            lex: self.pinyin.as_ref(),
            user: &self.user_pinyin,
            graph: &g,
            context: if self.selected.is_empty() {
                self.last_word.as_deref()
            } else {
                self.selected
                    .last()
                    .and_then(|s| s.words.last())
                    .map(|(_, w)| w.as_str())
            },
            lm: self
                .gram
                .as_ref()
                .filter(|_| self.options.lm_weight > 0.0)
                .map(|g| LmParams {
                    gram: g,
                    weight: self.options.lm_weight,
                    baseline: self.options.lm_baseline,
                }),
        };
        let lat = dec.decode();
        let raw_text = |s: usize, e: usize| String::from_utf8_lossy(&keys[s..e]).into_owned();
        let cands = dec.candidates(&lat, &raw_text);
        self.preedit = format!("{selected}{}", self.display_rest(&lat, &keys));
        self.cands = cands
            .into_iter()
            .map(|c| Cand {
                view: CandidateView {
                    text: c.text.clone(),
                    comment: c.comment.clone(),
                    user: c.origin == crate::decoder::Origin::User && c.kind == CandKind::Word,
                },
                action: Action::Pinyin(c),
            })
            .collect();
        if self.schema == Schema::Pinyin {
            self.mix_english(&lat, &keys);
        }
        if self.schema == Schema::T9 {
            let inp = T9Input::from_units(&self.t9_units[self.consumed..]);
            let mut opts = t9::pinyin_options(&inp);
            // 按该音节在词库里的最优 cost 排序，常用的在前。 Frequent spellings first.
            if let Some(lex) = &self.pinyin {
                let score = |u: &T9Unit| match u {
                    T9Unit::Syllable { id, digits } => (
                        usize::MAX - digits,
                        lex.child(weave_dict::ROOT, *id)
                            .map(|n| lex.best(n))
                            .unwrap_or(u16::MAX),
                    ),
                    _ => (usize::MAX, u16::MAX),
                };
                opts.sort_by_key(score);
            }
            self.t9_options = opts;
        }
    }

    /// 中英混输：键入的字母恰好是常用英文词时，把英文词插进候选。
    /// 拼音读不通时放首位；需要简拼时放第 2 位；拼音完全读得通时只有高频词放第 4 位。
    /// Mixed input: when the letters spell an English word, insert it — first if the letters are
    /// not pinyin, second if pinyin needs abbreviations, fourth (frequent words only) otherwise.
    fn mix_english(&mut self, lat: &Lattice, keys: &[u8]) {
        let Some(en) = &self.english else { return };
        if keys.len() < 2 || self.rest_raw().contains('\'') {
            return;
        }
        let Some(key) = table::code_key(std::str::from_utf8(keys).unwrap_or("")) else {
            return;
        };
        let Some(node) = en.find(&key) else { return };
        let Some(best) = en.entries(node).min_by_key(|e| e.cost) else {
            return;
        };
        let text = en.text(best.text_id).to_string();
        if self
            .cands
            .iter()
            .any(|c| c.view.text.eq_ignore_ascii_case(&text))
        {
            return;
        }
        // 拼音越读不通（原样按键、简拼越多），越可能是英文。 The less pinyin-like, the likelier English.
        let has_raw = lat.best.iter().any(|(si, _)| lat.spans[*si].raw);
        let pinyin_penalty: u32 = lat.best.iter().map(|(si, _)| lat.spans[*si].penalty).sum();
        let cost = best.cost as u32;
        let pos = if has_raw || lat.best.is_empty() || (pinyin_penalty >= 3000 && cost < 16_000) {
            0
        } else if pinyin_penalty > 0 && cost < 12_500 {
            1
        } else if cost < 11_000 {
            3
        } else {
            return;
        };
        let cand = Cand {
            view: CandidateView {
                text: text.clone(),
                comment: String::new(),
                user: false,
            },
            action: Action::Table { text },
        };
        self.cands.insert(pos.min(self.cands.len()), cand);
    }

    /// 组合串中未选部分的显示：按最优路径切分。 Display of the unselected part.
    fn display_rest(&self, lat: &Lattice, keys: &[u8]) -> String {
        let mut parts: Vec<String> = Vec::new();
        let locks: Vec<(usize, usize, T9Unit)> = if self.schema == Schema::T9 {
            T9Input::from_units(&self.t9_units[self.consumed..]).locks
        } else {
            Vec::new()
        };
        for (si, _) in &lat.best {
            let span = &lat.spans[*si];
            let mut s = span.start;
            for (i, &cut) in span.cuts.iter().enumerate() {
                let piece = match self.schema {
                    Schema::Pinyin => String::from_utf8_lossy(&keys[s..cut]).into_owned(),
                    Schema::Shuangpin(_) | Schema::T9 => match span.key.get(i) {
                        Some(&id) => {
                            let full = syllable::spelling(id);
                            let lock = locks.iter().find(|(ls, le, _)| *ls == s && *le == cut);
                            let locked = lock.is_some();
                            if let Some((_, _, T9Unit::Letter(c))) = lock {
                                (*c as char).to_string()
                            } else if self.schema == Schema::T9 && cut - s < full.len() && !locked {
                                full[..cut - s].to_string()
                            } else if self.schema == Schema::T9 && locked {
                                full.to_string()
                            } else if cut - s == 1 && self.schema != Schema::T9 {
                                String::from_utf8_lossy(&keys[s..cut]).into_owned()
                            } else {
                                full.to_string()
                            }
                        }
                        None => String::from_utf8_lossy(&keys[s..cut]).into_owned(),
                    },
                    _ => String::new(),
                };
                parts.push(piece);
                s = cut;
            }
        }
        parts.join("'")
    }

    fn wubi_code_of(&mut self, text: &str) -> String {
        if self.wubi_reverse.is_none() {
            if let Some(lex) = &self.wubi {
                self.wubi_reverse = Some(table::reverse_index(lex));
            }
        }
        self.wubi_reverse
            .as_ref()
            .and_then(|m| m.get(text).cloned())
            .unwrap_or_default()
    }

    fn refresh_wubi(&mut self) {
        self.preedit = self.raw.clone();
        if self.options.wubi_pinyin_lookup && self.raw == "z" {
            return;
        }
        if self.options.wubi_pinyin_lookup && self.raw.starts_with('z') && self.raw.len() > 1 {
            // z 键拼音反查：用拼音解码，注释显示五笔编码。 Pinyin lookup with wubi codes shown.
            let letters = Letters::parse(&self.raw[1..]);
            let g = graph::build_full_pinyin(&letters, &FuzzyOptions::default());
            let empty = UserDict::in_memory();
            let dec = Decoder {
                lex: self.pinyin.as_ref(),
                user: &empty,
                graph: &g,
                context: None,
                lm: None,
            };
            let lat = dec.decode();
            let keys = letters.keys.clone();
            let raw_text = |s: usize, e: usize| String::from_utf8_lossy(&keys[s..e]).into_owned();
            let found: Vec<Candidate> = dec
                .candidates(&lat, &raw_text)
                .into_iter()
                .filter(|c| c.end == g.len && c.kind == CandKind::Word)
                .take(80)
                .collect();
            for c in found {
                let code = self.wubi_code_of(&c.text);
                self.cands.push(Cand {
                    view: CandidateView {
                        text: c.text.clone(),
                        comment: code,
                        user: false,
                    },
                    action: Action::Table { text: c.text },
                });
            }
            return;
        }
        let Some(lex) = &self.wubi else { return };
        let limit = if self.options.wubi_completion && self.raw.len() < 4 {
            40
        } else {
            0
        };
        for c in table::lookup(lex, &self.raw, limit) {
            self.cands.push(Cand {
                view: CandidateView {
                    text: c.text.clone(),
                    comment: c.comment,
                    user: false,
                },
                action: Action::Table { text: c.text },
            });
        }
    }

    fn refresh_english(&mut self) {
        self.preedit = self.raw.clone();
        let typed = self.raw.clone();
        let cap = typed.chars().next().is_some_and(|c| c.is_ascii_uppercase());
        let all_caps = typed.len() > 1 && typed.chars().all(|c| !c.is_ascii_lowercase());
        let mut seen = std::collections::HashSet::new();
        seen.insert(typed.to_ascii_lowercase());
        self.cands.push(Cand {
            view: CandidateView {
                text: typed.clone(),
                comment: String::new(),
                user: false,
            },
            action: Action::Table {
                text: typed.clone(),
            },
        });
        let Some(lex) = &self.english else { return };
        let lower = typed.to_ascii_lowercase().replace('\'', "");
        let mut found = table::lookup(lex, &lower, 30);
        // 频率为主、补全长度为辅：每多一个字母约等于频率低 e^0.35 倍。
        // Frequency first, completion length second (each extra letter ≈ e^0.35 less likely).
        found.sort_by_key(|c| {
            (
                !c.exact,
                c.cost + 350 * c.text.len().saturating_sub(lower.len()) as u32,
            )
        });
        for c in found {
            let text = if all_caps {
                c.text.to_ascii_uppercase()
            } else if cap {
                capitalize(&c.text)
            } else {
                c.text.clone()
            };
            if !seen.insert(text.to_ascii_lowercase()) {
                continue;
            }
            self.cands.push(Cand {
                view: CandidateView {
                    text: text.clone(),
                    comment: String::new(),
                    user: false,
                },
                action: Action::Table { text },
            });
        }
    }
}

fn capitalize(s: &str) -> String {
    let mut c = s.chars();
    match c.next() {
        Some(f) => f.to_uppercase().collect::<String>() + c.as_str(),
        None => String::new(),
    }
}

fn is_cjk(c: char) -> bool {
    matches!(c as u32, 0x3400..=0x4DBF | 0x4E00..=0x9FFF | 0xF900..=0xFAFF | 0x20000..=0x3134F)
}

fn t9_option_label(u: &T9Unit) -> String {
    match u {
        T9Unit::Syllable { id, .. } => syllable::spelling(*id).to_string(),
        T9Unit::Letter(c) => (*c as char).to_string(),
        _ => String::new(),
    }
}

/// 便捷：从目录加载标准文件名的资源。 Load resources with standard names from a directory.
pub fn paths_in(data_dir: &Path, user_dir: &Path) -> Paths {
    let opt = |name: &str| {
        let p = data_dir.join(name);
        p.exists().then_some(p)
    };
    Paths {
        pinyin_lexicon: opt("pinyin.wvl"),
        wubi_lexicon: opt("wubi86.wvl"),
        english_lexicon: opt("english.wvl"),
        convert_dir: data_dir
            .join("emoji.txt")
            .exists()
            .then(|| data_dir.to_path_buf()),
        gram_model: opt("grammar.wvg"),
        user_dir: Some(user_dir.to_path_buf()),
    }
}

#[cfg(test)]
mod wubi_tests {
    use super::*;
    use weave_dict::lexicon::{letter_sym, Builder, Kind};

    fn engine() -> Engine {
        let mut b = Builder::new(Kind::Letters);
        let k = |s: &str| s.bytes().map(|c| letter_sym(c).unwrap()).collect::<Vec<_>>();
        b.insert(&k("ggll"), "一", 10);
        b.insert(&k("trnt"), "我", 10);
        b.insert(&k("trnt"), "特性", 20);
        b.insert(&k("wq"), "你", 10);
        let mut e = Engine::with_lexicons(None, Some(Lexicon::from_bytes(b.build()).unwrap()), None);
        e.set_schema(Schema::Wubi86);
        e
    }

    fn typing(e: &mut Engine, s: &str) -> String {
        for c in s.chars() {
            e.input_char(c);
        }
        e.snapshot().commit
    }

    #[test]
    fn unique_four_code_auto_commits() {
        let mut e = engine();
        assert_eq!(typing(&mut e, "ggll"), "一");
        assert!(!e.is_composing());
    }

    #[test]
    fn fifth_key_pushes_top_choice() {
        let mut e = engine();
        assert_eq!(typing(&mut e, "trnt"), "");
        assert_eq!(typing(&mut e, "w"), "我");
        assert_eq!(e.snapshot().preedit, "w");
    }

    #[test]
    fn empty_code_is_dropped() {
        let mut e = engine();
        assert_eq!(typing(&mut e, "xxxx"), "");
        assert_eq!(typing(&mut e, "wq"), "");
        assert_eq!(e.snapshot().preedit, "wq");
        e.select(0);
        assert_eq!(e.snapshot().commit, "你");
    }
}
