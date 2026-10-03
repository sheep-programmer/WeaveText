//! 拼音音节表 / Pinyin syllable inventory.
//!
//! 音节 ID 是词库二进制格式的一部分，**只能在末尾追加，不能重排**。
//! Syllable ids are part of the on-disk format: append only, never reorder.
//!
//! ID 0 保留不用；合法 ID 为 1..=SYLLABLES.len()。
//! Id 0 is reserved; valid ids are 1..=SYLLABLES.len().

use std::collections::HashMap;
use std::sync::OnceLock;

pub type SyllableId = u16;

/// 按字母序排列的全部音节（ü 写作 v）。 All syllables, alphabetical, ü written as v.
pub const SYLLABLES: &[&str] = &[
    "a", "ai", "an", "ang", "ao", "ba", "bai", "ban", "bang", "bao", "bei", "ben", "beng", "bi",
    "bian", "biang", "biao", "bie", "bin", "bing", "bo", "bu", "ca", "cai", "can", "cang", "cao",
    "ce", "cei", "cen", "ceng", "cha", "chai", "chan", "chang", "chao", "che", "chen", "cheng",
    "chi", "chong", "chou", "chu", "chua", "chuai", "chuan", "chuang", "chui", "chun", "chuo",
    "ci", "cong", "cou", "cu", "cuan", "cui", "cun", "cuo", "da", "dai", "dan", "dang", "dao",
    "de", "dei", "den", "deng", "di", "dia", "dian", "diao", "die", "ding", "diu", "dong", "dou",
    "du", "duan", "dui", "dun", "duo", "e", "ei", "en", "eng", "er", "fa", "fan", "fang", "fei",
    "fen", "feng", "fiao", "fo", "fou", "fu", "ga", "gai", "gan", "gang", "gao", "ge", "gei",
    "gen", "geng", "gong", "gou", "gu", "gua", "guai", "guan", "guang", "gui", "gun", "guo", "ha",
    "hai", "han", "hang", "hao", "he", "hei", "hen", "heng", "hong", "hou", "hu", "hua", "huai",
    "huan", "huang", "hui", "hun", "huo", "ji", "jia", "jian", "jiang", "jiao", "jie", "jin",
    "jing", "jiong", "jiu", "ju", "juan", "jue", "jun", "ka", "kai", "kan", "kang", "kao", "ke",
    "kei", "ken", "keng", "kong", "kou", "ku", "kua", "kuai", "kuan", "kuang", "kui", "kun", "kuo",
    "la", "lai", "lan", "lang", "lao", "le", "lei", "leng", "li", "lia", "lian", "liang", "liao",
    "lie", "lin", "ling", "liu", "lo", "long", "lou", "lu", "luan", "lun", "luo", "lv", "lve",
    "ma", "mai", "man", "mang", "mao", "me", "mei", "men", "meng", "mi", "mian", "miao", "mie",
    "min", "ming", "miu", "mo", "mou", "mu", "na", "nai", "nan", "nang", "nao", "ne", "nei", "nen",
    "neng", "ni", "nia", "nian", "niang", "niao", "nie", "nin", "ning", "niu", "nong", "nou", "nu",
    "nuan", "nun", "nuo", "nv", "nve", "o", "ou", "pa", "pai", "pan", "pang", "pao", "pei", "pen",
    "peng", "pi", "pian", "piao", "pie", "pin", "ping", "po", "pou", "pu", "qi", "qia", "qian",
    "qiang", "qiao", "qie", "qin", "qing", "qiong", "qiu", "qu", "quan", "que", "qun", "ran",
    "rang", "rao", "re", "ren", "reng", "ri", "rong", "rou", "ru", "rua", "ruan", "rui", "run",
    "ruo", "sa", "sai", "san", "sang", "sao", "se", "sen", "seng", "sha", "shai", "shan", "shang",
    "shao", "she", "shei", "shen", "sheng", "shi", "shou", "shu", "shua", "shuai", "shuan",
    "shuang", "shui", "shun", "shuo", "si", "song", "sou", "su", "suan", "sui", "sun", "suo", "ta",
    "tai", "tan", "tang", "tao", "te", "tei", "teng", "ti", "tian", "tiao", "tie", "ting", "tong",
    "tou", "tu", "tuan", "tui", "tun", "tuo", "wa", "wai", "wan", "wang", "wei", "wen", "weng",
    "wo", "wu", "xi", "xia", "xian", "xiang", "xiao", "xie", "xin", "xing", "xiong", "xiu", "xu",
    "xuan", "xue", "xun", "ya", "yan", "yang", "yao", "ye", "yi", "yin", "ying", "yo", "yong",
    "you", "yu", "yuan", "yue", "yun", "za", "zai", "zan", "zang", "zao", "ze", "zei", "zen",
    "zeng", "zha", "zhai", "zhan", "zhang", "zhao", "zhe", "zhei", "zhen", "zheng", "zhi", "zhong",
    "zhou", "zhu", "zhua", "zhuai", "zhuan", "zhuang", "zhui", "zhun", "zhuo", "zi", "zong", "zou",
    "zu", "zuan", "zui", "zun", "zuo",
];

