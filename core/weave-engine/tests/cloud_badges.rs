use weave_dict::{
    lexicon::{Builder, Kind, Lexicon},
    syllable,
};
use weave_engine::session::Engine;

fn lex(rows: &[(&str, &str)]) -> Lexicon {
    let mut b = Builder::new(Kind::Pinyin);
    for (py, word) in rows {
        b.insert(&syllable::parse_seq(py).unwrap(), word, 1000);
    }
    Lexicon::from_bytes(b.build()).unwrap()
}
fn type_word(e: &mut Engine, keys: &str) {
    e.clear();
    for c in keys.chars() {
        assert!(e.input_char(c));
    }
}
#[test]
fn cloud_badges_follow_added_words_and_sentence_parts_without_marking_local_duplicates() {
    let mut e = Engine::with_lexicons(
        Some(lex(&[("ci ku", "词库"), ("lai le", "来了")])),
        None,
        None,
    );
    let cloud = || lex(&[("ci ku", "词库"), ("yun duan re ci", "云端热词")]);
    assert!(e.attach_pack("cloud", cloud()));
    type_word(&mut e, "ciku");
    assert!(!e.snapshot().candidates[0].cloud);
    type_word(&mut e, "yunduanreci");
    let c = e.snapshot().candidates[0].clone();
    assert_eq!(c.text, "云端热词");
    assert!(c.cloud);
    assert!(!c.user);
    e.select(0);
    e.snapshot();
    type_word(&mut e, "yunduanreci");
    assert!(e
        .snapshot()
        .candidates
        .iter()
        .any(|c| c.text == "云端热词" && c.cloud && c.user));
    type_word(&mut e, "yunduanrecilaile");
    let c = e.snapshot().candidates[0].clone();
    assert_eq!(c.text, "云端热词来了");
    assert!(c.cloud);
    e.unload_pack("cloud");
    type_word(&mut e, "yunduanreci");
    let c = e.snapshot().candidates[0].clone();
    assert_eq!(c.text, "云端热词");
    assert!(!c.cloud);
    assert!(c.user);
    e.attach_pack("cloud", cloud());
    e.attach_pack("local", cloud());
    type_word(&mut e, "yunduanreci");
    assert!(!e.snapshot().candidates[0].cloud);
}
