//! Independent fixtures for double-pinyin Latin spans, literal input and learning ownership.
use weave_dict::{
    lexicon::{Builder, Kind, Lexicon},
    syllable,
};
use weave_engine::{
    shuangpin::{self, SchemeId},
    table, Engine, Schema,
};

fn fixture(english: bool) -> Engine {
    let mut p = Builder::new(Kind::Pinyin);
    for (py, text) in [
        ("ni", "你"),
        ("hao", "好"),
        ("ni hao", "你好"),
        ("guo", "国"),
        ("ru", "如"),
        ("shi", "是"),
        ("xing", "行"),
    ] {
        p.insert(
            &syllable::parse_seq(py).unwrap(),
            text,
            if py == "guo" { 18000 } else { 100 },
        );
    }
    let mut en = Builder::new(Kind::Letters);
    for word in ["GitHub", "WiFi", "review", "hello", "rust", "go", "don't"] {
        en.insert(
            &table::code_key(&word.replace('\'', "")).unwrap(),
            word,
            1000,
        );
    }
    let mut e = Engine::with_lexicons(
        Some(Lexicon::from_bytes(p.build()).unwrap()),
        None,
        english.then(|| Lexicon::from_bytes(en.build()).unwrap()),
    );
    e.options.emoji = false;
    e.options.prediction = false;
    e
}

fn code(scheme: SchemeId, pinyin: &str) -> String {
    syllable::parse_seq(pinyin)
        .unwrap()
        .into_iter()
        .flat_map(|sy| shuangpin::table(scheme).encode(sy).unwrap())
        .map(char::from)
        .collect()
}

fn type_keys(e: &mut Engine, input: &str) -> Vec<String> {
    e.clear();
    e.set_context(None);
    for c in input.chars() {
        assert!(e.input_char(c), "unhandled {c} in {input}");
    }
    e.candidates(0, 12).into_iter().map(|c| c.text).collect()
}

fn choose(e: &mut Engine, input: &str, expected: &str) {
    let candidates = type_keys(e, input);
    let i = candidates
        .iter()
        .position(|s| s == expected)
        .unwrap_or_else(|| panic!("{input} must offer {expected}: {candidates:?}"));
    assert!(e.select(i));
    let snapshot = e.snapshot();
    assert_eq!(snapshot.commit, expected);
    assert!(!snapshot.composing);
}

#[test]
fn embedded_words_resume_the_scheme_after_even_and_odd_latin_lengths() {
    for scheme in SchemeId::ALL {
        let mut e = fixture(true);
        e.set_schema(Schema::Shuangpin(scheme));
        for word in ["GitHub", "WiFi", "review", "hello", "API"] {
            let input = format!("{}{word}{}", code(scheme, "ni"), code(scheme, "hao"));
            let expected = format!("你{word}好");
            let candidates = type_keys(&mut e, &input);
            eprintln!("{scheme:?} {input}: {candidates:?}");
            assert!(
                candidates.iter().any(|s| s == &expected),
                "{expected} missing"
            );
            assert!(
                e.snapshot().preedit.contains(word),
                "preedit must preserve Latin case"
            );
            choose(&mut e, &input, &expected);
        }
    }
}

#[test]
fn uppercase_enter_and_backspace_preserve_the_original_keys() {
    for scheme in SchemeId::ALL {
        let mut e = fixture(true);
        e.set_schema(Schema::Shuangpin(scheme));
        for input in ["NiGitHubHC", "RuSt", "API", "WiFi"] {
            type_keys(&mut e, input);
            assert!(e.backspace());
            assert!(e.input_char(input.chars().last().unwrap()));
            e.commit_raw();
            assert_eq!(e.snapshot().commit, input, "{scheme:?}");
        }
    }
}

#[test]
fn technical_words_are_literal_candidates_without_an_english_dictionary() {
    for schema in [Schema::Pinyin, Schema::Shuangpin(SchemeId::Xiaohe)] {
        let mut e = fixture(false);
        e.set_schema(schema);
        for word in ["rust", "Rust", "GitHub", "OpenGL", "TypeScript", "zzWidget"] {
            choose(&mut e, word, word);
        }
    }
}

