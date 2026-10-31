use weave_dict::{lexicon::{Builder, Kind, Lexicon}, syllable};
use weave_engine::Engine;

fn engine() -> Engine {
    let mut b = Builder::new(Kind::Pinyin);
    for (code, text) in [("ni", "你"), ("hao", "好")] {
        b.insert(&syllable::parse_seq(code).unwrap(), text, 10000);
    }
    let mut e = Engine::with_lexicons(Some(Lexicon::from_bytes(b.build()).unwrap()), None, None);
    e.options.prediction = false;
    e.options.emoji = false;
    e
}

fn candidates_for(e: &mut Engine, typed: &str) -> Vec<String> {
    e.clear();
    for c in typed.chars() { assert!(e.input_char(c)); }
    e.snapshot().candidates.into_iter().map(|c| c.text).collect()
}

#[test]
fn word_only_lines_take_their_readings_from_the_built_in_readings() {
    let mut e = engine();
    let r = e.import_user_words_report("织文\n");
    assert_eq!((r.added, r.filled, r.skipped), (1, 1, 0));
    assert!(candidates_for(&mut e, "zhiwen").contains(&"织文".to_string()));
}

#[test]
fn rime_dict_yaml_header_and_tone_numbers_are_understood() {
    let mut e = engine();
    let text = "---\nname: 测试词库\nversion: \"1\"\nsort: by_weight\n...\n# 注释\n织文\tzhi1 wen2\t100\n";
    let r = e.import_user_words_report(text);
    assert_eq!((r.added, r.filled, r.skipped), (1, 0, 0), "{r:?}");
    assert!(candidates_for(&mut e, "zhiwen").contains(&"织文".to_string()));
}

#[test]
fn rows_that_cannot_be_read_are_counted_with_a_reason() {
    let mut e = engine();
    let text = "# 导出\n织文\tzhi wen\t5\n五笔词\tgant\t1\n\t\t\n";
    let r = e.import_user_words_report(text);
    assert_eq!((r.added, r.skipped), (1, 1), "{r:?}");
    assert!(r.rejected.iter().any(|s| s.contains("五笔词") && s.contains("读音")), "{:?}", r.rejected);
}

#[test]
fn the_old_entry_point_still_reports_only_the_added_rows() {
    let mut e = engine();
    assert_eq!(e.import_user_words("织文\tzhi wen\t2\n"), 1);
}
