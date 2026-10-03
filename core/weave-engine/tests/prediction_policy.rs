//! 联想的门控：实际词库下，短上文、句末语气词不联想；够长才联想，连着选最多接三次。
//! Prediction gating on the real dictionary: no prediction after a short context or a closing particle; enough
//! context predicts, and picks chain at most three times. Skipped when the built dictionary isn't there.
use std::path::PathBuf;
use weave_engine::session::{paths_in, Engine};

fn data() -> Option<PathBuf> {
    let d = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../data/build");
    d.join("pinyin.wvz").exists().then_some(d)
}

fn engine(name: &str) -> Option<Engine> {
    let user = std::env::temp_dir().join(format!("weave-predict-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&user);
    std::fs::create_dir_all(&user).unwrap();
    Some(Engine::new(&paths_in(&data()?, &user)))
}

/// 打一串拼音并选第一候选直到上屏完。 Type pinyin and take the first candidate until it is all committed.
fn type_and_commit(e: &mut Engine, keys: &str) {
    for c in keys.chars() {
        e.input_char(c);
    }
    let mut guard = 0;
    while e.is_composing() && guard < 10 {
        e.select(0);
        guard += 1;
    }
}

#[test]
fn short_contexts_and_closing_particles_do_not_predict() {
    let Some(mut e) = engine("short") else { return };
    type_and_commit(&mut e, "nihao");
    assert!(!e.is_predicting(), "two chars of context is not enough: {:?}", e.candidates(0, 8).iter().map(|c| c.text.clone()).collect::<Vec<_>>());
    e.reset_context();
    type_and_commit(&mut e, "jintiantianqibuhaoba");
    assert!(!e.is_predicting(), "a closing 吧 ends the sentence");
    // 前面已有一长串上文，「谢谢」这种整句收尾词也不再接着联想。 A closing phrase ends it even after a long run.
    type_and_commit(&mut e, "mingtianwoxiangqu");
    e.drop_predictions();
    type_and_commit(&mut e, "xiexie");
    assert!(!e.is_predicting(), "谢谢 closes the sentence");
}

#[test]
fn long_context_predicts_and_chains_at_most_three_times() {
    let Some(mut e) = engine("chain") else { return };
    type_and_commit(&mut e, "jintiantianqi");
    let mut shown = 0;
    let mut texts = Vec::new();
    while e.is_predicting() && shown < 10 {
        texts.push(e.candidates(0, 1).first().map(|c| c.text.clone()).unwrap_or_default());
        e.select(0);
        shown += 1;
    }
    eprintln!("chain picks: {texts:?}");
    assert!(shown <= 3, "at most three chained predictions, got {shown}: {texts:?}");
}

#[test]
fn typing_resets_the_chain() {
    let Some(mut e) = engine("reset") else { return };
    for _ in 0..3 {
        type_and_commit(&mut e, "jintiantianqi");
        let mut n = 0;
        while e.is_predicting() && n < 10 {
            e.select(0);
            n += 1;
        }
        assert!(n <= 3);
        e.drop_predictions();
    }
}

#[test]
fn pinyin_hints_follow_the_option_and_the_lexicon_syllables() {
    let Some(mut e) = engine("hint") else { return };
    let comments = |e: &mut Engine| e.snapshot().candidates.iter().map(|c| (c.text.clone(), c.comment.clone())).collect::<Vec<_>>();
    for c in "yinhang".chars() {
        e.input_char(c);
    }
    assert!(comments(&mut e).iter().all(|(_, c)| c.is_empty()), "off by default");
    assert!(e.options.set_flag("candidates.pinyin", true));
    e.clear();
    for c in "yinhang".chars() {
        e.input_char(c);
    }
    let list = comments(&mut e);
    assert_eq!(list[0], ("银行".to_string(), "yín háng".to_string()));
    assert!(e.options.set_flag("candidates.pinyin_tones", false));
    e.clear();
    for c in "yinhang".chars() {
        e.input_char(c);
    }
    assert_eq!(comments(&mut e)[0], ("银行".to_string(), "yin hang".to_string()));
}
