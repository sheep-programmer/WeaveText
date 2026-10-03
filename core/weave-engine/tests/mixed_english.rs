//! 中文方案里打英文：实际词库下，全拼和双拼都给出英文词，冷门词（wifi）和带撇号的词（don't）也在候选里。
//! English inside Chinese schemas on the real dictionary: full pinyin and shuangpin both offer the word, including
//! rare ones (wifi) and apostrophe forms (don't). Skipped when the built dictionary isn't there.
use std::path::PathBuf;
use weave_engine::session::{paths_in, Engine, Schema};

fn engine(name: &str) -> Option<Engine> {
    let data = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../data/build");
    if !data.join("english.wvz").exists() {
        return None;
    }
    let user = std::env::temp_dir().join(format!("weave-mixed-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&user);
    std::fs::create_dir_all(&user).unwrap();
    Some(Engine::new(&paths_in(&data, &user)))
}

fn type_keys(e: &mut Engine, keys: &str) -> Vec<String> {
    e.clear();
    for c in keys.chars() {
        e.input_char(c);
    }
    e.candidates(0, 12).into_iter().map(|c| c.text).collect()
}

#[test]
fn full_pinyin_offers_rare_and_apostrophe_words() {
    let Some(mut e) = engine("pinyin") else { return };
    for (keys, word) in [("school", "school"), ("School", "School"), ("wifi", "WiFi"), ("dont", "don't"), ("email", "email")] {
        let c = type_keys(&mut e, keys);
        assert!(c.iter().take(4).any(|t| t == word), "{keys} → {word}: {c:?}");
    }
    // 读得通的拼音照旧以中文为先。 Readable pinyin still leads with Chinese.
    assert_eq!(type_keys(&mut e, "women")[0], "我们");
}

#[test]
fn shuangpin_offers_english_words() {
    let Some(mut e) = engine("shuangpin") else { return };
    e.set_schema(Schema::from_key("shuangpin:xiaohe").unwrap());
    for (keys, word) in [("school", "school"), ("teacher", "teacher"), ("hello", "hello"), ("wifi", "WiFi")] {
        let c = type_keys(&mut e, keys);
        assert!(c.iter().take(4).any(|t| t == word), "{keys} → {word}: {c:?}");
    }
    // 小鹤 ni + hc = 你好，不该被英文挤掉。 Xiaohe ni + hc reads 你好 and keeps the top spot.
    assert_eq!(type_keys(&mut e, "nihc")[0], "你好");
}
