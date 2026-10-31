//! 双拼方案与音节图构建。 Shuangpin (double pinyin) schemes and graph building.
//!
//! 每个音节固定两键：声母键 + 韵母键；零声母音节按方案各自的规则编码。
//! Every syllable is exactly two keys: initial key + final key; zero-initial syllables follow
//! each scheme's own rule.

use std::collections::HashMap;
use std::sync::OnceLock;

use weave_dict::syllable::{self, SyllableId};

use crate::graph::{penalty, Edge, EdgeKind, FuzzyOptions, SyllableGraph};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum SchemeId {
    /// 小鹤双拼 Xiaohe (Flypy)
    Xiaohe,
    /// 自然码 Ziranma
    Ziranma,
    /// 微软双拼 Microsoft
    Microsoft,
    /// 搜狗双拼 Sogou
    Sogou,
    /// 首道双拼 Shoudao
    Shoudao,
    /// 智能ABC双拼 Zhineng ABC
    ZhinengAbc,
    /// 拼音加加 Pinyin Jiajia
    Jiajia,
}

impl SchemeId {
    pub const ALL: [SchemeId; 7] = [
        SchemeId::Xiaohe,
        SchemeId::Ziranma,
        SchemeId::Microsoft,
        SchemeId::Sogou,
        SchemeId::Shoudao,
        SchemeId::ZhinengAbc,
        SchemeId::Jiajia,
    ];

    pub fn key(self) -> &'static str {
        match self {
            SchemeId::Xiaohe => "xiaohe",
            SchemeId::Ziranma => "ziranma",
            SchemeId::Microsoft => "microsoft",
            SchemeId::Sogou => "sogou",
            SchemeId::Shoudao => "shoudao",
            SchemeId::ZhinengAbc => "znabc",
            SchemeId::Jiajia => "jiajia",
        }
    }

    pub fn from_key(k: &str) -> Option<Self> {
        Self::ALL.into_iter().find(|s| s.key() == k)
    }

    pub fn display_name(self) -> &'static str {
        match self {
            SchemeId::Xiaohe => "小鹤双拼",
            SchemeId::Ziranma => "自然码",
            SchemeId::Microsoft => "微软双拼",
            SchemeId::Sogou => "搜狗双拼",
            SchemeId::Shoudao => "首道双拼",
            SchemeId::ZhinengAbc => "智能ABC双拼",
            SchemeId::Jiajia => "拼音加加",
        }
    }
}

