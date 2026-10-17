use std::path::PathBuf;
use weave_dict::{
    blob::Source,
    lexicon::{Builder, Kind},
    syllable,
};
use weave_engine::{Engine, Paths, Schema};

fn directory(name: &str) -> PathBuf {
    let p = std::env::temp_dir().join(format!("weave-pageprefix-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&p);
    std::fs::create_dir_all(&p).unwrap();
    p
}

fn engine(dir: &std::path::Path) -> Engine {
    let mut b = Builder::new(Kind::Pinyin);
    // 同一个读音下放足够多的词，让候选总数超过「首屏上限」（120），从而真的会走翻页补全那条路。
    // Enough entries under one reading that the list passes the first-screen cap (120) and really takes the
    // page-completion path.
    for i in 0..400u32 {
        let word = char::from_u32(0x4e00 + i).unwrap().to_string();
        b.insert(&syllable::parse_seq("shi").unwrap(), &word, (100 + i) as u16);
    }
    b.insert(&syllable::parse_seq("shi").unwrap(), "是", 10);
    let lex = dir.join("pinyin.wvl");
    std::fs::write(&lex, b.build()).unwrap();
    let mut e = Engine::new(&Paths {
        pinyin_lexicon: Some(Source::file(lex)),
        user_dir: Some(dir.join("user")),
        ..Default::default()
    });
    e.set_schema(Schema::Pinyin);
    e.set_learning(false);
    e.options.emoji = false;
    e.options.pinyin_hint = false;
    e
}

fn type_shi(e: &mut Engine) {
    e.clear();
    e.set_context(None);
    for c in "shi".chars() {
        e.input_char(c);
    }
}

/// 翻页补全（cap 抬到 800 再 refresh）不能改变已经显示过的候选前缀：界面是按下标点选与长按的，
/// 前缀一变就会点到别的词。
/// Completing the list (cap raised to 800, then refresh) may not change the prefix already shown: the UI picks and
/// long-presses by index, so a changed prefix would act on a different word.
#[test]
fn completing_the_list_leaves_the_first_screen_unchanged() {
    let dir = directory("prefix");
    let mut e = engine(&dir);
    type_shi(&mut e);
    let first: Vec<String> = e.candidates(0, 120).into_iter().map(|c| c.text).collect();
    assert!(first.len() >= 120, "需要超过一屏的候选，实际 {}", first.len());
    // 首屏之外还有候选，才能走到「越界时补全」那条路。
    // More candidates exist past the first screen, so the over-range completion path is really taken.
    assert!(e.snapshot().total_candidates > first.len(), "应当还有未生成的候选");

    // 模拟界面向下翻页：越界时内核会把候选补全到 800 再重排。
    // Page past the generated part: the engine then completes the list to 800 and rebuilds it.
    let tail: Vec<String> = e.candidates(first.len(), 30).into_iter().map(|c| c.text).collect();
    assert!(!tail.is_empty(), "补全后应当还能取到后续候选");
    assert!(
        e.snapshot().total_candidates > 120,
        "补全后候选总数应超过首屏上限，说明补全确实发生"
    );

    let again: Vec<String> = e.candidates(0, 120).into_iter().map(|c| c.text).collect();
    assert_eq!(first, again, "补全后前 120 项必须逐项一致");
}

/// 逐页取整列，与一次取全的前 120 项一致。
/// Reading the first screen page by page matches reading it in one go.
#[test]
fn paging_through_the_first_screen_matches_one_call() {
    let dir = directory("paged");
    let mut e = engine(&dir);
    type_shi(&mut e);
    let all: Vec<String> = e.candidates(0, 120).into_iter().map(|c| c.text).collect();

    let mut paged = Vec::new();
    for offset in (0..120).step_by(30) {
        paged.extend(e.candidates(offset, 30).into_iter().map(|c| c.text));
        if paged.len() >= all.len() { break; }
    }
    assert_eq!(all, paged.into_iter().take(all.len()).collect::<Vec<String>>());
}
