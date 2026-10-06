//! Deterministic synthetic validation, NOT real-writer accuracy. The glyph source is also used in training;
//! only the perturbation seed and frequency slice are reserved for validation. No personal learning or tuning.
//! Usage: handcheck GRAPHICS TEMPLATES NET [n=300] [skip=2000] [--level=3 | --geometry-only] [--line-ink]
//! Reports production fusion, template top-k consistency, and spatial grouping separately.
#[allow(dead_code)]
#[path = "handbench.rs"]
mod geometry;

use std::time::Instant;
use weave_dict::hand::{Recognizer, Stroke};
use weave_dict::handnet::{HandModels, HandNet};

fn bbox(ink: &[Stroke]) -> (f32, f32, f32, f32) {
    ink.iter()
        .flatten()
        .fold((f32::INFINITY, f32::INFINITY, f32::NEG_INFINITY, f32::NEG_INFINITY), |b, p| {
            (b.0.min(p.0), b.1.min(p.1), b.2.max(p.0), b.3.max(p.1))
        })
}

fn main() {
    let a: Vec<String> = std::env::args().skip(1).collect();
    let n: usize = a.get(3).and_then(|v| v.parse().ok()).unwrap_or(300);
    let skip: usize = a.get(4).and_then(|v| v.parse().ok()).unwrap_or(2000);
    let rec = Recognizer::from_bytes(&std::fs::read(&a[1]).unwrap()).unwrap();
    let net = HandNet::from_bytes(&std::fs::read(&a[2]).unwrap()).unwrap();
    let models = HandModels::new(Some(rec), Some(net));
    models.set_personal_enabled(false);
    let mut glyphs: Vec<_> = std::fs::read_to_string(&a[0])
        .unwrap()
        .lines()
        .filter_map(geometry::medians)
        .filter(|(c, _)| models.templates.as_ref().unwrap().contains(*c))
        .collect();
    glyphs.sort_by(|a, b| {
        models
            .templates
            .as_ref()
            .unwrap()
            .prior_of(b.0)
            .total_cmp(&models.templates.as_ref().unwrap().prior_of(a.0))
            .then_with(|| a.0.cmp(&b.0))
    });
    let glyphs: Vec<_> = glyphs.into_iter().skip(skip).take(n).collect();
    assert!(!glyphs.is_empty());
    println!(
        "synthetic geometry validation; shared training glyphs; seed=0xd1b54a32d192ed03 n={} skip={skip}",
        glyphs.len()
    );
    for level in [0, 2, 3, 4].into_iter().filter(|level| {
        !a.iter().any(|v| v == "--geometry-only")
            && a.iter()
                .find_map(|v| v.strip_prefix("--level="))
                .is_none_or(|v| v.parse::<u32>().ok() == Some(*level))
    }) {
        let mut rng = geometry::Rng(0xd1b5_4a32_d192_ed03);
        let (mut first, mut fifth, mut missed, mut inconsistent) = (0, 0, Vec::new(), 0);
        let (mut infer_ms, mut template_ms, mut template_first, mut template_thirty_first) = (Vec::new(), 0.0, 0, 0);
        for (ch, ink) in &glyphs {
            let ink = geometry::perturb(ink, &mut rng, level);
            let t = Instant::now();
            let got = models.recognize(&ink, 12);
            infer_ms.push(t.elapsed().as_secs_f64() * 1000.0);
            let rank = got.iter().position(|c| c == ch);
            first += (rank == Some(0)) as usize;
            fifth += rank.is_some_and(|r| r < 5) as usize;
            if !rank.is_some_and(|r| r < 5) && missed.len() < 20 {
                missed.push(*ch);
            }
            let rec = models.templates.as_ref().unwrap();
            let t = Instant::now();
            let one = rec.recognize(&ink, 1);
            template_ms += t.elapsed().as_secs_f64() * 1000.0;
            let many = rec.recognize(&ink, 30);
            template_first += (one.first().map(|v| v.0) == Some(*ch)) as usize;
            template_thirty_first += (many.first().map(|v| v.0) == Some(*ch)) as usize;
            inconsistent += (one.first().map(|v| v.0) != many.first().map(|v| v.0)) as usize;
        }
        let total = glyphs.len() as f64;
        let mean = infer_ms.iter().sum::<f64>() / total;
        infer_ms.sort_by(f64::total_cmp);
        println!("single level={level} n={} top1={:.2}% top5={:.2}% mean={mean:.2}ms p95={:.2}ms template_top1_mean={:.2}ms template_top1_accuracy={:.2}% template_top30_first_accuracy={:.2}% top1_vs_top30_disagreements={inconsistent} missed_top5={}",
            glyphs.len(), first as f64 * 100.0 / total, fifth as f64 * 100.0 / total,
            infer_ms[(infer_ms.len() * 95 / 100).min(infer_ms.len() - 1)], template_ms / total,
            template_first as f64 * 100.0 / total, template_thirty_first as f64 * 100.0 / total, missed.iter().collect::<String>());
    }
    // Spatial grouping is measured without a recognizer: exact group contents AND original pen order must match.
    let t = Instant::now();
    for gap in [0.10f32, 0.30] {
        for count in [2, 4] {
            let (mut correct, mut total, mut reordered, mut merged, mut split) = (0, 0, 0, 0, 0);
            let (mut exact_text, mut all_char_top5, mut line_ms, mut failed_lines) = (0, 0, 0.0, Vec::new());
            for start in 0..glyphs.len() {
                let mut expected = Vec::new();
                let mut left = 0.0;
                for i in 0..count {
                    let src = &glyphs[(start + i) % glyphs.len()].1;
                    let b = bbox(src);
                    let scale = 100.0 / (b.3 - b.1).max(b.2 - b.0).max(1.0);
                    let ink: Vec<Stroke> = src
                        .iter()
                        .map(|s| s.iter().map(|&(x, y)| ((x - b.0) * scale + left, (y - b.1) * scale)).collect())
                        .collect();
                    left += (b.2 - b.0) * scale + gap * 100.0;
                    expected.push(ink);
                }
                let all: Vec<Stroke> = expected.iter().flatten().cloned().collect();
                let groups = HandModels::line_groups(&all);
                correct += (groups == expected) as usize;
                reordered += (groups.len() == count && groups != expected) as usize;
                merged += (groups.len() < count) as usize;
                split += (groups.len() > count) as usize;
                total += 1;
                if a.iter().any(|v| v == "--line-ink") && start < 40 {
                    models.set_line_mode(true);
                    let begin = Instant::now();
                    let result = models.recognize_groups(&all, 12);
                    line_ms += begin.elapsed().as_secs_f64() * 1000.0;
                    let truth: Vec<char> = (0..count).map(|i| glyphs[(start + i) % glyphs.len()].0).collect();
                    let exact = result.len() == count && result.iter().zip(&truth).all(|(g, ch)| g.first().map(|v| v.0) == Some(*ch));
                    exact_text += exact as usize;
                    if !exact && failed_lines.len() < 10 {
                        failed_lines.push(format!("{}(groups={})", truth.iter().collect::<String>(), result.len()));
                    }
                    all_char_top5 +=
                        (result.len() == count && result.iter().zip(&truth).all(|(g, ch)| g.iter().take(5).any(|v| v.0 == *ch))) as usize;
                }
            }
            println!("line geometry gap={gap:.2} chars={count} n={total} exact_groups_and_order={:.2}% reordered_or_wrong_boundary={reordered} merged={merged} oversplit={split}", correct as f64 * 100.0 / total as f64);
            if a.iter().any(|v| v == "--line-ink") {
                let evaluated = glyphs.len().min(40) as f64;
                println!("line recognition gap={gap:.2} chars={count} n={} exact_top1={:.2}% all_char_top5_present={:.2}% mean={:.2}ms/line failed={} (independent ink scores; no context)",
                    evaluated as usize, exact_text as f64 * 100.0 / evaluated, all_char_top5 as f64 * 100.0 / evaluated, line_ms / evaluated, failed_lines.join(","));
            }
        }
    }
    let oversplit: Vec<_> = glyphs
        .iter()
        .filter(|(_, ink)| HandModels::line_groups(ink).len() > 1)
        .map(|v| v.0)
        .collect();
    println!(
        "single geometry n={} oversplit={} chars={} line_geometry_and_optional_recognition_ms={:.2}",
        glyphs.len(),
        oversplit.len(),
        oversplit.iter().take(30).collect::<String>(),
        t.elapsed().as_secs_f64() * 1000.0
    );
    models.set_line_mode(true);
    let mut model_oversplit = Vec::new();
    for (ch, ink) in glyphs.iter().filter(|(ch, _)| oversplit.contains(ch)) {
        if models.recognize_groups(ink, 12).len() > 1 {
            model_oversplit.push(*ch);
        }
    }
    println!(
        "single model-aware grouping n={} oversplit={} chars={}",
        glyphs.len(),
        model_oversplit.len(),
        model_oversplit.iter().collect::<String>()
    );
}
