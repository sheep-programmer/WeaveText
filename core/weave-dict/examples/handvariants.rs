//! Check aspect-variant recognition on an external ink evaluation file before changing the shipped recognizer.
//! Usage: handvariants NET TEMPLATES SAMPLES LIMIT
use std::collections::HashMap;
use weave_dict::hand::{Recognizer, Stroke};
use weave_dict::handnet::{fuse, HandNet};

fn main() {
    let a: Vec<String> = std::env::args().skip(1).collect();
    let net = HandNet::from_bytes(&std::fs::read(&a[0]).unwrap()).unwrap();
    let templates = Recognizer::from_bytes(&std::fs::read(&a[1]).unwrap()).unwrap();
    let text = std::fs::read_to_string(&a[2]).unwrap();
    let limit: usize = a[3].parse().unwrap();
    let variants = [(1.15, 1.0, 0.0f32), (1.0, 1.15, 0.0), (1.0, 1.0, 0.08), (1.0, 1.0, -0.08)];
    let mut counts = [(0usize, 0usize); 9];
    let mut total = 0;
    let skip: usize = a.get(4).and_then(|s| s.parse().ok()).unwrap_or(0);
    for line in text.lines().skip(skip).take(limit) {
        let Some((label, ink)) = line.split_once('\t') else { continue };
        let ch = label.chars().next().unwrap();
        let strokes: Vec<Stroke> = ink.split(';').map(|s| s.split(' ').filter_map(|p| p.split_once(','))
            .filter_map(|(x, y)| Some((x.parse::<f32>().ok()?, y.parse::<f32>().ok()?))).collect()).filter(|s: &Stroke| !s.is_empty()).collect();
        let tmpl = templates.recognize(&strokes, 30);
        let original = net.recognize(&strokes, 30);
        let all: Vec<_> = variants.iter().map(|&(sx, sy, angle)| {
            let shifted: Vec<Stroke> = strokes.iter().map(|s| s.iter().map(|&(x,y)|
                (sx * (x * angle.cos() - y * angle.sin()), sy * (x * angle.sin() + y * angle.cos()))).collect()).collect();
            net.recognize(&shifted, 30)
        }).collect();
        let cases = [(0.0, 0usize), (0.25, 2), (0.5, 2), (0.25, 4), (0.5, 4), (0.75, 2), (1.0, 2), (0.25, 2), (0.25, 2)];
        for (i, (weight, n)) in cases.iter().enumerate() {
            let threshold = if i == 7 { 0.8 } else if i == 8 { 0.6 } else { 2.0 };
            let (weight, n) = if original.first().is_some_and(|p| p.1 >= threshold) { (0.0, 0) } else { (*weight, *n) };
            let mut probabilities = HashMap::new();
            for &(c,p) in &original { probabilities.insert(c, p * (1.0 - weight)); }
            if n > 0 {
                for list in all.iter().take(n) {
                    for &(c,p) in list { *probabilities.entry(c).or_insert(0.0) += p * weight / n as f32; }
                }
            }
            let mut probabilities: Vec<_> = probabilities.into_iter().collect();
            probabilities.sort_by(|a,b| b.1.total_cmp(&a.1));
            probabilities.truncate(30);
            let got = fuse(&probabilities, &tmpl, |c| templates.prior_of(c), |c| templates.contains(c), 10);
            let rank = got.iter().position(|&c| c == ch);
            counts[i].0 += usize::from(rank == Some(0));
            counts[i].1 += usize::from(rank.is_some_and(|j| j < 5));
        }
        total += 1;
    }
    for (i, &(top1, top5)) in counts.iter().enumerate() {
        println!("case {i}: n={total} top1={:.1}% top5={:.1}%", 100.0 * top1 as f64 / total as f64, 100.0 * top5 as f64 / total as f64);
    }
}
