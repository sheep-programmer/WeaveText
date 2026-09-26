//! 生成手写识别模板：读取 Make Me a Hanzi 的 graphics.txt（笔画中线，Arphic Public License），
//! 可选读取字频表（每行 `字<TAB>…<TAB>权重`，如万象 zi.dict.yaml）作为先验。
//! Build handwriting templates from Make Me a Hanzi's graphics.txt (stroke medians, Arphic Public
//! License), optionally with a character-frequency table as the prior.
//!
//! 用法 / Usage: `handgen <graphics.txt> <out.wvh> [freq.dict.yaml]`

use std::collections::HashMap;
use std::process::ExitCode;

use weave_dict::hand::{Builder, Stroke};

/// 从一行 JSON 里取出 "character" 与 "medians"（格式固定，只做这件事的小解析器）。
/// Pull "character" and "medians" out of one JSON line (a tiny parser for this fixed format).
fn parse_line(line: &str) -> Option<(char, Vec<Stroke>)> {
    let key = "\"character\":";
    let i = line.find(key)? + key.len();
    let rest = line[i..].trim_start().strip_prefix('"')?;
    let ch = if let Some(hex) = rest.strip_prefix("\\u") {
        char::from_u32(u32::from_str_radix(hex.get(..4)?, 16).ok()?)?
    } else {
        rest.chars().next()?
    };
    let key = "\"medians\":";
    let j = line.find(key)? + key.len();
    let bytes = line[j..].as_bytes();
    // 三层数组：[[[x,y],[x,y]],[[x,y],…]]。 Three nested levels.
    let mut depth = 0;
    let mut strokes: Vec<Stroke> = Vec::new();
    let mut nums: Vec<f32> = Vec::new();
    let mut num = String::new();
    for &b in bytes {
        match b {
            b'[' => {
                depth += 1;
                if depth == 2 {
                    strokes.push(Vec::new());
                }
            }
            b']' | b',' => {
                if !num.is_empty() {
                    nums.push(num.parse().ok()?);
                    num.clear();
                }
                if b == b']' {
                    if depth == 3 && nums.len() == 2 {
                        // 原坐标 y 向上，左上角为 (0, 900)；换成 y 向下。 Source y points up; flip it.
                        strokes.last_mut()?.push((nums[0], 900.0 - nums[1]));
                        nums.clear();
                    }
                    depth -= 1;
                    if depth == 0 {
                        break;
                    }
                }
            }
            b'-' | b'.' | b'0'..=b'9' => num.push(b as char),
            _ => {}
        }
    }
    (!strokes.is_empty()).then_some((ch, strokes))
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.len() < 2 {
        eprintln!("usage: handgen <graphics.txt> <out.wvh> [freq.dict.yaml]");
        return ExitCode::from(2);
    }
    // 字频：单字的最大权重，按名次换成 0..=255（对数刻度）。 Frequency: max weight per char, rank → 0..=255.
    let mut weight: HashMap<char, f64> = HashMap::new();
    if let Some(freq) = args.get(2) {
        let text = std::fs::read_to_string(freq).unwrap_or_default();
        let body = text.split("\n...\n").last().unwrap_or(&text);
        for l in body.lines() {
            let cols: Vec<&str> = l.split('\t').collect();
            let mut it = cols.first().map(|s| s.chars()).into_iter().flatten();
            let (Some(c), None) = (it.next(), it.next()) else { continue };
            let w: f64 = cols.last().and_then(|v| v.trim().parse().ok()).unwrap_or(0.0);
            let e = weight.entry(c).or_insert(0.0);
            *e = e.max(w);
        }
    }
    let mut ranked: Vec<(char, f64)> = weight.into_iter().collect();
    ranked.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap().then(a.0.cmp(&b.0)));
    let n = ranked.len().max(1) as f64;
    let prior: HashMap<char, u8> = ranked
        .iter()
        .enumerate()
        .map(|(r, (c, _))| (*c, (255.0 * (1.0 - ((r + 1) as f64).ln() / n.ln().max(1.0))).clamp(0.0, 255.0) as u8))
        .collect();

    let text = match std::fs::read_to_string(&args[0]) {
        Ok(t) => t,
        Err(e) => {
            eprintln!("read {}: {e}", args[0]);
            return ExitCode::FAILURE;
        }
    };
    let mut b = Builder::default();
    let mut bad = 0;
    for line in text.lines() {
        match parse_line(line) {
            Some((c, s)) => b.push(c, prior.get(&c).copied().unwrap_or(0), &s),
            None => bad += 1,
        }
    }
    let count = b.len();
    let bytes = b.build();
    if let Err(e) = std::fs::write(&args[1], &bytes) {
        eprintln!("write {}: {e}", args[1]);
        return ExitCode::FAILURE;
    }
    eprintln!("wrote {} ({count} chars, {bad} skipped, {:.2} MB)", args[1], bytes.len() as f64 / 1e6);
    ExitCode::SUCCESS
}