#[test]
fn complete_chinese_pairs_lead_even_with_cheap_english_and_capitals() {
    for scheme in SchemeId::ALL {
        let mut e = fixture(true);
        e.set_schema(Schema::Shuangpin(scheme));
        for (py, expected) in [("ni hao", "你好"), ("guo", "国")] {
            for input in [code(scheme, py), code(scheme, py).to_ascii_uppercase()] {
                let candidates = type_keys(&mut e, &input);
                assert_eq!(candidates[0], expected, "{scheme:?}: {candidates:?}");
                assert!(candidates.iter().any(|s| s == &input));
            }
        }
        let input = format!("{};", code(scheme, "ni"));
        type_keys(&mut e, &input);
        e.commit_raw();
        assert_eq!(e.snapshot().commit, input);
        if matches!(scheme, SchemeId::Microsoft | SchemeId::Sogou) {
            assert_eq!(type_keys(&mut e, "x;")[0], "行");
        }
    }
}

#[test]
fn legal_chinese_homographs_lead_but_embedded_english_remains_reachable() {
    let mut p = Builder::new(Kind::Pinyin);
    for (py, text, cost) in [
        ("ni", "你", 100),
        ("hao", "好", 100),
        ("ni re zhi ei hao", "你热值诶好", 30000),
        ("duo nve", "多虐", 30000),
    ] {
        p.insert(&syllable::parse_seq(py).unwrap(), text, cost);
    }
    let mut en = Builder::new(Kind::Letters);
    for (key, text) in [("review", "review"), ("dont", "don't")] {
        en.insert(&table::code_key(key).unwrap(), text, 1000);
    }
    let mut e = Engine::with_lexicons(
        Some(Lexicon::from_bytes(p.build()).unwrap()),
        None,
        Some(Lexicon::from_bytes(en.build()).unwrap()),
    );
    e.options.emoji = false;
    e.options.prediction = false;
    e.set_schema(Schema::Shuangpin(SchemeId::Xiaohe));
    let candidates = type_keys(&mut e, "nireviewhc");
    assert_eq!(candidates[0], "你热值诶好");
    assert!(candidates.iter().any(|s| s == "你review好"));
    let candidates = type_keys(&mut e, "dont");
    assert_eq!(candidates[0], "多虐");
    assert!(candidates.iter().any(|s| s == "don't"));
    assert!(candidates.iter().any(|s| s == "dont"));
}

#[test]
fn dictionary_variants_do_not_replace_original_english_on_automatic_commit() {
    for schema in [Schema::Pinyin, Schema::Shuangpin(SchemeId::Xiaohe)] {
        let mut e = fixture(true);
        e.set_schema(schema);
        let candidates = type_keys(&mut e, "dont");
        let literal = candidates.iter().position(|s| s == "dont").unwrap();
        let suggestion = candidates.iter().position(|s| s == "don't").unwrap();
        assert!(literal < suggestion, "{schema:?}: {candidates:?}");
        e.commit_first();
        assert_eq!(e.snapshot().commit, "dont");
    }
}

#[test]
fn full_pinyin_explicit_english_case_and_raw_enter_are_preserved() {
    let mut e = fixture(true);
    for input in ["RuSt", "GitHub", "WiFi", "API"] {
        let candidates = type_keys(&mut e, input);
        assert_eq!(
            candidates[0], input,
            "explicit English case: {candidates:?}"
        );
        e.commit_first();
        assert_eq!(e.snapshot().commit, input);
        type_keys(&mut e, input);
        e.commit_raw();
        assert_eq!(e.snapshot().commit, input);
    }
    for input in ["NiHao", "NIHAO", "Review"] {
        type_keys(&mut e, input);
        e.commit_raw();
        assert_eq!(e.snapshot().commit, input);
    }
}

#[test]
fn declined_digits_and_symbols_leave_the_composition_untouched() {
    for schema in [Schema::Pinyin, Schema::Shuangpin(SchemeId::Xiaohe)] {
        let mut e = fixture(false);
        e.set_schema(schema);
        for (letters, symbol) in [
            ("GPT", '4'),
            ("foo", '_'),
            ("GitHub", '.'),
            ("mail", '@'),
            ("HTTPS", ':'),
            ("API", '/'),
            ("e", '-'),
        ] {
            type_keys(&mut e, letters);
            let before = e.snapshot();
            assert!(!e.input_char(symbol), "{symbol} belongs to the host");
            let after = e.snapshot();
            assert_eq!(after.preedit, before.preedit);
            assert!(after.commit.is_empty());
            e.commit_raw();
            assert_eq!(e.snapshot().commit, letters);
        }
    }
}