#[derive(Clone, Copy)]
enum ZeroRule {
    /// 单韵母双击；双字母韵母原样；三字母韵母 = 首字母 + 韵母键（小鹤、自然码）。
    /// a→aa, ai→ai, ang→a+key(ang).
    Natural,
    /// 固定 o 键 + 韵母键（微软、搜狗）。 'o' + final key.
    OKey,
    /// 固定 o 键 + 韵母键，不加备用编码（智能ABC）。 'o' + final key, no alternates (Zhineng ABC).
    OKeyStrict,
    /// 零声母首字母 + 韵母键（拼音加加）：aa、ai→as、ou→op、er→eq。 First letter + final key (Jiajia).
    FirstLetter,
    /// 逐个写明的两键（首道）。 Each zero-initial syllable spelled out (Shoudao).
    Explicit(&'static [(&'static str, &'static str)]),
}

struct Scheme {
    zh: u8,
    ch: u8,
    sh: u8,
    finals: &'static [(u8, &'static [&'static str])],
    zero: ZeroRule,
}

const XIAOHE: Scheme = Scheme {
    zh: b'v',
    ch: b'i',
    sh: b'u',
    finals: &[
        (b'q', &["iu"]),
        (b'w', &["ei"]),
        (b'e', &["e"]),
        (b'r', &["uan"]),
        (b't', &["ue", "ve"]),
        (b'y', &["un"]),
        (b'u', &["u"]),
        (b'i', &["i"]),
        (b'o', &["uo", "o"]),
        (b'p', &["ie"]),
        (b'a', &["a"]),
        (b's', &["iong", "ong"]),
        (b'd', &["ai"]),
        (b'f', &["en"]),
        (b'g', &["eng"]),
        (b'h', &["ang"]),
        (b'j', &["an"]),
        (b'k', &["ing", "uai"]),
        (b'l', &["iang", "uang"]),
        (b'z', &["ou"]),
        (b'x', &["ia", "ua"]),
        (b'c', &["ao"]),
        (b'v', &["ui", "v"]),
        (b'b', &["in"]),
        (b'n', &["iao"]),
        (b'm', &["ian"]),
    ],
    zero: ZeroRule::Natural,
};

const ZIRANMA: Scheme = Scheme {
    zh: b'v',
    ch: b'i',
    sh: b'u',
    finals: &[
        (b'q', &["iu"]),
        (b'w', &["ia", "ua"]),
        (b'e', &["e"]),
        (b'r', &["uan"]),
        (b't', &["ue", "ve"]),
        (b'y', &["uai", "ing"]),
        (b'u', &["u"]),
        (b'i', &["i"]),
        (b'o', &["uo", "o"]),
        (b'p', &["un"]),
        (b'a', &["a"]),
        (b's', &["iong", "ong"]),
        (b'd', &["iang", "uang"]),
        (b'f', &["en"]),
        (b'g', &["eng"]),
        (b'h', &["ang"]),
        (b'j', &["an"]),
        (b'k', &["ao"]),
        (b'l', &["ai"]),
        (b'z', &["ei"]),
        (b'x', &["ie"]),
        (b'c', &["iao"]),
        (b'v', &["ui", "v"]),
        (b'b', &["ou"]),
        (b'n', &["in"]),
        (b'm', &["ian"]),
    ],
    zero: ZeroRule::Natural,
};

const MICROSOFT: Scheme = Scheme {
    zh: b'v',
    ch: b'i',
    sh: b'u',
    finals: &[
        (b'q', &["iu"]),
        (b'w', &["ia", "ua"]),
        (b'e', &["e"]),
        (b'r', &["uan", "er"]),
        (b't', &["ue", "ve"]),
        (b'y', &["uai", "v"]),
        (b'u', &["u"]),
        (b'i', &["i"]),
        (b'o', &["uo", "o"]),
        (b'p', &["un"]),
        (b'a', &["a"]),
        (b's', &["iong", "ong"]),
        (b'd', &["iang", "uang"]),
        (b'f', &["en"]),
        (b'g', &["eng"]),
        (b'h', &["ang"]),
        (b'j', &["an"]),
        (b'k', &["ao"]),
        (b'l', &["ai"]),
        (b';', &["ing"]),
        (b'z', &["ei"]),
        (b'x', &["ie"]),
        (b'c', &["iao"]),
        // ue/ve 也可在 V 上输入（微软双拼的备用键）。 ue/ve are also accepted on V.
        (b'v', &["ui", "ue", "ve"]),
        (b'b', &["ou"]),
        (b'n', &["in"]),
        (b'm', &["ian"]),
    ],
    zero: ZeroRule::OKey,
};

const SOGOU: Scheme = Scheme {
    zh: b'v',
    ch: b'i',
    sh: b'u',
    finals: &[
        (b'q', &["iu"]),
        (b'w', &["ia", "ua"]),
        (b'e', &["e"]),
        (b'r', &["uan", "er"]),
        (b't', &["ue", "ve"]),
        (b'y', &["uai", "v"]),
        (b'u', &["u"]),
        (b'i', &["i"]),
        (b'o', &["uo", "o"]),
        (b'p', &["un"]),
        (b'a', &["a"]),
        (b's', &["iong", "ong"]),
        (b'd', &["iang", "uang"]),
        (b'f', &["en"]),
        (b'g', &["eng"]),
        (b'h', &["ang"]),
        (b'j', &["an"]),
        (b'k', &["ao"]),
        (b'l', &["ai"]),
        (b';', &["ing"]),
        (b'z', &["ei"]),
        (b'x', &["ie"]),
        (b'c', &["iao"]),
        (b'v', &["ui"]),
        (b'b', &["ou"]),
        (b'n', &["in"]),
        (b'm', &["ian"]),
    ],
    zero: ZeroRule::OKey,
};

const SHOUDAO: Scheme = Scheme {
    zh: b'v',
    ch: b'i',
    sh: b'e',
    finals: &[
        (b'a', &["a"]),
        (b'b', &["ve"]),
        (b'c', &["in"]),
        (b'd', &["ao"]),
        (b'e', &["e"]),
        (b'f', &["eng"]),
        (b'g', &["ing", "uai"]),
        (b'h', &["ong", "iong"]),
        (b'i', &["i"]),
        (b'j', &["an"]),
        (b'k', &["en", "ia"]),
        (b'l', &["ai", "ue"]),
        (b'm', &["ei"]),
        (b'n', &["ian"]),
        (b'o', &["uo", "o"]),
        (b'p', &["iao"]),
        (b'q', &["iu"]),
        (b'r', &["ie"]),
        (b's', &["ou"]),
        (b't', &["uan"]),
        (b'u', &["u"]),
        (b'v', &["ui", "v"]),
        (b'w', &["ua"]),
        (b'x', &["iang", "uang"]),
        (b'y', &["ang"]),
        (b'z', &["un"]),
    ],
    zero: ZeroRule::Explicit(&[
        ("a", "aa"), ("o", "oo"), ("e", "ue"), ("ai", "ai"), ("ei", "ui"), ("ao", "ao"), ("ou", "ou"),
        ("an", "an"), ("en", "en"), ("ang", "ay"), ("eng", "uf"), ("er", "er"),
    ]),
};

const ZHINENG_ABC: Scheme = Scheme {
    zh: b'a',
    ch: b'e',
    sh: b'v',
    finals: &[
        (b'a', &["a"]),
        (b'b', &["ou"]),
        (b'c', &["in", "uai"]),
        (b'd', &["ia", "ua"]),
        (b'e', &["e"]),
        (b'f', &["en"]),
        (b'g', &["eng"]),
        (b'h', &["ang"]),
        (b'i', &["i"]),
        (b'j', &["an"]),
        (b'k', &["ao"]),
        (b'l', &["ai"]),
        (b'm', &["ui", "ue", "ve"]),
        (b'n', &["un"]),
        (b'o', &["uo", "o"]),
        (b'p', &["uan"]),
        (b'q', &["ei"]),
        (b'r', &["iu", "er"]),
        (b's', &["ong", "iong"]),
        (b't', &["iang", "uang"]),
        (b'u', &["u"]),
        (b'v', &["v"]),
        (b'w', &["ian"]),
        (b'x', &["ie"]),
        (b'y', &["ing"]),
        (b'z', &["iao"]),
    ],
    zero: ZeroRule::OKeyStrict,
};

const JIAJIA: Scheme = Scheme {
    zh: b'v',
    ch: b'u',
    sh: b'i',
    finals: &[
        (b'a', &["a"]),
        (b'b', &["ia", "ua"]),
        (b'c', &["uan"]),
        (b'd', &["ao"]),
        (b'e', &["e"]),
        (b'f', &["an"]),
        (b'g', &["ang"]),
        (b'h', &["iang", "uang"]),
        (b'i', &["i"]),
        (b'j', &["ian"]),
        (b'k', &["iao"]),
        (b'l', &["in"]),
        (b'm', &["ie"]),
        (b'n', &["iu"]),
        (b'o', &["o", "uo"]),
        (b'p', &["ou"]),
        (b'q', &["ing", "er"]),
        (b'r', &["en"]),
        (b's', &["ai"]),
        (b't', &["eng"]),
        (b'u', &["u"]),
        (b'v', &["ui", "v"]),
        (b'w', &["ei"]),
        (b'x', &["ue", "ve", "uai"]),
        (b'y', &["ong", "iong"]),
        (b'z', &["un"]),
    ],
    zero: ZeroRule::FirstLetter,
};

fn scheme(id: SchemeId) -> &'static Scheme {
    match id {
        SchemeId::Xiaohe => &XIAOHE,
        SchemeId::Ziranma => &ZIRANMA,
        SchemeId::Microsoft => &MICROSOFT,
        SchemeId::Sogou => &SOGOU,
        SchemeId::Shoudao => &SHOUDAO,
        SchemeId::ZhinengAbc => &ZHINENG_ABC,
        SchemeId::Jiajia => &JIAJIA,
    }
}

impl Scheme {
    fn initial_key(&self, ini: &str) -> u8 {
        match ini {
            "zh" => self.zh,
            "ch" => self.ch,
            "sh" => self.sh,
            other => other.as_bytes()[0],
        }
    }

