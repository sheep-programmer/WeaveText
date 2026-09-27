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
use crate::graph::{self, FuzzyOptions, Letters, Near, SyllableGraph};
use crate::shuangpin::{self, SchemeId};
use crate::t9::{self, Grouping, T9Input, T9Unit};
use crate::table;
use crate::userdict::UserDict;
use weave_dict::blob::Source;
use weave_dict::gram::Gram;

/// 输入方案。 Input schema.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Schema {
    Pinyin,
    Shuangpin(SchemeId),
    /// 九键 / 14 键：一个键对应多个字母。 T9 / 14-key: several letters per key.
    Keypad(Grouping),
    Wubi86,
    English,
    /** 手写（单字）。 Handwriting, one character at a time. */
    Hand,
}

impl Schema {
    /// 方案标识："pinyin" / "shuangpin:xiaohe" / "t9" / "t14" / "wubi86" / "english"。
    pub fn from_key(k: &str) -> Option<Self> {
        match k {
            "pinyin" => Some(Schema::Pinyin),
            "t9" => Some(Schema::Keypad(Grouping::Nine)),
            "t14" => Some(Schema::Keypad(Grouping::Fourteen)),
            "wubi86" => Some(Schema::Wubi86),
            "english" => Some(Schema::English),
            "hand" => Some(Schema::Hand),
            _ => k
                .strip_prefix("shuangpin:")
                .and_then(SchemeId::from_key)
                .map(Schema::Shuangpin),
        }
    }

    pub fn key(&self) -> String {
        match self {
            Schema::Pinyin => "pinyin".into(),
            Schema::Keypad(Grouping::Nine) => "t9".into(),
            Schema::Keypad(Grouping::Fourteen) => "t14".into(),
            Schema::Wubi86 => "wubi86".into(),
            Schema::English => "english".into(),
            Schema::Hand => "hand".into(),
            Schema::Shuangpin(s) => format!("shuangpin:{}", s.key()),
        }
    }

    fn is_pinyin_family(&self) -> bool {
        matches!(self, Schema::Pinyin | Schema::Shuangpin(_) | Schema::Keypad(_))
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
    /// 本地时区相对 UTC 的分钟数（日期时间候选用，宿主设置）。 Local UTC offset in minutes, set by the host.
    pub utc_offset_min: i32,
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
            utc_offset_min: 480,
        }
    }
}

