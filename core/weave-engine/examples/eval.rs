//! 整句评测：`cargo run --release --example eval -- --data ../../data/build --eval ../../data/eval/sentences.tsv`
//! 可选：`--gram <file.wvg>` `--lambda 1.0` `--baseline 12` `--schema pinyin|t9|xiaohe` `--show 20`
//! 触控误差模拟：`--touch-noise 0.2`（高斯标准差，单位键宽）按 26 键几何把每个字母加噪声后判键，
//! `--near` 再把交界处的邻键交给引擎纠正；`--seed 1`。
//! 打错模拟：`--typo swap|drop|extra|sub` 每句在一个随机音节里交换相邻字母 / 漏一个字母 / 多按一个字母 / 按成相邻的键；
//! `--no-autocorrect` 关掉引擎纠错作对照。
//! Sentence benchmark; prints top-1 / top-3 sentence accuracy and character accuracy.
//! Touch simulation: `--touch-noise 0.2` (Gaussian sigma in key widths) jitters every letter on the 26-key
//! geometry before hit-testing; `--near` also passes border neighbours to the engine; `--seed 1`.
//! Typo simulation: `--typo swap|drop|extra|sub` swaps two adjacent letters / drops one / doubles one / hits a neighbouring key inside a random
//! syllable of every sentence; `--no-autocorrect` turns the engine's correction off for comparison.

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

/// 26 键几何（单位：键宽；行距按行高 / 键宽 ≈ 1.56 换算）。 26-key geometry in key widths.
const ROWS: [(&str, f32); 3] = [("qwertyuiop", 0.0), ("asdfghjkl", 0.5), ("zxcvbnm", 1.5)];
const PITCH_Y: f32 = 1.56;
/// 与键盘界面一致的交界带宽度（归一化中心距之差）。 Border band, same as the keyboard UI.
const NEAR_BAND: f32 = 0.45;

fn key_center(c: u8) -> (f32, f32) {
    for (r, (row, off)) in ROWS.iter().enumerate() {
        if let Some(i) = row.bytes().position(|b| b == c) {
            return (off + i as f32 + 0.5, r as f32 + 0.5);
        }
    }
    (5.0, 1.5)
}

/// 触点 (x, 行坐标) → (所按字母, 邻键, 贴近度)；行外的触点归到最近的字母行。
/// Touch (x, row units) → (letter hit, neighbour, closeness); taps beyond the letter rows clamp to them.
fn hit(x: f32, y: f32) -> (u8, Option<(u8, f32)>) {
    let r = (y.floor().max(0.0) as usize).min(2);
    let (row, off) = ROWS[r];
    let i = ((x - off).floor().max(0.0) as usize).min(row.len() - 1);
    let primary = row.as_bytes()[i];
    let d = |c: u8| {
        let (cx, cy) = key_center(c);
        ((x - cx).powi(2) + (y - cy).powi(2)).sqrt()
    };
    let dp = d(primary);
    let (mut best, mut bd) = (0u8, f32::MAX);
    for (row, _) in ROWS {
        for c in row.bytes() {
            if c != primary && d(c) < bd {
                bd = d(c);
                best = c;
            }
        }
    }
    let margin = bd - dp;
    (primary, (margin < NEAR_BAND).then(|| (best, 1.0 - margin.max(0.0) / NEAR_BAND)))
}

struct Rng(u64);
impl Rng {
    fn next(&mut self) -> f32 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        ((self.0 >> 40) as f32 + 0.5) / (1u64 << 24) as f32
    }
    fn gauss(&mut self) -> f32 {
        let (a, b) = (self.next(), self.next());
        (-2.0 * a.ln()).sqrt() * (std::f32::consts::TAU * b).cos()
    }
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
    let mut paths = paths;
    // --packs <dir>：载入目录里全部扩展词库（检查专业词库不拖累日常输入）。 Load every pack in a directory.
    if let Some(dir) = arg(&args, "--packs") {
        for f in std::fs::read_dir(&dir).expect("read packs dir").flatten() {
            let p = f.path();
            if p.extension().is_some_and(|e| e == "wvz") {
                let id = p.file_stem().unwrap().to_string_lossy().into_owned();
                paths.set(&format!("pack.{id}"), Source::file(p));
            }
        }
    }
    let mut e = Engine::new(&paths);
    // --fuzzy z_zh,an_ang,…：打开这些模糊音（名字同设置里的选项）。 Turn on fuzzy pairs by option name.
    if let Some(list) = arg(&args, "--fuzzy") {
        for k in list.split(',').filter(|k| !k.is_empty()) {
            assert!(e.options.set_flag(&format!("fuzzy.{k}"), true), "unknown fuzzy pair {k}");
        }
    }
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
    let noise: f32 = arg(&args, "--touch-noise").and_then(|v| v.parse().ok()).unwrap_or(0.0);
    let use_near = args.iter().any(|a| a == "--near");
    let mut rng = Rng(arg(&args, "--seed").and_then(|v| v.parse().ok()).unwrap_or(1).max(1) * 0x9E37_79B9_7F4A_7C15);
    let (mut taps, mut slips) = (0usize, 0usize);
    let typo = arg(&args, "--typo");
    if args.iter().any(|a| a == "--no-autocorrect") {
        e.options.autocorrect = false;
    }
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
        let keys = match &typo {
            Some(kind) => {
                let spells: Vec<&str> = ids.iter().map(|&s| syllable::spelling(s)).collect();
                let mut parts: Vec<String> = spells.iter().map(|s| s.to_string()).collect();
                let long: Vec<usize> = (0..parts.len()).filter(|&i| parts[i].len() >= 3).collect();
                if let Some(&i) = long.get((rng.next() * long.len() as f32) as usize) {
                    let b = parts[i].as_bytes().to_vec();
                    let at = 1 + (rng.next() * (b.len() - 1) as f32) as usize;
                    let mut t = b.clone();
                    match kind.as_str() {
                        "swap" => t.swap(at - 1, at),
                        "drop" => {
                            t.remove(at);
                        }
                        // 按成左右相邻的键（整键按错，不在交界处）。 A whole wrong key: its left or right neighbour.
                        "sub" => {
                            let (cx, cy) = key_center(b[at]);
                            let dx = if rng.next() < 0.5 { -1.0 } else { 1.0 };
                            t[at] = hit(cx + dx, cy).0;
                        }
                        _ => t.insert(at, b[at]),
                    }
                    parts[i] = String::from_utf8(t).unwrap();
                }
                parts.concat()
            }
            None => keys,
        };
        e.clear();
        for c in keys.chars() {
            if noise > 0.0 && c.is_ascii_lowercase() {
                let (cx, cy) = key_center(c as u8);
                let (k, nb) = hit(cx + noise * rng.gauss(), cy + noise / PITCH_Y * rng.gauss());
                taps += 1;
                slips += (k != c as u8) as usize;
                match nb.filter(|_| use_near) {
                    Some((a, cl)) => e.input_key(k as char, Some(a as char), cl),
                    None => e.input_char(k as char),
                };
            } else {
                e.input_char(c);
            }
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
    if noise > 0.0 {
        println!("touch noise σ={noise} near={use_near}: {slips}/{taps} taps hit a neighbour ({:.2}%)", pct(slips, taps));
    }
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