    /// 韵母所在的全部键（第一个为主键）。 All keys carrying a final; the first is primary.
    fn final_keys(&self, fin: &str) -> Vec<u8> {
        self.finals.iter().filter(|(_, fs)| fs.contains(&fin)).map(|(k, _)| *k).collect()
    }

    /// 两个音节同声母，且韵母的键有交集（方案内的共键）。 Same initial, and their final keys overlap.
    #[cfg(test)]
    fn shares_final_key(&self, a: &str, b: &str) -> bool {
        let (ia, fa) = syllable::split(a);
        let (ib, fb) = syllable::split(b);
        ia == ib && self.final_keys(fa).iter().any(|k| self.final_keys(fb).contains(k))
    }

    /// 音节的全部可接受编码，主编码在前。与各方案的通行键位一致：
    /// - jqxy 后的 u（实为 ü）也可用 ü 所在的键；
    /// - 零声母：小鹤/自然码主编码为「单韵母双击、双字母原样、三字母首字母+韵母键」，另收「首字母+韵母键」；
    ///   微软/搜狗主编码为「o + 韵母键」，另收 a/e 开头的「首字母+韵母键」与 ou 原样。
    ///
    /// All accepted key pairs, primary first: ü keys after jqxy; zero-initial alternates per scheme.
    fn encodings(&self, s: &str) -> Vec<[u8; 2]> {
        let (ini, fin) = syllable::split(s);
        let mut out: Vec<[u8; 2]> = Vec::new();
        let mut push = |k: [u8; 2]| {
            if !out.contains(&k) {
                out.push(k);
            }
        };
        if ini.is_empty() {
            let first = fin.as_bytes()[0];
            let keys = self.final_keys(fin);
            match self.zero {
                ZeroRule::Natural => {
                    match fin.len() {
                        1 => push([first, first]),
                        2 => push([first, fin.as_bytes()[1]]),
                        _ => {}
                    }
                    for k in keys {
                        push([first, k]);
                    }
                }
                ZeroRule::Explicit(table) => {
                    if let Some((_, code)) = table.iter().find(|(f, _)| *f == fin) {
                        push(code.as_bytes().try_into().expect("two-key code"));
                    }
                }
                ZeroRule::FirstLetter => {
                    for k in keys {
                        push([first, k]);
                    }
                }
                ZeroRule::OKeyStrict => {
                    for k in keys {
                        push([b'o', k]);
                    }
                }
                ZeroRule::OKey => {
                    for &k in &keys {
                        push([b'o', k]);
                    }
                    if first == b'a' || first == b'e' {
                        for &k in &keys {
                            push([first, k]);
                        }
                    }
                    if fin == "ou" {
                        push(*b"ou");
                    }
                }
            }
            return out;
        }
        let ik = self.initial_key(ini);
        for k in self.final_keys(fin) {
            push([ik, k]);
        }
        if fin == "u" && matches!(ini, "j" | "q" | "x" | "y") {
            for k in self.final_keys("v") {
                push([ik, k]);
            }
        }
        out
    }
}

/// 预计算的方案表。 Precomputed scheme tables.
pub struct Table {
    /// 两键 → 音节；极少数方案里两个音节共用一对键（小鹤 lo/luo），交给语言模型区分。
    /// Keys → syllables; a few schemes share a pair between two syllables (Xiaohe lo/luo).
    decode: HashMap<[u8; 2], Vec<SyllableId>>,
    encode: HashMap<SyllableId, [u8; 2]>,
    /// 首键 → 以该键开头的音节（用于末尾单键）。 First key → syllables (trailing single key).
    by_first: HashMap<u8, Vec<SyllableId>>,
}

pub fn table(id: SchemeId) -> &'static Table {
    static TABLES: OnceLock<HashMap<SchemeId, Table>> = OnceLock::new();
    &TABLES.get_or_init(|| {
        SchemeId::ALL
            .into_iter()
            .map(|sid| {
                let sch = scheme(sid);
                let mut t = Table {
                    decode: HashMap::new(),
                    encode: HashMap::new(),
                    by_first: HashMap::new(),
                };
                for sy in syllable::all_ids() {
                    let all = sch.encodings(syllable::spelling(sy));
                    if let Some(&primary) = all.first() {
                        t.encode.insert(sy, primary);
                        t.by_first.entry(primary[0]).or_default().push(sy);
                    }
                    for keys in all {
                        let list = t.decode.entry(keys).or_default();
                        if !list.contains(&sy) {
                            list.push(sy);
                        }
                    }
                }
                (sid, t)
            })
            .collect()
    })[&id]
}

