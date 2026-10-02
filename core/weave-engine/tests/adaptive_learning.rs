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

#[test]
fn complete_ken_never_splits_into_ke_and_an_initial() {
    let mut b = Builder::new(Kind::Pinyin);
    for (py, text, cost) in [
        ("ken", "肯", 18000),
        ("ken", "啃", 21000),
        ("ke neng", "可能", 100),
    ] {
        b.insert(&syllable::parse_seq(py).unwrap(), text, cost);
    }
    let mut e = Engine::with_lexicons(
        Some(weave_dict::lexicon::Lexicon::from_bytes(b.build()).unwrap()),
        None,
        None,
    );
    for c in "ken".chars() {
        e.input_char(c);
    }
    let s = e.snapshot();
    assert_eq!(s.preedit, "ken");
    assert_eq!(s.candidates[0].text, "肯");
    e.clear();
    for c in "ke'n".chars() {e.input_char(c);}
    let s=e.snapshot();
    assert_eq!(s.preedit,"ke'n");
    assert_eq!(s.candidates[0].text,"可能");
}

#[test]
fn pin_and_profile_merge_are_persistent_idempotent_and_keep_deletions() {
    let dir = directory("profile");
    let paths = fixture(&dir);
    let mut e = Engine::new(&paths);
    e.import_user_words("是\tshi\t10000\n");
    let c = type_keys(&mut e, "shi");
    let i = c.iter().position(|s| s == "嗜").unwrap();
    assert_eq!(
        e.features(&serde_json::json!({"op":"policy","index":i,"text":"嗜","mode":"pin"}))["ok"],
        true
    );
    assert_eq!(type_keys(&mut e, "shi")[0], "嗜");
    let bundle = e.features(&serde_json::json!({"op":"exportPersonal"}))["data"]
        .as_str()
        .unwrap()
        .to_string();
    let otherdir = directory("profile-peer");
    let otherpaths = fixture(&otherdir);
    let mut other = Engine::new(&otherpaths);
    for _ in 0..3 {
        assert_eq!(
            other.features(&serde_json::json!({"op":"importPersonal","data":bundle}))["ok"],
            true
        );
    }
    assert_eq!(type_keys(&mut other, "shi")[0], "嗜");
    assert_eq!(other.user_words("嗜", 0, 8)[0].count, 1);
    assert_eq!(
        other.features(&serde_json::json!({"op":"policy","index":0,"mode":""}))["ok"],
        true
    );
    other.delete_user_word("shi", "嗜");
    other.features(&serde_json::json!({"op":"exportPersonal"}));
    other.features(&serde_json::json!({"op":"importPersonal","data":bundle}));
    assert!(other.user_words("嗜", 0, 8).is_empty());
    drop(other);
    drop(e);
    let _ = std::fs::remove_dir_all(dir);
    let _ = std::fs::remove_dir_all(otherdir);
}

