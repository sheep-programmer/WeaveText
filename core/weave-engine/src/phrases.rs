//! 快捷短语的编码：每个输入码对应一个个人数据键 `snippet:<输入码>`，值里可以有多条「权重 + 文字」，
//! 权重 1–999 决定同一码下的先后。旧版只存一段文字的值仍然可读，当作权重 10 的一条。
//!
//! Quick phrases: each code has one personal key, `snippet:<code>`, whose value holds several weighted texts.
//! A value written by the older single-text format still reads as one entry with the default weight.

use std::cmp::Reverse;

pub const DEFAULT_WEIGHT: u16 = 10;
pub const MAX_WEIGHT: u16 = 999;
pub const MAX_CODE_LEN: usize = 32;
/// 单条短语的字符上限，避免误粘贴一大段文字进候选。 Upper bound per phrase, so a stray paste stays out of the candidates.
pub const MAX_TEXT_CHARS: usize = 2_000;
pub const KEY_PREFIX: &str = "snippet:";

/// 一条短语（输入码由所在的键决定）。 One phrase; its code comes from the key it is stored under.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Entry {
    pub weight: u16,
    pub text: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PhraseError {
    /// 输入码须为 1–32 个小写英文字母。 Codes are 1–32 lowercase ASCII letters.
    BadCode,
    /// 权重须为 1–999。 Weights are 1–999.
    BadWeight,
    /// 文字为空或超过长度上限。 Text is empty or too long.
    BadText,
    /// 同一输入码下已有相同文字。 The same text already exists under this code.
    Duplicate,
    /// 没有这条短语。 No such phrase.
    Missing,
}

impl PhraseError {
    /// 给设置页显示的原因。 The reason shown in settings.
    pub fn message(self) -> &'static str {
        match self {
            PhraseError::BadCode => "输入码只能是 1–32 个小写英文字母",
            PhraseError::BadWeight => "权重须在 1–999 之间",
            PhraseError::BadText => "文字不能为空，也不能超过 2000 字",
            PhraseError::Duplicate => "该输入码下已有相同的文字",
            PhraseError::Missing => "没有这条短语",
        }
    }
}

/// 导入结果：新增条数与跳过的行数（格式错误、重复）。 Import outcome: rows added and rows skipped.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct ImportReport {
    pub added: usize,
    pub skipped: usize,
}

pub fn valid_code(code: &str) -> bool {
    (1..=MAX_CODE_LEN).contains(&code.len()) && code.bytes().all(|b| b.is_ascii_lowercase())
}

pub fn key(code: &str) -> String {
    format!("{KEY_PREFIX}{code}")
}

/// 读取一个输入码的值。每行「权重<Tab>文字」（文字转义）视为新格式；否则整段当作一条旧版短语。
/// Read one code's value. Rows of "weight<TAB>text" (escaped) are the new format; anything else is one legacy phrase.
pub fn decode(value: &str) -> Vec<Entry> {
    if value.is_empty() {
        return Vec::new();
    }
    let rows: Vec<&str> = value.lines().filter(|l| !l.is_empty()).collect();
    let weighted = rows.iter().all(|row| {
        row.split_once('\t').is_some_and(|(w, _)| w.parse::<u16>().is_ok_and(|w| (1..=MAX_WEIGHT).contains(&w)))
    });
    if !weighted {
        return vec![Entry { weight: DEFAULT_WEIGHT, text: value.to_string() }];
    }
    rows.into_iter()
        .filter_map(|row| {
            let (w, text) = row.split_once('\t')?;
            Some(Entry { weight: w.parse().ok()?, text: unescape(text) })
        })
        .collect()
}

/// 写回一个输入码的值；空列表返回空串（表示删除这个码）。 Encode a code's entries; empty means delete.
pub fn encode(entries: &[Entry]) -> String {
    entries.iter().map(|e| format!("{}\t{}\n", e.weight, escape(&e.text))).collect()
}

