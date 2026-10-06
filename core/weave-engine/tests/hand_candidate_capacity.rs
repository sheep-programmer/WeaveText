use weave_dict::{blob::Source, hand::{Builder, Stroke}, handnet::encode_wire};
use weave_engine::{Engine, Paths, Schema};

#[test]
fn expanded_handwriting_candidates_keep_the_thirtieth_choice_selectable() {
    let dir = std::env::temp_dir().join(format!("weave-hand-capacity-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let stroke: Stroke = vec![(0.0, 0.0), (100.0, 0.0)];
    let mut builder = Builder::default();
    builder.push('一', 255, &[stroke.clone()]);
    let file = dir.join("hand.wvh");
    std::fs::write(&file, builder.build()).unwrap();
    let mut engine = Engine::new(&Paths { hand: Some(Source::file(&file)), ..Default::default() });
    engine.set_schema(Schema::Hand);
    engine.set_learning(false);
    engine.options.emoji = false;
    engine.options.pinyin_hint = false;
    let group: Vec<_> = (0..30).map(|i| (char::from_u32(0x4e00 + i).unwrap(), -(i as f32))).collect();
    assert!(engine.hand_apply_wire(vec![stroke], &encode_wire(&[group.clone()])));
    assert_eq!(engine.snapshot().candidates.len(), 30);
    assert!(engine.select(29));
    assert_eq!(engine.snapshot().commit, group[29].0.to_string());
    drop(engine);
    std::fs::remove_dir_all(dir).unwrap();
}