#[test]
fn english_typo_suggestions_preserve_literal_and_reconvert_finds_readings() {
    let mut b = Builder::new(Kind::Letters);
    for (word, cost) in [("receive", 2000), ("review", 3000)] {
        b.insert(&weave_engine::table::code_key(word).unwrap(), word, cost);
    }
    let mut e = Engine::with_lexicons(
        None,
        None,
        Some(weave_dict::lexicon::Lexicon::from_bytes(b.build()).unwrap()),
    );
    e.set_schema(Schema::English);
    let c = type_keys(&mut e, "recieve");
    assert_eq!(c[0], "recieve");
    assert!(c.contains(&"receive".to_string()), "{c:?}");
    let dir = directory("reconvert");
    let mut e = Engine::new(&fixture(&dir));
    assert!(e.reconvert("时"));
    assert!(e.candidates(0, 100).iter().any(|c| c.text == "是"));
    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn chinese_and_latin_fragments_share_one_lattice_and_keep_capitals() {
    let mut p = Builder::new(Kind::Pinyin);
    for (py, text) in [("jin tian", "今天"), ("zhe ge", "这个")] {
        p.insert(&syllable::parse_seq(py).unwrap(), text, 8000);
    }
    let mut en = Builder::new(Kind::Letters);
    en.insert(
        &weave_engine::table::code_key("review").unwrap(),
        "review",
        8000,
    );
    let mut e = Engine::with_lexicons(
        Some(weave_dict::lexicon::Lexicon::from_bytes(p.build()).unwrap()),
        None,
        Some(weave_dict::lexicon::Lexicon::from_bytes(en.build()).unwrap()),
    );
    for c in "jintianreviewzhegePR".chars() {
        assert!(e.input_char(c));
    }
    let s = e.snapshot();
    assert_eq!(
        s.candidates[0].text, "今天review这个PR",
        "{:?}",
        s.candidates
    );
    e.select(0);
    assert_eq!(e.snapshot().commit, "今天review这个PR");
}

#[test]
fn shortcuts_expand_date_and_handwriting_groups_keep_multiple_characters() {
    let dir = directory("snippets");
    let paths = fixture(&dir);
    let mut e = Engine::new(&paths);
    assert_eq!(
        e.features(&serde_json::json!({"op":"setSnippet","code":"dz","text":"地址；日期 {date}"}))
            ["ok"],
        true
    );
    let c = type_keys(&mut e, "dz");
    assert!(c[0].starts_with("地址；日期 "));
    assert!(!c[0].contains("{date}"));
    let strokes = vec![
        vec![(0., 0.3), (0.3, 0.3)],
        vec![(0.15, 0.1), (0.15, 0.5)],
        vec![(0.7, 0.3), (1., 0.3)],
        vec![(0.85, 0.1), (0.85, 0.5)],
    ];
    assert_eq!(
        weave_dict::handnet::HandModels::line_groups(&strokes).len(),
        2
    );
    drop(e);
    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn learned_hand_shapes_survive_restart_and_spaced_word_candidates_are_words() {
    let dir = directory("hand-memory");
    let cross = vec![
        vec![(100., 200.), (300., 200.)],
        vec![(200., 100.), (200., 300.)],
    ];
    let soil = vec![
        vec![(120., 140.), (280., 140.)],
        vec![(200., 100.), (200., 300.)],
        vec![(100., 300.), (300., 300.)],
    ];
    let mut b = weave_dict::hand::Builder::default();
    b.push('十', 0, &cross);
    b.push('土', 0, &soil);
    let file = dir.join("hand.wvh");
    std::fs::write(&file, b.build()).unwrap();
    let paths = Paths {
        hand: Some(Source::file(file)),
        user_dir: Some(dir.join("user")),
        ..Default::default()
    };
    let mut e = Engine::new(&paths);
    e.set_schema(Schema::Hand);
    assert!(e.hand_input(cross.clone()));
    let i = e
        .snapshot()
        .candidates
        .iter()
        .position(|c| c.text == "土")
        .unwrap();
    assert!(i > 0);
    e.select(i);
    e.snapshot();
    drop(e);
    let mut e = Engine::new(&paths);
    e.set_schema(Schema::Hand);
    e.hand_input(cross.clone());
    assert_eq!(e.snapshot().candidates[0].text, "土");
    e.features(&serde_json::json!({"op":"clearHand"}));
    e.features(&serde_json::json!({"op":"setHandLine","on":true}));
    let mut line = cross.clone();
    line.extend(
        cross
            .iter()
            .map(|stroke| stroke.iter().map(|(x, y)| (x + 350., *y)).collect()),
    );
    e.hand_input(line);
    assert_eq!(e.snapshot().candidates[0].text, "十十");
    e.select(0);
    assert_eq!(e.snapshot().commit, "十十");
    drop(e);
    let _ = std::fs::remove_dir_all(dir);
}

#[test]
fn concurrent_peers_converge_on_one_pin_and_one_recent_choice() {
    let a_dir=directory("conflict-a");let b_dir=directory("conflict-b");
    let mut a=Engine::new(&fixture(&a_dir));let mut b=Engine::new(&fixture(&b_dir));
    a.options.prediction=false;b.options.prediction=false;
    for (e,word) in [(&mut a,"嗜"),(&mut b,"时")] {
        for _ in 0..5 {choose(e,"shi",word);}
        let c=type_keys(e,"shi");let i=c.iter().position(|s|s==word).unwrap();
        assert_eq!(e.features(&serde_json::json!({"op":"policy","index":i,"mode":"pin"}))["ok"],true);
        e.clear();
    }
    let export=|e:&mut Engine|e.features(&serde_json::json!({"op":"exportPersonal"}))["data"].as_str().unwrap().to_string();
    let ab=export(&mut a);let bb=export(&mut b);
    assert_eq!(a.features(&serde_json::json!({"op":"importPersonal","data":bb}))["ok"],true);
    assert_eq!(b.features(&serde_json::json!({"op":"importPersonal","data":ab}))["ok"],true);
    let first=type_keys(&mut a,"shi")[0].clone();
    assert_eq!(type_keys(&mut b,"shi")[0],first);
    for e in [&mut a,&mut b] {
        e.features(&serde_json::json!({"op":"policy","index":0,"mode":""}));
        assert_eq!(type_keys(e,"shi")[0],first,"automatic choices must also converge");
        e.clear();
    }
    let words=b.export_user_words();
    for _ in 0..3 {assert_eq!(b.features(&serde_json::json!({"op":"importPersonal","data":ab}))["ok"],true);}
    assert_eq!(b.export_user_words(),words,"reimport must not inflate word counts");
    drop(a);drop(b);let _=std::fs::remove_dir_all(a_dir);let _=std::fs::remove_dir_all(b_dir);
}

#[test]
fn malformed_profile_codes_are_rejected_before_any_merge() {
    let mut e=Engine::with_lexicons(None,None,None);
    for key in ["word:[\"english\",[65535],\"hello\"]","word:[\"unknown\",[1],\"词\"]","choice:[\"pinyin\",[65535]]","choice:[\"english\",[0]]"] {
        let data=serde_json::json!({"format":"weavetext-personal-1","personal":{"device":"peer","clock":1,"records":{key:{"value":"","device":"peer","clock":1}}}}).to_string();
        assert_eq!(e.features(&serde_json::json!({"op":"importPersonal","data":data}))["ok"],false,"{key}");
    }
}

#[test]
fn english_homographs_of_full_chinese_readings_do_not_steal_chinese() {
    let mut p=Builder::new(Kind::Pinyin);
    for (py,text) in [("chi le","吃了"),("wo men","我们"),("ni le","你了")] {p.insert(&syllable::parse_seq(py).unwrap(),text,18000);}
    let mut en=Builder::new(Kind::Letters);
    for word in ["chile","women","nile"] {en.insert(&weave_engine::table::code_key(word).unwrap(),word,1000);}
    let mut e=Engine::with_lexicons(Some(weave_dict::lexicon::Lexicon::from_bytes(p.build()).unwrap()),None,Some(weave_dict::lexicon::Lexicon::from_bytes(en.build()).unwrap()));
    for (input,text) in [("chile","吃了"),("women","我们"),("nile","你了")] {
        assert_eq!(type_keys(&mut e,input)[0],text);
    }
}
