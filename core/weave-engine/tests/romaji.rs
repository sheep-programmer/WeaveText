use weave_engine::session::{Engine, Schema};

fn engine() -> Engine {
    let mut e = Engine::with_lexicons(None, None, None);
    e.options.prediction = false;
    e.options.emoji = false;
    e.set_schema(Schema::Romaji);
    e
}

fn type_keys(e: &mut Engine, keys: &str) {
    e.clear();
    for c in keys.chars() {
        assert!(e.input_char(c), "rejected {c} in {keys}");
    }
}

#[test]
fn typed_romaji_offers_hiragana_first_then_katakana_and_the_romaji() {
    let mut e = engine();
    type_keys(&mut e, "nihongo");
    let texts: Vec<String> = e.snapshot().candidates.iter().map(|c| c.text.clone()).collect();
    assert_eq!(texts[0], "にほんご", "{texts:?}");
    assert_eq!(texts[1], "ニホンゴ", "{texts:?}");
    assert!(texts.contains(&"nihongo".to_string()), "{texts:?}");
}

#[test]
fn selecting_the_first_candidate_commits_the_kana() {
    let mut e = engine();
    type_keys(&mut e, "arigatou");
    assert!(e.select(0));
    assert_eq!(e.snapshot().commit, "ありがとう");
}

#[test]
fn an_incomplete_tail_is_kept_as_romaji() {
    let mut e = engine();
    type_keys(&mut e, "nihonk");
    assert_eq!(e.snapshot().candidates[0].text, "にほんk");
}

#[test]
fn digits_and_punctuation_are_not_taken() {
    let mut e = engine();
    e.clear();
    assert!(!e.input_char('1'));
}
