use weave_dict::{lexicon::{Builder, Kind, Lexicon}, syllable};
use weave_engine::Engine;

fn engine() -> Engine {
    let mut b = Builder::new(Kind::Pinyin);
    for (code, text, cost) in [("zhi wen", "织文", 3000u16), ("zhi", "只", 800), ("wen", "文", 900)] {
        b.insert(&syllable::parse_seq(code).unwrap(), text, cost);
    }
    let mut e = Engine::with_lexicons(Some(Lexicon::from_bytes(b.build()).unwrap()), None, None);
    e.options.prediction = false;
    e.options.emoji = false;
    e
}

fn type_keys(e: &mut Engine, typed: &str) {
    e.clear();
    for c in typed.chars() { assert!(e.input_char(c), "rejected {c}"); }
}

fn position(e: &mut Engine, text: &str) -> Option<usize> {
    e.snapshot().candidates.iter().position(|c| c.text == text)
}

#[test]
fn an_accepted_correction_moves_the_intended_word_forward_next_time() {
    let mut e = engine();
    type_keys(&mut e, "zhiwne");
    let before = position(&mut e, "织文").expect("the swapped spelling reaches 织文 through a correction");
    assert!(e.select(before));
    assert_eq!(e.snapshot().commit, "织文");
    type_keys(&mut e, "zhiwne");
    assert_eq!(position(&mut e, "织文"), Some(0), "before learning it was at {before}");
}

#[test]
fn a_correct_spelling_is_never_learned_as_a_correction() {
    let mut e = engine();
    type_keys(&mut e, "zhiwen");
    let before = position(&mut e, "织文").expect("the correct spelling offers 织文");
    assert!(e.select(before));
    type_keys(&mut e, "zhiwen");
    assert_eq!(position(&mut e, "织文"), Some(before), "the correct spelling keeps its ordinary rank");
}
