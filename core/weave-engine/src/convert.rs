//! 文本转换：简→繁（词组优先的最长匹配）与表情联想。
//! Text conversion: simplified → traditional (phrase-first longest match) and emoji suggestions.
//!
//! 数据为制表符分隔的文本：`源<TAB>目标1 目标2 …`（OpenCC 字典格式）。
//! Data are tab-separated text files: `source<TAB>target1 target2 …` (OpenCC dictionary format).

use std::collections::HashMap;
use std::path::Path;

fn load_map(path: &Path) -> HashMap<String, Vec<String>> {
    let Ok(text) = std::fs::read_to_string(path) else {
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

/// 简转繁。 Simplified → traditional converter.
#[derive(Default)]
pub struct Traditional {
    phrases: HashMap<String, String>,
    chars: HashMap<char, String>,
    max_phrase_chars: usize,
}

impl Traditional {
    /// 从 `STPhrases.txt` 与 `STCharacters.txt` 加载。 Load from OpenCC text dictionaries.
    pub fn load(phrases: &Path, characters: &Path) -> Self {
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
        }
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

/// 表情联想：词 → 表情符号。 Emoji suggestions: word → emoji.
#[derive(Default)]
pub struct Emoji {
    map: HashMap<String, Vec<String>>,
}

impl Emoji {
    /// 从 `emoji.txt` 加载（目标里与源相同的项会被去掉）。 Load; targets equal to the source are dropped.
    pub fn load(path: &Path) -> Self {
        let mut map = load_map(path);
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
