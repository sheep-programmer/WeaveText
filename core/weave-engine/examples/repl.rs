//! 命令行试用：`cargo run --release --example repl -- <data_dir> [schema]`
//! 每行输入按键序列，输出候选；`:schema xxx` 切换方案，`:sel N` 选择候选。
use std::io::BufRead;
use std::time::Instant;

use weave_engine::session::{paths_in, Engine, Schema};

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let data = std::path::PathBuf::from(
        args.first()
            .cloned()
            .unwrap_or_else(|| "../../data/build".into()),
    );
    let user = std::env::temp_dir().join("weave-repl-user");
    let t = Instant::now();
    let mut e = Engine::new(&paths_in(&data, &user));
    eprintln!("loaded in {:?}", t.elapsed());
    if let Some(s) = args.get(1).and_then(|s| Schema::from_key(s)) {
        e.set_schema(s);
    }
    for line in std::io::stdin().lock().lines() {
        let line = line.unwrap();
        let line = line.trim();
        if let Some(s) = line.strip_prefix(":schema ") {
            e.set_schema(Schema::from_key(s).expect("schema"));
            continue;
        }
        if let Some(opt) = line.strip_prefix(":opt ") {
            let (key, value) = opt.split_once('=').expect("option=value");
            assert!(e.options.set_flag(key, value == "true"), "unknown option");
            continue;
        }
        if let Some(weight) = line.strip_prefix(":lm ") {
            e.options.lm_weight = weight.parse().expect("language-model weight");
            continue;
        }
        if let Some(n) = line.strip_prefix(":sel ") {
            e.select(n.parse().unwrap());
        } else if line == ":trad" {
            e.options.traditional = !e.options.traditional;
            continue;
        } else if line == ":bs" {
            e.backspace();
        } else if let Some(n) = line.strip_prefix(":py ") {
            e.select_pinyin_option(n.parse().unwrap());
        } else {
            e.clear();
            let t = Instant::now();
            for c in line.chars() {
                e.input_char(c);
            }
            eprintln!("  ({:?})", t.elapsed());
        }
        let s = e.snapshot();
        if !s.commit.is_empty() {
            println!("COMMIT: {}", s.commit);
        }
        println!(
            "[{}] total={} {}",
            s.preedit,
            s.total_candidates,
            if s.pinyin_options.is_empty() {
                String::new()
            } else {
                format!(
                    "py={:?}",
                    &s.pinyin_options[..s.pinyin_options.len().min(8)]
                )
            }
        );
        let list: Vec<String> = s
            .candidates
            .iter()
            .take(12)
            .enumerate()
            .map(|(i, c)| {
                if c.comment.is_empty() {
                    format!("{i}.{}{}", c.text, if c.user { "*" } else { "" })
                } else {
                    format!("{i}.{}({})", c.text, c.comment)
                }
            })
            .collect();
        println!("  {}", list.join(" "));
    }
}
