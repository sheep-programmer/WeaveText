//! 生成字形表：读取万象拼音的反查词典（笔画序列与部件读音，CC BY 4.0），只收字库里有的字。
//! Build the shape table from rime_wanxiang's reverse-lookup dictionary (stroke orders and component readings,
//! CC BY 4.0), keeping only chars of the given char dictionary.
//!
//! 用法 / Usage: `shapegen <wanxiang_reverse.dict.yaml> <out.wvs> <zi.dict.yaml>`

use std::collections::HashSet;
use std::process::ExitCode;

use weave_dict::shape::ShapeBuilder;

/// RIME 词典正文（`...` 之后）里每一行的前两列。 The first two columns of each line after the RIME header.
fn rows(text: &str) -> impl Iterator<Item = (&str, &str)> {
    text.lines()
        .skip_while(|l| l.trim_end() != "...")
        .skip(1)
        .filter(|l| !l.starts_with('#'))
        .filter_map(|l| {
            let mut it = l.split('\t');
            Some((it.next()?, it.next()?))
        })
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.len() < 3 {
        eprintln!("usage: shapegen <wanxiang_reverse.dict.yaml> <out.wvs> <zi.dict.yaml>");
        return ExitCode::from(2);
    }
    let read = |p: &str| std::fs::read_to_string(p).map_err(|e| eprintln!("read {p}: {e}")).ok();
    let (Some(reverse), Some(zi)) = (read(&args[0]), read(&args[2])) else { return ExitCode::FAILURE };
    let chars: HashSet<&str> = rows(&zi).map(|(c, _)| c).filter(|c| c.chars().count() == 1).collect();
    let mut b = ShapeBuilder::default();
    let (mut strokes, mut splits) = (0, 0);
    for (c, code) in rows(&reverse) {
        if !chars.contains(c) {
            continue;
        }
        let ch = c.chars().next().unwrap();
        // 拆分：部件读音以 ' 分开，最后一段是来源标记（数字或 ~），丢掉。 Splits end with a source tag; drop it.
        if let Some((parts, _)) = code.rsplit_once('\'') {
            if parts.split('\'').all(|p| !p.is_empty() && p.bytes().all(|b| b.is_ascii_lowercase())) {
                b.add(ch, None, Some(parts));
                splits += 1;
            }
        } else if !code.is_empty() && code.bytes().all(|b| b"HSPNZ".contains(&b)) {
            b.add(ch, Some(code), None);
            strokes += 1;
        }
    }
    let bytes = b.build();
    if let Err(e) = std::fs::write(&args[1], &bytes) {
        eprintln!("write {}: {e}", args[1]);
        return ExitCode::FAILURE;
    }
    eprintln!("wrote {} ({strokes} stroke orders, {splits} splits, {:.2} MB)", args[1], bytes.len() as f64 / 1e6);
    ExitCode::SUCCESS
}
