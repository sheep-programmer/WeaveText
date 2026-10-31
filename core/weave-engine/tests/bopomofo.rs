use weave_dict::{lexicon::{Builder, Kind, Lexicon}, syllable};
use weave_engine::session::{Engine, Schema};

fn engine() -> Engine {
    let mut b = Builder::new(Kind::Pinyin);
    for (code, text) in [("zhong", "中"), ("guo", "国"), ("ni", "你"), ("hao", "好")] {
        b.insert(&syllable::parse_seq(code).unwrap(), text, 10000);
    }
    let mut e = Engine::with_lexicons(Some(Lexicon::from_bytes(b.build()).unwrap()), None, None);
    e.options.prediction = false;
    e.options.emoji = false;
    e.set_schema(Schema::Bopomofo);
    e
}

fn type_keys(e: &mut Engine, keys: &str) {
    e.clear();
    for c in keys.chars() {
        assert!(e.input_char(c), "rejected {c} in {keys}");
    }
}

#[test]
fn a_bopomofo_syllable_offers_its_characters() {
    let mut e = engine();
    type_keys(&mut e, "5j/"); // ㄓㄨㄥ
    assert_eq!(e.snapshot().candidates[0].text, "中");
}

#[test]
fn the_preedit_shows_bopomofo_symbols_one_per_key() {
    let mut e = engine();
    type_keys(&mut e, "5j/3");
    let s = e.snapshot();
    assert_eq!(s.preedit, "ㄓㄨㄥˇ");
    assert_eq!(s.candidates[0].text, "中");
}

#[test]
fn a_syllable_with_medial_and_final_finds_its_character() {
    let mut e = engine();
    type_keys(&mut e, "eji"); // ㄍㄨㄛ → guo
    assert!(e.snapshot().candidates.iter().any(|c| c.text == "国"), "{:?}", e.snapshot().candidates);
}

#[test]
fn a_non_bopomofo_key_is_not_taken() {
    let mut e = engine();
    e.clear();
    assert!(!e.input_char('!'));
}