/// 按权重从高到低排；权重相同时保持原有先后。 Highest weight first; equal weights keep their order.
pub fn ordered(mut entries: Vec<Entry>) -> Vec<Entry> {
    entries.sort_by_key(|e| Reverse(e.weight));
    entries
}

/// 加入一条；权重缺省为 10。 Add one entry; the weight defaults to 10.
pub fn add(entries: &mut Vec<Entry>, text: &str, weight: Option<u16>) -> Result<(), PhraseError> {
    let weight = weight.unwrap_or(DEFAULT_WEIGHT);
    if !(1..=MAX_WEIGHT).contains(&weight) {
        return Err(PhraseError::BadWeight);
    }
    if text.is_empty() || text.chars().count() > MAX_TEXT_CHARS {
        return Err(PhraseError::BadText);
    }
    if entries.iter().any(|e| e.text == text) {
        return Err(PhraseError::Duplicate);
    }
    entries.push(Entry { weight, text: text.to_string() });
    Ok(())
}

/// 修改权重。 Change one entry's weight.
pub fn set_weight(entries: &mut [Entry], text: &str, weight: u16) -> Result<(), PhraseError> {
    if !(1..=MAX_WEIGHT).contains(&weight) {
        return Err(PhraseError::BadWeight);
    }
    let entry = entries.iter_mut().find(|e| e.text == text).ok_or(PhraseError::Missing)?;
    entry.weight = weight;
    Ok(())
}

/// 删除一条。 Remove one entry.
pub fn remove(entries: &mut Vec<Entry>, text: &str) -> Result<(), PhraseError> {
    let before = entries.len();
    entries.retain(|e| e.text != text);
    if entries.len() == before { Err(PhraseError::Missing) } else { Ok(()) }
}

/// 导入文本的一行：输入码、权重（可省略）与文字。 One imported row: code, optional weight and text.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Row {
    pub code: String,
    pub weight: Option<u16>,
    pub text: String,
}

/// 解析导入文本：「输入码<Tab>权重<Tab>文字」或「输入码<Tab>文字」；`#` 开头为注释。
/// Parse import text: "code<TAB>weight<TAB>text" or "code<TAB>text"; lines starting with `#` are comments.
pub fn parse_import(text: &str) -> (Vec<Row>, usize) {
    let mut rows = Vec::new();
    let mut skipped = 0;
    for line in text.lines() {
        if line.trim().is_empty() || line.starts_with('#') {
            continue;
        }
        let fields: Vec<&str> = line.split('\t').collect();
        let row = match fields.as_slice() {
            [code, weight, value] => weight.trim().parse::<u16>().ok().map(|w| Row {
                code: code.to_string(),
                weight: Some(w),
                text: unescape(value),
            }),
            [code, value] => Some(Row { code: code.to_string(), weight: None, text: unescape(value) }),
            _ => None,
        };
        match row {
            Some(row) if valid_code(&row.code) => rows.push(row),
            _ => skipped += 1,
        }
    }
    (rows, skipped)
}

/// 导出为导入格式：每行「输入码<Tab>权重<Tab>文字」。 Export in the import format.
pub fn export(rows: &[(String, Entry)]) -> String {
    rows.iter().map(|(code, e)| format!("{code}\t{}\t{}\n", e.weight, escape(&e.text))).collect()
}

fn escape(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for c in text.chars() {
        match c {
            '\\' => out.push_str("\\\\"),
            '\t' => out.push_str("\\t"),
            '\n' => out.push_str("\\n"),
            c => out.push(c),
        }
    }
    out
}

