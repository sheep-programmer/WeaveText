//! 逐键延迟基准：按评测集逐字母输入，统计每次「输入一个键 + 取快照」的耗时分布。
//! 先跑一遍空用户词库，再把评测句全部学一遍后重跑，观察「越用越慢」。
//! Per-keystroke latency: type the eval set letter by letter and time "one key + snapshot". Runs once
//! with an empty user dictionary, then again after learning every sentence.
//!
//! 用法 / Usage: `keybench [--data DIR] [--eval FILE] [--limit N] [--cache-mb MB]`

use std::path::PathBuf;
use std::time::Instant;

use weave_engine::session::{paths_in, Engine};

fn arg(args: &[String], name: &str) -> Option<String> {
    args.iter()
        .position(|a| a == name)
        .and_then(|i| args.get(i + 1).cloned())
}

fn run(e: &mut Engine, inputs: &[String]) -> Vec<f64> {
    let mut times = Vec::new();
    for keys in inputs {
        e.clear();
        for c in keys.chars() {
            let t = Instant::now();
            e.input_char(c);
            let _ = e.snapshot();
            times.push(t.elapsed().as_secs_f64() * 1e3);
        }
    }
    times
}

fn report(label: &str, mut t: Vec<f64>) {
    t.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let q = |p: f64| t[((t.len() - 1) as f64 * p) as usize];
    let mean = t.iter().sum::<f64>() / t.len().max(1) as f64;
    println!(
        "{label}: keys={} mean={mean:.3}ms p50={:.3} p95={:.3} p99={:.3} max={:.3}",
        t.len(),
        q(0.5),
        q(0.95),
        q(0.99),
        t.last().copied().unwrap_or(0.0)
    );
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let data = PathBuf::from(arg(&args, "--data").unwrap_or("../data/build".into()));
    let eval = arg(&args, "--eval").unwrap_or("../data/eval/sentences.tsv".into());
    let limit: usize = arg(&args, "--limit").and_then(|v| v.parse().ok()).unwrap_or(usize::MAX);
    if let Some(mb) = arg(&args, "--cache-mb").and_then(|v| v.parse::<usize>().ok()) {
        weave_dict::blob::set_cache_budget(mb << 20);
    }
    let text = std::fs::read_to_string(&eval).expect("read eval set");
    let rows: Vec<(String, String)> = text
        .lines()
        .take(limit)
        .filter_map(|l| l.split_once('\t'))
        .map(|(s, p)| (s.to_string(), p.replace(' ', "")))
        .collect();
    let inputs: Vec<String> = rows.iter().map(|r| r.1.clone()).collect();
    let user = std::env::temp_dir().join(format!("keybench-{}", std::process::id()));
    std::fs::create_dir_all(&user).unwrap();
    let mut e = Engine::new(&paths_in(&data, &user));
    e.options.emoji = false;
    report("empty user dict", run(&mut e, &inputs));
    // 学一遍：把每句的首选上屏（首选不对的句子按正确答案学不了，只学首选即可制造用户词）。
    // Learn: commit the top candidate of every sentence, which fills the user dictionary.
    e.set_learning(true);
    for keys in &inputs {
        e.clear();
        for c in keys.chars() {
            e.input_char(c);
        }
        e.commit_first();
        let _ = e.snapshot();
    }
    report("after learning", run(&mut e, &inputs));
    std::fs::remove_dir_all(&user).ok();
}