impl Table {
    pub fn decode(&self, a: u8, b: u8) -> &[SyllableId] {
        self.decode
            .get(&[a, b])
            .map(|v| v.as_slice())
            .unwrap_or(&[])
    }

    pub fn encode(&self, s: SyllableId) -> Option<[u8; 2]> {
        self.encode.get(&s).copied()
    }
}

/// 该字符是否可能出现在双拼输入中。 Whether a char may appear in shuangpin input.
pub fn is_input_key(c: u8) -> bool {
    c.is_ascii_lowercase() || c == b';'
}

/// 为双拼输入建音节图。 Build the syllable graph for shuangpin input.
/// 九键 / 14 键的双拼建图：每次按键对应一个字母组（如 14 键的 `qw`），一个音节是两次按键。
/// 两个按键组里的每一对字母都查双拼表，所以歧义由解码器与语言模型一起消解。
/// Keypad shuangpin: each tap is a letter group (such as `qw` on 14 keys); a syllable is two taps.
/// Every letter pair across the two groups is looked up, and the decoder with the language model resolves ambiguity.
pub fn build_keypad_graph(id: SchemeId, taps: &[u8], grouping: crate::t9::Grouping) -> SyllableGraph {
    let t = table(id);
    let n = taps.len();
    let mut g = SyllableGraph::new(n);
    let letters: Vec<&str> = taps.iter().map(|&k| grouping.letters(k)).collect();
    for i in 0..n {
        if i + 1 < n {
            let mut seen: Vec<SyllableId> = Vec::new();
            for a in letters[i].bytes() {
                for b in letters[i + 1].bytes() {
                    for &sy in t.decode(a, b) {
                        if !seen.contains(&sy) {
                            seen.push(sy);
                        }
                    }
                }
            }
            if !seen.is_empty() {
                g.push(Edge {
                    start: i,
                    end: i + 2,
                    syls: seen,
                    penalty: 0,
                    kind: EdgeKind::Full,
                    bits: Vec::new(),
                });
            }
        } else {
            // 末尾的单键：只写了声母键，取各字母开头的音节（与双拼的单键补全相同）。
            // A trailing single tap: the syllables starting with any of its letters, as in the plain shuangpin.
            let mut starts: Vec<SyllableId> = Vec::new();
            for a in letters[i].bytes() {
                if let Some(v) = t.by_first.get(&a) {
                    for &sy in v {
                        if !starts.contains(&sy) {
                            starts.push(sy);
                        }
                    }
                }
            }
            if !starts.is_empty() {
                g.push(Edge {
                    start: i,
                    end: i + 1,
                    syls: starts,
                    penalty: penalty::ABBREV_END,
                    kind: EdgeKind::Abbrev,
                    bits: Vec::new(),
                });
            }
        }
    }
    g.ensure_connected();
    g
}