fn unescape(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut chars = text.chars();
    while let Some(c) = chars.next() {
        if c != '\\' {
            out.push(c);
            continue;
        }
        match chars.next() {
            Some('t') => out.push('\t'),
            Some('n') => out.push('\n'),
            Some('\\') => out.push('\\'),
            Some(other) => {
                out.push('\\');
                out.push(other);
            }
            None => out.push('\\'),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn entry(weight: u16, text: &str) -> Entry {
        Entry { weight, text: text.to_string() }
    }

    #[test]
    fn codes_are_one_to_thirty_two_lowercase_letters() {
        assert!(valid_code("dz"));
        assert!(valid_code(&"a".repeat(32)));
        assert!(!valid_code(""));
        assert!(!valid_code(&"a".repeat(33)));
        assert!(!valid_code("Dz"));
        assert!(!valid_code("d2"));
    }

    #[test]
    fn a_legacy_single_text_value_reads_as_one_default_weight_phrase() {
        assert_eq!(decode("地址；日期 {date}"), vec![entry(DEFAULT_WEIGHT, "地址；日期 {date}")]);
        assert_eq!(decode("第一行\n第二行"), vec![entry(DEFAULT_WEIGHT, "第一行\n第二行")]);
        assert!(decode("").is_empty());
    }

    #[test]
    fn weighted_rows_round_trip_including_newlines_and_tabs() {
        let entries = vec![entry(900, "北京市\t朝阳区"), entry(10, "日报：\n今天\\完成")];
        let value = encode(&entries);
        assert_eq!(value.lines().count(), 2, "escaped text stays on its row");
        assert_eq!(decode(&value), entries);
    }

    #[test]
    fn add_validates_weight_text_and_duplicates() {
        let mut list = Vec::new();
        assert_eq!(add(&mut list, "x", Some(0)), Err(PhraseError::BadWeight));
        assert_eq!(add(&mut list, "x", Some(1000)), Err(PhraseError::BadWeight));
        assert_eq!(add(&mut list, "", None), Err(PhraseError::BadText));
        assert_eq!(add(&mut list, &"字".repeat(MAX_TEXT_CHARS + 1), None), Err(PhraseError::BadText));
        assert_eq!(add(&mut list, "北京市", None), Ok(()));
        assert_eq!(add(&mut list, "北京市", Some(50)), Err(PhraseError::Duplicate));
        assert_eq!(list, vec![entry(DEFAULT_WEIGHT, "北京市")]);
    }

    #[test]
    fn ordering_is_by_weight_then_insertion() {
        let mut list = Vec::new();
        add(&mut list, "低", Some(5)).unwrap();
        add(&mut list, "高", Some(900)).unwrap();
        add(&mut list, "中一", Some(50)).unwrap();
        add(&mut list, "中二", Some(50)).unwrap();
        let texts: Vec<String> = ordered(list).into_iter().map(|e| e.text).collect();
        assert_eq!(texts, ["高", "中一", "中二", "低"]);
    }

    #[test]
    fn set_weight_and_remove_report_missing_entries() {
        let mut list = vec![entry(10, "地址")];
        assert_eq!(set_weight(&mut list, "地址", 999), Ok(()));
        assert_eq!(set_weight(&mut list, "不存在", 1), Err(PhraseError::Missing));
        assert_eq!(set_weight(&mut list, "地址", 0), Err(PhraseError::BadWeight));
        assert_eq!(remove(&mut list, "地址"), Ok(()));
        assert_eq!(remove(&mut list, "地址"), Err(PhraseError::Missing));
    }

    #[test]
    fn import_takes_weighted_and_plain_rows_and_counts_bad_codes() {
        let (rows, skipped) = parse_import("# 注释\ndz\t30\t北京市\nrb\t日报\n坏码\t文字\nBad\t1\tx\nzs\tnotanumber\tx\n\nsj\t10\t谢谢！\n");
        assert_eq!(skipped, 3);
        assert_eq!(rows, vec![
            Row { code: "dz".into(), weight: Some(30), text: "北京市".into() },
            Row { code: "rb".into(), weight: None, text: "日报".into() },
            Row { code: "sj".into(), weight: Some(10), text: "谢谢！".into() },
        ]);
    }

    #[test]
    fn export_is_importable_again() {
        let exported = export(&[("zs".into(), entry(100, "谢谢！\n再见"))]);
        let (rows, skipped) = parse_import(&exported);
        assert_eq!(skipped, 0);
        assert_eq!(rows, vec![Row { code: "zs".into(), weight: Some(100), text: "谢谢！\n再见".into() }]);
    }
}
