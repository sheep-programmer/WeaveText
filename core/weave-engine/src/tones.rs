//! 候选的拼音提示（可带声调）：读音由词库里这个候选实际用的无声调音节挑选，所以「银行」是 yín háng、「行走」是 xíng zǒu。
//! 单字读音来自 pinyin-data（MIT，经 pypinyin 打包）；音节相同、声调不同的多音词（为什么、爱好、中奖……）查一张小的覆盖表。
//! Pinyin hints for candidates (optionally with tones). The reading is chosen by the toneless syllable the lexicon
//! actually used for the candidate, so 银行 is yín háng and 行走 is xíng zǒu. Per-char readings come from pinyin-data
//! (MIT, shipped with pypinyin); polyphonic words whose syllables differ only in tone (为什么, 爱好, 中奖, …) are looked
//! up in a small override table. See tools/tones/build.py for how the data file is made.

use std::collections::HashMap;
use std::sync::OnceLock;

use weave_dict::syllable::{self, SyllableId};

const DATA: &str = include_str!("../data/tones.tsv");

struct Table {
    chars: HashMap<char, Vec<&'static str>>,
    words: HashMap<&'static str, Vec<&'static str>>,
    longest: usize,
}

fn table() -> &'static Table {
    static TABLE: OnceLock<Table> = OnceLock::new();
    TABLE.get_or_init(|| {
        let mut chars = HashMap::with_capacity(21_000);
        let mut words = HashMap::new();
        let mut longest = 0;
        for line in DATA.lines() {
            if line.starts_with('#') {
                continue;
            }
            let Some((head, rest)) = line.split_once('\t') else { continue };
            if let Some(word) = head.strip_prefix('@') {
                longest = longest.max(word.chars().count());
                words.insert(word, rest.split(' ').collect());
            } else if let Some(c) = head.chars().next() {
                chars.insert(c, rest.split(',').collect());
            }
        }
        Table { chars, words, longest }
    })
}

/// 去掉声调、与词库的写法一致（ü 写作 v）。 Strip the tone mark; ü is written v, like the lexicon.
pub fn plain(toned: &str) -> String {
    toned
        .chars()
        .map(|c| match c {
            'ā' | 'á' | 'ǎ' | 'à' => 'a',
            'ē' | 'é' | 'ě' | 'è' | 'ê' => 'e',
            'ī' | 'í' | 'ǐ' | 'ì' => 'i',
            'ō' | 'ó' | 'ǒ' | 'ò' => 'o',
            'ū' | 'ú' | 'ǔ' | 'ù' => 'u',
            'ǖ' | 'ǘ' | 'ǚ' | 'ǜ' | 'ü' => 'v',
            'ń' | 'ň' | 'ǹ' => 'n',
            'ḿ' => 'm',
            c => c,
        })
        .collect()
}

