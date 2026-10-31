//! 注音（标准键盘）：每个按键是一个注音符号，一个音节由「声母、介音、韵母、声调」组成，声调可省。
//! 音节转成拼音后交给拼音词典与解码器，因此候选词与拼音输入完全相同。
//!
//! Bopomofo on the standard keyboard: each key is one symbol; a syllable is initial, medial, final and an optional
//! tone. A parsed syllable becomes a pinyin syllable, so the dictionary and decoder are the same as for pinyin.

use crate::graph::{Edge, EdgeKind, SyllableGraph};
use weave_dict::syllable::{self, SyllableId};

/// 最长的音节按键数（声母 + 介音 + 韵母 + 声调）。 Longest syllable in keys (initial, medial, final, tone).
pub const MAX_KEYS: usize = 5;

/// 按键 → 注音符号（标准键盘）。 Key → bopomofo symbol, standard layout.
const SYMBOLS: [(u8, char); 41] = [
    (b'1', 'ㄅ'), (b'q', 'ㄆ'), (b'a', 'ㄇ'), (b'z', 'ㄈ'), (b'2', 'ㄉ'), (b'w', 'ㄊ'), (b's', 'ㄋ'), (b'x', 'ㄌ'),
    (b'e', 'ㄍ'), (b'd', 'ㄎ'), (b'c', 'ㄏ'), (b'r', 'ㄐ'), (b'f', 'ㄑ'), (b'v', 'ㄒ'), (b'5', 'ㄓ'), (b't', 'ㄔ'),
    (b'g', 'ㄕ'), (b'b', 'ㄖ'), (b'y', 'ㄗ'), (b'h', 'ㄘ'), (b'n', 'ㄙ'), (b'u', 'ㄧ'), (b'j', 'ㄨ'), (b'm', 'ㄩ'),
    (b'8', 'ㄚ'), (b'i', 'ㄛ'), (b'k', 'ㄜ'), (b',', 'ㄝ'), (b'9', 'ㄞ'), (b'o', 'ㄟ'), (b'l', 'ㄠ'), (b'.', 'ㄡ'),
    (b'0', 'ㄢ'), (b'p', 'ㄣ'), (b';', 'ㄤ'), (b'/', 'ㄥ'), (b'-', 'ㄦ'),
    (b'3', 'ˇ'), (b'4', 'ˋ'), (b'6', 'ˊ'), (b'7', '˙'),
];

/// 声调键：3 ˇ、4 ˋ、6 ˊ、7 ˙（第一声不用键，由空格决定）。 Tone keys: 3 ˇ, 4 ˋ, 6 ˊ, 7 ˙.
const TONES: [u8; 4] = [b'3', b'4', b'6', b'7'];

/// 按键的注音符号；不是注音键时返回 None（声调键也返回它们的符号）。
pub fn symbol(key: u8) -> Option<char> {
    SYMBOLS.iter().find(|(k, _)| *k == key).map(|(_, s)| *s)
}

