//! 生成联想用的接续表：遍历编译好的拼音词库，把常用的 3–6 字长词按前缀记下剩余部分。
//! Build the prediction follow table: walk the compiled pinyin lexicon and record the rest of common 3–6 char
//! phrases by their prefixes.
//!
//! 用法 / Usage: `followgen <pinyin.wvl> <out.wvf> [per_key=10] [max_cost=14000] [min_key=1]`

use std::path::Path;
use std::process::ExitCode;

use weave_dict::follow::FollowBuilder;
use weave_dict::lexicon::{Lexicon, NodeId, ROOT};


/// 接续表的取舍：长词 cost 上限（只收足够常用的）与最短前缀。 Follow-table limits: max phrase cost and min key length.
struct Limits {
    max_cost: u16,
    min_key: usize,
}

fn walk(lex: &Lexicon, n: NodeId, key: &mut Vec<u16>, f: &mut FollowBuilder, lim: &Limits, count: &mut usize) {
    if key.len() >= 2 {
        for e in lex.entries(n) {
            let t = lex.text(e.text_id, key);
            let chars = t.chars().count();
            if !t.chars().all(|c| ('\u{4e00}'..='\u{9fff}').contains(&c)) {
                continue;
            }
            if (3..=6).contains(&chars) && e.cost <= lim.max_cost {
                f.push_phrase(&t, e.cost, lim.min_key, 4);
                *count += 1;
            }
        }
    }
    if key.len() >= 6 {
        return;
    }
    for c in lex.children(n) {
        key.push(lex.sym(c));
        walk(lex, c, key, f, lim, count);
        key.pop();
    }
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.len() < 2 {
        eprintln!("usage: followgen <pinyin.wvl> <out.wvf> [per_key] [max_cost] [min_key]");
        return ExitCode::from(2);
    }
    let per_key: usize = args.get(2).and_then(|v| v.parse().ok()).unwrap_or(10);
    let lim = Limits {
        max_cost: args.get(3).and_then(|v| v.parse().ok()).unwrap_or(14_000),
        min_key: args.get(4).and_then(|v| v.parse().ok()).unwrap_or(1),
    };
    let lex = match Lexicon::open(Path::new(&args[0])) {
        Ok(l) => l,
        Err(e) => {
            eprintln!("open {}: {e:?}", args[0]);
            return ExitCode::FAILURE;
        }
    };
    let mut f = FollowBuilder::default();
    let mut count = 0;
    walk(&lex, ROOT, &mut Vec::new(), &mut f, &lim, &mut count);
    let bytes = f.build(per_key);
    if let Err(e) = std::fs::write(&args[1], &bytes) {
        eprintln!("write {}: {e}", args[1]);
        return ExitCode::FAILURE;
    }
    eprintln!("wrote {} ({count} phrases, {:.2} MB)", args[1], bytes.len() as f64 / 1e6);
    ExitCode::SUCCESS
}
