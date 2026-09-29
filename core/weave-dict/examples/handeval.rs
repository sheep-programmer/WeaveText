//! 手写识别在真人笔迹上的评测。输入每行 `字<TAB>笔画;笔画…`，每笔为空格分隔的 `x,y`（y 向下）。
//! Handwriting accuracy on real ink. Each input line is `char<TAB>stroke;stroke…`, a stroke being
//! space-separated `x,y` points (y down).
//!
//! 用法 / Usage: `handeval <hand.wvh | hand.wvn> <samples.txt> [limit] [hand.wvh]`
//! 模型按文件头区分：模板（WVHW）或卷积网络（WVHN）；网络后再给模板文件时评测两者融合（即随应用发布的识别）。
//! The model kind is taken from the file header; a network plus a template file evaluates the fusion the app ships.

use std::time::Instant;

use weave_dict::hand::{Recognizer, Stroke};
use weave_dict::handnet::HandNet;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let bytes = std::fs::read(&args[0]).unwrap();
    let recognize: Box<dyn Fn(&[Stroke]) -> Vec<char>> = if bytes.starts_with(weave_dict::handnet::MAGIC) {
        let net = HandNet::from_bytes(&bytes).unwrap();
        let templates = args.get(3).map(|p| Recognizer::from_bytes(&std::fs::read(p).unwrap()).unwrap());
        let models = weave_dict::handnet::HandModels { templates, net: Some(net) };
        Box::new(move |s| models.recognize(s, 10))
    } else {
        let rec = Recognizer::from_bytes(&bytes).unwrap();
        Box::new(move |s| rec.recognize(s, 10).into_iter().map(|c| c.0).collect())
    };
    let limit: usize = args.get(2).and_then(|v| v.parse().ok()).unwrap_or(usize::MAX);
    let text = std::fs::read_to_string(&args[1]).unwrap();
    let (mut top1, mut top5, mut top10, mut total) = (0, 0, 0, 0);
    let t = Instant::now();
    for line in text.lines().take(limit) {
        let Some((c, ink)) = line.split_once('\t') else { continue };
        let Some(c) = c.chars().next() else { continue };
        let strokes: Vec<Stroke> = ink
            .split(';')
            .map(|s| {
                s.split(' ')
                    .filter_map(|p| p.split_once(','))
                    .filter_map(|(x, y)| Some((x.parse().ok()?, y.parse().ok()?)))
                    .collect()
            })
            .filter(|s: &Stroke| !s.is_empty())
            .collect();
        let got = recognize(&strokes);
        total += 1;
        let rank = got.iter().position(|&g| g == c);
        top1 += (rank == Some(0)) as u32;
        top5 += rank.is_some_and(|r| r < 5) as u32;
        top10 += rank.is_some() as u32;
    }
    let ms = t.elapsed().as_secs_f64() * 1000.0 / total.max(1) as f64;
    let pct = |n: u32| n as f64 * 100.0 / total.max(1) as f64;
    println!("n={total} top1={:.1}% top5={:.1}% top10={:.1}% {ms:.1} ms/char", pct(top1), pct(top5), pct(top10));
}
