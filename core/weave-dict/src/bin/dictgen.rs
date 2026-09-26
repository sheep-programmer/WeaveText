//! 词库编译工具。 Dictionary compiler.
//!
//! 用法 / Usage:
//! ```text
//! dictgen pinyin  <out.wvl> <src.dict.yaml>...   # 行格式: 词<TAB>拼音(空格分隔)[<TAB>权重]
//! dictgen letters <out.wvl> <src>...             # 行格式: 词<TAB>编码[<TAB>权重]  (五笔 / 英文)
//! ```
//! 源文件可以带 Rime 风格的 YAML 头（到 `...` 行为止），`#` 开头为注释。
//! Sources may carry a Rime-style YAML header terminated by `...`; `#` starts a comment.
//!
//! 权重缺省为 `--default-weight`（默认 1）。 Missing weights default to `--default-weight`.

use std::path::PathBuf;
use std::process::ExitCode;

use weave_dict::lexicon::{letter_sym, prob_to_cost, Builder, Kind};
use weave_dict::syllable;

/// 去掉声调、把 ü 写成 v（万象等数据用带调拼音）。 Strip tone marks; ü → v.
fn strip_tones(s: &str) -> String {
    s.chars()
        .map(|c| match c {
            'ā' | 'á' | 'ǎ' | 'à' => 'a',
            'ō' | 'ó' | 'ǒ' | 'ò' => 'o',
            'ē' | 'é' | 'ě' | 'è' | 'ê' => 'e',
            'ī' | 'í' | 'ǐ' | 'ì' => 'i',
            'ū' | 'ú' | 'ǔ' | 'ù' => 'u',
            'ü' | 'ǖ' | 'ǘ' | 'ǚ' | 'ǜ' => 'v',
            'ń' | 'ň' | 'ǹ' => 'n',
            'ḿ' => 'm',
            c => c.to_ascii_lowercase(),
        })
        .collect()
}

struct Row {
    text: String,
    key: Vec<u16>,
    weight: f64,
}

fn read_rows(
    path: &PathBuf,
    kind: Kind,
    default_weight: f64,
    rows: &mut Vec<Row>,
) -> (usize, usize) {
    let content = std::fs::read_to_string(path).unwrap_or_else(|e| panic!("read {path:?}: {e}"));
    let has_header = content.lines().any(|l| l.trim_end() == "...");
    let mut in_body = !has_header;
    let (mut ok, mut bad) = (0usize, 0usize);
    for line in content.lines() {
        if !in_body {
            if line.trim_end() == "..." {
                in_body = true;
            }
            continue;
        }
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let mut cols = line.split('\t');
        let (Some(text), Some(code)) = (cols.next(), cols.next()) else {
            bad += 1;
            continue;
        };
        let weight = cols
            .next()
            .and_then(|w| w.trim().trim_end_matches('%').parse::<f64>().ok())
            .unwrap_or(default_weight);
        let key = match kind {
            Kind::Pinyin => syllable::parse_seq(&strip_tones(code)),
            Kind::Letters => {
                let lower = code.trim().to_ascii_lowercase();
                lower.bytes().map(letter_sym).collect::<Option<Vec<_>>>()
            }
        };
        match key {
            Some(k) if !k.is_empty() && !text.is_empty() && k.len() <= 32 => {
                rows.push(Row {
                    text: text.to_owned(),
                    key: k,
                    weight: weight.max(0.0),
                });
                ok += 1;
            }
            _ => bad += 1,
        }
    }
    (ok, bad)
}

fn main() -> ExitCode {
    let mut args: Vec<String> = std::env::args().skip(1).collect();
    let mut default_weight = 1.0f64;
    // 低于该权重的多字词丢弃（单字总是保留）。 Drop multi-char words below this weight.
    let mut min_weight = 0.0f64;
    if let Some(i) = args.iter().position(|a| a == "--min-weight") {
        min_weight = args[i + 1].parse().expect("--min-weight <f64>");
        args.drain(i..=i + 1);
    }
    if let Some(i) = args.iter().position(|a| a == "--default-weight") {
        default_weight = args[i + 1].parse().expect("--default-weight <f64>");
        args.drain(i..=i + 1);
    }
    if args.len() < 3 {
        eprintln!("usage: dictgen (pinyin|letters) <out.wvl> <src>... [--default-weight W]");
        return ExitCode::from(2);
    }
    let kind = match args[0].as_str() {
        "pinyin" => Kind::Pinyin,
        "letters" => Kind::Letters,
        other => {
            eprintln!("unknown kind {other}");
            return ExitCode::from(2);
        }
    };
    let out = PathBuf::from(&args[1]);
    let mut rows = Vec::new();
    for src in &args[2..] {
        let path = PathBuf::from(src);
        let (ok, bad) = read_rows(&path, kind, default_weight, &mut rows);
        eprintln!("{src}: {ok} rows, {bad} skipped");
    }
    let before = rows.len();
    rows.retain(|r| r.weight >= min_weight || r.text.chars().count() == 1);
    if rows.len() != before {
        eprintln!(
            "pruned {} rows below weight {min_weight}",
            before - rows.len()
        );
    }
    // 加一平滑的最大似然概率。 Add-one smoothed MLE.
    let total: f64 = rows.iter().map(|r| r.weight + 1.0).sum();
    let mut b = Builder::new(kind);
    for r in &rows {
        b.insert(&r.key, &r.text, prob_to_cost((r.weight + 1.0) / total));
    }
    eprintln!("nodes: {}", b.node_count());
    if let Err(e) = b.write_to(&out) {
        eprintln!("write {out:?}: {e}");
        return ExitCode::FAILURE;
    }
    let size = std::fs::metadata(&out).map(|m| m.len()).unwrap_or(0);
    eprintln!("wrote {out:?} ({:.1} MiB)", size as f64 / 1048576.0);
    ExitCode::SUCCESS
}
