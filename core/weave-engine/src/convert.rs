//! 文本转换：简→繁（词组优先的最长匹配）与表情联想。
//! Text conversion: simplified → traditional (phrase-first longest match) and emoji suggestions.
//!
//! 数据为制表符分隔的文本：`源<TAB>目标1 目标2 …`（OpenCC 字典格式）。
//! Data are tab-separated text files: `source<TAB>target1 target2 …` (OpenCC dictionary format).

use std::collections::HashMap;

use weave_dict::blob::{Blob, Source};

fn load_map(src: &Source) -> HashMap<String, Vec<String>> {
    let Ok(blob) = Blob::open(src) else {
        return HashMap::new();
    };
    let Ok(text) = String::from_utf8(blob.to_vec()) else {
        return HashMap::new();
    };
    let mut map = HashMap::new();
    for line in text.lines() {
        let mut cols = line.splitn(2, '\t');
        let (Some(src), Some(dst)) = (cols.next(), cols.next()) else {
            continue;
        };
        if src.is_empty() {
            continue;
        }
        let targets: Vec<String> = dst
            .split(' ')
            .filter(|s| !s.is_empty())
            .map(str::to_owned)
            .collect();
        if !targets.is_empty() {
            map.insert(src.to_owned(), targets);
        }
    }
    map
}

/// 繁体的地区字形：标准繁体、台湾、香港（OpenCC 的 s2t、s2tw、s2hk）。
/// Traditional regions: standard, Taiwan and Hong Kong (OpenCC s2t, s2tw and s2hk).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum Region {
    #[default]
    Standard,
    Taiwan,
    HongKong,
}

/// 简转繁。 Simplified → traditional converter.
#[derive(Default)]
pub struct Traditional {
    phrases: HashMap<String, String>,
    chars: HashMap<char, String>,
    max_phrase_chars: usize,
    /// 台湾字形（TWVariants）与香港字形（HKVariants）；标准繁体之后逐字替换。
    taiwan: HashMap<char, String>,
    hong_kong: HashMap<char, String>,
}

impl Traditional {
    /// 从 `STPhrases.txt` 与 `STCharacters.txt` 加载。 Load from OpenCC text dictionaries.
    pub fn load(phrases: &Source, characters: &Source) -> Self {
        let phrases: HashMap<String, String> = load_map(phrases)
            .into_iter()
            .map(|(k, mut v)| (k, v.swap_remove(0)))
            .collect();
        let chars = load_map(characters)
            .into_iter()
            .filter_map(|(k, mut v)| {
                let mut it = k.chars();
                let c = it.next()?;
                it.next().is_none().then(|| (c, v.swap_remove(0)))
            })
            .collect();
        let max_phrase_chars = phrases
            .keys()
            .map(|k| k.chars().count())
            .max()
            .unwrap_or(0)
            .min(16);
        Traditional {
            phrases,
            chars,
            max_phrase_chars,
            ..Default::default()
        }
    }

    /// 加上台湾与香港字形表（OpenCC 的 TWVariants、HKVariants）。
    /// Add the Taiwan and Hong Kong form tables (OpenCC TWVariants and HKVariants).
    pub fn with_region_tables(mut self, taiwan: &Source, hong_kong: &Source) -> Self {
        self.taiwan = single_chars(taiwan);
        self.hong_kong = single_chars(hong_kong);
        self
    }

    /// 按地区转换：先做标准繁体，再替换该地区的字形。 Convert for a region: standard first, then the region's forms.
    pub fn convert_in(&self, region: Region, s: &str) -> String {
        let standard = self.convert(s);
        let forms = match region {
            Region::Standard => return standard,
            Region::Taiwan => &self.taiwan,
            Region::HongKong => &self.hong_kong,
        };
        standard
            .chars()
            .map(|c| forms.get(&c).cloned().unwrap_or_else(|| c.to_string()))
            .collect()
    }

    pub fn is_empty(&self) -> bool {
        self.chars.is_empty()
    }

    /// 转换：每个位置先尝试最长词组，否则逐字映射。
    /// Convert: at each position try the longest phrase first, else map the character.
    pub fn convert(&self, s: &str) -> String {
        if self.is_empty() {
            return s.to_owned();
        }
        let chars: Vec<char> = s.chars().collect();
        let mut out = String::with_capacity(s.len());
        let mut i = 0;
        let mut buf = String::new();
        'outer: while i < chars.len() {
            let longest = self.max_phrase_chars.min(chars.len() - i);
            for len in (2..=longest).rev() {
                buf.clear();
                buf.extend(&chars[i..i + len]);
                if let Some(t) = self.phrases.get(&buf) {
                    out.push_str(t);
                    i += len;
                    continue 'outer;
                }
            }
            match self.chars.get(&chars[i]) {
                Some(t) => out.push_str(t),
                None => out.push(chars[i]),
            }
            i += 1;
        }
        out
    }
}

/// 只取单字到单字的映射（OpenCC 一行可能有多个候选，取第一个）。
/// Single-character entries only; an OpenCC row may list several targets, the first is used.
fn single_chars(src: &Source) -> HashMap<char, String> {
    load_map(src)
        .into_iter()
        .filter_map(|(k, mut v)| {
            let mut it = k.chars();
            let c = it.next()?;
            it.next().is_none().then(|| (c, v.swap_remove(0)))
        })
        .collect()
}

/// 表情联想：词 → 表情符号。 Emoji suggestions: word → emoji.
#[derive(Default)]
pub struct Emoji {
    map: HashMap<String, Vec<String>>,
}

impl Emoji {
    /// 从 `emoji.txt` 加载（目标里与源相同的项会被去掉）。 Load; targets equal to the source are dropped.
    pub fn load(src: &Source) -> Self {
        let mut map = load_map(src);
        map.retain(|k, v| {
            v.retain(|t| t != k && !t.is_ascii() && !t.chars().any(is_han));
            !v.is_empty()
        });
        Emoji { map }
    }

    pub fn lookup(&self, word: &str) -> &[String] {
        self.map.get(word).map(|v| v.as_slice()).unwrap_or(&[])
    }
}

fn is_han(c: char) -> bool {
    matches!(c as u32, 0x3400..=0x4DBF | 0x4E00..=0x9FFF | 0x20000..=0x3134F)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn longest_phrase_wins() {
        let mut t = Traditional::default();
        t.phrases.insert("头发".into(), "頭髮".into());
        t.chars.insert('头', "頭".into());
        t.chars.insert('发', "發".into());
        t.chars.insert('财', "財".into());
        t.max_phrase_chars = 2;
        assert_eq!(t.convert("头发发财"), "頭髮發財");
    }
}
