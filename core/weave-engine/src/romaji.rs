//! 罗马字 → 平假名（Hepburn 式拼写，常见别写也收）。汉字转换需要词典，这里只做假名。
//! Romaji → hiragana (Hepburn spelling plus the common variants). Kanji conversion needs a dictionary; this is kana only.

/// 罗马字 → 假名，最长匹配优先。 Romaji → kana, longest match first.
const TABLE: &[(&str, &str)] = &[
    ("a", "あ"), ("i", "い"), ("u", "う"), ("e", "え"), ("o", "お"),
    ("ka", "か"), ("ki", "き"), ("ku", "く"), ("ke", "け"), ("ko", "こ"),
    ("sa", "さ"), ("si", "し"), ("shi", "し"), ("su", "す"), ("se", "せ"), ("so", "そ"),
    ("ta", "た"), ("ti", "ち"), ("chi", "ち"), ("tu", "つ"), ("tsu", "つ"), ("te", "て"), ("to", "と"),
    ("na", "な"), ("ni", "に"), ("nu", "ぬ"), ("ne", "ね"), ("no", "の"),
    ("ha", "は"), ("hi", "ひ"), ("hu", "ふ"), ("fu", "ふ"), ("he", "へ"), ("ho", "ほ"),
    ("ma", "ま"), ("mi", "み"), ("mu", "む"), ("me", "め"), ("mo", "も"),
    ("ya", "や"), ("yu", "ゆ"), ("yo", "よ"),
    ("ra", "ら"), ("ri", "り"), ("ru", "る"), ("re", "れ"), ("ro", "ろ"),
    ("wa", "わ"), ("wo", "を"), ("wi", "うぃ"), ("we", "うぇ"),
    ("ga", "が"), ("gi", "ぎ"), ("gu", "ぐ"), ("ge", "げ"), ("go", "ご"),
    ("za", "ざ"), ("zi", "じ"), ("ji", "じ"), ("zu", "ず"), ("ze", "ぜ"), ("zo", "ぞ"),
    ("da", "だ"), ("di", "ぢ"), ("du", "づ"), ("de", "で"), ("do", "ど"),
    ("ba", "ば"), ("bi", "び"), ("bu", "ぶ"), ("be", "べ"), ("bo", "ぼ"),
    ("pa", "ぱ"), ("pi", "ぴ"), ("pu", "ぷ"), ("pe", "ぺ"), ("po", "ぽ"),
    ("kya", "きゃ"), ("kyu", "きゅ"), ("kyo", "きょ"), ("gya", "ぎゃ"), ("gyu", "ぎゅ"), ("gyo", "ぎょ"),
    ("sha", "しゃ"), ("shu", "しゅ"), ("sho", "しょ"), ("sya", "しゃ"), ("syu", "しゅ"), ("syo", "しょ"),
    ("ja", "じゃ"), ("ju", "じゅ"), ("jo", "じょ"), ("jya", "じゃ"), ("jyu", "じゅ"), ("jyo", "じょ"),
    ("cha", "ちゃ"), ("chu", "ちゅ"), ("cho", "ちょ"), ("tya", "ちゃ"), ("tyu", "ちゅ"), ("tyo", "ちょ"),
    ("nya", "にゃ"), ("nyu", "にゅ"), ("nyo", "にょ"),
    ("hya", "ひゃ"), ("hyu", "ひゅ"), ("hyo", "ひょ"),
    ("mya", "みゃ"), ("myu", "みゅ"), ("myo", "みょ"),
    ("rya", "りゃ"), ("ryu", "りゅ"), ("ryo", "りょ"),
    ("bya", "びゃ"), ("byu", "びゅ"), ("byo", "びょ"),
    ("pya", "ぴゃ"), ("pyu", "ぴゅ"), ("pyo", "ぴょ"),
    ("n", "ん"),
];

/// 一段假名的结果：已确定的假名，以及仍未拼完的罗马字（如末尾的 k、ky）。
/// Result: the settled kana and the romaji still incomplete at the end (such as a trailing k or ky).
#[derive(Debug, PartialEq, Eq)]
pub struct Conversion {
    pub kana: String,
    pub pending: String,
}

