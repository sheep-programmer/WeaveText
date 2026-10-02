//! Verify a published cloud dictionary using the pinned client key, then inspect candidate metadata.
//! Usage: cloudcheck DATA_DIR HOTWORDS_TSV SIGNATURE_FILE
use std::{
    path::PathBuf,
    time::{SystemTime, UNIX_EPOCH},
};
use weave_engine::{
    cloud,
    session::{paths_in, Engine},
};
fn main() {
    let args: Vec<_> = std::env::args().skip(1).collect();
    assert_eq!(args.len(), 3, "DATA_DIR TSV SIG");
    let bytes = std::fs::read(&args[1]).unwrap();
    let sig = std::fs::read_to_string(&args[2]).unwrap();
    let today = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_secs() as i64
        / 86400;
    let verified =
        cloud::compile(&bytes, &sig, &cloud::HOTWORDS_KEY, today).expect("publisher signature");
    println!(
        "verified version={} words={} expired={}",
        verified.version, verified.words, verified.expired
    );
    let user = std::env::temp_dir().join(format!("weave-cloudcheck-{}", std::process::id()));
    let mut e = Engine::new(&paths_in(&PathBuf::from(&args[0]), &user));
    assert_eq!(e.load_hotwords(&bytes, &sig).unwrap(), verified.words);
    for (input, word) in [
        ("shijianfuzadu", "时间复杂度"),
        ("zhengzebiaodashi", "正则表达式"),
        ("shangxiawengongcheng", "上下文工程"),
    ] {
        e.clear();
        for c in input.chars() {
            e.input_char(c);
        }
        let all = e.candidates(0, 800);
        let c = all
            .iter()
            .find(|c| c.text == word)
            .expect("published word is a candidate");
        println!("{input} -> {} cloud={} learned={}", c.text, c.cloud, c.user);
        if word == "时间复杂度" {
            assert!(c.cloud);
            assert!(!c.user);
        }
    }
    drop(e);
    let _ = std::fs::remove_dir_all(user);
}
