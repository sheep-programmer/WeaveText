//! 热词仓库发布流程（Python 签名）产出的样例文件，用内置公钥验签并编译。
//! A sample produced by the hot-words publishing workflow (signed in Python), verified with the built-in key.

use weave_engine::cloud::{compile, HOTWORDS_KEY};

#[test]
fn published_sample_verifies_with_the_pinned_key() {
    let tsv = include_bytes!("fixtures/hotwords.tsv");
    let sig = include_str!("fixtures/hotwords.tsv.sig");
    let c = compile(tsv, sig, &HOTWORDS_KEY, 20_723).expect("signature must verify");
    assert_eq!(c.words, 2);
    assert!(!c.version.is_empty());
}