/// 输入别名：用户可能敲的另一种拼写 → 规范音节。 Alternative spellings users type.
const ALIASES: &[(&str, &str)] = &[("lue", "lve"), ("nue", "nve")];

/// 声母（含零声母用 ""）。 Initials, longest first so `zh` wins over `z`.
pub const INITIALS: &[&str] = &[
    "zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "j", "q", "x", "r",
    "z", "c", "s", "y", "w",
];

/// 音节表的指纹，写入词库文件头用于校验。 Fingerprint stored in dictionary headers.
pub fn table_fingerprint() -> u32 {
    // FNV-1a over the joined table.
    let mut h: u32 = 0x811c9dc5;
    for s in SYLLABLES {
        for b in s.bytes().chain(std::iter::once(b',')) {
            h ^= b as u32;
            h = h.wrapping_mul(0x01000193);
        }
    }
    h
}

fn index() -> &'static HashMap<&'static str, SyllableId> {
    static INDEX: OnceLock<HashMap<&'static str, SyllableId>> = OnceLock::new();
    INDEX.get_or_init(|| {
        let mut m: HashMap<&'static str, SyllableId> = SYLLABLES
            .iter()
            .enumerate()
            .map(|(i, s)| (*s, (i + 1) as SyllableId))
            .collect();
        for (alias, target) in ALIASES {
            let id = m[target];
            m.insert(alias, id);
        }
        m
    })
}

/// 拼写 → 音节 ID（接受别名）。 Spelling → id, aliases accepted.
pub fn id_of(spelling: &str) -> Option<SyllableId> {
    index().get(spelling).copied()
}

/// 音节 ID → 规范拼写；越界的 ID（损坏或旧版的用户数据）给空串，不让整个词库因为一条坏数据崩掉。
/// Id → canonical spelling; an out-of-range id (corrupt or outdated user data) gives "" instead of a panic.
pub fn spelling(id: SyllableId) -> &'static str {
    (id as usize).checked_sub(1).and_then(|i| SYLLABLES.get(i)).copied().unwrap_or("")
}

pub fn count() -> usize {
    SYLLABLES.len()
}

/// 所有 ID 的迭代器。 Iterator over all ids.
pub fn all_ids() -> impl Iterator<Item = SyllableId> {
    1..=(SYLLABLES.len() as SyllableId)
}

/// 拆成 (声母, 韵母)。零声母音节的声母为 ""；y/w 视为声母。
/// Split into (initial, final); zero-initial syllables yield "", y/w count as initials.
pub fn split(s: &str) -> (&str, &str) {
    for ini in INITIALS {
        if let Some(rest) = s.strip_prefix(ini) {
            if !rest.is_empty() {
                return (ini, rest);
            }
        }
    }
    ("", s)
}

/// 输入串是否是某个声母。 Whether the string is exactly an initial.
pub fn is_initial(s: &str) -> bool {
    INITIALS.contains(&s)
}

/// 以 `prefix` 开头的所有音节（含完全相等）。 All syllables starting with `prefix`.
pub fn with_prefix(prefix: &str) -> impl Iterator<Item = SyllableId> + '_ {
    SYLLABLES
        .iter()
        .enumerate()
        .filter(move |(_, s)| s.starts_with(prefix))
        .map(|(i, _)| (i + 1) as SyllableId)
}

/// 是否存在以 `prefix` 开头的音节。 Whether any syllable (or alias) starts with `prefix`.
pub fn is_prefix(prefix: &str) -> bool {
    SYLLABLES.iter().any(|s| s.starts_with(prefix))
        || ALIASES.iter().any(|(a, _)| a.starts_with(prefix))
}

/// 用空格分隔的拼音串 → ID 序列；任何一个音节非法则返回 None。
/// Space separated pinyin → ids; None if any syllable is invalid.
pub fn parse_seq(pinyin: &str) -> Option<Vec<SyllableId>> {
    pinyin.split_whitespace().map(id_of).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sorted_and_unique() {
        for w in SYLLABLES.windows(2) {
            assert!(w[0] < w[1], "{} !< {}", w[0], w[1]);
        }
    }

    #[test]
    fn roundtrip() {
        for id in all_ids() {
            assert_eq!(id_of(spelling(id)), Some(id));
        }
        assert_eq!(id_of("lue"), id_of("lve"));
    }

    #[test]
    fn splitting() {
        assert_eq!(split("zhuang"), ("zh", "uang"));
        assert_eq!(split("ang"), ("", "ang"));
        assert_eq!(split("yi"), ("y", "i"));
        assert_eq!(split("er"), ("", "er"));
    }
}