/// 把罗马字转成假名。 Convert romaji to hiragana.
pub fn convert(input: &str) -> Conversion {
    let s = input.to_ascii_lowercase();
    let b = s.as_bytes();
    let mut kana = String::new();
    let mut i = 0;
    while i < b.len() {
        let rest = &s[i..];
        // 长音 "-" 与 ー。 Long vowel mark.
        if rest.starts_with('-') {
            kana.push('ー');
            i += 1;
            continue;
        }
        // 促音：同一辅音连写（但 n 不算，nn 是 ん）。 Sokuon: a doubled consonant (n is not doubled this way).
        if b.len() > i + 1 && b[i] == b[i + 1] && b[i].is_ascii_alphabetic() && !b"aiueon".contains(&b[i]) {
            kana.push('っ');
            i += 1;
            continue;
        }
        // "nn" 与 n + 辅音（不含 y）：ん；n 后面是元音或 y 时 n 与后面合成音节。 "nn" and n + consonant (not y) give ん.
        if b[i] == b'n' && b.len() > i + 1 && b[i + 1] == b'n' {
            // nn 后面是元音或 y 时，只有第一个 n 是 ん；其余情况两个 n 合成一个 ん。
            // In "nn" followed by a vowel or y only the first n is ん (konnichiha: こんにちは); otherwise both make one ん.
            let vowel_next = b.get(i + 2).is_some_and(|c| b"aiueoy".contains(c));
            kana.push('ん');
            i += if vowel_next { 1 } else { 2 };
            continue;
        }
        if b[i] == b'n' && b.len() > i + 1 && !b"aiueoy".contains(&b[i + 1]) {
            kana.push('ん');
            i += 1;
            continue;
        }
        let hit = TABLE.iter().filter(|(r, _)| rest.starts_with(r)).max_by_key(|(r, _)| r.len());
        match hit {
            Some((r, k)) => {
                kana.push_str(k);
                i += r.len();
            }
            None => break,
        }
    }
    // 末尾的 n 只写完一半时，输入结束就是 ん。 A trailing lone n becomes ん when the input ends.
    let pending = s[i..].to_string();
    if pending == "n" {
        kana.push('ん');
        return Conversion { kana, pending: String::new() };
    }
    Conversion { kana, pending }
}

/// 平假名 → 片假名。 Hiragana → katakana.
pub fn katakana(hiragana: &str) -> String {
    hiragana
        .chars()
        .map(|c| match c {
            'ぁ'..='ゖ' => char::from_u32(c as u32 + 0x60).unwrap_or(c),
            other => other,
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn kana(s: &str) -> String {
        convert(s).kana
    }

    #[test]
    fn plain_syllables() {
        assert_eq!(kana("nihongo"), "にほんご");
        assert_eq!(kana("arigatou"), "ありがとう");
        assert_eq!(kana("sakura"), "さくら");
    }

    #[test]
    fn youon_and_long_vowels() {
        assert_eq!(kana("kyou"), "きょう");
        assert_eq!(kana("sho"), "しょ");
        assert_eq!(kana("ka-do"), "かーど");
    }

    #[test]
    fn n_and_sokuon() {
        assert_eq!(kana("shinbun"), "しんぶん");
        assert_eq!(kana("konnichiha"), "こんにちは");
        assert_eq!(kana("kitte"), "きって");
        assert_eq!(kana("zasshi"), "ざっし");
    }

    #[test]
    fn an_incomplete_tail_stays_pending() {
        assert_eq!(convert("nihonk"), Conversion { kana: "にほん".into(), pending: "k".into() });
        assert_eq!(convert("ky"), Conversion { kana: String::new(), pending: "ky".into() });
        assert_eq!(kana("n"), "ん");
    }

    #[test]
    fn katakana_shifts_the_kana_block() {
        assert_eq!(katakana("にほんご"), "ニホンゴ");
    }
}
