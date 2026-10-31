use weave_dict::{lexicon::{Builder, Kind, Lexicon}, syllable};
use weave_engine::Engine;

fn engine(memo: bool) -> Engine {
    let mut b = Builder::new(Kind::Pinyin);
    for (code, text, cost) in [("ni", "你", 500u16), ("ni", "泥", 800), ("hao", "好", 500), ("ni hao", "你好", 900)] {
        b.insert(&syllable::parse_seq(code).unwrap(), text, cost);
    }
    let mut e = Engine::with_lexicons(Some(Lexicon::from_bytes(b.build()).unwrap()), None, None);
    e.options.prediction = false;
    e.options.emoji = false;
    e.options.span_memo = memo;
    e
}

fn candidates(e: &mut Engine, typed: &str) -> Vec<String> {
    e.clear();
    for c in typed.chars() { assert!(e.input_char(c)); }
    e.snapshot().candidates.into_iter().map(|c| c.text).collect()
}

/// 同一串输入与选择，缓存开与关得到的候选每一步都相同（缓存只影响速度）。
/// The same inputs and picks, memo on and off: the candidates match at every step (the memo changes speed only).
#[test]
fn memo_on_and_off_agree_at_every_step_of_a_session() {
    let mut on = engine(true);
    let mut off = engine(false);
    let script: Vec<(&str, Option<usize>)> = vec![
        ("ni", None), ("ni", Some(1)), ("nihao", None), ("ni", None),
        ("ni", Some(1)), ("nihao", None), ("ni", Some(0)), ("hao", None),
    ];
    for (typed, pick) in script {
        let a = candidates(&mut on, typed);
        let b = candidates(&mut off, typed);
        assert_eq!(a, b, "after typing {typed}");
        if let Some(i) = pick {
            let i = i.min(a.len().saturating_sub(1));
            assert_eq!(on.select(i), off.select(i));
        }
    }
}

/// 缓存开时，学习后的结果与缓存关时完全一致（不返回学习前的陈旧顺序）。
/// With the memo on, the answer after learning equals the answer with the memo off (no stale order).
#[test]
fn learning_is_seen_through_the_memo_exactly_as_without_it() {
    let mut on = engine(true);
    let mut off = engine(false);
    for _ in 0..3 {
        let pick_on = candidates(&mut on, "ni").iter().position(|t| t == "泥").unwrap();
        let pick_off = candidates(&mut off, "ni").iter().position(|t| t == "泥").unwrap();
        assert_eq!(pick_on, pick_off);
        assert!(on.select(pick_on));
        assert!(off.select(pick_off));
    }
    assert_eq!(candidates(&mut on, "ni"), candidates(&mut off, "ni"));
}
