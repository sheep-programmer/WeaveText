use serde_json::json;
use weave_dict::{lexicon::{Builder, Kind, Lexicon}, syllable};
use weave_engine::Engine;

fn engine() -> Engine {
    let mut b = Builder::new(Kind::Pinyin);
    for (code, text, cost) in [("ni", "你", 500u16), ("ni", "泥", 800)] {
        b.insert(&syllable::parse_seq(code).unwrap(), text, cost);
    }
    let mut e = Engine::with_lexicons(Some(Lexicon::from_bytes(b.build()).unwrap()), None, None);
    e.options.prediction = false;
    e.options.emoji = false;
    e
}

fn type_keys(e: &mut Engine, text: &str) {
    e.clear();
    for c in text.chars() { assert!(e.input_char(c)); }
}

fn first(e: &mut Engine) -> String {
    e.snapshot().candidates[0].text.clone()
}

#[test]
fn clearing_candidate_preferences_puts_pinned_and_demoted_words_back_in_their_places() {
    let mut e = engine();
    type_keys(&mut e, "ni");
    assert_eq!(first(&mut e), "你");
    let index = e.snapshot().candidates.iter().position(|c| c.text == "泥").unwrap();
    assert_eq!(e.features(&json!({"op":"policy","index":index,"mode":"pin"}))["ok"], true);
    type_keys(&mut e, "ni");
    assert_eq!(first(&mut e), "泥", "the pin takes effect");
    let cleared = e.features(&json!({"op":"clearCandidatePreferences"}));
    assert_eq!(cleared["ok"], true, "{cleared}");
    assert!(cleared["cleared"].as_u64().unwrap() >= 1, "{cleared}");
    type_keys(&mut e, "ni");
    assert_eq!(first(&mut e), "你", "after clearing, the ordinary order returns");
}
