use std::path::PathBuf;
use weave_dict::{
    blob::Source,
    lexicon::{Builder, Kind},
    syllable,
};
use weave_engine::{shuangpin::SchemeId, t9::Grouping, Engine, Paths, Schema};

fn directory(name: &str) -> PathBuf {
    let p = std::env::temp_dir().join(format!("weave-adaptive-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&p);
    std::fs::create_dir_all(&p).unwrap();
    p
}
fn fixture(dir: &std::path::Path) -> Paths {
    let mut b = Builder::new(Kind::Pinyin);
    for (py, word, cost) in [
        ("shi", "是", 10),
        ("shi", "时", 20),
        ("shi", "嗜", 28000),
        ("shi jian", "时间", 100),
        ("jian", "间", 10000),
        ("xiu", "修", 7000),
        ("ba", "吧", 7000),
        ("xiu ba", "秀吧", 10),
        ("xiu", "秀", 9000),
    ] {
        b.insert(&syllable::parse_seq(py).unwrap(), word, cost);
    }
    let lex = dir.join("pinyin.wvl");
    std::fs::write(&lex, b.build()).unwrap();
    let mut en = Builder::new(Kind::Letters);
    for (word, cost) in [
        ("hello", 1000),
        ("help", 1200),
        ("helmet", 1400),
        ("world", 1000),
        ("there", 1000),
    ] {
        en.insert(&weave_engine::table::code_key(word).unwrap(), word, cost);
    }
    let english = dir.join("english.wvl");
    std::fs::write(&english, en.build()).unwrap();
    Paths {
        pinyin_lexicon: Some(Source::file(lex)),
        english_lexicon: Some(Source::file(english)),
        user_dir: Some(dir.join("user")),
        ..Default::default()
    }
}
fn type_keys(e: &mut Engine, code: &str) -> Vec<String> {
    e.clear();
    e.set_context(None);
    for c in code.chars() {
        e.input_char(c);
    }
    e.candidates(0, 800).into_iter().map(|c| c.text).collect()
}
fn choose(e: &mut Engine, code: &str, text: &str) {
    let c = type_keys(e, code);
    let i = c.iter().position(|c| c == text).expect("candidate present");
    assert!(e.select(i));
    assert_eq!(e.snapshot().commit, text);
}

#[test]
fn five_real_commits_beat_old_counts_and_survive_an_independent_reader() {
    for (name, schema, code) in [
        ("full", Schema::Pinyin, "shi"),
        ("double", Schema::Shuangpin(SchemeId::Xiaohe), "ui"),
        ("keypad", Schema::Keypad(Grouping::Nine), "744"),
    ] {
        let dir = directory(name);
        let paths = fixture(&dir);
        let mut e = Engine::new(&paths);
        e.options.emoji = false;
        e.options.prediction = false;
        e.set_schema(schema);
        e.import_user_words("是\tshi\t100000\n时\tshi\t90000\n");
        assert_ne!(type_keys(&mut e, code)[0], "嗜");
        for round in 0..5 {
            choose(&mut e, code, "嗜");
            if round >= 1 {
                assert_eq!(type_keys(&mut e, code)[0], "嗜");
            }
        }
        // Do not flush/drop the first engine: committed choices must already be visible on disk.
        let mut other = Engine::new(&paths);
        other.set_schema(schema);
        assert_eq!(type_keys(&mut other, code)[0], "嗜");
        assert_eq!(e.user_words("嗜", 0, 10)[0].count, 5);
        e.set_schema(Schema::Pinyin);
        assert_eq!(
            type_keys(&mut e, "shijian")[0],
            "时间",
            "a learned single must not hijack a longer phrase"
        );
        drop(other);
        drop(e);
        std::fs::remove_dir_all(dir).unwrap();
    }
}

#[test]
fn undo_restores_the_previous_preference_and_does_not_learn_in_private_fields() {
    let dir = directory("undo");
    let paths = fixture(&dir);
    let mut e = Engine::new(&paths);
    e.options.prediction = false;
    for _ in 0..5 {
        choose(&mut e, "shi", "嗜");
    }
    let before = e.export_user_words();
    choose(&mut e, "shi", "时");
    assert!(!e.backspace());
    assert_eq!(e.export_user_words(), before);
    assert_eq!(type_keys(&mut e, "shi")[0], "嗜");
    e.set_learning(false);
    for _ in 0..5 {
        choose(&mut e, "shi", "时");
    }
    assert_eq!(e.export_user_words(), before);
    e.set_learning(true);
    assert_eq!(type_keys(&mut e, "shi")[0], "嗜");
    for _ in 0..5 {
        choose(&mut e, "shi", "时");
    }
    assert_eq!(
        type_keys(&mut e, "shi")[0],
        "时",
        "a changed habit must be able to displace the previous favorite"
    );
    drop(e);
    std::fs::remove_dir_all(dir).unwrap();
}

#[test]
fn chosen_generated_phrase_becomes_a_word_and_can_lead_the_next_candidates() {
    let dir = directory("phrase");
    let paths = fixture(&dir);
    let mut e = Engine::new(&paths);
    e.options.prediction = false;
    let candidates = type_keys(&mut e, "xiuba");
    assert!(e.select(candidates.iter().position(|c| c == "修").unwrap()));
    let candidates = e.candidates(0, 800);
    assert!(e.select(candidates.iter().position(|c| c.text == "吧").unwrap()));
    assert_eq!(e.snapshot().commit, "修吧");
    for _ in 0..4 {
        choose(&mut e, "xiuba", "修吧");
    }
    assert_eq!(type_keys(&mut e, "xiuba")[0], "修吧");
    assert!(e
        .user_words("修吧", 0, 10)
        .iter()
        .any(|w| w.pinyin == "xiu ba"));
    let mut other = Engine::new(&paths);
    assert_eq!(type_keys(&mut other, "xiuba")[0], "修吧");
    drop(other);
    drop(e);
    std::fs::remove_dir_all(dir).unwrap();
}

#[test]
fn english_completes_learns_prefix_choices_and_commits_without_an_added_space() {
    let dir = directory("english");
    let paths = fixture(&dir);
    let mut e = Engine::new(&paths);
    e.set_schema(Schema::English);
    let candidates = type_keys(&mut e, "hel");
    assert_eq!(candidates[0], "hel");
    assert!(candidates.contains(&"hello".to_string()));
    for _ in 0..5 {
        choose(&mut e, "hel", "helmet");
    }
    assert_eq!(type_keys(&mut e, "hel")[1], "helmet");
    assert_eq!(type_keys(&mut e, "Hel")[1], "Helmet");
    choose(&mut e, "weavecode", "weavecode");
    assert!(type_keys(&mut e, "weav").contains(&"weavecode".to_string()));
    choose(&mut e, "hello", "hello");
    assert!(e.snapshot().candidates.iter().any(|c| c.text == "world"));
    // Learn a pair through explicit word commits, without inserting separators into either commit.
    e.drop_predictions();
    for c in "weavecode".chars() {
        e.input_char(c);
    }
    e.select(0);
    assert_eq!(e.snapshot().commit, "weavecode");
    choose(&mut e, "hello", "hello");
    assert_eq!(e.snapshot().candidates[0].text, "weavecode");
    let mut other = Engine::new(&paths);
    other.set_schema(Schema::English);
    assert_eq!(type_keys(&mut other, "hel")[1], "helmet");
    assert!(type_keys(&mut other, "weav").contains(&"weavecode".to_string()));
    other.set_learning(false);
    assert!(!type_keys(&mut other, "weav").contains(&"weavecode".to_string()));
    drop(other);
    drop(e);
    std::fs::remove_dir_all(dir).unwrap();
}
