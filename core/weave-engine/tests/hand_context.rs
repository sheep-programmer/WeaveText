//! 手写候选的上文重排与传输格式。 Context re-ranking of handwriting candidates and the wire format.
use std::path::PathBuf;
use weave_dict::{blob::Source, gram::GramBuilder, handnet::{decode_wire, encode_wire}};
use weave_engine::{Engine, Paths, Schema};

fn engine(name: &str) -> Engine {
    let dir: PathBuf = std::env::temp_dir().join(format!("weave-handctx-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    let mut g = GramBuilder::default();
    g.push("天气", 10.0);
    g.push("天天", 10.5);
    let path = dir.join("grammar.wvg");
    std::fs::write(&path, g.build()).unwrap();
    let mut e = Engine::new(&Paths { gram_model: Some(Source::file(path)), user_dir: Some(dir.join("user")), ..Default::default() });
    e.set_schema(Schema::Hand);
    e
}

fn texts(e: &mut Engine) -> Vec<String> {
    e.snapshot().candidates.iter().map(|c| c.text.clone()).collect()
}

#[test]
fn wire_roundtrips_scores_and_rejects_garbage() {
    let groups = vec![vec![('汽', -1.0), ('气', -1.8)], vec![('x', 0.5)]];
    let wire = encode_wire(&groups);
    let back = decode_wire(&wire).unwrap();
    assert_eq!(back.len(), 2);
    assert_eq!(back[0][1].0, '气');
    assert!((back[0][1].1 + 1.8).abs() < 0.001);
    // 旧格式（只有字码位）、截断和越界都不当作新格式。 Old format, truncation and overflow are not the new format.
    assert!(decode_wire(&['气' as u32, '汽' as u32]).is_none());
    assert!(decode_wire(&wire[..wire.len() - 1]).is_none());
    let mut bad = wire.clone();
    bad[2] = 9999;
    assert!(decode_wire(&bad).is_none());
    assert!(decode_wire(&[]).is_none());
}

#[test]
fn context_lifts_the_char_that_follows_it() {
    let mut e = engine("one");
    let wire = encode_wire(&[vec![('汽', -1.0), ('气', -1.8)]]);
    let stroke = vec![vec![(0.0, 0.0), (10.0, 10.0)]];
    // 没有上文或权重为 0：笔迹说了算。 No context, or weight 0: the ink decides.
    e.options.hand_lm_weight = 0.25;
    assert!(e.hand_apply_wire(stroke.clone(), &wire));
    assert_eq!(texts(&mut e)[0], "汽");
    e.clear();
    e.set_context(Some("天".into()));
    e.options.hand_lm_weight = 0.0;
    assert!(e.hand_apply_wire(stroke.clone(), &wire));
    assert_eq!(texts(&mut e)[0], "汽");
    // 前面是「天」：「气」成词，排到前面。 After 天, 气 forms a word and moves up.
    e.clear();
    e.set_context(Some("天".into()));
    e.options.hand_lm_weight = 0.25;
    assert!(e.hand_apply_wire(stroke.clone(), &wire));
    assert_eq!(texts(&mut e)[..2], ["气".to_string(), "汽".to_string()]);
    // 旧格式的数组照旧可用。 The old array format still works.
    e.clear();
    assert!(e.hand_apply_wire(stroke, &['汽' as u32, '气' as u32]));
    assert_eq!(texts(&mut e)[0], "汽");
}

#[test]
fn spaced_line_picks_the_known_combination() {
    let mut e = engine("line");
    e.options.hand_lm_weight = 0.25;
    // 两个字组：第一组「天」最像；第二组「汽」比「气」更像一点，但「天气」是搭配。
    // Two groups: the second reads 汽 a little better than 气, but 天气 is the collocation.
    let wire = encode_wire(&[vec![('天', -0.5), ('夭', -2.0)], vec![('汽', -1.0), ('气', -1.4)]]);
    let stroke = vec![vec![(0.0, 0.0), (10.0, 10.0)]];
    assert!(e.hand_apply_wire(stroke, &wire));
    assert_eq!(texts(&mut e)[0], "天气");
}

#[test]
fn line_context_can_use_the_fifth_candidate_retained_by_the_recognizer() {
    let mut e = engine("fifth");
    e.options.hand_lm_weight = 0.25;
    let wire = encode_wire(&[vec![('天', -0.5)], vec![('汽', -1.0), ('池', -1.05), ('汁', -1.1), ('汕', -1.15), ('气', -1.4)]]);
    assert!(e.hand_apply_wire(vec![vec![(0.0, 0.0), (10.0, 10.0)]], &wire));
    assert_eq!(texts(&mut e)[0], "天气");
}

#[test]
fn long_wire_line_produces_bounded_complete_candidates() {
    let mut e = engine("eight");
    e.options.hand_lm_weight = 0.0;
    e.options.hand_word_bonus = 0.0;
    let wire = encode_wire(&vec![vec![('天', 0.0), ('大', -1.0), ('夭', -2.0), ('太', -3.0), ('夫', -4.0)]; 8]);
    assert!(e.hand_apply_wire(vec![vec![(0.0, 0.0), (10.0, 10.0)]], &wire));
    let got = texts(&mut e);
    assert_eq!(got[0], "天天天天天天天天");
    assert_eq!(got.len(), weave_engine::session::HAND_CANDIDATES);
    assert!(got.iter().all(|s| s.chars().count() == 8));
}
