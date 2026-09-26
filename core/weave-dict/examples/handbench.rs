//! 手写识别的合成评测：把标准笔画加上仿射变形、抖动、笔顺调换、反向与连笔，看能否认回原字。
//! 这不是真人笔迹，只用来比较算法改动。
//! Synthetic handwriting benchmark: perturb reference strokes (affine, jitter, swapped order, reversed and
//! joined strokes) and check the original comes back. Not real handwriting; for comparing changes only.
//!
//! 用法 / Usage: `handbench <graphics.txt> <hand.wvh> [n] [difficulty 0..3]`

use std::time::Instant;

use weave_dict::hand::{Recognizer, Stroke};

struct Rng(u64);
impl Rng {
    fn next(&mut self) -> f32 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        (self.0 >> 40) as f32 / (1u64 << 24) as f32
    }
    fn range(&mut self, a: f32, b: f32) -> f32 {
        a + (b - a) * self.next()
    }
}

fn medians(line: &str) -> Option<(char, Vec<Stroke>)> {
    // 与 handgen 相同的格式：借用一个极简解析。 Same format as handgen.
    let ch = line.split("\"character\":\"").nth(1)?.chars().next()?;
    let med = line.split("\"medians\":").nth(1)?;
    let mut strokes = Vec::new();
    for s in med.split("]],") {
        let nums: Vec<f32> = s
            .split(|c: char| !(c.is_ascii_digit() || c == '-' || c == '.'))
            .filter(|t| !t.is_empty())
            .filter_map(|t| t.parse().ok())
            .collect();
        let pts: Stroke = nums.chunks_exact(2).map(|p| (p[0], 900.0 - p[1])).collect();
        if !pts.is_empty() {
            strokes.push(pts);
        }
    }
    Some((ch, strokes))
}

fn perturb(src: &[Stroke], r: &mut Rng, level: u32) -> Vec<Stroke> {
    let l = level as f32;
    let (sx, sy) = (r.range(0.85, 1.15), r.range(0.85, 1.15) * r.range(0.9, 1.1));
    let rot = r.range(-0.05, 0.05) * (1.0 + l);
    let shear = r.range(-0.08, 0.08) * (1.0 + l * 0.5);
    let jitter = 6.0 + 6.0 * l;
    let mut out: Vec<Stroke> = src
        .iter()
        .map(|s| {
            // 加密采样再抖动，模拟手指轨迹。 Densify then jitter, like a finger trace.
            let mut dense = Vec::new();
            for w in s.windows(2) {
                for k in 0..4 {
                    let t = k as f32 / 4.0;
                    dense.push((w[0].0 + (w[1].0 - w[0].0) * t, w[0].1 + (w[1].1 - w[0].1) * t));
                }
            }
            dense.push(*s.last().unwrap());
            dense
                .into_iter()
                .map(|(x, y)| {
                    let (x, y) = (x - 512.0, y - 388.0);
                    let (x, y) = (x * rot.cos() - y * rot.sin(), x * rot.sin() + y * rot.cos());
                    let x = x + shear * y;
                    (x * sx + r.range(-jitter, jitter), y * sy + r.range(-jitter, jitter))
                })
                .collect()
        })
        .collect();
    if level >= 1 && out.len() >= 2 && r.next() < 0.3 {
        let i = (r.next() * (out.len() - 1) as f32) as usize;
        out.swap(i, i + 1);
    }
    if level >= 1 && r.next() < 0.3 {
        let i = (r.next() * out.len() as f32) as usize % out.len();
        out[i].reverse();
    }
    if level >= 2 && out.len() >= 3 && r.next() < 0.3 {
        // 连笔：把相邻两笔接成一笔。 Join two consecutive strokes.
        let i = (r.next() * (out.len() - 1) as f32) as usize;
        let next = out.remove(i + 1);
        out[i].extend(next);
    }
    out
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let n: usize = args.get(2).and_then(|v| v.parse().ok()).unwrap_or(1000);
    let level: u32 = args.get(3).and_then(|v| v.parse().ok()).unwrap_or(1);
    let bytes = std::fs::read(&args[1]).unwrap();
    let rec = Recognizer::from_bytes(&bytes).unwrap();
    let text = std::fs::read_to_string(&args[0]).unwrap();
    // 取最常用的 n 个字（按模板里的字频先验排序）。 The n most frequent chars by the stored prior.
    let all: Vec<(char, Vec<Stroke>)> = text.lines().filter_map(medians).collect();
    let prior = |c: char| rec.prior_of(c);
    let mut chars: Vec<&(char, Vec<Stroke>)> = all.iter().collect();
    chars.sort_by(|a, b| prior(b.0).partial_cmp(&prior(a.0)).unwrap());
    let mut rng = Rng(0x9E3779B97F4A7C15);
    let (mut top1, mut top5, mut total) = (0, 0, 0);
    let t = Instant::now();
    for (c, s) in chars.iter().take(n) {
        let input = perturb(s, &mut rng, level);
        let got = rec.recognize(&input, 5);
        total += 1;
        if got.first().map(|g| g.0) == Some(*c) {
            top1 += 1;
        }
        if got.iter().any(|g| g.0 == *c) {
            top5 += 1;
        }
    }
    let ms = t.elapsed().as_secs_f64() * 1000.0 / total.max(1) as f64;
    println!(
        "level {level}: n={total} top1={:.1}% top5={:.1}% {ms:.1} ms/char",
        100.0 * top1 as f64 / total as f64,
        100.0 * top5 as f64 / total as f64
    );
}
