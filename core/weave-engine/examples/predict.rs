//! 联想试跑：`cargo run --release --example predict -- 今天 我们 中国 谢谢`
//! Try next-word prediction on a few contexts and time it.

use std::path::Path;
use std::time::Instant;

use weave_dict::blob::Source;
use weave_dict::gram::Gram;
use weave_dict::lexicon::Lexicon;
use weave_engine::predict::{predict, Readings};
use weave_engine::userdict::UserDict;

fn main() {
    let data = Path::new("../../data/build");
    let lex = Lexicon::open_source(&Source::file(data.join("pinyin.wvz"))).expect("lexicon");
    let follow = weave_dict::follow::Follow::open_source(&Source::file(data.join("follow.wvz"))).ok();
    let gram = Gram::open_source(&Source::file(data.join("grammar.wvz"))).ok();
    println!("gram lens: 2={} 3={} 4={}", gram.as_ref().map_or(0, |g| g.count(2)), gram.as_ref().map_or(0, |g| g.count(3)), gram.as_ref().map_or(0, |g| g.count(4)));
    let t = Instant::now();
    let r = Readings::build(&lex);
    println!("readings: {} chars in {:?}", r.len(), t.elapsed());
    let user = UserDict::in_memory();
    // --eval：整句评测集里，在每个位置用前文预测，下一段文字以某个联想开头算命中（相对比较用）。
    // --eval: at each position of the sentences, predict from the prefix; a hit when the text continues with one.
    if std::env::args().any(|a| a == "--eval") {
        let text = std::fs::read_to_string("../../data/eval/sentences.tsv").expect("eval set");
        let (mut n, mut h1, mut h8, mut chars_saved, mut wh) = (0usize, 0usize, 0usize, 0usize, 0usize);
        let t = Instant::now();
        for line in text.lines().take(400) {
            let s: Vec<char> = line.split('\t').next().unwrap_or("").chars().collect();
            // 按词库最长匹配切词，只在词边界处预测。 Longest-match segmentation; predict only at word boundaries.
            let mut bounds = Vec::new();
            let mut i = 0;
            while i < s.len() {
                let mut l = (s.len() - i).min(4);
                while l > 1 && r.word_cost(&lex, &s[i..i + l].iter().collect::<String>()).is_none() {
                    l -= 1;
                }
                i += l;
                bounds.push(i);
            }
            for w in bounds.windows(2) {
                let (i, j) = (w[0], w[1]);
                let ctx: String = s[..i].iter().collect();
                let next: String = s[i..j].iter().collect();
                let rest = next.clone();
                let p = predict(&ctx, None, &lex, &r, follow.as_ref(), gram.as_ref(), &user, 8);
                n += 1;
                let rest_all: String = s[i..].iter().collect();
                if p.iter().any(|p| p.text.chars().count() > 1 && rest_all.starts_with(&p.text)) {
                    wh += 1;
                }
                if let Some(k) = p.iter().position(|p| rest_all.starts_with(&p.text)) {
                    h8 += 1;
                    if k == 0 {
                        h1 += 1;
                    }
                    chars_saved += p[k].text.chars().count();
                }
            }
        }
        println!("word-hit@8={:.1}%", 100.0 * wh as f64 / n as f64);
        println!("positions={n} hit@1={:.1}% hit@8={:.1}% chars/hit={:.2} saved/pos={:.3} | {:.2} ms/prediction",
            100.0 * h1 as f64 / n as f64, 100.0 * h8 as f64 / n as f64, chars_saved as f64 / h8.max(1) as f64,
            chars_saved as f64 / n as f64,
            t.elapsed().as_secs_f64() * 1000.0 / n as f64);
        return;
    }
    for ctx in std::env::args().skip(1).filter(|a| !a.starts_with("--")) {
        let t = Instant::now();
        let p = predict(&ctx, Some(&ctx), &lex, &r, follow.as_ref(), gram.as_ref(), &user, 8);
        let dt = t.elapsed();
        let words: Vec<String> = p.iter().map(|p| p.text.clone()).collect();
        println!("{ctx} → {} ({dt:?})", words.join(" "));
    }
}
