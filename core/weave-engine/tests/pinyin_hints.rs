use weave_dict::{lexicon::{Builder, Kind, Lexicon}, syllable};
use weave_engine::Engine;

fn engine(hint: bool) -> Engine {
    let mut b = Builder::new(Kind::Pinyin);
    for (code, text, cost) in [("ni", "你", 500u16), ("ni", "泥", 800), ("hao", "好", 500)] {
        b.insert(&syllable::parse_seq(code).unwrap(), text, cost);
    }
    let mut e = Engine::with_lexicons(Some(Lexicon::from_bytes(b.build()).unwrap()), None, None);
    e.options.prediction = false;
    e.options.emoji = false;
    e.options.pinyin_hint = hint;
    e
}

fn type_keys(e: &mut Engine, typed: &str) {
    e.clear();
    for c in typed.chars() { assert!(e.input_char(c)); }
}

#[test]
fn hints_appear_on_the_first_page_and_on_later_pages() {
    let mut e = engine(true);
    type_keys(&mut e, "ni");
    let snapshot = e.snapshot();
    assert!(snapshot.candidates.iter().any(|c| c.text == "你" && !c.pinyin.is_empty()), "{:?}", snapshot.candidates);
    let page = e.candidates(0, 2);
    assert!(page.iter().all(|c| !c.pinyin.is_empty()), "{page:?}");
}

#[test]
fn hints_are_empty_when_the_option_is_off() {
    let mut e = engine(false);
    type_keys(&mut e, "ni");
    assert!(e.snapshot().candidates.iter().all(|c| c.pinyin.is_empty()));
    assert!(e.candidates(0, 9).iter().all(|c| c.pinyin.is_empty()));
}

fn long_word_engine() -> Engine {
    let mut b = Builder::new(Kind::Pinyin);
    for (code, text, cost) in [("tian qi hen hao", "天气很好", 900u16), ("ni hao", "你好", 900)] {
        b.insert(&syllable::parse_seq(code).unwrap(), text, cost);
    }
    let mut e = Engine::with_lexicons(Some(Lexicon::from_bytes(b.build()).unwrap()), None, None);
    e.options.prediction = false;
    e.options.emoji = false;
    e.options.pinyin_hint = true;
    e
}

/// 注音只用于三个字以内的候选；四个字及以上不标拼音（拼音太长会挡住后面的候选）。
/// Pronunciation is shown only for candidates of up to three characters; longer ones get none, since the
/// annotation would cover the candidates after them.
#[test]
fn pronunciation_is_shown_only_for_words_of_three_characters_or_fewer() {
    let mut e = long_word_engine();
    type_keys(&mut e, "tianqihenhao");
    let word = e.snapshot().candidates.into_iter().find(|c| c.text == "天气很好").expect("4-character word offered");
    assert_eq!(word.pinyin, "", "four characters get no pronunciation");
    type_keys(&mut e, "nihao");
    let two = e.snapshot().candidates.into_iter().find(|c| c.text == "你好").expect("2-character word offered");
    assert_eq!(two.pinyin.split_whitespace().count(), 2, "{:?}", two.pinyin);
}