/// `text` 的拼音（音节以空格分隔）。`key` 是词库给这个候选用的音节（长度与字数一致时据此挑读音）。
/// 含非汉字、或有生僻到没有读音的字时为 `None`。
/// Pinyin of `text`, syllables separated by spaces. `key` is the lexicon's syllables for this candidate (used to choose
/// readings when its length matches the char count). `None` if it has non-Han chars or a char without a reading.
pub fn pinyin(text: &str, key: Option<&[SyllableId]>, tones: bool) -> Option<String> {
    let t = table();
    let chars: Vec<char> = text.chars().collect();
    if chars.is_empty() || chars.len() > 24 {
        return None;
    }
    let key = key.filter(|k| k.len() == chars.len());
    let spelled: Option<Vec<&str>> = key.map(|k| k.iter().map(|&id| syllable::spelling(id)).collect());
    let mut out: Vec<Option<String>> = vec![None; chars.len()];
    // 多音词覆盖：最长匹配，且读音必须和词库用的音节一致。 Word overrides: longest match, agreeing with the lexicon's syllables.
    let mut i = 0;
    while i < chars.len() {
        let mut hit = false;
        for len in (2..=t.longest.min(chars.len() - i)).rev() {
            let word: String = chars[i..i + len].iter().collect();
            let Some(reading) = t.words.get(word.as_str()) else { continue };
            let agrees = spelled.as_ref().is_none_or(|s| reading.iter().enumerate().all(|(j, r)| plain(r) == s[i + j]));
            if agrees {
                for (j, r) in reading.iter().enumerate() {
                    out[i + j] = Some((*r).to_string());
                }
                i += len;
                hit = true;
                break;
            }
        }
        if !hit {
            i += 1;
        }
    }
    let mut parts: Vec<String> = Vec::with_capacity(chars.len());
    for (i, c) in chars.iter().enumerate() {
        let toned = match out[i].take() {
            Some(r) => r,
            None => {
                let readings = t.chars.get(c)?;
                match spelled.as_ref().map(|s| s[i]) {
                    Some(want) if !want.is_empty() => readings
                        .iter()
                        .find(|r| plain(r) == want)
                        .map_or_else(|| want.to_string(), |r| (*r).to_string()),
                    _ => readings[0].to_string(),
                }
            }
        };
        parts.push(if tones { toned } else { plain(&toned) });
    }
    Some(parts.join(" "))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ids(s: &str) -> Vec<SyllableId> {
        syllable::parse_seq(s).unwrap()
    }

    #[test]
    fn tones_follow_the_syllables_the_lexicon_used() {
        assert_eq!(pinyin("你好", None, true).as_deref(), Some("nǐ hǎo"));
        assert_eq!(pinyin("你好", None, false).as_deref(), Some("ni hao"));
        // 同一个字，词库用哪个音节就读哪个。 The same char reads as the syllable the lexicon used.
        assert_eq!(pinyin("银行", Some(&ids("yin hang")), true).as_deref(), Some("yín háng"));
        assert_eq!(pinyin("行走", Some(&ids("xing zou")), true).as_deref(), Some("xíng zǒu"));
        assert_eq!(pinyin("长大", Some(&ids("zhang da")), true).as_deref(), Some("zhǎng dà"));
        assert_eq!(pinyin("长度", Some(&ids("chang du")), true).as_deref(), Some("cháng dù"));
        // ü 与 ê 的写法和词库一致。 ü is written v.
        assert_eq!(pinyin("女", Some(&ids("nv")), false).as_deref(), Some("nv"));
        assert_eq!(pinyin("女", None, true).as_deref(), Some("nǚ"));
    }

    #[test]
    fn same_syllable_polyphones_use_the_override_table() {
        assert_eq!(pinyin("为什么", Some(&ids("wei shen me")), true).as_deref(), Some("wèi shén me"));
        assert_eq!(pinyin("爱好", Some(&ids("ai hao")), true).as_deref(), Some("ài hào"));
        assert_eq!(pinyin("中奖", Some(&ids("zhong jiang")), true).as_deref(), Some("zhòng jiǎng"));
        assert_eq!(pinyin("中国", Some(&ids("zhong guo")), true).as_deref(), Some("zhōng guó"));
        // 覆盖表和词库用的音节对不上时不采用。 An override that disagrees with the lexicon's syllables is ignored.
        assert_eq!(pinyin("爱好", Some(&ids("ai hou")), true).as_deref(), Some("ài hou"));
        // 句子里夹着词：最长匹配。 A word inside a longer run.
        assert_eq!(pinyin("我爱好看书", None, true).as_deref(), Some("wǒ ài hào kàn shū"));
    }

    #[test]
    fn non_han_text_and_bad_keys_are_handled() {
        assert_eq!(pinyin("ab", None, true), None);
        assert_eq!(pinyin("😀", None, true), None);
        assert_eq!(pinyin("", None, true), None);
        // key 长度和字数对不上就不用它。 A key of the wrong length is ignored.
        assert_eq!(pinyin("你好", Some(&ids("ni")), true).as_deref(), Some("nǐ hǎo"));
    }
}
