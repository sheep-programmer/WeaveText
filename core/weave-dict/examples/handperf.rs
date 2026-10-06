//! Component timings and repeated/incremental requests on synthetic ink, NOT writer accuracy.
//! Usage: handperf GRAPHICS TEMPLATES NET [n=40] [skip=2000]
#[allow(dead_code)]
#[path = "handbench.rs"]
mod geometry;
use std::time::Instant;
use weave_dict::hand::{Recognizer, Stroke};
use weave_dict::handnet::{HandModels, HandNet};

fn main() {
    let a: Vec<_> = std::env::args().skip(1).collect();
    let n: usize = a.get(3).and_then(|s| s.parse().ok()).unwrap_or(40);
    let skip: usize = a.get(4).and_then(|s| s.parse().ok()).unwrap_or(2000);
    let t = Instant::now();
    let rec = Recognizer::from_bytes(&std::fs::read(&a[1]).unwrap()).unwrap();
    let template_load = t.elapsed().as_secs_f64() * 1000.0;
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
    let mut rng = geometry::Rng(0xd1b5_4a32_d192_ed03);
    let (mut tpl, mut plain, mut robust, mut fusion, mut repeated) = (0.0, 0.0, 0.0, 0.0, 0.0);
    let mut total = 0;
    for (_, ink) in glyphs.iter().skip(skip).take(n) {
        let ink = geometry::perturb(ink, &mut rng, 4);
        let t = Instant::now();
        std::hint::black_box(models.templates.as_ref().unwrap().recognize(&ink, 30));
        tpl += t.elapsed().as_secs_f64() * 1000.0;
        let t = Instant::now();
        std::hint::black_box(models.net.as_ref().unwrap().recognize(&ink, 30));
        plain += t.elapsed().as_secs_f64() * 1000.0;
        let t = Instant::now();
        std::hint::black_box(models.net.as_ref().unwrap().recognize_robust(&ink, 30));
        robust += t.elapsed().as_secs_f64() * 1000.0;
        let t = Instant::now();
        let cold = models.recognize(&ink, 12);
        fusion += t.elapsed().as_secs_f64() * 1000.0;
        let t = Instant::now();
        assert_eq!(models.recognize(&ink, 12), cold);
        repeated += t.elapsed().as_secs_f64() * 1000.0;
        total += 1;
    }
    let f = total as f64;
    println!("synthetic level4 n={total} skip={skip} template_load={template_load:.2}ms template30={:.2}ms cnn_plain={:.2}ms cnn_robust={:.2}ms fusion_cold={:.2}ms fusion_same_ink_repeat={:.2}ms", tpl/f, plain/f, robust/f, fusion/f, repeated/f);
    models.set_line_mode(true);
    for gap in [10.0f32, 30.0] {
        let (mut ms, mut calls, mut elapsed_final, mut final_calls) = (0.0, 0, 0.0, 0);
        for start in (skip..skip + 8).step_by(2) {
            let mut all = Vec::new();
            let mut left = 0.0;
            for i in 0..2 {
                let src = &glyphs[start + i].1;
                let b = src
                    .iter()
                    .flatten()
                    .fold((f32::INFINITY, f32::INFINITY, f32::NEG_INFINITY, f32::NEG_INFINITY), |b, p| {
                        (b.0.min(p.0), b.1.min(p.1), b.2.max(p.0), b.3.max(p.1))
                    });
                let scale = 100.0 / (b.2 - b.0).max(b.3 - b.1).max(1.0);
                for s in src {
                    let stroke: Stroke = s.iter().map(|&(x, y)| ((x - b.0) * scale + left, (y - b.1) * scale)).collect();
                    all.push(stroke);
                    let t = Instant::now();
                    std::hint::black_box(models.recognize_groups(&all, 12));
                    ms += t.elapsed().as_secs_f64() * 1000.0;
                    calls += 1;
                }
                left += (b.2 - b.0) * scale + gap;
            }
            let t = Instant::now();
            std::hint::black_box(models.recognize_groups(&all, 12));
            elapsed_final += t.elapsed().as_secs_f64() * 1000.0;
            final_calls += 1;
        }
        println!(
            "incremental two-char gap={gap:.0} requests={calls} mean={:.2}ms total={ms:.2}ms completed_repeat_mean={:.2}ms",
            ms / calls as f64,
            elapsed_final / final_calls as f64
        );
    }
    let large: Vec<Stroke> = (0..256)
        .map(|s| {
            (0..4096)
                .map(|p| {
                    let f = p as f32 / 4095.0;
                    let left = (s / 64) as f32 * 160.0;
                    if s % 2 == 0 {
                        (left + 100.0 * f, (s % 8) as f32 / 7.0 * 100.0)
                    } else {
                        (left + 50.0, 100.0 * f)
                    }
                })
                .collect()
        })
        .collect();
    let t = Instant::now();
    for _ in 0..12 {
        assert_eq!(HandModels::line_groups(&large).len(), 4);
    }
    println!(
        "geometry stress strokes=256 points=1048576 repetitions=12 mean={:.2}ms (bounds/grouping only, no classification)",
        t.elapsed().as_secs_f64() * 1000.0 / 12.0
    );
}
