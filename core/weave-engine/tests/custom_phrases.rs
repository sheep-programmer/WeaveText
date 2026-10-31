use serde_json::{json, Value};
use weave_dict::{lexicon::{Builder, Kind, Lexicon}, syllable};
use weave_engine::Engine;

fn engine() -> Engine {
    let mut b = Builder::new(Kind::Pinyin);
    for (code, text) in [("ni", "你"), ("hao", "好"), ("shi jian", "时间"), ("bei jing", "北京")] {
        b.insert(&syllable::parse_seq(code).unwrap(), text, 10000);
    }
    let mut e = Engine::with_lexicons(Some(Lexicon::from_bytes(b.build()).unwrap()), None, None);
    e.options.prediction = false;
    e.options.emoji = false;
    e
}

fn type_keys(e: &mut Engine, text: &str) {
    e.clear();
    for c in text.chars() { assert!(e.input_char(c), "rejected {c} in {text}"); }
}

fn call(e: &mut Engine, cmd: Value) -> Value {
    e.features(&cmd)
}

#[test]
fn a_whole_code_offers_its_phrases_first_in_weight_order_and_commits_them_as_written() {
    let mut e = engine();
    assert_eq!(call(&mut e, json!({"op":"addPhrase","code":"dz","text":"北京市朝阳区","weight":10}))["ok"], true);
    assert_eq!(call(&mut e, json!({"op":"addPhrase","code":"dz","text":"上海市浦东新区","weight":500}))["ok"], true);
    type_keys(&mut e, "dz");
    let s = e.snapshot();
    assert_eq!(s.candidates[0].text, "上海市浦东新区", "{:?}", s.candidates);
    assert_eq!(s.candidates[1].text, "北京市朝阳区");
    assert_eq!(s.candidates[0].comment, "快捷短语");
    assert!(e.select(0));
    assert_eq!(e.snapshot().commit, "上海市浦东新区");
    assert!(e.user_words("", 0, 100).iter().all(|w| w.text != "上海市浦东新区"), "phrases are not learned");
}

#[test]
fn a_phrase_code_that_is_also_pinyin_comes_first_but_keeps_the_ordinary_word() {
    let mut e = engine();
    call(&mut e, json!({"op":"addPhrase","code":"ni","text":"拟定"}));
    type_keys(&mut e, "ni");
    let s = e.snapshot();
    assert_eq!(s.candidates[0].text, "拟定");
    assert!(s.candidates.iter().any(|c| c.text == "你"), "{:?}", s.candidates);
}

#[test]
fn a_prefix_or_a_longer_input_does_not_show_phrases() {
    let mut e = engine();
    call(&mut e, json!({"op":"addPhrase","code":"dz","text":"北京市朝阳区"}));
    type_keys(&mut e, "d");
    assert!(e.snapshot().candidates.iter().all(|c| c.text != "北京市朝阳区"));
    type_keys(&mut e, "dzz");
    assert!(e.snapshot().candidates.iter().all(|c| c.text != "北京市朝阳区"));
}

#[test]
fn multi_line_text_commits_intact() {
    let mut e = engine();
    let body = "您好：\n地址是北京市朝阳区。\n谢谢";
    call(&mut e, json!({"op":"addPhrase","code":"rb","text":body}));
    type_keys(&mut e, "rb");
    assert!(e.select(0));
    assert_eq!(e.snapshot().commit, body);
}

#[test]
fn invalid_phrases_are_refused_with_a_reason() {
    let mut e = engine();
    assert_eq!(call(&mut e, json!({"op":"addPhrase","code":"Dz","text":"x"}))["ok"], false);
    assert_eq!(call(&mut e, json!({"op":"addPhrase","code":"dz","text":"x","weight":0}))["ok"], false);
    assert_eq!(call(&mut e, json!({"op":"addPhrase","code":"dz","text":""}))["ok"], false);
    assert_eq!(call(&mut e, json!({"op":"addPhrase","code":"dz","text":"x"}))["ok"], true);
    let again = call(&mut e, json!({"op":"addPhrase","code":"dz","text":"x"}));
    assert_eq!(again["ok"], false);
    assert!(again["error"].as_str().unwrap().contains("已有"));
}

#[test]
fn list_weight_remove_and_listing_round_trip_through_the_engine() {
    let mut e = engine();
    call(&mut e, json!({"op":"addPhrase","code":"dz","text":"北京","weight":10}));
    assert_eq!(call(&mut e, json!({"op":"setPhraseWeight","code":"dz","text":"北京","weight":900}))["ok"], true);
    let listed = call(&mut e, json!({"op":"snippets"}));
    assert_eq!(listed["items"][0], json!({"code":"dz","text":"北京","weight":900}));
    assert_eq!(call(&mut e, json!({"op":"removePhrase","code":"dz","text":"北京"}))["ok"], true);
    assert_eq!(call(&mut e, json!({"op":"snippets"}))["items"], json!([]));
}

#[test]
fn export_and_import_move_phrases_between_engines_and_skip_duplicates() {
    let mut a = engine();
    call(&mut a, json!({"op":"addPhrase","code":"zs","text":"谢谢！","weight":100}));
    call(&mut a, json!({"op":"addPhrase","code":"rb","text":"日报\n今天完成了：\t输入法"}));
    let exported = call(&mut a, json!({"op":"exportPhrases"}))["text"].as_str().unwrap().to_string();

    let mut b = engine();
    let first = call(&mut b, json!({"op":"importPhrases","text":exported}));
    assert_eq!((first["added"].as_u64(), first["skipped"].as_u64()), (Some(2), Some(0)));
    let second = call(&mut b, json!({"op":"importPhrases","text":exported}));
    assert_eq!((second["added"].as_u64(), second["skipped"].as_u64()), (Some(0), Some(2)));
    type_keys(&mut b, "rb");
    assert!(b.select(0));
    assert_eq!(b.snapshot().commit, "日报\n今天完成了：\t输入法");
}