impl Options {
    /// 按名字设置开关：`fuzzy.z_zh` … `fuzzy.uan_uang`、`wubi.auto_commit`、`wubi.pinyin_lookup`、
    /// `wubi.completion`、`output.traditional`、`candidates.emoji`；未知名字返回 false。
    /// Set a switch by name (see above); false for unknown names.
    pub fn set_flag(&mut self, key: &str, on: bool) -> bool {
        let f = &mut self.fuzzy;
        let slot: &mut bool = match key {
            "fuzzy.z_zh" => &mut f.z_zh,
            "fuzzy.c_ch" => &mut f.c_ch,
            "fuzzy.s_sh" => &mut f.s_sh,
            "fuzzy.n_l" => &mut f.n_l,
            "fuzzy.f_h" => &mut f.f_h,
            "fuzzy.r_l" => &mut f.r_l,
            "fuzzy.an_ang" => &mut f.an_ang,
            "fuzzy.en_eng" => &mut f.en_eng,
            "fuzzy.in_ing" => &mut f.in_ing,
            "fuzzy.ian_iang" => &mut f.ian_iang,
            "fuzzy.uan_uang" => &mut f.uan_uang,
            "wubi.auto_commit" => &mut self.wubi_auto_commit,
            "wubi.pinyin_lookup" => &mut self.wubi_pinyin_lookup,
            "wubi.completion" => &mut self.wubi_completion,
            "output.traditional" => &mut self.traditional,
            "candidates.emoji" => &mut self.emoji,
            _ => return false,
        };
        *slot = on;
        true
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

/// 资源位置：普通文件，或 APK 内的一段区间（见 [`Source`]）。
/// Resource locations: plain files or byte ranges inside the APK (see [`Source`]).
#[derive(Clone, Debug, Default)]
pub struct Paths {
    pub pinyin_lexicon: Option<Source>,
    pub wubi_lexicon: Option<Source>,
    pub english_lexicon: Option<Source>,
    /// OpenCC 简繁词组表与单字表。 OpenCC phrase and character tables.
    pub st_phrases: Option<Source>,
    pub st_characters: Option<Source>,
    /// 表情联想表。 Emoji suggestion table.
    pub emoji: Option<Source>,
    /// 手写识别模板（.wvh）。 Handwriting templates.
    pub hand: Option<Source>,
    /// 字符搭配模型（.wvg）。 Character collocation model.
    pub gram_model: Option<Source>,
    /// 用户数据目录。 User data directory.
    pub user_dir: Option<PathBuf>,
    /// 扩展拼音词库（专业词库、热词）：(id, 来源)。 Extra pinyin lexicons (domain packs, hot words): (id, source).
    pub packs: Vec<(String, Source)>,
}

/// 资源名 → 原始文件名；分块压缩版统一命名为 `<资源名>.wvz`。
/// Resource key → raw file name; packed copies are named `<key>.wvz`.
pub const RESOURCES: [(&str, &str); 8] = [
    ("pinyin", "pinyin.wvl"),
    ("wubi86", "wubi86.wvl"),
    ("english", "english.wvl"),
    ("grammar", "grammar.wvg"),
    ("st_phrases", "STPhrases.txt"),
    ("st_characters", "STCharacters.txt"),
    ("emoji", "emoji.txt"),
    ("hand", "hand.wvh"),
];

impl Paths {
    /// 按资源名设置来源；未知名字返回 false。 Set a source by resource key; false for unknown keys.
    pub fn set(&mut self, key: &str, src: Source) -> bool {
        // 扩展词库：`pack.<id>`。 Extra lexicons use `pack.<id>`.
        if let Some(id) = key.strip_prefix("pack.").filter(|id| valid_pack_id(id)) {
            self.packs.retain(|(p, _)| p != id);
            self.packs.push((id.to_string(), src));
            return true;
        }
        let slot = match key {
            "pinyin" => &mut self.pinyin_lexicon,
            "wubi86" => &mut self.wubi_lexicon,
            "english" => &mut self.english_lexicon,
            "grammar" => &mut self.gram_model,
            "st_phrases" => &mut self.st_phrases,
            "st_characters" => &mut self.st_characters,
            "emoji" => &mut self.emoji,
            "hand" => &mut self.hand,
            _ => return false,
        };
        *slot = Some(src);
        true
    }

    /// 解析 `key=path@offset+len;…`（安卓端把 APK 内资源的位置传进来）。
    /// Parse `key=path@offset+len;…`, how Android passes asset ranges inside the APK.
    pub fn from_spec(spec: &str, user_dir: &Path) -> Paths {
        let mut paths = Paths {
            user_dir: Some(user_dir.to_path_buf()),
            ..Default::default()
        };
        for item in spec.split(';').filter(|s| !s.is_empty()) {
            let Some((key, loc)) = item.split_once('=') else {
                continue;
            };
            let src = match loc.rsplit_once('@').and_then(|(p, r)| {
                let (o, l) = r.split_once('+')?;
                Some(Source::range(p, o.parse().ok()?, l.parse().ok()?))
            }) {
                Some(s) => s,
                None => Source::file(loc),
            };
            paths.set(key, src);
        }
        paths
    }
}

/// 输入法引擎。 The IME engine.
pub struct Engine {
    pinyin: Option<Lexicon>,
    /// 扩展拼音词库与其 id（顺序一致）。 Extra pinyin lexicons and their ids, in the same order.
    packs: Vec<Lexicon>,
    pack_ids: Vec<String>,
    wubi: Option<Lexicon>,
    english: Option<Lexicon>,
    user_pinyin: UserDict,
    schema: Schema,
    pub options: Options,

    raw: String,
    /// 与 raw 逐字节对应的触点邻键（仅全拼使用；长度不符时忽略）。
    /// Tap neighbours, one per raw byte (full pinyin only; ignored when the lengths disagree).
    near: Vec<Near>,
    t9_units: Vec<T9Unit>,
    consumed: usize,
    selected: Vec<Selection>,
    cands: Vec<Cand>,
    preedit: String,
    t9_options: Vec<T9Unit>,
    commit: String,
    last_word: Option<String>,
    wubi_reverse: Option<HashMap<String, String>>,
    st_sources: (Option<Source>, Option<Source>),
    traditional: Option<Traditional>,
    /// 手写模板来源与（首次使用时载入的）识别器。 Handwriting source and the recognizer, loaded on first use.
    hand_src: Option<Source>,
    hand: Option<weave_dict::hand::Recognizer>,
    /// 当前这个字已写的笔画。 Strokes of the character being written.
    hand_strokes: Vec<weave_dict::hand::Stroke>,
    /// 本次刷新最多生成的候选数。 Candidate budget for the current refresh.
    cand_cap: usize,
    /// 候选列表是否因预算截断（翻页时补齐）。 Whether the list was cut by the budget.
    cands_more: bool,
    emoji: Emoji,
    gram: Option<Gram>,
}

fn open_lex(p: &Option<Source>) -> Option<Lexicon> {
    p.as_ref().and_then(|p| Lexicon::open_source(p).ok())
}

const PAGE_INITIAL: usize = 60;
/// 每次按键先生成的候选数；翻页超出时再补齐（每键不必把几百个候选都构造一遍）。
/// Candidates built per keystroke; the rest are built only when paging goes past them.
const CAND_FIRST: usize = 120;
/// 手写每次给出的候选数。 Candidates per handwriting recognition.
const HAND_CANDIDATES: usize = 12;

impl Engine {
    pub fn new(paths: &Paths) -> Self {
        let user_pinyin = paths
            .user_dir
            .as_ref()
            .and_then(|d| UserDict::open(&d.join("pinyin.userdb")).ok())
            .unwrap_or_default();
        let mut packs = Vec::new();
        let mut pack_ids = Vec::new();
        for (id, src) in paths.packs.iter().take(crate::decoder::MAX_LEX - 1) {
            if let Ok(l) = Lexicon::open_source(src) {
                packs.push(l);
                pack_ids.push(id.clone());
            }
        }
        Engine {
            pinyin: open_lex(&paths.pinyin_lexicon),
            packs,
            pack_ids,
            wubi: open_lex(&paths.wubi_lexicon),
            english: open_lex(&paths.english_lexicon),
            user_pinyin,
            schema: Schema::Pinyin,
            options: Options::default(),
            raw: String::new(),
            near: Vec::new(),
            t9_units: Vec::new(),
            consumed: 0,
            selected: Vec::new(),
            cands: Vec::new(),
            preedit: String::new(),
            t9_options: Vec::new(),
            commit: String::new(),
            last_word: None,
            wubi_reverse: None,
            emoji: paths.emoji.as_ref().map(Emoji::load).unwrap_or_default(),
            st_sources: (paths.st_phrases.clone(), paths.st_characters.clone()),
            traditional: None,
            hand_src: paths.hand.clone(),
            hand: None,
            hand_strokes: Vec::new(),
            cand_cap: CAND_FIRST,
            cands_more: false,
            gram: paths.gram_model.as_ref().and_then(|p| Gram::open_source(p).ok()),
        }
    }

    /// 预热：在后台加载线程上用常见输入走一遍解码，把最常用的数据块提前解压进缓存，
    /// 避免第一次打字时在按键路径上解压。学习关闭，不影响用户词库。
    /// Warm up on the loader thread: decode common inputs once so the hottest blocks are cached before
    /// the first real keystroke. Learning is off, so the user dictionary is untouched.
    pub fn warm_up(&mut self) {
        const SAMPLES: [&str; 16] = [
            "wo", "ni", "shi", "de", "zai", "you", "le", "bu", "zhege", "women", "shenme", "keyi",
            "zhongguo", "xianzai", "jintian", "haode",
        ];
        let (schema, learning) = (self.schema, self.user_pinyin.learning);
        self.user_pinyin.learning = false;
        self.schema = Schema::Pinyin;
        for s in SAMPLES {
            self.clear();
            for c in s.chars() {
                self.input_char(c);
            }
        }
        self.clear();
        self.last_word = None;
        self.schema = schema;
        self.user_pinyin.learning = learning;
    }

    /// 清空所有分块压缩数据的解压缓存。 Drop every packed file's decode cache.
    pub fn trim_caches(&self) {
        for l in [&self.pinyin, &self.wubi, &self.english].into_iter().flatten() {
            l.blob().trim();
        }
        if let Some(g) = &self.gram {
            g.blob().trim();
        }
    }

    /// 当前的按键分组（非九键/14 键时按九键）。 Current key grouping (T9 when not a keypad schema).
    fn grouping(&self) -> Grouping {
        match self.schema {
            Schema::Keypad(g) => g,
            _ => Grouping::Nine,
        }
    }

    /// 分块压缩数据的缓存统计：(资源, 命中, 未命中)。 Cache stats of packed data: (resource, hits, misses).
    pub fn cache_stats(&self) -> Vec<(&'static str, u64, u64)> {
        let mut out = Vec::new();
        for (name, blob) in [
            ("pinyin", self.pinyin.as_ref().map(|l| l.blob())),
            ("wubi86", self.wubi.as_ref().map(|l| l.blob())),
            ("english", self.english.as_ref().map(|l| l.blob())),
            ("grammar", self.gram.as_ref().map(|g| g.blob())),
        ] {
            if let Some(b) = blob.filter(|b| b.is_packed()) {
                let (h, m) = b.cache_stats();
                out.push((name, h, m));
            }
        }
        out
    }

    /// 输出前的转换（繁体）。 Output conversion (traditional).
    fn out(&mut self, s: &str) -> String {
        if !self.options.traditional {
            return s.to_owned();
        }
        if self.traditional.is_none() {
            let t = match &self.st_sources {
                (Some(p), Some(c)) => Traditional::load(p, c),
                _ => Traditional::default(),
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

    /// 载入或替换一个扩展词库；失败（文件无效、超过上限）返回 false。
    /// Load or replace an extra lexicon; false when the file is invalid or the limit is reached.
    pub fn load_pack(&mut self, id: &str, src: &Source) -> bool {
        if !valid_pack_id(id) {
            return false;
        }
        let Ok(l) = Lexicon::open_source(src) else { return false };
        if let Some(i) = self.pack_ids.iter().position(|p| p == id) {
            self.packs[i] = l;
        } else if self.packs.len() + 1 < crate::decoder::MAX_LEX {
            self.packs.push(l);
            self.pack_ids.push(id.to_string());
        } else {
            return false;
        }
        self.refresh_if_composing();
        true
    }

    /// 载入云端热词（验签、去掉过期词后作为扩展词库 `cloud`），返回词数；签名不对或文件无效返回 Err。
    /// Load cloud hot words (verified, expired rows dropped) as the extra lexicon `cloud`; returns the word count.
    pub fn load_hotwords(&mut self, tsv: &[u8], sig_hex: &str) -> Result<usize, String> {
        let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0);
        let today = (now + self.options.utc_offset_min as i64 * 60).div_euclid(86_400);
        let c = crate::cloud::compile(tsv, sig_hex, &crate::cloud::HOTWORDS_KEY, today)?;
        let lex = Lexicon::from_bytes(c.lexicon).map_err(|e| format!("{e:?}"))?;
        let id = "cloud";
        if let Some(i) = self.pack_ids.iter().position(|p| p == id) {
            self.packs[i] = lex;
        } else if self.packs.len() + 1 < crate::decoder::MAX_LEX {
            self.packs.push(lex);
            self.pack_ids.push(id.to_string());
        } else {
            return Err("too many packs".into());
        }
        self.refresh_if_composing();
        Ok(c.words)
    }

    /// 卸下一个扩展词库。 Unload an extra lexicon.
    pub fn unload_pack(&mut self, id: &str) -> bool {
        let Some(i) = self.pack_ids.iter().position(|p| p == id) else { return false };
        self.packs.remove(i);
        self.pack_ids.remove(i);
        self.refresh_if_composing();
        true
    }

    /// 已载入的扩展词库 id。 Ids of the loaded extra lexicons.
    pub fn pack_ids(&self) -> &[String] {
        &self.pack_ids
    }

    fn refresh_if_composing(&mut self) {
        if self.is_composing() {
            self.refresh();
        }
    }

    pub fn has_lexicon(&self, schema: Schema) -> bool {
        match schema {
            Schema::Pinyin | Schema::Shuangpin(_) | Schema::Keypad(_) => self.pinyin.is_some(),
            Schema::Wubi86 => self.wubi.is_some(),
            Schema::English => self.english.is_some(),
            Schema::Hand => self.hand_src.is_some(),
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
        !self.raw.is_empty() || !self.t9_units.is_empty() || !self.selected.is_empty() || !self.hand_strokes.is_empty()
    }

    // ------------------------------------------------------------ keys

    /// 带触点信息输入一个键：[alt] 为交界另一侧的字母，[closeness] 1 = 正压在交界上、0 = 刚进交界带。
    /// 全拼时用来纠正按到邻键的误触；其它方案等同 [input_char]。
    /// Feed a key with touch info: [alt] is the letter across the nearby border, [closeness] 1 = right on it,
    /// 0 = at the band's edge. Full pinyin uses it to fix taps on the neighbouring key; otherwise like [input_char].
    pub fn input_key(&mut self, c: char, alt: Option<char>, closeness: f32) -> bool {
        let before = self.raw.len();
        let nb = match alt {
            Some(a) if self.schema == Schema::Pinyin && c.is_ascii_alphabetic() && a.is_ascii_alphabetic() => {
                Near::new(a.to_ascii_lowercase() as u8, closeness)
            }
            _ => Near::NONE,
        };
        if nb == Near::NONE {
            return self.input_char(c);
        }
        // 先记下邻键再输入，刷新时即可用上。 Record the neighbour first so the refresh already uses it.
        self.near.resize(before, Near::NONE);
        self.near.push(nb);
        let ok = self.input_char(c);
        if self.raw.len() != before + 1 {
            self.near.truncate(self.raw.len().min(before));
        }
        ok
    }

    /// 输入一个字符；返回 false 表示引擎不处理（界面应直接上屏）。
    /// Feed a character; false means "not mine", the UI should insert it directly.
    pub fn input_char(&mut self, c: char) -> bool {
        match self.schema {
            Schema::Pinyin => {
                // v 模式：v 之后可以输入数字与算式。 The v mode takes digits and operators after `v`.
                if self.raw.starts_with('v') && self.consumed == 0 && crate::special::v_accepts(c) {
                    self.raw.push(c);
                    self.refresh();
                    return true;
                }
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
            Schema::Keypad(g) => {
                if g.is_code(c) {
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
            // 手写不接受按键字符（笔画经 hand_input 进来）。 Handwriting takes strokes via hand_input, not keys.
            Schema::Hand => false,
        }
    }

    /// 退格；返回 false 表示没有组合中的内容（界面应删除编辑器里的字）。
    /// Backspace; false when nothing is composing (the UI should delete in the editor).
    pub fn backspace(&mut self) -> bool {
        if !self.is_composing() {
            return false;
        }
        // 手写：退掉最后一笔并重新识别。 Handwriting: drop the last stroke and recognise again.
        if self.schema == Schema::Hand {
            self.hand_strokes.pop();
            self.refresh();
            return true;
        }
        let rest_empty = match self.schema {
            Schema::Keypad(_) => self.t9_units.len() <= self.consumed,
            _ => self.raw.len() <= self.consumed,
        };
        if rest_empty {
            if let Some(sel) = self.selected.pop() {
                self.consumed = sel.consumed_before;
            }
        } else if let Schema::Keypad(g) = self.schema {
            if let Some(T9Unit::Syllable { id, .. }) = self.t9_units.pop() {
                // 退掉锁定的拼音：恢复数字（去掉最后一个）。 Unlock and drop one digit.
                let spelling = syllable::spelling(id);
                for b in spelling.bytes().take(spelling.len() - 1) {
                    self.t9_units.push(T9Unit::Digit(g.code(b)));
                }
            }
        } else {
            self.raw.pop();
            self.near.truncate(self.raw.len());
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
            Schema::Keypad(_) => {
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
        if !matches!(self.schema, Schema::Keypad(_)) {
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
        self.hand_strokes.clear();
        self.raw.clear();
        self.near.clear();
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
            // 截断时报上限，界面会一直翻到拿不到为止。 When cut, report the cap; the UI pages until empty.
            total_candidates: if self.cands_more {
                crate::decoder::MAX_CANDIDATES.max(self.cands.len())
            } else {
                self.cands.len()
            },
            pinyin_options: self.t9_options.iter().map(t9_option_label).collect(),
            schema: self.schema.key(),
        }
    }

    /// 分页取候选；超出已生成的部分时补齐完整列表。 Page through candidates, completing the list on demand.
    pub fn candidates(&mut self, offset: usize, limit: usize) -> Vec<CandidateView> {
        if self.cands_more && offset + limit > self.cands.len() {
            self.cand_cap = crate::decoder::MAX_CANDIDATES;
            self.refresh();
            self.cand_cap = CAND_FIRST;
        }
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

    /// 未上屏部分的邻键，与 [Letters::parse] 的字母一一对应（分隔符跳过）；没有或不同步时为空。
    /// Neighbours of the uncommitted part, aligned with the letters of [Letters::parse] (separators skipped);
    /// empty when absent or out of sync.
    fn rest_near(&self) -> Vec<Near> {
        let start = self.consumed.min(self.raw.len());
        // 普通 input_char 输入的键没有邻键记录，这里按长度补齐。 Keys fed via input_char have none; pad by length.
        if self.near.len() > self.raw.len() || self.near.iter().all(|n| n.alt == 0) {
            return Vec::new();
        }
        let mut out = Vec::with_capacity(self.raw.len() - start);
        for (i, b) in self.raw.bytes().enumerate().skip(start) {
            if b.is_ascii_lowercase() {
                out.push(self.near.get(i).copied().unwrap_or(Near::NONE));
            }
        }
        out
    }

    fn skip_separators(&mut self) {
        match self.schema {
            Schema::Keypad(_) => {
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
            Schema::Keypad(_) => T9Input::from_units(&self.t9_units[self.consumed..], self.grouping())
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
            Schema::Keypad(_) => {
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
                let near = self.rest_near();
                (graph::build_full_pinyin_near(&l, &self.options.fuzzy, &near), l.keys)
            }
            Schema::Shuangpin(id) => {
                let keys = self.rest_raw().as_bytes().to_vec();
                (shuangpin::build_graph(id, &keys, &self.options.fuzzy), keys)
            }
            Schema::Keypad(_) => {
                let inp = T9Input::from_units(&self.t9_units[self.consumed..], self.grouping());
                (t9::build_graph(&inp), inp.digits)
            }
            _ => (SyllableGraph::new(0), Vec::new()),
        }
    }

    fn refresh(&mut self) {
        self.cands.clear();
        self.cands_more = false;
        self.t9_options.clear();
        let selected: String = self.selected.iter().map(|s| s.text.as_str()).collect();
        match self.schema {
            s if s.is_pinyin_family() => self.refresh_pinyin(selected),
            Schema::Wubi86 => self.refresh_wubi(),
            Schema::English => self.refresh_english(),
            Schema::Hand => self.refresh_hand(),
            _ => {}
        }
        self.decorate();
    }

    /// 手写：给出当前这个字的全部笔画（每笔是 y 向下的点列），识别并刷新候选；返回是否有候选。
    /// Handwriting: pass all strokes of the current character (y pointing down); recognises and refreshes the
    /// candidates. Returns whether there are any.
    pub fn hand_input(&mut self, strokes: Vec<weave_dict::hand::Stroke>) -> bool {
        if self.schema != Schema::Hand {
            return false;
        }
        self.hand_strokes = strokes;
        self.refresh();
        !self.cands.is_empty()
    }

    fn refresh_hand(&mut self) {
        if self.hand_strokes.is_empty() {
            return;
        }
        if self.hand.is_none() {
            self.hand = self.hand_src.as_ref().and_then(|s| weave_dict::hand::Recognizer::open(s).ok());
        }
        let Some(r) = &self.hand else { return };
        for (c, _) in r.recognize(&self.hand_strokes, HAND_CANDIDATES) {
            let text = c.to_string();
            self.cands.push(Cand {
                view: CandidateView { text: text.clone(), comment: String::new(), user: false },
                action: Action::Table { text },
            });
        }
    }

    /// v 模式（v 后跟数字或算式）：只给计算与数字读法候选。 The v mode: arithmetic and numeral candidates only.
    fn refresh_v_mode(&mut self) -> bool {
        if self.schema != Schema::Pinyin || self.consumed != 0 || !self.selected.is_empty() {
            return false;
        }
        let Some(body) = self.raw.strip_prefix('v') else { return false };
        if !body.chars().next().is_some_and(crate::special::v_accepts) {
            return false;
        }
        self.preedit = self.raw.clone();
        self.cands = crate::special::v_candidates(body)
            .into_iter()
            .map(|(text, comment)| Cand {
                view: CandidateView { text: text.clone(), comment, user: false },
                action: Action::Table { text },
            })
            .collect();
        true
    }

    /// 输入恰为 rq / sj / xq 时，把日期时间插在首选之后。 Date/time after the first candidate for rq / sj / xq.
    fn insert_dates(&mut self) {
        if !self.selected.is_empty() || !matches!(self.rest_raw(), "rq" | "sj" | "xq") {
            return;
        }
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs() as i64)
            .unwrap_or(0);
        let at = self.cands.len().min(1);
        for (k, (text, comment)) in crate::special::date_candidates(self.rest_raw(), now, self.options.utc_offset_min)
            .into_iter()
            .enumerate()
        {
            let c = Cand { view: CandidateView { text: text.clone(), comment, user: false }, action: Action::Table { text } };
            self.cands.insert(at + k, c);
        }
    }

    fn refresh_pinyin(&mut self, selected: String) {
        if self.refresh_v_mode() {
            return;
        }
        let (g, keys) = self.build_graph();
        if g.len == 0 {
            self.preedit = selected;
            return;
        }
        let dec = Decoder {
            lex: self.pinyin.as_ref(),
            packs: &self.packs,
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
        let cands = dec.candidates(&lat, &raw_text, self.cand_cap);
        self.cands_more = cands.len() >= self.cand_cap.min(crate::decoder::MAX_CANDIDATES);
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
        if self.schema.is_pinyin_family() && !matches!(self.schema, Schema::Keypad(_)) {
            self.insert_dates();
        }
        if matches!(self.schema, Schema::Keypad(_)) {
            let inp = T9Input::from_units(&self.t9_units[self.consumed..], self.grouping());
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
        let text = en.text(best.text_id, &key);
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
        let locks: Vec<(usize, usize, T9Unit)> = if matches!(self.schema, Schema::Keypad(_)) {
            T9Input::from_units(&self.t9_units[self.consumed..], self.grouping()).locks
        } else {
            Vec::new()
        };
        for (si, _) in &lat.best {
            let span = &lat.spans[*si];
            let mut s = span.start;
            for (i, &cut) in span.cuts.iter().enumerate() {
                let piece = match self.schema {
                    Schema::Pinyin => {
                        let typed = String::from_utf8_lossy(&keys[s..cut]).into_owned();
                        // 按错的键被纠正（xhong → zhong）时显示纠正后的拼写；模糊音等本身合法的拼写保持原样。
                        // Show the corrected spelling for a fixed typo (xhong → zhong); already valid
                        // spellings (fuzzy sounds etc.) stay as typed.
                        match span.key.get(i).map(|&id| syllable::spelling(id)) {
                            Some(full) if full.len() == typed.len() && full != typed && syllable::id_of(&typed).is_none() => full.to_string(),
                            _ => typed,
                        }
                    }
                    Schema::Shuangpin(_) | Schema::Keypad(_) => match span.key.get(i) {
                        Some(&id) => {
                            let full = syllable::spelling(id);
                            let lock = locks.iter().find(|(ls, le, _)| *ls == s && *le == cut);
                            let locked = lock.is_some();
                            if let Some((_, _, T9Unit::Letter(c))) = lock {
                                (*c as char).to_string()
                            } else if matches!(self.schema, Schema::Keypad(_)) && cut - s < full.len() && !locked {
                                full[..cut - s].to_string()
                            } else if matches!(self.schema, Schema::Keypad(_)) && locked {
                                full.to_string()
                            } else if cut - s == 1 && !matches!(self.schema, Schema::Keypad(_)) {
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
                packs: &self.packs,
                user: &empty,
                graph: &g,
                context: None,
                lm: None,
            };
            let lat = dec.decode();
            let keys = letters.keys.clone();
            let raw_text = |s: usize, e: usize| String::from_utf8_lossy(&keys[s..e]).into_owned();
            let found: Vec<Candidate> = dec
                .candidates(&lat, &raw_text, crate::decoder::MAX_CANDIDATES)
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
/// 从目录收集资源：优先原始文件，没有时找同名 `.wvz` 分块压缩版。
/// Collect resources from a directory: raw files first, falling back to packed `.wvz` copies.
pub fn paths_in(data_dir: &Path, user_dir: &Path) -> Paths {
    let mut paths = Paths {
        user_dir: Some(user_dir.to_path_buf()),
        ..Default::default()
    };
    for (key, name) in RESOURCES {
        let raw = data_dir.join(name);
        let packed = data_dir.join(format!("{key}.wvz"));
        if raw.exists() {
            paths.set(key, Source::file(raw));
        } else if packed.exists() {
            paths.set(key, Source::file(packed));
        }
    }
    // 扩展词库：数据目录与用户目录下的 packs/<id>.wvz（用户目录的同名包优先）。
    // Extra lexicons: packs/<id>.wvz under the data dir and the user dir (the user dir wins on a clash).
    for dir in [data_dir.join("packs"), user_dir.join("packs")] {
        let Ok(rd) = std::fs::read_dir(&dir) else { continue };
        let mut files: Vec<PathBuf> = rd.flatten().map(|e| e.path()).collect();
        files.sort();
        for f in files {
            let is_pack = matches!(f.extension().and_then(|e| e.to_str()), Some("wvz" | "wvl"));
            if let (true, Some(id)) = (is_pack, f.file_stem().and_then(|s| s.to_str()).map(str::to_string)) {
                paths.set(&format!("pack.{id}"), Source::file(f));
            }
        }
    }
    paths
}

/// 扩展词库 id：小写字母、数字与 `-_`，不超过 40 个字符。 Pack ids: lowercase letters, digits, `-_`, ≤ 40 chars.
pub fn valid_pack_id(id: &str) -> bool {
    !id.is_empty() && id.len() <= 40 && id.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-' || b == b'_')
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

    fn pinyin_engine() -> Engine {
        let mut b = Builder::new(Kind::Pinyin);
        let k = |s: &str| weave_dict::syllable::parse_seq(s).unwrap();
        b.insert(&k("ri qi"), "日期", 10);
        b.insert(&k("shi jian"), "时间", 10);
        let mut e = Engine::with_lexicons(Some(Lexicon::from_bytes(b.build()).unwrap()), None, None);
        e.set_schema(Schema::Pinyin);
        e.options.emoji = false;
        e
    }

    #[test]
    fn domain_pack_adds_words_without_pushing_common_ones_down() {
        let mut e = pinyin_engine();
        let k = |s: &str| weave_dict::syllable::parse_seq(s).unwrap();
        let mut b = Builder::new(Kind::Pinyin);
        b.insert(&k("shi jian"), "实践", 30_000);
        b.insert(&k("shi xing"), "室性", 21_000);
        let dir = std::env::temp_dir().join(format!("weave-pack-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let f = dir.join("med.wvl");
        std::fs::write(&f, b.build()).unwrap();
        assert!(e.load_pack("med", &Source::file(f.clone())));
        assert_eq!(e.pack_ids(), ["med".to_string()]);
        typing(&mut e, "shixing");
        assert!(e.snapshot().candidates.iter().any(|c| c.text == "室性"));
        e.clear();
        typing(&mut e, "shijian");
        let c: Vec<String> = e.snapshot().candidates.iter().map(|c| c.text.clone()).collect();
        assert_eq!(c[0], "时间");
        assert!(c.contains(&"实践".to_string()));
        e.clear();
        assert!(e.unload_pack("med"));
        typing(&mut e, "shixing");
        assert!(!e.snapshot().candidates.iter().any(|c| c.text == "室性"));
        assert!(!e.load_pack("Bad Id", &Source::file(f)));
        // 目录里的 packs/<id>.wvl 自动载入。 packs/<id>.wvl in the data dir are picked up.
        std::fs::create_dir_all(dir.join("data/packs")).unwrap();
        std::fs::rename(dir.join("med.wvl"), dir.join("data/packs/med.wvl")).unwrap();
        let p = paths_in(&dir.join("data"), &dir.join("user"));
        assert_eq!(p.packs.len(), 1);
        assert_eq!(p.packs[0].0, "med");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn v_mode_numbers_and_arithmetic() {
        let mut e = pinyin_engine();
        typing(&mut e, "v1234");
        let s = e.snapshot();
        assert_eq!(s.preedit, "v1234");
        assert_eq!(s.candidates[1].text, "壹仟贰佰叁拾肆元整");
        e.select(1);
        assert_eq!(e.snapshot().commit, "壹仟贰佰叁拾肆元整");
        typing(&mut e, "v(128+32)*4");
        assert_eq!(e.snapshot().candidates[0].text, "640");
        e.backspace();
        e.backspace();
        assert_eq!(e.snapshot().preedit, "v(128+32)");
        e.clear();
        // 普通拼音里数字不进组合串。 Digits don't enter normal pinyin.
        typing(&mut e, "ri");
        assert!(!e.input_char('1'));
    }

    #[test]
    fn date_shortcuts_follow_the_first_candidate() {
        let mut e = pinyin_engine();
        typing(&mut e, "rq");
        let s = e.snapshot();
        assert_eq!(s.candidates[0].text, "日期");
        assert!(s.candidates[1].text.contains('年'));
        assert_eq!(s.candidates[1].comment, "日期");
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

#[cfg(test)]
mod hand_tests {
    use super::*;

    fn line(x0: f32, y0: f32, x1: f32, y1: f32) -> weave_dict::hand::Stroke {
        vec![(x0, y0), ((x0 + x1) / 2.0, (y0 + y1) / 2.0), (x1, y1)]
    }

    #[test]
    fn handwriting_schema_recognizes_selects_and_undoes_strokes() {
        let mut b = weave_dict::hand::Builder::default();
        b.push('一', 255, &[line(0.0, 50.0, 100.0, 50.0)]);
        b.push('十', 200, &[line(0.0, 50.0, 100.0, 50.0), line(50.0, 0.0, 50.0, 100.0)]);
        let dir = std::env::temp_dir().join(format!("weave-hand-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let file = dir.join("hand.wvh");
        std::fs::write(&file, b.build()).unwrap();
        let mut e = Engine::new(&Paths { hand: Some(Source::file(&file)), ..Default::default() });
        assert!(e.has_lexicon(Schema::Hand));
        e.set_schema(Schema::Hand);
        assert!(!e.input_char('a'), "keys are not handwriting input");
        assert!(e.hand_input(vec![line(0.0, 50.0, 100.0, 50.0), line(50.0, 0.0, 50.0, 100.0)]));
        assert!(e.is_composing());
        assert_eq!(e.snapshot().candidates[0].text, "十");
        // 退一笔：剩一横，首选变成「一」。 Undo one stroke: a single horizontal is 一.
        assert!(e.backspace());
        assert_eq!(e.snapshot().candidates[0].text, "一");
        assert!(e.select(0));
        let s = e.snapshot();
        assert_eq!(s.commit, "一");
        assert!(!s.composing);
        std::fs::remove_dir_all(&dir).ok();
    }
}