/// 是否是注音键盘上的键（包括声调）。 Whether the key belongs to the bopomofo keyboard, tones included.
pub fn is_key(key: u8) -> bool {
    symbol(key).is_some()
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Part {
    Initial(&'static str),
    Medial(&'static str),
    Final(&'static str),
}

fn part(key: u8) -> Option<Part> {
    Some(match key {
        b'1' => Part::Initial("b"),
        b'q' => Part::Initial("p"),
        b'a' => Part::Initial("m"),
        b'z' => Part::Initial("f"),
        b'2' => Part::Initial("d"),
        b'w' => Part::Initial("t"),
        b's' => Part::Initial("n"),
        b'x' => Part::Initial("l"),
        b'e' => Part::Initial("g"),
        b'd' => Part::Initial("k"),
        b'c' => Part::Initial("h"),
        b'r' => Part::Initial("j"),
        b'f' => Part::Initial("q"),
        b'v' => Part::Initial("x"),
        b'5' => Part::Initial("zh"),
        b't' => Part::Initial("ch"),
        b'g' => Part::Initial("sh"),
        b'b' => Part::Initial("r"),
        b'y' => Part::Initial("z"),
        b'h' => Part::Initial("c"),
        b'n' => Part::Initial("s"),
        b'u' => Part::Medial("i"),
        b'j' => Part::Medial("u"),
        b'm' => Part::Medial("v"),
        b'8' => Part::Final("a"),
        b'i' => Part::Final("o"),
        b'k' => Part::Final("e"),
        b',' => Part::Final("ê"),
        b'9' => Part::Final("ai"),
        b'o' => Part::Final("ei"),
        b'l' => Part::Final("ao"),
        b'.' => Part::Final("ou"),
        b'0' => Part::Final("an"),
        b'p' => Part::Final("en"),
        b';' => Part::Final("ang"),
        b'/' => Part::Final("eng"),
        b'-' => Part::Final("er"),
        _ => return None,
    })
}

/// 介音加韵母 → 拼音的韵部（不含声母）。Medial + final → the pinyin rhyme (without initial).
fn rhyme(medial: &str, fin: &str) -> Option<&'static str> {
    Some(match (medial, fin) {
        ("", "a") => "a", ("", "o") => "o", ("", "e") => "e", ("", "ai") => "ai", ("", "ei") => "ei",
        ("", "ao") => "ao", ("", "ou") => "ou", ("", "an") => "an", ("", "en") => "en", ("", "ang") => "ang",
        ("", "eng") => "eng", ("", "er") => "er",
        ("i", "") => "i", ("i", "a") => "ia", ("i", "ê") => "ie", ("i", "ao") => "iao", ("i", "ou") => "iu",
        ("i", "an") => "ian", ("i", "en") => "in", ("i", "ang") => "iang", ("i", "eng") => "ing",
        ("u", "") => "u", ("u", "a") => "ua", ("u", "o") => "uo", ("u", "ai") => "uai", ("u", "ei") => "ui",
        ("u", "an") => "uan", ("u", "en") => "un", ("u", "ang") => "uang", ("u", "eng") => "ong",
        ("v", "") => "v", ("v", "ê") => "ve", ("v", "an") => "van", ("v", "en") => "vn", ("v", "eng") => "iong",
        _ => return None,
    })
}

/// 声母 + 韵部 → 拼音音节（声母为空时用零声母写法）。Initial + rhyme → pinyin spelling.
fn spell(initial: &str, rhyme: &str) -> Option<String> {
    if initial.is_empty() {
        return Some(match rhyme {
            "i" => "yi", "ia" => "ya", "ie" => "ye", "iao" => "yao", "iu" => "you", "ian" => "yan", "in" => "yin",
            "iang" => "yang", "ing" => "ying", "u" => "wu", "ua" => "wa", "uo" => "wo", "uai" => "wai", "ui" => "wei",
            "uan" => "wan", "un" => "wen", "uang" => "wang", "ong" => "weng", "v" => "yu", "ve" => "yue",
            "van" => "yuan", "vn" => "yun", "iong" => "yong",
            other => other,
        }
        .to_string());
    }
    let jqx = matches!(initial, "j" | "q" | "x");
    if rhyme.is_empty() {
        // 只有声母：z、c、s、zh、ch、sh、r 后面要补 i（zhi、ci、ri），其余声母单独不成音节。
        // A bare initial is a syllable only for the apical ones (zhi, ci, ri ...).
        return matches!(initial, "zh" | "ch" | "sh" | "r" | "z" | "c" | "s").then(|| format!("{initial}i"));
    }
    if jqx && rhyme.starts_with('u') {
        return None;
    }
    let rhyme = if jqx && rhyme.starts_with('v') { format!("u{}", &rhyme[1..]) } else { rhyme.to_string() };
    Some(format!("{initial}{rhyme}"))
}

/// 一个音节（不含声调）→ 拼音音节。Syllable without its tone → pinyin spelling.
pub fn spelling(keys: &[u8]) -> Option<String> {
    let mut i = 0;
    let mut initial = "";
    let mut medial = "";
    let mut fin = "";
    if let Some(Part::Initial(s)) = keys.get(i).and_then(|&k| part(k)) {
        initial = s;
        i += 1;
    }
    if let Some(Part::Medial(s)) = keys.get(i).and_then(|&k| part(k)) {
        medial = s;
        i += 1;
    }
    if let Some(Part::Final(s)) = keys.get(i).and_then(|&k| part(k)) {
        fin = s;
        i += 1;
    }
    if i != keys.len() {
        return None;
    }
    // ㄝ（ê）只与介音 ㄧ、ㄩ 组成音节；单独的 ㄝ 不是拼音音节。
    if fin == "ê" && medial.is_empty() {
        return None;
    }
    // 只有声母：z、c、s、zh、ch、sh、r 后补 i（ㄓ → zhi）。
    if medial.is_empty() && fin.is_empty() {
        return spell(initial, "");
    }
    spell(initial, rhyme(medial, fin)?)
}

/// 一个完整的注音音节（可带声调）→ 拼音音节的 id。 A complete syllable (tone optional) → syllable id.
pub fn syllable_at(keys: &[u8]) -> Option<SyllableId> {
    let body = match keys.last() {
        Some(&t) if TONES.contains(&t) => &keys[..keys.len() - 1],
        _ => keys,
    };
    if body.is_empty() {
        return None;
    }
    syllable::id_of(&spelling(body)?)
}

/// 按键序列建图：从每个位置起，凡能组成一个注音音节的片段都连成一条边。
/// Build the graph: every fragment that forms one bopomofo syllable becomes an edge.
pub fn build_graph(keys: &[u8]) -> SyllableGraph {
    let n = keys.len();
    let mut g = SyllableGraph::new(n);
    for start in 0..n {
        for end in start + 1..=n.min(start + MAX_KEYS) {
            if let Some(id) = syllable_at(&keys[start..end]) {
                g.push(Edge {
                    start,
                    end,
                    syls: vec![id],
                    penalty: 0,
                    kind: EdgeKind::Full,
                    bits: Vec::new(),
                });
            }
        }
    }
    g.ensure_connected();
    g
}

/// 按键显示成注音符号；每个键一个字符。 Display: one symbol per key.
pub fn display(keys: &[u8]) -> String {
    keys.iter().map(|&k| symbol(k).unwrap_or(k as char)).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pinyin(keys: &str) -> Option<String> {
        spelling(keys.as_bytes())
    }

    #[test]
    fn standard_syllables_become_pinyin() {
        assert_eq!(pinyin("5j/").as_deref(), Some("zhong"), "ㄓㄨㄥ");
        assert_eq!(pinyin("1u").as_deref(), Some("bi"), "ㄅㄧ");
        assert_eq!(pinyin("1k").as_deref(), Some("be"), "ㄅㄜ");
        assert_eq!(pinyin("q9").as_deref(), Some("pai"), "ㄆㄞ");
        assert_eq!(pinyin("ej").as_deref(), Some("gu"), "ㄍㄨ");
        assert_eq!(pinyin("2jo").as_deref(), Some("dui"), "ㄉㄨㄟ");
        assert_eq!(pinyin("xjp").as_deref(), Some("lun"), "ㄌㄨㄣ");
        assert_eq!(pinyin("3"), None, "a tone alone is not a syllable");
    }

    #[test]
    fn medial_and_final_rules_match_pinyin_spelling() {
        assert_eq!(pinyin("rj"), None, "ㄐㄨ is not a syllable");
        assert_eq!(pinyin("rm").as_deref(), Some("ju"), "ㄐㄩ → ju");
        assert_eq!(pinyin("vm,").as_deref(), Some("xue"), "ㄒㄩㄝ → xue");
        assert_eq!(pinyin("rui").as_deref(), None, "ㄐㄨㄧ is not a syllable");
        assert_eq!(pinyin("ru.").as_deref(), Some("jiu"), "ㄐㄧㄡ → jiu");
        assert_eq!(pinyin("hj.").as_deref(), None, "ㄘㄨㄡ is not a syllable");
        assert_eq!(pinyin("ru/").as_deref(), Some("jing"), "ㄐㄧㄥ → jing");
    }

    #[test]
    fn zero_initial_syllables_use_the_y_and_w_spellings() {
        assert_eq!(pinyin("u").as_deref(), Some("yi"));
        assert_eq!(pinyin("8").as_deref(), Some("a"));
        assert_eq!(pinyin("j").as_deref(), Some("wu"));
        assert_eq!(pinyin("m").as_deref(), Some("yu"));
        assert_eq!(pinyin("j/").as_deref(), Some("weng"));
        assert_eq!(pinyin("m/").as_deref(), Some("yong"));
    }

    #[test]
    fn apical_initials_take_an_implied_i() {
        assert_eq!(pinyin("5").as_deref(), Some("zhi"), "ㄓ");
        assert_eq!(pinyin("b").as_deref(), Some("ri"), "ㄖ");
        assert_eq!(pinyin("1"), None, "ㄅ alone is no syllable");
    }

    #[test]
    fn tones_are_ignored_for_the_syllable_but_end_it() {
        assert_eq!(syllable_at(b"5j/3").map(syllable::spelling), Some("zhong"));
        assert_eq!(syllable_at(b"5j/").map(syllable::spelling), Some("zhong"));
    }

    #[test]
    fn a_sentence_builds_a_path_of_syllables() {
        let keys = b"5j/3"; // 众
        let g = build_graph(keys);
        assert!(g.out[0].iter().any(|e| e.end == 4 && e.syls.iter().any(|&s| syllable::spelling(s) == "zhong")));
    }

    #[test]
    fn display_shows_one_symbol_per_key() {
        assert_eq!(display(b"5j/3"), "ㄓㄨㄥˇ");
    }
}
