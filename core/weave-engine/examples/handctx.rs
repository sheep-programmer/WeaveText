//! 手写上文重排的合成评测：真实词库与识别网络，加上变形笔迹；比较重排权重对首选准确率的影响。
//! 不是真人笔迹，只用来比较算法改动。
//! Synthetic evaluation of the handwriting context re-ranking: the real lexicon and network on perturbed strokes;
//! compares re-ranking weights by top-1 accuracy. Not real handwriting; for comparing changes only.
//!
//! 用法 / Usage: `handctx <graphics.txt> <data_dir> <sentences.tsv> [level 0..4] [limit] [line]`

use std::collections::HashMap;
use weave_dict::hand::Stroke;
use weave_engine::session::{paths_in, Engine, Schema};

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
    if level >= 3 {
        out = realistic(out, r, level);
    }
    out
}

/// 更像真人手写的局部变形：部件整体错位与比例失调、每笔微移、笔画长短不一、弯曲、带钩、偶尔断笔。
/// Local deformation closer to real handwriting: whole components shifted and resized, per-stroke drift,
/// strokes longer or shorter, bowed, hooked, occasionally broken.
fn realistic(strokes: Vec<Stroke>, r: &mut Rng, level: u32) -> Vec<Stroke> {
    let k = if level >= 4 { 1.6 } else { 1.0 };
    let (mut x0, mut y0, mut x1, mut y1) = (f32::MAX, f32::MAX, f32::MIN, f32::MIN);
    for s in &strokes {
        for &(x, y) in s {
            x0 = x0.min(x);
            y0 = y0.min(y);
            x1 = x1.max(x);
            y1 = y1.max(y);
        }
    }
    let size = (x1 - x0).max(y1 - y0).max(1.0);
    let (cx, cy) = ((x0 + x1) / 2.0, (y0 + y1) / 2.0);
    // 部件：按左右或上下把笔画分成两组，各自平移与缩放。 Components: split left/right or top/bottom, move and scale each.
    let vertical = r.next() < 0.5;
    let mut groups = [(0f32, 0f32, 1f32), (0f32, 0f32, 1f32)];
    for g in &mut groups {
        *g = (r.range(-0.08, 0.08) * k * size, r.range(-0.08, 0.08) * k * size, 1.0 + r.range(-0.18, 0.18) * k);
    }
    let mut out: Vec<Stroke> = Vec::new();
    for s in strokes {
        let (mx, my) = s.iter().fold((0.0, 0.0), |a, p| (a.0 + p.0, a.1 + p.1));
        let (mx, my) = (mx / s.len() as f32, my / s.len() as f32);
        let side = if vertical { (my > cy) as usize } else { (mx > cx) as usize };
        let (gx, gy, gs) = groups[side];
        let (sx, sy) = (r.range(-0.03, 0.03) * k * size, r.range(-0.03, 0.03) * k * size);
        let len_scale = 1.0 + r.range(-0.15, 0.15) * k;
        let bow = r.range(-0.05, 0.05) * k * size;
        let n = s.len().max(2);
        let (ax, ay) = (s[0].0, s[0].1);
        let (bx, by) = (s[n - 1].0, s[n - 1].1);
        let (dx, dy) = (bx - ax, by - ay);
        let dl = (dx * dx + dy * dy).sqrt().max(1.0);
        let (nx, ny) = (-dy / dl, dx / dl);
        let mut t: Stroke = s
            .iter()
            .enumerate()
            .map(|(i, &(x, y))| {
                let f = i as f32 / (n - 1) as f32;
                // 以起点为基准伸缩长度，并向一侧弯曲。 Stretch from the start and bow sideways.
                let (x, y) = (ax + (x - ax) * len_scale, ay + (y - ay) * len_scale);
                let b = bow * (std::f32::consts::PI * f).sin();
                let (x, y) = (x + nx * b, y + ny * b);
                // 部件缩放与平移。 Component scale and shift.
                let (x, y) = (cx + (x - cx) * gs + gx + sx, cy + (y - cy) * gs + gy + sy);
                (x, y)
            })
            .collect();
        if r.next() < 0.25 * k {
            // 收笔带钩。 A hook at the end.
            let &(ex, ey) = t.last().unwrap();
            let a = r.range(0.0, std::f32::consts::TAU);
            let hl = 0.05 * size;
            t.push((ex + a.cos() * hl, ey + a.sin() * hl));
        }
        if t.len() >= 8 && r.next() < 0.12 * k {
            // 断笔：一笔写成两笔。 A broken stroke.
            let cut = t.len() / 2;
            let tail = t.split_off(cut);
            out.push(t);
            out.push(tail);
        } else {
            out.push(t);
        }
    }
    out
}