#[test]
fn mixed_learning_stays_in_its_language_and_undo_and_privacy_work() {
    let mut e = fixture(true);
    e.set_schema(Schema::Shuangpin(SchemeId::Xiaohe));
    let before = e.export_user_words();
    choose(&mut e, "niGitHubhc", "你GitHub好");
    let learned = e.export_user_words();
    assert!(learned.contains("你\tni\t1"));
    assert!(learned.contains("好\thao\t1"));
    assert!(!learned.contains("GitHub"));
    assert!(!learned.contains("github"));
    assert!(!e.backspace());
    assert_eq!(e.export_user_words(), before);
    e.set_schema(Schema::English);
    assert!(
        !type_keys(&mut e, "githu").iter().any(|s| s == "github"),
        "undo must also restore English learning from the mixed sentence"
    );
    e.set_schema(Schema::Shuangpin(SchemeId::Xiaohe));
    e.set_learning(false);
    choose(&mut e, "niGitHubhc", "你GitHub好");
    choose(&mut e, "zzWidget", "zzWidget");
    assert_eq!(e.export_user_words(), before);
    e.set_learning(true);
    e.set_schema(Schema::English);
    assert!(!type_keys(&mut e, "zzW").iter().any(|s| s == "zzwidget"));
    e.set_schema(Schema::Shuangpin(SchemeId::Xiaohe));
    choose(&mut e, "zzWidget", "zzWidget");
    assert_eq!(e.export_user_words(), before);
    e.set_schema(Schema::English);
    assert!(type_keys(&mut e, "zzw").iter().any(|s| s == "zzwidget"));
}

#[test]
fn host_can_reconstruct_digits_symbols_and_case_without_lost_keys() {
    for schema in [Schema::Pinyin, Schema::Shuangpin(SchemeId::Xiaohe)] {
        let mut e = fixture(false);
        e.set_schema(schema);
        e.options.calculator = false;
        for input in [
            "foo_bar",
            "GPT4o",
            "v1.2.3",
            "User@GitHub.com",
            "HTTPS://GitHub.com/a",
        ] {
            e.clear();
            let mut output = String::new();
            for c in input.chars() {
                if !e.input_char(c) {
                    e.commit_raw();
                    output.push_str(&e.snapshot().commit);
                    output.push(c);
                } else {
                    assert!(e.snapshot().commit.is_empty());
                }
            }
            e.commit_raw();
            output.push_str(&e.snapshot().commit);
            assert_eq!(output, input, "{schema:?}");
        }
    }
}

#[test]
fn partial_chinese_selection_then_enter_keeps_the_latin_suffix() {
    let mut e = fixture(true);
    e.set_schema(Schema::Shuangpin(SchemeId::Xiaohe));
    let candidates = type_keys(&mut e, "niGitHubhc");
    let i = candidates.iter().position(|s| s == "你").unwrap();
    assert!(e.select(i));
    e.commit_raw();
    assert_eq!(e.snapshot().commit, "你GitHubhc");
}

#[test]
#[ignore = "requires built dictionaries; set WEAVE_TEST_DATA_DIR and run with --include-ignored"]
fn real_dictionary_embedded_english_and_original_technical_words() {
    use std::path::PathBuf;
    use weave_engine::session::paths_in;
    let data = std::env::var_os("WEAVE_TEST_DATA_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../data/build"));
    for name in ["pinyin.wvz", "english.wvz"] {
        assert!(
            data.join(name).exists(),
            "missing real resource {name}: {}",
            data.display()
        );
    }
    let mut paths = paths_in(&data, &std::env::temp_dir());
    paths.user_dir = None;
    let mut e = Engine::new(&paths);
    let english = Lexicon::open(&data.join("english.wvz")).unwrap();
    for word in ["github", "wifi", "review", "hello"] {
        eprintln!(
            "real English {word}: {:?}",
            table::lookup(&english, word, 0)
        );
    }
    e.options.emoji = false;
    e.options.prediction = false;
    for scheme in SchemeId::ALL {
        e.set_schema(Schema::Shuangpin(scheme));
        assert_eq!(type_keys(&mut e, &code(scheme, "ni hao"))[0], "你好");
        for word in ["GitHub", "WiFi", "API", "review", "hello"] {
            let input = format!("{}{word}{}", code(scheme, "ni"), code(scheme, "hao"));
            let candidates = type_keys(&mut e, &input);
            eprintln!("real {scheme:?} {input}: {candidates:?}");
            assert!(candidates.iter().any(|s| s == &format!("你{word}好")));
            e.commit_raw();
            assert_eq!(e.snapshot().commit, input);
        }
        for word in ["Rust", "GitHub", "TypeScript", "zzWidget"] {
            choose(&mut e, word, word);
        }
    }
}
