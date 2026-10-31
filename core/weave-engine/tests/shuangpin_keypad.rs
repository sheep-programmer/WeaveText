use weave_dict::{lexicon::{Builder, Kind, Lexicon}, syllable};
use weave_engine::session::{Engine, Schema};
use weave_engine::shuangpin::SchemeId;
use weave_engine::t9::Grouping;

fn engine(schema: Schema) -> Engine {
    let mut b = Builder::new(Kind::Pinyin);
    for (code, text) in [("zhong", "中"), ("guo", "国")] {
        b.insert(&syllable::parse_seq(code).unwrap(), text, 10000);
    }
    let mut e = Engine::with_lexicons(Some(Lexicon::from_bytes(b.build()).unwrap()), None, None);
    e.options.prediction = false;
    e.options.emoji = false;
    e.set_schema(schema);
    e
}

fn type_keys(e: &mut Engine, keys: &str) {
    e.clear();
    for c in keys.chars() {
        assert!(e.input_char(c), "rejected {c} in {keys}");
    }
}

#[test]
fn fourteen_key_xiaohe_reads_zhong_from_v_then_s() {
    let mut e = engine(Schema::ShuangpinKeypad(SchemeId::Xiaohe, Grouping::Fourteen));
    type_keys(&mut e, "vs");
    assert!(e.snapshot().candidates.iter().any(|c| c.text == "中"), "{:?}", e.snapshot().candidates);
}

#[test]
fn nine_key_xiaohe_reads_zhong_from_the_digits_of_v_and_s() {
    // v 在 8（tuv），s 在 7（pqrs）。
    let mut e = engine(Schema::ShuangpinKeypad(SchemeId::Xiaohe, Grouping::Nine));
    type_keys(&mut e, "87");
    assert!(e.snapshot().candidates.iter().any(|c| c.text == "中"), "{:?}", e.snapshot().candidates);
}

#[test]
fn the_schema_key_round_trips() {
    assert_eq!(Schema::from_key("shuangpin14:xiaohe"), Some(Schema::ShuangpinKeypad(SchemeId::Xiaohe, Grouping::Fourteen)));
    assert_eq!(Schema::from_key("shuangpin9:ziranma"), Some(Schema::ShuangpinKeypad(SchemeId::Ziranma, Grouping::Nine)));
    assert_eq!(Schema::ShuangpinKeypad(SchemeId::Xiaohe, Grouping::Nine).key(), "shuangpin9:xiaohe");
}
