//! 整句评测：`cargo run --release --example eval -- --data ../../data/build --eval ../../data/eval/sentences.tsv`
//! 可选：`--gram <file.wvg>` `--lambda 1.0` `--baseline 12` `--schema pinyin|t9|xiaohe` `--show 20`
//! Sentence benchmark; prints top-1 / top-3 sentence accuracy and character accuracy.

use std::path::PathBuf;
use std::time::Instant;

use weave_dict::blob::Source;
use weave_dict::syllable;
use weave_engine::session::{Engine, Paths, Schema};
use weave_engine::shuangpin::{self, SchemeId};
use weave_engine::t9;

fn arg(args: &[String], name: &str) -> Option<String> {
    args.iter()
        .position(|a| a == name)
        .and_then(|i| args.get(i + 1).cloned())
}

fn levenshtein(a: &[char], b: &[char]) -> usize {
    let mut prev: Vec<usize> = (0..=b.len()).collect();
    for (i, ca) in a.iter().enumerate() {
        let mut cur = vec![i + 1; b.len() + 1];
        for (j, cb) in b.iter().enumerate() {
            cur[j + 1] = (prev[j] + (ca != cb) as usize)
                .min(prev[j + 1] + 1)
                .min(cur[j] + 1);
        }
        prev = cur;
    }
    prev[b.len()]
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let data = PathBuf::from(arg(&args, "--data").unwrap_or("../../data/build".into()));
    let eval = arg(&args, "--eval").unwrap_or("../../data/eval/sentences.tsv".into());
    let schema = arg(&args, "--schema").unwrap_or("pinyin".into());
    let limit: usize = arg(&args, "--limit")
        .and_then(|v| v.parse().ok())
        .unwrap_or(usize::MAX);
    let show: usize = arg(&args, "--show")
        .and_then(|v| v.parse().ok())
        .unwrap_or(0);
    let opt = |n: &str| {
        let p = data.join(n);
        let z = data.join("pinyin.wvz");
        if p.exists() {
            Some(Source::file(p))
        } else {
            z.exists().then(|| Source::file(z))
        }
    };
    if let Some(mb) = arg(&args, "--cache-mb").and_then(|v| v.parse::<usize>().ok()) {
        weave_dict::blob::set_cache_budget(mb << 20);
    }
    let paths = Paths {
        pinyin_lexicon: opt("pinyin.wvl"),
        gram_model: arg(&args, "--gram").map(|g| Source::file(PathBuf::from(g))),
        ..Default::default()
    };
    let mut e = Engine::new(&paths);
    e.set_learning(false);
    e.options.emoji = false;
    if let Some(l) = arg(&args, "--lambda").and_then(|v| v.parse().ok()) {
        e.options.lm_weight = l;
    }
    if let Some(b) = arg(&args, "--baseline").and_then(|v| v.parse().ok()) {
        e.options.lm_baseline = b;
    }
    e.set_schema(match schema.as_str() {
        "t9" => Schema::Keypad(t9::Grouping::Nine),
        "t14" => Schema::Keypad(t9::Grouping::Fourteen),
        "xiaohe" => Schema::Shuangpin(SchemeId::Xiaohe),
        _ => Schema::Pinyin,
    });
    let text = std::fs::read_to_string(&eval).expect("read eval set");
    let (mut n, mut top1, mut top3, mut chars, mut char_err) =
        (0usize, 0usize, 0usize, 0usize, 0usize);
    let mut by_len: [(usize, usize); 4] = [(0, 0); 4];
    let mut shown = 0;
    let started = Instant::now();
    for line in text.lines().take(limit) {
        let Some((sentence, pinyin)) = line.split_once('\t') else {
            continue;
        };
        let Some(ids) = syllable::parse_seq(pinyin) else {
            continue;
        };
        let keys: String = match schema.as_str() {
            "t9" => ids
                .iter()
                .flat_map(|&s| syllable::spelling(s).bytes())
                .map(|c| t9::letter_digit(c) as char)
                .collect(),
            "t14" => ids
                .iter()
                .flat_map(|&s| syllable::spelling(s).bytes())
                .map(|c| t9::Grouping::Fourteen.code(c) as char)
                .collect(),
            "xiaohe" => ids
                .iter()
                .flat_map(|&s| {
                    shuangpin::table(SchemeId::Xiaohe)
                        .encode(s)
                        .unwrap_or(*b"??")
                })
                .map(|c| c as char)
                .collect(),
            _ => ids.iter().map(|&s| syllable::spelling(s)).collect(),
        };
        e.clear();
        for c in keys.chars() {
            e.input_char(c);
        }
        let snap = e.snapshot();
        let got = snap
            .candidates
            .first()
            .map(|c| c.text.clone())
            .unwrap_or_default();
        let target: Vec<char> = sentence.chars().collect();
        n += 1;
        let ok = got == sentence;
        top1 += ok as usize;
        top3 += snap.candidates.iter().take(3).any(|c| c.text == sentence) as usize;
        chars += target.len();
        char_err += levenshtein(&got.chars().collect::<Vec<_>>(), &target);
        let bucket = match target.len() {
            0..=4 => 0,
            5..=8 => 1,
            9..=14 => 2,
            _ => 3,
        };
        by_len[bucket].0 += 1;
        by_len[bucket].1 += ok as usize;
        if !ok && shown < show {
            shown += 1;
            println!("  ✗ {sentence}  →  {got}");
        }
    }
    for (name, hits, misses) in e.cache_stats() {
        eprintln!("cache {name}: {hits} hits, {misses} misses");
    }
    let pct = |a: usize, b: usize| 100.0 * a as f64 / b.max(1) as f64;
    println!(
        "schema={schema} n={n} top1={:.1}% top3={:.1}% char_acc={:.2}% | len≤4 {:.1}% 5-8 {:.1}% 9-14 {:.1}% 15+ {:.1}% | {:.1} ms/sentence",
        pct(top1, n),
        pct(top3, n),
        100.0 - pct(char_err, chars),
        pct(by_len[0].1, by_len[0].0),
        pct(by_len[1].1, by_len[1].0),
        pct(by_len[2].1, by_len[2].0),
        pct(by_len[3].1, by_len[3].0),
        started.elapsed().as_secs_f64() * 1000.0 / n.max(1) as f64,
    );
}