pub fn build_graph(id: SchemeId, keys: &[u8], fuzzy: &FuzzyOptions) -> SyllableGraph {
    build_graph_with_step(id, keys, fuzzy, 2)
}

/// An English span can have odd length. Allow the same scheme's pairs to resume
/// at either parity; the session only uses this graph when a Latin span wins.
pub(crate) fn build_mixed_graph(id: SchemeId, keys: &[u8], fuzzy: &FuzzyOptions) -> SyllableGraph {
    build_graph_with_step(id, keys, fuzzy, 1)
}

fn build_graph_with_step(id: SchemeId, keys: &[u8], fuzzy: &FuzzyOptions, step: usize) -> SyllableGraph {
    let t = table(id);
    let n = keys.len();
    let mut g = SyllableGraph::new(n);
    let mut i = 0;
    while i < n {
        if i + 1 < n {
            let syls = t.decode(keys[i], keys[i + 1]);
            if !syls.is_empty() {
                g.push(Edge {
                    start: i,
                    end: i + 2,
                    syls: syls.to_vec(),
                    penalty: 0,
                    kind: EdgeKind::Full,
                    bits: Vec::new(),
                });
                for &sy in syls {
                    for v in fuzzy.variants(syllable::spelling(sy)) {
                        g.push(Edge {
                            start: i,
                            end: i + 2,
                            syls: vec![v],
                            penalty: penalty::FUZZY,
                            kind: EdgeKind::Fuzzy,
                            bits: Vec::new(),
                        });
                    }
                }
            }
        } else if let Some(syls) = t.by_first.get(&keys[i]) {
            g.push(Edge {
                start: i,
                end: i + 1,
                syls: syls.clone(),
                penalty: penalty::ABBREV_END,
                kind: EdgeKind::Abbrev,
                bits: Vec::new(),
            });
        }
        i += step;
    }
    g.ensure_connected();
    g
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_syllable_roundtrips_without_collision() {
        for sid in SchemeId::ALL {
            let sch = scheme(sid);
            let mut seen: HashMap<[u8; 2], &str> = HashMap::new();
            for sy in syllable::all_ids() {
                let s = syllable::spelling(sy);
                let all = sch.encodings(s);
                assert!(!all.is_empty(), "{sid:?} cannot encode {s}");
                // 主编码与备用编码都不能和别的音节撞键。同声母且韵母共用一键的一对（首道 en/ia，智能ABC ui/ue/ve）
                // 是方案本身的共键，交给语言模型区分；lo/luo 同理。
                // Neither primary nor alternate pairs may collide, except shared keys within one initial whose finals
                // share a key (Shoudao en/ia, Zhineng ABC ui/ue/ve): the language model tells them apart.
                for k in all {
                    if let Some(prev) = seen.insert(k, s) {
                        let known = [("lo", "luo")];
                        let shared_final = sch.shares_final_key(prev, s);
                        assert!(
                            prev == s || known.contains(&(prev, s)) || known.contains(&(s, prev)) || shared_final,
                            "{sid:?}: {s} and {prev} both encode to {:?}",
                            std::str::from_utf8(&k)
                        );
                    }
                }
            }
        }
    }

    /// 与 Rime 官方双拼方案的拼写规则逐条对照的样例。 Cases checked against Rime's schemes.
    #[test]
    fn matches_reference_layouts() {
        let dec = |sid: SchemeId, keys: &str| -> Vec<&'static str> {
            let k = keys.as_bytes();
            table(sid).decode(k[0], k[1]).iter().map(|&s| syllable::spelling(s)).collect()
        };
        use SchemeId::*;
        for (sid, keys, want) in [
            (Xiaohe, "ai", "ai"), (Xiaohe, "ad", "ai"), (Xiaohe, "ah", "ang"), (Xiaohe, "eg", "eng"),
            (Xiaohe, "oz", "ou"), (Xiaohe, "ou", "ou"), (Xiaohe, "jv", "ju"), (Xiaohe, "lt", "lve"),
            (Xiaohe, "ek", "e"), (Ziranma, "vy", "zhuai"), (Ziranma, "xy", "xing"), (Ziranma, "ak", "ao"),
            (Microsoft, "ol", "ai"), (Microsoft, "al", "ai"), (Microsoft, "or", "er"), (Microsoft, "ob", "ou"),
            (Microsoft, "lt", "lve"), (Microsoft, "lv", "lve"), (Microsoft, "ly", "lv"), (Microsoft, "x;", "xing"),
            (Sogou, "ol", "ai"), (Sogou, "al", "ai"), (Sogou, "lt", "lve"), (Sogou, "jy", "ju"), (Sogou, "x;", "xing"),
            // 首道：zh→V、ch→I、sh→E；零声母 e→UE、ang→AY。 Shoudao: zh V, ch I, sh E; zero e→ue, ang→ay.
            (Shoudao, "vh", "zhong"), (Shoudao, "ie", "che"), (Shoudao, "ue", "e"), (Shoudao, "ay", "ang"), (Shoudao, "ai", "ai"),
            // 智能ABC：zh→A、ch→E、sh→V；零声母 O + 韵母键。 Zhineng ABC: zh A, ch E, sh V; zero O + final key.
            (ZhinengAbc, "as", "zhong"), (ZhinengAbc, "ol", "ai"), (ZhinengAbc, "or", "er"), (ZhinengAbc, "oa", "a"),
            // 拼音加加：zh→V、ch→U、sh→I；零声母首字母 + 韵母键。 Jiajia: zh V, ch U, sh I; zero first letter + final key.
            (Jiajia, "vy", "zhong"), (Jiajia, "as", "ai"), (Jiajia, "eq", "er"), (Jiajia, "op", "ou"), (Jiajia, "aa", "a"),
        ] {
            if sid == Xiaohe && keys == "ek" {
                // 非法音节：确认不会被解码。 Invalid syllables must not decode.
                assert!(dec(sid, keys).is_empty(), "{sid:?} {keys} → {:?}", dec(sid, keys));
                continue;
            }
            assert!(dec(sid, keys).contains(&want), "{sid:?} {keys} → {:?}, want {want}", dec(sid, keys));
        }
    }

    #[test]
    fn xiaohe_examples() {
        let t = table(SchemeId::Xiaohe);
        let dec = |s: &str| {
            syllable::spelling(*t.decode(s.as_bytes()[0], s.as_bytes()[1]).last().unwrap())
        };
        assert_eq!(dec("vs"), "zhong");
        assert_eq!(dec("go"), "guo");
        assert_eq!(dec("rf"), "ren");
        assert_eq!(dec("ah"), "ang");
        assert_eq!(dec("aa"), "a");
        assert_eq!(dec("lv"), "lv");
        assert_eq!(dec("xm"), "xian");
    }

    #[test]
    fn microsoft_uses_semicolon() {
        let t = table(SchemeId::Microsoft);
        assert_eq!(syllable::spelling(t.decode(b'x', b';')[0]), "xing");
        assert_eq!(syllable::spelling(t.decode(b'o', b'l')[0]), "ai");
    }

    #[test]
    fn keypad_shuangpin_resolves_two_taps_through_their_letter_groups() {
        use crate::t9::Grouping;
        // 14 键：v 在 cv（L），s 在 as（F）。zhong = zh(v) + ong(s)。
        let taps = [Grouping::Fourteen.code(b'v'), Grouping::Fourteen.code(b's')];
        let g = build_keypad_graph(SchemeId::Xiaohe, &taps, Grouping::Fourteen);
        assert!(g.out[0].iter().any(|e| e.end == 2 && e.syls.iter().any(|&s| syllable::spelling(s) == "zhong")));
        // 九键：v 在 8（tuv），s 在 7（pqrs）。
        let taps = [Grouping::Nine.code(b'v'), Grouping::Nine.code(b's')];
        let g = build_keypad_graph(SchemeId::Xiaohe, &taps, Grouping::Nine);
        assert!(g.out[0].iter().any(|e| e.end == 2 && e.syls.iter().any(|&s| syllable::spelling(s) == "zhong")));
    }

    #[test]
    fn graph_pairs() {
        let g = build_graph(SchemeId::Xiaohe, b"vsgov", &FuzzyOptions::default());
        assert_eq!(g.out[0][0].end, 2);
        assert_eq!(g.out[2][0].end, 4);
        let last = &g.out[4][0];
        assert_eq!(last.kind, EdgeKind::Abbrev);
        assert!(last.syls.contains(&syllable::id_of("zhong").unwrap()));
    }
}
