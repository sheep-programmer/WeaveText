use weave_dict::{lexicon::{Builder, Kind, Lexicon}, syllable};
use weave_engine::{special::date_candidates, Engine, Schema};

fn engine() -> Engine {
    let mut b = Builder::new(Kind::Pinyin);
    for (code, text) in [("shi jian", "时间"), ("ri qi", "日期"), ("xing qi", "星期"),
        ("jin tian", "今天"), ("ming tian", "明天"), ("zuo tian", "昨天"),
        ("xian zai", "现在"), ("ni", "你")] {
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

#[test]
fn full_date_words_offer_current_values_after_the_literal_word() {
    let mut e = engine();
    for (code, literal, comment) in [("shijian", "时间", "时间"), ("riqi", "日期", "日期"),
        ("xingqi", "星期", "星期"), ("jintian", "今天", "日期"),
        ("mingtian", "明天", "日期"), ("zuotian", "昨天", "日期"), ("xianzai", "现在", "时间")] {
        type_keys(&mut e, code);
        let s = e.snapshot();
        assert_eq!(s.candidates[0].text, literal, "{code}");
        assert_eq!(s.candidates[1].comment, comment, "{code}: {:?}", s.candidates);
        let value = s.candidates[1].text.clone();
        assert_ne!(value, literal);
        assert!(e.select(1));
        assert_eq!(e.snapshot().commit, value);
        assert!(e.user_words("", 0, 100).iter().all(|w| w.text != value));
    }
}

#[test]
fn relative_dates_weekday_and_midnight_follow_local_time() {
    // 2026-09-27 23:30 UTC: already Monday in UTC+8, still Sunday in UTC-7.
    let now = 20_723 * 86_400 + 23 * 3600 + 30 * 60;
    for (code, offset, want) in [("riqi", 480, "2026年9月28日"),
        ("jintian", -420, "2026年9月27日"), ("mingtian", 480, "2026年9月29日"),
        ("zuotian", -420, "2026年9月26日"), ("shijian", 480, "07:30"),
        ("xianzai", -420, "16:30"), ("xingqi", 480, "星期一"),
        ("zhouji", -420, "星期日"), ("星期", 480, "星期一"),
        ("日期", 480, "2026年9月28日"), ("时间", -420, "16:30")] {
        let c = date_candidates(code, now, offset);
        assert_eq!(c.first().map(|c| c.0.as_str()), Some(want), "{code}");
    }
    // Relative dates cross year boundaries correctly.
    assert_eq!(date_candidates("mingtian", 365 * 86_400 - 1, 0)[0].0, "1971年1月1日");
    assert_eq!(date_candidates("zuotian", 0, 0)[0].0, "1969年12月31日");
}

#[test]
fn date_aliases_do_not_expand_inside_sentences_or_after_partial_selection() {
    let mut e = engine();
    type_keys(&mut e, "nishijian");
    assert!(e.snapshot().candidates.iter().all(|c| c.comment != "时间"));
    let i = e.candidates(0, 100).iter().position(|c| c.text == "你").unwrap();
    assert!(e.select(i));
    assert!(e.snapshot().candidates.iter().all(|c| c.comment != "时间"));
    e.set_schema(Schema::English);
    type_keys(&mut e, "shijian");
    assert!(e.snapshot().candidates.iter().all(|c| c.comment != "时间"));
}

#[test]
fn double_pinyin_and_keypad_offer_values_for_the_same_decoded_word() {
    use weave_engine::{shuangpin::SchemeId, t9::Grouping};
    let mut e = engine();
    for (schema, code, word) in [(Schema::Shuangpin(SchemeId::Xiaohe), "uijm", "时间"),
        (Schema::Keypad(Grouping::Nine), "7445426", "时间")] {
        e.set_schema(schema);
        type_keys(&mut e, code);
        let s = e.snapshot();
        assert_eq!(s.candidates[0].text, word);
        assert_eq!(s.candidates[1].comment, "时间");
    }
}

#[test]
fn unicode_v_expression_can_be_edited_cancelled_and_committed() {
    let mut e = engine();
    type_keys(&mut e, "v（128＋32）×4=");
    assert_eq!(e.snapshot().candidates[0].text, "640");
    assert!(e.backspace());
    assert!(e.backspace());
    assert!(e.backspace());
    assert_eq!(e.snapshot().preedit, "v（128＋32）");
    assert!(e.input_char('÷'));
    assert!(e.input_char('4'));
    assert_eq!(e.snapshot().candidates[0].text, "40");
    assert!(e.select(0));
    assert_eq!(e.snapshot().commit, "40");
    assert_eq!(e.user_word_count(), 0);
    type_keys(&mut e, "v2+3=");
    e.commit_raw();
    assert_eq!(e.snapshot().commit, "v2+3=");
}

#[test]
fn date_templates_can_include_weekday_without_learning_the_expansion() {
    let mut e = engine();
    assert_eq!(e.features(&serde_json::json!({"op":"setSnippet", "code":"dz", "text":"{date} {weekday} {time}"}))["ok"], true);
    type_keys(&mut e, "dz");
    let text = e.snapshot().candidates[0].text.clone();
    assert!(!text.contains('{'), "unexpanded template: {text}");
    assert!(text.contains("星期"));
}