fn main() {
    let a: Vec<String> = std::env::args().skip(1).collect();
    let level: u32 = a.get(3).and_then(|v| v.parse().ok()).unwrap_or(3);
    let limit: usize = a.get(4).and_then(|v| v.parse().ok()).unwrap_or(400);
    let line = a.get(5).is_some_and(|s| s == "line");
    let graphics = std::fs::read_to_string(&a[0]).unwrap();
    let chars: HashMap<char, Vec<Stroke>> = graphics.lines().filter_map(medians).collect();
    let user = std::env::temp_dir().join(format!("weave-handctx-{}", std::process::id()));
    std::fs::create_dir_all(&user).unwrap();
    let mut e = Engine::new(&paths_in(std::path::Path::new(&a[1]), &user));
    e.set_schema(Schema::Hand);
    e.features(&serde_json::json!({"op":"setHandLine","on":line}));
    let models = e.hand_models().expect("handwriting models");
    let sentences: Vec<Vec<char>> = std::fs::read_to_string(&a[2]).unwrap().lines()
        .filter_map(|l| l.split('\t').next()).map(|t| t.chars().collect()).collect();
    let mut rng = Rng(0x9E3779B97F4A7C15);
    // 样本：(上文, 真值, 线上传输格式)。 Samples: (context, truth, wire).
    let mut samples: Vec<(String, String, Vec<Stroke>, Vec<u32>)> = Vec::new();
    'outer: for s in &sentences {
        let n = if line { 2 + (rng.next() * 2.0) as usize } else { 1 };
        let mut i = 1;
        while i + n <= s.len() {
            let word: Vec<char> = s[i..i + n].to_vec();
            if word.iter().all(|c| chars.contains_key(c)) {
                let mut ink: Vec<Stroke> = Vec::new();
                for (k, c) in word.iter().enumerate() {
                    for mut st in perturb(&chars[c], &mut rng, level) {
                        for p in &mut st { p.0 += k as f32 * 1500.0; }
                        ink.push(st);
                    }
                }
                let ctx: String = s[i.saturating_sub(8)..i].iter().collect();
                let wire = models.recognize_wire(&ink, weave_engine::session::HAND_CANDIDATES);
                samples.push((ctx, word.iter().collect(), ink, wire));
                if samples.len() >= limit { break 'outer; }
            }
            i += n + 1;
        }
    }
    println!("{} samples, level {level}, {}", samples.len(), if line { "spaced line" } else { "single char" });
    let weights: Vec<(f32, f32)> = if line {
        vec![(0.0, 0.0), (0.3, 0.0), (0.6, 0.0), (1.0, 0.0), (0.0, 2.0), (0.3, 2.0), (0.6, 2.0), (0.3, 4.0), (0.6, 4.0), (1.0, 4.0)]
    } else {
        vec![(0.0, 0.0), (0.05, 0.0), (0.1, 0.0), (0.15, 0.0), (0.2, 0.0), (0.3, 0.0), (0.45, 0.0), (0.6, 0.0), (1.0, 0.0)]
    };
    for (w, bonus) in weights {
        e.options.hand_lm_weight = w;
        e.options.hand_word_bonus = bonus;
        let (mut top1, mut top5) = (0, 0);
        for (ctx, truth, ink, wire) in &samples {
            e.clear();
            e.set_context(if ctx.is_empty() { None } else { Some(ctx.clone()) });
            e.set_schema(Schema::Hand);
            e.hand_apply_wire(ink.clone(), wire);
            let c: Vec<String> = e.snapshot().candidates.iter().map(|c| c.text.clone()).collect();
            if c.first() == Some(truth) { top1 += 1; }
            if c.iter().take(5).any(|x| x == truth) { top5 += 1; }
        }
        let n = samples.len() as f64;
        println!("lm={w:<5} word_bonus={bonus:<4} top1={:.1}% top5={:.1}%", 100.0 * top1 as f64 / n, 100.0 * top5 as f64 / n);
    }
}
