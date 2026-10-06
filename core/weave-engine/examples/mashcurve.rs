//! 按字母位置统计每次按键耗时：乱打（锯齿即组合串上限在顶屏）、评测集里的真实长句、以及一句错一个字母的长串。
//! 上限前后的对照：把 MAX_COMPOSITION_RAW 调大再跑一次即可。
//! Per-key cost by letter index: a mash (the saw-tooth is the composition cap committing), real long sentences
//! from the eval set, and the same sentences with one letter changed. For a before/after, raise
//! `MAX_COMPOSITION_RAW` and run again.
//!
//! 用法 / Usage: `mashcurve [data dir] [eval tsv]`
use std::path::PathBuf;
use std::time::Instant;

use weave_engine::session::{paths_in, Engine};

fn engine(name: &str, data: &PathBuf) -> Engine {
    let user = std::env::temp_dir().join(format!("weave-curve-{name}"));
    let _ = std::fs::remove_dir_all(&user);
    std::fs::create_dir_all(&user).unwrap();
    Engine::new(&paths_in(data, &user))
}

struct Curve(Vec<Vec<u128>>);

impl Curve {
    fn new() -> Curve {
        Curve(vec![Vec::new(); 12])
    }
    fn add(&mut self, i: usize, us: u128) {
        let b = (i / 6).min(11);
        self.0[b].push(us);
    }
    /// 归到「组合串长度 6 个字母一档」。 Bucketed by composition length in sixes.
    fn print(&self, label: &str) {
        let cells: Vec<String> = self
            .0
            .iter()
            .enumerate()
            .filter(|(_, v)| !v.is_empty())
            .map(|(b, v)| {
                let mut s = v.clone();
                s.sort();
                format!("{}-{}: p50={}us max={}us", b * 6 + 1, b * 6 + v.len().min(6), s[s.len() / 2], s[s.len() - 1])
            })
            .collect();
        println!("{label}\n  {}", cells.join("\n  "));
    }
}

fn main() {
    let data = PathBuf::from(std::env::args().nth(1).unwrap_or("../data/build".into()));
    let eval = std::env::args().nth(2).unwrap_or("../data/eval/sentences.tsv".into());

    // 乱打：随手敲的字母。 Random mash.
    let mut e = engine("mash", &data);
    let mash = "qazwsxedcrfvtgbyhnujmikolpqazwsxedcrfvtgbyhnujmikolpqazwsxedcrfvtgbyhnujmikolp";
    let mut c = Curve::new();
    for (i, ch) in mash.chars().enumerate() {
        let t = Instant::now();
        e.input_char(ch);
        c.add(i, t.elapsed().as_micros());
    }
    c.print("mash");

    // 真实长句：评测集里最长的 60 句。 Real long sentences: the 60 longest in the eval set.
    let text = std::fs::read_to_string(&eval).expect("eval set");
    let mut rows: Vec<String> = text
        .lines()
        .filter_map(|l| l.split_once('\t'))
        .map(|(_, p)| p.replace(' ', ""))
        .collect();
    rows.sort_by_key(|p| std::cmp::Reverse(p.len()));
    let mut c = Curve::new();
    for keys in rows.iter().take(60) {
        let mut e = engine("real", &data);
        for (i, ch) in keys.chars().enumerate() {
            let t = Instant::now();
            e.input_char(ch);
            c.add(i, t.elapsed().as_micros());
        }
    }
    c.print("real sentences (longest 60)");

    // 拼错的长串：真实句子里每句随机改一个字母。 Typo soup: every sentence with one letter substituted.
    let mut c = Curve::new();
    for keys in rows.iter().take(200) {
        let mut e = engine("typo", &data);
        let mut bytes = keys.as_bytes().to_vec();
        if !bytes.is_empty() {
            let at = bytes.len() / 2;
            bytes[at] = b'x';
        }
        for (i, ch) in bytes.iter().enumerate() {
            let t = Instant::now();
            e.input_char(*ch as char);
            c.add(i, t.elapsed().as_micros());
        }
    }
    c.print("typo soup (first 200 sentences, one letter changed)");
}
