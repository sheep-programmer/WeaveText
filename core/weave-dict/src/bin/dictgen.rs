//! 词库编译工具。 Dictionary compiler.
//!
//! 用法 / Usage:
//! ```text
//! dictgen pinyin  <out.wvl> <src.dict.yaml>...   # 行格式: 词<TAB>拼音(空格分隔)[<TAB>权重]
//! dictgen letters <out.wvl> <src>...             # 行格式: 词<TAB>编码[<TAB>权重]  (五笔 / 英文)
//! dictgen total   <src.dict.yaml>...              # 只打印这些源的总权重 / print the total weight only
//! dictgen annotate <out.dict.yaml> <words.txt> <base.dict.yaml>...
//!                  # 给「词<TAB>频次」词表注音（按基础词库最长匹配），频次按对数排名映射到 1..1000，已在基础词库的词跳过
//!                  # annotate a "word<TAB>count" list by longest match against the base; counts map to 1..1000
//!                  # on a log rank; words already in the base are skipped
//! ```
//! 扩展词库（专业词库）用 `--total <基础词库总权重>` 与基础词库同尺度计分，再加 `--bias <cost>` 整体靠后，
//! 保证不会挤掉常用词。
//! Extra packs use `--total <base total>` to score on the base scale and `--bias <cost>` to sit behind it,
//! so they never push common words down.
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
    let plain: String = s.chars()
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
        .collect();
    // 嗯的音节鼻音在源词库里写作 n/ng（含声调）；用常见输入 en 收录，避免整条词被跳过。
    // Syllabic n/ng (including tones) are commonly typed as en; retain these interjections in the dictionary.
    plain.split_whitespace().map(|part| if matches!(part, "n" | "ng") { "en" } else { part }).collect::<Vec<_>>().join(" ")
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

/// 注音：基础词库里每个词取权重最高的读音；单字另记最常用读音。
/// Annotation: each base word keeps its most weighted reading; single chars too.
fn annotate(out: &PathBuf, words: &PathBuf, bases: &[String]) -> ExitCode {
    use std::collections::HashMap;
    let mut best: HashMap<String, (String, f64)> = HashMap::new();
    for b in bases {
        let content = std::fs::read_to_string(b).unwrap_or_else(|e| panic!("read {b}: {e}"));
        let body = content.split("\n...\n").last().unwrap_or(&content);
        for line in body.lines() {
            let mut cols = line.split('\t');
            let (Some(text), Some(code)) = (cols.next(), cols.next()) else { continue };
            if text.is_empty() || line.starts_with('#') {
                continue;
            }
            let py = strip_tones(code);
            if syllable::parse_seq(&py).is_none() {
                continue;
            }
            let w = cols.next().and_then(|w| w.trim().trim_end_matches('%').parse::<f64>().ok()).unwrap_or(1.0);
            let e = best.entry(text.to_string()).or_insert((py.clone(), f64::MIN));
            if w > e.1 {
                *e = (py, w);
            }
        }
    }
    let raw = std::fs::read_to_string(words).unwrap_or_else(|e| panic!("read {words:?}: {e}"));
    let raw = raw.trim_start_matches('\u{feff}');
    let mut items: Vec<(String, f64)> = Vec::new();
    for line in raw.split(['\r', '\n']) {
        let mut cols = line.split('\t');
        let Some(w) = cols.next().map(str::trim).filter(|w| !w.is_empty()) else { continue };
        let n: f64 = cols.next().and_then(|c| c.trim().parse().ok()).unwrap_or(1.0);
        items.push((w.to_string(), n.max(1.0)));
    }
    let (lo, hi) = items.iter().fold((f64::MAX, f64::MIN), |(a, b), (_, n)| (a.min(n.ln()), b.max(n.ln())));
    let span = (hi - lo).max(1e-9);
    let (mut kept, mut known, mut unreadable) = (0usize, 0usize, 0usize);
    let mut text = String::from("# 由 dictgen annotate 生成 / generated by dictgen annotate\n...\n");
    'item: for (w, n) in &items {
        if best.contains_key(w) {
            known += 1;
            continue;
        }
        // 最长匹配切分：优先用基础词库里的词，拿不准的字取最常用读音。
        // Longest-match segmentation over base words; single chars take their most common reading.
        let chars: Vec<char> = w.chars().collect();
        let mut i = 0;
        let mut py: Vec<String> = Vec::new();
        while i < chars.len() {
            let mut hit = None;
            for len in (1..=(chars.len() - i).min(8)).rev() {
                let seg: String = chars[i..i + len].iter().collect();
                if let Some((p, _)) = best.get(&seg) {
                    hit = Some((len, p.clone()));
                    break;
                }
            }
            match hit {
                Some((len, p)) => {
                    py.push(p);
                    i += len;
                }
                None => {
                    unreadable += 1;
                    continue 'item;
                }
            }
        }
        let weight = (1.0 + 999.0 * (n.ln() - lo) / span).round();
        text.push_str(&format!("{w}\t{}\t{weight}\n", py.join(" ")));
        kept += 1;
    }
    if let Err(e) = std::fs::write(out, text) {
        eprintln!("write {out:?}: {e}");
        return ExitCode::FAILURE;
    }
    eprintln!("{words:?}: {kept} annotated, {known} already in base, {unreadable} unreadable");
    ExitCode::SUCCESS
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
    let mut fixed_total: Option<f64> = None;
    if let Some(i) = args.iter().position(|a| a == "--total") {
        fixed_total = Some(args[i + 1].parse().expect("--total <f64>"));
        args.drain(i..=i + 1);
    }
    let mut bias = 0u32;
    if let Some(i) = args.iter().position(|a| a == "--bias") {
        bias = args[i + 1].parse().expect("--bias <cost>");
        args.drain(i..=i + 1);
    }
    if args.first().map(String::as_str) == Some("annotate") {
        if args.len() < 4 {
            eprintln!("usage: dictgen annotate <out.dict.yaml> <words.txt> <base.dict.yaml>...");
            return ExitCode::from(2);
        }
        return annotate(&PathBuf::from(&args[1]), &PathBuf::from(&args[2]), &args[3..]);
    }
    if args.first().map(String::as_str) == Some("total") {
        let mut rows = Vec::new();
        for src in &args[1..] {
            read_rows(&PathBuf::from(src), Kind::Pinyin, default_weight, &mut rows);
        }
        rows.retain(|r| r.weight >= min_weight || r.text.chars().count() == 1);
        println!("{:.0}", rows.iter().map(|r| r.weight + 1.0).sum::<f64>());
        return ExitCode::SUCCESS;
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
    let total: f64 = fixed_total.unwrap_or_else(|| rows.iter().map(|r| r.weight + 1.0).sum());
    let mut b = Builder::new(kind);
    for r in &rows {
        let cost = prob_to_cost((r.weight + 1.0) / total) as u32 + bias;
        b.insert(&r.key, &r.text, cost.min(u16::MAX as u32) as u16);
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn syllabic_nasals_are_compiled_as_typable_interjections() {
        for input in ["ǹ", "ń", "ň", "ǹg", "ng"] {
            assert_eq!(syllable::parse_seq(&strip_tones(input)), syllable::parse_seq("en"));
        }
        assert_eq!(strip_tones("ng ng"), "en en");
        assert_eq!(strip_tones("xiū ba"), "xiu ba");
        assert_eq!(strip_tones("nǐ hǎo"), "ni hao");
    }
}
